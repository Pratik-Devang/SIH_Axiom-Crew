package com.percorsa.sensorlogger

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class InsDiagnostics(
    val timestampNs: Long = 0L,
    val dtSeconds: Double = 0.0,
    val correctedAccelForward: Float = 0f,
    val correctedAccelLeft: Float = 0f,
    val correctedAccelUp: Float = 0f,
    val velocityBeforeMps: Float = 0f,
    val velocityAfterMps: Float = 0f,
    val tcnSpeedMps: Float = Float.NaN,
    val tcnSpeedInjected: Boolean = false,
    val vehicleMotionObserved: Boolean = false,
    val vehicleMotionEvidence: String = "WAITING: GNSS speed ≥ 4.0 m/s and accuracy ≤ 15 m",
    val positionBefore: LatLon = LatLon(0.0, 0.0),
    val positionAfter: LatLon = LatLon(0.0, 0.0)
)

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
    private var headingInitialized: Boolean = false
    @Volatile private var velocityMps: Float = 0f
    private var initialized: Boolean = false
    private var lastSensorTimestampNs: Long = 0L
    private var filteredForwardAccel = 0f
    private var pendingTcnSpeedMps: Float? = null
    private var vehicleMotionObserved = false
    @Volatile private var lastDiagnostics = InsDiagnostics()

    val diagnostics: InsDiagnostics get() = lastDiagnostics

    /** Diagnostic-only read of the provider's current internal velocity. */
    val diagnosticSpeedMps: Float get() = velocityMps

    override val acceptsTcnSpeedEstimate: Boolean
        get() = vehicleMotionObserved

    override fun injectSpeedEstimate(speedMps: Float) {
        if (acceptsTcnSpeedEstimate && speedMps.isFinite() && speedMps >= 0f) {
            pendingTcnSpeedMps = speedMps
        } else {
            pendingTcnSpeedMps = null
        }
    }

    // Estimated accuracy degrades over time without GNSS
    private var estimatedAccuracyM: Float = 5f
    private val DR_ACCURACY_GROWTH_RATE = 2f  // metres per second of DR

    // ── ZUPT (Zero-Velocity Update) state ──────────────────────────────────────
    private var stationaryAccumSeconds: Double = 0.0
    private val ZUPT_ACCEL_THRESHOLD = 0.6f   // m/s² — aligned with deadband
    private val ZUPT_WINDOW_SECONDS = 0.3     // require 0.3s of low accel before clamping

    // Vehicle dynamics limits. These reject phone handling artifacts without
    // clamping normal road acceleration or the displayed speed.
    private val MAX_VALID_DT_SECONDS = 0.25
    private val MAX_FORWARD_ACCEL_MPS2 = 12f
    private val MAX_FORWARD_JERK_MPS3 = 35f

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

        val positionBefore = LatLon(estimatedLat, estimatedLon)
        val velocityBefore = velocityMps

        val wallDt = if (dtSeconds.isFinite() && dtSeconds in 0.005..MAX_VALID_DT_SECONDS)
            dtSeconds else return
        val sampleDt = if (snapshot.timestampNs > 0L && lastSensorTimestampNs > 0L) {
            val deltaNs = snapshot.timestampNs - lastSensorTimestampNs
            if (deltaNs <= 0L || deltaNs > (MAX_VALID_DT_SECONDS * 1_000_000_000L).toLong()) {
                lastSensorTimestampNs = snapshot.timestampNs
                return
            }
            deltaNs / 1_000_000_000.0
        } else {
            wallDt
        }
        if (snapshot.timestampNs > 0L) lastSensorTimestampNs = snapshot.timestampNs

        // GNSS course initializes the world heading. During an outage, yaw
        // changes come from the calibrated vehicle-frame gyro, not compass yaw.
        if (!headingInitialized && snapshot.compassBearingDeg.isFinite()) {
            headingDeg = snapshot.compassBearingDeg
            headingInitialized = true
        }
        if (snapshot.isCalibrated && snapshot.correctedGyroUp.isFinite()) {
            headingDeg = normalizeHeading(headingDeg + Math.toDegrees(
                snapshot.correctedGyroUp.toDouble() * sampleDt
            ).toFloat())
        }

        // Without calibration there is no defensible phone->vehicle forward
        // axis, so do not integrate an arbitrary phone X axis.
        val rawForwardAccel = if (snapshot.isCalibrated) snapshot.correctedLinearForward else 0f

        val accelIsFinite = rawForwardAccel.isFinite()
        val jerkLimit = MAX_FORWARD_JERK_MPS3 * sampleDt.toFloat()
        val isAccelOutlier = !accelIsFinite ||
                abs(rawForwardAccel) > MAX_FORWARD_ACCEL_MPS2 ||
                abs(rawForwardAccel - filteredForwardAccel) > jerkLimit
        val validatedAccel = if (isAccelOutlier) 0f else rawForwardAccel
        filteredForwardAccel = if (isAccelOutlier) {
            filteredForwardAccel * 0.8f
        } else {
            0.35f * validatedAccel + 0.65f * filteredForwardAccel
        }
        val deadbandAccel = if (abs(filteredForwardAccel) < ZUPT_ACCEL_THRESHOLD) 0f else filteredForwardAccel

        // 3. ZUPT & Shake Detection — detect stationary or rapid hand movement
        val accelMag = sqrt(
            snapshot.linearAccelX * snapshot.linearAccelX +
            snapshot.linearAccelY * snapshot.linearAccelY +
            snapshot.linearAccelZ * snapshot.linearAccelZ
        )
        val gyroMag = snapshot.gyroMag

        // Rapid gyro changes or high 3D accel variance indicates hand shaking, not vehicle motion
        val isMotionArtifact = accelMag > MAX_FORWARD_ACCEL_MPS2 ||
                (gyroMag > 4.0f && snapshot.linearAccelMag > 4.0f)

        val lowMotion = snapshot.linearAccelMag < 0.35f && gyroMag < 0.15f
        if (lowMotion || (snapshot.hasGps && snapshot.gpsSpeedMps < 0.3f && snapshot.gpsAccuracyM < 15f)) {
            stationaryAccumSeconds += sampleDt
        } else {
            stationaryAccumSeconds = 0.0
        }
        val isStationary = stationaryAccumSeconds >= ZUPT_WINDOW_SECONDS

        // 4. Velocity integration with artifact rejection and ZUPT.
        var tcnSpeedInjected = false
        var injectedTcnSpeed = Float.NaN
        if (isStationary) {
            velocityMps = 0f
            pendingTcnSpeedMps = null
        } else if (!isMotionArtifact) {
            if (deadbandAccel != 0f) {
                velocityMps = (velocityMps + deadbandAccel * sampleDt.toFloat()).coerceIn(0f, 40f)
            } else {
                // Exponential velocity decay when acceleration is within deadband (friction damping)
                velocityMps = (velocityMps * 0.90f).let { if (it < 0.05f) 0f else it }
            }
            pendingTcnSpeedMps?.let { tcnSpeed ->
                velocityMps = (0.75f * velocityMps + 0.25f * tcnSpeed).coerceIn(0f, 40f)
                injectedTcnSpeed = tcnSpeed
                tcnSpeedInjected = true
            }
            pendingTcnSpeedMps = null
        }

        // 5. Position integration
        val distM = velocityMps * sampleDt.toFloat()
        val headingRad = Math.toRadians(headingDeg.toDouble())
        val dLat = (distM * cos(headingRad)) / EARTH_RADIUS_M
        val dLon = (distM * sin(headingRad)) / (EARTH_RADIUS_M * cos(Math.toRadians(estimatedLat)))
        estimatedLat += Math.toDegrees(dLat)
        estimatedLon += Math.toDegrees(dLon)

        // 6. Accuracy degradation during DR
        estimatedAccuracyM += (DR_ACCURACY_GROWTH_RATE * sampleDt).toFloat()

        // 7. Advance GNSS blend
        if (blendActive) {
            blendElapsedSeconds += sampleDt
            val t = (blendElapsedSeconds / blendWindowSeconds).coerceIn(0.0, 1.0)
            // Smooth ease-in-out blend: 3t² - 2t³
            val smoothT = t * t * (3.0 - 2.0 * t)
            estimatedLat = blendStartLat + smoothT * (gnssTargetLat - blendStartLat)
            estimatedLon = blendStartLon + smoothT * (gnssTargetLon - blendStartLon)
            if (t >= 1.0) blendActive = false
        }

        lastDiagnostics = InsDiagnostics(
            timestampNs = snapshot.timestampNs,
            dtSeconds = sampleDt,
            correctedAccelForward = snapshot.correctedLinearForward,
            correctedAccelLeft = snapshot.correctedLinearLeft,
            correctedAccelUp = snapshot.correctedLinearUp,
            velocityBeforeMps = velocityBefore,
            velocityAfterMps = velocityMps,
            tcnSpeedMps = injectedTcnSpeed,
            tcnSpeedInjected = tcnSpeedInjected,
            vehicleMotionObserved = vehicleMotionObserved,
            vehicleMotionEvidence = if (vehicleMotionObserved)
                "OBSERVED: GNSS speed ≥ 4.0 m/s and accuracy ≤ 15 m"
            else
                "WAITING: GNSS speed ≥ 4.0 m/s and accuracy ≤ 15 m",
            positionBefore = positionBefore,
            positionAfter = LatLon(estimatedLat, estimatedLon)
        )
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
            vehicleMotionObserved = speedMps >= VEHICLE_MOTION_THRESHOLD_MPS &&
                    accuracyM <= VEHICLE_EVIDENCE_MAX_ACCURACY_M
            headingInitialized = bearingDeg.isFinite() && speedMps > 0.5f
            return
        }

        // GNSS return after outage — smooth blend rather than teleport
        blendStartLat = estimatedLat
        blendStartLon = estimatedLon
        gnssTargetLat = lat
        gnssTargetLon = lon
        this.blendWindowSeconds = blendWindowSeconds.coerceAtLeast(0.0)
        blendElapsedSeconds = 0.0
        blendActive = this.blendWindowSeconds > 0.0

        // Reset accuracy to GNSS accuracy
        estimatedAccuracyM = accuracyM

        // Update speed from GNSS if moving
        if (speedMps > 0.5f && speedMps.isFinite()) velocityMps = speedMps.coerceIn(0f, 40f)
        if (speedMps >= VEHICLE_MOTION_THRESHOLD_MPS && accuracyM <= VEHICLE_EVIDENCE_MAX_ACCURACY_M) {
            vehicleMotionObserved = true
        }
        if (bearingDeg.isFinite() && speedMps > 0.5f) {
            headingDeg = normalizeHeading(bearingDeg)
            headingInitialized = true
        }
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
        headingInitialized = false
        velocityMps = 0f
        estimatedAccuracyM = 5f
        initialized = false
        stationaryAccumSeconds = 0.0
        blendActive = false
        blendElapsedSeconds = 0.0
        lastSensorTimestampNs = 0L
        filteredForwardAccel = 0f
        pendingTcnSpeedMps = null
        vehicleMotionObserved = false
    }

    private fun normalizeHeading(value: Float): Float {
        val normalized = value % 360f
        return if (normalized < 0f) normalized + 360f else normalized
    }

    companion object {
        private const val VEHICLE_MOTION_THRESHOLD_MPS = 4.0f
        private const val VEHICLE_EVIDENCE_MAX_ACCURACY_M = 15f
    }
}
