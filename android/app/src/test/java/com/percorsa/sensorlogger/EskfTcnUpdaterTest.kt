package com.percorsa.sensorlogger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class EskfTcnUpdaterTest {
    private val updater = EskfTcnUpdater()
    private val identityVehicle = PhoneToVehicleRotation(arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0)
    ))
    private val state = EskfNominalState(
        position = doubleArrayOf(1.0, -2.0, 0.5),
        velocity = doubleArrayOf(4.0, -1.0, 0.5),
        quaternion = EskfQuaternion(0.9238795325112867, 0.0, 0.0, 0.3826834323650898),
        accelerometerBias = doubleArrayOf(0.01, -0.02, 0.03),
        gyroscopeBias = doubleArrayOf(0.001, -0.002, 0.003),
        timestampSeconds = 0.4
    )
    private val covariance = EskfCovariance.diagonal(DoubleArray(15) { 0.1 })

    @Test
    fun vehicleMotionGateRejectsWithoutChangingStateOrCovariance() {
        val result = updater.update(state, covariance, EskfTcnMeasurement(2.0), identityVehicle, false)
        assertFalse(result.accepted)
        assertTrue(result.nis.isInfinite())
        assertArrayEquals(state.velocity, result.state.velocity, 0.0)
        assertEquals(covariance.values[3][3], result.covariance.values[3][3], 0.0)
    }

    @Test
    fun observedMotionAllowsValidMeasurementToUpdateState() {
        val result = updater.update(state, covariance, EskfTcnMeasurement(2.0), identityVehicle, true)
        assertTrue(result.accepted)
        assertNotEquals(state.velocity[0], result.state.velocity[0])
        assertEquals(1.0, result.state.quaternion.norm(), 1e-12)
    }

    @Test
    fun forwardPredictionAndJacobianMatchPythonModel() {
        val transform = PhoneToVehicleRotation(arrayOf(
            doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0)
        ))
        val (prediction, h) = updater.computeMeasurement(state, transform)
        assertEquals(3.5355339059327378, prediction, 1e-12)
        assertArrayEquals(doubleArrayOf(
            0.0, 0.0, 0.0, 0.7071067811865476, -0.7071067811865475, 0.0,
            -0.5, 0.0, 2.1213203435596424, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
        ), h, 1e-12)
    }

    @Test
    fun fixedPhoneToVehicleRotationAndNontrivialWorldOrientationAreUsed() {
        val transform = PhoneToVehicleRotation(arrayOf(
            doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0)
        ))
        val (prediction, _) = updater.computeMeasurement(state, transform)
        assertEquals(3.5355339059327378, prediction, 1e-12)
    }

    @Test
    fun zeroLateralVelocityProducesForwardOnlyMeasurement() {
        val forwardState = state.copy(velocity = doubleArrayOf(3.0, 0.0, 0.0), quaternion = EskfQuaternion.IDENTITY)
        val (prediction, h) = updater.computeMeasurement(forwardState, identityVehicle)
        assertEquals(3.0, prediction, 0.0)
        assertArrayEquals(doubleArrayOf(
            0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
        ), h, 0.0)
    }

    @Test
    fun positiveAndNegativeInnovationsAreAcceptedWhenNisPasses() {
        val positive = updater.update(state, covariance, EskfTcnMeasurement(2.2), identityVehicle, true)
        val negative = updater.update(state, covariance, EskfTcnMeasurement(2.0), identityVehicle, true)
        assertTrue(positive.accepted && negative.accepted)
        assertTrue(positive.innovationMps > 0.0)
        assertTrue(negative.innovationMps < 0.0)
    }

    @Test
    fun clearOutlierIsRejectedWithoutStateChange() {
        val result = updater.update(state, covariance, EskfTcnMeasurement(20.0), identityVehicle, true)
        assertFalse(result.accepted)
        assertTrue(result.nis > 10.827566170662733)
        assertArrayEquals(state.position, result.state.position, 0.0)
        assertEquals(covariance.values[0][0], result.covariance.values[0][0], 0.0)
    }

    @Test
    fun josephUpdateRemainsSymmetricAndPsd() {
        val result = updater.update(state, covariance, EskfTcnMeasurement(4.0), identityVehicle, true)
        assertTrue(result.covariance.maxAsymmetry() <= 1e-5)
        assertTrue(result.covariance.minimumEigenvalue() >= -1e-7)
    }

    @Test
    fun fixedPythonReferenceFixtureMatches() {
        val transform = PhoneToVehicleRotation(arrayOf(
            doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0)
        ))
        val result = updater.update(state, covariance, EskfTcnMeasurement(2.0, 0.25, 0.5), transform, true)
        assertTrue(result.accepted)
        assertEquals(3.5355339059327378, result.predictedSpeedMps, 1e-12)
        assertEquals(-1.5355339059327378, result.innovationMps, 1e-12)
        assertEquals(0.825, result.innovationVarianceMps2, 1e-12)
        assertEquals(2.8580174257806665, result.nis, 1e-12)
        assertArrayEquals(doubleArrayOf(
            0.0, 0.0, 0.0, 0.0857099128710967, -0.08570991287109668, 0.0,
            -0.060606060606060615, 0.0, 0.25712973861329, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
        ), result.kalmanGain, 1e-12)
        assertArrayEquals(doubleArrayOf(3.8683895227118903, -0.8683895227118903, 0.5), result.state.velocity, 1e-12)
        assertEquals(0.09393939393939392, result.covariance.values[3][3], 1e-12)
        assertEquals(0.04567106192619552, result.covariance.values[8][8], 1e-12)
    }

    @Test
    fun invalidSpeedVarianceAndTimestampAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            updater.update(state, covariance, EskfTcnMeasurement(Double.NaN), identityVehicle, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            updater.update(state, covariance, EskfTcnMeasurement(2.0, -0.1), identityVehicle, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            updater.update(state, covariance, EskfTcnMeasurement(2.0, 0.25, Double.NaN), identityVehicle, true)
        }
    }
}
