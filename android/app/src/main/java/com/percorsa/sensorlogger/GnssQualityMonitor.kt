package com.percorsa.sensorlogger

/**
 * Classifies GPS/GNSS quality from a [SensorSnapshot].
 *
 * Uses horizontal accuracy, fix age (ms), and provider state to produce a stable,
 * truthful [GnssQuality] enum:
 * - GOOD: accuracy ≤ 10m, fix age < 3s
 * - FAIR: accuracy 10–30m, fix age < 5s
 * - POOR: accuracy > 30m
 * - STALE: fix age 3s – 5s
 * - DENIED: fix age > 5s or no fix
 * - RECOVERING: GNSS fix returned after outage, blending smoothly back
 */
class GnssQualityMonitor {

    private val GNSS_STALE_TIMEOUT_MS = 3_000L
    private val GNSS_DENIED_TIMEOUT_MS = 5_000L

    private val ACCURACY_GOOD_M = 10f
    private val ACCURACY_FAIR_M = 30f

    private var previousQuality: GnssQuality = GnssQuality.DENIED
    private var _currentQuality: GnssQuality = GnssQuality.DENIED
    private var recoveryStartMs: Long = 0L

    val currentQuality: GnssQuality get() = _currentQuality

    /**
     * Feed the latest sensor snapshot.
     * Called on every UI update cycle (~10 Hz from NavigationController).
     */
    fun update(snapshot: SensorSnapshot): GnssQuality {
        val fixAgeMs = snapshot.gpsFixAgeMs

        // 1. Check fix age timeouts (handles GPS turned OFF or satellite loss)
        if (fixAgeMs < 0 || fixAgeMs > GNSS_DENIED_TIMEOUT_MS || !snapshot.hasGps || snapshot.latitude == 0.0) {
            previousQuality = _currentQuality
            _currentQuality = GnssQuality.DENIED
            return _currentQuality
        }

        // 2. Handle GNSS Return & Smooth Recovery
        if (previousQuality == GnssQuality.DENIED) {
            _currentQuality = GnssQuality.RECOVERING
            recoveryStartMs = System.currentTimeMillis()
            previousQuality = GnssQuality.RECOVERING
            return _currentQuality
        }

        if (_currentQuality == GnssQuality.RECOVERING) {
            if (System.currentTimeMillis() - recoveryStartMs < 3000L) {
                return GnssQuality.RECOVERING
            }
        }

        // 3. Classify based on fix age and horizontal accuracy
        val newQuality = when {
            fixAgeMs > GNSS_STALE_TIMEOUT_MS -> GnssQuality.POOR
            snapshot.gpsAccuracyM <= ACCURACY_GOOD_M -> GnssQuality.GOOD
            snapshot.gpsAccuracyM <= ACCURACY_FAIR_M -> GnssQuality.FAIR
            else -> GnssQuality.POOR
        }

        previousQuality = _currentQuality
        _currentQuality = newQuality
        return _currentQuality
    }

    fun shouldUseMeasurement(): Boolean =
        _currentQuality == GnssQuality.GOOD || _currentQuality == GnssQuality.FAIR

    fun isGnssDenied(): Boolean = _currentQuality == GnssQuality.DENIED

    fun reset() {
        previousQuality = GnssQuality.DENIED
        _currentQuality = GnssQuality.DENIED
        recoveryStartMs = 0L
    }
}
