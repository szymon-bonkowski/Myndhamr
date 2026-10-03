package io.github.szymonbonkowski.myndhamr.reconstruction

import io.github.szymonbonkowski.myndhamr.scan.v1.CaptureFrame
import io.github.szymonbonkowski.myndhamr.scan.v1.CaptureManifest
import io.github.szymonbonkowski.myndhamr.scan.v1.CaptureState
import io.github.szymonbonkowski.myndhamr.scan.v1.CameraObservation
import io.github.szymonbonkowski.myndhamr.scan.v1.DepthAvailability
import io.github.szymonbonkowski.myndhamr.scan.v1.DepthRecord
import io.github.szymonbonkowski.myndhamr.scan.v1.DepthSource
import io.github.szymonbonkowski.myndhamr.scan.v1.Intrinsics
import io.github.szymonbonkowski.myndhamr.scan.v1.Pose
import io.github.szymonbonkowski.myndhamr.scan.v1.Timestamp
import io.github.szymonbonkowski.myndhamr.scan.v1.TrackingState
import io.github.szymonbonkowski.myndhamr.store.CaptureProject
import io.github.szymonbonkowski.myndhamr.store.CaptureValidation
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObjectCommandTest {
    @Test fun rejectsUnknownAndDuplicateOptionsBeforeCreatingOutput() {
        val root = Files.createTempDirectory("object-command-args-").toFile()
        try {
            val output = File(root, "object-run")
            val unknown = execute(listOf("object", "missing.scan3d", output.path, "--bad", "1"))
            assertEquals(2, unknown.status)
            assertContains(unknown.stderr, "Unknown object option")
            val duplicate = execute(listOf("object", "missing.scan3d", output.path, "--max-image-size", "100", "--max-image-size", "200"))
            assertEquals(2, duplicate.status)
            assertContains(duplicate.stderr, "Duplicate object option")
            assertFalse(output.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun validatesScanAndRejectsObjectRunInsideRawProject() {
        val root = Files.createTempDirectory("object-command-path-").toFile()
        try {
            val scan = createProject(File(root, "scan"))
            val before = scan.walkTopDown().filter { it.isFile }.associate { it.relativeTo(scan).path to sha256(it) }
            val output = File(scan, "object-run")
            val result = execute(listOf("object", scan.path, output.path, "--repository", root.path))
            assertEquals(2, result.status)
            assertContains(result.stderr, "outside the raw scan")
            assertFalse(output.exists())
            assertEquals(before, scan.walkTopDown().filter { it.isFile }.associate { it.relativeTo(scan).path to sha256(it) })
        } finally { root.deleteRecursively() }
    }

    @Test fun reusingSparseRequiresMatchingProvenanceAndDenseRuntime() {
        val root = Files.createTempDirectory("object-command-provenance-").toFile()
        try {
            val scan = createProject(File(root, "scan"))
            val sparse = File(root, "sparse-run").apply { mkdirs() }
            val hash = sha256(File(scan, CaptureProject.MANIFEST))
            File(sparse, "diagnostics.json").writeText("""{"sourceManifestSha256":"$hash","status":"metric_aligned"}""")
            val output = File(root, "object-run")
            val result = execute(listOf(
                "object", scan.path, output.path, "--sparse", sparse.path,
                "--python", File(root, "missing-python").path,
                "--repository", root.path,
            ))
            assertEquals(2, result.status)
            assertContains(result.stderr, "Pinned dense Python runtime missing")
            assertFalse(output.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun objectReusesItsSiblingSparseRun() {
        val root = Files.createTempDirectory("object-command-sibling-sparse-").toFile()
        try {
            val scan = createProject(File(root, "scan"))
            val objectRun = File(root, "object-run")
            val sparse = File(root, "object-run-sparse").apply { mkdirs() }
            File(sparse, "diagnostics.json").writeText("""{"sourceManifestSha256":"${sha256(File(scan, CaptureProject.MANIFEST))}"}""")
            val result = execute(listOf("object", scan.path, objectRun.path, "--python", File(root, "missing-python").path,
                "--repository", root.path))
            assertEquals(2, result.status)
            assertContains(result.stderr, "Pinned dense Python runtime missing")
            assertTrue(File(sparse, "diagnostics.json").isFile)
            assertFalse(objectRun.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun parsesConservativeDefaultsAndRejectsInvalidExportConfiguration() {
        val root = Files.createTempDirectory("object-command-config-").toFile()
        try {
            val scan = createProject(File(root, "scan"))
            val sparse = File(root, "sparse-run").apply { mkdirs() }
            File(sparse, "diagnostics.json").writeText("""{"sourceManifestSha256":"${sha256(File(scan, CaptureProject.MANIFEST))}"}""")
            val invalid = execute(listOf(
                "dense", sparse.path, File(root, "object-run").path,
                "--formats", "ply,fbx", "--repository", root.path,
            ))
            assertEquals(2, invalid.status)
            assertContains(invalid.stderr, "--formats must be a unique list")
            assertFalse(File(root, "object-run").exists())
        } finally { root.deleteRecursively() }
    }

    private data class Result(val status: Int, val stdout: String, val stderr: String)
    private fun execute(args: List<String>): Result {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val status = ObjectCommand.run(args, PrintStream(out), PrintStream(err))
        return Result(status, out.toString(), err.toString())
    }

    private fun createProject(root: File): File {
        val manifest = CaptureManifest.newBuilder().setFormatVersion(1).setProjectId("object-command-test")
            .setDevice("test").setStartedNs(1).setState(CaptureState.RECORDING)
            .setPoseConvention(CaptureValidation.POSE_CONVENTION).setImageConvention(CaptureValidation.IMAGE_CONVENTION).build()
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        CaptureProject.create(root, manifest).use { writer ->
            val asset = writer.writeAsset("assets/rgb/1.png", bytes, "image/png")
            fun time(clock: String) = Timestamp.newBuilder().setValueNs(2).setClockDomain(clock).setArrivalElapsedNs(2).build()
            val cameraTime = time("CAMERA_REALTIME")
            val frameTime = time("ARCORE_FRAME")
            val imageTime = time("ARCORE_CPU_IMAGE")
            val camera = CameraObservation.newBuilder().setTimestamp(cameraTime).setFrameNumber(1).setTimestampSource("CAMERA_REALTIME").build()
            val intrinsics = Intrinsics.newBuilder().setWidth(2).setHeight(2).setFx(1.0).setFy(1.0).setCx(1.0).setCy(1.0)
                .setModel("pinhole").build()
            val pose = Pose.newBuilder().setConvention(CaptureValidation.POSE_CONVENTION)
                .addAllColumnMajor(listOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)).build()
            writer.appendFrame(CaptureFrame.newBuilder().setFrameId(1).setArTimestamp(frameTime).setCameraTimestamp(cameraTime).setImageTimestamp(imageTime)
                .setImageAssociation(CaptureValidation.ARCORE_CURRENT_FRAME_IMAGE).setCamera(camera)
                .setTrackingState(TrackingState.TRACKING).setPose(pose).setIntrinsics(intrinsics)
                .setKeyframe(true).setRgb(asset).setImageWidth(2).setImageHeight(2).setImageFormat("PNG")
                .addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_RAW).setAvailability(DepthAvailability.UNSUPPORTED))
                .addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_SMOOTHED).setAvailability(DepthAvailability.UNSUPPORTED)).build())
            writer.finish(3, CaptureState.COMPLETED)
        }
        return root
    }

    private fun sha256(file: File) = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
