package io.github.szymonbonkowski.myndhamr.reconstruction

import io.github.szymonbonkowski.myndhamr.domain.PinholeIntrinsics
import io.github.szymonbonkowski.myndhamr.domain.RigidTransform
import io.github.szymonbonkowski.myndhamr.domain.UnitQuaternion
import io.github.szymonbonkowski.myndhamr.domain.Vector3
import io.github.szymonbonkowski.myndhamr.scan.v1.*
import io.github.szymonbonkowski.myndhamr.store.CaptureProject
import io.github.szymonbonkowski.myndhamr.store.CaptureValidation
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import javax.imageio.ImageIO

class ReconstructionIngestTest {
    @Test
    fun directoryAndZipIngestPreserveRawEvidenceAndNanosecondPrecision() {
        val parent = Files.createTempDirectory("reconstruction-ingest-").toFile()
        try {
            val project = createProject(File(parent, "scan"), frameId = 42, timestamp = 9_007_199_254_740_993L)
            val before = fileHashes(project)
            val directoryResult = Ingest.prepare(project, File(parent, "directory-run"))
            assertEquals(listOf(42L), directoryResult.frames.map { it.frameId })
            assertEquals(9_007_199_254_740_993L, directoryResult.frames.single().timestampNs)
            assertEquals("ARCORE_CPU_IMAGE", directoryResult.frames.single().clockDomain)
            val directoryJson = File(parent, "directory-run/input.json").readText()
            assertTrue(directoryJson.contains("\"timestampNs\":\"9007199254740993\""))
            assertTrue(directoryJson.contains("\"frameId\":\"42\""))
            assertEquals(10, ImageIO.read(directoryResult.frames.single().image).raster.getSample(0, 0, 0))
            assertEquals(before, fileHashes(project))

            val archive = File(parent, "scan.scan3d")
            CaptureProject.export(project, archive)
            val zipped = Ingest.prepare(archive, File(parent, "zip-run"))
            assertEquals(directoryResult.sourceManifestSha256, zipped.sourceManifestSha256)
            assertEquals(directoryResult.frames.single().raw, zipped.frames.single().raw)
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun excludesNonKeyframesAndCopiesDepthProvenanceWithoutChangingRawAsset() {
        val parent = Files.createTempDirectory("reconstruction-depth-").toFile()
        try {
            val root = File(parent, "scan")
            createProject(root, frameId = 1, timestamp = 99, includeDepth = true, includeNonKeyframe = true)
            val before = fileHashes(root)
            val result = Ingest.prepare(root, File(parent, "run"))
            assertEquals(listOf(ExcludedFrame(2, "NOT_MANUAL_KEYFRAME;INVALID_TRACKING")), result.excluded)
            val evidence = File(parent, "run/evidence/assets/depth/1.u16")
            assertTrue(evidence.isFile)
            assertEquals(byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0).toList(), evidence.readBytes().toList())
            assertEquals(before, fileHashes(root))
            assertTrue(File(parent, "run/input.json").readText().contains("evidence/assets/depth/1.u16"))
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun rejectsInvalidProjectUnsupportedEncodingAndMalformedPixelsWithFrameContext() {
        val parent = Files.createTempDirectory("reconstruction-invalid-").toFile()
        try {
            val corrupt = createProject(File(parent, "corrupt"), 7, 123)
            File(corrupt, "assets/rgb/7.i420").writeBytes(byteArrayOf(0, 1))
            val failure = assertFailsWith<IllegalArgumentException> { Ingest.prepare(corrupt, File(parent, "corrupt-run")) }
            assertTrue(failure.message.orEmpty().contains("Invalid capture project"))

            val unsupported = createProject(File(parent, "unsupported"), 8, 124, rgbEncoding = "application/octet-stream")
            val unsupportedFailure = assertFailsWith<IllegalArgumentException> { Ingest.prepare(unsupported, File(parent, "unsupported-run")) }
            assertTrue(unsupportedFailure.message.orEmpty().contains("Frame 8"))
            assertFalse(File(parent, "unsupported-run").exists())

            val wrongDimensions = createProject(File(parent, "wrong-size"), 9, 125, rgbEncoding = "image/jpeg", jpegDimensions = 4 to 2)
            val dimensionFailure = assertFailsWith<IllegalArgumentException> { Ingest.prepare(wrongDimensions, File(parent, "wrong-size-run")) }
            assertTrue(dimensionFailure.message.orEmpty().contains("Frame 9"))
            assertTrue(dimensionFailure.message.orEmpty().contains("do not match metadata"))
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun rejectsUnsupportedCalibrationAndDestinationInsideSource() {
        val parent = Files.createTempDirectory("reconstruction-contract-").toFile()
        try {
            val unsupportedModel = createProject(File(parent, "model"), 3, 100, model = "fisheye")
            val modelFailure = assertFailsWith<IllegalArgumentException> { Ingest.prepare(unsupportedModel, File(parent, "model-run")) }
            assertTrue(modelFailure.message.orEmpty().contains("unsupported camera model"))

            val source = createProject(File(parent, "nested"), 4, 101)
            val nestedFailure = assertFailsWith<IllegalArgumentException> { Ingest.prepare(source, File(source, "run")) }
            assertTrue(nestedFailure.message.orEmpty().contains("outside the source"))
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun invalidRecordDiagnosticsNameFrameAndJournal() {
        val parent=Files.createTempDirectory("reconstruction-record-errors").toFile()
        try {
            val source=createProject(File(parent,"source"),42,123)
            var frame: CaptureFrame?=null
            CaptureProject.visitFrames(source){frame=it}
            val validFrame=requireNotNull(frame)
            File(source,CaptureProject.FRAMES).outputStream().use { output ->
                validFrame.toBuilder().setIntrinsics(validFrame.intrinsics.toBuilder().setFx(0.0)).build().writeDelimitedTo(output)
            }
            val error=assertFailsWith<IllegalArgumentException>{Ingest.prepare(source,File(parent,"run"))}
            assertTrue(error.message.orEmpty().contains("metadata/frames.pb frameId 42"))
            assertTrue(error.message.orEmpty().contains("Invalid calibration"))
            val unsupported=createProject(File(parent,"version"),43,124)
            File(unsupported,CaptureProject.MANIFEST).writeBytes(CaptureProject.readManifest(unsupported).toBuilder().setFormatVersion(99).build().toByteArray())
            val versionError=assertFailsWith<IllegalArgumentException>{Ingest.prepare(unsupported,File(parent,"version-run"))}
            assertTrue(versionError.message.orEmpty().contains("manifest.pb: Unsupported capture format 99"))
        }finally{parent.deleteRecursively()}
    }

    private fun createProject(
        root: File,
        frameId: Long,
        timestamp: Long,
        includeDepth: Boolean = false,
        includeNonKeyframe: Boolean = false,
        rgbEncoding: String = "i420",
        model: String = "ARCORE_CPU_IMAGE_PINHOLE;unrotated-pixels",
        jpegDimensions: Pair<Int, Int>? = null,
    ): File {
        val width = 2
        val height = 2
        val manifest = CaptureManifest.newBuilder().setFormatVersion(1).setProjectId("scan-\"${frameId}")
            .setStartedNs(1).setState(CaptureState.RECORDING)
            .setPoseConvention(CaptureValidation.POSE_CONVENTION).setImageConvention(CaptureValidation.IMAGE_CONVENTION).build()
        CaptureProject.create(root, manifest).use { writer ->
            val rgbBytes = if (rgbEncoding == "i420") byteArrayOf(10, 20, 30, 40, 128.toByte(), 128.toByte()) else {
                val image = BufferedImage(jpegDimensions?.first ?: 2, jpegDimensions?.second ?: 2, BufferedImage.TYPE_INT_RGB)
                val output = java.io.ByteArrayOutputStream(); ImageIO.write(image, "jpeg", output); output.toByteArray()
            }
            val rgb = writer.writeAsset("assets/rgb/$frameId.${if (rgbEncoding == "i420") "i420" else "jpg"}", rgbBytes, rgbEncoding)
            val cameraTime = timestamp(timestamp, "CAMERA_REALTIME")
            val camera = CameraObservation.newBuilder().setTimestamp(cameraTime).setFrameNumber(frameId).setTimestampSource("CAMERA_REALTIME").build()
            val intrinsics = Intrinsics.newBuilder().setFx(100.0).setFy(100.0).setCx(width / 2.0).setCy(height / 2.0)
                .setWidth(width).setHeight(height).setModel(model).build()
            var builder = CaptureFrame.newBuilder().setFrameId(frameId).setArTimestamp(timestamp(timestamp, "ARCORE_FRAME"))
                .setCameraTimestamp(cameraTime).setImageTimestamp(timestamp(timestamp, "ARCORE_CPU_IMAGE"))
                .setImageAssociation(CaptureValidation.ARCORE_CURRENT_FRAME_IMAGE)
                .setTrackingState(TrackingState.TRACKING).setPose(pose()).setIntrinsics(intrinsics).setCamera(camera)
                .setKeyframe(true).setRgb(rgb).setImageFormat(if (rgbEncoding == "i420") "YUV_420_888;packed-I420-on-keyframe" else "JPEG")
                .setImageWidth(width).setImageHeight(height)
                .addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_RAW).setAvailability(DepthAvailability.UNSUPPORTED))
                .addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_SMOOTHED).setAvailability(DepthAvailability.UNSUPPORTED))
            if (includeDepth) {
                val depthBytes = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0)
                val depthAsset = writer.writeAsset("assets/depth/$frameId.u16", depthBytes, "U16_LE;millimeters;axial-Z")
                val calibration = Intrinsics.newBuilder().setFx(1.0).setFy(1.0).setCx(1.0).setCy(1.0).setWidth(2).setHeight(2).setModel("ARCORE_DEPTH_RAW_PINHOLE").build()
                builder.clearDepth().addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_RAW).setAvailability(DepthAvailability.AVAILABLE)
                    .setTimestamp(timestamp(timestamp, "ARCORE_FRAME")).setWidth(2).setHeight(2).setIntrinsics(calibration)
                    .setAlignment("raw test depth").setUnitMeters(0.001).setDepth(depthAsset))
                    .addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_SMOOTHED).setAvailability(DepthAvailability.UNSUPPORTED))
            }
            writer.appendFrame(builder.build())
            if (includeNonKeyframe) {
                writer.appendFrame(CaptureFrame.newBuilder().setFrameId(frameId + 1).setArTimestamp(timestamp(timestamp + 1, "ARCORE_FRAME"))
                    .setTrackingState(TrackingState.PAUSED).setIntrinsics(intrinsics)
                    .addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_RAW).setAvailability(DepthAvailability.UNSUPPORTED))
                    .addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_SMOOTHED).setAvailability(DepthAvailability.UNSUPPORTED)).build())
            }
            writer.finish(timestamp + 10, CaptureState.COMPLETED)
        }
        return root
    }

    private fun timestamp(ns: Long, clock: String) = Timestamp.newBuilder().setValueNs(ns).setClockDomain(clock).setArrivalElapsedNs(ns).build()

    private fun pose(transform: RigidTransform = RigidTransform.identity()) = Pose.newBuilder()
        .setConvention(CaptureValidation.POSE_CONVENTION).addAllColumnMajor(transform.toColumnMajor()).build()

    private fun fileHashes(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate { file ->
        file.relativeTo(root).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }
}

class PairGraphTest {
    @Test
    fun temporalWindowBeyondFrameCountPreservesAllSequencePairs() {
        val frames=(1L..4L).map { graphFrame(it,it*10.0,RigidTransform.identity()) }
        val graph=PairGraphs.build(frames,PairGraphConfig(temporalWindow=Int.MAX_VALUE,maxLoopNeighbors=0))
        assertEquals(6,graph.pairs.size)
        assertTrue(graph.pairs.all { it.reason == "TEMPORAL" })
    }

    @Test
    fun includesSequenceNeighborsAndSpatialLoopClosuresDeterministically() {
        val frames = (1L..14L).map { id ->
            val x = when (id) { 1L -> 0.0; 14L -> 1.0; else -> id * 10.0 }
            graphFrame(id, x, RigidTransform.identity())
        }
        val graph = PairGraphs.build(frames)
        assertTrue(graph.pairs.any { it.first == "frame-1.png" && it.second == "frame-2.png" && it.reason.contains("TEMPORAL") })
        assertTrue(graph.pairs.any { it.first == "frame-1.png" && it.second == "frame-14.png" && it.reason == "SPATIAL_LOOP" })
        assertEquals(graph, PairGraphs.build(frames.reversed()))
    }

    @Test
    fun viewDirectionFiltersSpatialEdgesButNeverTemporalEdges() {
        val reversed = UnitQuaternion.axisAngle(Vector3(0.0, 1.0, 0.0), Math.PI).toTransform(Vector3(0.5, 0.0, 0.0))
        val frames = listOf(graphFrame(1, 0.0, RigidTransform.identity()), graphFrame(2, 100.0, RigidTransform.identity()),
            graphFrame(3, 200.0, RigidTransform.identity()), graphFrame(4, 300.0, RigidTransform.identity()),
            graphFrame(5, 400.0, RigidTransform.identity()), graphFrame(6, 0.5, reversed))
        val graph = PairGraphs.build(frames)
        assertTrue(graph.pairs.any { it.first == "frame-1.png" && it.second == "frame-6.png" && "TEMPORAL" in it.reason })
        assertFalse(graph.pairs.any { it.first == "frame-1.png" && it.second == "frame-6.png" && "SPATIAL_LOOP" in it.reason })
    }

    @Test
    fun crowdedCellsHaveBoundedCandidateWorkAndPairGrowth() {
        val count = 10_000
        val frames = (1L..count).map { id -> graphFrame(id, (id % 3) * 0.01, RigidTransform.identity()) }
        val graph = PairGraphs.build(frames)
        val checks = graph.statistics.getValue("spatialCandidateChecks") as Long
        assertTrue(checks <= count.toLong() * 27 * 32)
        assertTrue(graph.pairs.size <= count * (5 + 12))
        assertTrue((graph.statistics.getValue("maxCellRepresentatives") as Int) == 32)
        assertTrue((graph.statistics.getValue("sampledCellInsertions") as Long) > 0)
    }

    private fun graphFrame(id: Long, x: Double, transform: RigidTransform) = ReconstructionFrame(
        id, id, "ARCORE_FRAME", "frame-$id.png", File("frame-$id.png"), PinholeIntrinsics(2, 2, 1.0, 1.0, 1.0, 1.0),
        RigidTransform.translation(Vector3(x, 0.0, 0.0)) * transform, CaptureFrame.getDefaultInstance(),
    )
}
