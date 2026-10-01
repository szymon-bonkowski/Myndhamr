package io.github.szymonbonkowski.myndhamr.reconstruction

import io.github.szymonbonkowski.myndhamr.scan.v1.*
import io.github.szymonbonkowski.myndhamr.store.CaptureProject
import io.github.szymonbonkowski.myndhamr.store.CaptureValidation
import java.io.File

/** Test tooling only: canonical v1 scan from the analytic renderer, no handwritten wire encoding. */
fun main(args: Array<String>) {
    require(args.size == 2) { "render-directory scan-destination" }
    val render = File(args[0]); val destination = File(args[1])
    val rows = File(render, "poses.tsv").readLines().map { it.split('\t') }
    val start = rows.first()[1].toLong()
    val manifest = CaptureManifest.newBuilder().setFormatVersion(1).setProjectId("synthetic-metric-nonplanar-v02")
        .setDevice("analytic-fixture").setStartedNs(start).setState(CaptureState.RECORDING)
        .setPoseConvention(CaptureValidation.POSE_CONVENTION).setImageConvention(CaptureValidation.IMAGE_CONVENTION)
        .setAppVersion("v0.2-fixture").addCapabilities("synthetic-ground-truth").build()
    val rgbK = Intrinsics.newBuilder().setWidth(640).setHeight(480).setFx(500.0).setFy(505.0).setCx(320.0).setCy(240.0)
        .setModel("ARCORE_CPU_IMAGE_PINHOLE;unrotated-pixels").build()
    val depthK = rgbK.toBuilder().setWidth(160).setHeight(120).setFx(125.0).setFy(126.25).setCx(80.0).setCy(60.0).build()
    val writer = CaptureProject.create(destination, manifest)
    writer.use {
        for (r in rows) {
            val id = r[0].toLong(); val time = Timestamp.newBuilder().setValueNs(r[1].toLong()).setClockDomain("SYNTHETIC_FRAME")
                .setArrivalElapsedNs(r[1].toLong()).build()
            val image = writer.writeAsset("assets/rgb/${r[2]}", File(render,r[2]).readBytes(), "png")
            val depth = writer.writeAsset("assets/depth/$id.bin",File(render,"depth-$id.bin").readBytes(),"U16_LE;millimeters;axial-Z")
            val conf = writer.writeAsset("assets/confidence/$id.bin",File(render,"confidence-$id.bin").readBytes(),"U8;0-invalid;255-highest")
            val camera = CameraObservation.newBuilder().setTimestamp(time).setFrameNumber(id).setTimestampSource("SYNTHETIC_FRAME").build()
            writer.appendCamera(camera)
            val frame = CaptureFrame.newBuilder().setCamera(camera).setFrameId(id).setArTimestamp(time).setCameraTimestamp(time).setImageTimestamp(time)
                .setIntrinsics(rgbK).setTrackingState(TrackingState.TRACKING)
                .setPose(Pose.newBuilder().setConvention(CaptureValidation.POSE_CONVENTION).addAllColumnMajor(r.drop(3).map(String::toDouble)))
                .setKeyframe(true).setRgb(image).setImageWidth(640).setImageHeight(480).setImageFormat("PNG")
                .addDepth(DepthRecord.newBuilder().setSource(DepthSource.ARCORE_RAW).setAvailability(DepthAvailability.AVAILABLE)
                    .setTimestamp(time).setWidth(160).setHeight(120).setIntrinsics(depthK).setUnitMeters(0.001)
                    .setAlignment("ARCORE_DEPTH_TEXTURE_VIEW;CPU-image-may-be-cropped;texture-intrinsics-scaled;axial-Z-millimeters")
                    .addAllCpuToDepthColumnMajor(listOf(.25,0.0,0.0,0.0,.25,0.0,0.0,0.0,1.0))
                    .setMappingConvention("column-vectors;column-major;H_depthPixels_cpuImagePixels")
                    .setDepth(depth).setConfidence(conf).setConfidenceAvailability(DepthAvailability.AVAILABLE).setConfidenceTimestamp(time)
                    .setDetail("SYNTHETIC analytic axial depth; not a sensor accuracy claim")).build()
            writer.appendFrame(frame)
        }
        writer.finish(rows.last()[1].toLong()+1,CaptureState.COMPLETED)
    }
    val report=CaptureProject.validate(destination)
    require(report.valid) { report.diagnostics.joinToString("; ") }
    println("Created valid synthetic v1 scan: ${report.keyframes} keyframes")
}
