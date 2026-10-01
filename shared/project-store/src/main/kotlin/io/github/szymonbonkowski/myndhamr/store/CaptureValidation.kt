package io.github.szymonbonkowski.myndhamr.store

import io.github.szymonbonkowski.myndhamr.scan.v1.*
import kotlin.math.abs

/** Contract checks do not change measured values. */
object CaptureValidation {
    const val POSE_CONVENTION = "T_world_camera;meters;right-handed;+X-right,+Y-up,-Z-forward;column-vectors;column-major"
    const val IMAGE_CONVENTION = "unrotated-cpu-image;+X-right,+Y-down;camera-to-optical=diag(1,-1,-1)"

    fun manifest(value: CaptureManifest) {
        require(value.formatVersion == 1) { "Unsupported capture format ${value.formatVersion}" }
        require(value.projectId.isNotBlank() && value.startedNs >= 0) { "Invalid project identity/start" }
        require(value.state != CaptureState.CAPTURE_STATE_UNSPECIFIED && value.state != CaptureState.UNRECOGNIZED)
        require(value.poseConvention == POSE_CONVENTION && value.imageConvention == IMAGE_CONVENTION) { "Unsupported coordinate convention" }
        if (value.state != CaptureState.RECORDING) require(value.endedNs >= value.startedNs) { "End precedes start" }
    }

    fun timestamp(value: Timestamp) {
        require(value.valueNs >= 0 && value.arrivalElapsedNs >= 0 && value.clockDomain.isNotBlank()) { "Invalid timestamp/source clock" }
    }

    fun intrinsics(value: Intrinsics) {
        require(listOf(value.fx, value.fy, value.cx, value.cy).all { it.isFinite() }) { "Nonfinite calibration" }
        require(value.fx > 0 && value.fy > 0 && value.width > 0 && value.height > 0 && value.model.isNotBlank()) { "Invalid calibration" }
    }

    fun pose(value: Pose) {
        require(value.convention == POSE_CONVENTION && value.columnMajorCount == 16) { "Invalid pose convention/matrix" }
        val m = value.columnMajorList
        require(m.all { it.isFinite() }) { "Nonfinite pose" }
        require(abs(m[3]) < 1e-6 && abs(m[7]) < 1e-6 && abs(m[11]) < 1e-6 && abs(m[15] - 1) < 1e-6) { "Non-affine pose" }
        for (a in 0..2) for (b in 0..2) {
            val dot = (0..2).sumOf { m[a * 4 + it] * m[b * 4 + it] }
            require(abs(dot - if (a == b) 1.0 else 0.0) < 1e-4) { "Pose rotation is not orthonormal" }
        }
        val determinant = m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])
        require(abs(determinant - 1) < 1e-4) { "Pose is not right-handed" }
    }

    fun camera(value: CameraObservation) {
        require(value.hasTimestamp()); timestamp(value.timestamp)
        require(value.frameNumber >= 0 && value.timestampSource.isNotBlank()) { "Missing camera source metadata" }
        require((value.intrinsicCalibrationList + value.lensDistortionList + value.colorGainsList + value.lensPoseTranslationList + value.lensPoseRotationList).all { it.isFinite() }) { "Nonfinite camera metadata" }
        if (value.hasExposureNs()) require(value.exposureNs > 0)
        if (value.hasIso()) require(value.iso > 0)
        if (value.hasFocalMm()) require(value.focalMm.isFinite() && value.focalMm > 0)
        if (value.hasRollingShutterNs()) require(value.rollingShutterNs >= 0)
    }

    fun frame(value: CaptureFrame) {
        require(value.frameId > 0 && value.hasArTimestamp() && value.hasIntrinsics()) { "Missing frame identity/time/calibration" }
        timestamp(value.arTimestamp); intrinsics(value.intrinsics)
        require(value.trackingState != TrackingState.TRACKING_UNSPECIFIED && value.trackingState != TrackingState.UNRECOGNIZED)
        if (value.hasPose()) { require(value.trackingState == TrackingState.TRACKING) { "Pose with invalid tracking" }; pose(value.pose) }
        if (value.trackingState == TrackingState.TRACKING) require(value.hasPose()) { "Tracked frame lacks pose" }
        if (value.hasCameraTimestamp()) timestamp(value.cameraTimestamp)
        if (value.hasImageTimestamp()) timestamp(value.imageTimestamp)
        if (value.hasCamera()) camera(value.camera)
        require(value.depthCount > 0) { "Depth absence must be explicit" }
        for (depth in value.depthList) {
            require(depth.source != DepthSource.DEPTH_SOURCE_UNSPECIFIED && depth.source != DepthSource.UNRECOGNIZED)
            require(depth.availability != DepthAvailability.DEPTH_AVAILABILITY_UNSPECIFIED && depth.availability != DepthAvailability.UNRECOGNIZED)
            if (depth.availability == DepthAvailability.AVAILABLE) {
                require(depth.hasTimestamp() && depth.hasIntrinsics() && depth.alignment.isNotBlank()) { "Incomplete available depth" }
                require(depth.hasDepth() || depth.assetOmittedByPolicy && !value.keyframe) { "Available depth lacks asset or explicit non-keyframe omission policy" }
                require(!depth.hasDepth() || !depth.assetOmittedByPolicy) { "Contradictory depth omission policy" }
                timestamp(depth.timestamp); intrinsics(depth.intrinsics)
                if(depth.cpuToDepthColumnMajorCount>0) {
                    val h=depth.cpuToDepthColumnMajorList
                    require(h.size==9 && h.all { it.isFinite() } && depth.mappingConvention.isNotBlank()) { "Invalid CPU/depth pixel mapping" }
                    require(abs(h[2])<1e-8 && abs(h[5])<1e-8 && abs(h[8]-1.0)<1e-8 && abs(h[0]*h[4]-h[3]*h[1])>1e-12) { "Degenerate depth mapping" }
                }
                if(depth.hasConfidenceTimestamp()) timestamp(depth.confidenceTimestamp)
                if(depth.intrinsics.model=="ARCORE_DEPTH_SCALED_TEXTURE_PINHOLE") require(depth.cpuToDepthColumnMajorCount==9) { "Depth texture view requires crop mapping" }
                if(depth.hasDepth() && depth.depth.encoding=="U16_LE;millimeters;axial-Z") require(depth.depth.size==depth.width.toLong()*depth.height.toLong()*2) { "Depth asset dimensions mismatch" }
                if(depth.hasConfidence() && depth.confidence.encoding=="U8;0-invalid;255-highest") require(depth.confidence.size==depth.width.toLong()*depth.height.toLong()) { "Confidence asset dimensions mismatch" }
                require(depth.width == depth.intrinsics.width && depth.height == depth.intrinsics.height)
                require(depth.unitMeters.isFinite() && depth.unitMeters > 0)
            } else require(!depth.hasDepth() && !depth.hasConfidence()) { "Unavailable depth has assets" }
        }
        if (value.projectionColumnMajorCount > 0) {
            require(value.projectionColumnMajorCount == 16 && value.projectionColumnMajorList.all { it.isFinite() })
            require(value.hasProjectionNearMeters() && value.hasProjectionFarMeters() && value.projectionNearMeters > 0 && value.projectionFarMeters.isFinite() && value.projectionFarMeters > value.projectionNearMeters)
        }
        if (value.keyframe) {
            require(value.trackingState == TrackingState.TRACKING && value.hasPose()) { "Keyframe requires valid tracking/pose" }
            require(value.hasCamera()) { "Keyframe lacks exact Camera2 observation" }
            require(value.hasRgb() && value.hasCameraTimestamp() && value.hasImageTimestamp()) { "Keyframe lacks RGB/source timestamps" }
            require(value.cameraTimestamp.valueNs == value.imageTimestamp.valueNs && value.camera.timestamp.valueNs == value.cameraTimestamp.valueNs && value.cameraTimestamp.clockDomain == value.imageTimestamp.clockDomain && value.camera.timestamp.clockDomain == value.cameraTimestamp.clockDomain) { "Keyframe camera/image association is not exact in one source clock" }
            if (value.rgb.encoding.equals("i420", ignoreCase = true)) {
                val width = value.imageWidth.toLong(); val height = value.imageHeight.toLong()
                require(value.rgb.size == width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2)) { "I420 byte size/dimensions mismatch" }
            }
            require(value.imageWidth == value.intrinsics.width && value.imageHeight == value.intrinsics.height && value.imageFormat.isNotBlank()) { "Keyframe image/calibration mismatch" }
        }
    }

    fun imu(value: ImuSample) {
        require(value.type in setOf("ACCELEROMETER", "GYROSCOPE", "MAGNETOMETER")) { "Unknown IMU type" }
        require(value.hasTimestamp()); timestamp(value.timestamp)
        require(value.valuesCount == 3 && value.valuesList.all { it.isFinite() }) { "Invalid IMU vector" }
    }

    fun event(value: SessionEvent) {
        require(value.type.isNotBlank() && value.hasTimestamp()); timestamp(value.timestamp)
    }
}
