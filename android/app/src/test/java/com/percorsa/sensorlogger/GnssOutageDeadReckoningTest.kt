package com.percorsa.sensorlogger

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Regression test suite for GNSS-denied dead reckoning transition.
 * Validates that GNSS outage alone does NOT cause the ESKF to be declared DEGRADED/unhealthy,
 * and ensures IMU dead reckoning continues properly when GNSS is lost.
 */
class GnssOutageDeadReckoningTest {

    private fun makeSnapshot(
        timestampNs: Long = 1_000_000_000L,
        accelX: Float = 0f,
        accelY: Float = 0f,
        accelZ: Float = 9.81f,
        gyroX: Float = 0f,
        gyroY: Float = 0f,
        gyroZ: Float = 0f,
        quatW: Float = 1f,
        quatX: Float = 0f,
        quatY: Float = 0f,
        quatZ: Float = 0f
    ) = SensorSnapshot(
        timestampNs = timestampNs,
        hasAccel = true, hasGyro = true, hasRotVector = true,
        hasLinearAccel = false, hasGravity = true, hasMag = false, hasGps = false,
        latitude = 0.0, longitude = 0.0, altitude = 0.0,
        gpsSpeedMps = 0f, gpsBearingDeg = 0f, gpsAccuracyM = 5f, compassBearingDeg = 0f,
        accelX = accelX, accelY = accelY, accelZ = accelZ,
        accelMag = sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ),
        gyroX = gyroX, gyroY = gyroY, gyroZ = gyroZ,
        gyroMag = sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ),
        quatW = quatW, quatX = quatX, quatY = quatY, quatZ = quatZ,
        quatNorm = sqrt(quatW * quatW + quatX * quatX + quatY * quatY + quatZ * quatZ),
        linearAccelX = 0f, linearAccelY = 0f, linearAccelZ = 0f, linearAccelMag = 0f,
        gravityX = 0f, gravityY = 0f, gravityZ = accelZ, gravityMag = 9.81f,
        magX = 0f, magY = 0f, magZ = 0f, isCalibrated = false,
        correctedAccelForward = 0f, correctedAccelLeft = 0f, correctedAccelUp = accelZ,
        correctedAccelMag = 9.81f,
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
        minDtMs = 10f, maxDtMs = 10f, avgDtMs = 10f, dtJitterMs = 0f, loggedCsvRows = 0L,
        duplicateTimestampsCount = 0L, nonMonotonicTimestampsCount = 0L, largeGapCount = 0L,
        staleSensorCount = 0L, warnings = emptyList()
    )

    // ─── TEST 1: ESKF stays HEALTHY after GNSS denied (no vehicle calibration) ─
    @Test
    fun test1_eskfStaysHealthyAfterGnssLossWithoutCalibration() {
        val provider = PercorsaEskfProvider()
        // Inject good GNSS fix (driving at 10 m/s north)
        provider.injectGnssCorrection(
            lat = 12.9716, lon = 77.5946,
            accuracyM = 5f, speedMps = 10f, bearingDeg = 0f,
            blendWindowSeconds = 1.0, sourceTimestampNs = 1_000_000_000L
        )

        // Propagate for 2 seconds of GNSS outage with nominal IMU data
        var t = 1_000_000_000L
        for (i in 1..200) {
            t += 10_000_000L // 10ms
            val snap = makeSnapshot(timestampNs = t, accelZ = 9.81f)
            provider.update(snap, 0.01)
        }

        val diag = provider.status
        assertTrue("ESKF must remain valid during GNSS outage", diag.valid)
        assertTrue("ESKF state must remain finite", diag.stateFinite)
        assertTrue("ESKF covariance must remain finite", diag.covarianceFinite)
        assertTrue("ESKF covariance must remain PSD", diag.covariancePsd)
        assertTrue("ESKF must be HEALTHY during GNSS outage even without calibration", diag.isHealthy)
        assertEquals("Health reason must be NONE", EskfHealthReason.NONE, diag.healthReason)
    }

    // ─── TEST 2: consecutiveGnssRejections does NOT increment during pure GNSS outage ─
    @Test
    fun test2_consecutiveGnssRejectionsDoesNotIncrementDuringOutage() {
        val provider = PercorsaEskfProvider()
        provider.injectGnssCorrection(
            lat = 12.9716, lon = 77.5946,
            accuracyM = 5f, speedMps = 8f, bearingDeg = 90f,
            blendWindowSeconds = 1.0, sourceTimestampNs = 1_000_000_000L
        )
        assertEquals(0, provider.status.consecutiveGnssRejections)

        // GNSS outage: IMU updates arrive, but injectGnssCorrection is NOT called
        var t = 1_000_000_000L
        for (i in 1..100) {
            t += 10_000_000L
            val snap = makeSnapshot(timestampNs = t, accelZ = 9.81f)
            provider.update(snap, 0.01)
        }

        assertEquals("GNSS rejections must remain 0 during outage", 0, provider.status.consecutiveGnssRejections)
        assertTrue("ESKF must remain healthy", provider.status.isHealthy)
    }

    // ─── TEST 3: IMU propagation continues after GNSS loss ─────────────────────
    @Test
    fun test3_imuPropagationContinuesAfterGnssLoss() {
        val provider = PercorsaEskfProvider()
        // Initialize at rest
        provider.injectGnssCorrection(
            lat = 12.9716, lon = 77.5946,
            accuracyM = 5f, speedMps = 0f, bearingDeg = 0f,
            blendWindowSeconds = 0.0, sourceTimestampNs = 1_000_000_000L
        )
        val initialSpeed = provider.status.speedMps
        assertEquals(0.0, initialSpeed, 0.01)

        // Vehicle accelerates forward: +2 m/s² forward (accelY in phone frame with identity attitude)
        var t = 1_000_000_000L
        for (i in 1..100) {
            t += 10_000_000L // 10ms * 100 = 1.0s
            // Forward accel along Y axis (3.5 m/s² to exceed stationary ZUPT noise threshold of 0.35 m/s²), 9.81 on Z (gravity)
            val snap = makeSnapshot(timestampNs = t, accelY = 3.5f, accelZ = 9.81f)
            provider.update(snap, 0.01)
        }

        val estimatedPos = provider.getEstimatedPosition()
        assertNotNull(estimatedPos)
        assertTrue("Speed must have increased after forward acceleration", provider.status.speedMps > 0.5)
        assertTrue("Speed estimate must be finite", estimatedPos!!.speedMps.isFinite())
    }

    // ─── TEST 4: Speed policy selects ESKF when HEALTHY and GNSS denied ────────
    @Test
    fun test4_speedPolicySelectsEskfWhenHealthyAndGnssDenied() {
        val hasTrustedGnss = false
        val gpsSpeedMps = 15f
        val drPosSpeedMps = 12f
        val eskfHealth = EskfHealthState.HEALTHY

        val (speed, speedSource) = when {
            hasTrustedGnss && gpsSpeedMps.isFinite() && gpsSpeedMps >= 0f -> {
                Pair(gpsSpeedMps, SpeedSource.GNSS)
            }
            drPosSpeedMps.isFinite() && drPosSpeedMps >= 0f && eskfHealth == EskfHealthState.HEALTHY -> {
                Pair(drPosSpeedMps, SpeedSource.ESKF)
            }
            else -> {
                val fallback = if (hasTrustedGnss && gpsSpeedMps.isFinite() && gpsSpeedMps >= 0f) gpsSpeedMps else 0f
                Pair(fallback, SpeedSource.FALLBACK)
            }
        }

        assertEquals(SpeedSource.ESKF, speedSource)
        assertEquals(12f, speed, 0.001f)
    }

    // ─── TEST 5: Speed policy falls back when ESKF is DIVERGED / INVALID ───────
    @Test
    fun test5_speedPolicyFallsBackWhenEskfDiverged() {
        val hasTrustedGnss = false
        val gpsSpeedMps = 15f
        val drPosSpeedMps = 100f
        val eskfHealth = EskfHealthState.DIVERGED

        val (speed, speedSource) = when {
            hasTrustedGnss && gpsSpeedMps.isFinite() && gpsSpeedMps >= 0f -> {
                Pair(gpsSpeedMps, SpeedSource.GNSS)
            }
            drPosSpeedMps.isFinite() && drPosSpeedMps >= 0f && eskfHealth == EskfHealthState.HEALTHY -> {
                Pair(drPosSpeedMps, SpeedSource.ESKF)
            }
            else -> {
                val fallback = if (hasTrustedGnss && gpsSpeedMps.isFinite() && gpsSpeedMps >= 0f) gpsSpeedMps else 0f
                Pair(fallback, SpeedSource.FALLBACK)
            }
        }

        assertEquals(SpeedSource.FALLBACK, speedSource)
        assertEquals(0f, speed, 0.001f)
    }

    // ─── TEST 6: EskfRuntimeState.DEGRADED does NOT set isHealthy=false ────────
    @Test
    fun test6_runtimeStateDegradedDoesNotSetIsHealthyFalse() {
        val diag = EskfProviderDiagnostics(
            initialized = true,
            valid = true,
            runtimeState = EskfRuntimeState.DEGRADED,
            degradationReason = "Vehicle-frame calibration required for TCN/NHC",
            stateFinite = true,
            covarianceFinite = true,
            covariancePsd = true,
            speedMps = 10.0,
            covarianceTrace = 100.0,
            quaternionNorm = 1.0,
            accelBiasMag = 0.1,
            gyroBiasMag = 0.01,
            consecutiveGnssRejections = 0
        )
        assertTrue("DEGRADED runtime state must still be isHealthy = true for dead reckoning", diag.isHealthy)
        assertEquals(EskfHealthReason.NONE, diag.healthReason)
    }

    // ─── TEST 7: Accel bias explosion → UNHEALTHY (threshold preserved) ────────
    @Test
    fun test7_accelBiasExplosionSetsUnhealthy() {
        val diag = EskfProviderDiagnostics(
            initialized = true,
            valid = true,
            runtimeState = EskfRuntimeState.MOVING,
            stateFinite = true,
            covarianceFinite = true,
            covariancePsd = true,
            speedMps = 10.0,
            covarianceTrace = 100.0,
            quaternionNorm = 1.0,
            accelBiasMag = 5.5, // >= 5.0 m/s²
            gyroBiasMag = 0.01,
            consecutiveGnssRejections = 0
        )
        assertFalse("Accel bias >= 5.0 must cause isHealthy = false", diag.isHealthy)
        assertTrue("Health reasons must contain ACCEL_BIAS_EXPLODING",
            diag.healthReasons.contains(EskfHealthReason.ACCEL_BIAS_EXPLODING))
        assertEquals(EskfHealthReason.ACCEL_BIAS_EXPLODING, diag.healthReason)
    }

    // ─── TEST 8: Gyro bias explosion → UNHEALTHY (threshold preserved) ─────────
    @Test
    fun test8_gyroBiasExplosionSetsUnhealthy() {
        val diag = EskfProviderDiagnostics(
            initialized = true,
            valid = true,
            runtimeState = EskfRuntimeState.MOVING,
            stateFinite = true,
            covarianceFinite = true,
            covariancePsd = true,
            speedMps = 10.0,
            covarianceTrace = 100.0,
            quaternionNorm = 1.0,
            accelBiasMag = 0.1,
            gyroBiasMag = 0.6, // >= 0.5 rad/s
            consecutiveGnssRejections = 0
        )
        assertFalse("Gyro bias >= 0.5 must cause isHealthy = false", diag.isHealthy)
        assertTrue("Health reasons must contain GYRO_BIAS_EXPLODING",
            diag.healthReasons.contains(EskfHealthReason.GYRO_BIAS_EXPLODING))
        assertEquals(EskfHealthReason.GYRO_BIAS_EXPLODING, diag.healthReason)
    }

    // ─── TEST 9: GNSS speed initializes ESKF velocity before GNSS loss ─────────
    @Test
    fun test9_gnssSpeedInitializesEskfVelocity() {
        val provider = PercorsaEskfProvider()
        // Initialize with 15 m/s heading east (90 deg)
        provider.injectGnssCorrection(
            lat = 12.9716, lon = 77.5946,
            accuracyM = 4f, speedMps = 15f, bearingDeg = 90f,
            blendWindowSeconds = 0.0, sourceTimestampNs = 1_000_000_000L
        )

        val speed = provider.status.speedMps
        assertEquals("ESKF speed must match initialized GNSS speed", 15.0, speed, 0.1)

        val vel = provider.status.velocityWorldEnu
        // East = velocityWorldEnu[0] ≈ 15, North = velocityWorldEnu[1] ≈ 0
        assertEquals("East velocity must be ~15 m/s", 15.0, vel[0], 0.2)
        assertEquals("North velocity must be ~0 m/s", 0.0, vel[1], 0.2)
    }

    // ─── TEST 10: healthReason is NONE when filter is healthy ─────────────────
    @Test
    fun test10_healthReasonIsNoneWhenHealthy() {
        val diag = EskfProviderDiagnostics(
            initialized = true,
            valid = true,
            runtimeState = EskfRuntimeState.READY,
            stateFinite = true,
            covarianceFinite = true,
            covariancePsd = true,
            speedMps = 15.0,
            covarianceTrace = 50.0,
            quaternionNorm = 1.0,
            accelBiasMag = 0.2,
            gyroBiasMag = 0.02,
            consecutiveGnssRejections = 0
        )
        assertTrue(diag.isHealthy)
        assertEquals(EskfHealthReason.NONE, diag.healthReason)
        assertEquals(listOf(EskfHealthReason.NONE), diag.healthReasons)
    }

    // ─── TEST 11: healthReason is NOT_INITIALIZED before first GNSS fix ────────
    @Test
    fun test11_healthReasonIsNotInitializedBeforeFirstFix() {
        val provider = PercorsaEskfProvider()
        val diag = provider.status
        assertFalse(diag.isHealthy)
        assertEquals(EskfHealthReason.NOT_INITIALIZED, diag.healthReason)
    }

    // ─── TEST 12: runtimeState=DEGRADED + isHealthy=true is a valid state ─────
    @Test
    fun test12_runtimeStateDegradedWithHealthyEstimator() {
        val provider = PercorsaEskfProvider()
        // Initialize at 5 m/s (> 4 m/s triggers vehicleMotionObserved)
        provider.injectGnssCorrection(
            lat = 12.9716, lon = 77.5946,
            accuracyM = 5f, speedMps = 5f, bearingDeg = 0f,
            blendWindowSeconds = 1.0, sourceTimestampNs = 1_000_000_000L
        )
        val snap = makeSnapshot(timestampNs = 1_010_000_000L)
        provider.update(snap, 0.01)

        val diag = provider.status
        // Vehicle motion observed + no calibration → EskfRuntimeState.DEGRADED
        assertEquals(EskfRuntimeState.DEGRADED, diag.runtimeState)
        // But the ESKF is still HEALTHY for dead reckoning
        assertTrue("ESKF must be HEALTHY even when runtimeState is DEGRADED", diag.isHealthy)
        assertEquals(EskfHealthReason.NONE, diag.healthReason)
    }
}
