package com.percorsa.sensorlogger

/** Configuration copied from Python EskfConfig and NavigatorConfig defaults. */
data class EskfConfig(
    val sigmaAccel: Double = 0.1,
    val sigmaGyro: Double = 0.01,
    val sigmaAccelBias: Double = 0.001,
    val sigmaGyroBias: Double = 0.0001,
    val initialPositionStd: Double = 5.0,
    val initialVelocityStd: Double = 0.5,
    val initialAttitudeStd: Double = 0.05,
    val initialAccelBiasStd: Double = 0.05,
    val initialGyroBiasStd: Double = 0.005,
    val covarianceSymmetryTolerance: Double = 1e-5,
    val covariancePsdTolerance: Double = -1e-7,
    val quaternionNormTolerance: Double = 1e-12,
    val maxPropagationDtSeconds: Double = 5.0
) {
    init {
        require(listOf(sigmaAccel, sigmaGyro, sigmaAccelBias, sigmaGyroBias).all { it.isFinite() && it >= 0.0 }) {
            "ESKF noise parameters must be finite and non-negative"
        }
        require(listOf(initialPositionStd, initialVelocityStd, initialAttitudeStd, initialAccelBiasStd, initialGyroBiasStd).all { it.isFinite() && it >= 0.0 }) {
            "Initial covariance standard deviations must be finite and non-negative"
        }
        require(covarianceSymmetryTolerance.isFinite() && covarianceSymmetryTolerance >= 0.0)
        require(covariancePsdTolerance.isFinite() && quaternionNormTolerance > 0.0)
        require(maxPropagationDtSeconds.isFinite() && maxPropagationDtSeconds > 0.0)
    }

    fun initialCovariance(): EskfCovariance = EskfCovariance.diagonal(doubleArrayOf(
        initialPositionStd * initialPositionStd,
        initialPositionStd * initialPositionStd,
        initialPositionStd * initialPositionStd,
        initialVelocityStd * initialVelocityStd,
        initialVelocityStd * initialVelocityStd,
        initialVelocityStd * initialVelocityStd,
        initialAttitudeStd * initialAttitudeStd,
        initialAttitudeStd * initialAttitudeStd,
        initialAttitudeStd * initialAttitudeStd,
        initialAccelBiasStd * initialAccelBiasStd,
        initialAccelBiasStd * initialAccelBiasStd,
        initialAccelBiasStd * initialAccelBiasStd,
        initialGyroBiasStd * initialGyroBiasStd,
        initialGyroBiasStd * initialGyroBiasStd,
        initialGyroBiasStd * initialGyroBiasStd
    ))
}
