#!/usr/bin/env python3
"""Independent PLY/OBJ/GLB geometry and metric acceptance for an object run."""
import argparse
import json
from pathlib import Path
import sys
import numpy as np
import trimesh
import open3d as o3d


def check(run, controlled=False):
    manifest=json.loads((run/'object-manifest.json').read_text())
    diagnostic=json.loads((run/'diagnostics.json').read_text())
    assert manifest['schemaVersion']==1 and diagnostic['status']=='succeeded'
    assert manifest['stages'].keys() >= {'dense','mesh','export'}
    with np.load(run/'mesh.npz') as mesh:
        v,f,n=mesh['vertices'],mesh['faces'],mesh['normals']
    assert len(v)>=3 and len(f)>0 and np.isfinite(v).all() and np.isfinite(n).all()
    assert f.min()>=0 and f.max()<len(v)
    np.testing.assert_allclose(np.linalg.norm(n,axis=1),1,atol=1e-7)
    parsed={}
    for fmt in manifest['config']['formats']:
        path=run/'exports'/('mesh.'+fmt)
        m=trimesh.load(path,force='mesh',process=False)
        assert len(m.vertices)==len(v) and len(m.faces)==len(f),(fmt,len(m.vertices),len(m.faces))
        tolerance=max(1e-7,np.abs(v).max()*2e-7) if fmt=='glb' else 1e-12
        np.testing.assert_allclose(m.vertices,v,atol=tolerance,rtol=0,err_msg=fmt)
        np.testing.assert_array_equal(m.faces,f,err_msg=fmt+' winding')
        np.testing.assert_allclose(m.vertex_normals,n,atol=2e-6,rtol=0,err_msg=fmt+' normals')
        parsed[fmt]={'passed':True,'bytes':path.stat().st_size,'maxPositionErrorMeters':float(np.abs(m.vertices-v).max())}
    sparse=Path(manifest['sparseInputPath'])
    alignment=json.loads((sparse/'aligned-model.json').read_text())['alignment']
    R=np.array(alignment['rotationWorldSfmRowMajor']).reshape(3,3)
    translation=np.array(alignment['translationWorldSfmMeters']);scale=alignment['scale']
    fused=o3d.io.read_point_cloud(str(run/'dense-workspace/fused.ply'))
    with np.load(run/'dense.npz') as dense:
        points=dense['points']; normals=dense['normals']
        np.testing.assert_allclose(points,scale*(np.asarray(fused.points)@R.T)+translation,atol=1e-12,rtol=0)
        inverse=(points-translation)@R/scale
        np.testing.assert_allclose(inverse,np.asarray(fused.points),atol=1e-10,rtol=0)
        np.testing.assert_allclose(np.linalg.norm(normals,axis=1),1,atol=1e-7)
        # Every final mesh vertex must be an observed dense point, not extrapolated.
        cloud=o3d.geometry.PointCloud(o3d.utility.Vector3dVector(points))
        query=o3d.geometry.PointCloud(o3d.utility.Vector3dVector(v))
        support=np.asarray(query.compute_point_cloud_distance(cloud))
        assert support.max()<1e-10,'Mesh fabricated vertices beyond observed support'
    stats=manifest['stages']['mesh']['statistics']['mesh']
    assert stats['sanity']['passed'] and stats['nonManifoldEdges']==0 and stats['nonManifoldVertices']==0 and stats['windingConflictEdges']==0
    result={'acceptance':'passed','densePoints':manifest['stages']['dense']['statistics']['densePoints'],
            'vertices':len(v),'triangles':len(f),'components':stats['components'],'dimensionsMeters':np.ptp(v,axis=0).tolist(),
            'exports':parsed,'metricTransformRoundTrip':True,'maximumVertexSupportDistanceMeters':float(support.max()),'stageRuntimeSeconds':{k:s['runtimeSeconds'] for k,s in manifest['stages'].items()},
            'peakRssBytes':diagnostic['peakRssBytes'],'artifactBytes':diagnostic['artifactBytes']}
    if controlled:
        # Known independent fixture consists of planes z=2.8,3.2,4 metres.
        distance=np.min(np.abs(v[:,2,None]-np.array([2.8,3.2,4.])),axis=1)
        result['knownPlaneResidualMeters']={'median':float(np.median(distance)),'p95':float(np.quantile(distance,.95)),'max':float(distance.max())}
        assert np.median(distance)<.005 and np.quantile(distance,.95)<.02,result
        assert np.ptp(v,axis=0)[2]>1.,'Lost known 1.2m depth extent'
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('run',type=Path);p.add_argument('--controlled',action='store_true');a=p.parse_args()
    print(json.dumps(check(a.run,a.controlled),indent=2,allow_nan=False))
