package io.github.szymonbonkowski.myndhamr

import io.github.szymonbonkowski.myndhamr.scan.v1.*
import io.github.szymonbonkowski.myndhamr.store.CaptureProject
import io.github.szymonbonkowski.myndhamr.store.CaptureValidation
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureInspectorTest {
    @Test
    fun inspectStreamsCountsAndReportsIntegerTimestampResiduals() {
        val parent = Files.createTempDirectory("myndhamr-inspector-test-").toFile()
        try {
            val root = File(parent, "scan")
            val manifest = CaptureManifest.newBuilder().setFormatVersion(1).setProjectId("synthetic")
                .setStartedNs(1).setState(CaptureState.RECORDING)
                .setPoseConvention(CaptureValidation.POSE_CONVENTION)
                .setImageConvention(CaptureValidation.IMAGE_CONVENTION).build()
            val writer = CaptureProject.create(root, manifest)
            val rgb = writer.writeAsset("assets/keyframe.jpg", byteArrayOf(1, 2, 3), "image/jpeg")
            val baseNs = 8_000_000_000_000_000_000L
            fun timestamp(value: Long) = Timestamp.newBuilder().setValueNs(value)
                .setClockDomain("android_elapsed_realtime").setArrivalElapsedNs(value + 100).build()
            fun intrinsics() = Intrinsics.newBuilder().setFx(100.0).setFy(100.0).setCx(50.0).setCy(40.0)
                .setWidth(100).setHeight(80).setModel("pinhole").build()
            val camera = CameraObservation.newBuilder().setTimestamp(timestamp(baseNs)).setFrameNumber(7)
                .setTimestampSource("REALTIME").build()
            writer.appendCamera(camera)
            writer.appendFrame(CaptureFrame.newBuilder().setFrameId(1).setArTimestamp(timestamp(baseNs + 10))
                .setCameraTimestamp(timestamp(baseNs)).setImageTimestamp(timestamp(baseNs))
                .setTrackingState(TrackingState.PAUSED).setIntrinsics(intrinsics()).setCamera(camera)
                .setKeyframe(true).setRgb(rgb).setImageFormat("YUV_420_888").setImageWidth(100).setImageHeight(80)
                .addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_RAW)
                    .setAvailability(DepthAvailability.TEMPORARILY_UNAVAILABLE))
                .addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_SMOOTHED)
                    .setAvailability(DepthAvailability.UNSUPPORTED)).build())
            writer.appendImu(ImuSample.newBuilder().setType("ACCELEROMETER").setTimestamp(timestamp(baseNs))
                .addValues(0.0).addValues(0.0).addValues(9.8).build())
            writer.finish(2_000, CaptureState.COMPLETED)

            val result = inspectProject(root)
            assertTrue(result["valid"] as Boolean)
            val counts = result["counts"] as Map<*, *>
            assertEquals(1L, counts["frames"])
            assertEquals(1L, counts["keyframes"])
            val associations = result["keyframeAssociations"] as Map<*, *>
            assertEquals(1L, associations["exact"])
            val offsets = associations["camera2ArOffsetNs"] as Map<*, *>
            assertEquals("10", offsets["minNs"])
            assertEquals("10", offsets["maxNs"])
            assertEquals(1L, associations["arCameraSameClockChecked"])
            assertEquals(0L, associations["arCameraExact"])
            val imu = result["imu"] as Map<*, *>
            assertEquals(mapOf("ACCELEROMETER" to 1L), imu["samplesByType"])

            val archive = File(parent, "sample.scan3d")
            CaptureProject.export(root, archive)
            val replay = captureStdout { foundationCommand(listOf("replay", archive.path)) }.trim().lines()
            assertEquals(3, replay.size)
            assertTrue(replay[0].contains("\"record\":\"frame\""))
            assertTrue(replay[1].contains("\"record\":\"imu\""))
            assertTrue(replay[2].contains("\"record\":\"camera\""))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun reportsTimestampMismatchWithoutCallingMeasuredTimingInvalid() {
        val parent = Files.createTempDirectory("myndhamr-inspector-corrupt-").toFile()
        try {
            val root = File(parent, "scan")
            val manifest = CaptureManifest.newBuilder().setFormatVersion(1).setProjectId("bad")
                .setStartedNs(1).setState(CaptureState.RECORDING)
                .setPoseConvention(CaptureValidation.POSE_CONVENTION)
                .setImageConvention(CaptureValidation.IMAGE_CONVENTION).build()
            val writer = CaptureProject.create(root, manifest)
            val rgb = writer.writeAsset("assets/keyframe.jpg", byteArrayOf(4), "image/jpeg")
            fun time(ns: Long) = Timestamp.newBuilder().setValueNs(ns).setClockDomain("android_elapsed_realtime").setArrivalElapsedNs(ns).build()
            val intrinsics = Intrinsics.newBuilder().setFx(1.0).setFy(1.0).setWidth(1).setHeight(1).setModel("pinhole")
            writer.appendFrame(CaptureFrame.newBuilder().setFrameId(1).setArTimestamp(time(20)).setCameraTimestamp(time(10))
                .setImageTimestamp(time(11)).setTrackingState(TrackingState.PAUSED).setIntrinsics(intrinsics)
                .setKeyframe(true).setRgb(rgb).setImageFormat("JPEG").setImageWidth(1).setImageHeight(1)
                .addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_RAW).setAvailability(DepthAvailability.UNSUPPORTED))
                .addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_SMOOTHED).setAvailability(DepthAvailability.UNSUPPORTED)).build())
            writer.finish(30, CaptureState.COMPLETED)
            val result = inspectProject(root)
            val association = result["keyframeAssociations"] as Map<*, *>
            assertEquals(0L, association["exact"])
            assertEquals(1L, association["mismatches"])
            assertTrue(result["valid"] as Boolean) // timing diagnostics do not rewrite package integrity status
        } finally {
            parent.deleteRecursively()
        }
    }
}

private fun captureStdout(block: () -> Unit): String {
    val previous = System.out
    val buffer = ByteArrayOutputStream()
    return try {
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        block()
        buffer.toString(Charsets.UTF_8)
    } finally {
        System.setOut(previous)
    }
}
