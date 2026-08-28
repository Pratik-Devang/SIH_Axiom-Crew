package com.percorsa.sensorlogger

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * TEMPORARY simplified INS-based dead-reckoning provider.
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * THIS IS A PLACEHOLDER IMPLEMENTATION — NOT THE PERCORSA ALGORITHM.
 * Replace with [PercorsaEskfProvider] once the TCN + ESKF stack is
 * ported from Python to Android/Kotlin.
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * What this does (simplified):
 * - Extracts heading from the rotation vector quaternion (same as SensorEngine computes)
 * - Takes forward linear acceleration from the vehicle-calibrated frame (correctedLinearForward)
 * - Integrates velocity forward using dt
 * - Converts velocity → lat/lon displacement using spherical Earth geometry
 * - Applies a basic ZUPT: if |linear forward accel| < 0.3 m/s² for 0.5s, clamp velocity to 0
 *
 * Known limitations (acceptable for now):
 * - No gyro integration for heading refinement
 * - No magnetometer correction
 * - Position error accumulates over time without GNSS corrections
 * - No full covariance propagation (that's the ESKF's job)
 *
 * Smooth GNSS reacquisition:
 * - When injectGnssCorrection() is called, positions are linearly interpolated
 *   from the DR estimate to the GNSS fix over [blendWindowSeconds] seconds
 * - The marker never teleports
 */
class SimplifiedInsProvider : DeadReckoningProvider {

    override val providerType: DrProviderType = DrProviderType.SIMPLIFIED_INS

    // ── Integration state ──────────────────────────────────────────────────────
    private var estimatedLat: Double = 0.0
    private var estimatedLon: Double = 0.0
    private var headingDeg: Float = 0f
    private var velocityMps: Float = 0f
    private var initialized: Boolean = false

    // Estimated accuracy degrades over time without GNSS
    private var estimatedAccuracyM: Float = 5f
    private val DR_ACCURACY_GROWTH_RATE = 2f  // metres per second of DR

    // ── ZUPT (Zero-Velocity Update) state ──────────────────────────────────────
    private var stationaryAccumSeconds: Double = 0.0
    private val ZUPT_ACCEL_THRESHOLD = 0.6f   // m/s² — aligned with deadband
    private val ZUPT_WINDOW_SECONDS = 0.3     // require 0.3s of low accel before clamping

    // ── GNSS blend state ──────────────────────────────────────────────────────
    private var gnssTargetLat: Double = 0.0
    private var gnssTargetLon: Double = 0.0
    private var blendWindowSeconds: Double = 0.0
    private var blendElapsedSeconds: Double = 0.0
    private var blendActive: Boolean = false
    private var blendStartLat: Double = 0.0
    private var blendStartLon: Double = 0.0

    // ── Earth geometry ─────────────────────────────────────────────────────────
    private val EARTH_RADIUS_M = 6_371_000.0

    // ── Public interface ───────────────────────────────────────────────────────

    override fun update(snapshot: SensorSnapshot, dtSeconds: Double) {
        if (!initialized) return

        // 1. Heading from rotation vector (quaternion → yaw)
        val q0 = snapshot.quatW.toDouble()
        val q1 = snapshot.quatX.toDouble()
        val q2 = snapshot.quatY.toDouble()
        val q3 = snapshot.quatZ.toDouble()
        val yawRad = atan2(2.0 * (q0 * q3 + q1 * q2), 1.0 - 2.0 * (q2 * q2 + q3 * q3))
        headingDeg = Math.toDegrees(yawRad).toFloat().let {
            if (it < 0f) it + 360f else it
        }

        // 2. Forward acceleration from calibrated vehicle frame with deadband & low-pass filtering
        val rawForwardAccel = if (snapshot.isCalibrated)
            snapshot.correctedLinearForward
        else
            snapshot.linearAccelX   // fallback before calibration

        // Deadband filter: ignore small accelerations and hand tremor (< 0.6 m/s²)
        val deadbandAccel = if (abs(rawForwardAccel) < 0.6f) 0f else rawForwardAccel

        // 3. ZUPT & Shake Detection — detect stationary or rapid hand movement
        val accelMag = sqrt(
            snapshot.linearAccelX * snapshot.linearAccelX +
            snapshot.linearAccelY * snapshot.linearAccelY +
            snapshot.linearAccelZ * snapshot.linearAccelZ
        )
        val gyroMag = snapshot.gyroMag

        // Rapid gyro changes or high 3D accel variance indicates hand shaking, not vehicle motion
        val isHandShaking = gyroMag > 2.5f || (accelMag > 6.0f && snapshot.gpsSpeedMps < 1.0f)

        if (accelMag < ZUPT_ACCEL_THRESHOLD || isHandShaking) {
            stationaryAccumSeconds += dtSeconds
        } else {
            stationaryAccumSeconds = 0.0
        }
        val isStationary = stationaryAccumSeconds >= ZUPT_WINDOW_SECONDS

        // 4. Velocity integration with velocity damping decay to prevent stationary drift
        if (isStationary || (snapshot.hasGps && snapshot.gpsSpeedMps < 0.3f && snapshot.gpsAccuracyM < 15f)) {
            velocityMps = 0f
        } else if (!isHandShaking) {
            if (deadbandAccel != 0f) {
                velocityMps = (velocityMps + deadbandAccel * dtSeconds.toFloat()).coerceIn(0f, 40f)
            } else {
                // Exponential velocity decay when acceleration is within deadband (friction damping)
                velocityMps = (velocityMps * 0.90f).let { if (it < 0.05f) 0f else it }
            }
        }

        // 5. Position integration
        val distM = velocityMps * dtSeconds.toFloat()
        val headingRad = Math.toRadians(headingDeg.toDouble())
        val dLat = (distM * cos(headingRad)) / EARTH_RADIUS_M
        val dLon = (distM * sin(headingRad)) / (EARTH_RADIUS_M * cos(Math.toRadians(estimatedLat)))
        estimatedLat += Math.toDegrees(dLat)
        estimatedLon += Math.toDegrees(dLon)

        // 6. Accuracy degradation during DR
        estimatedAccuracyM += (DR_ACCURACY_GROWTH_RATE * dtSeconds).toFloat()

        // 7. Advance GNSS blend
        if (blendActive) {
            blendElapsedSeconds += dtSeconds
            val t = (blendElapsedSeconds / blendWindowSeconds).coerceIn(0.0, 1.0)
            // Smooth ease-in-out blend: 3t² - 2t³
            val smoothT = t * t * (3.0 - 2.0 * t)
            estimatedLat = blendStartLat + smoothT * (gnssTargetLat - blendStartLat)
            estimatedLon = blendStartLon + smoothT * (gnssTargetLon - blendStartLon)
            if (t >= 1.0) blendActive = false
        }
    }

    override fun injectGnssCorrection(
        lat: Double,
        lon: Double,
        accuracyM: Float,
        speedMps: Float,
        bearingDeg: Float,
        blendWindowSeconds: Double
    ) {
        if (!initialized) {
            // First fix — initialise directly with no blend
            estimatedLat = lat
            estimatedLon = lon
            headingDeg = bearingDeg
            velocityMps = speedMps
            estimatedAccuracyM = accuracyM
            initialized = true
            return
        }

        // GNSS return after outage — smooth blend rather than teleport
        blendStartLat = estimatedLat
        blendStartLon = estimatedLon
        gnssTargetLat = lat
        gnssTargetLon = lon
        this.blendWindowSeconds = blendWindowSeconds
        blendElapsedSeconds = 0.0
        blendActive = true

        // Reset accuracy to GNSS accuracy
        estimatedAccuracyM = accuracyM

        // Update speed from GNSS if moving
        if (speedMps > 0.5f) velocityMps = speedMps
        if (bearingDeg > 0f) headingDeg = bearingDeg
    }

    override fun getEstimatedPosition(): DrPosition? {
        if (!initialized) return null
        val blendFactor = if (blendActive && blendWindowSeconds > 0)
            (blendElapsedSeconds / blendWindowSeconds).toFloat().coerceIn(0f, 1f)
        else 1f
        return DrPosition(
            latitude = estimatedLat,
            longitude = estimatedLon,
            heading = headingDeg,
            speedMps = velocityMps,
            estimatedAccuracyM = estimatedAccuracyM,
            gnssBlendFactor = blendFactor
        )
    }

    override fun reset() {
        estimatedLat = 0.0
        estimatedLon = 0.0
        headingDeg = 0f
        velocityMps = 0f
        estimatedAccuracyM = 5f
        initialized = false
        stationaryAccumSeconds = 0.0
        blendActive = false
        blendElapsedSeconds = 0.0
    }
}
