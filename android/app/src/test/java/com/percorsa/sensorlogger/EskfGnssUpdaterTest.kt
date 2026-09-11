package com.percorsa.sensorlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class EskfGnssUpdaterTest {

    private val updater = EskfGnssUpdater()
    private val state = EskfNominalState(
        position = doubleArrayOf(1.0, -2.0, 0.5),
        velocity = doubleArrayOf(2.0, -1.0, 0.3),
        quaternion = EskfQuaternion(0.9659258263, 0.1, 0.2, 0.05),
        accelerometerBias = doubleArrayOf(0.01, -0.02, 0.03),
        gyroscopeBias = doubleArrayOf(0.001, -0.002, 0.003),
        timestampSeconds = 0.4
    )
    private val covariance = EskfCovariance.diagonal(DoubleArray(15) { 0.1 })

    @Test
    fun positionUpdateMatchesPythonReferenceFixture() {
        val result = updater.updatePosition(
            state,
            covariance,
            EskfGnssPositionMeasurement(EskfVector3(1.5, -1.0, 0.0), EskfVector3(3.0, 3.0, 5.0), 0.5)
        )

        assertTrue(result.accepted)
        assertEquals(0.14732279672518717, result.nis, 1e-12)
        assertEquals(1.0054945054945055, result.state.position[0], 1e-12)
        assertEquals(-1.989010989010989, result.state.position[1], 1e-12)
        assertEquals(0.49800796812749004, result.state.position[2], 1e-12)
        assertEquals(0.09890109890109892, result.covariance.values[0][0], 1e-12)
        assertEquals(0.09960159362549802, result.covariance.values[2][2], 1e-12)
        assertEquals(0.010989010989010991, result.kalmanGain[0][0], 1e-12)
    }

    @Test
    fun velocityUpdateMatchesPythonReferenceFixture() {
        val result = updater.updateVelocity(
            state,
            covariance,
            EskfGnssVelocityMeasurement(EskfVector3(2.5, -0.5, 0.1), EskfVector3(0.5, 0.5, 1.0), 0.5)
        )

        assertTrue(result.accepted)
        assertEquals(1.464935064935065, result.nis, 1e-12)
        assertEquals(2.142857142857143, result.state.velocity[0], 1e-12)
        assertEquals(-0.8571428571428571, result.state.velocity[1], 1e-12)
        assertEquals(0.2818181818181818, result.state.velocity[2], 1e-12)
        assertEquals(0.07142857142857142, result.covariance.values[3][3], 1e-12)
    }

    @Test
    fun measurementNoiseControlsCorrectionStrength() {
        val measurement = EskfGnssPositionMeasurement(EskfVector3(1.05, -1.95, 0.52), EskfVector3(0.01, 0.01, 0.01))
        val lowNoise = updater.updatePosition(state, covariance, measurement)
        val highNoise = updater.updatePosition(
            state,
            covariance,
            measurement.copy(standardDeviationM = EskfVector3(100.0, 100.0, 100.0))
        )
        assertTrue(lowNoise.accepted && highNoise.accepted)
        assertTrue(kotlin.math.abs(lowNoise.state.position[0] - state.position[0]) >
                kotlin.math.abs(highNoise.state.position[0] - state.position[0]))
    }

    @Test
    fun clearPositionOutlierIsRejectedByNis() {
        val result = updater.updatePosition(
            state,
            covariance,
            EskfGnssPositionMeasurement(EskfVector3(1000.0, 1000.0, 1000.0), EskfVector3(1.0, 1.0, 1.0))
        )
        assertTrue(!result.accepted)
        assertTrue(result.nis > 16.26623619623813)
        assertEquals(state.position[0], result.state.position[0], 0.0)
        assertEquals(covariance.values[0][0], result.covariance.values[0][0], 0.0)
    }

    @Test
    fun josephUpdateIsSymmetricAndPsd() {
        val result = updater.updateVelocity(
            state,
            covariance,
            EskfGnssVelocityMeasurement(EskfVector3(2.2, -0.8, 0.2), EskfVector3(0.5, 0.5, 1.0))
        )
        assertTrue(result.covariance.maxAsymmetry() <= 1e-5)
        assertTrue(result.covariance.minimumEigenvalue() >= -1e-7)
    }

    @Test
    fun correlatedCovarianceInjectsAllNominalErrorBlocks() {
        val matrix = Array(15) { row -> DoubleArray(15) { column -> if (row == column) 0.1 else 0.0 } }
        for (index in 1 until 15) {
            matrix[0][index] = 0.001
            matrix[index][0] = 0.001
        }
        val result = updater.updatePosition(
            state,
            EskfCovariance.from(matrix),
            EskfGnssPositionMeasurement(EskfVector3(2.0, -1.0, 1.0), EskfVector3(3.0, 3.0, 3.0))
        )
        assertTrue(result.accepted)
        assertNotEquals(state.position[0], result.state.position[0])
        assertNotEquals(state.velocity[0], result.state.velocity[0])
        assertNotEquals(state.accelerometerBias[0], result.state.accelerometerBias[0])
        assertNotEquals(state.gyroscopeBias[0], result.state.gyroscopeBias[0])
        assertTrue(result.state.quaternion.norm() == 1.0)
    }

    @Test
    fun invalidAndSingularMeasurementsAreRejectedSafely() {
        assertThrows(IllegalArgumentException::class.java) {
            updater.updatePosition(state, covariance, EskfGnssPositionMeasurement(EskfVector3(Double.NaN, 0.0, 0.0), EskfVector3(1.0, 1.0, 1.0)))
        }
        val singular = updater.updatePosition(
            state,
            EskfCovariance.diagonal(DoubleArray(15)),
            EskfGnssPositionMeasurement(EskfVector3(1.0, -2.0, 0.5), EskfVector3(0.0, 0.0, 0.0))
        )
        assertTrue(!singular.accepted)
        assertTrue(singular.nis.isInfinite())
    }
}
