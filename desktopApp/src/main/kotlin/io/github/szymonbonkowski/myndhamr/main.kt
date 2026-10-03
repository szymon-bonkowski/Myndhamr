package io.github.szymonbonkowski.myndhamr

import io.github.szymonbonkowski.myndhamr.domain.FoundationVersion
import io.github.szymonbonkowski.myndhamr.store.CaptureValidation
import io.github.szymonbonkowski.myndhamr.store.CaptureProject
import io.github.szymonbonkowski.myndhamr.store.ValidationReport
import io.github.szymonbonkowski.myndhamr.scan.v1.*
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.nio.file.Files
import java.util.Locale
import io.github.szymonbonkowski.myndhamr.reconstruction.SparseCommand
import io.github.szymonbonkowski.myndhamr.reconstruction.ObjectCommand

private const val HELP = """Usage: myndhamr [--help]
       myndhamr inspect <project-directory|package.scan3d>
       myndhamr replay <project-directory|package.scan3d>
       myndhamr validate <project-directory|package.scan3d>
       myndhamr ingest <scan> <new-run-directory>
       myndhamr reconstruct <scan> <new-run-directory> [--python path] [--timeout-seconds 600] [--pose-threshold-meters 0.05]
       myndhamr object <scan> <object-run> [--sparse sparse-run] [object options]
       myndhamr dense|mesh|export <sparse-run> <object-run> [object options]
       myndhamr mesh-validate <object-run> [--python path] [--repository path]

Object options: --python path --sparse-python path --repository path --timeout-seconds 3600
                --max-image-size 1600 --radii-meters 0.005,0.01 --max-edge-meters 0.02
                --min-component-faces 0 --formats ply,obj,glb
All commands emit JSON. replay emits one JSON object per stored metadata record.
"""
private const val MAX_DIAGNOSTIC_KEYS = 64

fun main(args: Array<String>) {
    val status = runCli(args.toList(), System.out, System.err)
    if (status != 0) System.exit(status)
}

/** Kept as a public function for the v0.0 CLI compatibility checks. */
fun foundationCommand(args: List<String>): String {
    if (args.isEmpty()) return "Myndhamr ${FoundationVersion.MILESTONE} foundation"
    if (args == listOf("--help")) return "Usage: myndhamr [--help]"
    require(args.size == 2 && args[0] in setOf("inspect", "replay", "validate")) { "Invalid arguments: ${args.joinToString(" ")}" }
    val command = args[0]
    withProject(args[1]) { root ->
        when (command) {
            "validate" -> println(json(reportJson(CaptureProject.validate(root))))
            "inspect" -> println(json(Inspector.inspect(root)))
            "replay" -> replay(root, ::println)
        }
    }
    return ""
}

internal fun runCli(args: List<String>, stdout: java.io.PrintStream, stderr: java.io.PrintStream): Int {
    if (SparseCommand.handles(args.firstOrNull())) return SparseCommand.run(args, stdout, stderr)
    if (ObjectCommand.handles(args.firstOrNull())) return ObjectCommand.run(args, stdout, stderr)
    try {
        if (args.isEmpty()) {
            stdout.println("Myndhamr ${FoundationVersion.MILESTONE} foundation")
            return 0
        }
        if (args == listOf("--help")) {
            stdout.println(HELP.trimEnd())
            return 0
        }
        require(args.size == 2 && args[0] in setOf("inspect", "replay", "validate")) {
            "Invalid arguments: ${args.joinToString(" ")}"
        }
        return withProject(args[1]) { root ->
            when (args[0]) {
                "validate" -> {
                    val report = CaptureProject.validate(root)
                    stdout.println(json(reportJson(report)))
                    if (report.valid) 0 else 1
                }
                "inspect" -> {
                    val result = Inspector.inspect(root)
                    stdout.println(json(result))
                    if (result["valid"] == true) 0 else 1
                }
                "replay" -> if (replay(root) { stdout.println(it) }) 0 else 1
                else -> error("Unsupported command")
            }
        }
    } catch (e: Exception) {
        stderr.println(json(mapOf("error" to (e.message ?: e.javaClass.simpleName))))
        return 2
    }
}

private inline fun <T> withProject(path: String, block: (File) -> T): T {
    val source = File(path).canonicalFile
    require(source.exists()) { "Project does not exist: $path" }
    if (source.isDirectory) return block(source)
    require(source.isFile) { "Project path is not a directory or package file" }
    val temp = Files.createTempDirectory("myndhamr-inspect-").toFile()
    return try {
        val destination = File(temp, "project")
        CaptureProject.importPackage(source, destination)
        block(destination)
    } finally {
        temp.deleteRecursively()
    }
}

private fun replay(root: File, emit: (String) -> Unit): Boolean {
    val report = CaptureProject.validate(root)
    if (!report.valid) {
        emit(json(reportJson(report)))
        return false
    }
    CaptureProject.visitFrames(root) { emit(json(mapOf("record" to "frame", "value" to frameJson(it)))) }
    CaptureProject.visitImu(root) { emit(json(mapOf("record" to "imu", "value" to imuJson(it)))) }
    CaptureProject.visitCamera(root) { emit(json(mapOf("record" to "camera", "value" to cameraJson(it)))) }
    CaptureProject.visitEvents(root) { emit(json(mapOf("record" to "event", "value" to eventJson(it)))) }
    return true
}

private object Inspector {
    fun inspect(root: File): Map<String, Any?> {
        val report = CaptureProject.validate(root)
        val manifest = report.manifest
        val depth = linkedMapOf<String, Long>()
        val confidence = linkedMapOf<String, Long>()
        val confidenceAvailability = linkedMapOf<String,Long>()
        val representativeDepth=linkedMapOf<String,Any?>()
        val tracking = linkedMapOf<String, Long>()
        val intrinsics = linkedMapOf<String, Any?>()
        val pose = linkedMapOf<String, Any?>()
        val timestampOrder = linkedMapOf<String, Order>()
        var omittedClockDomains = 0L
        val arMinusCameraByClockPair = linkedMapOf<String, DeltaStats>()
        var omittedArCameraClockPairs = 0L
        val imageCamera = DeltaStats()
        val imageCameraObservedByClockPair = linkedMapOf<String, DeltaStats>()
        var omittedImageCameraClockPairs = 0L
        var providerLinkedKeyframes = 0L
        var embeddedCameraChecked = 0L
        var embeddedCameraExact = 0L
        var embeddedCameraMismatch = 0L
        var embeddedCameraUnverified = 0L
        var missingEmbeddedCamera = 0L
        val depthObservedDeltaByClockPair = linkedMapOf<String, DeltaStats>()
        var omittedDepthClockPairs = 0L
        val previousDepthTimestampBySource = mutableMapOf<DepthSource, Timestamp>()
        var reusedDepthTimestampObservations = 0L
        var unverifiedImageCameraClocks = 0L
        var frameCount = 0L
        var keyframes = 0L
        var exactKeyframes = 0L
        var checkedKeyframes = 0L
        var unverifiedKeyframeClocks = 0L
        var calibrationErrors = 0L
        var poses = 0L
        CaptureProject.visitFrames(root) { frame ->
            frameCount++
            tracking.bump(frame.trackingState.name)
            if (!order(timestampOrder, "ar:${frame.arTimestamp.clockDomain}", frame.arTimestamp.valueNs)) omittedClockDomains++
            if (frame.hasCameraTimestamp() && !order(timestampOrder, "camera:${frame.cameraTimestamp.clockDomain}", frame.cameraTimestamp.valueNs)) omittedClockDomains++
            if (frame.hasImageTimestamp() && !order(timestampOrder, "image:${frame.imageTimestamp.clockDomain}", frame.imageTimestamp.valueNs)) omittedClockDomains++
            if (frame.hasCameraTimestamp() && frame.hasImageTimestamp()) {
                if (frame.imageTimestamp.clockDomain == frame.cameraTimestamp.clockDomain) imageCamera.add(delta(frame.imageTimestamp, frame.cameraTimestamp))
                else unverifiedImageCameraClocks++
                val pair="${frame.imageTimestamp.clockDomain}->${frame.cameraTimestamp.clockDomain}"
                if(pair in imageCameraObservedByClockPair || imageCameraObservedByClockPair.size < MAX_DIAGNOSTIC_KEYS) {
                    imageCameraObservedByClockPair.getOrPut(pair) { DeltaStats() }.add(delta(frame.imageTimestamp,frame.cameraTimestamp))
                } else omittedImageCameraClockPairs++
            }
            frame.depthList.forEach { d ->
                val key = "${d.source.name}:${d.availability.name}"
                depth.bump(key)
                if (d.hasConfidence()) confidence.bump(d.source.name)
                confidenceAvailability.bump("${d.source.name}:${d.confidenceAvailability.name}")
                if(d.availability==DepthAvailability.AVAILABLE && !representativeDepth.containsKey(d.source.name))representativeDepth[d.source.name]=depthJson(d)
                if (d.availability == DepthAvailability.AVAILABLE && d.hasTimestamp()) {
                    val deltaKey = "${frame.arTimestamp.clockDomain}->${d.timestamp.clockDomain}:${d.source.name}"
                    if (deltaKey in depthObservedDeltaByClockPair || depthObservedDeltaByClockPair.size < MAX_DIAGNOSTIC_KEYS) {
                        depthObservedDeltaByClockPair.getOrPut(deltaKey) { DeltaStats() }
                            .add(BigInteger.valueOf(d.timestamp.valueNs).subtract(BigInteger.valueOf(frame.arTimestamp.valueNs)))
                    } else {
                        omittedDepthClockPairs++
                    }
                    val previous = previousDepthTimestampBySource.put(d.source, d.timestamp)
                    if (previous != null && sameSourceTime(previous, d.timestamp)) reusedDepthTimestampObservations++
                }
            }
            if (!intrinsics.containsKey("rgb")) intrinsics["rgb"] = intrinsicsJson(frame.intrinsics)
            if (frame.hasPose()) {
                poses++
                if (!pose.containsKey("firstTrackedPose")) pose["firstTrackedPose"] = frame.pose.columnMajorList
            }
            if (frame.keyframe) {
                keyframes++
                if(frame.imageAssociation==CaptureValidation.ARCORE_CURRENT_FRAME_IMAGE) providerLinkedKeyframes++
                var exact = false
                if (frame.hasCameraTimestamp() && frame.hasImageTimestamp()) {
                    if (frame.imageTimestamp.clockDomain == frame.cameraTimestamp.clockDomain) {
                        exact = sameSourceTime(frame.imageTimestamp, frame.cameraTimestamp)
                        checkedKeyframes++
                    } else {
                        unverifiedKeyframeClocks++
                    }
                }
                if (exact) exactKeyframes++
            }
            if (frame.hasCamera() && frame.hasCameraTimestamp()) {
                if (frame.camera.timestamp.clockDomain == frame.cameraTimestamp.clockDomain) {
                    embeddedCameraChecked++
                    if (sameSourceTime(frame.camera.timestamp, frame.cameraTimestamp) && frame.camera.frameNumber >= 0) embeddedCameraExact++
                    else embeddedCameraMismatch++
                } else {
                    embeddedCameraUnverified++
                }
            } else {
                missingEmbeddedCamera++
            }
            if (frame.hasCameraTimestamp()) {
                val pair = "${frame.arTimestamp.clockDomain}->${frame.cameraTimestamp.clockDomain}"
                if (pair in arMinusCameraByClockPair || arMinusCameraByClockPair.size < MAX_DIAGNOSTIC_KEYS) {
                    arMinusCameraByClockPair.getOrPut(pair) { DeltaStats() }.add(delta(frame.arTimestamp, frame.cameraTimestamp))
                } else {
                    omittedArCameraClockPairs++
                }
            }
            val calibrationProblem = frame.intrinsics.fx <= 0.0 || frame.intrinsics.fy <= 0.0 ||
                frame.intrinsics.width == 0 || frame.intrinsics.height == 0 || frame.intrinsics.model.isBlank() ||
                frame.intrinsics.width != frame.imageWidth && frame.keyframe ||
                frame.intrinsics.height != frame.imageHeight && frame.keyframe ||
                listOf(frame.intrinsics.fx, frame.intrinsics.fy, frame.intrinsics.cx, frame.intrinsics.cy).any { !it.isFinite() }
            if (calibrationProblem) calibrationErrors++
        }

        val imuCounts = linkedMapOf<String, Long>()
        val imuOrders = linkedMapOf<String, Order>()
        val imuLatency = DeltaStats()
        var unverifiedImuLatencies = 0L
        val imuByClock = linkedMapOf<String, SampleSpan>()
        CaptureProject.visitImu(root) { sample ->
            imuCounts.bump(sample.type)
            val key = "${sample.type}:${sample.timestamp.clockDomain}"
            if (!order(imuOrders, key, sample.timestamp.valueNs)) omittedClockDomains++
            if (key in imuByClock || imuByClock.size < MAX_DIAGNOSTIC_KEYS) imuByClock.getOrPut(key) { SampleSpan() }.add(sample.timestamp.valueNs)
            if (isExplicitRealtimeLatencyDomain(sample.timestamp.clockDomain)) {
                imuLatency.add(BigInteger.valueOf(sample.timestamp.arrivalElapsedNs).subtract(BigInteger.valueOf(sample.timestamp.valueNs)))
            } else {
                unverifiedImuLatencies++
            }
        }
        val cameraOrders = linkedMapOf<String, Order>()
        val cameraCallbackLatency = DeltaStats()
        var unverifiedCameraCallbackLatencies = 0L
        CaptureProject.visitCamera(root) { camera ->
            if (!order(cameraOrders, camera.timestamp.clockDomain, camera.timestamp.valueNs)) omittedClockDomains++
            if (isExplicitRealtimeLatencyDomain(camera.timestamp.clockDomain, camera.timestampSource)) {
                cameraCallbackLatency.add(BigInteger.valueOf(camera.timestamp.arrivalElapsedNs)
                    .subtract(BigInteger.valueOf(camera.timestamp.valueNs)))
            } else {
                unverifiedCameraCallbackLatencies++
            }
        }
        val eventCounts = linkedMapOf<String, Long>()
        CaptureProject.visitEvents(root) { eventCounts.bump(it.type) }

        return linkedMapOf(
            "valid" to report.valid,
            "manifest" to manifest?.let(::manifestJson),
            "counts" to linkedMapOf("frames" to frameCount, "keyframes" to keyframes,
                "trackedFrames" to report.trackedFrames, "poses" to poses,
                "cameraObservations" to report.cameraObservations, "events" to report.events),
            "tracking" to tracking,
            "depth" to linkedMapOf("availabilityBySource" to depth, "confidenceAssetsBySource" to confidence, "confidenceAvailabilityBySource" to confidenceAvailability,
                "representativeCalibrationBySource" to representativeDepth,
                "observedDepthMinusArNsByClockPair" to depthObservedDeltaByClockPair.mapValues { (_, stats) ->
                    linkedMapOf("mapping" to "unverified", "deltaNs" to stats.json())
                }, "omittedClockPairs" to omittedDepthClockPairs,
                "reusedTimestampObservations" to reusedDepthTimestampObservations),
            "keyframeAssociations" to linkedMapOf("checked" to checkedKeyframes, "exact" to exactKeyframes,
                "mismatches" to checkedKeyframes - exactKeyframes,
                "unverifiedClockKeyframes" to unverifiedKeyframeClocks,
                "providerLinkedCurrentFrameKeyframes" to providerLinkedKeyframes,
                "embeddedCameraCheckedAllFrames" to embeddedCameraChecked, "embeddedCameraExactAllFrames" to embeddedCameraExact,
                "embeddedCameraMismatchesAllFrames" to embeddedCameraMismatch,
                "embeddedCameraUnverifiedAllFrames" to embeddedCameraUnverified,
                "missingEmbeddedCameraFrames" to missingEmbeddedCamera,
                "arMinusCameraObservedNsByClockPair" to arMinusCameraByClockPair.mapValues { (_, stats) ->
                    linkedMapOf("mapping" to "unverified", "deltaNs" to stats.json())
                }, "omittedArCameraClockPairs" to omittedArCameraClockPairs,
                "cpuImageMinusCameraNs" to imageCamera.json(),
                "cpuImageMinusCameraObservedNsByClockPair" to imageCameraObservedByClockPair.mapValues { (_,stats) ->
                    linkedMapOf("mapping" to "unverified", "deltaNs" to stats.json())
                }, "omittedImageCameraClockPairs" to omittedImageCameraClockPairs,
                "unverifiedImageCameraClockPairs" to unverifiedImageCameraClocks),
            "calibration" to linkedMapOf("representativeIntrinsics" to intrinsics,
                "finitePositiveDimensionFailures" to calibrationErrors, "representativePose" to pose),
            "timestamps" to linkedMapOf("frameOrders" to timestampOrder.mapValues { it.value.json() },
                "cameraStreamOrders" to cameraOrders.mapValues { it.value.json() },
                "imuOrders" to imuOrders.mapValues { it.value.json() }, "omittedClockDomainRecords" to omittedClockDomains),
            "imu" to linkedMapOf("samplesByType" to imuCounts,
                "rateHzByTypeAndClock" to imuByClock.mapValues { it.value.rateHz() },
                "maximumGapNs" to report.maximumImuGapNs, "callbackLatencyNs" to imuLatency.json(),
                "unverifiedCallbackLatencySamples" to unverifiedImuLatencies),
            "cameraTiming" to linkedMapOf("callbackLatencyNs" to cameraCallbackLatency.json(),
                "unverifiedCallbackLatencySamples" to unverifiedCameraCallbackLatencies),
            "eventsByType" to eventCounts,
            "validation" to reportJson(report)
        )
    }
}

internal fun inspectProject(root: File): Map<String, Any?> = Inspector.inspect(root)

private class DeltaStats {
    var count = 0L; private set
    private var sum = BigInteger.ZERO
    private var first: BigInteger? = null
    private var last: BigInteger? = null
    private var min: BigInteger? = null
    private var max: BigInteger? = null
    fun add(value: BigInteger) {
        if (count == 0L) first = value
        last = value; count++; sum += value
        if (min == null || value < min) min = value
        if (max == null || value > max) max = value
    }
    fun json(): Map<String, Any?> = linkedMapOf("count" to count, "minNs" to min?.toString(), "maxNs" to max?.toString(),
        "meanNs" to if (count == 0L) null else BigDecimal(sum).divide(BigDecimal.valueOf(count), MathContext.DECIMAL64).toPlainString(),
        "driftNs" to if (count < 2) null else (last!! - first!!).toString())
}

private class Order {
    var count = 0L; var first = 0L; var last = 0L; var regressions = 0L; var consecutiveDuplicates = 0L
    fun add(value: Long) {
        if (count == 0L) first = value
        else when {
            value < last -> regressions++
            value == last -> consecutiveDuplicates++
        }
        last = value; count++
    }
    fun json() = mapOf("count" to count, "regressions" to regressions, "consecutiveDuplicates" to consecutiveDuplicates,
        "firstNs" to first.toString(), "lastNs" to last.toString())
}

private class SampleSpan {
    var count = 0L; var first = 0L; var last = 0L
    fun add(ns: Long) { if (count == 0L) first = ns; last = ns; count++ }
    fun rateHz(): String? {
        if (count < 2 || last <= first) return null
        return BigDecimal.valueOf(count - 1).multiply(BigDecimal("1000000000"))
            .divide(BigDecimal.valueOf(last).subtract(BigDecimal.valueOf(first)), MathContext.DECIMAL64).toPlainString()
    }
}

private fun order(orders: MutableMap<String, Order>, key: String, ns: Long): Boolean {
    if (key !in orders && orders.size >= MAX_DIAGNOSTIC_KEYS) return false
    orders.getOrPut(key) { Order() }.add(ns)
    return true
}
private fun MutableMap<String, Long>.bump(key: String) {
    if (key !in this && size >= MAX_DIAGNOSTIC_KEYS) return
    this[key] = (this[key] ?: 0) + 1
}
private fun sameSourceTime(a: Timestamp, b: Timestamp) = a.valueNs == b.valueNs && a.clockDomain == b.clockDomain
private fun isExplicitRealtimeLatencyDomain(domain: String, timestampSource: String = ""): Boolean {
    val clockDomain = domain.uppercase(Locale.ROOT)
    val source = timestampSource.uppercase(Locale.ROOT)
    val explicitlyRealtimeDomains = setOf("ANDROID_ELAPSED_REALTIME", "CAMERA_REALTIME", "APPLICATION_ELAPSED")
    return clockDomain in explicitlyRealtimeDomains || source == "CAMERA_REALTIME"
}
private fun delta(a: Timestamp, b: Timestamp) = BigInteger.valueOf(a.valueNs).subtract(BigInteger.valueOf(b.valueNs))
private fun intrinsicsJson(i: Intrinsics) = mapOf("fx" to i.fx, "fy" to i.fy, "cx" to i.cx, "cy" to i.cy,
    "width" to i.width, "height" to i.height, "model" to i.model)
private fun manifestJson(m: CaptureManifest) = mapOf("formatVersion" to m.formatVersion, "projectId" to m.projectId,
    "device" to m.device, "capabilities" to m.capabilitiesList, "startedNs" to m.startedNs.toString(),
    "endedNs" to m.endedNs.toString(), "state" to m.state.name, "poseConvention" to m.poseConvention,
    "imageConvention" to m.imageConvention, "appVersion" to m.appVersion,
    "streams" to m.streamsList.map(::assetJson))
private fun assetJson(a: Asset) = mapOf("path" to a.path, "size" to a.size.toString(), "sha256" to a.sha256.toByteArray().joinToString("") { "%02x".format(it) }, "encoding" to a.encoding)
private fun timestampJson(t: Timestamp) = mapOf("valueNs" to t.valueNs.toString(), "clockDomain" to t.clockDomain, "arrivalElapsedNs" to t.arrivalElapsedNs.toString())
private fun cameraJson(c: CameraObservation) = mapOf("timestamp" to if (c.hasTimestamp()) timestampJson(c.timestamp) else null,
    "frameNumber" to c.frameNumber.toString(), "timestampSource" to c.timestampSource, "physicalCameraId" to c.physicalCameraId,
    "logicalCameraId" to c.logicalCameraId, "exposureNs" to if (c.hasExposureNs()) c.exposureNs.toString() else null,
    "iso" to if (c.hasIso()) c.iso else null, "intrinsicCalibration" to c.intrinsicCalibrationList,
    "lensDistortion" to c.lensDistortionList, "cropRegion" to c.cropRegionList,
    "focalMm" to if(c.hasFocalMm())c.focalMm else null,"rollingShutterNs" to if(c.hasRollingShutterNs())c.rollingShutterNs.toString() else null,
    "sensorOrientationDegrees" to if(c.hasSensorOrientationDegrees())c.sensorOrientationDegrees else null,
    "colorGains" to c.colorGainsList,"lensPoseTranslation" to c.lensPoseTranslationList,"lensPoseRotation" to c.lensPoseRotationList)
private fun frameJson(f: CaptureFrame) = mapOf("frameId" to f.frameId.toString(), "arTimestamp" to timestampJson(f.arTimestamp),
    "cameraTimestamp" to if (f.hasCameraTimestamp()) timestampJson(f.cameraTimestamp) else null,
    "imageTimestamp" to if (f.hasImageTimestamp()) timestampJson(f.imageTimestamp) else null,
    "imageAssociation" to f.imageAssociation,
    "trackingState" to f.trackingState.name, "poseColumnMajor" to if (f.hasPose()) f.pose.columnMajorList else null,
    "intrinsics" to intrinsicsJson(f.intrinsics), "keyframe" to f.keyframe, "camera" to if (f.hasCamera()) cameraJson(f.camera) else null,
    "depth" to f.depthList.map(::depthJson),
    "projectionColumnMajor" to f.projectionColumnMajorList,"projectionConvention" to f.projectionConvention,
    "displayRotation" to if(f.hasDisplayRotation())f.displayRotation else null,
    "imageWidth" to f.imageWidth,"imageHeight" to f.imageHeight,"imageFormat" to f.imageFormat,
    "rgb" to if (f.hasRgb()) assetJson(f.rgb) else null, "failureReason" to f.failureReason)
private fun depthJson(d:DepthRecord)=mapOf("source" to d.source.name,"availability" to d.availability.name,
    "timestamp" to if(d.hasTimestamp())timestampJson(d.timestamp) else null,"width" to d.width,"height" to d.height,
    "intrinsics" to if(d.hasIntrinsics())intrinsicsJson(d.intrinsics) else null,"unitMeters" to d.unitMeters,
    "cpuToDepthColumnMajor" to d.cpuToDepthColumnMajorList,"mappingConvention" to d.mappingConvention,"alignment" to d.alignment,
    "depthAsset" to if(d.hasDepth())assetJson(d.depth) else null,"confidenceAsset" to if(d.hasConfidence())assetJson(d.confidence) else null,
    "confidenceAvailability" to d.confidenceAvailability.name,"confidenceTimestamp" to if(d.hasConfidenceTimestamp())timestampJson(d.confidenceTimestamp) else null,
    "assetOmittedByPolicy" to d.assetOmittedByPolicy,"detail" to d.detail)
private fun imuJson(i: ImuSample) = mapOf("type" to i.type, "timestamp" to timestampJson(i.timestamp), "values" to i.valuesList, "accuracy" to i.accuracy)
private fun eventJson(e: SessionEvent) = mapOf("type" to e.type, "timestamp" to timestampJson(e.timestamp), "detail" to e.detail)
private fun reportJson(r: ValidationReport) = mapOf("valid" to r.valid, "frames" to r.frames, "keyframes" to r.keyframes,
    "imuSamples" to r.imuSamples, "cameraObservations" to r.cameraObservations, "events" to r.events,
    "trackedFrames" to r.trackedFrames, "depthFrames" to r.depthFrames, "maximumImuGapNs" to r.maximumImuGapNs.toString(),
    "timestampRegressions" to r.timestampRegressions, "errorCount" to r.errorCount, "diagnostics" to r.diagnostics)

/** Small JSON writer keeps the CLI dependency-free and escapes all user-controlled strings. */
private fun json(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"" + value.flatMap { ch -> when (ch) {
        '"' -> "\\\"".toList(); '\\' -> "\\\\".toList(); '\b' -> "\\b".toList(); '\u000c' -> "\\f".toList()
        '\n' -> "\\n".toList(); '\r' -> "\\r".toList(); '\t' -> "\\t".toList()
        else -> if (ch.code < 0x20) "\\u%04x".format(ch.code).toList() else listOf(ch)
    } }.joinToString("") + "\""
    is Double -> if (value.isFinite()) value.toString() else "null"
    is Float -> if (value.isFinite()) value.toString() else "null"
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { json(it.key.toString()) + ":" + json(it.value) }
    is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { json(it) }
    is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { json(it) }
    else -> json(value.toString())
}
