package com.percorsa.sensorlogger

/**
 * Classifies GPS/GNSS quality from a [SensorSnapshot].
 *
 * Uses accuracy, fix age, and GPS speed consistency to produce a stable
 * [GnssQuality] enum that NavigationController uses to decide:
 * - Whether to trust GNSS for navigation
 * - Whether to fall back to dead-reckoning
 * - How to label GNSS status in the UI
 */
class GnssQualityMonitor {

    /** How long (ms) without a new GPS fix before declaring DENIED. */
    private val GNSS_TIMEOUT_MS = 5_000L

    /** Accuracy thresholds in metres. */
    private val ACCURACY_GOOD_M = 10f
    private val ACCURACY_FAIR_M = 30f

    // ── State ─────────────────────────────────────────────────────────────────
    private var lastFixTimeMs: Long = 0L
    private var lastAccuracyM: Float = Float.MAX_VALUE
    private var _currentQuality: GnssQuality = GnssQuality.DENIED

    val currentQuality: GnssQuality get() = _currentQuality

    /**
     * Feed the latest sensor snapshot.
     * Called on every UI update cycle (~10 Hz from MainActivity's handler).
     *
     * @return Updated [GnssQuality]
     */
    fun update(snapshot: SensorSnapshot): GnssQuality {
        val nowMs = System.currentTimeMillis()

        if (!snapshot.hasGps || snapshot.latitude == 0.0) {
            // No fix at all
            if (nowMs - lastFixTimeMs > GNSS_TIMEOUT_MS) {
                _currentQuality = GnssQuality.DENIED
            }
            return _currentQuality
        }

        // We have a fix — record it
        lastFixTimeMs = nowMs
        lastAccuracyM = snapshot.gpsAccuracyM

        _currentQuality = when {
            snapshot.gpsAccuracyM <= ACCURACY_GOOD_M -> GnssQuality.GOOD
            snapshot.gpsAccuracyM <= ACCURACY_FAIR_M -> GnssQuality.FAIR
            else                                      -> GnssQuality.POOR
        }

        return _currentQuality
    }

    /**
     * Returns true when GNSS measurements should be used to update navigation state.
     * Conservative: only GOOD or FAIR quality is trusted.
     */
    fun shouldUseMeasurement(): Boolean =
        _currentQuality == GnssQuality.GOOD || _currentQuality == GnssQuality.FAIR

    /** Returns true when GNSS has been lost long enough to activate dead-reckoning. */
    fun isGnssDenied(): Boolean = _currentQuality == GnssQuality.DENIED

    /** Returns seconds since last valid GNSS fix. */
    fun secondsSinceLastFix(): Double =
        (System.currentTimeMillis() - lastFixTimeMs) / 1000.0

    fun reset() {
        lastFixTimeMs = 0L
        lastAccuracyM = Float.MAX_VALUE
        _currentQuality = GnssQuality.DENIED
    }
}
