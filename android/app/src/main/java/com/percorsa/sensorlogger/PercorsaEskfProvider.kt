package com.percorsa.sensorlogger

import android.util.Log

/**
 * Future slot for the real Percorsa ESKF + TCN navigation engine.
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * THIS CLASS IS A STUB. IT DOES NOT YET DO ANYTHING.
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * When this is implemented, it will run:
 *
 *   SensorEngine (phone → vehicle transform already applied)
 *       ↓
 *   INS mechanization (proper coning + sculling corrections)
 *       ↓
 *   Error-State Kalman Filter (ESKF) propagation
 *       ↓
 *   GNSS measurement update (when GnssQuality ≥ FAIR)
 *       ↓
 *   TCN speed measurement update (loaded from ONNX model)
 *       ↓
 *   Vehicle constraints (non-holonomic: lateral/vertical velocity ≈ 0)
 *       ↓
 *   ZUPT (zero-velocity update when stationary)
 *       ↓
 *   Map matching (route-constrained position projection)
 *       ↓
 *   DrPosition
 *
 * Integration steps required to activate this provider:
 * 1. Port the Python ESKF from /src/navigation/eskf.py to Kotlin
 * 2. Port the vehicle constraints from /src/navigation/constraints.py
 * 3. Consume the already available TCN speed through injectSpeedEstimate()
 * 4. In NavigationController, replace:
 *       val drEngine: DeadReckoningProvider = SimplifiedInsProvider()
 *    with:
 *       val drEngine: DeadReckoningProvider = PercorsaEskfProvider(context)
 *    No other changes required.
 *
 * Python source references:
 * - /src/navigation/eskf.py        — ESKF propagation and update steps
 * - /src/navigation/types.py       — NavState, ImuMeasurement definitions
 * - /src/navigation/constraints.py — vehicle constraint updates
 * - /src/navigation/ai_update.py   — TCN speed measurement injection
 */
class PercorsaEskfProvider : DeadReckoningProvider {

    override val providerType: DrProviderType = DrProviderType.PERCORSA_ESKF

    init {
        Log.w("PercorsaESKF", "PercorsaEskfProvider is a stub — ESKF/TCN not yet ported.")
    }

    override fun update(snapshot: SensorSnapshot, dtSeconds: Double) {
        // TODO: implement ESKF propagation + TCN speed update
        Log.w("PercorsaESKF", "update() called but ESKF not yet implemented")
    }

    override fun injectGnssCorrection(
        lat: Double,
        lon: Double,
        accuracyM: Float,
        speedMps: Float,
        bearingDeg: Float,
        blendWindowSeconds: Double
    ) {
        // TODO: implement ESKF GNSS measurement update
        Log.w("PercorsaESKF", "injectGnssCorrection() called but ESKF not yet implemented")
    }

    override fun getEstimatedPosition(): DrPosition? {
        // Not yet available
        return null
    }

    override fun injectSpeedEstimate(speedMps: Float) {
        Log.w("PercorsaESKF", "TCN speed received but ESKF measurement update is not yet implemented")
    }

    override fun reset() {
        // TODO: reset ESKF state vector and covariance
    }
}
