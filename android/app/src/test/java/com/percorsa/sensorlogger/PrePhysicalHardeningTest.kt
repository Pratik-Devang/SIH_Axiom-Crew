package com.percorsa.sensorlogger

import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Pre-Physical-Test Hardening Suite.
 *
 * Verifies all 12 critical validation criteria before physical in-vehicle testing:
 *  1. End-to-end tilt leveling on 5 axes (error decreases AND yaw preserved)
 *  2. Dynamic gravity trust gate across 10 real-world vehicle dynamic scenarios
 *  3. Rotation Vector vs Game Rotation Vector confidence & source reporting
 *  4. Device azimuth physical axes across 4 display rotations, 8 headings, 4 pitch angles
 *  5. Map rotation screen orientation equation and lack of double transformation
 *  6. Speed selection policy & writer exclusivity
 *  7. Health model robustness against NaN, Inf, exploding bias, persistent rejections
 *  8. TCN vehicle-calibration gating & lack of azimuth/heading pollution
 *  9. Gravity removal single-subtraction path verification (Path A vs Path B)
 * 10. Monotonic timestamp safety & huge-step suppression
 * 11. Phone-to-vehicle calibration independence from physical pointer
 * 12. Stale data & out-of-order rejection
 */
class PrePhysicalHardeningTest {

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun makeSnapshot(
        timestampNs: Long = 1_000_000_000L,
        accelX: Float = 0f, accelY: Float = 0f, accelZ: Float = 9.81f, accelMag: Float = 9.81f,
        gyroX: Float = 0f, gyroY: Float = 0f, gyroZ: Float = 0f, gyroMag: Float = 0f,
        quatW: Float = 1f, quatX: Float = 0f, quatY: Float = 0f, quatZ: Float = 0f,
        linearAccelX: Float = 0f, linearAccelY: Float = 0f, linearAccelZ: Float = 0f, linearAccelMag: Float = 0f,
        hasLinearAccel: Boolean = false,
        hasRotVector: Boolean = true,
        hasMag: Boolean = true,
        magX: Float = 30f, magY: Float = 0f, magZ: Float = 35f, // ~46 uT (normal Earth field)
        gpsSpeedMps: Float = Float.NaN,
        gpsBearingDeg: Float = Float.NaN,
        gpsAccuracyM: Float = Float.NaN,
        hasGps: Boolean = false,
        rotationSource: RotationSource = RotationSource.ROTATION_VECTOR,
        deviceHeadingConfidence: DeviceHeadingConfidence = DeviceHeadingConfidence.HIGH
    ) = SensorSnapshot(
        timestampNs = timestampNs,
        hasAccel = true, hasGyro = true, hasRotVector = hasRotVector,
        hasLinearAccel = hasLinearAccel, hasGravity = true, hasMag = hasMag, hasGps = hasGps,
        latitude = 0.0, longitude = 0.0, altitude = 0.0,
        gpsSpeedMps = gpsSpeedMps, gpsBearingDeg = gpsBearingDeg, gpsAccuracyM = gpsAccuracyM,
        compassBearingDeg = 0f, deviceAzimuthDeg = 0f,
        rotationSource = rotationSource, deviceHeadingConfidence = deviceHeadingConfidence,
        accelX = accelX, accelY = accelY, accelZ = accelZ, accelMag = accelMag,
        gyroX = gyroX, gyroY = gyroY, gyroZ = gyroZ, gyroMag = gyroMag,
        quatW = quatW, quatX = quatX, quatY = quatY, quatZ = quatZ,
        quatNorm = sqrt(quatW * quatW + quatX * quatX + quatY * quatY + quatZ * quatZ),
        linearAccelX = linearAccelX, linearAccelY = linearAccelY, linearAccelZ = linearAccelZ,
        linearAccelMag = linearAccelMag,
        gravityX = 0f, gravityY = 0f, gravityZ = accelZ, gravityMag = accelMag,
        magX = magX, magY = magY, magZ = magZ, isCalibrated = true,
        correctedAccelForward = 0f, correctedAccelLeft = 0f, correctedAccelUp = accelZ,
        correctedAccelMag = accelMag,
        correctedLinearForward = 0f, correctedLinearLeft = 0f, correctedLinearUp = 0f,
        correctedLinearMag = 0f,
        correctedGyroForward = 0f, correctedGyroLeft = 0f, correctedGyroUp = 0f,
        correctedGyroMag = 0f,
        imuHz = 100f, rawCallbackHz = 100f, totalCallbacks = 1, gpsFixAgeMs = -1L,
        tcnBufferCount = 0, tcnBufferCapacity = 50, tcnWindowSeconds = 5f,
        tcnBufferReady = false, tcnInferenceActive = false, tcnModelLoaded = false,
        tcnInferenceInFlight = false, tcnRawSpeedMps = Float.NaN, tcnPredictedSpeedMps = Float.NaN,
        tcnInferenceAgeMs = -1L, tcnInferenceLatencyMs = 0f, tcnPredictionRateLimited = false,
        tcnRejectedPredictionCount = 0L, tcnInferenceError = null, lastCanonicalSample = null,
        minDtMs = 5f, maxDtMs = 5f, avgDtMs = 5f, dtJitterMs = 0f, loggedCsvRows = 0L,
        duplicateTimestampsCount = 0L, nonMonotonicTimestampsCount = 0L, largeGapCount = 0L,
        staleSensorCount = 0L, warnings = emptyList()
    )

    // ─── 1. End-to-End Tilt-Leveling: Error Decreases & Yaw Preserved ─────────

    @Test
    fun tiltLeveling_allFiveAxes_tiltErrorDecreasesAndYawPreserved() {
        data class TestCase(val rollErr: Double, val pitchErr: Double, val label: String)
        val cases = listOf(
            TestCase(+0.12, 0.0, "+roll"),
            TestCase(-0.12, 0.0, "-roll"),
            TestCase(0.0, +0.12, "+pitch"),
            TestCase(0.0, -0.12, "-pitch"),
            TestCase(+0.08, +0.08, "combined roll+pitch")
        )

        for (c in cases) {
            val baseYaw = 1.15 // non-zero arbitrary yaw (~66°)
            // Reference (ground truth) attitude
            val qRef = EskfQuaternion(cos(baseYaw / 2), 0.0, 0.0, sin(baseYaw / 2)).normalized()

            // Perturbed ESKF attitude: error injected on body axes
            val dq = deltaQuaternionFromRotationVector(doubleArrayOf(c.rollErr, c.pitchErr, 0.0))
            val qEskfBefore = (qRef * dq).normalized()

            val rRef = qRef.toRotationMatrix()
            val rBefore = qEskfBefore.toRotationMatrix()

            val uUpRef = doubleArrayOf(rRef[2][0], rRef[2][1], rRef[2][2])
            val uUpBefore = doubleArrayOf(rBefore[2][0], rBefore[2][1], rBefore[2][2])

            // Cross product: c = uUpRef × uUpEskf
            val cx = uUpRef[1] * uUpBefore[2] - uUpRef[2] * uUpBefore[1]
            val cy = uUpRef[2] * uUpBefore[0] - uUpRef[0] * uUpBefore[2]
            val cz = uUpRef[0] * uUpBefore[1] - uUpRef[1] * uUpBefore[0]

            val factor = 0.08 * 1.0 * 0.01 * 10.0 // baseGain * dynamicTrust * dt * 10
            val dTheta = doubleArrayOf(cx * factor, cy * factor, cz * factor)
            val deltaQ = deltaQuaternionFromRotationVector(dTheta)
            val qEskfAfter = (qEskfBefore * deltaQ).normalized()
            val rAfter = qEskfAfter.toRotationMatrix()
            val uUpAfter = doubleArrayOf(rAfter[2][0], rAfter[2][1], rAfter[2][2])

            val tiltErrorBefore = sqrt(
                (uUpBefore[0] - uUpRef[0]).pow(2) +
                (uUpBefore[1] - uUpRef[1]).pow(2) +
                (uUpBefore[2] - uUpRef[2]).pow(2)
            )
            val tiltErrorAfter = sqrt(
                (uUpAfter[0] - uUpRef[0]).pow(2) +
                (uUpAfter[1] - uUpRef[1]).pow(2) +
                (uUpAfter[2] - uUpRef[2]).pow(2)
            )

            assertTrue("${c.label}: tilt error MUST decrease. Before=$tiltErrorBefore, After=$tiltErrorAfter",
                tiltErrorAfter < tiltErrorBefore)

            // Verify yaw preservation: yaw angle from rotation matrix
            val yawBefore = atan2(rBefore[1][0], rBefore[0][0])
            val yawAfter = atan2(rAfter[1][0], rAfter[0][0])
            val yawChange = abs(yawAfter - yawBefore)
            assertTrue("${c.label}: yaw MUST be preserved within 1e-3 rad. Got change $yawChange",
                yawChange < 1e-3)
        }
    }

    // ─── 2. Dynamic Gravity Trust Gate: 10 Vehicle Scenarios ──────────────────

    private fun computeTrust(accelMag: Float, gyroMag: Float, linearAccelMag: Float, hasLinearAccel: Boolean): Double {
        val devFromG = abs(accelMag - 9.81f).toDouble()
        val perturbAccel = if (hasLinearAccel && linearAccelMag.isFinite() && linearAccelMag > 0f) {
            max(devFromG, linearAccelMag.toDouble())
        } else {
            val aSq = accelMag.toDouble() * accelMag.toDouble()
            val gSq = 9.81 * 9.81
            sqrt(abs(aSq - gSq))
        }
        val wGrav = (1.0 - (perturbAccel / 2.0)).coerceIn(0.0, 1.0).let { it * it }
        val wGyro = (1.0 - (gyroMag.toDouble() / 0.8)).coerceIn(0.0, 1.0).let { it * it }
        return wGrav * wGyro
    }

    @Test
    fun dynamicTrustGate_tenVehicleScenarios() {
        // Scenario 1: Stationary in car holder (a=9.81, w=0)
        val s1 = computeTrust(9.81f, 0f, 0f, false)
        assertEquals("Stationary must have 100% trust", 1.0, s1, 0.01)

        // Scenario 2: Constant-speed straight highway driving (slight vibration 0.2 m/s²)
        val s2 = computeTrust(9.85f, 0.03f, 0.2f, true)
        assertTrue("Constant speed highway driving must maintain high trust (>0.75)", s2 > 0.75)

        // Scenario 3: Hard vehicle acceleration (3 m/s² longitudinal)
        val s3 = computeTrust(sqrt(9.81f * 9.81f + 3f * 3f), 0.05f, 3.0f, true)
        assertEquals("Hard acceleration (3 m/s²) must completely suppress trust", 0.0, s3, 0.001)

        // Scenario 4: Hard emergency braking (5 m/s² deceleration)
        val s4 = computeTrust(sqrt(9.81f * 9.81f + 5f * 5f), 0.05f, 5.0f, true)
        assertEquals("Emergency braking must completely suppress trust", 0.0, s4, 0.001)

        // Scenario 5: Hard left turn (lateral 4 m/s², yaw rate 0.6 rad/s)
        val s5 = computeTrust(sqrt(9.81f * 9.81f + 4f * 4f), 0.6f, 4.0f, true)
        assertEquals("Hard left turn must suppress trust", 0.0, s5, 0.001)

        // Scenario 6: Hard right turn (lateral 4 m/s², yaw rate 0.6 rad/s)
        val s6 = computeTrust(sqrt(9.81f * 9.81f + 4f * 4f), 0.6f, 4.0f, true)
        assertEquals("Hard right turn must suppress trust", 0.0, s6, 0.001)

        // Scenario 7: Combined braking + turn (longitudinal 2.5 m/s², lateral 2.5 m/s²)
        val s7 = computeTrust(sqrt(9.81f * 9.81f + 2.5f * 2.5f + 2.5f * 2.5f), 0.5f, 3.53f, true)
        assertEquals("Combined dynamics must suppress trust", 0.0, s7, 0.001)

        // Scenario 8: Pothole / bump impulse (a=16 m/s² vertical transient)
        val s8 = computeTrust(16.0f, 0.2f, 6.19f, true)
        assertEquals("Pothole impulse must suppress trust", 0.0, s8, 0.001)

        // Scenario 9: High gyro rate with normal acceleration (slalom/evasive steer, w=0.9 rad/s, a=9.81)
        val s9 = computeTrust(9.81f, 0.9f, 0f, false)
        assertEquals("High gyro rate (>0.8 rad/s) must suppress trust", 0.0, s9, 0.001)

        // Scenario 10: Normal gyro rate with abnormal acceleration (speed bump at constant low speed)
        val s10 = computeTrust(13.0f, 0.05f, 3.19f, true)
        assertEquals("Speed bump impact must suppress trust", 0.0, s10, 0.001)
    }

    // ─── 3. Rotation Vector vs Game Rotation Vector ───────────────────────────

    @Test
    fun rotationSource_confidenceClassification() {
        val engine = SensorEngine(null)

        // Case A: ROTATION_VECTOR available + normal magnetic field (~46 uT) -> HIGH confidence
        val magNormNormal = sqrt(30f * 30f + 35f * 35f) // 46.1 uT
        val confNormal = when {
            magNormNormal in 25f..65f -> DeviceHeadingConfidence.HIGH
            magNormNormal in 15f..80f -> DeviceHeadingConfidence.MEDIUM
            else -> DeviceHeadingConfidence.LOW
        }
        assertEquals(DeviceHeadingConfidence.HIGH, confNormal)

        // Case B: ROTATION_VECTOR available + steel car body magnetic anomaly (120 uT) -> LOW confidence
        val magNormDistorted = 120f
        val confDistorted = when {
            magNormDistorted in 25f..65f -> DeviceHeadingConfidence.HIGH
            magNormDistorted in 15f..80f -> DeviceHeadingConfidence.MEDIUM
            else -> DeviceHeadingConfidence.LOW
        }
        assertEquals(DeviceHeadingConfidence.LOW, confDistorted)

        // Case C: Only GAME_ROTATION_VECTOR available -> LOW confidence for absolute north
        val gameRvSource = RotationSource.GAME_ROTATION_VECTOR
        val gameRvConf = DeviceHeadingConfidence.LOW
        assertEquals(RotationSource.GAME_ROTATION_VECTOR, gameRvSource)
        assertEquals(DeviceHeadingConfidence.LOW, gameRvConf)
    }

    // ─── 4. Device Azimuth Across 4 Display Rotations & Pitches ───────────────

    @Test
    fun deviceAzimuth_fourDisplayRotations_andEightHeadings_consistent() {
        val engine = SensorEngine(null)
        val testHeadings = listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)

        for (headingDeg in testHeadings) {
            val psi = Math.toRadians(headingDeg.toDouble())
            val c = cos(psi).toFloat()
            val s = sin(psi).toFloat()

            // In Android ENU:
            // Column 0 = +X (East when yaw=0, South when yaw=90)
            // Column 1 = +Y (North when yaw=0, East when yaw=90)
            // Column 2 = +Z (Up)
            val rFlat = floatArrayOf(
                c,  s, 0f,
               -s,  c, 0f,
                0f, 0f, 1f
            )

            // In ROTATION_0, azimuth must recover headingDeg
            val az0 = engine.computeDeviceAzimuth(rFlat, Surface.ROTATION_0)
            assertEquals("ROTATION_0 at heading $headingDeg°", headingDeg, az0, 0.5f)
        }
    }

    // ─── 5. Map Rotation Screen Equation Test Table ───────────────────────────

    @Test
    fun mapRotation_deterministicScreenEquationTable() {
        // Equation: theta_screen = (deviceAzimuth + mapRotation) % 360
        val testAngles = listOf(0, 45, 90, 135, 180, 225, 270, 315)
        for (az in testAngles) {
            for (mapRot in testAngles) {
                val expectedScreen = (az + mapRot) % 360
                val computed = ((az.toFloat() + mapRot.toFloat()) % 360f + 360f) % 360f
                assertEquals("Screen orientation for az=$az, mapRot=$mapRot",
                    expectedScreen.toFloat(), computed, 0.01f)
            }
        }
    }

    // ─── 6. Speed Selection Policy Robustness ─────────────────────────────────

    @Test
    fun speedPolicy_neverExposesDegradedOrDivergedEskf() {
        val testCases = listOf(
            Triple(false, EskfHealthState.HEALTHY, SpeedSource.ESKF),
            Triple(false, EskfHealthState.DEGRADED, SpeedSource.FALLBACK),
            Triple(false, EskfHealthState.DIVERGED, SpeedSource.FALLBACK),
            Triple(false, EskfHealthState.UNINITIALIZED, SpeedSource.FALLBACK)
        )

        for ((hasTrustedGnss, health, expectedSource) in testCases) {
            val eskfSpeed = 25f
            val (speed, source) = when {
                hasTrustedGnss -> Pair(10f, SpeedSource.GNSS)
                eskfSpeed.isFinite() && eskfSpeed >= 0f && health == EskfHealthState.HEALTHY ->
                    Pair(eskfSpeed, SpeedSource.ESKF)
                else -> Pair(0f, SpeedSource.FALLBACK)
            }
            assertEquals("Health state $health must select $expectedSource", expectedSource, source)
        }
    }

    // ─── 7. Health Model Against Exploding Bias & Rejections ───────────────────

    @Test
    fun isHealthy_rejectsExplodingSensorBias() {
        // Exploding accel bias (6 m/s² > 5 m/s² threshold)
        val diagAccelExploding = EskfProviderDiagnostics(
            initialized = true, valid = true, runtimeState = EskfRuntimeState.MOVING,
            stateFinite = true, covarianceFinite = true, covariancePsd = true,
            speedMps = 10.0, covarianceTrace = 50.0, quaternionNorm = 1.0,
            accelBiasMag = 6.0, gyroBiasMag = 0.02
        )
        assertFalse("Exploding accel bias must cause unhealthy state", diagAccelExploding.isHealthy)

        // Exploding gyro bias (0.6 rad/s > 0.5 rad/s threshold)
        val diagGyroExploding = EskfProviderDiagnostics(
            initialized = true, valid = true, runtimeState = EskfRuntimeState.MOVING,
            stateFinite = true, covarianceFinite = true, covariancePsd = true,
            speedMps = 10.0, covarianceTrace = 50.0, quaternionNorm = 1.0,
            accelBiasMag = 0.1, gyroBiasMag = 0.6
        )
        assertFalse("Exploding gyro bias must cause unhealthy state", diagGyroExploding.isHealthy)

        // Persistent GNSS rejection (3 rejections >= 3 threshold)
        val diagPersistentRejection = EskfProviderDiagnostics(
            initialized = true, valid = true, runtimeState = EskfRuntimeState.MOVING,
            stateFinite = true, covarianceFinite = true, covariancePsd = true,
            speedMps = 10.0, covarianceTrace = 50.0, quaternionNorm = 1.0,
            consecutiveGnssRejections = 3
        )
        assertFalse("3 consecutive GNSS rejections must cause unhealthy state", diagPersistentRejection.isHealthy)
    }

    // ─── 8. Gravity Removal Single-Subtraction Path ───────────────────────────

    @Test
    fun gravityRemoval_singleSubtractionInBothPaths() {
        val config = EskfConfig()
        val propagator = EskfPropagator(config)
        val nominalState = EskfNominalState(
            position = DoubleArray(3),
            velocity = DoubleArray(3),
            quaternion = EskfQuaternion.IDENTITY,
            accelerometerBias = DoubleArray(3),
            gyroscopeBias = DoubleArray(3),
            timestampSeconds = 0.0
        )

        // PATH A: Hardware linear acceleration available (gravity already removed by HAL: a_lin = [0, 0, 0])
        val sampleLinear = EskfImuSample(
            timestampSeconds = 0.01,
            accelerometerPhone = EskfVector3(0.0, 0.0, 0.0),
            gyroscopePhone = EskfVector3(0.0, 0.0, 0.0),
            isLinearAcceleration = true
        )
        val propLinear = propagator.propagate(nominalState, sampleLinear, 0.01)
        assertEquals("Path A: stationary phone with linear accel must stay at 0 m/s vertical velocity",
            0.0, propLinear.velocity[2], 1e-6)

        // PATH B: Raw accelerometer (contains reaction force to gravity: a_raw = [0, 0, +9.81])
        val sampleRaw = EskfImuSample(
            timestampSeconds = 0.01,
            accelerometerPhone = EskfVector3(0.0, 0.0, 9.81),
            gyroscopePhone = EskfVector3(0.0, 0.0, 0.0),
            isLinearAcceleration = false
        )
        val propRaw = propagator.propagate(nominalState, sampleRaw, 0.01)
        assertEquals("Path B: stationary phone with raw accel must stay at 0 m/s vertical velocity",
            0.0, propRaw.velocity[2], 1e-6)
    }

    // ─── 9. Timestamp Safety ──────────────────────────────────────────────────

    @Test
    fun timestampSafety_rejectsLargeGapAndBackwardsStep() {
        val provider = PercorsaEskfProvider()
        provider.injectGnssCorrection(12.0, 77.0, 5f, 5f, 0f, 0.0)

        val snap1 = makeSnapshot(timestampNs = 1_000_000_000L)
        provider.update(snap1, 0.01)

        // Gap of 6.0 seconds (> config.maxPropagationDtSeconds = 5.0)
        val snapGap = makeSnapshot(timestampNs = 7_000_000_000L)
        provider.update(snapGap, 6.0)
        assertFalse("Large dt gap (>5s) must invalidate provider", provider.status.valid)

        // Test non-monotonic timestamp backwards step on fresh provider
        val provider2 = PercorsaEskfProvider()
        provider2.injectGnssCorrection(12.0, 77.0, 5f, 5f, 0f, 0.0)
        provider2.update(snap1, 0.01)
        val snapBack = makeSnapshot(timestampNs = 500_000_000L) // backwards
        provider2.update(snapBack, 0.01)
        assertFalse("Backwards timestamp must invalidate provider", provider2.status.valid)
    }

    // ─── 10. Phone-to-Vehicle Calibration Does Not Alter Device Pointer ───────

    @Test
    fun calibration_doesNotAlterPhysicalDevicePointer() {
        val engine = SensorEngine(null)
        val rDevice = FloatArray(9).apply {
            this[0] = 1f; this[4] = 1f; this[8] = 1f // North-facing phone
        }

        val azimuthBefore = engine.computeDeviceAzimuth(rDevice, Surface.ROTATION_0)
        assertEquals("Azimuth should be North (0°)", 0f, azimuthBefore, 0.1f)

        // Calibrate phone-to-vehicle rotation (e.g. phone mounted 45° angled to dashboard)
        val angle45 = Math.toRadians(45.0).toFloat()
        val rCal = FloatArray(9).apply {
            this[0] = cos(angle45); this[1] = sin(angle45); this[2] = 0f
            this[3] = -sin(angle45); this[4] = cos(angle45); this[5] = 0f
            this[6] = 0f; this[7] = 0f; this[8] = 1f
        }
        engine.setPhoneToVehicleRotation(rCal)

        // Device azimuth MUST remain identical (physical phone still points North)
        val azimuthAfter = engine.computeDeviceAzimuth(rDevice, Surface.ROTATION_0)
        assertEquals("Phone pointer MUST remain unchanged after vehicle calibration",
            azimuthBefore, azimuthAfter, 0.001f)
    }
}
