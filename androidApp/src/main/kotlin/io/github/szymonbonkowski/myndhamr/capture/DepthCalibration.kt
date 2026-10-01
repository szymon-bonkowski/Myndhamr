package io.github.szymonbonkowski.myndhamr.capture

import kotlin.math.abs

/** ARCore depth can crop the CPU image. H_depthPixels_cpuImagePixels uses column vectors,
 * column-major3x3, preserving the measured frame-specific crop/axis mapping.
 * Samples are texture-normalized coordinates for CPU (0,0),(w,0),(0,h),(w,h).
 */
internal object DepthCalibration {
    fun mapping(cpuWidth:Int,cpuHeight:Int,depthWidth:Int,depthHeight:Int,samples:FloatArray):List<Double> {
        require(cpuWidth>0 && cpuHeight>0 && depthWidth>0 && depthHeight>0 && samples.size==8 && samples.all { it.isFinite() })
        val x=samples.mapIndexed { i,v -> v.toDouble() * if(i%2==0)depthWidth else depthHeight }
        val a=(x[2]-x[0])/cpuWidth;val b=(x[4]-x[0])/cpuHeight
        val c=(x[3]-x[1])/cpuWidth;val d=(x[5]-x[1])/cpuHeight
        require(abs(a*d-b*c)>1e-12) { "Degenerate CPU/depth mapping" }
        require(abs(a*cpuWidth+b*cpuHeight+x[0]-x[6])<0.05 && abs(c*cpuWidth+d*cpuHeight+x[1]-x[7])<0.05) { "ARCore mapping is not affine within 0.05 depth pixels" }
        return listOf(a,c,0.0,b,d,0.0,x[0],x[1],1.0)
    }
}
