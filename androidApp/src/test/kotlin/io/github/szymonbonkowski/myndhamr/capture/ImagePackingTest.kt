package io.github.szymonbonkowski.myndhamr.capture

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails

class ImagePackingTest {
    @Test fun rowAndPixelPaddingAreExcluded() {
        val buffer=ByteBuffer.wrap(byteArrayOf(99,1,88,2,88,77,3,88,4,88,77)).apply { position(1) }
        assertContentEquals(byteArrayOf(1,2,3,4),ImagePacking.plane(buffer,2,2,5,2,1))
    }
    @Test fun depthLittleEndianBytesSurviveCopy() {
        val buffer=ByteBuffer.wrap(byteArrayOf(1,2,3,4,99,99,5,6,7,8))
        assertContentEquals(byteArrayOf(1,2,3,4,5,6,7,8),ImagePacking.plane(buffer,2,2,6,2,2))
    }
    @Test fun invalidStrideOrTruncatedBufferFailsExplicitly() {
        assertFails { ImagePacking.plane(ByteBuffer.allocate(4),2,2,4,1,2) }
        assertFails { ImagePacking.plane(ByteBuffer.allocate(3),2,2,2,1,1) }
    }
}
