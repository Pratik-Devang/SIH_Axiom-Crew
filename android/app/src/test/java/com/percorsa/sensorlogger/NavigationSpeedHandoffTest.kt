package com.percorsa.sensorlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationSpeedHandoffTest {

    @Test
    fun tcnIsInjectedOnlyWhenGnssIsUntrustedAndInferenceIsActive() {
        assertFalse(NavigationController.shouldInjectTcnSpeed(false, true, false))
        assertTrue(NavigationController.shouldInjectTcnSpeed(false, true, true))
        assertFalse(NavigationController.shouldInjectTcnSpeed(true, true, true))
        assertFalse(NavigationController.shouldInjectTcnSpeed(false, false, true))
    }

    @Test
    fun tcnMeasurementIsBlendedIntoExistingInsVelocity() {
        val provider = SimplifiedInsProvider()
        provider.injectGnssCorrection(12.0, 77.0, 10f, 4f, 0f, 0.0)
        provider.injectSpeedEstimate(10f)

        provider.update(testSnapshot(timestampNs = 1_000_000_000L), 0.1)

        val position = provider.getEstimatedPosition()!!
        assertEquals(5.5525f, position.speedMps, 0.001f)
        assertTrue(provider.diagnostics.tcnSpeedInjected)
        assertEquals(10f, provider.diagnostics.tcnSpeedMps, 0.001f)
    }

    @Test
    fun uncalibratedPhoneAxisIsNotUsedByTcnHandoff() {
        val provider = SimplifiedInsProvider()
        provider.injectGnssCorrection(12.0, 77.0, 10f, 4f, 0f, 0.0)
        provider.injectSpeedEstimate(8f)

        val snapshot = testSnapshot(timestampNs = 1_000_000_000L).copy(
            isCalibrated = false,
            linearAccelX = 3f,
            correctedLinearForward = 0f
        )
        provider.update(snapshot, 0.1)

        assertTrue(provider.diagnostics.tcnSpeedInjected)
        // The qualifying GNSS fix initialized INS velocity at 4 m/s. With no
        // calibrated forward axis, the existing damping leaves 3.6 m/s before
        // the unchanged 75/25 TCN correction.
        assertEquals(4.7f, provider.getEstimatedPosition()!!.speedMps, 0.001f)
    }

    @Test
    fun vehicleMotionEvidenceRequiresSpeedAndAccuracy() {
        val belowSpeed = SimplifiedInsProvider()
        belowSpeed.injectGnssCorrection(12.0, 77.0, 10f, 3.99f, 0f, 0.0)
        assertFalse(belowSpeed.acceptsTcnSpeedEstimate)

        val observed = SimplifiedInsProvider()
        observed.injectGnssCorrection(12.0, 77.0, 15f, 4f, 0f, 0.0)
        assertTrue(observed.acceptsTcnSpeedEstimate)
        assertTrue(NavigationController.shouldInjectTcnSpeed(false, true, observed.acceptsTcnSpeedEstimate))
    }

    @Test
    fun tcnMeasurementIsRejectedBeforeVehicleMotionEvidence() {
        val provider = SimplifiedInsProvider()
        provider.injectGnssCorrection(12.0, 77.0, 5f, 0f, 0f, 0.0)
        provider.injectSpeedEstimate(11.51f)

        provider.update(testSnapshot(timestampNs = 1_000_000_000L), 0.1)

        assertFalse(provider.diagnostics.vehicleMotionObserved)
        assertFalse(provider.diagnostics.tcnSpeedInjected)
    }

    private fun testSnapshot(timestampNs: Long): SensorSnapshot = SensorSnapshot(
        timestampNs = timestampNs,
        hasAccel = true, hasGyro = true, hasRotVector = true,
        hasLinearAccel = true, hasGravity = true, hasMag = false, hasGps = false,
        latitude = 12.0, longitude = 77.0, altitude = 0.0,
        gpsSpeedMps = 0f, gpsBearingDeg = 0f, gpsAccuracyM = 5f,
        compassBearingDeg = 0f,
        accelX = 0f, accelY = 0f, accelZ = 9.81f, accelMag = 9.81f,
        gyroX = 0f, gyroY = 0f, gyroZ = 0f, gyroMag = 0.2f,
        quatW = 1f, quatX = 0f, quatY = 0f, quatZ = 0f, quatNorm = 1f,
        linearAccelX = 2f, linearAccelY = 0f, linearAccelZ = 0f, linearAccelMag = 2f,
        gravityX = 0f, gravityY = 0f, gravityZ = 9.81f, gravityMag = 9.81f,
        magX = 0f, magY = 0f, magZ = 0f,
        isCalibrated = true,
        correctedAccelForward = 0f, correctedAccelLeft = 0f, correctedAccelUp = 0f,
        correctedAccelMag = 0f,
        correctedLinearForward = 2f, correctedLinearLeft = 0f, correctedLinearUp = 0f,
        correctedLinearMag = 2f,
        correctedGyroForward = 0f, correctedGyroLeft = 0f, correctedGyroUp = 0f,
        correctedGyroMag = 0f,
        imuHz = 200f, rawCallbackHz = 1000f, totalCallbacks = 1, gpsFixAgeMs = -1L,
        tcnBufferCount = 50, tcnBufferCapacity = 50, tcnWindowSeconds = 5f,
        tcnBufferReady = true, tcnInferenceActive = true, tcnModelLoaded = true,
        tcnInferenceInFlight = false, tcnRawSpeedMps = 10f, tcnPredictedSpeedMps = 10f,
        tcnInferenceAgeMs = 0L, tcnInferenceLatencyMs = 1f,
        tcnPredictionRateLimited = false, tcnRejectedPredictionCount = 0L,
        tcnInferenceError = null, lastCanonicalSample = null,
        minDtMs = 5f, maxDtMs = 5f, avgDtMs = 5f, dtJitterMs = 0f,
        loggedCsvRows = 0L, duplicateTimestampsCount = 0L,
        nonMonotonicTimestampsCount = 0L, largeGapCount = 0L,
        staleSensorCount = 0L, warnings = emptyList()
    )
}
