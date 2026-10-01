package io.github.szymonbonkowski.myndhamr.reconstruction

import io.github.szymonbonkowski.myndhamr.domain.PinholeIntrinsics
import io.github.szymonbonkowski.myndhamr.domain.RigidTransform
import io.github.szymonbonkowski.myndhamr.scan.v1.CaptureFrame
import io.github.szymonbonkowski.myndhamr.scan.v1.CaptureManifest
import io.github.szymonbonkowski.myndhamr.scan.v1.DepthAvailability
import io.github.szymonbonkowski.myndhamr.scan.v1.TrackingState
import io.github.szymonbonkowski.myndhamr.store.CaptureProject
import io.github.szymonbonkowski.myndhamr.store.CaptureValidation
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream

data class ReconstructionFrame(
    val frameId: Long,
    val timestampNs: Long,
    val clockDomain: String,
    val imageName: String,
    val image: File,
    val intrinsics: PinholeIntrinsics,
    val worldFromCamera: RigidTransform,
    val raw: CaptureFrame,
)

data class ExcludedFrame(val frameId: Long, val reason: String)

data class ReconstructionInput(
    val projectId: String,
    val sourceManifestSha256: String,
    val frames: List<ReconstructionFrame>,
    val excluded: List<ExcludedFrame>,
    val totalFrames: Long,
)

/** Validates immutable v1 evidence, then writes normalized images and a versioned handoff. */
object Ingest {
    private const val MAX_DECODED_PIXELS = 64_000_000L
    private const val MAX_IMAGE_DIMENSION = 16_384

    fun prepare(source: File, destination: File): ReconstructionInput {
        require(source.exists()) { "Capture source does not exist: ${source.path}" }
        require(!destination.exists()) { "Reconstruction destination already exists: ${destination.path}" }
        val sourceCanonical = source.canonicalFile
        val destinationCanonical = destination.canonicalFile
        if (source.isDirectory) require(!destinationCanonical.path.startsWith(sourceCanonical.path + File.separator)) {
            "Reconstruction destination must be outside the source project"
        }
        require(destinationCanonical != sourceCanonical) { "Reconstruction destination must differ from capture source" }

        val importedTemp = if (source.isDirectory) null else Files.createTempDirectory("myndhamr-ingest-").toFile()
        val projectRoot = if (source.isDirectory) source else File(importedTemp, "project")
        try {
            if (!source.isDirectory) {
                val report = CaptureProject.importPackage(source, projectRoot)
                require(report.valid) { "Invalid capture package: ${report.diagnostics.joinToString("; ")}" }
            }
            // Revalidate the complete source before decoding or selecting a single frame.
            val validation = CaptureProject.validate(projectRoot)
            require(validation.valid) {
                "Invalid capture project: ${validation.diagnostics.joinToString("; ").ifBlank { "validation failed without diagnostics" }}"
            }
            val manifest = CaptureProject.readManifest(projectRoot)
            val manifestHash = sha256(File(projectRoot, CaptureProject.MANIFEST))
            require(destinationCanonical.parentFile?.isDirectory == true || destinationCanonical.parentFile?.mkdirs() == true) {
                "Cannot create reconstruction destination parent"
            }
            require(destination.mkdirs()) { "Cannot create reconstruction destination" }
            val imagesDir = File(destination, "images").also { require(it.mkdirs()) { "Cannot create images directory" } }
            val evidenceDir = File(destination, "evidence").also { require(it.mkdirs()) { "Cannot create evidence directory" } }

            val normalized = mutableListOf<ReconstructionFrame>()
            val excluded = mutableListOf<ExcludedFrame>()
            val metadata = mutableListOf<Map<String, Any?>>()
            CaptureProject.visitFrames(projectRoot) { raw ->
                if (!raw.keyframe) {
                    excluded += ExcludedFrame(raw.frameId, if (raw.trackingState == TrackingState.TRACKING) "NOT_MANUAL_KEYFRAME" else "NOT_MANUAL_KEYFRAME;INVALID_TRACKING")
                    return@visitFrames
                }
                require(raw.trackingState == TrackingState.TRACKING && raw.hasPose()) {
                    "Frame ${raw.frameId}: manual keyframe lacks a valid TRACKING pose"
                }
                require(raw.hasRgb() && raw.hasImageTimestamp() && raw.hasIntrinsics()) {
                    "Frame ${raw.frameId}: manual keyframe lacks RGB, image timestamp, or calibration"
                }
                val calibration = raw.intrinsics
                require(calibration.model == "pinhole" || calibration.model == "ARCORE_CPU_IMAGE_PINHOLE;unrotated-pixels") {
                    "Frame ${raw.frameId}: unsupported camera model '${calibration.model}'"
                }
                require(raw.imageWidth == calibration.width && raw.imageHeight == calibration.height) {
                    "Frame ${raw.frameId}: image metadata dimensions do not match calibration"
                }
                val intrinsics = PinholeIntrinsics(calibration.width, calibration.height, calibration.fx, calibration.fy, calibration.cx, calibration.cy)
                val worldFromCamera = try {
                    RigidTransform.fromColumnMajor(raw.pose.columnMajorList, tolerance = 1e-4)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("Frame ${raw.frameId}: invalid worldFromCamera pose: ${e.message}", e)
                }
                val sourceRgb = safeAsset(projectRoot, raw.rgb.path, raw.frameId, "RGB")
                val imageName = "frame-${raw.frameId}.png"
                val outputImage = File(imagesDir, imageName)
                val imageEncoding = normalizeImage(sourceRgb, raw.rgb.encoding, raw.imageWidth, raw.imageHeight, outputImage, raw.frameId)
                val rgbSha = sha256(sourceRgb)
                val timestamp = raw.imageTimestamp
                val reconstructionFrame = ReconstructionFrame(raw.frameId, timestamp.valueNs, timestamp.clockDomain,
                    imageName, outputImage, intrinsics, worldFromCamera, raw)
                normalized += reconstructionFrame

                val depthEvidence = raw.depthList.flatMapIndexed { index, depth ->
                    val entries = mutableListOf<Map<String, Any?>>()
                    if (depth.availability == DepthAvailability.AVAILABLE) {
                        if (depth.hasDepth()) entries += copyEvidence(projectRoot, evidenceDir, depth.depth.path, raw.frameId, index, "depth")
                        if (depth.hasConfidence()) entries += copyEvidence(projectRoot, evidenceDir, depth.confidence.path, raw.frameId, index, "confidence")
                    }
                    entries
                }
                metadata += linkedMapOf(
                    "frameId" to raw.frameId.toString(),
                    "timestampNs" to timestamp.valueNs.toString(),
                    "clockDomain" to timestamp.clockDomain,
                    "arTimestampNs" to raw.arTimestamp.valueNs.toString(),
                    "arClockDomain" to raw.arTimestamp.clockDomain,
                    "imageTimestampNs" to timestamp.valueNs.toString(),
                    "imageClockDomain" to timestamp.clockDomain,
                    "name" to imageName,
                    "width" to intrinsics.width,
                    "height" to intrinsics.height,
                    "fx" to intrinsics.fx,
                    "fy" to intrinsics.fy,
                    "cx" to intrinsics.cx,
                    "cy" to intrinsics.cy,
                    "worldFromCameraColumnMajor" to worldFromCamera.toColumnMajor(),
                    "sourceRgbPath" to raw.rgb.path,
                    "sourceRgbSha256" to rgbSha,
                    "sourceImageEncoding" to raw.rgb.encoding,
                    "normalizedImageEncoding" to imageEncoding,
                    "depthEvidence" to depthEvidence,
                    "depthRecords" to raw.depthList.mapIndexed { index, depth ->
                        val matchingAssets = depthEvidence.filter { it["depthIndex"] == index }
                        val depthAsset = matchingAssets.firstOrNull { it["kind"] == "depth" }
                        val confidenceAsset = matchingAssets.firstOrNull { it["kind"] == "confidence" }
                        linkedMapOf(
                            "source" to depth.source.name,
                            "availability" to depth.availability.name,
                            "width" to depth.width,
                            "height" to depth.height,
                            "unitMeters" to depth.unitMeters,
                            "timestampNs" to if (depth.hasTimestamp()) depth.timestamp.valueNs.toString() else null,
                            "clockDomain" to if (depth.hasTimestamp()) depth.timestamp.clockDomain else null,
                            "confidenceTimestampNs" to if (depth.hasConfidenceTimestamp()) depth.confidenceTimestamp.valueNs.toString() else null,
                            "confidenceClockDomain" to if (depth.hasConfidenceTimestamp()) depth.confidenceTimestamp.clockDomain else null,
                            "cpuToDepthColumnMajor" to depth.cpuToDepthColumnMajorList,
                            "mappingConvention" to depth.mappingConvention,
                            "alignment" to depth.alignment,
                            "depthPath" to depthAsset?.get("evidencePath"),
                            "confidencePath" to confidenceAsset?.get("evidencePath"),
                            "confidenceAvailability" to depth.confidenceAvailability.name,
                            "depthSha256" to depthAsset?.get("sha256"),
                            "confidenceSha256" to confidenceAsset?.get("sha256"),
                            "detail" to depth.detail,
                        )
                    },
                )
            }
            require(normalized.isNotEmpty()) { "Capture contains no eligible manual keyframes" }
            val input = ReconstructionInput(manifest.projectId, manifestHash, normalized.toList(), excluded.toList(), validation.frames)
            val document = linkedMapOf<String, Any?>(
                "schema" to 1,
                "projectId" to input.projectId,
                "sourceManifestSha256" to input.sourceManifestSha256,
                "eligibleFrames" to input.frames.size,
                "totalFrames" to input.totalFrames.toString(),
                "exclusions" to input.excluded.map { linkedMapOf("frameId" to it.frameId.toString(), "reason" to it.reason) },
                "images" to metadata,
            )
            File(destination, "input.json").writeText(reconstructionJson(document) + "\n", Charsets.UTF_8)
            return input
        } catch (e: Exception) {
            if (destination.exists()) destination.deleteRecursively()
            throw e
        } finally {
            importedTemp?.deleteRecursively()
        }
    }

    private fun normalizeImage(source: File, encoding: String, expectedWidth: Int, expectedHeight: Int, target: File, frameId: Long): String {
        val normalizedEncoding = encoding.trim().lowercase()
        if (normalizedEncoding == "i420" || normalizedEncoding == "yuv420-i420") {
            require(expectedWidth % 2 == 0 && expectedHeight % 2 == 0) { "Frame $frameId: I420 requires even dimensions" }
            val pixels = expectedWidth.toLong() * expectedHeight
            require(expectedWidth <= MAX_IMAGE_DIMENSION && expectedHeight <= MAX_IMAGE_DIMENSION && pixels <= MAX_DECODED_PIXELS) {
                "Frame $frameId: I420 dimensions exceed decode budget"
            }
            val expectedBytes = pixels + pixels / 2
            require(source.length() == expectedBytes) { "Frame $frameId: I420 byte length does not match dimensions" }
            val y = ByteArray(pixels.toInt())
            FileInputStream(source).use { input ->
                var offset = 0
                while (offset < y.size) {
                    val count = input.read(y, offset, y.size - offset)
                    require(count > 0) { "Frame $frameId: truncated I420 luma plane" }
                    offset += count
                }
            }
            val gray = BufferedImage(expectedWidth, expectedHeight, BufferedImage.TYPE_BYTE_GRAY)
            val raster = gray.raster
            for (row in 0 until expectedHeight) for (column in 0 until expectedWidth) {
                raster.setSample(column, row, 0, y[row * expectedWidth + column].toInt() and 0xff)
            }
            require(ImageIO.write(gray, "png", target)) { "Frame $frameId: PNG writer unavailable" }
            return "image/png;grayscale-from-i420-luma"
        }

        val imageType = when {
            normalizedEncoding == "image/jpeg" || normalizedEncoding == "jpeg" || normalizedEncoding == "jpg" -> "jpeg"
            normalizedEncoding == "image/png" || normalizedEncoding == "png" -> "png"
            else -> throw IllegalArgumentException("Frame $frameId: unsupported RGB encoding '$encoding'")
        }
        val decoded = readBoundedImage(source, frameId, imageType)
        require(decoded.width == expectedWidth && decoded.height == expectedHeight) {
            "Frame $frameId: decoded image dimensions ${decoded.width}x${decoded.height} do not match metadata ${expectedWidth}x$expectedHeight"
        }
        require(ImageIO.write(decoded, "png", target)) { "Frame $frameId: PNG writer unavailable" }
        return "image/png;source-$imageType"
    }

    private fun readBoundedImage(file: File, frameId: Long, expectedType: String): BufferedImage {
        val stream: ImageInputStream = ImageIO.createImageInputStream(file)
            ?: throw IllegalArgumentException("Frame $frameId: unsupported or corrupt image stream")
        stream.use { input ->
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext()) { "Frame $frameId: unsupported or corrupt image encoding" }
            val reader: ImageReader = readers.next()
            try {
                reader.input = input
                val actualType = reader.formatName.lowercase()
                val expectedMatches = if (expectedType == "jpeg") actualType in setOf("jpeg", "jpg") else actualType == "png"
                require(expectedMatches) { "Frame $frameId: declared $expectedType encoding contains $actualType data" }
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                require(width > 0 && height > 0 && width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION &&
                    width.toLong() * height <= MAX_DECODED_PIXELS) { "Frame $frameId: decoded image dimensions exceed resource budget" }
                return reader.read(0) ?: throw IllegalArgumentException("Frame $frameId: decoder returned no pixels")
            } catch (e: Exception) {
                if (e is IllegalArgumentException) throw e
                throw IllegalArgumentException("Frame $frameId: image decode failed: ${e.message}", e)
            } finally { reader.dispose() }
        }
    }

    private fun copyEvidence(root: File, evidenceRoot: File, path: String, frameId: Long, depthIndex: Int, kind: String): Map<String, Any?> {
        val source = safeAsset(root, path, frameId, "depth evidence")
        val relative = "evidence/$path"
        val target = File(evidenceRoot, path)
        require(target.parentFile.mkdirs() || target.parentFile.isDirectory) { "Frame $frameId: cannot create evidence directory" }
        if (!target.exists()) source.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
        else require(target.length() == source.length() && sha256(target) == sha256(source)) { "Frame $frameId: conflicting evidence asset path $path" }
        return linkedMapOf("depthIndex" to depthIndex, "kind" to kind, "sourcePath" to path,
            "evidencePath" to relative, "sha256" to sha256(source))
    }

    private fun safeAsset(root: File, path: String, frameId: Long, label: String): File {
        val normalized = path.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') && normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Frame $frameId: unsafe $label asset path '$path'"
        }
        val file = File(root, normalized).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator) && file.isFile) { "Frame $frameId: missing or unsafe $label asset '$path'" }
        return file
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
