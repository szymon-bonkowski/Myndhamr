package io.github.szymonbonkowski.myndhamr.capture

import android.media.Image
import java.nio.ByteBuffer

/** Copy only valid pixels, respecting Android row/pixel strides; no image remains held by disk work. */
object ImagePacking {
    fun plane(buffer:ByteBuffer,width:Int,height:Int,rowStride:Int,pixelStride:Int,bytesPerPixel:Int):ByteArray {
        require(width>0 && height>0 && rowStride>0 && pixelStride>=bytesPerPixel)
        val output=ByteArray(Math.multiplyExact(Math.multiplyExact(width,height),bytesPerPixel))
        val b=buffer.duplicate(); val origin=b.position()
        for(y in 0 until height) for(x in 0 until width) for(c in 0 until bytesPerPixel) {
            output[(y*width+x)*bytesPerPixel+c]=b.get(origin+y*rowStride+x*pixelStride+c)
        }
        return output
    }
    fun yuv(image:Image):ByteArray {
        require(image.width%2==0 && image.height%2==0 && image.planes.size==3)
        return image.planes.mapIndexed { i,p ->
            plane(p.buffer,if(i==0) image.width else image.width/2,if(i==0) image.height else image.height/2,p.rowStride,p.pixelStride,1)
        }.reduce { a,b -> a+b }
    }
    fun depth(image:Image,bytesPerPixel:Int):ByteArray = image.planes[0].let {
        plane(it.buffer,image.width,image.height,it.rowStride,it.pixelStride,bytesPerPixel)
    }
}
