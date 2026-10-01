package io.github.szymonbonkowski.myndhamr.scan

import com.google.protobuf.ByteString
import com.google.protobuf.UnknownFieldSet
import io.github.szymonbonkowski.myndhamr.scan.v1.FoundationRecord
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeFoundationTest {
    private fun record() = FoundationRecord.newBuilder()
        .setFormatVersion(1)
        .setProjectId("synthetic-v1")
        .setSourceTimestampNs(9007199254740993L)
        .setEvidence(ByteString.copyFrom(byteArrayOf(0, -1, 1)))
        .addSampleIds(0).addSampleIds(-1L)
        .build()

    @Test fun realJniPreservesFieldsAndUnknownExtensions() {
        val original = record().toBuilder().setUnknownFields(
            UnknownFieldSet.newBuilder().addField(100,
                UnknownFieldSet.Field.newBuilder().addVarint(123).build()).build()
        ).build()
        val bytes = original.toByteArray()
        val output = NativeFoundation.roundTrip(bytes)
        assertEquals(original, FoundationRecord.parseFrom(output))
        assertContentEquals(output, NativeFoundation.roundTrip(bytes))
        assertContentEquals(bytes, original.toByteArray())
    }

    @Test fun goldenWireFixtureIsCompatible() {
        val hex = File(System.getProperty("myndhamr.fixtures"), "foundation.hex").readText().trim()
        val expected = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertContentEquals(expected, record().toByteArray())
        assertEquals(record(), FoundationRecord.parseFrom(NativeFoundation.roundTrip(expected)))
    }

    @Test fun bridgeRejectsCorruptionVersionsAndResourceOverflow() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(-128),
            record().toBuilder().setFormatVersion(2).build().toByteArray(), ByteArray(1024 * 1024 + 1))) {
            assertFailsWith<IllegalArgumentException> { NativeFoundation.roundTrip(bytes) }
        }
    }

    @Test fun timestampExtremesAreExact() {
        for (timestamp in listOf(Long.MIN_VALUE, -1, 0, Long.MAX_VALUE)) {
            val original = record().toBuilder().setSourceTimestampNs(timestamp).build()
            assertEquals(original, FoundationRecord.parseFrom(NativeFoundation.roundTrip(original.toByteArray())))
        }
    }
}
