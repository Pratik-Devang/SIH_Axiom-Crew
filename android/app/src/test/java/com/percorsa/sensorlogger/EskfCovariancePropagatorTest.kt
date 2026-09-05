package com.percorsa.sensorlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class EskfCovariancePropagatorTest {

    private val config = EskfConfig()
    private val covariancePropagator = EskfCovariancePropagator(config)

    @Test
    fun knownJacobianCaseMatchesPythonReference() {
        val step = covariancePropagator.propagate(
            EskfCovariance.diagonal(DoubleArray(15) { 0.1 }),
            referenceState,
            referenceSample,
            0.1
        )

        assertEquals(0.12092215233668224, step.continuousF[3][6], 1e-12)
        assertEquals(8.518231424204805, step.continuousF[3][7], 1e-12)
        assertEquals(-0.9137504774570886, step.continuousF[3][9], 1e-12)
        assertEquals(-0.9137504774570886, step.noiseMappingG[3][0], 1e-12)
        assertEquals(-0.0014894287335941395, step.discreteF[3][6], 1e-12)
        assertEquals(0.0010000000000000002, step.discreteQ[3][3], 1e-15)
        assertEquals(0.10118615320764542, step.covariance.values[0][0], 1e-12)
        assertEquals(0.1756652708729157, step.covariance.values[3][3], 1e-12)
        assertEquals(0.100000001, step.covariance.values[12][12], 1e-12)
    }

    @Test
    fun covarianceIsSymmetricAfterOneAndManyPropagations() {
        var covariance = EskfCovariance.diagonal(DoubleArray(15) { 0.1 })
        repeat(101) {
            covariance = covariancePropagator.propagate(
                covariance, referenceState, referenceSample, 0.01
            ).covariance
            assertTrue(covariance.maxAsymmetry() <= config.covarianceSymmetryTolerance)
        }
    }

    @Test
    fun processNoiseIncreasesUncertaintyAndScalesWithDt() {
        val initial = EskfCovariance.diagonal(DoubleArray(15))
        val oneStep = covariancePropagator.propagate(initial, referenceState, referenceSample, 0.1)
        val smallerStep = covariancePropagator.propagate(initial, referenceState, referenceSample, 0.05)

        assertTrue(oneStep.discreteQ[3][3] > 0.0)
        assertTrue(oneStep.discreteQ[6][6] > 0.0)
        assertTrue(oneStep.covariance.values[9][9] > initial.values[9][9])
        assertTrue(smallerStep.discreteQ[3][3] < oneStep.discreteQ[3][3])
    }

    @Test
    fun attitudeAndBiasUncertaintyPropagate() {
        val step = covariancePropagator.propagate(
            EskfCovariance.diagonal(DoubleArray(15) { 0.1 }),
            referenceState,
            referenceSample,
            0.1
        )
        assertTrue(step.covariance.values[6][6] > 0.1)
        assertTrue(step.covariance.values[9][9] > 0.1)
        assertTrue(step.covariance.values[12][12] > 0.1)
    }

    @Test
    fun covarianceRemainsFiniteAndPsdOverLongSequence() {
        var covariance = EskfCovariance.diagonal(DoubleArray(15) { 0.1 })
        repeat(250) {
            covariance = covariancePropagator.propagate(
                covariance, referenceState, referenceSample, 0.01
            ).covariance
        }
        assertTrue(covariance.values.all { row -> row.all(Double::isFinite) })
        assertTrue(covariance.minimumEigenvalue() >= config.covariancePsdTolerance)
    }

    @Test
    fun invalidCovarianceAndCatastrophicAsymmetryAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            EskfCovariance.from(Array(15) { row ->
                DoubleArray(15) { column -> if (row == 0 && column == 1) Double.NaN else 0.0 }
            })
        }

        val asymmetric = Array(15) { row -> DoubleArray(15) { column -> if (row == column) 0.1 else 0.0 } }
        asymmetric[0][1] = 1.0
        assertThrows(IllegalArgumentException::class.java) {
            covariancePropagator.propagate(
                EskfCovariance.from(asymmetric), referenceState, referenceSample, 0.1
            )
        }
    }

    @Test
    fun invalidPropagationInputsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            covariancePropagator.propagate(
                EskfCovariance.diagonal(DoubleArray(15) { 0.1 }), referenceState, referenceSample, 0.0
            )
        }
        assertFalse(EskfCovariance.diagonal(DoubleArray(15) { 0.1 }).values.any { row -> row.any { !it.isFinite() } })
    }

    private val referenceState = EskfNominalState(
        position = doubleArrayOf(1.0, -2.0, 0.5),
        velocity = doubleArrayOf(2.0, -1.0, 0.3),
        quaternion = EskfQuaternion(0.9659258263, 0.1, 0.2, 0.05),
        accelerometerBias = doubleArrayOf(0.01, -0.02, 0.03),
        gyroscopeBias = doubleArrayOf(0.001, -0.002, 0.003),
        timestampSeconds = 0.4
    )

    private val referenceSample = EskfImuSample(
        timestampSeconds = 0.5,
        accelerometerPhone = EskfVector3(0.8, -1.1, 9.7),
        gyroscopePhone = EskfVector3(0.1, -0.2, 0.3),
        isLinearAcceleration = false
    )
}
