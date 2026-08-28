package com.percorsa.navigation

class SensorTimingTracker {

    private var previousTimestampNs = 0L

    private var intervalCount = 0L
    private var totalIntervalNs = 0L

    private var minIntervalNs = Long.MAX_VALUE
    private var maxIntervalNs = 0L

    var averageIntervalMs: Float = 0f
        private set

    var minimumIntervalMs: Float = 0f
        private set

    var maximumIntervalMs: Float = 0f
        private set

    var rateHz: Float = 0f
        private set

    fun update(timestampNs: Long) {

        if (previousTimestampNs != 0L) {

            val intervalNs =
                timestampNs - previousTimestampNs

            // Ignore invalid timestamps.
            if (intervalNs > 0L) {

                intervalCount++
                totalIntervalNs += intervalNs

                if (intervalNs < minIntervalNs) {
                    minIntervalNs = intervalNs
                }

                if (intervalNs > maxIntervalNs) {
                    maxIntervalNs = intervalNs
                }

                averageIntervalMs =
                    totalIntervalNs.toFloat() /
                            intervalCount /
                            1_000_000f

                minimumIntervalMs =
                    minIntervalNs.toFloat() /
                            1_000_000f

                maximumIntervalMs =
                    maxIntervalNs.toFloat() /
                            1_000_000f

                if (averageIntervalMs > 0f) {
                    rateHz = 1000f / averageIntervalMs
                }
            }
        }

        previousTimestampNs = timestampNs
    }

    fun reset() {
        previousTimestampNs = 0L

        intervalCount = 0L
        totalIntervalNs = 0L

        minIntervalNs = Long.MAX_VALUE
        maxIntervalNs = 0L

        averageIntervalMs = 0f
        minimumIntervalMs = 0f
        maximumIntervalMs = 0f
        rateHz = 0f
    }
}