package com.percorsa.sensorlogger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class EskfFoundationTest {

    @Test
    fun errorStateOrderingMatchesPythonReference() {
        assertEquals(0, ErrorStateIndex.POSITION)
        assertEquals(3, ErrorStateIndex.VELOCITY)
        assertEquals(6, ErrorStateIndex.ATTITUDE)
        assertEquals(9, ErrorStateIndex.ACCELEROMETER_BIAS)
        assertEquals(12, ErrorStateIndex.GYROSCOPE_BIAS)
        assertEquals(15, ErrorStateIndex.SIZE)
    }

    @Test
    fun identityQuaternionRemainsIdentityAfterNormalization() {
        assertEquals(EskfQuaternion.IDENTITY, EskfQuaternion.IDENTITY.normalized())
    }

    @Test
    fun quaternionMultiplicationAppliesKnownQuarterTurn() {
        val quarterTurnZ = EskfQuaternion(1.0 / sqrt(2.0), 0.0, 0.0, 1.0 / sqrt(2.0))
        val rotated = phoneToWorld(EskfVector3(1.0, 0.0, 0.0), quarterTurnZ)
        assertEquals(0.0, rotated.x, 1e-12)
        assertEquals(1.0, rotated.y, 1e-12)
        assertEquals(0.0, rotated.z, 1e-12)
    }

    @Test
    fun smallAngleErrorIsRightMultiplicative() {
        val corrected = injectRightMultiplicativeAttitudeError(
            EskfQuaternion.IDENTITY,
            doubleArrayOf(0.0, 0.0, 1e-6)
        )
        assertEquals(1.0, corrected.w, 1e-12)
        assertEquals(0.5e-6, corrected.z, 1e-12)
    }

    @Test
    fun frameTransformsUseExplicitPhoneVehicleWorldConventions() {
        val quarterTurnZ = EskfQuaternion(1.0 / sqrt(2.0), 0.0, 0.0, 1.0 / sqrt(2.0))
        val phoneToVehicle = PhoneToVehicleRotation(arrayOf(
            doubleArrayOf(0.0, -1.0, 0.0),
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0)
        ))
        val forwardPhone = EskfVector3(1.0, 0.0, 0.0)
        val vehicleForward = phoneToVehicle(forwardPhone, phoneToVehicle)
        assertEquals(0.0, vehicleForward.x, 1e-12)
        assertEquals(1.0, vehicleForward.y, 1e-12)
        assertEquals(0.0, vehicleForward.z, 1e-12)
        val worldFromPhone = phoneToWorld(forwardPhone, quarterTurnZ)
        assertEquals(0.0, worldFromPhone.x, 1e-12)
        assertEquals(1.0, worldFromPhone.y, 1e-12)
        assertEquals(0.0, worldFromPhone.z, 1e-12)
        val worldFromVehicle = vehicleToWorld(EskfVector3(1.0, 0.0, 0.0), quarterTurnZ, phoneToVehicle)
        assertEquals(1.0, worldFromVehicle.x, 1e-12)
        assertEquals(0.0, worldFromVehicle.y, 1e-12)
        assertEquals(0.0, worldFromVehicle.z, 1e-12)
    }

    @Test
    fun covarianceIs15By15AndSymmetrizable() {
        val matrix = Array(15) { row -> DoubleArray(15) { column -> if (row == column) 1.0 else 0.0 } }
        matrix[0][1] = 2.0
        val covariance = EskfCovariance.from(matrix)
        assertEquals(15, covariance.values.size)
        assertEquals(15, covariance.values[0].size)
        assertTrue(covariance.symmetrized().maxAsymmetry() == 0.0)
    }

    @Test
    fun defaultStateAndCovarianceAreFinite() {
        val state = EskfNominalState()
        val covariance = EskfConfig().initialCovariance()
        assertTrue(state.position.all(Double::isFinite))
        assertTrue(covariance.values.all { row -> row.all(Double::isFinite) })
    }

    @Test
    fun invalidQuaternionIsDetected() {
        assertThrows(IllegalArgumentException::class.java) {
            EskfQuaternion(0.0, 0.0, 0.0, 0.0).normalized()
        }
    }

    @Test
    fun vectorArrayRoundTripIsStable() {
        val vector = EskfVector3(1.0, -2.0, 3.0)
        assertArrayEquals(doubleArrayOf(1.0, -2.0, 3.0), vector.asArray(), 0.0)
    }
}
