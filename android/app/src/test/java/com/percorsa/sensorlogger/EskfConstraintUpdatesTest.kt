package com.percorsa.sensorlogger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class EskfConstraintUpdatesTest {
    private val nhc = EskfNhcUpdater()
    private val zupt = EskfZuptUpdater()
    private val transform = PhoneToVehicleRotation(arrayOf(
        doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0)
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
    fun nhcPredictionAndJacobianMatchPython() {
        val (prediction, h) = nhc.computeMeasurement(state, transform)
        assertArrayEquals(doubleArrayOf(2.1213203435596424, 0.5), prediction, 1e-12)
        assertEquals(2, h.size)
        assertArrayEquals(doubleArrayOf(
            0.0, 0.0, 0.0, 0.7071067811865475, 0.7071067811865476, 0.0,
            0.0, -0.5, -3.5355339059327378, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
        ), h[0], 1e-12)
        assertArrayEquals(doubleArrayOf(
            0.0, 0.0, 0.0, 0.0, 0.0, 1.0,
            3.5355339059327378, 2.1213203435596424, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
        ), h[1], 1e-12)
    }

    @Test
    fun nhcAnalyticalFrameCasesAreCorrect() {
        val zero = state.copy(velocity = doubleArrayOf(5.0, 0.0, 0.0), quaternion = EskfQuaternion.IDENTITY)
        val (zeroPrediction, _) = nhc.computeMeasurement(zero)
        assertArrayEquals(doubleArrayOf(0.0, 0.0), zeroPrediction, 0.0)
        val lateral = state.copy(velocity = doubleArrayOf(5.0, 2.0, 0.0), quaternion = EskfQuaternion.IDENTITY)
        assertEquals(2.0, nhc.computeMeasurement(lateral).first[0], 0.0)
        val vertical = state.copy(velocity = doubleArrayOf(5.0, 0.0, -1.0), quaternion = EskfQuaternion.IDENTITY)
        assertEquals(-1.0, nhc.computeMeasurement(vertical).first[1], 0.0)
    }

    @Test
    fun nhcUpdateMatchesPythonFixtureAndPreservesNumerics() {
        val result = nhc.update(state, covariance, EskfNhcMeasurement(0.1, 0.1, 0.5), transform)
        assertTrue(result.accepted)
        assertEquals(3.4926470588235277, result.nis, 1e-12)
        assertArrayEquals(doubleArrayOf(3.889705882352941, -1.1102941176470589, 0.4632352941176471), result.state.velocity, 1e-12)
        assertEquals(0.09637361756691781, result.covariance.values[3][3], 1e-12)
        assertTrue(result.covariance.maxAsymmetry() <= 1e-5)
        assertTrue(result.covariance.minimumEigenvalue() >= -1e-7)
        assertEquals(1.0, result.state.quaternion.norm(), 1e-12)
    }

    @Test
    fun nhcOutlierAndDisabledUpdateLeaveStateUnchanged() {
        val outlier = nhc.update(state.copy(velocity = doubleArrayOf(20.0, 10.0, 5.0)), covariance, EskfNhcMeasurement(0.01, 0.01), transform)
        assertFalse(outlier.accepted)
        assertArrayEquals(doubleArrayOf(20.0, 10.0, 5.0), outlier.state.velocity, 0.0)
        val disabled = nhc.update(state, covariance, EskfNhcMeasurement(), transform, enabled = false)
        assertFalse(disabled.accepted)
        assertEquals(covariance.values[3][3], disabled.covariance.values[3][3], 0.0)
    }

    @Test
    fun zuptCorrectsWorldVelocityWithVelocityOnlyJacobian() {
        val moving = state.copy(velocity = doubleArrayOf(0.1, -0.05, 0.02))
        val result = zupt.update(moving, covariance, EskfZuptMeasurement(0.01, 0.5), enabled = true)
        assertTrue(result.accepted)
        assertArrayEquals(doubleArrayOf(0.00009990009990010207, -0.00004995004995005103, 0.000019980019980020414), result.state.velocity, 1e-12)
        assertArrayEquals(doubleArrayOf(
            0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
        ), result.jacobian[0], 0.0)
        assertEquals(0.12887112887112886, result.nis, 1e-12)
        assertTrue(result.covariance.maxAsymmetry() <= 1e-5)
        assertTrue(result.covariance.minimumEigenvalue() >= -1e-7)
        assertEquals(1.0, result.state.quaternion.norm(), 1e-12)
    }

    @Test
    fun zuptDisabledAndOutlierAreRejectedWithoutMutation() {
        val disabled = zupt.update(state, covariance, EskfZuptMeasurement(), enabled = false)
        assertFalse(disabled.accepted)
        assertArrayEquals(state.position, disabled.state.position, 0.0)
        val outlier = zupt.update(state, covariance, EskfZuptMeasurement(0.01), enabled = true)
        assertFalse(outlier.accepted)
        assertArrayEquals(state.velocity, outlier.state.velocity, 0.0)
        assertEquals(covariance.values[3][3], outlier.covariance.values[3][3], 0.0)
    }

    @Test
    fun invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            nhc.update(state, covariance, EskfNhcMeasurement(stdLateralMps = Double.NaN), transform)
        }
        assertThrows(IllegalArgumentException::class.java) {
            zupt.update(state, covariance, EskfZuptMeasurement(stdVelocityMps = -1.0), enabled = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            zupt.update(state, EskfCovariance.diagonal(DoubleArray(15) { if (it == 0) Double.NaN else 0.1 }), EskfZuptMeasurement(), true)
        }
    }
}
