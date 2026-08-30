package com.percorsa.sensorlogger

/**
 * Immutable 10 Hz canonical IMU measurement sample.
 * Aligns with Python preprocessing feature order:
 * [accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z]
 */
data class CanonicalImuSample(
    val timestampNs: Long,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val linearAccelX: Float = 0f,
    val linearAccelY: Float = 0f,
    val linearAccelZ: Float = 0f,
    val vehicleAccelForward: Float = 0f,
    val vehicleAccelLeft: Float = 0f,
    val vehicleAccelUp: Float = 0f
) {
    /** Feature array for downstream TCN model input [6 features]. */
    fun toFeatureArray(): FloatArray = floatArrayOf(
        accelX, accelY, accelZ,
        gyroX, gyroY, gyroZ
    )
}
