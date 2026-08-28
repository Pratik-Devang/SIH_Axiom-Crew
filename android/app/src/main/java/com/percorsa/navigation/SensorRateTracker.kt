package com.percorsa.navigation

class SensorRateTracker {

    private var lastTimestampNs = 0L
    private var sampleCount = 0

    private var startTimestampNs = 0L

    var rateHz: Float = 0f
        private set

    fun update(timestampNs: Long) {

        if (startTimestampNs == 0L) {
            startTimestampNs = timestampNs
        }

        sampleCount++

        if (lastTimestampNs != 0L) {
            val elapsedNs = timestampNs - startTimestampNs

            if (elapsedNs > 0L) {
                rateHz =
                    sampleCount.toFloat() /
                            (elapsedNs / 1_000_000_000f)
            }
        }

        lastTimestampNs = timestampNs
    }

    fun reset() {
        lastTimestampNs = 0L
        sampleCount = 0
        startTimestampNs = 0L
        rateHz = 0f
    }
}