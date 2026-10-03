package com.percorsa.sensorlogger

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class DeviceAzimuthAndSpeedPolicyTest {

    private fun rotationMatrixFromEuler(yawDeg: Double, pitchDeg: Double, rollDeg: Double): FloatArray {
        val y = Math.toRadians(yawDeg)
        val p = Math.toRadians(pitchDeg)
        val r = Math.toRadians(rollDeg)

        // R = Rz(yaw) * Rx(pitch) * Ry(roll)
        // Phone in ENU world frame
        val cy = cos(y).toFloat()
        val sy = sin(y).toFloat()
        val cp = cos(p).toFloat()
        val sp = sin(p).toFloat()
        val cr = cos(r).toFloat()
        val sr = sin(r).toFloat()

        return floatArrayOf(
            cy * cr + sy * sp * sr,  sr * cp,  -sy * cr + cy * sp * sr,
            -sy * cr + cy * sp * sr, cr * cp,  cy * cr + sy * sp * sr,
            sp,                      -cp * sr,  cp * cr
        )
    }

    private fun flatYawMatrix(yawDeg: Double): FloatArray {
        // Flat phone: roll = 0, pitch = 0.
        // In Android ENU: top (+Y) points North at yaw=0, East at yaw=90, South at yaw=180, West at yaw=270.
        // Azimuth is clockwise from North.
        // If yaw=0 (North): top (+Y) = [0, 1, 0], right (+X) = [1, 0, 0], screen (+Z) = [0, 0, 1]
        // If yaw=90 (East): top (+Y) = [1, 0, 0], right (+X) = [0, -1, 0], screen (+Z) = [0, 0, 1]
        val psi = Math.toRadians(yawDeg)
        val c = cos(psi).toFloat()
        val s = sin(psi).toFloat()
        // Column 0 = +X (East when yaw=0, South when yaw=90)
        // Column 1 = +Y (North when yaw=0, East when yaw=90)
        // Column 2 = +Z (Up)
        return floatArrayOf(
            c,  s, 0f,  // row 0: East component of X, Y, Z
           -s,  c, 0f,  // row 1: North component of X, Y, Z
            0f, 0f, 1f  // row 2: Up component of X, Y, Z
        )
    }

    private fun uprightDockMatrix(azimuthDeg: Double, pitchDeg: Double = 80.0): FloatArray {
        // Phone mounted upright in a car dock:
        // Pitched up by pitchDeg (e.g. 80 deg) around phone's lateral axis.
        // Top of phone points towards the roof; back of phone points towards the road/windshield.
        val psi = Math.toRadians(azimuthDeg)
        val theta = Math.toRadians(pitchDeg)
        val cPsi = cos(psi).toFloat()
        val sPsi = sin(psi).toFloat()
        val cTh = cos(theta).toFloat()
        val sTh = sin(theta).toFloat()

        // Natural phone axes:
        // +X: right
        // +Y: top of screen (points up and slightly forward if pitch < 90)
        // +Z: screen normal (faces driver, points back and slightly down)
        // -Z: back normal (faces road/windshield)
        // In world ENU:
        // East component (row 0):
        // X: cos(psi)
        // Y: sin(psi) * cos(theta)
        // Z: -sin(psi) * sin(theta) => so -Z is sin(psi)*sin(theta)
        // North component (row 1):
        // X: -sin(psi)
        // Y: cos(psi) * cos(theta)
        // Z: -cos(psi) * sin(theta) => so -Z is cos(psi)*sin(theta)
        // Up component (row 2):
        // X: 0
        // Y: sin(theta)
        // Z: cos(theta)
        return floatArrayOf(
             cPsi,  sPsi * cTh, -sPsi * sTh,
            -sPsi,  cPsi * cTh, -cPsi * sTh,
             0f,    sTh,         cTh
        )
    }

    // ── 1. Device Azimuth Across 8 Compass Headings (Flat Mount) ─────────────

    @Test
    fun computeDeviceAzimuth_flatMount_all8CompassHeadings() {
        val sensorEngine = SensorEngine(null)
        val headings = listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)

        for (targetHeading in headings) {
            val r = flatYawMatrix(targetHeading)
            val azimuth = sensorEngine.computeDeviceAzimuth(r, Surface.ROTATION_0)
            assertEquals("Azimuth mismatch for $targetHeading°", targetHeading, azimuth.toDouble(), 0.5)
        }
    }

    // ── 2. Device Azimuth Across 4 Display Rotations ──────────────────────────

    @Test
    fun computeDeviceAzimuth_displayRotations_remapCorrectly() {
        val sensorEngine = SensorEngine(null)
        // Physical phone pointing North (azimuth = 0)
        // In ROTATION_0, visual top is natural +Y.
        val rNorth = flatYawMatrix(0.0)
        val az0 = sensorEngine.computeDeviceAzimuth(rNorth, Surface.ROTATION_0)
        assertEquals(0.0, az0.toDouble(), 0.5)

        // In ROTATION_90 (landscape, turned counter-clockwise 90° so right edge +X is top of screen),
        // if user holds the phone sideways such that visual top points North:
        // Natural +X is pointing South, natural -X is pointing North.
        // Let's create matrix where -X points North:
        // X points South (-1 North), Y points East (+1 East)
        val rLandscapeNorth = floatArrayOf(
            0f, 1f, 0f,
           -1f, 0f, 0f,
            0f, 0f, 1f
        )
        val azLandscape = sensorEngine.computeDeviceAzimuth(rLandscapeNorth, Surface.ROTATION_90)
        assertEquals(0.0, azLandscape.toDouble(), 0.5)
    }

    // ── 3. Device Azimuth on Steep Car-Dock Mounts (Upright 80° - 90°) ────────

    @Test
    fun computeDeviceAzimuth_uprightCarDock_continuousWithoutGimbalLock() {
        val sensorEngine = SensorEngine(null)
        val headings = listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)

        for (pitch in listOf(45.0, 75.0, 80.0, 88.0)) {
            for (h in headings) {
                val r = uprightDockMatrix(h, pitch)
                val azimuth = sensorEngine.computeDeviceAzimuth(r, Surface.ROTATION_0)
                assertEquals("Failed at pitch $pitch° heading $h°", h, azimuth.toDouble(), 1.0)
            }
        }
    }

    // ── 4. Speed Source Selection Policy ─────────────────────────────────────

    @Test
    fun speedSourcePolicy_trustedGnss_prefersGnssOverEskf() {
        // When GNSS is healthy (accuracy <= 12m, good speed), displayed speed must be GNSS speed
        val gpsSpeed = 10.0f // 36 km/h
        val eskfDivergedSpeed = 40.0f // 144 km/h (diverged)

        val hasTrustedGnss = true
        val (speed, source) = if (hasTrustedGnss && gpsSpeed.isFinite() && gpsSpeed >= 0f) {
            Pair(gpsSpeed, SpeedSource.GNSS)
        } else {
            Pair(eskfDivergedSpeed, SpeedSource.ESKF)
        }

        assertEquals(SpeedSource.GNSS, source)
        assertEquals(10.0f, speed, 0.001f)
    }

    @Test
    fun speedSourcePolicy_gnssOutage_usesEskfOnlyWhenHealthy() {
        val hasTrustedGnss = false
        val eskfSpeed = 12.5f

        // Healthy ESKF
        val healthyState = EskfHealthState.HEALTHY
        val (speedHealthy, sourceHealthy) = when {
            hasTrustedGnss -> Pair(0f, SpeedSource.GNSS)
            healthyState == EskfHealthState.HEALTHY -> Pair(eskfSpeed, SpeedSource.ESKF)
            else -> Pair(0f, SpeedSource.FALLBACK)
        }
        assertEquals(SpeedSource.ESKF, sourceHealthy)
        assertEquals(12.5f, speedHealthy, 0.001f)

        // Diverged ESKF
        val divergedState = EskfHealthState.DIVERGED
        val (speedDiverged, sourceDiverged) = when {
            hasTrustedGnss -> Pair(0f, SpeedSource.GNSS)
            divergedState == EskfHealthState.HEALTHY -> Pair(eskfSpeed, SpeedSource.ESKF)
            else -> Pair(0f, SpeedSource.FALLBACK)
        }
        assertEquals(SpeedSource.FALLBACK, sourceDiverged)
        assertEquals(0f, speedDiverged, 0.001f)
    }

    // ── 5. Tilt Leveling Stationary Stability ─────────────────────────────────

    @Test
    fun tiltLeveling_uprightStationaryPhone_doesNotDiverge() {
        val provider = PercorsaEskfProvider()
        // Initialize upright in car holder (pointing North)
        val rUpright = uprightDockMatrix(0.0, 80.0)
        provider.initialize(
            positionWorldEnu = DoubleArray(3),
            velocityWorldEnu = DoubleArray(3)
        )

        // Accelerometer in upright phone dock:
        // +Y is ~9.65 m/s² (tilted back 80°), -Z is ~1.70 m/s²
        val ay = (9.81 * sin(Math.toRadians(80.0))).toFloat()
        val az = (9.81 * cos(Math.toRadians(80.0))).toFloat()

        // 100 IMU ticks (1 second) with linear acceleration available
        for (i in 0 until 100) {
            val snap = makeSnapshot(
                timestampNs = (i + 1) * 10_000_000L,
                ay = ay,
                az = az
            )
            provider.update(snap, 0.01)
        }

        val estPos = provider.getEstimatedPosition()
        val speed = estPos?.speedMps ?: Float.NaN
        // Speed must remain 0.0 m/s (not diverging to 145 km/h)
        assertTrue("Speed diverged: $speed", speed < 0.1f)
    }

    private fun makeSnapshot(timestampNs: Long, ay: Float, az: Float) = SensorSnapshot(
        timestampNs = timestampNs,
        hasAccel = true, hasGyro = true, hasRotVector = true, hasLinearAccel = true,
        hasGravity = true, hasMag = false, hasGps = false,
        latitude = 0.0, longitude = 0.0, altitude = 0.0,
        gpsSpeedMps = 0f, gpsBearingDeg = 0f, gpsAccuracyM = 5f, compassBearingDeg = 0f,
        deviceAzimuthDeg = 0f,
        accelX = 0f, accelY = ay, accelZ = az, accelMag = 9.81f,
        gyroX = 0f, gyroY = 0f, gyroZ = 0f, gyroMag = 0f,
        quatW = 1f, quatX = 0f, quatY = 0f, quatZ = 0f, quatNorm = 1f,
        linearAccelX = 0f, linearAccelY = 0f, linearAccelZ = 0f, linearAccelMag = 0f,
        gravityX = 0f, gravityY = ay, gravityZ = az, gravityMag = 9.81f,
        magX = 0f, magY = 0f, magZ = 0f, isCalibrated = true,
        correctedAccelForward = 0f, correctedAccelLeft = 0f, correctedAccelUp = 0f, correctedAccelMag = 0f,
        correctedLinearForward = 0f, correctedLinearLeft = 0f, correctedLinearUp = 0f, correctedLinearMag = 0f,
        correctedGyroForward = 0f, correctedGyroLeft = 0f, correctedGyroUp = 0f, correctedGyroMag = 0f,
        imuHz = 100f, rawCallbackHz = 100f, totalCallbacks = 1, gpsFixAgeMs = -1L,
        tcnBufferCount = 0, tcnBufferCapacity = 50, tcnWindowSeconds = 5f,
        tcnBufferReady = false, tcnInferenceActive = false, tcnModelLoaded = false, tcnInferenceInFlight = false,
        tcnRawSpeedMps = Float.NaN, tcnPredictedSpeedMps = Float.NaN, tcnInferenceAgeMs = -1L,
        tcnInferenceLatencyMs = 0f, tcnPredictionRateLimited = false, tcnRejectedPredictionCount = 0L,
        tcnInferenceError = null, lastCanonicalSample = null,
        minDtMs = 0f, maxDtMs = 0f, avgDtMs = 0f, dtJitterMs = 0f, loggedCsvRows = 0L,
        duplicateTimestampsCount = 0L, nonMonotonicTimestampsCount = 0L, largeGapCount = 0L,
        staleSensorCount = 0L, warnings = emptyList()
    )
}
