package com.percorsa.sensorlogger

/**
 * Resamples raw 200 Hz IMU callbacks into a synchronized 10 Hz canonical stream
 * suitable for downstream TCN model input matching Python preprocessing specs.
 */
class ImuPreprocessor {

    private val TARGET_INTERVAL_NS = 100_000_000L // 100 ms = 10 Hz
    private var lastSampleTimeNs: Long = 0L

    /**
     * Feed the latest SensorSnapshot.
     * @return A new [CanonicalImuSample] if a 10 Hz boundary has elapsed, or null.
     */
    fun processSnapshot(snapshot: SensorSnapshot): CanonicalImuSample? {
        val sampleTimeNs = snapshot.timestampNs
        if (sampleTimeNs <= 0L) return null
        if (lastSampleTimeNs > 0L && sampleTimeNs <= lastSampleTimeNs) return null

        if (lastSampleTimeNs == 0L || (sampleTimeNs - lastSampleTimeNs) >= TARGET_INTERVAL_NS) {
            lastSampleTimeNs = sampleTimeNs
            return CanonicalImuSample(
                timestampNs = sampleTimeNs,
                // Raw phone-frame values (preserved for logging/debugging)
                accelX = snapshot.accelX,
                accelY = snapshot.accelY,
                accelZ = snapshot.accelZ,
                gyroX = snapshot.gyroX,
                gyroY = snapshot.gyroY,
                gyroZ = snapshot.gyroZ,
                linearAccelX = snapshot.linearAccelX,
                linearAccelY = snapshot.linearAccelY,
                linearAccelZ = snapshot.linearAccelZ,
                // Vehicle-benchmark-frame raw accel (gravity included, Z-Up ~+9.81 stationary).
                // correctedAccel* = raw accel rotated by R_v_p (not linear accel).
                vehicleAccelForward = snapshot.correctedAccelForward,
                vehicleAccelLeft = snapshot.correctedAccelLeft,
                vehicleAccelUp = snapshot.correctedAccelUp,
                // Vehicle-benchmark-frame gyro
                vehicleGyroLeft = snapshot.correctedGyroLeft,
                vehicleGyroForward = snapshot.correctedGyroForward,
                vehicleGyroUp = snapshot.correctedGyroUp,
                vehicleFrameCalibrated = snapshot.isCalibrated
            )
        }
        return null
    }

    fun reset() {
        lastSampleTimeNs = 0L
    }
}
