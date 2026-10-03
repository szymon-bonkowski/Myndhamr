#!/usr/bin/env python3
"""Versioned derived object stages; raw capture and sparse products stay immutable."""
from __future__ import annotations
import argparse
import hashlib
import inspect
import json
import os
from pathlib import Path
import resource
import shutil
import sys
import sysconfig
import time


def bootstrap_cuda():
    # CUDA wheels install libraries outside the dynamic loader's standard paths.
    lib = Path(sysconfig.get_paths()['purelib']) / 'nvidia'
    paths = [str(p) for p in (lib/'cuda_runtime/lib', lib/'curand/lib') if p.is_dir()]
    if paths and os.environ.get('MYNDHAMR_CUDA_LOADER') != '1':
        env = dict(os.environ, MYNDHAMR_CUDA_LOADER='1')
        env['LD_LIBRARY_PATH'] = ':'.join(paths + [env.get('LD_LIBRARY_PATH', '')])
        os.execve(sys.executable, [sys.executable, *sys.argv], env)


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda: f.read(1024*1024), b''): h.update(block)
    return h.hexdigest()


def write_json(path, data):
    temporary = path.with_suffix(path.suffix+'.tmp')
    temporary.write_text(json.dumps(data, indent=2, allow_nan=False)+'\n')
    temporary.replace(path)


def read_json(path):
    return json.loads(path.read_text())


def configuration(config):
    allowed = {'schemaVersion','maxImageSize','radiiMeters','maxEdgeMeters','minComponentFaces','formats','expectedSourceManifestSha256'}
    if set(config)-allowed or config.get('schemaVersion') != 1: raise ValueError('Unsupported object configuration version or keys')
    result = {'schemaVersion':1, 'maxImageSize':1600,'radiiMeters':[.005,.01], 'maxEdgeMeters':.02,
              'minComponentFaces':0,'formats':['ply','obj','glb'], **config}
    if not isinstance(result['maxImageSize'],int) or not 32 <= result['maxImageSize'] <= 16000: raise ValueError('Invalid maxImageSize')
    if not isinstance(result['minComponentFaces'],int) or result['minComponentFaces'] < 0: raise ValueError('Invalid component threshold')
    import math
    radii = result['radiiMeters']
    if not radii or any(not math.isfinite(v) or v<=0 for v in radii) or radii != sorted(set(radii)): raise ValueError('Radii must be finite, positive and increasing metres')
    if not math.isfinite(result['maxEdgeMeters']) or result['maxEdgeMeters']<=0: raise ValueError('Invalid maximum edge')
    if not result['formats'] or len(set(result['formats'])) != len(result['formats']) or set(result['formats'])-{'ply','obj','glb'}: raise ValueError('Invalid formats')
    return result


def sparse_inputs(sparse, config):
    import numpy as np
    from mesh_geometry import transform_cloud
    model = read_json(sparse/'aligned-model.json')
    inputs = read_json(sparse/'input.json')
    diagnostic = read_json(sparse/'diagnostics.json')
    if model.get('schemaVersion') != 1 or inputs.get('schemaVersion') != 1: raise ValueError('Unsupported sparse artifact version')
    if model.get('units') != 'meters' or model.get('frame') != 'capture-world': raise ValueError('Sparse frame/units incompatible')
    if diagnostic.get('status') != 'metric_aligned' or not diagnostic.get('registrationAcceptancePassed'): raise ValueError('Sparse reconstruction is not an accepted connected metric reconstruction')
    if config.get('expectedSourceManifestSha256') and config['expectedSourceManifestSha256'] != inputs['sourceManifestSha256']: raise ValueError('Sparse result belongs to a different raw capture manifest')
    if len(model['cameras']) < 3: raise ValueError('At least three registered views required')
    transform_cloud(np.array([[0.,0.,0.]]),np.array([[1.,0.,0.]]),model['alignment'])
    files = [sparse/p for p in ('aligned-model.json','input.json','diagnostics.json','colmap/raw-model.json')]
    model_path = sparse/'colmap/models/component-001'
    files += sorted(model_path.glob('*.bin'))
    if len(list(model_path.glob('*.bin'))) < 3: raise ValueError('Incomplete COLMAP sparse model')
    registered = {c['name'] for c in model['cameras']}
    if len(registered)!=len(model['cameras']): raise ValueError('Duplicate registered camera names')
    images = {i['name']:i for i in inputs['images']}
    if not registered <= images.keys(): raise ValueError('Registered camera missing source calibration')
    for name in sorted(registered):
        path = sparse/'images'/name
        if not path.is_file(): raise ValueError('Missing source image: '+name)
        from PIL import Image
        c=next(c for c in model['cameras'] if c['name']==name)
        source=images[name]
        if any(c[k]!=source[k] for k in ('frameId','width','height','fx','fy','cx','cy')): raise ValueError('Normalized/aligned calibration disagreement: '+name)
        with Image.open(path) as image:
            image.verify()
            if image.size!=(c['width'],c['height']): raise ValueError('Source image dimensions disagree: '+name)
        files.append(path)
    return model, inputs, model_path, {str(p.relative_to(sparse)):digest(p) for p in files}


def check_native_sparse(sparse, aligned, model_path):
    import numpy as np
    import pycolmap
    from mesh_geometry import transform_cloud
    native = pycolmap.Reconstruction(str(model_path))
    cameras = {c['name']:c for c in aligned['cameras']}
    if {im.name for im in native.images.values()} != set(cameras): raise ValueError('COLMAP/aligned registered camera sets disagree')
    scale = aligned['alignment']['scale']
    for im in native.images.values():
        c = cameras[im.name]
        center = im.projection_center()
        metric,_ = transform_cloud(np.array([center]), np.array([[1.,0.,0.]]),aligned['alignment'])
        expected = np.array(c['worldFromOpticalColumnMajor']).reshape(4,4,order='F')
        if not np.isfinite(expected).all() or not np.allclose(expected[3],[0,0,0,1],atol=1e-12,rtol=0): raise ValueError('Malformed aligned optical pose')
        if not np.allclose(metric[0],expected[:3,3],atol=1e-7,rtol=0): raise ValueError('Sparse transform direction/camera disagreement: '+im.name)
        sfm_rotation = im.cam_from_world().rotation.matrix()
        R = np.array(aligned['alignment']['rotationWorldSfmRowMajor']).reshape(3,3)
        if not np.allclose(R@sfm_rotation.T,expected[:3,:3],atol=1e-7,rtol=0): raise ValueError('Sparse rotation disagreement')
        camera = native.cameras[im.camera_id]
        if camera.model_name!='PINHOLE' or camera.width!=c['width'] or camera.height!=c['height']: raise ValueError('Sparse camera model/dimensions disagreement')
        if not np.allclose(camera.params,np.array([c['fx'],c['fy'],c['cx'],c['cy']]),atol=1e-8,rtol=0): raise ValueError('Sparse camera calibration disagreement')
    raw = read_json(sparse/'colmap/raw-model.json')
    # Every observed sparse point must follow the same metric transform.
    raw_points = {p['id']:p for p in raw['points']}
    for p in aligned['points']:
        source = raw_points[p['id']]
        q,_ = transform_cloud(np.array([source['position']]),np.array([[1.,0.,0.]]),aligned['alignment'])
        if not np.allclose(q[0],p['position'],atol=1e-7,rtol=0): raise ValueError('Aligned sparse point scale/axis disagreement')
    return native


def dense_stage(sparse, run, aligned, model_path, config):
    import numpy as np
    import pycolmap
    import open3d as o3d
    from mesh_geometry import transform_cloud
    if not pycolmap.has_cuda: raise RuntimeError('COLMAP dense stereo requires CUDA; install requirements-cuda.txt and a supported NVIDIA GPU')
    check_native_sparse(sparse,aligned,model_path)
    workspace = run/'dense-workspace'
    if workspace.exists():
        workspace.rename(run/('failed-dense-workspace-'+str(time.time_ns())))
    workspace.mkdir()
    # Existence-only native skip logic is unsafe for partial, unhashed maps.
    times = {}
    start=time.monotonic()
    options=pycolmap.UndistortCameraOptions(); options.max_image_size=config['maxImageSize']
    pycolmap.undistort_images(str(workspace),str(model_path),str(sparse/'images'),num_patch_match_src_images=20,undistort_options=options,num_threads=4)
    times['undistort']=time.monotonic()-start
    patch=pycolmap.PatchMatchOptions(); patch.max_image_size=config['maxImageSize']; patch.gpu_index='0'; patch.cache_size=2.; patch.geom_consistency=True; patch.num_threads=4
    start=time.monotonic(); pycolmap.patch_match_stereo(str(workspace),options=patch); times['stereo']=time.monotonic()-start
    fusion=pycolmap.StereoFusionOptions(); fusion.num_threads=4; fusion.cache_size=2.; fusion.use_cache=True; fusion.max_image_size=config['maxImageSize']
    start=time.monotonic(); pycolmap.stereo_fusion(str(workspace/'fused.ply'),str(workspace),options=fusion,output_type='ply'); times['fusion']=time.monotonic()-start
    cloud=o3d.io.read_point_cloud(str(workspace/'fused.ply'))
    p,n=np.asarray(cloud.points),np.asarray(cloud.normals)
    if len(p)<3 or n.shape!=p.shape: raise ValueError('Dense fusion empty or lacks observed normals')
    points,normals=transform_cloud(p,n,aligned['alignment'])
    np.savez(run/'dense.npz',points=points,normals=normals)
    cloud.points=o3d.utility.Vector3dVector(points); cloud.normals=o3d.utility.Vector3dVector(normals)
    if not o3d.io.write_point_cloud(str(run/'dense-metric.ply'),cloud): raise ValueError('Dense PLY write failed')
    return {'registeredImages':len(aligned['cameras']),'densePoints':len(points),'boundsMeters':[points.min(0).tolist(),points.max(0).tolist()],
            'dimensionsMeters':np.ptp(points,axis=0).tolist(),'stageRuntimeSeconds':times,'patchMatchOptions':patch.todict(),
            'fusionOptions':{k:str(v) if isinstance(v,Path) else v for k,v in fusion.todict().items() if k!='bounding_box'},
            'quality':'geometric-consistency filtered COLMAP fusion; no per-point probability supplied',
            'normalPolicy':'fused SfM-world surface normals rotated by proper R_world_sfm; no scale/translation applied'}


def sanity(stats, aligned):
    import numpy as np
    dims=np.array(stats['dimensionsMeters'])
    positions=np.array([np.array(c['worldFromOpticalColumnMajor']).reshape(4,4,order='F')[:3,3] for c in aligned['cameras']])
    baseline=float(np.linalg.norm(np.ptp(positions,axis=0)))
    diagonal=float(np.linalg.norm(dims))
    small_faces=sum(c['triangles'] for c in stats['componentDetails'] if c['triangles']<=2)
    fragmented=small_faces/stats['triangles']
    if stats['triangles']>=100 and fragmented>.95: raise ValueError('Extreme disconnected triangle debris: >95% of faces in one/two-face components')
    if stats['triangles']<1 or diagonal<max(1e-6,baseline*1e-4) or diagonal>max(10.,baseline*100.): raise ValueError('Collapsed or absurd metric mesh dimensions relative to registered camera baseline')
    if stats['nonManifoldEdges'] or stats['windingConflictEdges'] or stats['degenerateTriangles']: raise ValueError('Invalid mesh topology; inspect mesh diagnostics')
    return {'passed':True,'cameraBaselineMeters':baseline,'meshDiagonalMeters':diagonal,'oneOrTwoFaceComponentFraction':fragmented,'maximumDiagonalMeters':max(10.,baseline*100.),
            'claim':'catastrophe detection, no unmeasured physical ground truth'}


def run_pipeline(sparse, run, config, stage='all'):
    import numpy as np
    import pycolmap
    import open3d as o3d
    from mesh_geometry import surface_mesh, validate_mesh, transform_cloud
    from exporters import export_mesh
    from inspection import write_inspection
    config=configuration(config)
    if stage=='validate' and sparse.resolve()==run.resolve():
        saved=read_json(run/'object-manifest.json')
        sparse=Path(saved['sparseInputPath'])
    sparse,run=sparse.resolve(),run.resolve()
    if sparse==run or sparse in run.parents or run in sparse.parents: raise ValueError('Object output must be outside sparse input')
    aligned,inputs,model_path,hashes=sparse_inputs(sparse,config)
    versions={'pycolmap':pycolmap.__version__,'cuda':pycolmap.has_cuda,'open3d':o3d.__version__,'numpy':np.__version__}
    code={p.name:digest(p) for p in Path(__file__).parent.glob('*.py')}
    identity={'schemaVersion':1,'pipeline':'v0.3','sparseInputPath':str(sparse),'sparseInputHashes':hashes,'config':config,'versions':versions,'code':code}
    def identity_hash(document): return hashlib.sha256(json.dumps(document,sort_keys=True).encode()).hexdigest()
    fingerprint=identity_hash(identity)
    def function_sources(function,seen=None):
        seen=set() if seen is None else seen
        if function in seen: return {}
        seen.add(function)
        sources={function.__name__:inspect.getsource(function)}
        for dependency in inspect.getclosurevars(function).globals.values():
            if inspect.isfunction(dependency) and dependency.__module__==function.__module__: sources.update(function_sources(dependency,seen))
        return sources
    implementations={'dense':identity_hash({'worker':code['worker.py'],'transform':function_sources(transform_cloud)}),
                     'mesh':identity_hash({'worker':code['worker.py'],'geometry':code['mesh_geometry.py']}),
                     'export':identity_hash({'worker':code['worker.py'],'exporters':code['exporters.py']})}
    run.mkdir(parents=True,exist_ok=True)
    manifest_path=run/'object-manifest.json'
    if manifest_path.exists():
        manifest=read_json(manifest_path)
        saved_identity={k:manifest.get(k) for k in identity}
        if any(manifest.get(k)!=v for k,v in identity.items() if k!='code') or manifest.get('fingerprint')!=identity_hash(saved_identity) or manifest.get('inputReconstructionId')!=digest(sparse/'aligned-model.json') or manifest.get('sourceManifestSha256')!=inputs['sourceManifestSha256'] or manifest.get('code',{}).get('worker.py')!=code['worker.py']:
            raise ValueError('Stale/incompatible object artifacts; use a new run directory')
        if manifest['code']!=code:
            manifest.setdefault('implementationHistory',[]).append({'fingerprint':manifest['fingerprint'],'code':manifest['code']})
            manifest.update(code=code,fingerprint=fingerprint)
            write_json(manifest_path,manifest)
    else:
        if any(p.name not in {'object-config.json','object-worker.log','object-orchestration-failure.json','object-all.log','object-dense.log','object-mesh.log','object-export.log','object-validate.log'} for p in run.iterdir()): raise ValueError('Refusing to adopt nonempty unowned object directory')
        manifest={**identity,'fingerprint':fingerprint,'inputReconstructionId':digest(sparse/'aligned-model.json'),'sourceManifestSha256':inputs['sourceManifestSha256'],'stages':{}}
        write_json(manifest_path,manifest)
    diagnostic={'schemaVersion':1,'status':'running','frame':'capture-world','units':'meters','fingerprint':fingerprint,'reusedStages':[], 'stages':manifest['stages']}
    current='prerequisites'
    initial_stages=set(manifest['stages'])
    try:
        def process(name, paths, action):
            nonlocal current
            current=name
            previous=manifest['stages'].get(name)
            if previous:
                expected={'dense':{'dense.npz','dense-metric.ply'},'mesh':{'mesh.npz','mesh-diagnostics.json'},'export':{'exports/mesh.'+fmt for fmt in config['formats']}}[name]
                if set(previous.get('outputHashes',{}))!=expected: raise ValueError('Malformed checkpoint output set: '+name)
                if any(not (run/p).is_file() or digest(run/p)!=h for p,h in previous['outputHashes'].items()): raise ValueError('Corrupt checkpoint: '+name+'; use a new run directory')
                if previous.get('implementationHash')==implementations[name]:
                    if name in initial_stages and name not in diagnostic['reusedStages']: diagnostic['reusedStages'].append(name)
                    return previous['statistics']
                if name=='dense': raise ValueError('Stale/incompatible dense implementation; use a new run directory')
                if not paths: raise ValueError('Stale dependent stage '+name+'; rerun object or the mesh/export stage')
                invalid=[name]+(['export'] if name=='mesh' else [])
                archive=run/('stale-implementation-'+str(time.time_ns()))
                for invalid_name in invalid:
                    initial_stages.discard(invalid_name)
                    old=manifest['stages'].pop(invalid_name,None)
                    if old:
                        for path,checksum in old['outputHashes'].items():
                            source=run/path
                            if not source.is_file() or digest(source)!=checksum: raise ValueError('Corrupt checkpoint during invalidation: '+invalid_name)
                            destination=archive/path; destination.parent.mkdir(parents=True,exist_ok=True); source.rename(destination)
                        write_json(archive/(invalid_name+'-checkpoint.json'),old)
                write_json(manifest_path,manifest)
            started=time.monotonic(); stats=action()
            manifest['stages'][name]={'implementationHash':implementations[name],'runtimeSeconds':time.monotonic()-started,'statistics':stats,'outputHashes':{p:digest(run/p) for p in paths}}
            write_json(manifest_path,manifest); return stats
        if stage in ('all','dense'):
            process('dense',['dense.npz','dense-metric.ply'],lambda:dense_stage(sparse,run,aligned,model_path,config))
        if stage in ('all','mesh'):
            if 'dense' not in manifest['stages']: raise ValueError('No completed dense cloud; run dense first')
            process('dense',[],lambda:None)
            def meshing():
                with np.load(run/'dense.npz') as cloud:
                    v,f,n,stats=surface_mesh(cloud['points'],cloud['normals'],config['radiiMeters'],config['maxEdgeMeters'],config['minComponentFaces'])
                checked=validate_mesh(v,f,n)
                write_json(run/'mesh-diagnostics.json',{'surface':stats,'mesh':checked})
                try: checked['sanity']=sanity(checked,aligned)
                except Exception:
                    np.savez(run/'failed-mesh-candidate.npz',vertices=v,faces=f,normals=n)
                    raise
                np.savez(run/'mesh.npz',vertices=v,faces=f,normals=n)
                write_json(run/'mesh-diagnostics.json',{'surface':stats,'mesh':checked})
                return {'surface':stats,'mesh':checked}
            process('mesh',['mesh.npz','mesh-diagnostics.json'],meshing)
        if stage in ('all','export','validate'):
            if stage=='validate':
                if set(manifest['stages'])!={'dense','mesh','export'}: raise ValueError('Validation requires completed dense/mesh/export stages')
                process('dense',[],lambda:None); process('export',[],lambda:None)
            if 'mesh' not in manifest['stages']: raise ValueError('No completed mesh; run mesh first')
            process('mesh',[],lambda:None)
            with np.load(run/'mesh.npz') as mesh:
                v,f,n=mesh['vertices'],mesh['faces'],mesh['normals']
                checked=validate_mesh(v,f,n); checked['sanity']=sanity(checked,aligned)
                diagnostic['mesh']=checked
                write_inspection(run/'inspection.html',v,f,checked)
                if stage!='validate':
                    def exporting():
                        result=export_mesh(v,f,n,run/'exports',formats=tuple(config['formats']))
                        return {fmt:{**value,'path':str(Path(value['path']).relative_to(run))} for fmt,value in result.items()}
                    process('export',['exports/mesh.'+fmt for fmt in config['formats']],exporting)
        diagnostic['status']='succeeded'; diagnostic['peakRssBytes']=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss*1024
        diagnostic['artifactBytes']=sum(p.stat().st_size for p in run.rglob('*') if p.is_file())
        write_json(run/'diagnostics.json',diagnostic)
        return diagnostic
    except Exception as e:
        diagnostic.update(status='failed',stage=current,error={'type':type(e).__name__,'message':str(e)})
        write_json(run/'diagnostics.json',diagnostic)
        raise


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sparse',type=Path); parser.add_argument('output',type=Path)
    parser.add_argument('--stage',choices=['all','dense','mesh','export','validate'],default='all')
    parser.add_argument('--config',type=Path,required=True)
    args=parser.parse_args()
    try:
        result=run_pipeline(args.sparse,args.output,read_json(args.config),args.stage)
        print(json.dumps({'status':result['status'],'output':str(args.output)}))
        return 0
    except Exception as e:
        failure={'schemaVersion':1,'status':'failed','stage':args.stage,'error':str(e)}
        owned=args.output/'object-manifest.json'
        if owned.is_file():
            try:
                previous=read_json(owned)
                if previous.get('pipeline')=='v0.3': write_json(args.output/'object-attempt-failure.json',failure)
            except (OSError,ValueError): pass
        print(json.dumps(failure),file=sys.stderr)
        return 2

if __name__=='__main__':
    bootstrap_cuda()
    raise SystemExit(main())
