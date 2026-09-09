package com.percorsa.sensorlogger

/**
 * Immutable 10 Hz canonical IMU measurement sample.
 *
 * Channel order aligns with IO-VNBD benchmark training data:
 *   [accel_lateral, accel_forward, accel_up, gyro_x, gyro_y, gyro_z]
 *
 * The benchmark vehicle frame is Z-Up, Y-Forward, X-Lateral.
 * Training normalization: mean[accel_z] ≈ 9.845 m/s² (gravity included in Z channel).
 */
data class CanonicalImuSample(
    val timestampNs: Long,
    /** Raw accelerometer X (phone frame). Preserved for logging. */
    val accelX: Float,
    /** Raw accelerometer Y (phone frame). Preserved for logging. */
    val accelY: Float,
    /** Raw accelerometer Z (phone frame). Preserved for logging. */
    val accelZ: Float,
    /** Gyroscope X (phone frame). Preserved for logging. */
    val gyroX: Float,
    /** Gyroscope Y (phone frame). Preserved for logging. */
    val gyroY: Float,
    /** Gyroscope Z (phone frame). Preserved for logging. */
    val gyroZ: Float,
    val linearAccelX: Float = 0f,
    val linearAccelY: Float = 0f,
    val linearAccelZ: Float = 0f,
    /** Raw accel in vehicle forward direction (gravity included). Channel 1 → accel_y. */
    val vehicleAccelForward: Float = 0f,
    /** Raw accel in vehicle lateral direction (gravity included). Channel 0 → accel_x. */
    val vehicleAccelLeft: Float = 0f,
    /** Raw accel in vehicle up direction (gravity included, ≈+9.81 stationary). Channel 2 → accel_z. */
    val vehicleAccelUp: Float = 0f,
    /** Gyroscope in vehicle lateral direction. Channel 3 → gyro_x. */
    val vehicleGyroLeft: Float = 0f,
    /** Gyroscope in vehicle forward direction. Channel 4 → gyro_y. */
    val vehicleGyroForward: Float = 0f,
    /** Gyroscope in vehicle up direction. Channel 5 → gyro_z. */
    val vehicleGyroUp: Float = 0f,
    /** True when vehicle-frame values are calibrated and valid for TCN inference. */
    val vehicleFrameCalibrated: Boolean = false
) {
    /**
     * Feature array for TCN model input [6 channels].
     *
     * When the phone-to-vehicle calibration is available ([vehicleFrameCalibrated] == true),
     * returns vehicle-benchmark-frame channels so the model receives in-distribution inputs
     * regardless of how the phone is physically mounted in the car:
     *   [accel_lateral, accel_forward, accel_up, gyro_lateral, gyro_forward, gyro_up]
     *
     * Falls back to raw phone-frame when uncalibrated (signals degraded output to caller).
     */
    fun toFeatureArray(): FloatArray = if (vehicleFrameCalibrated) {
        floatArrayOf(
            vehicleAccelLeft,    // accel_x → lateral
            vehicleAccelForward, // accel_y → forward
            vehicleAccelUp,      // accel_z → up (≈+9.81 stationary, matching model normalization)
            vehicleGyroLeft,     // gyro_x
            vehicleGyroForward,  // gyro_y
            vehicleGyroUp        // gyro_z
        )
    } else {
        floatArrayOf(
            accelX, accelY, accelZ,
            gyroX, gyroY, gyroZ
        )
    }
}
