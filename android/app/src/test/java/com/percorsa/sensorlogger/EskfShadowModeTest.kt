package com.percorsa.sensorlogger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EskfShadowModeTest {
    @Test
    fun equivalentRawPhoneSamplesProduceEquivalentShadowStates() {
        val first = PercorsaEskfProvider()
        val second = PercorsaEskfProvider()
        first.initialize(timestampNs = 1_000_000_000L)
        second.initialize(timestampNs = 1_000_000_000L)

        val samples = listOf(
            shadowSnapshot(1_010_000_000L, 0.2f, -0.1f),
            shadowSnapshot(1_020_000_000L, 0.4f, 0.3f)
        )
        samples.forEach { sample ->
            first.update(sample, 99.0)
            second.update(sample, 0.01)
        }

        assertArrayEquals(first.currentState!!.position, second.currentState!!.position, 0.0)
        assertArrayEquals(first.currentState!!.velocity, second.currentState!!.velocity, 0.0)
        assertEquals(first.status.lastDtSeconds, second.status.lastDtSeconds, 0.0)
    }

    @Test
    fun shadowMeasurementFailureDoesNotChangeSeparateActiveProvider() {
        val active = SimplifiedInsProvider()
        val shadow = PercorsaEskfProvider()
        active.injectGnssCorrection(19.0, 72.0, 5f, 0f, 0f, 0.0)
        val before = active.getEstimatedPosition()!!.speedMps

        shadow.initialize(timestampNs = 1_000_000_000L)
        shadow.update(shadowSnapshot(1_010_000_000L), 0.1)
        shadow.update(shadowSnapshot(900_000_000L), 0.1)

        assertEquals(before, active.getEstimatedPosition()!!.speedMps, 0.0f)
        assertNotEquals(null, shadow.status.error)
    }

    @Test
    fun diagnosticSnapshotIsConsistentAndDoesNotExposeProviderArrays() {
        val provider = PercorsaEskfProvider()
        provider.initialize(velocityWorldEnu = doubleArrayOf(3.0, 4.0, 0.0), timestampNs = 1_000_000_000L)

        val snapshot = provider.status
        val state = provider.currentState!!
        state.velocity[0] = 99.0

        assertEquals(listOf(3.0, 4.0, 0.0), snapshot.velocityWorldEnu)
        assertEquals(5.0, snapshot.speedMps, 0.0)
        assertEquals(1.0, snapshot.quaternionNorm, 0.0)
    }

    @Test
    fun gnssDiagnosticNamesTheInnovationMetric() {
        val provider = PercorsaEskfProvider()
        provider.injectGnssCorrection(19.0, 72.0, 5f, 5f, 0f, 0.0)
        provider.injectGnssCorrection(19.00001, 72.00001, 5f, 5f, 0f, 0.0)

        assertEquals(true, provider.status.lastGnssInnovationMagnitudeM.isFinite())
    }

    private fun shadowSnapshot(timestampNs: Long, accelX: Float = 0f, gyroZ: Float = 0f) = SensorSnapshot(
        timestampNs = timestampNs,
        hasAccel = true, hasGyro = true, hasRotVector = true, hasLinearAccel = false,
        hasGravity = true, hasMag = false, hasGps = false,
        latitude = 0.0, longitude = 0.0, altitude = 0.0,
        gpsSpeedMps = 0f, gpsBearingDeg = 0f, gpsAccuracyM = 5f, compassBearingDeg = 0f,
        accelX = accelX, accelY = 0f, accelZ = 9.81f, accelMag = 9.81f,
        gyroX = 0f, gyroY = 0f, gyroZ = gyroZ, gyroMag = kotlin.math.abs(gyroZ),
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
}
