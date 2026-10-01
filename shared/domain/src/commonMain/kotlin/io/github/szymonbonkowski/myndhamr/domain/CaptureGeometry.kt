package io.github.szymonbonkowski.myndhamr.domain

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Vector3(val x: Double, val y: Double, val z: Double) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) { "Vector coordinates must be finite" } }
    operator fun plus(other: Vector3) = Vector3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3) = Vector3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double): Vector3 {
        require(scale.isFinite())
        return Vector3(x * scale, y * scale, z * scale)
    }
}

/** Right-handed rigid T_destination_source, meters, column vectors, column-major 4x4. */
class RigidTransform private constructor(private val elements: List<Double>) {
    val translationMeters: Vector3 get() = Vector3(elements[12], elements[13], elements[14])
    val rotationDeterminant: Double get() = determinant(elements)

    /** Returns a copy; raw calibration/pose input is never mutated by this class. */
    fun toColumnMajor(): List<Double> = elements.toList()

    fun transform(pointMeters: Vector3): Vector3 = Vector3(
        elements[0] * pointMeters.x + elements[4] * pointMeters.y + elements[8] * pointMeters.z + elements[12],
        elements[1] * pointMeters.x + elements[5] * pointMeters.y + elements[9] * pointMeters.z + elements[13],
        elements[2] * pointMeters.x + elements[6] * pointMeters.y + elements[10] * pointMeters.z + elements[14],
    )

    /** T_A_B * T_B_C = T_A_C. Callers own the named frame relationship. */
    operator fun times(other: RigidTransform): RigidTransform {
        val result = MutableList(16) { 0.0 }
        for (column in 0..2) for (row in 0..2) {
            result[column * 4 + row] = (0..2).sumOf { k -> elements[k * 4 + row] * other.elements[column * 4 + k] }
        }
        val translation = transform(other.translationMeters)
        result[12] = translation.x
        result[13] = translation.y
        result[14] = translation.z
        result[15] = 1.0
        return fromColumnMajor(result)
    }

    fun inverse(): RigidTransform {
        val result = MutableList(16) { 0.0 }
        for (column in 0..2) for (row in 0..2) result[column * 4 + row] = elements[row * 4 + column]
        val t = translationMeters
        result[12] = -(elements[0] * t.x + elements[1] * t.y + elements[2] * t.z)
        result[13] = -(elements[4] * t.x + elements[5] * t.y + elements[6] * t.z)
        result[14] = -(elements[8] * t.x + elements[9] * t.y + elements[10] * t.z)
        result[15] = 1.0
        return fromColumnMajor(result)
    }

    companion object {
        /** Accepts ordinary floating-point rotation error, rejects scale, shear and reflection. */
        fun fromColumnMajor(matrix: List<Double>, tolerance: Double = 1e-5): RigidTransform {
            require(tolerance.isFinite() && tolerance > 0 && tolerance <= 1e-3) { "Invalid rigid-transform tolerance" }
            require(matrix.size == 16 && matrix.all { it.isFinite() }) { "A transform requires 16 finite values" }
            require(abs(matrix[3]) <= tolerance && abs(matrix[7]) <= tolerance &&
                abs(matrix[11]) <= tolerance && abs(matrix[15] - 1) <= tolerance) { "Invalid homogeneous row" }
            for (first in 0..2) for (second in first..2) {
                val dot = (0..2).sumOf { row -> matrix[first * 4 + row] * matrix[second * 4 + row] }
                require(abs(dot - if (first == second) 1.0 else 0.0) <= tolerance) { "Rotation must be orthonormal" }
            }
            require(abs(determinant(matrix) - 1) <= tolerance) { "Rotation must preserve right handedness" }
            return RigidTransform(matrix.toList())
        }

        fun identity(): RigidTransform = translation(Vector3(0.0, 0.0, 0.0))

        fun translation(meters: Vector3): RigidTransform = fromColumnMajor(listOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            meters.x, meters.y, meters.z, 1.0,
        ))
    }
}

private fun determinant(m: List<Double>): Double =
    m[0] * (m[5] * m[10] - m[9] * m[6]) -
        m[4] * (m[1] * m[10] - m[9] * m[2]) +
        m[8] * (m[1] * m[6] - m[5] * m[2])

/** AR camera: +X right, +Y up, -Z forward. CPU optical: +X right, +Y down, +Z forward. */
object CameraCoordinates {
    val opticalFromCamera: RigidTransform = RigidTransform.fromColumnMajor(listOf(
        1.0, 0.0, 0.0, 0.0,
        0.0, -1.0, 0.0, 0.0,
        0.0, 0.0, -1.0, 0.0,
        0.0, 0.0, 0.0, 1.0,
    ))
    val cameraFromOptical: RigidTransform get() = opticalFromCamera

    fun worldFromOptical(worldFromCamera: RigidTransform): RigidTransform = worldFromCamera * cameraFromOptical
}

data class PixelPoint(val u: Double, val v: Double) {
    init { require(u.isFinite() && v.isFinite()) { "Pixel coordinates must be finite" } }
}

/** Unrotated CPU-image pixel coordinates. Depth is positive optical axial Z in meters. */
data class PinholeIntrinsics(
    val width: Int,
    val height: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
) {
    init {
        require(width > 0 && height > 0) { "Calibration dimensions must be positive" }
        require(fx.isFinite() && fy.isFinite() && cx.isFinite() && cy.isFinite() && fx > 0 && fy > 0) {
            "Pinhole calibration requires finite values and positive focal lengths"
        }
    }

    /** Direct pixel-coordinate scaling u'=sx*u, v'=sy*v; no crop, rotation or half-pixel shift. */
    fun scaledTo(width: Int, height: Int): PinholeIntrinsics {
        require(width > 0 && height > 0)
        val sx = width.toDouble() / this.width
        val sy = height.toDouble() / this.height
        return PinholeIntrinsics(width, height, fx * sx, fy * sy, cx * sx, cy * sy)
    }

    fun projectOptical(pointMeters: Vector3): PixelPoint {
        require(pointMeters.z > 0) { "Projection requires a point in front of the optical camera" }
        return PixelPoint(fx * (pointMeters.x / pointMeters.z) + cx, fy * (pointMeters.y / pointMeters.z) + cy)
    }

    fun unprojectOptical(pixel: PixelPoint, axialDepthMeters: Double): Vector3 {
        require(axialDepthMeters.isFinite() && axialDepthMeters > 0) { "Axial depth must be finite and positive meters" }
        return Vector3((pixel.u - cx) * axialDepthMeters / fx, (pixel.v - cy) * axialDepthMeters / fy, axialDepthMeters)
    }

    fun contains(pixel: PixelPoint): Boolean = pixel.u >= 0 && pixel.u < width && pixel.v >= 0 && pixel.v < height
}

/** Unit quaternion (x,y,z,w). Construction rejects zero or nonfinite rotations. */
class UnitQuaternion private constructor(val x: Double, val y: Double, val z: Double, val w: Double) {
    fun toTransform(translationMeters: Vector3 = Vector3(0.0, 0.0, 0.0)): RigidTransform =
        RigidTransform.fromColumnMajor(listOf(
            1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0.0,
            2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0.0,
            2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0.0,
            translationMeters.x, translationMeters.y, translationMeters.z, 1.0,
        ))

    fun slerp(other: UnitQuaternion, fraction: Double): UnitQuaternion {
        require(fraction.isFinite() && fraction in 0.0..1.0) { "SLERP fraction must lie in [0,1]" }
        var ox = other.x
        var oy = other.y
        var oz = other.z
        var ow = other.w
        var dot = x * ox + y * oy + z * oz + w * ow
        if (dot < 0) {
            ox = -ox; oy = -oy; oz = -oz; ow = -ow
            dot = -dot
        }
        dot = dot.coerceIn(-1.0, 1.0)
        if (dot > 0.9995) return normalized(
            x + fraction * (ox - x), y + fraction * (oy - y), z + fraction * (oz - z), w + fraction * (ow - w),
        )
        val angle = acos(dot)
        val denominator = sin(angle)
        val a = sin((1 - fraction) * angle) / denominator
        val b = sin(fraction * angle) / denominator
        return normalized(a * x + b * ox, a * y + b * oy, a * z + b * oz, a * w + b * ow)
    }

    companion object {
        fun normalized(x: Double, y: Double, z: Double, w: Double): UnitQuaternion {
            require(listOf(x, y, z, w).all { it.isFinite() }) { "Quaternion must be finite" }
            // Scale before squaring to avoid overflow/underflow for otherwise valid input.
            val scale = maxOf(abs(x), abs(y), abs(z), abs(w))
            require(scale > 0) { "Quaternion must have nonzero length" }
            val sx = x / scale; val sy = y / scale; val sz = z / scale; val sw = w / scale
            val length = sqrt(sx * sx + sy * sy + sz * sz + sw * sw)
            return UnitQuaternion(sx / length, sy / length, sz / length, sw / length)
        }

        fun fromRotation(transform: RigidTransform): UnitQuaternion {
            val m = transform.toColumnMajor()
            val trace = m[0] + m[5] + m[10]
            return if (trace > 0) {
                val s = sqrt(trace + 1) * 2
                normalized((m[6] - m[9]) / s, (m[8] - m[2]) / s, (m[1] - m[4]) / s, s / 4)
            } else if (m[0] > m[5] && m[0] > m[10]) {
                val s = sqrt(1 + m[0] - m[5] - m[10]) * 2
                normalized(s / 4, (m[4] + m[1]) / s, (m[8] + m[2]) / s, (m[6] - m[9]) / s)
            } else if (m[5] > m[10]) {
                val s = sqrt(1 + m[5] - m[0] - m[10]) * 2
                normalized((m[4] + m[1]) / s, s / 4, (m[9] + m[6]) / s, (m[8] - m[2]) / s)
            } else {
                val s = sqrt(1 + m[10] - m[0] - m[5]) * 2
                normalized((m[8] + m[2]) / s, (m[9] + m[6]) / s, s / 4, (m[1] - m[4]) / s)
            }
        }

        fun axisAngle(axis: Vector3, radians: Double): UnitQuaternion {
            require(radians.isFinite())
            val scale = maxOf(abs(axis.x), abs(axis.y), abs(axis.z))
            require(scale > 0) { "Rotation axis must be nonzero" }
            val a = Vector3(axis.x / scale, axis.y / scale, axis.z / scale)
            val length = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
            val s = sin(radians / 2) / length
            return normalized(a.x * s, a.y * s, a.z * s, cos(radians / 2))
        }
    }
}

enum class PoseInterpolationFailure { CLOCK_DOMAIN_MISMATCH, NONINCREASING_INTERVAL, OUTSIDE_INTERVAL, INTERVAL_TOO_LARGE }

sealed class PoseInterpolation {
    data class Interpolated(
        val timestamp: ClockTimestamp,
        val worldFromCamera: RigidTransform,
        val start: Timestamped<RigidTransform>,
        val end: Timestamped<RigidTransform>,
        val fraction: Double,
    ) : PoseInterpolation()
    data class Rejected(val reason: PoseInterpolationFailure) : PoseInterpolation()
}

/** Optional bounded derived pose; real capture association uses exact measured poses. */
object BoundedPoseInterpolator {
    fun interpolate(
        start: Timestamped<RigidTransform>,
        end: Timestamped<RigidTransform>,
        timestamp: ClockTimestamp,
        maximumIntervalNanoseconds: Long,
    ): PoseInterpolation {
        require(maximumIntervalNanoseconds > 0)
        if (start.timestamp.clockDomain != end.timestamp.clockDomain || start.timestamp.clockDomain != timestamp.clockDomain) {
            return PoseInterpolation.Rejected(PoseInterpolationFailure.CLOCK_DOMAIN_MISMATCH)
        }
        val interval = checkedSubtractNanoseconds(end.timestamp.nanoseconds, start.timestamp.nanoseconds)
        if (interval <= 0) return PoseInterpolation.Rejected(PoseInterpolationFailure.NONINCREASING_INTERVAL)
        if (timestamp.nanoseconds < start.timestamp.nanoseconds || timestamp.nanoseconds > end.timestamp.nanoseconds) {
            return PoseInterpolation.Rejected(PoseInterpolationFailure.OUTSIDE_INTERVAL)
        }
        if (interval > maximumIntervalNanoseconds) return PoseInterpolation.Rejected(PoseInterpolationFailure.INTERVAL_TOO_LARGE)
        // Subtract integer epochs first, preserving 1ns changes even beyond 2^53.
        val fraction = checkedSubtractNanoseconds(timestamp.nanoseconds, start.timestamp.nanoseconds).toDouble() / interval.toDouble()
        val translation = start.value.translationMeters * (1 - fraction) + end.value.translationMeters * fraction
        val rotation = UnitQuaternion.fromRotation(start.value).slerp(UnitQuaternion.fromRotation(end.value), fraction)
        return PoseInterpolation.Interpolated(timestamp, rotation.toTransform(translation), start, end, fraction)
    }
}
