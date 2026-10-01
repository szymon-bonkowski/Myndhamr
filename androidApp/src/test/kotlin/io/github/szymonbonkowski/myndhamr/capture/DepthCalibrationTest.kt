package io.github.szymonbonkowski.myndhamr.capture
import kotlin.test.*
class DepthCalibrationTest {
    @Test fun differentAspectRatioPreservesCropRatherThanStretchingCpuIntrinsics() {
        // 640x480 CPU -> 16:9 depth160x90: crop60 pixels from top/bottom of CPU.
        val h=DepthCalibration.mapping(640,480,160,90,floatArrayOf(0f,-1f/6,1f,-1f/6,0f,7f/6,1f,7f/6))
        fun point(x:Double,y:Double)=Pair(h[0]*x+h[3]*y+h[6],h[1]*x+h[4]*y+h[7])
        val center=point(320.0,240.0)
        assertEquals(80.0,center.first,1e-5);assertEquals(45.0,center.second,1e-5)
        assertEquals(0.0,point(0.0,60.0).second,1e-5)
        assertEquals(-15.0,point(0.0,0.0).second,1e-5)
        assertEquals(0.25,h[4],1e-6) // naive 90/480=0.1875 is wrong.
    }
    @Test fun malformedAndNonAffineMappingRejectsBeforePersistence() {
        assertFails { DepthCalibration.mapping(2,2,2,2,floatArrayOf(0f,0f,1f,0f,0f,1f,0.5f,0.5f)) }
        assertFails { DepthCalibration.mapping(2,2,2,2,FloatArray(8)) }
        assertFails { DepthCalibration.mapping(2,2,2,2,floatArrayOf(Float.NaN,0f,1f,0f,0f,1f,1f,1f)) }
    }
}
