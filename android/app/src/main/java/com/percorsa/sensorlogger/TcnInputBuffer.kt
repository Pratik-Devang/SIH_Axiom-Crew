package com.percorsa.sensorlogger

/**
 * Sliding window for the deployed TCN contract: 50 canonical samples at 10 Hz
 * (5 seconds), ordered as `[batch, channel, time] = [1, 6, 50]`.
 */
class TcnInputBuffer(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "TCN buffer capacity must be positive" }
    }

    private val samples = ArrayDeque<CanonicalImuSample>(capacity)
    private var lastSampleTimestampNs: Long = 0L

    val size: Int get() = samples.size
    val isReady: Boolean get() = samples.size >= capacity
    val windowSeconds: Float get() = capacity.toFloat() / SAMPLE_RATE_HZ
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

    /** Extract a channel-major `[6][time]` matrix for ONNX input `[1, 6, 50]`. */
    fun getFeatureMatrix(): Array<FloatArray> {
        synchronized(this) {
            val rows = samples.map { it.toFeatureArray() }
            return Array(FEATURE_COUNT) { channel ->
                FloatArray(rows.size) { time -> rows[time][channel] }
            }
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

    companion object {
        const val SAMPLE_RATE_HZ = 10
        const val FEATURE_COUNT = 6
        const val DEFAULT_CAPACITY = 50
        const val DEFAULT_WINDOW_SECONDS = 5
    }
}
