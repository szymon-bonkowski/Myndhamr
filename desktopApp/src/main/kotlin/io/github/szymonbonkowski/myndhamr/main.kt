package io.github.szymonbonkowski.myndhamr

import io.github.szymonbonkowski.myndhamr.domain.FoundationVersion
import io.github.szymonbonkowski.myndhamr.store.CaptureProject
import io.github.szymonbonkowski.myndhamr.store.ValidationReport
import io.github.szymonbonkowski.myndhamr.scan.v1.*
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.nio.file.Files
import java.util.Locale

private const val HELP = """Usage: myndhamr [--help]
       myndhamr inspect <project-directory|package.scan3d>
       myndhamr replay <project-directory|package.scan3d>
       myndhamr validate <project-directory|package.scan3d>

All commands emit JSON. replay emits one JSON object per stored metadata record.
"""
private const val MAX_DIAGNOSTIC_KEYS = 64

fun main(args: Array<String>) {
    try {
        val output = foundationCommand(args.toList())
        if (output.isNotEmpty()) println(output)
    } catch (e: Exception) {
        System.err.println(json(mapOf("error" to (e.message ?: e.javaClass.simpleName))))
        System.exit(2)
    }
}

/** Kept as a public pure function for the v0.0 CLI compatibility checks. */
fun foundationCommand(args: List<String>): String {
    if (args.isEmpty()) return "Myndhamr ${FoundationVersion.MILESTONE} foundation"
    if (args == listOf("--help")) return HELP.trimEnd()
    require(args.size == 2 && args[0] in setOf("inspect", "replay", "validate")) { "Invalid arguments: ${args.joinToString(" ")}" }
    val command = args[0]
    withProject(args[1]) { root ->
        when (command) {
            "validate" -> println(json(reportJson(CaptureProject.validate(root))))
            "inspect" -> println(json(Inspector.inspect(root)))
            "replay" -> replay(root)
        }
    }
    return ""
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

private fun replay(root: File) {
    CaptureProject.visitFrames(root) { println(json(mapOf("record" to "frame", "value" to frameJson(it)))) }
    CaptureProject.visitImu(root) { println(json(mapOf("record" to "imu", "value" to imuJson(it)))) }
    CaptureProject.visitCamera(root) { println(json(mapOf("record" to "camera", "value" to cameraJson(it)))) }
    CaptureProject.visitEvents(root) { println(json(mapOf("record" to "event", "value" to eventJson(it)))) }
}

private object Inspector {
    fun inspect(root: File): Map<String, Any?> {
        val report = CaptureProject.validate(root)
        val manifest = report.manifest
        val depth = linkedMapOf<String, Long>()
        val confidence = linkedMapOf<String, Long>()
        val tracking = linkedMapOf<String, Long>()
        val intrinsics = linkedMapOf<String, Any?>()
        val pose = linkedMapOf<String, Any?>()
        val timestampOrder = linkedMapOf<String, Order>()
        var omittedClockDomains = 0L
        val arAndroid = DeltaStats()
        val imageCamera = DeltaStats()
        var arCameraChecked = 0L
        var arCameraExact = 0L
        var embeddedCameraChecked = 0L
        var embeddedCameraExact = 0L
        val depthAge = linkedMapOf<String, DeltaStats>()
        val unverifiedDepthAges = linkedMapOf<String, Long>()
        val depthTimestampUse = mutableMapOf<String, Long>()
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
            }
            frame.depthList.forEach { d ->
                val key = "${d.source.name}:${d.availability.name}"
                depth.bump(key)
                if (d.hasConfidence()) confidence.bump(d.source.name)
                if (d.availability == DepthAvailability.AVAILABLE && d.hasTimestamp()) {
                    if (frame.arTimestamp.clockDomain == d.timestamp.clockDomain) {
                        val age = BigInteger.valueOf(frame.arTimestamp.valueNs).subtract(BigInteger.valueOf(d.timestamp.valueNs))
                        depthAge.getOrPut(d.source.name) { DeltaStats() }.add(age)
                    } else {
                        unverifiedDepthAges[d.source.name] = (unverifiedDepthAges[d.source.name] ?: 0L) + 1L
                    }
                    val reuseKey = "${d.source.name}:${d.timestamp.clockDomain}:${d.timestamp.valueNs}"
                    if (reuseKey in depthTimestampUse || depthTimestampUse.size < MAX_DIAGNOSTIC_KEYS) {
                        depthTimestampUse[reuseKey] = (depthTimestampUse[reuseKey] ?: 0L) + 1L
                    }
                }
            }
            if (!intrinsics.containsKey("rgb")) intrinsics["rgb"] = intrinsicsJson(frame.intrinsics)
            if (frame.hasPose()) {
                poses++
                if (!pose.containsKey("firstTrackedPose")) pose["firstTrackedPose"] = frame.pose.columnMajorList
            }
            if (frame.keyframe) {
                keyframes++
                var exact = false
                if (frame.hasCameraTimestamp() && frame.hasImageTimestamp()) {
                    if (frame.imageTimestamp.clockDomain == frame.cameraTimestamp.clockDomain) {
                        exact = sameSourceTime(frame.imageTimestamp, frame.cameraTimestamp)
                        checkedKeyframes++
                    } else {
                        unverifiedKeyframeClocks++
                    }
                }
                if (frame.hasCameraTimestamp() && frame.arTimestamp.clockDomain == frame.cameraTimestamp.clockDomain) {
                    arCameraChecked++
                    if (sameSourceTime(frame.arTimestamp, frame.cameraTimestamp)) arCameraExact++
                }
                if (frame.hasCamera()) {
                    if (frame.hasCameraTimestamp() && frame.camera.timestamp.clockDomain == frame.cameraTimestamp.clockDomain) {
                        embeddedCameraChecked++
                        val embeddedExact = sameSourceTime(frame.camera.timestamp, frame.cameraTimestamp) && frame.camera.frameNumber >= 0
                        if (embeddedExact) embeddedCameraExact++
                    }
                }
                if (exact) exactKeyframes++
            }
            if (frame.hasCameraTimestamp() && isAndroidMonotonic(
                    frame.cameraTimestamp.clockDomain,
                    if (frame.hasCamera()) frame.camera.timestampSource else ""
                )) {
                arAndroid.add(delta(frame.arTimestamp, frame.cameraTimestamp))
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
            if (isAndroidMonotonic(sample.timestamp.clockDomain)) {
                imuLatency.add(BigInteger.valueOf(sample.timestamp.arrivalElapsedNs).subtract(BigInteger.valueOf(sample.timestamp.valueNs)))
            } else {
                unverifiedImuLatencies++
            }
        }
        val cameraOrders = linkedMapOf<String, Order>()
        CaptureProject.visitCamera(root) { camera ->
            if (!order(cameraOrders, camera.timestamp.clockDomain, camera.timestamp.valueNs)) omittedClockDomains++
        }
        val eventCounts = linkedMapOf<String, Long>()
        CaptureProject.visitEvents(root) { eventCounts.bump(it.type) }

        val repeatedDepth = depthTimestampUse.values.sumOf { if (it > 1) it - 1 else 0L }
        return linkedMapOf(
            "valid" to report.valid,
            "manifest" to manifest?.let(::manifestJson),
            "counts" to linkedMapOf("frames" to frameCount, "keyframes" to keyframes,
                "trackedFrames" to report.trackedFrames, "poses" to poses,
                "cameraObservations" to report.cameraObservations, "events" to report.events),
            "tracking" to tracking,
            "depth" to linkedMapOf("availabilityBySource" to depth, "confidenceAssetsBySource" to confidence,
                "ageNsBySource" to depthAge.mapValues { it.value.json() }, "unverifiedAgeClockPairsBySource" to unverifiedDepthAges,
                "reusedTimestampObservations" to repeatedDepth),
            "keyframeAssociations" to linkedMapOf("checked" to checkedKeyframes, "exact" to exactKeyframes,
                "mismatches" to checkedKeyframes - exactKeyframes,
                "unverifiedClockKeyframes" to unverifiedKeyframeClocks,
                "arCameraSameClockChecked" to arCameraChecked, "arCameraExact" to arCameraExact,
                "embeddedCameraChecked" to embeddedCameraChecked, "embeddedCameraExact" to embeddedCameraExact,
                "camera2ArOffsetNs" to arAndroid.json(), "cpuImageMinusCameraNs" to imageCamera.json(),
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
    var count = 0L; var first = 0L; var last = 0L; var regressions = 0L
    fun add(value: Long) { if (count == 0L) first = value else if (value < last) regressions++; last = value; count++ }
    fun json() = mapOf("count" to count, "regressions" to regressions,
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
private fun isAndroidMonotonic(domain: String, timestampSource: String = ""): Boolean {
    val normalized = domain.lowercase(Locale.ROOT)
    return normalized.contains("android") || normalized.contains("elapsedrealtime") ||
        (normalized.contains("camera2") && normalized.contains("realtime")) || timestampSource.equals("REALTIME", ignoreCase = true)
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
    "lensDistortion" to c.lensDistortionList, "cropRegion" to c.cropRegionList)
private fun frameJson(f: CaptureFrame) = mapOf("frameId" to f.frameId.toString(), "arTimestamp" to timestampJson(f.arTimestamp),
    "cameraTimestamp" to if (f.hasCameraTimestamp()) timestampJson(f.cameraTimestamp) else null,
    "imageTimestamp" to if (f.hasImageTimestamp()) timestampJson(f.imageTimestamp) else null,
    "trackingState" to f.trackingState.name, "poseColumnMajor" to if (f.hasPose()) f.pose.columnMajorList else null,
    "intrinsics" to intrinsicsJson(f.intrinsics), "keyframe" to f.keyframe, "camera" to if (f.hasCamera()) cameraJson(f.camera) else null,
    "depth" to f.depthList.map { mapOf("source" to it.source.name, "availability" to it.availability.name,
        "timestamp" to if (it.hasTimestamp()) timestampJson(it.timestamp) else null, "confidence" to it.hasConfidence(), "detail" to it.detail) },
    "rgb" to if (f.hasRgb()) assetJson(f.rgb) else null, "failureReason" to f.failureReason)
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
