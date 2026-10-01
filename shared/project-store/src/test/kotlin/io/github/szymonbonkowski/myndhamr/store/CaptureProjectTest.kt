package io.github.szymonbonkowski.myndhamr.store

import com.google.protobuf.ByteString
import com.google.protobuf.UnknownFieldSet
import io.github.szymonbonkowski.myndhamr.scan.v1.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class CaptureProjectTest {
    private fun directory(): File = Files.createTempDirectory("capture-test-").toFile().apply { deleteOnExit() }
    private fun manifest() = CaptureManifest.newBuilder().setFormatVersion(1).setProjectId("synthetic")
        .setDevice("test-device").addCapabilities("depth=unsupported").setStartedNs(100)
        .setState(CaptureState.RECORDING).setPoseConvention(CaptureValidation.POSE_CONVENTION)
        .setImageConvention(CaptureValidation.IMAGE_CONVENTION).build()
    private fun timestamp(time: Long = 101) = Timestamp.newBuilder().setValueNs(time).setClockDomain("ARCORE")
        .setArrivalElapsedNs(1_000_000).build()
    private fun intrinsics() = Intrinsics.newBuilder().setFx(500.0).setFy(500.0).setCx(320.0).setCy(240.0)
        .setWidth(640).setHeight(480).setModel("pinhole").build()
    private fun pose() = Pose.newBuilder().setConvention(CaptureValidation.POSE_CONVENTION)
        .addAllColumnMajor(listOf(1.0,0.0,0.0,0.0, 0.0,1.0,0.0,0.0, 0.0,0.0,1.0,0.0, 2.0,3.0,4.0,1.0)).build()
    private fun frame(id: Long = 1) = CaptureFrame.newBuilder().setFrameId(id).setArTimestamp(timestamp(id + 100))
        .setTrackingState(TrackingState.TRACKING).setPose(pose()).setIntrinsics(intrinsics())
        .addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_RAW).setAvailability(DepthAvailability.UNSUPPORTED)).build()
    private fun imu(time: Long) = ImuSample.newBuilder().setType("ACCELEROMETER").setTimestamp(timestamp(time))
        .addAllValues(listOf(0.0,9.81,0.0)).setAccuracy(3).build()
    private fun camera() = CameraObservation.newBuilder().setTimestamp(timestamp()).setFrameNumber(1)
        .setTimestampSource("UNKNOWN").setExposureNs(1_000_000).setIso(100).setFocalMm(4f).build()

    @Test fun streamingRoundtripAndUnknownFieldsPreserveExactEvidence() {
        val root = directory(); val largeTime = 9_007_199_254_740_993L
        val unknown = UnknownFieldSet.newBuilder().addField(100, UnknownFieldSet.Field.newBuilder().addVarint(largeTime).build()).build()
        val original = frame().toBuilder().setArTimestamp(timestamp(largeTime)).setUnknownFields(unknown).build()
        val writer = CaptureProject.create(root, manifest())
        writer.appendFrame(original); writer.appendImu(imu(largeTime)); writer.appendCamera(camera())
        writer.appendEvent(SessionEvent.newBuilder().setType("START").setTimestamp(timestamp()).setDetail("raw event").build())
        writer.finish(200, CaptureState.COMPLETED)
        var decoded: CaptureFrame? = null
        CaptureProject.visitFrames(root) { decoded = it }
        assertEquals(original, decoded)
        val report = CaptureProject.validate(root)
        assertTrue(report.valid, report.diagnostics.joinToString()); assertEquals(1, report.frames); assertEquals(1, report.imuSamples)
        assertEquals(1, report.cameraObservations); assertEquals(1, report.events)
    }

    @Test fun assetsImmutableChecksummedAndKeyframeComplete() {
        val root = directory(); val writer = CaptureProject.create(root, manifest())
        val rgb = writer.writeAsset("assets/rgb/1.jpg", byteArrayOf(1,2,3,4), "jpeg")
        assertFailsWith<IllegalArgumentException> { writer.writeAsset(rgb.path, byteArrayOf(4), "jpeg") }
        val keyframe = frame().toBuilder().setKeyframe(true).setRgb(rgb).setCameraTimestamp(timestamp())
            .setImageTimestamp(timestamp()).setCamera(camera()).setImageWidth(640).setImageHeight(480).setImageFormat("JPEG").build()
        writer.appendFrame(keyframe); writer.finish(200, CaptureState.COMPLETED)
        assertTrue(CaptureProject.validate(root).valid)
        File(root, rgb.path).writeBytes(byteArrayOf(4,3,2,1))
        val report = CaptureProject.validate(root)
        assertFalse(report.valid); assertTrue(report.diagnostics.any { "SHA-256" in it })
    }

    @Test fun invalidMeasurementRejectedBeforePersisting() {
        val root = directory(); val writer = CaptureProject.create(root, manifest())
        assertFailsWith<IllegalArgumentException> { writer.appendFrame(frame().toBuilder().setIntrinsics(intrinsics().toBuilder().setFx(Double.NaN)).build()) }
        assertFailsWith<IllegalArgumentException> { writer.appendFrame(frame().toBuilder().setTrackingState(TrackingState.PAUSED).build()) }
        assertFailsWith<IllegalArgumentException> { writer.appendFrame(frame().toBuilder().clearPose().build()) }
        assertFailsWith<IllegalArgumentException> { writer.appendFrame(frame().toBuilder().setPose(pose().toBuilder().setColumnMajor(0, -1.0)).build()) }
        assertFailsWith<IllegalArgumentException> { writer.appendFrame(frame().toBuilder().setKeyframe(true).build()) }
        assertFailsWith<IllegalArgumentException> { writer.appendFrame(frame().toBuilder().clearDepth().build()) }
        assertFailsWith<IllegalArgumentException> { writer.appendImu(imu(101).toBuilder().setValues(0, Double.POSITIVE_INFINITY).build()) }
        assertFailsWith<IllegalArgumentException> { writer.writeAsset("assets/../../escape", byteArrayOf(1), "raw") }
        writer.close()
    }

    @Test fun metadataOnlyDepthIsExplicitAndLimitedToNonKeyframes() {
        val depth = DepthRecord.newBuilder().setSource(DepthSource.ARCORE_RAW).setAvailability(DepthAvailability.AVAILABLE)
            .setTimestamp(timestamp()).setIntrinsics(intrinsics()).setWidth(640).setHeight(480)
            .setUnitMeters(.001).setAlignment("synthetic uniform aligned optical view").setAssetOmittedByPolicy(true)
        CaptureValidation.frame(frame().toBuilder().clearDepth().addDepth(depth).build())
        assertFailsWith<IllegalArgumentException> { CaptureValidation.frame(frame().toBuilder().clearDepth().addDepth(depth.clearAssetOmittedByPolicy()).build()) }
    }

    @Test fun cropMappingAndConfidenceTimestampsRoundtripAndRejectMalformedCalibration() {
        val root=directory()
        val d=DepthRecord.newBuilder().setSource(DepthSource.ARCORE_RAW).setAvailability(DepthAvailability.AVAILABLE)
            .setTimestamp(timestamp(123)).setConfidenceTimestamp(timestamp(124)).setConfidenceAvailability(DepthAvailability.AVAILABLE)
            .setIntrinsics(intrinsics().toBuilder().setWidth(160).setHeight(90).setModel("ARCORE_DEPTH_SCALED_TEXTURE_PINHOLE"))
            .setWidth(160).setHeight(90).setUnitMeters(.001).setAlignment("ARCore depth texture crop")
            .addAllCpuToDepthColumnMajor(listOf(.25,0.0,0.0,0.0,.25,0.0,0.0,-15.0,1.0))
            .setMappingConvention("H_depthPixels_cpuImagePixels;column-major3x3").setAssetOmittedByPolicy(true).build()
        val f=frame().toBuilder().clearDepth().addDepth(d).build()
        CaptureProject.create(root,manifest()).use { it.appendFrame(f);it.finish(200,CaptureState.COMPLETED) }
        var decoded:CaptureFrame?=null;CaptureProject.visitFrames(root) { decoded=it }
        assertEquals(f,decoded)
        assertFails { CaptureValidation.frame(f.toBuilder().setDepth(0,d.toBuilder().clearCpuToDepthColumnMajor()).build()) }
        assertFails { CaptureValidation.frame(f.toBuilder().setDepth(0,d.toBuilder().setCpuToDepthColumnMajor(0,Double.NaN)).build()) }
        assertFails { CaptureValidation.frame(f.toBuilder().setDepth(0,d.toBuilder().setCpuToDepthColumnMajor(0,0.0)).build()) }
    }

    @Test fun interruptedTailRecoveryPreservesPrefixAndTail() {
        val root = directory(); val writer = CaptureProject.create(root, manifest())
        writer.appendFrame(frame()); writer.appendImu(imu(101)); writer.close()
        val frames = File(root, CaptureProject.FRAMES); val prefix = frames.readBytes()
        frames.appendBytes(byteArrayOf(20, 1, 2, 3))
        assertFalse(CaptureProject.validate(root).valid)
        val recovered = CaptureProject.recover(root)
        assertContentEquals(prefix, frames.readBytes())
        assertContentEquals(byteArrayOf(20,1,2,3), File(root, "recovery").listFiles()!!.single().readBytes())
        assertFailsWith<IllegalArgumentException> { recovered.appendFrame(frame()) }
        recovered.appendFrame(frame(2)); recovered.finish(300, CaptureState.INTERRUPTED)
        val report = CaptureProject.validate(root)
        assertTrue(report.valid, report.diagnostics.joinToString()); assertEquals(2, report.frames)
        assertFailsWith<IllegalArgumentException> { CaptureProject.recover(root) }
    }

    @Test fun completeCorruptOrOversizedRecordsAreNeverSilentlyRecovered() {
        val root = directory(); CaptureProject.create(root, manifest()).close()
        File(root, CaptureProject.FRAMES).appendBytes(byteArrayOf(1, 0xFF.toByte()))
        assertFails { CaptureProject.recover(root) }
        File(root, CaptureProject.FRAMES).writeBytes(byteArrayOf(0xFF.toByte(),0xFF.toByte(),0xFF.toByte(),0xFF.toByte(),0x7F))
        assertFails { CaptureProject.recover(root) }
    }

    @Test fun truncatedFinalizedStreamAndMissingReferencedAssetFailValidation() {
        val root = directory(); val writer = CaptureProject.create(root, manifest()); writer.appendFrame(frame()); writer.finish(200, CaptureState.COMPLETED)
        val bytes = File(root, CaptureProject.FRAMES).readBytes(); File(root, CaptureProject.FRAMES).writeBytes(bytes.copyOf(bytes.size - 1))
        assertFalse(CaptureProject.validate(root).valid)
        File(root, CaptureProject.IMU).delete()
        assertTrue(CaptureProject.validate(root).diagnostics.any { "Missing" in it })
    }

    @Test fun zipRoundtripTraversalDuplicateAndExpandedByteBudget() {
        val root = directory(); val writer = CaptureProject.create(root, manifest()); writer.appendFrame(frame()); writer.finish(200, CaptureState.COMPLETED)
        val folder = directory(); val zip = File(folder, "synthetic.scan3d"); CaptureProject.export(root, zip)
        val imported = File(folder, "imported"); assertTrue(CaptureProject.importPackage(zip, imported).valid)
        assertEquals(1, CaptureProject.validate(imported).frames)
        val tinyDestination = File(folder, "tiny")
        assertFailsWith<IllegalArgumentException> { CaptureProject.importPackage(zip, tinyDestination, StoreLimits(maxImportBytes = 5)) }
        assertFalse(tinyDestination.exists())
        val bad = File(folder, "bad.scan3d")
        ZipOutputStream(bad.outputStream()).use { it.putNextEntry(ZipEntry("../escape")); it.write(1); it.closeEntry() }
        assertFailsWith<IllegalArgumentException> { CaptureProject.importPackage(bad, File(folder, "bad-import")) }
        assertFalse(File(folder, "escape").exists())
        ZipOutputStream(bad.outputStream()).use { it.putNextEntry(ZipEntry("assets/a")); it.write(1); it.closeEntry(); it.putNextEntry(ZipEntry("assets/./a")); it.write(1); it.closeEntry() }
        assertFailsWith<IllegalArgumentException> { CaptureProject.importPackage(bad, File(folder, "duplicate")) }
    }

    @Test fun longJournalStreamingHasNoAccumulatedSampleCollection() {
        val root = directory(); val writer = CaptureProject.create(root, manifest())
        repeat(100_000) { writer.appendImu(imu(1_000L + it * 5_000_000L)) }
        writer.appendFrame(frame()); writer.finish(500_000_000_000L, CaptureState.COMPLETED)
        val report = CaptureProject.validate(root)
        assertTrue(report.valid, report.diagnostics.joinToString()); assertEquals(100_000, report.imuSamples)
        assertEquals(5_000_000, report.maximumImuGapNs); assertTrue(report.diagnostics.isEmpty())
        var visited = 0L; CaptureProject.visitImu(root) { visited++ }; assertEquals(100_000, visited)
    }

    @Test fun trustedRootAliasWorksButInternalSymlinkIsRejected() {
        val folder = directory(); val realRoot = File(folder, "real").apply { mkdirs() }
        val alias = File(folder, "alias")
        Files.createSymbolicLink(alias.toPath(), realRoot.toPath())
        val writer = CaptureProject.create(alias, manifest())
        writer.appendFrame(frame())
        val outside = File(folder, "outside").apply { mkdirs() }
        File(realRoot, "assets").mkdirs()
        Files.createSymbolicLink(File(realRoot, "assets/escape").toPath(), outside.toPath())
        assertFailsWith<IllegalArgumentException> { writer.writeAsset("assets/escape/secret", byteArrayOf(1), "raw") }
        Files.delete(File(realRoot, "assets/escape").toPath())
        writer.finish(200, CaptureState.COMPLETED)
        assertTrue(CaptureProject.validate(alias).valid)
        val zip = File(folder, "alias.scan3d")
        CaptureProject.export(alias, zip)
        assertTrue(CaptureProject.importPackage(zip, File(folder, "imported")).valid)
        assertFalse(File(outside, "secret").exists())
    }

    @Test fun partialJournalWritePoisonsWriterAndKeepsTailRecoverable() {
        val root = directory()
        CaptureProject.create(root, manifest()).use { it.appendFrame(frame()); it.appendImu(imu(101)) }
        val journal = File(root, CaptureProject.FRAMES)
        val prefix = journal.readBytes()
        val closed = mutableListOf<String>()
        val writer = CaptureWriter(root, manifest(), StoreLimits()) { file ->
            val stream = FileOutputStream(file, true)
            var remaining = if (file.name == "frames.pb") 7 else Int.MAX_VALUE
            val output = object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    val accepted = minOf(count, remaining)
                    stream.write(bytes, offset, accepted)
                    remaining -= accepted
                    if (remaining == 0) throw IOException("injected partial journal write")
                }
                override fun close() {
                    closed += file.name
                    stream.close()
                    // Closing one handle must never prevent closure of the remaining three.
                    if (file.name == "frames.pb") throw IOException("injected close failure")
                }
            }
            JournalHandle(output) { stream.fd.sync() }
        }
        val firstFailure = assertFailsWith<IOException> { writer.appendFrame(frame(2)) }
        assertEquals("injected partial journal write", firstFailure.message)
        assertEquals(listOf("injected close failure"), firstFailure.suppressed.map { it.message })
        assertEquals(CaptureProject.streamPaths.map { File(it).name }, closed)
        val damaged = journal.readBytes()
        assertEquals(prefix.size + 7, damaged.size)
        // Model tasks already admitted to the FIFO when the first append failed.
        assertFailsWith<IllegalStateException> { writer.appendFrame(frame(3)) }
        assertFailsWith<IllegalStateException> { writer.appendImu(imu(102)) }
        assertFailsWith<IllegalStateException> { writer.appendCamera(camera()) }
        assertFailsWith<IllegalStateException> { writer.appendEvent(SessionEvent.newBuilder().setType("FINAL").setTimestamp(timestamp()).build()) }
        assertFailsWith<IllegalStateException> { writer.writeAsset("assets/after-failure", byteArrayOf(1), "raw") }
        assertFailsWith<IllegalStateException> { writer.sync() }
        assertFailsWith<IllegalStateException> { writer.finish(200, CaptureState.FAILED) }
        writer.close()
        assertContentEquals(damaged, journal.readBytes())
        assertEquals(CaptureState.RECORDING, CaptureProject.readManifest(root).state)
        assertFalse(File(root, "assets/after-failure").exists())
        CaptureProject.recover(root).use { recovered ->
            assertContentEquals(prefix, journal.readBytes())
            assertContentEquals(damaged.copyOfRange(prefix.size, damaged.size), File(root, "recovery").listFiles()!!.single().readBytes())
            recovered.appendFrame(frame(2))
            recovered.finish(200, CaptureState.INTERRUPTED)
        }
        val report = CaptureProject.validate(root)
        assertTrue(report.valid, report.diagnostics.joinToString())
        assertEquals(2L, report.frames)
        assertEquals(1L, report.imuSamples)
    }

    @Test fun journalSyncFailureClosesAllHandlesAndRetainsCompletePrefix() {
        for (duringFinish in listOf(false, true)) {
            val root = directory(); CaptureProject.create(root, manifest()).close()
            val closed = mutableListOf<String>()
            val writer = CaptureWriter(root, manifest(), StoreLimits()) { file ->
                val stream = FileOutputStream(file, true)
                val output = object : OutputStream() {
                    override fun write(value: Int) = stream.write(value)
                    override fun write(bytes: ByteArray, offset: Int, count: Int) = stream.write(bytes, offset, count)
                    override fun close() { closed += file.name; stream.close() }
                }
                JournalHandle(output) {
                    if (file.name == "imu.pb") throw IOException("injected journal sync failure")
                    stream.fd.sync()
                }
            }
            writer.appendFrame(frame()); writer.appendImu(imu(101))
            assertFailsWith<IOException> { if (duringFinish) writer.finish(200, CaptureState.FAILED) else writer.sync() }
            assertEquals(CaptureProject.streamPaths.map { File(it).name }, closed)
            assertFailsWith<IllegalStateException> { writer.appendFrame(frame(2)) }
            assertFailsWith<IllegalStateException> { writer.finish(200, CaptureState.FAILED) }
            writer.close()
            assertEquals(CaptureState.RECORDING, CaptureProject.readManifest(root).state)
            CaptureProject.recover(root).use { it.finish(200, CaptureState.INTERRUPTED) }
            val report = CaptureProject.validate(root)
            assertTrue(report.valid, report.diagnostics.joinToString())
            assertEquals(1L, report.frames); assertEquals(1L, report.imuSamples)
        }
    }

    @Test fun failedJournalInitializationClosesAlreadyOpenedHandles() {
        val root = directory(); CaptureProject.create(root, manifest()).close()
        val closed = mutableListOf<String>()
        assertFailsWith<IOException> {
            CaptureWriter(root, manifest(), StoreLimits()) { file ->
                if (file.name == "imu.pb") throw IOException("injected journal open failure")
                val stream = FileOutputStream(file, true)
                JournalHandle(object : OutputStream() {
                    override fun write(value: Int) = stream.write(value)
                    override fun close() { closed += file.name; stream.close() }
                }) { stream.fd.sync() }
            }
        }
        assertEquals(listOf("frames.pb"), closed)
        assertEquals(CaptureState.RECORDING, CaptureProject.readManifest(root).state)
        CaptureProject.recover(root).use { it.finish(200, CaptureState.INTERRUPTED) }
        assertTrue(CaptureProject.validate(root).valid)
    }

    @Test fun unsupportedVersionAndConventionsAreRejected() {
        assertFailsWith<IllegalArgumentException> { CaptureProject.create(directory(), manifest().toBuilder().setFormatVersion(2).build()) }
        assertFailsWith<IllegalArgumentException> { CaptureProject.create(directory(), manifest().toBuilder().setPoseConvention("row-major").build()) }
    }
}
