import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
import numpy as np

MODULE=Path(__file__).resolve().parents[1]/'metric_output.py'
spec=importlib.util.spec_from_file_location('metric_output',MODULE);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class GeometryTests(unittest.TestCase):
    def test_colmap_direction_and_axes(self):
        # Nonidentity, nonsymmetric R + t detects transpose/inversion errors.
        R=np.array([[0.,0.,1.],[1.,0.,0.],[0.,1.,0.]])
        C=np.array([1.,2.,3.]); M=np.eye(4);M[:3,:3]=R;M[:3,3]=-R@C
        c={'name':'known','worldToOpticalColumnMajor':M.flatten(order='F').tolist()}
        pose=m.world_from_optical(c)
        np.testing.assert_allclose(pose[:3,3],C,atol=1e-12)
        np.testing.assert_allclose(pose[:3,:3],R.T,atol=1e-12)
        np.testing.assert_allclose(M @ pose,np.eye(4),atol=1e-12)
        D=np.diag([1.,-1.,-1.]); self.assertEqual(np.linalg.det(D),1)
        # AR -Z forward becomes optical +Z; up becomes optical -Y.
        np.testing.assert_allclose(D@[0,1,-2],[0,-1,2])
        M[0,0]=float('nan');c['worldToOpticalColumnMajor']=M.flatten(order='F').tolist()
        with self.assertRaises(ValueError):m.world_from_optical(c)

    def test_depth_measured_crop_and_timing(self):
        with tempfile.TemporaryDirectory() as temp:
            run=Path(temp);np.array([0,2000,3000,0],dtype='<u2').tofile(run/'depth.bin')
            np.array([0,255,255,0],dtype='u1').tofile(run/'conf.bin')
            d={'source':'ARCORE_RAW','availability':'AVAILABLE','depthPath':'depth.bin','confidencePath':'conf.bin','width':2,'height':2,
               'unitMeters':.001,'timestampNs':'9007199254740993','clockDomain':'AR','confidenceClockDomain':'AR','confidenceTimestampNs':'9007199254740993','cpuToDepthColumnMajor':[.5,0,0,0,.5,0,-1,0,1]}
            f={'frameId':'1','timestampNs':'9007199254740993','clockDomain':'AR','depth':[d]}
            c={'frameId':'1','worldFromOpticalColumnMajor':np.eye(4).flatten(order='F').tolist()}
            p={'position':[1,0,2],'observations':[{'frameId':'1','u':4.5,'v':.5}]}
            result=m.depth_validation(run,[f],[c],[p]);self.assertEqual(result['sources']['ARCORE_RAW']['absoluteResidualMeters']['max'],0)
            # 1ns mismatch >2^53 is preserved and excluded rather than rounded equal.
            d['timestampNs']='9007199254740992';result=m.depth_validation(run,[f],[c],[p]);self.assertEqual(result['sources'],{})
            self.assertEqual(result['skipped']['depth_timestamp_not_current_AR_frame'],1)
            d['timestampNs']=f['timestampNs'];f['arClockDomain']='ARCORE_FRAME';f['arTimestampNs']=f['timestampNs'];d['clockDomain']='ARCORE_DEPTH';d['confidenceClockDomain']='ARCORE_DEPTH_CONFIDENCE'
            result=m.depth_validation(run,[f],[c],[p]);self.assertEqual(result['sources']['ARCORE_RAW']['absoluteResidualMeters']['max'],0)
            self.assertEqual(result['provenance'][0]['derivedFrameAssociationPolicy'],'ARCore-provider-current-depth-timestamp-equality')
            d['confidenceClockDomain']='UNKNOWN';d['confidenceTimestampNs']=d['timestampNs'];result=m.depth_validation(run,[f],[c],[p]);self.assertEqual(result['sources'],{})
            d['confidenceClockDomain']='ARCORE_DEPTH_CONFIDENCE';result=m.depth_validation(run,[f],[c],[p]);self.assertEqual(result['sources']['ARCORE_RAW']['absoluteResidualMeters']['max'],0)
            d['clockDomain']='UNKNOWN';result=m.depth_validation(run,[f],[c],[p]);self.assertEqual(result['sources'],{})
            d['clockDomain']='ARCORE_DEPTH';p['observations'][0]['u']=2.5
            result=m.depth_validation(run,[f],[c],[p]);self.assertEqual(result['sources'],{});self.assertEqual(result['skipped']['invalid_zero_depth'],1)
            p['observations'][0]['u']=4.5;np.array([0,127,255,0],dtype='u1').tofile(run/'conf.bin')
            result=m.depth_validation(run,[f],[c],[p]);self.assertEqual(result['sources'],{});self.assertEqual(result['skipped']['raw_confidence_below_128_of_255'],1)

    def test_native_known_similarity(self):
        import subprocess
        align=Path(os.environ.get('MYNDHAMR_ALIGN','build/native/myndhamr_align')).resolve()
        self.assertTrue(align.is_file(),'Build nativeBuild before this geometry test')
        src=np.array([[0,0,0],[1,0,0],[0,1,0],[0,0,1],[1,1,1]],dtype=float)
        R=np.array([[0.,-1.,0.],[1.,0.,0.],[0.,0.,1.]]);s=3.2;t=np.array([3.,-2.,7.])
        target=(s*R@src.T).T+t
        data=''.join(' '.join(map(str,[*a,*b]))+'\n' for a,b in zip(src,target))
        p=subprocess.run([str(align),'.01','.6','.05'],input=data,text=True,capture_output=True)
        self.assertEqual(p.returncode,0,p.stderr);header=list(map(float,p.stdout.splitlines()[0].split()))
        self.assertAlmostEqual(header[0],s,places=10);np.testing.assert_allclose(np.array(header[1:10]).reshape(3,3),R,atol=1e-10)
        np.testing.assert_allclose(header[10:13],t,atol=1e-10)
        inv=''.join(' '.join(map(str,[*a,*b]))+'\n' for a,b in zip(target,src))
        p=subprocess.run([str(align),'.01','.6','.05'],input=inv,text=True,capture_output=True)
        self.assertEqual(p.returncode,0,p.stderr);self.assertAlmostEqual(float(p.stdout.split()[0]),1/s,places=10)

if __name__=='__main__':unittest.main()
