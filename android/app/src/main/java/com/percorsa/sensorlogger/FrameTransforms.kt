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
