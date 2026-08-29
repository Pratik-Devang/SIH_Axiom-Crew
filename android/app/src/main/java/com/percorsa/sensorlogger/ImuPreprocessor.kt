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

        if (lastSampleTimeNs == 0L || (sampleTimeNs - lastSampleTimeNs) >= TARGET_INTERVAL_NS) {
            lastSampleTimeNs = sampleTimeNs
            return CanonicalImuSample(
                timestampNs = sampleTimeNs,
                accelX = snapshot.accelX,
                accelY = snapshot.accelY,
                accelZ = snapshot.accelZ,
                gyroX = snapshot.gyroX,
                gyroY = snapshot.gyroY,
                gyroZ = snapshot.gyroZ,
                linearAccelX = snapshot.linearAccelX,
                linearAccelY = snapshot.linearAccelY,
                linearAccelZ = snapshot.linearAccelZ,
                vehicleAccelForward = snapshot.correctedLinearForward,
                vehicleAccelLeft = snapshot.correctedLinearLeft,
                vehicleAccelUp = snapshot.correctedLinearUp
            )
        }
        return null
    }

    fun reset() {
        lastSampleTimeNs = 0L
    }
}
