#!/usr/bin/env python3
"""Export normalized metric products. Estimation belongs to the C++ geometry process."""
from __future__ import annotations
import json
import math
import subprocess
import sys
import time
from pathlib import Path
import numpy as np


def summary(values):
    a = np.asarray(values, dtype=float)
    if not len(a):
        return {'count': 0, 'mean': None, 'median': None, 'p95': None, 'max': None}
    return {'count': len(a), 'mean': float(a.mean()), 'median': float(np.median(a)),
            'p95': float(np.quantile(a, .95)), 'max': float(a.max())}


def world_from_optical(camera):
    m = np.asarray(camera['worldToOpticalColumnMajor'], dtype=float).reshape((4, 4), order='F')
    if not np.isfinite(m).all() or not np.allclose(m[3], [0, 0, 0, 1], atol=1e-8):
        raise ValueError('Malformed optical-from-SfM pose: ' + camera['name'])
    r, t = m[:3, :3], m[:3, 3]
    if not np.allclose(r.T @ r, np.eye(3), atol=1e-6) or abs(np.linalg.det(r) - 1) > 1e-6:
        raise ValueError('Improper COLMAP rotation: ' + camera['name'])
    result = np.eye(4)
    result[:3, :3] = r.T
    result[:3, 3] = -r.T @ t
    return result


def depth_validation(run, inputs, cameras, points):
    """Measured H maps CPU pixels into depth pixels; no resolution-ratio shortcut."""
    by_id = {c['frameId']: c for c in cameras}
    samples, skipped, provenance = {}, {}, []
    cache = {}
    def skip(reason):
        skipped[reason] = skipped.get(reason, 0) + 1
    # Read each map once; arrays use the capture LE uint16 encoding, confidence uint8.
    for f in inputs:
        for d in f.get('depth', []):
            source = d.get('source', 'UNKNOWN')
            provenance.append({'frameId': f['frameId'], **d})
            if d.get('availability') != 'AVAILABLE' or not d.get('depthPath'):
                skip('unavailable_or_not_saved'); continue
            ar_clock = f.get('arClockDomain', f.get('clockDomain'))
            association = 'exact-same-source-clock'
            if d.get('clockDomain') != ar_clock:
                # Documented ARCore current-depth test compares these provider timestamps.
                # This is a frame association, never a global clock conversion.
                if (ar_clock, d.get('clockDomain')) == ('ARCORE_FRAME', 'ARCORE_DEPTH') and source in ('ARCORE_RAW', 'ARCORE_SMOOTHED'):
                    association = 'ARCore-provider-current-depth-timestamp-equality'
                else:
                    skip('depth_AR_clock_mapping_unavailable'); continue
            provenance[-1]['derivedFrameAssociationPolicy'] = association
            delta = int(d['timestampNs']) - int(f.get('arTimestampNs', f['timestampNs']))
            # Raw depth can legitimately repeat an earlier measurement. Such maps must not
            # be compared to a later optimized camera without an association model.
            if delta != 0:
                skip('depth_timestamp_not_current_AR_frame'); continue
            if len(d.get('cpuToDepthColumnMajor', [])) != 9:
                skip('missing_measured_pixel_mapping'); continue
            if d.get('depthEncoding', 'U16_LE;millimeters;axial-Z') != 'U16_LE;millimeters;axial-Z':
                skip('unsupported_depth_encoding'); continue
            if d.get('confidencePath') and d.get('confidenceEncoding', 'U8;0-invalid;255-highest') != 'U8;0-invalid;255-highest':
                skip('unsupported_confidence_encoding'); continue
            w, h = int(d['width']), int(d['height'])
            path = run / d['depthPath']
            a = np.fromfile(path, dtype='<u2')
            if len(a) != w*h:
                raise ValueError('Invalid depth byte length: ' + str(path))
            conf = None
            if d.get('confidencePath'):
                confidence_clock = d.get('confidenceClockDomain')
                paired = (d.get('clockDomain'), confidence_clock) == ('ARCORE_DEPTH', 'ARCORE_DEPTH_CONFIDENCE') and source == 'ARCORE_RAW'
                if confidence_clock is None or (confidence_clock != d.get('clockDomain') and not paired):
                    skip('confidence_depth_clock_mapping_unavailable'); continue
                provenance[-1]['derivedConfidenceAssociationPolicy'] = 'ARCore-provider-paired-raw-confidence' if paired else 'exact-same-source-clock'
                if d.get('confidenceTimestampNs') is None or int(d['confidenceTimestampNs']) != int(d['timestampNs']):
                    skip('confidence_depth_timestamp_mismatch'); continue
                conf = np.fromfile(run / d['confidencePath'], dtype='u1')
                if len(conf) != w*h:
                    raise ValueError('Invalid confidence byte length: ' + d['confidencePath'])
            H = np.asarray(d['cpuToDepthColumnMajor'], dtype=float).reshape((3, 3), order='F')
            cache[(f['frameId'], source)] = (a.reshape(h, w), None if conf is None else conf.reshape(h, w), H, float(d['unitMeters']))
    for point in points:
        X = np.array(point['position'])
        for obs in point['observations']:
            c = by_id.get(obs['frameId'])
            if c is None:
                continue
            m = np.asarray(c['worldFromOpticalColumnMajor']).reshape((4, 4), order='F')
            pc = m[:3, :3].T @ (X - m[:3, 3])
            if pc[2] <= 0:
                skip('point_behind_camera'); continue
            # Use original observation pixel, not a pose-dependent rounded reprojection.
            for source in ('ARCORE_RAW', 'ARCORE_SMOOTHED'):
                cached = cache.get((obs['frameId'], source))
                if cached is None:
                    continue
                a, conf, H, unit = cached
                pix = H @ np.array([obs['u'], obs['v'], 1.])
                if abs(pix[2]) < 1e-12:
                    skip('singular_depth_mapping'); continue
                u, v = np.floor(pix[:2]/pix[2]).astype(int)
                if not (0 <= v < a.shape[0] and 0 <= u < a.shape[1]):
                    skip('outside_depth_map'); continue
                z = float(a[v, u])*unit
                if z <= 0:
                    skip('invalid_zero_depth'); continue
                if conf is not None and conf[v, u] < 128:
                    skip('raw_confidence_below_128_of_255'); continue
                entry = samples.setdefault(source, {'absolute': [], 'signed': [], 'relative': [], 'ratio': []})
                entry['absolute'].append(abs(float(pc[2])-z)); entry['signed'].append(float(pc[2])-z)
                entry['relative'].append(abs(float(pc[2])-z)/z); entry['ratio'].append(float(pc[2])/z)
    return {'policy': 'axial optical Z; measured CPU-to-depth H; exact AR timestamp; raw confidence>=128; zero invalid',
            'uncertainty': 'AR-estimated depth; independent sanity check, not hardware-LiDAR accuracy',
            'sources': {s: {'absoluteResidualMeters': summary(v['absolute']), 'signedResidualMeters': summary(v['signed']),
                            'relativeResidual': summary(v['relative']), 'sparseToDepthRatio': summary(v['ratio'])} for s, v in samples.items()},
            'skipped': skipped, 'provenance': provenance}


def viewer(run, trajectories, diagnostic):
    # Numeric data only, embedded JSON cannot contain markup or user-controlled strings.
    data = {name: [p['position'] for p in points] for name, points in trajectories.items()}
    html = '''<!doctype html><meta charset="utf-8"><title>Myndhamr sparse trajectory</title>
<style>body{font:16px system-ui;background:#101828;color:#e4e7ec;margin:24px}svg{width:100%;height:70vh;background:#17243a}label{margin-right:16px}.note{color:#b4c4dc}polyline{fill:none;stroke-width:2}</style>
<h1>Capture and reconstructed camera trajectories</h1><p class="note">Capture and aligned use the same metric axes. Raw SfM uses arbitrary units in its own panel. Compare numerical residuals in diagnostics.json.</p>
<select id="plane"><option value="0,1">XY</option><option value="0,2">XZ</option><option value="1,2">YZ</option></select>
<label><input id="capture" type="checkbox" checked>Capture (green)</label><label><input id="aligned" type="checkbox" checked>Aligned (blue)</label>
<svg id="plot" viewBox="0 0 1000 650"></svg><p id="stats"></p><script>
const D=DATA;const stats=STATS;document.getElementById('stats').textContent=stats;
function draw(){let ax=document.getElementById('plane').value.split(',').map(Number);let s=document.getElementById('plot');s.innerHTML='';
function panel(keys,x0,width,title){let points=keys.flatMap(k=>D[k]);if(!points.length)return;let x=points.map(p=>p[ax[0]]),y=points.map(p=>p[ax[1]]);let xmin=Math.min(...x),xmax=Math.max(...x),ymin=Math.min(...y),ymax=Math.max(...y);let scale=Math.min((width-50)/Math.max(xmax-xmin,1e-6),540/Math.max(ymax-ymin,1e-6));
let label=document.createElementNS('http://www.w3.org/2000/svg','text');label.setAttribute('x',x0+25);label.setAttribute('y',35);label.setAttribute('fill','#eee');label.textContent=title+' axes '+ax.join(',')+'; span '+(xmax-xmin).toFixed(3)+' / '+(ymax-ymin).toFixed(3);s.appendChild(label);
keys.forEach(k=>{if(document.getElementById(k)&&!document.getElementById(k).checked)return;let line=document.createElementNS('http://www.w3.org/2000/svg','polyline');line.setAttribute('stroke',{capture:'#55d99b',aligned:'#65a9ff',raw:'#ffad70'}[k]);line.setAttribute('points',D[k].map(p=>[(p[ax[0]]-xmin)*scale+x0+25,610-(p[ax[1]]-ymin)*scale].join(',')).join(' '));s.appendChild(line)})}
panel(['capture','aligned'],0,650,'Capture world (metres)');panel(['raw'],650,350,'Raw SfM (arbitrary units)')};document.querySelectorAll('input,select').forEach(x=>x.onchange=draw);draw();</script>'''
    detail = 'registered {}/{}; scale {:.9g}; median position residual {:.6g} m'.format(diagnostic['registeredImages'], diagnostic['inputEligibleImages'], diagnostic['alignment']['scale'], diagnostic['alignment']['inlierResidualMeters']['median'])
    (run/'trajectory.html').write_text(html.replace('DATA', json.dumps(data)).replace('STATS', json.dumps(detail)))


def export_metric(run: Path, align_executable: Path, config: dict):
    started = time.monotonic()
    inputs = json.loads((run/'input.json').read_text())
    raw = json.loads((run/'colmap'/'raw-model.json').read_text())
    diagnostics = json.loads((run/'adapter-diagnostics.json').read_text())
    frames = {f['frameId']: f for f in inputs['images']}
    frame_order = {f['frameId']: i for i, f in enumerate(inputs['images'])}
    cameras = sorted(raw['cameras'], key=lambda c: frame_order[c['frameId']])
    positions = [world_from_optical(c) for c in cameras]
    correspondences = [(m[:3, 3], np.array(frames[c['frameId']]['worldFromCameraColumnMajor']).reshape((4, 4), order='F')[:3, 3]) for c, m in zip(cameras, positions)]
    request = ''.join(' '.join(format(float(v), '.17g') for v in [*s, *t])+'\n' for s, t in correspondences)
    (run/'alignment-correspondences.txt').write_text(request)
    native_started = time.monotonic()
    proc = subprocess.run([str(align_executable), str(config['poseThresholdMeters']), str(config['minimumPriorInlierFraction']), str(config['minimumMetricBaselineMeters'])], input=request, text=True, capture_output=True, timeout=config['timeoutSeconds'])
    native_elapsed = time.monotonic()-native_started
    (run/'alignment.log').write_text(proc.stderr)
    if proc.returncode:
        raise ValueError('Metric alignment failed: ' + proc.stderr.strip())
    lines = proc.stdout.splitlines(); header = [float(v) for v in lines[0].split()]
    if len(header) != 14 or len(lines) != len(cameras)+1:
        raise ValueError('Malformed native alignment output')
    scale, R, t = header[0], np.array(header[1:10]).reshape(3, 3), np.array(header[10:13])
    residuals, inliers = zip(*[(float(row.split()[0]), bool(int(row.split()[1]))) for row in lines[1:]])
    aligned, pose_residuals = [], []
    D = np.diag([1., -1., -1.])
    for c, m, residual, inlier in zip(cameras, positions, residuals, inliers):
        metric = np.eye(4); metric[:3, :3] = R @ m[:3, :3]; metric[:3, 3] = scale * R @ m[:3, 3] + t
        capture = np.array(frames[c['frameId']]['worldFromCameraColumnMajor']).reshape((4, 4), order='F')
        expected = capture[:3, :3] @ D
        angle = math.degrees(math.acos(np.clip((np.trace(expected.T @ metric[:3, :3])-1)/2, -1, 1)))
        aligned.append({**c, 'worldFromOpticalColumnMajor': metric.flatten(order='F').tolist()})
        pose_residuals.append({'frameId': c['frameId'], 'positionResidualMeters': residual, 'orientationResidualDegrees': angle, 'priorInlier': inlier})
    angles = [r['orientationResidualDegrees'] for r in pose_residuals if r['priorInlier']]
    alignment = {'scale': scale, 'rotationWorldSfmRowMajor': R.flatten().tolist(), 'translationWorldSfmMeters': t.tolist(),
                 'equation': 'p_world = scale * R_world_sfm * p_sfm + t_world_sfm',
                 'inlierCount': sum(inliers), 'outlierCount': len(inliers)-sum(inliers), 'sourceCondition': header[13],
                 'allResidualMeters': summary(residuals), 'inlierResidualMeters': summary([r for r, i in zip(residuals, inliers) if i]),
                 'inlierOrientationResidualDegrees': summary(angles), 'perFrame': pose_residuals}
    diagnostics['alignment'] = alignment
    diagnostics['excludedImages'] = inputs.get('excluded', inputs.get('exclusions', []))
    diagnostics['sourceManifestSha256'] = inputs['sourceManifestSha256']
    diagnostics['pairGraph'] = json.loads((run/'pairs.json').read_text()).get('statistics', {})
    diagnostics['metricConfiguration'] = config
    if float(np.median(angles)) > config['maximumMedianOrientationResidualDegrees']:
        diagnostics['status'] = 'alignment_failed'
        diagnostics['error'] = 'Position consensus disagrees with capture orientations'
        (run/'diagnostics.json').write_text(json.dumps(diagnostics, indent=2, allow_nan=False)+'\n')
        raise ValueError(diagnostics['error'])
    points = [{**p, 'position': (scale*R @ np.asarray(p['position'])+t).tolist()} for p in raw['points']]
    diagnostics['depthValidation'] = depth_validation(run, inputs['images'], aligned, points)
    diagnostics['status'] = 'metric_aligned' if raw['componentCount'] == 1 else 'disconnected_reconstruction'
    diagnostics['registrationAcceptancePassed'] = diagnostics['registeredFraction'] > .95 and raw['componentCount'] == 1
    diagnostics.setdefault('stageRuntimeSeconds', {})['nativeAlignment'] = native_elapsed
    diagnostics['stageRuntimeSeconds']['metric_export'] = time.monotonic()-started
    (run/'aligned-model.json').write_text(json.dumps({'schemaVersion': 1, 'units': 'meters', 'frame': 'capture-world', 'alignment': alignment, 'cameras': aligned, 'points': points}, allow_nan=False)+'\n')
    trajectories = {'capture': [{'frameId': f['frameId'], 'position': np.asarray(f['worldFromCameraColumnMajor']).reshape((4, 4), order='F')[:3, 3].tolist()} for f in inputs['images']],
                    'raw': [{'frameId': c['frameId'], 'position': m[:3, 3].tolist()} for c, m in zip(cameras, positions)],
                    'aligned': [{'frameId': c['frameId'], 'position': np.asarray(c['worldFromOpticalColumnMajor']).reshape((4, 4), order='F')[:3, 3].tolist()} for c in aligned]}
    (run/'trajectory.json').write_text(json.dumps({'schemaVersion': 1, 'trajectories': trajectories, 'residuals': pose_residuals})+'\n')
    with (run/'sparse-metric.ply').open('w') as out:
        out.write('ply\nformat ascii 1.0\ncomment capture-world meters\nelement vertex '+str(len(points))+'\nproperty double x\nproperty double y\nproperty double z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n')
        for p in points:
            out.write(' '.join(map(str, p['position']+p['color']))+'\n')
    viewer(run, trajectories, diagnostics)
    (run/'diagnostics.json').write_text(json.dumps(diagnostics, indent=2, allow_nan=False)+'\n')
    if raw['componentCount'] != 1:
        raise ValueError('Disconnected reconstruction: independent components are preserved; no merged metric scene')
    return diagnostics


def main():
    run = Path(sys.argv[1]); align = Path(sys.argv[2])
    config = json.loads((run/'metric-config.json').read_text())
    try:
        export_metric(run, align, config)
        return 0
    except Exception as error:
        target = run/'diagnostics.json'
        diagnostics = json.loads(target.read_text()) if target.exists() else json.loads((run/'adapter-diagnostics.json').read_text())
        diagnostics['status'] = 'failed'; diagnostics['error'] = str(error)
        diagnostics['metricConfiguration'] = config
        target.write_text(json.dumps(diagnostics, indent=2, allow_nan=False)+'\n')
        print(str(error), file=sys.stderr); return 2

if __name__ == '__main__':
    sys.exit(main())
