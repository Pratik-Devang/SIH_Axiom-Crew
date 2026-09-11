package com.percorsa.sensorlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class EskfPropagatorTest {

    private val propagator = EskfPropagator()

    @Test
    fun zeroAngularRateKeepsQuaternionUnchanged() {
        val state = state()
        val next = propagator.propagate(state, sample(1.0, gyro = EskfVector3(0.0, 0.0, 0.0)), 1.0)
        assertEquals(state.quaternion, next.quaternion)
    }

    @Test
    fun constantAngularRateProducesNormalizedPredictableQuaternion() {
        val next = propagator.propagate(
            state(), sample(0.2, gyro = EskfVector3(0.0, 0.0, 1.0)), 0.2
        )
        assertEquals(cos(0.1), next.quaternion.w, 1e-12)
        assertEquals(sin(0.1), next.quaternion.z, 1e-12)
        assertEquals(1.0, next.quaternion.norm(), 1e-12)
    }

    @Test
    fun rawZeroSpecificForceProducesWorldGravityAcceleration() {
        val next = propagator.propagate(
            state(), sample(1.0, accel = EskfVector3(0.0, 0.0, 0.0)), 1.0
        )
        assertEquals(-9.81, next.velocity[2], 1e-12)
        assertEquals(-4.905, next.position[2], 1e-12)
    }

    @Test
    fun rawGravityReactionProducesStationaryAcceleration() {
        val next = propagator.propagate(
            state(), sample(1.0, accel = EskfVector3(0.0, 0.0, 9.81)), 1.0
        )
        assertEquals(0.0, next.velocity[2], 1e-12)
        assertEquals(0.0, next.position[2], 1e-12)
    }

    @Test
    fun constantWorldLinearAccelerationUpdatesVelocityAndPosition() {
        val next = propagator.propagate(
            state(), sample(0.5, accel = EskfVector3(1.0, 2.0, 3.0), linear = true), 0.5
        )
        assertArrayEquals(doubleArrayOf(0.5, 1.0, 1.5), next.velocity)
        assertArrayEquals(doubleArrayOf(0.125, 0.25, 0.375), next.position)
    }

    @Test
    fun accelerometerBiasIsSubtractedInPhoneFrame() {
        val next = propagator.propagate(
            state(accelBias = EskfVector3(1.0, 0.0, 0.0)),
            sample(1.0, accel = EskfVector3(2.0, 0.0, 0.0), linear = true), 1.0
        )
        assertEquals(1.0, next.velocity[0], 1e-12)
        assertEquals(0.5, next.position[0], 1e-12)
    }

    @Test
    fun gyroBiasIsSubtractedBeforeAttitudePropagation() {
        val next = propagator.propagate(
            state(gyroBias = EskfVector3(0.0, 0.0, 0.5)),
            sample(0.2, gyro = EskfVector3(0.0, 0.0, 1.0)), 0.2
        )
        assertEquals(cos(0.05), next.quaternion.w, 1e-12)
        assertEquals(sin(0.05), next.quaternion.z, 1e-12)
    }

    @Test
    fun invalidDtIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            propagator.propagate(state(), sample(1.0), 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            propagator.propagate(state(), sample(6.0), 6.0)
        }
    }

    @Test
    fun nonFiniteImuIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            propagator.propagate(
                state(), sample(1.0, accel = EskfVector3(Double.NaN, 0.0, 0.0)), 1.0
            )
        }
    }

    @Test
    fun longSequenceKeepsStateFiniteAndQuaternionNormalized() {
        var state = state()
        repeat(1000) { index ->
            state = propagator.propagate(
                state,
                sample((index + 1) * 0.01, accel = EskfVector3(0.0, 0.0, 9.81), gyro = EskfVector3(0.001, 0.0, 0.0)),
                0.01
            )
        }
        assertTrue(state.position.all(Double::isFinite))
        assertTrue(state.velocity.all(Double::isFinite))
        assertEquals(1.0, state.quaternion.norm(), 1e-12)
    }

    private fun state(
        accelBias: EskfVector3 = EskfVector3(0.0, 0.0, 0.0),
        gyroBias: EskfVector3 = EskfVector3(0.0, 0.0, 0.0)
    ) = EskfNominalState(
        accelerometerBias = accelBias.asArray(),
        gyroscopeBias = gyroBias.asArray()
    )

    private fun sample(
        timestamp: Double,
        accel: EskfVector3 = EskfVector3(0.0, 0.0, 0.0),
        gyro: EskfVector3 = EskfVector3(0.0, 0.0, 0.0),
        linear: Boolean = false
    ) = EskfImuSample(timestamp, accel, gyro, linear)

    private fun assertArrayEquals(expected: DoubleArray, actual: DoubleArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals(expected[it], actual[it], 1e-12) }
    }
}
