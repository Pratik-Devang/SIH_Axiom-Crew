package com.percorsa.sensorlogger

/** Explicit frame labels used by the future ESKF adapter. */
enum class EskfFrame { PHONE, WORLD_ENU, VEHICLE }

data class EskfVector3(val x: Double, val y: Double, val z: Double) {
    fun asArray(): DoubleArray = doubleArrayOf(x, y, z)

    companion object {
        fun fromArray(value: DoubleArray): EskfVector3 {
            require(value.size == 3 && value.all(Double::isFinite)) { "Vector must be finite 3D" }
            return EskfVector3(value[0], value[1], value[2])
        }
    }
}

/** R_v_p: vehicle vector = R_v_p * phone vector. */
data class PhoneToVehicleRotation(val values: Array<DoubleArray>) {
    init {
        require(values.size == 3 && values.all { it.size == 3 }) { "Rotation must be 3x3" }
        require(values.all { row -> row.all(Double::isFinite) }) { "Rotation contains NaN or Inf" }
    }

    fun copyArray(): Array<DoubleArray> = Array(3) { values[it].copyOf() }

    /** Vehicle forward axis (X) in phone coordinates: Row 0 of R_v_p (since v_phone = R_v_p^T * [1, 0, 0]^T). */
    val forwardPhone: EskfVector3 get() = EskfVector3(values[0][0], values[0][1], values[0][2])

    /** Vehicle lateral axis (Y, Left) in phone coordinates: Row 1 of R_v_p. */
    val lateralPhone: EskfVector3 get() = EskfVector3(values[1][0], values[1][1], values[1][2])

    /** Vehicle vertical axis (Z, Up) in phone coordinates: Row 2 of R_v_p. */
    val upPhone: EskfVector3 get() = EskfVector3(values[2][0], values[2][1], values[2][2])

    companion object {
        val IDENTITY = PhoneToVehicleRotation(arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0)
        ))

        /** Build an orthonormal R_v_p from forward and up vectors in the phone frame. */
        fun fromForwardAndUp(forwardPhone: EskfVector3, upPhone: EskfVector3): PhoneToVehicleRotation {
            val uUp = normalize(upPhone)
            // Left = Up x Forward
            val uLeft = normalize(cross(uUp, forwardPhone))
            // Re-orthogonalize Forward = Left x Up
            val uFwd = normalize(cross(uLeft, uUp))
            return PhoneToVehicleRotation(arrayOf(
                doubleArrayOf(uFwd.x, uFwd.y, uFwd.z),
                doubleArrayOf(uLeft.x, uLeft.y, uLeft.z),
                doubleArrayOf(uUp.x, uUp.y, uUp.z)
            ))
        }

        private fun normalize(v: EskfVector3): EskfVector3 {
            val len = kotlin.math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
            require(len > 1e-6) { "Cannot normalize zero-length vector" }
            return EskfVector3(v.x / len, v.y / len, v.z / len)
        }

        private fun cross(a: EskfVector3, b: EskfVector3): EskfVector3 = EskfVector3(
            a.y * b.z - a.z * b.y,
            a.z * b.x - a.x * b.z,
            a.x * b.y - a.y * b.x
        )
    }
}

private fun multiply(matrix: Array<DoubleArray>, vector: EskfVector3): EskfVector3 =
    EskfVector3(
        matrix[0][0] * vector.x + matrix[0][1] * vector.y + matrix[0][2] * vector.z,
        matrix[1][0] * vector.x + matrix[1][1] * vector.y + matrix[1][2] * vector.z,
        matrix[2][0] * vector.x + matrix[2][1] * vector.y + matrix[2][2] * vector.z
    )

private fun transpose(matrix: Array<DoubleArray>): Array<DoubleArray> =
    Array(3) { row -> DoubleArray(3) { column -> matrix[column][row] } }

/** Phone → world using the Python phone-to-ENU quaternion convention. */
fun phoneToWorld(vectorPhone: EskfVector3, phoneToWorld: EskfQuaternion): EskfVector3 {
    return multiply(phoneToWorld.toRotationMatrix(), vectorPhone)
}

/** Phone → vehicle using the calibrated R_v_p contract. */
fun phoneToVehicle(vectorPhone: EskfVector3, transform: PhoneToVehicleRotation): EskfVector3 =
    multiply(transform.values, vectorPhone)

/** Vehicle → world = (phone → world) * (vehicle → phone). */
fun vehicleToWorld(
    vectorVehicle: EskfVector3,
    phoneToWorldQuaternion: EskfQuaternion,
    phoneToVehicle: PhoneToVehicleRotation
): EskfVector3 = phoneToWorld(
    phoneToVehicle(vectorVehicle, PhoneToVehicleRotation(transpose(phoneToVehicle.values))),
    phoneToWorldQuaternion
)
