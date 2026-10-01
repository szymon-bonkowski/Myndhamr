#!/usr/bin/env python3
"""Quantitative sparse acceptance with unchanged eligible-frame denominator."""
import argparse
import json
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__);p.add_argument('run',type=Path);p.add_argument('--controlled',action='store_true');args=p.parse_args()
d=json.loads((args.run/'diagnostics.json').read_text())
assert d['status']=='metric_aligned',d.get('error',d['status'])
assert d['registeredFraction']>.95, f"Registered {d['registeredImages']}/{d['inputEligibleImages']}; acceptance requires >95%"
assert d['sparsePointCount']>0 and d['observationCount']>0
assert d['componentCount']==1
if args.controlled:
 a=d['alignment'];assert a['inlierCount']==d['inputEligibleImages']
 assert a['allResidualMeters']['max']<.002, 'Analytic cameras require <2mm max residual (subpixel rendered-image tolerance)'
 assert a['inlierOrientationResidualDegrees']['max']<.1
 assert d['reprojectionErrorPixels']['p95']<1
 depth=d['depthValidation']['sources']['ARCORE_RAW']
 assert abs(depth['sparseToDepthRatio']['median']-1)<.005, 'Independent metric depth scale error must be <0.5%'
 assert depth['absoluteResidualMeters']['median']<.01
print(json.dumps({'acceptance':'passed','registered':d['registeredImages'],'eligible':d['inputEligibleImages'],
 'registeredFraction':d['registeredFraction'],'sparsePoints':d['sparsePointCount'],'scale':d['alignment']['scale'],
 'maxPositionResidualMeters':d['alignment']['allResidualMeters']['max'],
 'medianDepthScaleRatio':d.get('depthValidation',{}).get('sources',{}).get('ARCORE_RAW',{}).get('sparseToDepthRatio',{}).get('median')}))
