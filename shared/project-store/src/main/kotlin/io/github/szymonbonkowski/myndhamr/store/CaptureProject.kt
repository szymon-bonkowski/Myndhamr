package io.github.szymonbonkowski.myndhamr.store

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import io.github.szymonbonkowski.myndhamr.scan.v1.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** All byte limits are resource budgets, configurable without a photo-count policy. */
data class StoreLimits(val maxRecordBytes: Int = 4 * 1024 * 1024, val maxManifestBytes: Int = 1024 * 1024,
    val maxImportBytes: Long = 100L * 1024 * 1024 * 1024, val maxImportEntries: Long = 1_000_000)

data class ValidationReport(val manifest: CaptureManifest?, val frames: Long, val keyframes: Long, val imuSamples: Long,
    val cameraObservations: Long, val events: Long, val trackedFrames: Long, val depthFrames: Long,
    val maximumImuGapNs: Long, val timestampRegressions: Long, val errorCount: Long, val diagnostics: List<String>) {
    val valid: Boolean get() = errorCount == 0L
}

/** JVM/Android implementation; one sequential worker owns each writer. */
object CaptureProject {
    const val MANIFEST = "manifest.pb"
    const val FRAMES = "metadata/frames.pb"
    const val IMU = "metadata/imu.pb"
    const val CAMERA = "metadata/camera.pb"
    const val EVENTS = "metadata/events.pb"
    internal val streamPaths = listOf(FRAMES, IMU, CAMERA, EVENTS)

    fun create(root: File, manifest: CaptureManifest, limits: StoreLimits = StoreLimits()): CaptureWriter {
        CaptureValidation.manifest(manifest)
        require(manifest.state == CaptureState.RECORDING && manifest.streamsCount == 0)
        require(manifest.serializedSize <= limits.maxManifestBytes)
        require(!root.exists() || root.isDirectory && root.list()?.isEmpty() == true) { "Project destination is not empty" }
        require(root.mkdirs() || root.isDirectory)
        atomicWrite(root, MANIFEST, manifest.toByteArray())
        return CaptureWriter(root, manifest, limits)
    }

    fun readManifest(root: File, limits: StoreLimits = StoreLimits()): CaptureManifest {
        val file = safeFile(root, MANIFEST)
        require(file.length() <= limits.maxManifestBytes) { "Oversized manifest" }
        return CaptureManifest.parseFrom(file.readBytes()).also { CaptureValidation.manifest(it) }
    }

    /** Only incomplete final records are recoverable; complete malformed records fail explicitly. */
    fun recover(root: File, limits: StoreLimits = StoreLimits()): CaptureWriter {
        val manifest = readManifest(root, limits)
        require(manifest.state == CaptureState.RECORDING) { "Finalized project is immutable" }
        for (path in streamPaths) {
            val file = safeFile(root, path)
            if (!file.exists()) continue
            val parser: Parser<out MessageLite> = when (path) {
                FRAMES -> CaptureFrame.parser(); IMU -> ImuSample.parser(); CAMERA -> CameraObservation.parser(); else -> SessionEvent.parser()
            }
            val end = scanJournal(file, parser, limits, allowIncomplete = true) { record ->
                when (record) {
                    is CaptureFrame -> {
                        CaptureValidation.frame(record)
                        if (record.hasRgb()) verifyAsset(root, record.rgb)
                        record.depthList.forEach { if (it.hasDepth()) verifyAsset(root, it.depth); if (it.hasConfidence()) verifyAsset(root, it.confidence) }
                    }
                    is ImuSample -> CaptureValidation.imu(record)
                    is CameraObservation -> CaptureValidation.camera(record)
                    is SessionEvent -> CaptureValidation.event(record)
                }
            }
            if (end < file.length()) {
                val tail = safeFile(root, "recovery/${File(path).name}.${System.nanoTime()}.tail")
                tail.parentFile.mkdirs()
                RandomAccessFile(file, "r").use { input -> input.seek(end); FileOutputStream(tail).use { output -> copy(input, output) } }
                RandomAccessFile(file, "rw").use { it.setLength(end); it.fd.sync() }
            }
        }
        val interruptedManifest = safeFile(root, "$MANIFEST.tmp")
        if (interruptedManifest.exists()) {
            val preserved = safeFile(root, "recovery/manifest.${System.nanoTime()}.tail")
            preserved.parentFile.mkdirs()
            require(interruptedManifest.renameTo(preserved)) { "Cannot preserve interrupted manifest" }
        }
        return CaptureWriter(root, manifest, limits)
    }

    fun visitFrames(root: File, limits: StoreLimits = StoreLimits(), visitor: (CaptureFrame) -> Unit) = visit(root, FRAMES, CaptureFrame.parser(), limits, visitor)
    fun visitImu(root: File, limits: StoreLimits = StoreLimits(), visitor: (ImuSample) -> Unit) = visit(root, IMU, ImuSample.parser(), limits, visitor)
    fun visitCamera(root: File, limits: StoreLimits = StoreLimits(), visitor: (CameraObservation) -> Unit) = visit(root, CAMERA, CameraObservation.parser(), limits, visitor)
    fun visitEvents(root: File, limits: StoreLimits = StoreLimits(), visitor: (SessionEvent) -> Unit) = visit(root, EVENTS, SessionEvent.parser(), limits, visitor)

    private fun <T : MessageLite> visit(root: File, path: String, parser: Parser<T>, limits: StoreLimits, visitor: (T) -> Unit) {
        scanJournal(safeFile(root, path), parser, limits, false, visitor)
    }

    fun validate(root: File, limits: StoreLimits = StoreLimits()): ValidationReport {
        var errors = 0L
        val diagnostics = mutableListOf<String>()
        fun issue(message: String) { errors++; if (diagnostics.size < 100) diagnostics += message }
        fun check(block: () -> Unit) { try { block() } catch (e: Exception) { issue(e.message ?: e.javaClass.simpleName) } }
        var manifest: CaptureManifest? = null
        check { manifest = readManifest(root, limits) }
        val verifiedAssets = object : LinkedHashMap<Asset, Boolean>(128, .75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Asset, Boolean>?) = size > 128 }
        fun asset(value: Asset) { if (!verifiedAssets.containsKey(value)) { check { verifyAsset(root, value) }; verifiedAssets[value] = true } }
        manifest?.let { value ->
            if (value.state == CaptureState.RECORDING) issue("Project is not finalized")
            if (value.streamsList.map { it.path }.sorted() != streamPaths.sorted()) issue("Manifest must reference exactly four raw streams")
            value.streamsList.forEach(::asset)
        }
        var frames = 0L; var keyframes = 0L; var imu = 0L; var camera = 0L; var events = 0L; var tracked = 0L; var depthFrames = 0L
        var previousFrameId = 0L; var maxImuGap = 0L; var regressions = 0L
        val imuTimes = mutableMapOf<String, Long>()
        check { visitFrames(root, limits) { value ->
            frames++; check { CaptureValidation.frame(value) }
            if (value.frameId <= previousFrameId) issue("Frame IDs are not strictly increasing")
            previousFrameId = value.frameId
            if (value.keyframe) keyframes++
            if (value.trackingState == TrackingState.TRACKING) tracked++
            if (value.hasRgb()) asset(value.rgb)
            if (value.depthList.any { it.availability == DepthAvailability.AVAILABLE }) depthFrames++
            value.depthList.forEach { if (it.hasDepth()) asset(it.depth); if (it.hasConfidence()) asset(it.confidence) }
        } }
        check { visitImu(root, limits) { value ->
            imu++; check { CaptureValidation.imu(value) }
            val key = "${value.type}:${value.timestamp.clockDomain}"
            // Bounded even for deliberately hostile clock/type strings.
            if (imuTimes.size < 64 || imuTimes.containsKey(key)) {
                val previous = imuTimes.put(key, value.timestamp.valueNs)
                if (previous != null) { val gap = value.timestamp.valueNs - previous; if (gap < 0) regressions++ else maxImuGap = maxOf(maxImuGap, gap) }
            } else issue("Too many IMU timestamp domains")
        } }
        check { visitCamera(root, limits) { camera++; check { CaptureValidation.camera(it) } } }
        check { visitEvents(root, limits) { events++; check { CaptureValidation.event(it) } } }
        if (regressions > 0) issue("IMU timestamps regress $regressions times within a source clock")
        return ValidationReport(manifest, frames, keyframes, imu, camera, events, tracked, depthFrames, maxImuGap, regressions, errors, diagnostics)
    }

    fun export(root: File, destination: File, limits: StoreLimits = StoreLimits()) {
        val report = validate(root, limits)
        require(report.valid) { "Invalid project: ${report.diagnostics.joinToString()}" }
        require(!destination.exists()) { "Export destination already exists" }
        val temp = File(destination.parentFile, "${destination.name}.tmp")
        require(!temp.exists())
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { output ->
                val canonicalRoot = root.canonicalFile
                canonicalRoot.walkTopDown().onEnter { directory -> require(directory.canonicalFile == directory.absoluteFile) { "Symlink directory in project" }; true }.filter { it.isFile }.forEach { file ->
                    val path = file.relativeTo(canonicalRoot).invariantSeparatorsPath
                    if (path == MANIFEST || path in streamPaths || path.startsWith("assets/") && !path.endsWith(".tmp")) {
                        require(safeFile(root, path).canonicalFile == file.absoluteFile) { "Symlink in project" }
                        output.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(output) }; output.closeEntry()
                    }
                }
            }
            FileOutputStream(temp, true).use { it.fd.sync() }
            require(temp.renameTo(destination)) { "Cannot commit export" }
        } catch (e: Exception) { temp.delete(); throw e }
    }

    /** Import into a new directory. Actual expanded bytes, duplicate names and every path are checked. */
    fun importPackage(archive: File, destination: File, limits: StoreLimits = StoreLimits()): ValidationReport {
        require(!destination.exists()) { "Import destination already exists" }
        require(destination.mkdirs())
        try {
            var bytes = 0L; var entries = 0L
            ZipInputStream(BufferedInputStream(archive.inputStream())).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    require(++entries <= limits.maxImportEntries) { "Archive entry budget exceeded" }
                    val name = entry.name.removeSuffix("/")
                    val file = safeFile(destination, name)
                    require(name == MANIFEST || name in streamPaths || name.startsWith("assets/") || entry.isDirectory && name in listOf("assets", "metadata")) { "Unexpected archive path $name" }
                    require(!file.exists()) { "Duplicate archive path $name" }
                    if (entry.isDirectory) require(file.mkdirs()) else {
                        require(file.parentFile.mkdirs() || file.parentFile.isDirectory)
                        FileOutputStream(file).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) { val count = input.read(buffer); if (count < 0) break; require(bytes <= limits.maxImportBytes - count) { "Archive expanded-byte budget exceeded" }; bytes += count; output.write(buffer, 0, count) }
                            output.fd.sync()
                        }
                    }
                    input.closeEntry()
                }
            }
            val report = validate(destination, limits)
            require(report.valid) { "Invalid imported project: ${report.diagnostics.joinToString()}" }
            return report
        } catch (e: Exception) { destination.deleteRecursively(); throw e }
    }

    internal fun safeFile(root: File, path: String): File {
        require(path.isNotBlank() && !path.startsWith("/") && '\\' !in path && ':' !in path && path.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Unsafe relative path $path" }
        // The caller-selected root may itself have platform aliases (Android filesDir).
        // Trust its canonical identity; disallow symlinks only inside that root.
        val canonicalRoot = root.canonicalFile
        val file = File(canonicalRoot, path).absoluteFile
        require(file.canonicalFile == file && file.canonicalPath.startsWith(canonicalRoot.path + File.separator)) { "Path escapes project or traverses symlink: $path" }
        return file
    }
    internal fun hash(file: File): ByteString {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return ByteString.copyFrom(digest.digest())
    }
    internal fun reference(root: File, path: String, encoding: String): Asset {
        val file = safeFile(root, path)
        return Asset.newBuilder().setPath(path).setSize(file.length()).setSha256(hash(file)).setEncoding(encoding).build()
    }
    internal fun verifyAsset(root: File, asset: Asset) {
        require(asset.path in streamPaths || asset.path.startsWith("assets/")) { "Invalid asset namespace" }
        val file = safeFile(root, asset.path)
        require(asset.size >= 0 && asset.sha256.size() == 32 && asset.encoding.isNotBlank()) { "Invalid asset reference ${asset.path}" }
        require(file.isFile && file.length() == asset.size) { "Missing/wrong-size asset ${asset.path}" }
        require(hash(file) == asset.sha256) { "SHA-256 mismatch ${asset.path}" }
    }
    internal fun atomicWrite(root: File, path: String, bytes: ByteArray) {
        val file = safeFile(root, path); require(file.parentFile.mkdirs() || file.parentFile.isDirectory)
        val temp = safeFile(root, "$path.tmp")
        require(!temp.exists()) { "Interrupted asset exists: $path.tmp" }
        try { FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }; require(temp.renameTo(file)) { "Cannot commit $path" } }
        catch (e: Exception) { temp.delete(); throw e }
    }
    private fun copy(input: RandomAccessFile, output: OutputStream) { val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) } }

    internal fun <T : MessageLite> scanJournal(file: File, parser: Parser<T>, limits: StoreLimits, allowIncomplete: Boolean, visitor: (T) -> Unit): Long {
        RandomAccessFile(file, "r").use { input ->
            var committed = 0L
            while (input.filePointer < input.length()) {
                var length = 0L; var shift = 0; var terminated = false
                while (shift < 35 && input.filePointer < input.length()) {
                    val byte = input.readUnsignedByte(); length = length or ((byte and 127).toLong() shl shift); shift += 7
                    if (byte and 128 == 0) { terminated = true; break }
                }
                if (!terminated && shift < 35 && allowIncomplete) return committed
                require(terminated && length in 1..limits.maxRecordBytes.toLong()) { "Invalid journal record length in ${file.name}" }
                if (length > input.length() - input.filePointer) { if (allowIncomplete) return committed; error("Truncated journal ${file.name}") }
                val bytes = ByteArray(length.toInt()); input.readFully(bytes); visitor(parser.parseFrom(bytes)); committed = input.filePointer
            }
            return committed
        }
    }
}

/** A narrow internal seam for deterministic partial-write and sync-failure tests. */
internal class JournalHandle(val output: OutputStream, val syncToDisk: () -> Unit) : Closeable {
    override fun close() = output.close()
}

class CaptureWriter internal constructor(
    private val root: File,
    private val initial: CaptureManifest,
    private val limits: StoreLimits,
    openJournal: (File) -> JournalHandle = { file ->
        val stream = FileOutputStream(file, true)
        JournalHandle(stream) { stream.fd.sync() }
    },
) : Closeable {
    private val outputs = linkedMapOf<String, JournalHandle>()
    private var closed = false
    private var poisoned: IOException? = null
    private var previousFrameId = 0L

    init {
        try {
            CaptureProject.streamPaths.forEach { path ->
                val file = CaptureProject.safeFile(root, path)
                require(file.parentFile.mkdirs() || file.parentFile.isDirectory)
                outputs[path] = openJournal(file)
            }
            if (File(root, CaptureProject.FRAMES).length() > 0) CaptureProject.visitFrames(root, limits) { previousFrameId = it.frameId }
        } catch (failure: Throwable) {
            closeHandles(failure)
            throw failure
        }
    }

    private fun writable() {
        poisoned?.let { throw IllegalStateException("Capture writer has failed; recover the recording project before further writes", it) }
        check(!closed) { "Capture writer closed" }
    }

    /** Once a partial append occurs it must remain the final journal tail for prefix recovery. */
    private fun poison(failure: IOException) {
        poisoned = failure
        closeHandles(failure)
    }

    /** Close every opened handle even if one close fails, preserving the original failure. */
    private fun closeHandles(primary: Throwable? = null): Throwable? {
        closed = true
        var failure = primary
        outputs.values.forEach { handle ->
            try { handle.close() } catch (closing: Throwable) {
                if (failure == null) failure = closing else if (failure !== closing) failure.addSuppressed(closing)
            }
        }
        return failure
    }

    private fun append(path: String, value: MessageLite) {
        writable()
        require(value.serializedSize in 1..limits.maxRecordBytes) { "Record exceeds configured budget" }
        try { value.writeDelimitedTo(outputs.getValue(path).output) }
        catch (failure: IOException) { poison(failure); throw failure }
    }
    fun appendFrame(value: CaptureFrame) { writable(); CaptureValidation.frame(value); require(value.frameId > previousFrameId) { "Frame ID must increase" }; references(value).forEach { CaptureProject.verifyAsset(root, it) }; append(CaptureProject.FRAMES, value); previousFrameId = value.frameId }
    fun appendImu(value: ImuSample) { writable(); CaptureValidation.imu(value); append(CaptureProject.IMU, value) }
    fun appendCamera(value: CameraObservation) { writable(); CaptureValidation.camera(value); append(CaptureProject.CAMERA, value) }
    fun appendEvent(value: SessionEvent) { writable(); CaptureValidation.event(value); append(CaptureProject.EVENTS, value) }
    fun writeAsset(path: String, bytes: ByteArray, encoding: String): Asset {
        writable(); require(path.startsWith("assets/") && !path.endsWith(".tmp") && encoding.isNotBlank() && bytes.isNotEmpty())
        require(!CaptureProject.safeFile(root, path).exists()) { "Raw asset is immutable: $path" }
        CaptureProject.atomicWrite(root, path, bytes)
        return CaptureProject.reference(root, path, encoding)
    }
    fun sync() {
        writable()
        try { outputs.values.forEach { it.syncToDisk() } }
        catch (failure: IOException) { poison(failure); throw failure }
    }
    fun finish(endNs: Long, state: CaptureState): CaptureManifest {
        writable(); require(state in listOf(CaptureState.COMPLETED, CaptureState.INTERRUPTED, CaptureState.FAILED)); require(endNs >= initial.startedNs)
        close()
        val manifest = initial.toBuilder().setEndedNs(endNs).setState(state).clearStreams()
            .addAllStreams(CaptureProject.streamPaths.map { CaptureProject.reference(root, it, "protobuf-delimited") }).build()
        require(manifest.serializedSize <= limits.maxManifestBytes)
        CaptureProject.atomicWrite(root, CaptureProject.MANIFEST, manifest.toByteArray())
        return manifest
    }
    override fun close() {
        if (closed) return
        var failure: Throwable? = null
        try { outputs.values.forEach { it.syncToDisk() } }
        catch (syncFailure: Throwable) { failure = syncFailure; if (syncFailure is IOException) poisoned = syncFailure }
        finally { failure = closeHandles(failure) }
        failure?.let { throw it }
    }
    private fun references(value: CaptureFrame): List<Asset> = buildList { if (value.hasRgb()) add(value.rgb); value.depthList.forEach { if (it.hasDepth()) add(it.depth); if (it.hasConfidence()) add(it.confidence) } }
}
