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
            fun timestamp(value: Long, clock: String = "ARCORE_FRAME") = Timestamp.newBuilder().setValueNs(value)
                .setClockDomain(clock).setArrivalElapsedNs(value + 100).build()
            fun intrinsics() = Intrinsics.newBuilder().setFx(100.0).setFy(100.0).setCx(50.0).setCy(40.0)
                .setWidth(100).setHeight(80).setModel("pinhole").build()
            val camera = CameraObservation.newBuilder().setTimestamp(timestamp(baseNs, "CAMERA_REALTIME")).setFrameNumber(7)
                .setTimestampSource("CAMERA_REALTIME").build()
            writer.appendCamera(camera)
            writer.appendFrame(CaptureFrame.newBuilder().setFrameId(1).setArTimestamp(timestamp(baseNs + 10))
                .setCameraTimestamp(timestamp(baseNs, "CAMERA_REALTIME")).setImageTimestamp(timestamp(baseNs, "CAMERA_REALTIME"))
                .setTrackingState(TrackingState.TRACKING).setPose(pose()).setIntrinsics(intrinsics()).setCamera(camera)
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
            val offsetPairs = associations["arMinusCameraObservedNsByClockPair"] as Map<*, *>
            val offset = offsetPairs["ARCORE_FRAME->CAMERA_REALTIME"] as Map<*, *>
            assertEquals("unverified", offset["mapping"])
            val offsets = offset["deltaNs"] as Map<*, *>
            assertEquals("10", offsets["minNs"])
            assertEquals("10", offsets["maxNs"])
            assertEquals(1L, associations["embeddedCameraCheckedAllFrames"])
            assertEquals(1L, associations["embeddedCameraExactAllFrames"])
            val imu = result["imu"] as Map<*, *>
            assertEquals(mapOf("ACCELEROMETER" to 1L), imu["samplesByType"])
            val cameraTiming = result["cameraTiming"] as Map<*, *>
            assertEquals(1L, (cameraTiming["callbackLatencyNs"] as Map<*, *>)["count"])

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
    fun providerAssociationReportsClockDifferenceWithoutClaimingClockEquality() {
        val parent=Files.createTempDirectory("myndhamr-provider-image-").toFile()
        try {
            val root=File(parent,"scan")
            CaptureProject.create(root,manifest("provider")).use { writer ->
                val cameraTime=timestamp(137848998457227,"CAMERA_REALTIME")
                val rgb=writer.writeAsset("assets/rgb/1.i420",ByteArray(6),"i420")
                writer.appendFrame(CaptureFrame.newBuilder().setFrameId(1).setArTimestamp(timestamp(137849012417062,"ARCORE_FRAME"))
                    .setCameraTimestamp(cameraTime).setCamera(CameraObservation.newBuilder().setTimestamp(cameraTime).setFrameNumber(7).setTimestampSource("REALTIME"))
                    .setImageTimestamp(timestamp(137849012053824,"ARCORE_CPU_IMAGE")).setImageAssociation(CaptureValidation.ARCORE_CURRENT_FRAME_IMAGE)
                    .setTrackingState(TrackingState.TRACKING).setPose(pose()).setIntrinsics(intrinsics(2,2))
                    .setKeyframe(true).setRgb(rgb).setImageWidth(2).setImageHeight(2).setImageFormat("YUV_420_888")
                    .addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_RAW).setAvailability(DepthAvailability.UNSUPPORTED)).build())
                writer.finish(200,CaptureState.COMPLETED)
            }
            val archive=File(parent,"provider.scan3d");CaptureProject.export(root,archive)
            val imported=File(parent,"imported");CaptureProject.importPackage(archive,imported)
            val result=inspectProject(imported)
            assertTrue(result["valid"] as Boolean)
            val associations=result["keyframeAssociations"] as Map<*,*>
            assertEquals(1L,associations["providerLinkedCurrentFrameKeyframes"])
            assertEquals(0L,associations["exact"])
            assertEquals(0L,associations["mismatches"])
            assertEquals(1L,associations["unverifiedClockKeyframes"])
            assertEquals(1L,associations["embeddedCameraExactAllFrames"])
            val pairs=associations["cpuImageMinusCameraObservedNsByClockPair"] as Map<*,*>
            val observation=pairs["ARCORE_CPU_IMAGE->CAMERA_REALTIME"] as Map<*,*>
            assertEquals("unverified",observation["mapping"])
            assertEquals("13596597",(observation["deltaNs"] as Map<*,*>)["minNs"])
            val replay=captureStdout { foundationCommand(listOf("replay",archive.path)) }
            assertTrue(replay.contains(CaptureValidation.ARCORE_CURRENT_FRAME_IMAGE))
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun reportsNonKeyframeCameraMetadataMismatchWithoutCallingTimingInvalid() {
        val parent = Files.createTempDirectory("myndhamr-inspector-corrupt-").toFile()
        try {
            val root = File(parent, "scan")
            val manifest = CaptureManifest.newBuilder().setFormatVersion(1).setProjectId("bad")
                .setStartedNs(1).setState(CaptureState.RECORDING)
                .setPoseConvention(CaptureValidation.POSE_CONVENTION)
                .setImageConvention(CaptureValidation.IMAGE_CONVENTION).build()
            val writer = CaptureProject.create(root, manifest)
            val rgb = writer.writeAsset("assets/keyframe.jpg", byteArrayOf(4), "image/jpeg")
            fun time(ns: Long, clock: String = "ARCORE_FRAME") = Timestamp.newBuilder().setValueNs(ns).setClockDomain(clock).setArrivalElapsedNs(ns).build()
            val intrinsics = Intrinsics.newBuilder().setFx(1.0).setFy(1.0).setWidth(1).setHeight(1).setModel("pinhole")
            val camera = CameraObservation.newBuilder().setTimestamp(time(12, "CAMERA_REALTIME")).setFrameNumber(1)
                .setTimestampSource("CAMERA_REALTIME").build()
            writer.appendFrame(CaptureFrame.newBuilder().setFrameId(1).setArTimestamp(time(20))
                .setCameraTimestamp(time(10, "CAMERA_REALTIME")).setImageTimestamp(time(11, "CAMERA_REALTIME"))
                .setCamera(camera).setTrackingState(TrackingState.PAUSED).setIntrinsics(intrinsics)
                .setRgb(rgb).setImageFormat("JPEG").setImageWidth(1).setImageHeight(1)
                .addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_RAW).setAvailability(DepthAvailability.UNSUPPORTED))
                .addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_SMOOTHED).setAvailability(DepthAvailability.UNSUPPORTED)).build())
            writer.finish(30, CaptureState.COMPLETED)
            val result = inspectProject(root)
            val association = result["keyframeAssociations"] as Map<*, *>
            assertEquals(0L, association["checked"])
            assertEquals(1L, association["embeddedCameraMismatchesAllFrames"])
            assertTrue(result["valid"] as Boolean) // timing diagnostics do not rewrite package integrity status
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun unknownClockDomainsDoNotProduceLatencyEstimates() {
        val parent = Files.createTempDirectory("myndhamr-inspector-unknown-clock-").toFile()
        try {
            val root = File(parent, "scan")
            val writer = CaptureProject.create(root, manifest("unknown-clock"))
            val unknownTime = timestamp(500, "UNKNOWN", 900_000)
            writer.appendCamera(CameraObservation.newBuilder().setTimestamp(unknownTime).setFrameNumber(1)
                .setTimestampSource("UNKNOWN").build())
            writer.appendImu(ImuSample.newBuilder().setType("GYROSCOPE").setTimestamp(unknownTime)
                .addValues(0.0).addValues(0.0).addValues(0.0).build())
            writer.appendFrame(CaptureFrame.newBuilder().setFrameId(1).setArTimestamp(timestamp(600, "ARCORE_FRAME"))
                .setTrackingState(TrackingState.PAUSED).setIntrinsics(intrinsics(1, 1))
                .addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_RAW)
                    .setAvailability(DepthAvailability.UNSUPPORTED)).build())
            writer.finish(1_000, CaptureState.COMPLETED)

            val result = inspectProject(root)
            val imu = result["imu"] as Map<*, *>
            assertEquals(0L, (imu["callbackLatencyNs"] as Map<*, *>)["count"])
            assertEquals(1L, imu["unverifiedCallbackLatencySamples"])
            val camera = result["cameraTiming"] as Map<*, *>
            assertEquals(0L, (camera["callbackLatencyNs"] as Map<*, *>)["count"])
            assertEquals(1L, camera["unverifiedCallbackLatencySamples"])
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun depthReuseTrackingWorksPastSixtyFourDistinctTimestamps() {
        val parent = Files.createTempDirectory("myndhamr-inspector-depth-reuse-").toFile()
        try {
            val root = File(parent, "scan")
            val writer = CaptureProject.create(root, manifest("depth-reuse"))
            fun depth(value: Long) = DepthRecord.newBuilder().setSource(DepthSource.ARCORE_RAW)
                .setAvailability(DepthAvailability.AVAILABLE).setTimestamp(timestamp(value, "ARCORE_DEPTH"))
                .setIntrinsics(intrinsics(2, 2)).setWidth(2).setHeight(2).setUnitMeters(0.001)
                .setAlignment("test aligned view").setAssetOmittedByPolicy(true).build()
            fun frame(id: Long, frameTime: Long, depthTime: Long) = CaptureFrame.newBuilder().setFrameId(id)
                .setArTimestamp(timestamp(frameTime, "ARCORE_FRAME")).setTrackingState(TrackingState.PAUSED)
                .setIntrinsics(intrinsics(2, 2)).addDepth(depth(depthTime)).build()
            for (id in 1L..70L) writer.appendFrame(frame(id, 10_000 + id * 100, 5_000 + id * 100))
            writer.appendFrame(frame(71, 17_000, 12_000)) // repeats frame 70's AR and depth timestamps after 70 unique samples
            writer.finish(30_000, CaptureState.COMPLETED)

            val result = inspectProject(root)
            assertTrue(result["valid"] as Boolean)
            val depthSummary = result["depth"] as Map<*, *>
            assertEquals(1L, depthSummary["reusedTimestampObservations"])
            val timestamps = result["timestamps"] as Map<*, *>
            val frameOrders = timestamps["frameOrders"] as Map<*, *>
            assertEquals(1L, ((frameOrders["ar:ARCORE_FRAME"] as Map<*, *>)["consecutiveDuplicates"]))
            val deltas = depthSummary["observedDepthMinusArNsByClockPair"] as Map<*, *>
            assertEquals(71L, ((deltas["ARCORE_FRAME->ARCORE_DEPTH:ARCORE_RAW"] as Map<*, *>)
                ["deltaNs"] as Map<*, *>)["count"])
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun invalidValidationReturnsFailureAndReplayEmitsNoMetadataRecords() {
        val parent = Files.createTempDirectory("myndhamr-inspector-invalid-").toFile()
        try {
            val root = File(parent, "unfinished")
            CaptureProject.create(root, manifest("unfinished")).close()
            val validateOut = ByteArrayOutputStream()
            val validateErr = ByteArrayOutputStream()
            val validateStatus = runCli(listOf("validate", root.path), PrintStream(validateOut), PrintStream(validateErr))
            assertEquals(1, validateStatus)
            assertTrue(validateOut.toString(Charsets.UTF_8).contains("\"valid\":false"))
            assertEquals("", validateErr.toString(Charsets.UTF_8))

            val replayOut = ByteArrayOutputStream()
            val replayErr = ByteArrayOutputStream()
            val replayStatus = runCli(listOf("replay", root.path), PrintStream(replayOut), PrintStream(replayErr))
            assertEquals(1, replayStatus)
            assertTrue(replayOut.toString(Charsets.UTF_8).contains("\"valid\":false"))
            assertTrue("\"record\"" !in replayOut.toString(Charsets.UTF_8))
            assertEquals("", replayErr.toString(Charsets.UTF_8))
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

private fun manifest(projectId: String) = CaptureManifest.newBuilder().setFormatVersion(1).setProjectId(projectId)
    .setStartedNs(1).setState(CaptureState.RECORDING)
    .setPoseConvention(CaptureValidation.POSE_CONVENTION)
    .setImageConvention(CaptureValidation.IMAGE_CONVENTION).build()

private fun timestamp(value: Long, clock: String, arrival: Long = value + 100) = Timestamp.newBuilder()
    .setValueNs(value).setClockDomain(clock).setArrivalElapsedNs(arrival).build()

private fun intrinsics(width: Int, height: Int) = Intrinsics.newBuilder().setFx(100.0).setFy(100.0)
    .setCx(width / 2.0).setCy(height / 2.0).setWidth(width).setHeight(height).setModel("pinhole").build()

private fun pose() = Pose.newBuilder().setConvention(CaptureValidation.POSE_CONVENTION)
    .addAllColumnMajor(listOf(1.0,0.0,0.0,0.0, 0.0,1.0,0.0,0.0, 0.0,0.0,1.0,0.0, 0.0,0.0,0.0,1.0)).build()
