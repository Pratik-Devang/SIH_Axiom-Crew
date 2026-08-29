package com.percorsa.sensorlogger

/**
 * Dead-reckoning provider interface.
 *
 * Defines the contract that all DR/navigation engines must implement.
 * The application architecture is designed so that NavigationController
 * and MainActivity depend ONLY on this interface — never on a specific
 * implementation.
 *
 * Provider hierarchy:
 *   DeadReckoningProvider
 *       ├── SimplifiedInsProvider   — temporary fallback (linear accel integration)
 *       └── PercorsaEskfProvider    — future slot for real TCN + ESKF engine
 *
 * To replace SimplifiedInsProvider with the real Percorsa algorithm:
 *   1. Complete PercorsaEskfProvider (port the ESKF from Python)
 *   2. In NavigationController, change:
 *        drProvider = SimplifiedInsProvider()
 *      to:
 *        drProvider = PercorsaEskfProvider()
 *   No changes required in MainActivity, NavigationState, or UI layer.
 */
interface DeadReckoningProvider {

    /** Current DR type identifier — used for honest UI labelling. */
    val providerType: DrProviderType

    /**
     * Feed a new sensor snapshot into the DR engine.
     *
     * Called at every sensor update cycle (~100ms from the UI handler).
     * The implementation must:
     * - Use [snapshot].correctedLinear* (vehicle-frame linear acceleration) for forward integration
     * - Use [snapshot].quatW/X/Y/Z (rotation vector) for heading
     * - Optionally apply ZUPT (zero-velocity update) if detecting stationary state
     *
     * @param snapshot  Latest sensor snapshot from [SensorEngine]
     * @param dtSeconds Time delta since last call, in seconds
     */
    fun update(snapshot: SensorSnapshot, dtSeconds: Double)

    /** Inject a trusted TCN forward-speed estimate when GNSS is unavailable. */
    fun injectSpeedEstimate(speedMps: Float)

    /**
     * Inject a confirmed GNSS position to reset or correct the DR estimate.
     *
     * Called by [NavigationController] whenever a trusted GNSS fix arrives.
     * Implementations must:
     * - Accept the correction without jumping the published position
     * - Blend smoothly toward the corrected position over [blendWindowSeconds]
     *
     * @param lat              Confirmed latitude
     * @param lon              Confirmed longitude
     * @param accuracyM        GPS accuracy radius in metres
     * @param speedMps         GPS speed in m/s
     * @param bearingDeg       GPS bearing in degrees
     * @param blendWindowSeconds  Seconds over which to blend toward the correction (0 = immediate)
     */
    fun injectGnssCorrection(
        lat: Double,
        lon: Double,
        accuracyM: Float,
        speedMps: Float,
        bearingDeg: Float,
        blendWindowSeconds: Double = 3.0
    )

    /**
     * Current estimated position.
     * Returns null if the engine has not yet received enough data to produce an estimate.
     */
    fun getEstimatedPosition(): DrPosition?

    /** Reset all integration state. Call when navigation is stopped or restarted. */
    fun reset()
}

/**
 * Output of a dead-reckoning engine.
 */
data class DrPosition(
    val latitude: Double,
    val longitude: Double,
    val heading: Float,
    val speedMps: Float,
    /** Estimated accuracy in metres. Increases over time without GNSS corrections. */
    val estimatedAccuracyM: Float,
    /** Blend factor 0..1: 0 = fully DR, 1 = fully GNSS-corrected. */
    val gnssBlendFactor: Float = 0f
)
