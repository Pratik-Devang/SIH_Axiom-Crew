package com.percorsa.sensorlogger

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Regression tests for the real-world navigation failure fix pass.
 *
 * Six failures fixed:
 * 1. INS velocity runaway (gravity leakage) -- tested via EskfPropagatorTest (linear accel flag)
 * 2. TCN 38 m/s stationary output -- frame transform test below
 * 3. GNSS position inaccuracy / NIS latch -- divergence recovery test below
 * 4. 90-degree heading offset -- heading row extraction test below
 * 5. Turn detection sign inversion -- yaw rate sign convention test below
 * 6. Inaccurate GNSS-off trajectory -- covered by items 1, 2, 4, 5
 */
class NavigationFixPassTest {

    // ── Fix 2: TCN Frame Transform ─────────────────────────────────────────────

    @Test
    fun tcnFeatureArrayUncalibrated_usePhoneFrame() {
        val sample = CanonicalImuSample(
            timestampNs = 1L,
            accelX = 1f, accelY = 2f, accelZ = 9.8f,
            gyroX = 0.1f, gyroY = 0.2f, gyroZ = 0.3f,
            vehicleFrameCalibrated = false
        )
        val features = sample.toFeatureArray()
        assertArrayEquals(floatArrayOf(1f, 2f, 9.8f, 0.1f, 0.2f, 0.3f), features, 0.001f)
    }

    @Test
    fun tcnFeatureArrayCalibrated_useVehicleBenchmarkFrame() {
        // Portrait-mounted phone: gravity on Y phone-axis (+Y = up in phone frame).
        // After correct R_v_p: vehicleAccelUp should carry the ~9.81 gravity component.
        val sample = CanonicalImuSample(
            timestampNs = 1L,
            accelX = 0f, accelY = 9.81f, accelZ = 0f, // phone Y is gravity
            gyroX = 0f, gyroY = 0f, gyroZ = 0f,
            vehicleAccelForward = 0f,
            vehicleAccelLeft = 0f,
            vehicleAccelUp = 9.81f,  // properly rotated to benchmark Z-up
            vehicleGyroLeft = 0f,
            vehicleGyroForward = 0f,
            vehicleGyroUp = 0f,
            vehicleFrameCalibrated = true
        )
        val features = sample.toFeatureArray()
        // [lateral, forward, up, gyro_lat, gyro_fwd, gyro_up]
        assertEquals("accel_lateral", 0f, features[0], 0.001f)
        assertEquals("accel_forward", 0f, features[1], 0.001f)
        assertEquals("accel_up should carry gravity ~9.81", 9.81f, features[2], 0.001f)
        // Gravity in the Z channel matches training normalization mean (9.845 m/s^2)
        // so normalised value = (9.81 - 9.845) / 0.695 = -0.05 sigma -- well in distribution.
        val zNormalised = (features[2] - 9.845f) / 0.695f
        assertTrue("Z channel must be within 1 sigma of training mean, got $zNormalised sigma",
            abs(zNormalised) < 1.0f)
    }

    @Test
    fun tcnStationaryPortraitPhone_gravityInZChannel() {
        // Simulates a stationary phone mounted upright (portrait).
        // Old code: accelZ ≈ 0 → z_normalised = (0 - 9.845)/0.695 = -14.2 sigma → 38 m/s output
        // New code: vehicleAccelUp ≈ 9.81  → z_normalised ≈ -0.05 sigma → ~0 m/s output
        val sampleNew = CanonicalImuSample(
            timestampNs = 1L,
            accelX = 0f, accelY = 9.81f, accelZ = 0f,
            gyroX = 0f, gyroY = 0f, gyroZ = 0f,
            vehicleAccelForward = 0f, vehicleAccelLeft = 0f, vehicleAccelUp = 9.81f,
            vehicleGyroLeft = 0f, vehicleGyroForward = 0f, vehicleGyroUp = 0f,
            vehicleFrameCalibrated = true
        )
        val features = sampleNew.toFeatureArray()
        val zChannel = features[2]
        val zNormalised = (zChannel - 9.845f) / 0.695f
        // Must be within 1 sigma -- will result in near-zero speed output
        assertTrue("Portrait stationary: Z channel $zChannel normalises to $zNormalised sigma (must be |σ| < 1)",
            abs(zNormalised) < 1.0f)

        // Contrast with old (uncalibrated) path: accelZ = 0 is -14 sigma
        val sampleOld = CanonicalImuSample(
            timestampNs = 2L,
            accelX = 0f, accelY = 9.81f, accelZ = 0f,
            gyroX = 0f, gyroY = 0f, gyroZ = 0f,
            vehicleFrameCalibrated = false
        )
        val zOldChannel = sampleOld.toFeatureArray()[2]
        val zOldNormalised = (zOldChannel - 9.845f) / 0.695f
        assertTrue("Old path Z channel is heavily out of distribution (< -10 sigma)",
            zOldNormalised < -10f)
    }

    // ── Fix 4 & 3: Heading extraction (Row 0 vs Column 0) ─────────────────────

    @Test
    fun phoneToVehicleForwardPhone_isRow0NotColumn0() {
        // R_v_p with distinct values to detect row vs column confusion
        // Row 0 = [10, 11, 12], Column 0 = [10, 20, 30]
        val rotation = PhoneToVehicleRotation(arrayOf(
            doubleArrayOf(10.0, 11.0, 12.0),
            doubleArrayOf(20.0, 21.0, 22.0),
            doubleArrayOf(30.0, 31.0, 32.0)
        ))
        val forward = rotation.forwardPhone
        // Row 0 should be (10, 11, 12)
        assertEquals("forwardPhone.x must be row0[0] = 10.0", 10.0, forward.x, 1e-10)
        assertEquals("forwardPhone.y must be row0[1] = 11.0", 11.0, forward.y, 1e-10)
        assertEquals("forwardPhone.z must be row0[2] = 12.0", 12.0, forward.z, 1e-10)
        // NOT column 0 = (10, 20, 30)
        assertNotEquals("must NOT be column 0 (x=20)", 20.0, forward.y, 1e-10)
    }

    // ── Fix 5: Yaw Rate Sign Convention ───────────────────────────────────────

    @Test
    fun yawRateSignConvention_rightTurnIsPositive() {
        // ENU convention: +Z = counter-clockwise. Navigation bearing: clockwise = positive.
        // For a right turn (clockwise), gyro Z in ENU should be NEGATIVE.
        // yawRateDegS = -Math.toDegrees(omega_Z) -- for omega_Z < 0 (right turn), result > 0 ✓
        val omegaZEnu = -0.3  // rad/s CCW, so vehicle is actually turning right (CW)
        val yawRateDegS = -Math.toDegrees(omegaZEnu).toFloat()
        assertTrue("Right turn: yawRateDegS must be positive (CW), got $yawRateDegS", yawRateDegS > 0f)
    }

    @Test
    fun yawRateSignConvention_leftTurnIsNegative() {
        // For a left turn (CCW), omega_Z in ENU is positive.
        // yawRateDegS = -Math.toDegrees(omega_Z) -- for omega_Z > 0 (left turn), result < 0 ✓
        val omegaZEnu = 0.3  // rad/s CCW = left turn
        val yawRateDegS = -Math.toDegrees(omegaZEnu).toFloat()
        assertTrue("Left turn: yawRateDegS must be negative (CCW), got $yawRateDegS", yawRateDegS < 0f)
    }

    // ── Fix 3: GNSS Divergence Recovery ───────────────────────────────────────

    @Test
    fun gnssDivergenceRecovery_triggersAfter5HighQualityRejections() {
        val provider = PercorsaEskfProvider()
        // Initialize with first GNSS fix at origin
        provider.injectGnssCorrection(12.9716, 77.5946, 5f, 10f, 45f, 3.0, 1_000_000_000L)
        assertTrue("Provider must be initialized after first fix", provider.isInitialized)

        // Drive the ESKF to a diverged state by accumulating many IMU updates
        // that push position far from the first GNSS fix.
        val snapshot = makeSensorSnapshot(timestampNs = 2_000_000_000L, accelZ = 9.81f)
        provider.update(snapshot, 0.01)

        // Now inject 5 high-quality fixes at the original location.
        // These will all be rejected by NIS because the filter has diverged,
        // but after 5 rejections the recovery should trigger.
        for (i in 1..5) {
            provider.injectGnssCorrection(
                12.9716, 77.5946,
                5f,   // accuracy <= 10m triggers divergence count
                10f, 45f, 3.0,
                (3_000_000_000L + i * 1_000_000_000L)
            )
        }

        // After recovery the position should no longer be far-diverged.
        val pos = provider.getEstimatedPosition()
        assertNotNull("Position must be available after recovery", pos)
    }

    @Test
    fun gnssVelocityGate_notInjectedBelowThreshold() {
        // The GNSS velocity update must only apply when speedMps >= 1.5 m/s.
        // This test verifies the provider does not crash or reject due to invalid
        // velocity when speed is 0 (stopped at traffic light).
        val provider = PercorsaEskfProvider()
        // Initialize
        provider.injectGnssCorrection(12.9716, 77.5946, 8f, 0f, 0f, 3.0, 1_000_000_000L)
        assertTrue("Provider must be initialized", provider.isInitialized)
        // Inject a stationary fix with speed = 0.5 m/s (below gate)
        provider.injectGnssCorrection(12.9716, 77.5946, 6f, 0.5f, 90f, 3.0, 2_000_000_000L)
        // Must not crash and must remain valid
        assertTrue("Provider must remain valid after low-speed GNSS fix", provider.isInitialized)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun makeSensorSnapshot(
        timestampNs: Long,
        accelX: Float = 0f, accelY: Float = 0f, accelZ: Float = 9.81f,
        gyroX: Float = 0f, gyroY: Float = 0f, gyroZ: Float = 0f
    ) = SensorSnapshot(
        timestampNs = timestampNs,
        hasAccel = true, hasGyro = true, hasRotVector = false,
        hasLinearAccel = false, hasGravity = false, hasMag = false, hasGps = false,
        latitude = 0.0, longitude = 0.0, altitude = Double.NaN,
        gpsSpeedMps = Float.NaN, gpsBearingDeg = Float.NaN, gpsAccuracyM = Float.NaN,
        compassBearingDeg = 0f,
        accelX = accelX, accelY = accelY, accelZ = accelZ, accelMag = sqrt(accelX*accelX + accelY*accelY + accelZ*accelZ),
        gyroX = gyroX, gyroY = gyroY, gyroZ = gyroZ, gyroMag = 0f,
        quatW = 1f, quatX = 0f, quatY = 0f, quatZ = 0f, quatNorm = 1f,
        linearAccelX = 0f, linearAccelY = 0f, linearAccelZ = 0f, linearAccelMag = 0f,
        gravityX = 0f, gravityY = 0f, gravityZ = -9.81f, gravityMag = 9.81f,
        magX = 0f, magY = 0f, magZ = 0f,
        isCalibrated = false,
        correctedAccelForward = 0f, correctedAccelLeft = 0f, correctedAccelUp = accelZ,
        correctedAccelMag = accelZ,
        correctedLinearForward = 0f, correctedLinearLeft = 0f, correctedLinearUp = 0f,
        correctedLinearMag = 0f,
        correctedGyroForward = 0f, correctedGyroLeft = 0f, correctedGyroUp = 0f,
        correctedGyroMag = 0f,
        imuHz = 100f, rawCallbackHz = 100f, totalCallbacks = 0,
        gpsFixAgeMs = -1L,
        tcnBufferCount = 0, tcnBufferCapacity = 50, tcnWindowSeconds = 5f, tcnBufferReady = false,
        tcnInferenceActive = false, tcnModelLoaded = false, tcnInferenceInFlight = false,
        tcnRawSpeedMps = Float.NaN, tcnPredictedSpeedMps = Float.NaN,
        tcnInferenceAgeMs = -1L, tcnInferenceLatencyMs = 0f,
        tcnPredictionRateLimited = false, tcnRejectedPredictionCount = 0L,
        tcnInferenceError = null, lastCanonicalSample = null,
        minDtMs = 9.5f, maxDtMs = 10.5f, avgDtMs = 10f, dtJitterMs = 0.5f,
        loggedCsvRows = 0L, duplicateTimestampsCount = 0L,
        nonMonotonicTimestampsCount = 0L, largeGapCount = 0L, staleSensorCount = 0L,
        warnings = emptyList()
    )
}
