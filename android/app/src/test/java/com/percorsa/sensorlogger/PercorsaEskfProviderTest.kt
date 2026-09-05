package com.percorsa.sensorlogger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PercorsaEskfProviderTest {
    private fun snapshot(timestampNs: Long, accelZ: Float = 9.81f) = SensorSnapshot(
        timestampNs = timestampNs,
        hasAccel = true, hasGyro = true, hasRotVector = true, hasLinearAccel = false,
        hasGravity = true, hasMag = false, hasGps = false,
        latitude = 0.0, longitude = 0.0, altitude = 0.0,
        gpsSpeedMps = 0f, gpsBearingDeg = 0f, gpsAccuracyM = 5f, compassBearingDeg = 0f,
        accelX = 0f, accelY = 0f, accelZ = accelZ, accelMag = 9.81f,
        gyroX = 0f, gyroY = 0f, gyroZ = 0f, gyroMag = 0f,
        quatW = 1f, quatX = 0f, quatY = 0f, quatZ = 0f, quatNorm = 1f,
        linearAccelX = 0f, linearAccelY = 0f, linearAccelZ = 0f, linearAccelMag = 0f,
        gravityX = 0f, gravityY = 0f, gravityZ = -9.81f, gravityMag = 9.81f,
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

    @Test
    fun providerStartsUninitializedAndInitializesDeterministically() {
        val provider = PercorsaEskfProvider()
        assertFalse(provider.isInitialized)
        assertEquals(null, provider.getEstimatedPosition())
        provider.initialize(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), timestampNs = 1_000_000_000L)
        assertTrue(provider.isInitialized)
        assertArrayEquals(doubleArrayOf(1.0, 0.0, 0.0), provider.currentState!!.velocity, 0.0)
        assertTrue(provider.status.stateFinite && provider.status.covariancePsd)
    }

    @Test
    fun rawPhoneImuPropagatesNominalAndCovarianceWithSensorTimestamp() {
        val provider = PercorsaEskfProvider()
        provider.initialize(timestampNs = 1_000_000_000L)
        provider.update(snapshot(1_010_000_000L), 99.0)
        val current = provider.currentState!!
        assertEquals(1.01, current.timestampSeconds, 1e-12)
        assertEquals(0.0, current.velocity[2], 1e-8)
        assertTrue(provider.status.stateFinite && provider.status.covarianceFinite && provider.status.covariancePsd)
        assertEquals(1.0, current.quaternion.norm(), 1e-12)
    }

    @Test
    fun invalidAndNonMonotonicTimestampsInvalidateWithoutFabrication() {
        val provider = PercorsaEskfProvider()
        provider.initialize(timestampNs = 1_000_000_000L)
        provider.update(snapshot(1_000_000_000L), 0.1)
        provider.update(snapshot(900_000_000L), 0.1)
        assertFalse(provider.status.valid)
        assertTrue(provider.status.error!!.contains("Non-monotonic"))
    }

    @Test
    fun measurementsUseStandaloneUpdatersAndRejectUnsafeTcn() {
        val provider = PercorsaEskfProvider()
        provider.initialize(velocityWorldEnu = doubleArrayOf(5.0, 0.0, 0.0), timestampNs = 1_000_000_000L)
        val blocked = provider.processTcn(5.0, motionObserved = false)!!
        assertFalse(blocked.accepted)
        provider.setVehicleMotionObserved(true)
        val accepted = provider.processTcn(5.0, motionObserved = true)!!
        assertTrue(accepted.accepted)
        assertNotNull(provider.processNhc(enabled = true))
        assertNotNull(provider.processZupt(EskfZuptMeasurement(stdVelocityMps = 1.0), enabled = true))
        assertTrue(provider.status.stateFinite && provider.status.covariancePsd)
    }

    @Test
    fun gnssInitializesPositionAndOutputUsesNavigationBearingConvention() {
        val provider = PercorsaEskfProvider()
        provider.injectGnssCorrection(19.0, 73.0, 5f, 5f, 90f, 0.0)
        val output = provider.getEstimatedPosition()!!
        assertEquals(19.0, output.latitude, 0.0)
        assertEquals(73.0, output.longitude, 0.0)
        assertEquals(90f, output.heading, 1e-5f)
        assertTrue(provider.acceptsTcnSpeedEstimate)
    }
}
