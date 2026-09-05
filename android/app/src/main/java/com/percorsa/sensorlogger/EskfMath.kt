package com.percorsa.sensorlogger

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Hamilton quaternion [w, x, y, z], matching src/navigation/ins.py. */
data class EskfQuaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    init {
        require(w.isFinite() && x.isFinite() && y.isFinite() && z.isFinite()) {
            "Quaternion contains NaN or Inf"
        }
    }

    fun norm(): Double = sqrt(w * w + x * x + y * y + z * z)

    fun normalized(): EskfQuaternion {
        val magnitude = norm()
        require(magnitude >= NORMALIZATION_TOLERANCE) { "Quaternion norm is invalid" }
        return EskfQuaternion(w / magnitude, x / magnitude, y / magnitude, z / magnitude)
    }

    /** Rotation matrix R_phone_world: world vector = R * phone vector. */
    fun toRotationMatrix(): Array<DoubleArray> {
        val q = normalized()
        val (w, x, y, z) = q
        return arrayOf(
            doubleArrayOf(1.0 - 2.0 * (y * y + z * z), 2.0 * (x * y - w * z), 2.0 * (x * z + w * y)),
            doubleArrayOf(2.0 * (x * y + w * z), 1.0 - 2.0 * (x * x + z * z), 2.0 * (y * z - w * x)),
            doubleArrayOf(2.0 * (x * z - w * y), 2.0 * (y * z + w * x), 1.0 - 2.0 * (x * x + y * y))
        )
    }

    operator fun times(other: EskfQuaternion): EskfQuaternion = EskfQuaternion(
        w * other.w - x * other.x - y * other.y - z * other.z,
        w * other.x + x * other.w + y * other.z - z * other.y,
        w * other.y - x * other.z + y * other.w + z * other.x,
        w * other.z + x * other.y - y * other.x + z * other.w
    )

    companion object {
        const val NORMALIZATION_TOLERANCE = 1e-12
        val IDENTITY = EskfQuaternion(1.0, 0.0, 0.0, 0.0)
    }
}

/** Python delta_quat_from_rotation_vector(), including its small-angle series. */
fun deltaQuaternionFromRotationVector(deltaTheta: DoubleArray): EskfQuaternion {
    require(deltaTheta.size == 3 && deltaTheta.all(Double::isFinite)) { "Rotation vector must be finite 3D" }
    val thetaSquared = deltaTheta.sumOf { it * it }
    val theta = sqrt(thetaSquared)
    val scale: Double
    val scalar: Double
    if (theta > 1e-8) {
        scalar = cos(0.5 * theta)
        scale = sin(0.5 * theta) / theta
    } else {
        scalar = 1.0 - thetaSquared / 8.0
        scale = 0.5 - thetaSquared / 48.0
    }
    return EskfQuaternion(
        scalar,
        scale * deltaTheta[0],
        scale * deltaTheta[1],
        scale * deltaTheta[2]
    ).normalized()
}

/** Right-multiplicative attitude error injection: q_new = q * delta_q. */
fun injectRightMultiplicativeAttitudeError(
    nominal: EskfQuaternion,
    deltaTheta: DoubleArray
): EskfQuaternion = (nominal.normalized() * deltaQuaternionFromRotationVector(deltaTheta)).normalized()
