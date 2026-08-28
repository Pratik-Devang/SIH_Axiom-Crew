package com.percorsa.sensorlogger

/**
 * Sliding window buffer maintaining exactly 20 canonical 10 Hz IMU samples (2 seconds).
 * Provides the [TcnInputProvider] contract for downstream ML speed inference on `feature/ml-speed-tcn`.
 */
class TcnInputBuffer(private val capacity: Int = 20) {

    private val samples = ArrayDeque<CanonicalImuSample>(capacity)
    private var lastSampleTimestampNs: Long = 0L

    val size: Int get() = samples.size
    val isReady: Boolean get() = samples.size >= capacity
    val lastUpdateAgeMs: Long
        get() = if (lastSampleTimestampNs > 0) (System.nanoTime() - lastSampleTimestampNs) / 1_000_000L else -1L

    /**
     * Push a new 10 Hz canonical sample into the sliding window.
     */
    fun push(sample: CanonicalImuSample) {
        synchronized(this) {
            if (samples.size >= capacity) {
                samples.removeFirst()
            }
            samples.addLast(sample)
            lastSampleTimestampNs = System.nanoTime()
        }
    }

    /**
     * Extract full 20x6 feature array for model input [shape: 1 x 6 x 20 or 20 x 6].
     */
    fun getFeatureMatrix(): Array<FloatArray> {
        synchronized(this) {
            return samples.map { it.toFeatureArray() }.toTypedArray()
        }
    }

    fun getSnapshot(): List<CanonicalImuSample> {
        synchronized(this) {
            return samples.toList()
        }
    }

    fun clear() {
        synchronized(this) {
            samples.clear()
            lastSampleTimestampNs = 0L
        }
    }
}
