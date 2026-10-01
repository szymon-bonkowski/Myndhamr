package io.github.szymonbonkowski.myndhamr

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.szymonbonkowski.myndhamr.scan.NativeFoundation
import io.github.szymonbonkowski.myndhamr.scan.v1.FoundationRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeFoundationDeviceTest {
    @Test fun protobufCrossesRealAndroidJni() {
        val original = FoundationRecord.newBuilder().setFormatVersion(1)
            .setProjectId("android-synthetic").setSourceTimestampNs(Long.MAX_VALUE)
            .setEvidence(ByteString.copyFrom(byteArrayOf(0, -1, 42)))
            .addSampleIds(-1L).build()
        val bytes = original.toByteArray() + byteArrayOf(0xa0.toByte(), 0x06, 0x7b)
        val parsed = FoundationRecord.parseFrom(bytes)
        val output = NativeFoundation.roundTrip(bytes)
        assertEquals(parsed, FoundationRecord.parseFrom(output))
        assertArrayEquals(output, NativeFoundation.roundTrip(bytes))
    }

    @Test fun corruptAndUnsupportedDataFailExplicitly() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeFoundation.roundTrip(byteArrayOf(-128))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeFoundation.roundTrip(FoundationRecord.newBuilder().setFormatVersion(2).build().toByteArray())
        }
    }
}
