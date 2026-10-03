"""Standalone offline mesh inspector; display sampling never changes mesh artifacts."""
import json
from pathlib import Path
import numpy as np


def write_inspection(path: Path, vertices, faces, stats):
    # Deterministic stratified face sampling bounds browser storage/work.
    ids=np.linspace(0,len(faces)-1,min(len(faces),30000),dtype=int)
    triangles=vertices[faces[ids]]
    data=json.dumps({'triangles':triangles.tolist(),'bounds':stats['boundsMeters'],'dimensions':stats['dimensionsMeters'],
                     'vertices':stats['vertices'],'faces':stats['triangles'],'components':stats['components']},allow_nan=False)
    html='''<!doctype html><meta charset="utf-8"><title>Myndhamr metric mesh inspection</title>
<style>body{margin:24px;background:#111827;color:#eee;font:16px system-ui}canvas{width:100%;height:72vh;background:#1c2940}label{margin-right:18px}input{vertical-align:middle}</style>
<h1>Observed object mesh</h1><p id="stats"></p><p>Capture-world: right-handed, +Y up, metres. Open boundaries are retained. Display samples at most 30,000 triangles; exports contain the entire mesh.</p>
<label>Yaw <input id="yaw" type="range" min="-180" max="180" value="-30"></label><label>Pitch <input id="pitch" type="range" min="-90" max="90" value="20"></label><label>Zoom <input id="zoom" type="range" min="20" max="600" value="180"></label><label><input id="wire" type="checkbox">Wire</label><label><input id="points" type="checkbox" checked>Observed corners</label><canvas id="view" width="1200" height="800"></canvas>
<script>const D=DATA;const C=document.getElementById('view'),g=C.getContext('2d');
document.getElementById('stats').textContent=`${D.vertices} vertices; ${D.faces} triangles; ${D.components} components; dimensions ${D.dimensions.map(x=>x.toFixed(4)).join(' × ')} m`;
function draw(){g.clearRect(0,0,C.width,C.height);const yaw=+document.getElementById('yaw').value*Math.PI/180,pitch=+document.getElementById('pitch').value*Math.PI/180,zoom=+document.getElementById('zoom').value/100;const center=D.bounds.min.map((x,i)=>(x+D.bounds.max[i])/2);const span=Math.hypot(...D.dimensions),scale=650*zoom/span;
function rotate(p){let x=p[0]-center[0],y=p[1]-center[1],z=p[2]-center[2];let a=Math.cos(yaw)*x+Math.sin(yaw)*z,b=-Math.sin(yaw)*x+Math.cos(yaw)*z;return[a,Math.cos(pitch)*y-Math.sin(pitch)*b,Math.sin(pitch)*y+Math.cos(pitch)*b]}
const triangles=D.triangles.map(t=>t.map(rotate)).sort((a,b)=>a.reduce((s,p)=>s+p[2],0)-b.reduce((s,p)=>s+p[2],0));
for(const t of triangles){g.beginPath();t.forEach((p,i)=>i?g.lineTo(600+p[0]*scale,400-p[1]*scale):g.moveTo(600+p[0]*scale,400-p[1]*scale));g.closePath();let a=t[1].map((v,i)=>v-t[0][i]),b=t[2].map((v,i)=>v-t[0][i]);let n=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],l=Math.hypot(...n);let shade=l?90+110*Math.abs(n[2]/l):90;g.fillStyle=`rgb(${shade*.65},${shade*.85},${shade})`;g.fill();if(document.getElementById('wire').checked){g.strokeStyle='#385575';g.stroke()}}
if(document.getElementById('points').checked){g.fillStyle='#a5d9ff';for(const t of triangles)for(const p of t)g.fillRect(600+p[0]*scale-.6,400-p[1]*scale-.6,1.2,1.2)}
g.fillStyle='#eee';g.fillText('View centered for inspection only. Stored world coordinates are unchanged.',20,25);
const origin=[0,0,0];['X','Y','Z'].forEach((label,i)=>{let p=[0,0,0];p[i]=span*.15;let a=rotate(center),b=rotate(center.map((v,k)=>v+p[k]));g.strokeStyle=['#f66','#6f6','#69f'][i];g.beginPath();g.moveTo(80,730);g.lineTo(80+(b[0]-a[0])*scale,730-(b[1]-a[1])*scale);g.stroke();g.fillText(label,80+(b[0]-a[0])*scale,730-(b[1]-a[1])*scale)})}
document.querySelectorAll('input').forEach(x=>x.oninput=draw);draw();</script>'''
    path.write_text(html.replace('DATA',data))
