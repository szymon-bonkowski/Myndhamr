package io.github.szymonbonkowski.myndhamr.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CaptureGeometryTest {
    private fun close(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        assertTrue(abs(expected - actual) <= tolerance * maxOf(1.0, abs(expected)), "Expected $expected, got $actual")
    }
    private fun close(expected: Vector3, actual: Vector3) {
        close(expected.x, actual.x); close(expected.y, actual.y); close(expected.z, actual.z)
    }
    private fun close(expected: RigidTransform, actual: RigidTransform) {
        expected.toColumnMajor().zip(actual.toColumnMajor()).forEach { (e, a) -> close(e, a) }
    }
    private val origin = Vector3(0.0, 0.0, 0.0)
    private val identity = RigidTransform.identity()
    private fun pose(ns: Long, transform: RigidTransform, domain: ClockDomain = ClockDomain.ARCORE_FRAME) =
        Timestamped(SourceTimestamp(ns, domain, ns), transform)

    @Test
    fun identityAndAxisTranslationsUseMetersAndColumnMajorDirection() {
        val p = Vector3(1.25, -2.5, 3.75)
        close(p, identity.transform(p))
        for (t in listOf(Vector3(2.0, 0.0, 0.0), Vector3(0.0, -3.0, 0.0), Vector3(0.0, 0.0, 4.0))) {
            val transform = RigidTransform.translation(t)
            close(p + t, transform.transform(p))
            close(p, transform.inverse().transform(transform.transform(p)))
            close(identity, transform * transform.inverse())
            assertEquals(listOf(t.x, t.y, t.z), transform.toColumnMajor().slice(12..14))
        }
        close(Vector3(0.0, 0.0, -0.001), RigidTransform.translation(Vector3(0.0, 0.0, -0.001)).transform(origin))
        close(1.0, identity.rotationDeterminant)
    }

    @Test
    fun ninetyDegreeRotationFixturesAreIndependentOfQuaternionGeneration() {
        val worldFromCamera = RigidTransform.fromColumnMajor(listOf(
            0.0, 1.0, 0.0, 0.0,
            -1.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            2.0, 3.0, 4.0, 1.0,
        ))
        close(Vector3(2.0, 4.0, 4.0), worldFromCamera.transform(Vector3(1.0, 0.0, 0.0)))
        close(Vector3(1.0, 3.0, 4.0), worldFromCamera.transform(Vector3(0.0, 1.0, 0.0)))
        close(Vector3(2.0, 3.0, 3.0), worldFromCamera.transform(Vector3(0.0, 0.0, -1.0)))
        val point = Vector3(7.0, -8.0, 9.0)
        close(point, worldFromCamera.inverse().transform(worldFromCamera.transform(point)))
        close(identity, worldFromCamera.inverse() * worldFromCamera)
    }

    @Test
    fun compositionRespectsFrameOrder() {
        val rotate = RigidTransform.fromColumnMajor(listOf(
            0.0, 1.0, 0.0, 0.0,
            -1.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        ))
        val translate = RigidTransform.translation(Vector3(2.0, 0.0, 0.0))
        close(Vector3(0.0, 2.0, 0.0), (rotate * translate).transform(origin))
        close(Vector3(2.0, 0.0, 0.0), (translate * rotate).transform(origin))
    }

    @Test
    fun rigidTransformRejectsScaleShearReflectionAndInvalidHomogeneousRows() {
        val valid = identity.toColumnMajor()
        for (change in listOf<Pair<Int, Double>>(0 to 1000.0, 0 to 2.0, 4 to 0.25, 0 to -1.0, 3 to 1.0, 7 to 1.0, 11 to 1.0, 15 to 0.0)) {
            val bad = valid.toMutableList().also { it[change.first] = change.second }
            assertFailsWith<IllegalArgumentException> { RigidTransform.fromColumnMajor(bad) }
        }
        assertFailsWith<IllegalArgumentException> { RigidTransform.fromColumnMajor(valid.dropLast(1)) }
        assertFailsWith<IllegalArgumentException> { RigidTransform.fromColumnMajor(valid, 1.0) }
    }

    @Test
    fun numericalBoundariesRejectNanInfinityAndOverflow() {
        for (nonfinite in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (index in 0..15) assertFailsWith<IllegalArgumentException> {
                RigidTransform.fromColumnMajor(identity.toColumnMajor().toMutableList().also { it[index] = nonfinite })
            }
            assertFailsWith<IllegalArgumentException> { Vector3(nonfinite, 0.0, 0.0) }
            assertFailsWith<IllegalArgumentException> { UnitQuaternion.normalized(0.0, 0.0, 0.0, nonfinite) }
        }
        assertFailsWith<IllegalArgumentException> { Vector3(Double.MAX_VALUE, 0.0, 0.0) * 2.0 }
        assertFailsWith<IllegalArgumentException> { RigidTransform.translation(Vector3(Double.MAX_VALUE, 0.0, 0.0)).transform(Vector3(Double.MAX_VALUE, 0.0, 0.0)) }
    }

    @Test
    fun matrixInputAndOutputCopiesCannotMutateValidatedPose() {
        val mutable = identity.toColumnMajor().toMutableList()
        val transform = RigidTransform.fromColumnMajor(mutable)
        mutable[12] = 123.0
        assertEquals(0.0, transform.translationMeters.x)
        val output = transform.toColumnMajor().toMutableList()
        output[13] = 456.0
        assertEquals(0.0, transform.translationMeters.y)
    }

    @Test
    fun cameraOpticalConversionHasKnownSignsAndIsAnInvolution() {
        val cameraPoint = Vector3(1.0, 2.0, -3.0)
        close(Vector3(1.0, -2.0, 3.0), CameraCoordinates.opticalFromCamera.transform(cameraPoint))
        close(cameraPoint, CameraCoordinates.cameraFromOptical.transform(CameraCoordinates.opticalFromCamera.transform(cameraPoint)))
        close(identity, CameraCoordinates.opticalFromCamera * CameraCoordinates.cameraFromOptical)
        close(1.0, CameraCoordinates.opticalFromCamera.rotationDeterminant)
        val worldFromCamera = RigidTransform.translation(Vector3(10.0, 20.0, 30.0))
        close(Vector3(11.0, 22.0, 27.0), CameraCoordinates.worldFromOptical(worldFromCamera).transform(Vector3(1.0, -2.0, 3.0)))
        val lookingAt = CameraCoordinates.worldFromOptical(identity).transform(Vector3(0.0, 0.0, 2.0))
        close(Vector3(0.0, 0.0, -2.0), lookingAt)
    }

    @Test
    fun projectionAndAxialDepthUnprojectionMatchAnalyticalFixture() {
        val calibration = PinholeIntrinsics(640, 480, 500.0, 600.0, 320.0, 240.0)
        val point = Vector3(0.4, -0.2, 2.0)
        assertEquals(PixelPoint(420.0, 180.0), calibration.projectOptical(point))
        close(point, calibration.unprojectOptical(PixelPoint(420.0, 180.0), 2.0))
        close(Vector3(0.2, -0.1, 1.0), calibration.unprojectOptical(PixelPoint(420.0, 180.0), 1.0))
        val arPoint = Vector3(0.4, 0.2, -2.0)
        assertEquals(PixelPoint(420.0, 180.0), calibration.projectOptical(CameraCoordinates.opticalFromCamera.transform(arPoint)))
        for (z in listOf(0.001, 1.0, 10_000.0)) for (x in listOf(-0.3, 0.0, 0.7)) for (y in listOf(-0.4, 0.0, 0.5)) {
            val p = Vector3(x * z, y * z, z)
            close(p, calibration.unprojectOptical(calibration.projectOptical(p), z))
        }
    }

    @Test
    fun intrinsicsScalingDeclaresPixelOriginAndSupportsAnisotropicDimensions() {
        val original = PinholeIntrinsics(640, 480, 500.0, 600.0, 319.5, 239.5)
        val resized = original.scaledTo(320, 120)
        assertEquals(PinholeIntrinsics(320, 120, 250.0, 150.0, 159.75, 59.875), resized)
        val p = Vector3(0.4, -0.2, 2.0)
        val pixel = original.projectOptical(p)
        val resizedPixel = resized.projectOptical(p)
        close(pixel.u / 2, resizedPixel.u)
        close(pixel.v / 4, resizedPixel.v)
        assertEquals(original, resized.scaledTo(640, 480))
    }

    @Test
    fun calibrationAndProjectionRejectInvalidDepthAndGeometry() {
        val intrinsics = PinholeIntrinsics(640, 480, 500.0, 500.0, 320.0, 240.0)
        for (depth in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { intrinsics.unprojectOptical(PixelPoint(320.0, 240.0), depth) }
        }
        for (z in listOf(0.0, -1.0)) assertFailsWith<IllegalArgumentException> { intrinsics.projectOptical(Vector3(1.0, 2.0, z)) }
        assertFailsWith<IllegalArgumentException> { intrinsics.copy(width = 0) }
        assertFailsWith<IllegalArgumentException> { intrinsics.copy(height = -1) }
        assertFailsWith<IllegalArgumentException> { intrinsics.copy(fx = 0.0) }
        assertFailsWith<IllegalArgumentException> { intrinsics.copy(fy = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { intrinsics.copy(cx = Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { intrinsics.scaledTo(0, 1) }
        assertTrue(intrinsics.contains(PixelPoint(0.0, 0.0)))
        assertTrue(intrinsics.contains(PixelPoint(639.999, 479.999)))
        assertFalse(intrinsics.contains(PixelPoint(640.0, 240.0)))
        assertFalse(intrinsics.contains(PixelPoint(320.0, 480.0)))
        assertFalse(intrinsics.contains(PixelPoint(-0.001, 0.0)))
    }

    @Test
    fun quaternionConversionCoversTraceAndEachHalfTurnBranch() {
        for (axis in listOf(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector3(0.0, 0.0, 1.0), Vector3(1.0, 2.0, 3.0))) {
            for (angle in listOf(0.0, PI / 2, PI, 1.75 * PI)) {
                val rotation = UnitQuaternion.axisAngle(axis, angle).toTransform()
                close(rotation, UnitQuaternion.fromRotation(rotation).toTransform())
                close(identity, rotation * rotation.inverse())
            }
        }
        close(Vector3(0.0, 1.0, 0.0), UnitQuaternion.axisAngle(Vector3(0.0, 0.0, 1.0), PI / 2).toTransform().transform(Vector3(1.0, 0.0, 0.0)))
        // Known Y rotation: AR camera forward -Z turns toward -X.
        close(Vector3(-1.0, 0.0, 0.0), UnitQuaternion.axisAngle(Vector3(0.0, 1.0, 0.0), PI / 2).toTransform().transform(Vector3(0.0, 0.0, -1.0)))
    }

    @Test
    fun slerpTakesShortestArcAndHandlesAntipodalIdenticalAndNearIdenticalQuaternions() {
        val start = UnitQuaternion.normalized(0.0, 0.0, 0.0, 1.0)
        val end = UnitQuaternion.axisAngle(Vector3(0.0, 0.0, 1.0), PI / 2)
        close(Vector3(sqrt(0.5), sqrt(0.5), 0.0), start.slerp(end, 0.5).toTransform().transform(Vector3(1.0, 0.0, 0.0)))
        close(identity, start.slerp(UnitQuaternion.normalized(0.0, 0.0, 0.0, -1.0), 0.5).toTransform())
        close(end.toTransform(), end.slerp(end, 0.25).toTransform())
        val tiny = UnitQuaternion.axisAngle(Vector3(0.0, 0.0, 1.0), 1e-6)
        close(Vector3(cos(5e-7), sin(5e-7), 0.0), start.slerp(tiny, 0.5).toTransform().transform(Vector3(1.0, 0.0, 0.0)))
        val longArc = UnitQuaternion.axisAngle(Vector3(0.0, 0.0, 1.0), 1.5 * PI)
        close(Vector3(sqrt(0.5), -sqrt(0.5), 0.0), start.slerp(longArc, 0.5).toTransform().transform(Vector3(1.0, 0.0, 0.0)))
    }

    @Test
    fun quaternionNormalizationAvoidsIntermediateOverflowAndUnderflow() {
        val huge = UnitQuaternion.normalized(Double.MAX_VALUE, 0.0, 0.0, Double.MAX_VALUE)
        val tiny = UnitQuaternion.normalized(Double.MIN_VALUE, 0.0, 0.0, Double.MIN_VALUE)
        close(huge.toTransform(), tiny.toTransform())
        assertFailsWith<IllegalArgumentException> { UnitQuaternion.normalized(0.0, 0.0, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { UnitQuaternion.axisAngle(origin, 1.0) }
        assertFailsWith<IllegalArgumentException> { huge.slerp(tiny, -0.1) }
        assertFailsWith<IllegalArgumentException> { huge.slerp(tiny, 1.1) }
        assertFailsWith<IllegalArgumentException> { huge.slerp(tiny, Double.NaN) }
    }

    @Test
    fun boundedInterpolationUsesLerpSlerpAndPreservesBothMeasuredEndpoints() {
        val start = pose(100, identity)
        val endTransform = UnitQuaternion.axisAngle(Vector3(0.0, 0.0, 1.0), PI / 2).toTransform(Vector3(2.0, 4.0, 6.0))
        val end = pose(200, endTransform)
        val middle = assertIs<PoseInterpolation.Interpolated>(BoundedPoseInterpolator.interpolate(start, end, pose(150, identity).timestamp, 100))
        close(Vector3(1.0, 2.0, 3.0), middle.worldFromCamera.translationMeters)
        close(Vector3(1 + sqrt(0.5), 2 + sqrt(0.5), 3.0), middle.worldFromCamera.transform(Vector3(1.0, 0.0, 0.0)))
        close(0.5, middle.fraction)
        assertEquals(start, middle.start); assertEquals(end, middle.end)
        close(identity, assertIs<PoseInterpolation.Interpolated>(BoundedPoseInterpolator.interpolate(start, end, start.timestamp, 100)).worldFromCamera)
        close(endTransform, assertIs<PoseInterpolation.Interpolated>(BoundedPoseInterpolator.interpolate(start, end, end.timestamp, 100)).worldFromCamera)
    }

    @Test
    fun interpolationRejectsExtrapolationLongGapsUnorderedAndMixedClockTimes() {
        val start = pose(100, identity)
        val end = pose(200, identity)
        fun rejected(time: Long, bound: Long = 100) = assertIs<PoseInterpolation.Rejected>(
            BoundedPoseInterpolator.interpolate(start, end, pose(time, identity).timestamp, bound),
        ).reason
        assertEquals(PoseInterpolationFailure.OUTSIDE_INTERVAL, rejected(99))
        assertEquals(PoseInterpolationFailure.OUTSIDE_INTERVAL, rejected(201))
        assertEquals(PoseInterpolationFailure.INTERVAL_TOO_LARGE, rejected(150, 99))
        assertEquals(PoseInterpolationFailure.NONINCREASING_INTERVAL,
            assertIs<PoseInterpolation.Rejected>(BoundedPoseInterpolator.interpolate(end, start, start.timestamp, 100)).reason)
        assertEquals(PoseInterpolationFailure.NONINCREASING_INTERVAL,
            assertIs<PoseInterpolation.Rejected>(BoundedPoseInterpolator.interpolate(start, start, start.timestamp, 100)).reason)
        assertEquals(PoseInterpolationFailure.CLOCK_DOMAIN_MISMATCH,
            assertIs<PoseInterpolation.Rejected>(BoundedPoseInterpolator.interpolate(start, end, pose(150, identity, ClockDomain.CAMERA_REALTIME).timestamp, 100)).reason)
        assertFailsWith<IllegalArgumentException> { BoundedPoseInterpolator.interpolate(start, end, start.timestamp, 0) }
    }

    @Test
    fun interpolationSubtractsLongEpochsBeforeFloatingPointConversion() {
        val epoch = Long.MAX_VALUE - 10
        val start = pose(epoch, identity)
        val end = pose(epoch + 2, RigidTransform.translation(Vector3(2.0, 0.0, 0.0)))
        val result = assertIs<PoseInterpolation.Interpolated>(BoundedPoseInterpolator.interpolate(start, end, pose(epoch + 1, identity).timestamp, 2))
        assertEquals(0.5, result.fraction)
        close(Vector3(1.0, 0.0, 0.0), result.worldFromCamera.translationMeters)
    }
}
