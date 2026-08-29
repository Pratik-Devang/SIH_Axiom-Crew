package com.percorsa.sensorlogger

import kotlin.math.abs

data class FilteredTcnSpeed(
    val rawSpeedMps: Float,
    val speedMps: Float,
    val rateLimited: Boolean
)

/** Causal output filter that limits isolated TCN speed jumps before navigation. */
class TcnSpeedFilter(
    private val smoothingAlpha: Float = DEFAULT_SMOOTHING_ALPHA,
    private val maxAccelerationMps2: Float = DEFAULT_MAX_ACCELERATION_MPS2
) {
    private var filteredSpeedMps: Float? = null
    private var lastTimestampNs: Long = 0L

    init {
        require(smoothingAlpha in 0f..1f) { "Smoothing alpha must be in [0, 1]" }
        require(maxAccelerationMps2 > 0f) { "Maximum acceleration must be positive" }
    }

    fun update(rawSpeedMps: Float, timestampNs: Long): FilteredTcnSpeed {
        require(rawSpeedMps.isFinite() && rawSpeedMps >= 0f) {
            "TCN speed must be finite and non-negative"
        }

        val previous = filteredSpeedMps
        if (previous == null || lastTimestampNs <= 0L || timestampNs <= lastTimestampNs) {
            filteredSpeedMps = rawSpeedMps
            lastTimestampNs = timestampNs
            return FilteredTcnSpeed(rawSpeedMps, rawSpeedMps, false)
        }

        val dtSeconds = ((timestampNs - lastTimestampNs) / 1_000_000_000f)
            .coerceIn(MIN_DT_SECONDS, MAX_DT_SECONDS)
        val maxDelta = maxAccelerationMps2 * dtSeconds
        val rateLimitedSpeed = rawSpeedMps.coerceIn(previous - maxDelta, previous + maxDelta)
            .coerceAtLeast(0f)
        val filtered = previous + smoothingAlpha * (rateLimitedSpeed - previous)

        filteredSpeedMps = filtered
        lastTimestampNs = timestampNs
        return FilteredTcnSpeed(
            rawSpeedMps = rawSpeedMps,
            speedMps = filtered,
            rateLimited = abs(rateLimitedSpeed - rawSpeedMps) > 1e-4f
        )
    }

    fun reset() {
        filteredSpeedMps = null
        lastTimestampNs = 0L
    }

    companion object {
        // At 10 Hz these values allow the filtered prediction to change by at
        // most 0.2 m/s per update. This blocks isolated spikes without making
        // ordinary acceleration take tens of seconds to reach navigation.
        const val DEFAULT_SMOOTHING_ALPHA = 0.25f
        const val DEFAULT_MAX_ACCELERATION_MPS2 = 8f
        private const val MIN_DT_SECONDS = 0.05f
        private const val MAX_DT_SECONDS = 0.5f
    }
}
