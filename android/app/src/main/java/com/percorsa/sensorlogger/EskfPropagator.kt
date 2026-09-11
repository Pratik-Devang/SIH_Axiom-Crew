package com.percorsa.sensorlogger

import kotlin.math.abs

/** One phone-frame IMU sample for the nominal ESKF propagator. */
data class EskfImuSample(
    val timestampSeconds: Double,
    val accelerometerPhone: EskfVector3,
    val gyroscopePhone: EskfVector3,
    val isLinearAcceleration: Boolean = false
) {
    init {
        require(timestampSeconds.isFinite()) { "IMU timestamp must be finite" }
    }
}

/**
 * Nominal strapdown INS propagation copied from src/navigation/ins.py.
 *
 * This class intentionally does not propagate covariance or apply any
 * measurement updates. It is a standalone Phase 2 foundation and is not
 * connected to NavigationController.
 */
class EskfPropagator(
    private val config: EskfConfig = EskfConfig(),
    private val gravityWorld: EskfVector3 = EskfVector3(0.0, 0.0, -9.81)
) {

    fun propagate(
        state: EskfNominalState,
        sample: EskfImuSample,
        dtSeconds: Double
    ): EskfNominalState {
        require(dtSeconds.isFinite() && dtSeconds > 0.0) {
            "Invalid timestamp delta dt=$dtSeconds"
        }
        require(dtSeconds <= config.maxPropagationDtSeconds) {
            "Unreasonable timestamp gap dt=$dtSeconds"
        }

        val q = state.quaternion.normalized()
        val accel = sample.accelerometerPhone.asArray()
        val gyro = sample.gyroscopePhone.asArray()
        val correctedAccel = DoubleArray(3) { accel[it] - state.accelerometerBias[it] }
        val correctedGyro = DoubleArray(3) { gyro[it] - state.gyroscopeBias[it] }

        val deltaTheta = DoubleArray(3) { correctedGyro[it] * dtSeconds }
        val nextQuaternion = (q * deltaQuaternionFromRotationVector(deltaTheta)).normalized()

        val specificForceWorld = phoneToWorld(
            EskfVector3.fromArray(correctedAccel),
            nextQuaternion
        ).asArray()
        val accelerationWorld = if (sample.isLinearAcceleration) {
            specificForceWorld
        } else {
            DoubleArray(3) { specificForceWorld[it] + gravityWorld.asArray()[it] }
        }

        val nextVelocity = DoubleArray(3) { index ->
            state.velocity[index] + accelerationWorld[index] * dtSeconds
        }
        val nextPosition = DoubleArray(3) { index ->
            state.position[index] + state.velocity[index] * dtSeconds +
                    0.5 * accelerationWorld[index] * dtSeconds * dtSeconds
        }

        require(nextPosition.all(Double::isFinite) && nextVelocity.all(Double::isFinite)) {
            "Propagated state contains NaN or Inf"
        }
        require(nextQuaternion.norm().isFinite() &&
                abs(nextQuaternion.norm() - 1.0) <= 1e-10) {
            "Propagated quaternion normalization failed"
        }

        return state.copy(
            position = nextPosition,
            velocity = nextVelocity,
            quaternion = nextQuaternion,
            timestampSeconds = sample.timestampSeconds
        )
    }
}
