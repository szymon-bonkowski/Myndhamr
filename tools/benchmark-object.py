#!/usr/bin/env python3
"""Bounded metric mesh validation, cleanup, loading and export smoke benchmark."""
import json
from pathlib import Path
import sys
import tempfile
import time
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'desktop-workers/object-mesh'))
from mesh_geometry import cleanup_mesh,validate_mesh
from exporters import export_mesh

x,y=np.meshgrid(np.linspace(0,1,81),np.linspace(0,.5,81))
v=np.column_stack((x.ravel(),y.ravel(),np.zeros(x.size)))
ids=np.arange(81*81).reshape(81,81);a=ids[:-1,:-1].ravel();b=ids[:-1,1:].ravel();c=ids[1:,:-1].ravel();d=ids[1:,1:].ravel()
f=np.vstack((np.column_stack((a,b,c)),np.column_stack((b,d,c))))
times={}
start=time.perf_counter();stats=validate_mesh(v,f);times['validation']=time.perf_counter()-start
start=time.perf_counter();v,f,n,clean=cleanup_mesh(v,f);times['cleanupNormals']=time.perf_counter()-start
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);np.savez(root/'mesh.npz',vertices=v,faces=f,normals=n)
 start=time.perf_counter()
 with np.load(root/'mesh.npz') as loaded: assert loaded['vertices'].shape==v.shape
 times['artifactLoad']=time.perf_counter()-start
 start=time.perf_counter();outputs=export_mesh(v,f,n,root);times['exportAll']=time.perf_counter()-start
 assert stats['triangles']==12800 and clean['after']['triangles']==12800
 np.testing.assert_allclose(stats['surfaceAreaSquareMeters'],.5,atol=1e-10)
 print(json.dumps({'benchmark':'metric-plane-81x81','vertices':len(v),'triangles':len(f),'runtimeSeconds':times,'outputs':outputs},allow_nan=False,default=str))
