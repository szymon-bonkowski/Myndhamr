#!/usr/bin/env python3
"""Deterministic multi-plane textured scene; outputs pixels, analytic camera/depth evidence.
Run with the pinned sparse venv. The companion Gradle fixture writer creates scan v1.
"""
import json
import sys
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw


def render(destination, count=10):
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=False)
    rng = np.random.default_rng(20261001)
    planes = [(4., -2.5, 2.5, -2., 2.), (2.8, -.95, -.25, -.7, .65), (3.2, .25, 1.05, -.8, .3)]
    textures = []
    for _ in planes:
        im = Image.new('RGB', (1024, 1024), (185, 185, 185)); d = ImageDraw.Draw(im)
        for j in range(3000):
            x, y = rng.integers(0, 1024, 2); size = int(rng.integers(3, 17)); color = tuple(int(v) for v in rng.integers(0, 256, 3))
            if j % 2:
                d.ellipse((int(x), int(y), int(x+size), int(y+size)), fill=color)
            else:
                d.rectangle((int(x), int(y), int(x+size), int(y+size)), fill=color)
        textures.append(np.asarray(im))
    w, h, fx, fy, cx, cy = 640, 480, 500., 505., 320., 240.
    u, v = np.meshgrid(np.arange(w)+.5, np.arange(h)+.5)
    rays = np.stack([(u-cx)/fx, (v-cy)/fy, np.ones_like(u)], -1)
    records=[]
    for i in range(count):
        theta = 2*np.pi*i/count
        C = np.array([.45*np.cos(theta), .3*np.sin(theta), .08*np.sin(2*theta)])
        z = np.array([0., 0., 3.5])-C; z /= np.linalg.norm(z)
        x = np.cross([0., 1., 0.], z); x /= np.linalg.norm(x); y=np.cross(z, x)
        R=np.column_stack([x,y,z]); world_rays=rays @ R.T
        depth=np.full((h,w),np.inf); pixels=np.zeros((h,w,3), dtype=np.uint8)
        for (pz,xmin,xmax,ymin,ymax), texture in zip(planes,textures):
            dist=(pz-C[2])/world_rays[...,2]
            point=C + world_rays*dist[...,None]
            mask=(dist>0)&(dist<depth)&(point[...,0]>=xmin)&(point[...,0]<=xmax)&(point[...,1]>=ymin)&(point[...,1]<=ymax)
            tx=(point[...,0]-xmin)/(xmax-xmin)*1023;ty=(point[...,1]-ymin)/(ymax-ymin)*1023
            ix=np.floor(tx).astype(int).clip(0,1022);iy=np.floor(ty).astype(int).clip(0,1022)
            ax=(tx-ix).clip(0,1)[...,None];ay=(ty-iy).clip(0,1)[...,None]
            sampled=(texture[iy,ix]*(1-ax)*(1-ay)+texture[iy,ix+1]*ax*(1-ay)+texture[iy+1,ix]*(1-ax)*ay+texture[iy+1,ix+1]*ax*ay).astype(np.uint8)
            pixels[mask]=sampled[mask];depth[mask]=dist[mask]
        name=f'frame-{i+1}.png';Image.fromarray(pixels).save(destination/name)
        # Optical axial Z: ray z=1, hence intersection parameter is axial depth.
        scaled=depth[2::4,2::4]; scaled=np.where(np.isfinite(scaled),np.rint(scaled*1000),0).astype('<u2')
        scaled.tofile(destination/f'depth-{i+1}.bin');np.full(scaled.shape,255,dtype='u1').tofile(destination/f'confidence-{i+1}.bin')
        M=np.eye(4);M[:3,:3]=R @ np.diag([1.,-1.,-1.]);M[:3,3]=C
        timestamp=9_007_199_254_740_993+i*100_000_000
        records.append({'frameId':str(i+1),'timestampNs':str(timestamp),'worldFromCameraColumnMajor':M.flatten(order='F').tolist(),'name':name})
    meta={'schemaVersion':1,'fixture':'textured nonplanar metric planes','seed':20261001,'width':w,'height':h,'fx':fx,'fy':fy,'cx':cx,'cy':cy,'frames':records,'planes':planes}
    (destination/'truth.json').write_text(json.dumps(meta,indent=2)+'\n')
    with (destination/'poses.tsv').open('w') as out:
        for f in records:
            out.write('\t'.join([f['frameId'],f['timestampNs'],f['name'],*[format(v,'.17g') for v in f['worldFromCameraColumnMajor']]])+'\n')
    return meta

if __name__=='__main__':
    render(sys.argv[1], int(sys.argv[2]) if len(sys.argv)>2 else 10)
