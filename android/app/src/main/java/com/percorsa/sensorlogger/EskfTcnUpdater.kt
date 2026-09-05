package com.percorsa.sensorlogger

import kotlin.math.abs

data class EskfTcnMeasurement(
    val speedMps: Double,
    val varianceMps2: Double = 0.25,
    val timestampSeconds: Double = 0.0
)

data class EskfTcnUpdateResult(
    val state: EskfNominalState,
    val covariance: EskfCovariance,
    val accepted: Boolean,
    val nis: Double,
    val predictedSpeedMps: Double,
    val innovationMps: Double,
    val innovationVarianceMps2: Double,
    val jacobian: DoubleArray,
    val kalmanGain: DoubleArray
)

/** Standalone Python-compatible scalar TCN forward-speed ESKF update. */
class EskfTcnUpdater(
    private val nisThresholdDf1: Double = 10.827566170662733,
    private val covariancePsdTolerance: Double = -1e-7
) {

    fun update(
        state: EskfNominalState,
        covariance: EskfCovariance,
        measurement: EskfTcnMeasurement,
        phoneToVehicle: PhoneToVehicleRotation = defaultPhoneToVehicleRotation(),
        vehicleMotionObserved: Boolean
    ): EskfTcnUpdateResult {
        require(measurement.timestampSeconds.isFinite()) { "TCN timestamp must be finite" }
        require(measurement.speedMps.isFinite()) { "TCN speed must be finite" }
        require(measurement.varianceMps2.isFinite() && measurement.varianceMps2 >= 0.0) {
            "TCN measurement variance must be finite and non-negative"
        }
        require(covariance.maxAsymmetry() <= 1e-5) { "Covariance asymmetry exceeds tolerance" }

        val (predicted, h) = computeMeasurement(state, phoneToVehicle)
        val innovation = measurement.speedMps - predicted
        val p = covariance.values
        var innovationVariance = measurement.varianceMps2
        for (column in 0 until ErrorStateIndex.SIZE) {
            for (row in 0 until ErrorStateIndex.SIZE) {
                innovationVariance += h[column] * p[column][row] * h[row]
            }
        }
        require(innovationVariance.isFinite()) { "TCN innovation variance is non-finite" }

        if (!vehicleMotionObserved) {
            return rejected(state, covariance, predicted, innovation, innovationVariance, h, "Vehicle motion not observed")
        }
        if (innovationVariance <= 1e-12) {
            return rejected(state, covariance, predicted, innovation, innovationVariance, h, "Singular innovation covariance")
        }

        val nis = innovation * innovation / innovationVariance
        if (!nis.isFinite() || nis > nisThresholdDf1) {
            return rejected(state, covariance, predicted, innovation, innovationVariance, h, "NIS rejected")
                .copy(nis = nis)
        }

        val k = DoubleArray(ErrorStateIndex.SIZE) { index ->
            var value = 0.0
            for (column in 0 until ErrorStateIndex.SIZE) value += p[index][column] * h[column]
            value / innovationVariance
        }
        val delta = DoubleArray(ErrorStateIndex.SIZE) { index -> k[index] * innovation }

        val iKh = Array(ErrorStateIndex.SIZE) { row -> DoubleArray(ErrorStateIndex.SIZE) { column ->
            (if (row == column) 1.0 else 0.0) - k[row] * h[column]
        } }
        val pNew = add(
            multiply(multiply(iKh, p), transpose(iKh)),
            outerProduct(k, k, measurement.varianceMps2)
        )
        require(isFiniteMatrix(pNew)) { "TCN updated covariance contains NaN or Inf" }

        val covarianceAfterUpdate = EskfCovariance.from(symmetrize(pNew))
        val (stateAfterInjection, covarianceAfterReset) = injectErrorAndReset(
            state, delta, covarianceAfterUpdate
        )
        require(covarianceAfterReset.minimumEigenvalue() >= covariancePsdTolerance) {
            "TCN updated covariance is not PSD within tolerance"
        }

        return EskfTcnUpdateResult(
            stateAfterInjection,
            covarianceAfterReset,
            true,
            nis,
            predicted,
            innovation,
            innovationVariance,
            h,
            k
        )
    }

    /** Returns h(x) and the Python 1x15 Jacobian. */
    fun computeMeasurement(
        state: EskfNominalState,
        phoneToVehicle: PhoneToVehicleRotation = defaultPhoneToVehicleRotation()
    ): Pair<Double, DoubleArray> {
        val rPhoneWorld = state.quaternion.toRotationMatrix()
        val rWorldPhone = transpose(rPhoneWorld)
        val vPhone = multiply(rWorldPhone, state.velocity)
        val vVehicle = multiply(phoneToVehicle.values, vPhone)
        val h = DoubleArray(ErrorStateIndex.SIZE)
        val forward = doubleArrayOf(1.0, 0.0, 0.0)
        val velocityRow = multiply(forward, multiply(phoneToVehicle.values, rWorldPhone))
        for (index in 0 until 3) h[ErrorStateIndex.VELOCITY + index] = velocityRow[index]
        val attitudeRow = multiply(forward, multiply(phoneToVehicle.values, skew(vPhone)))
        for (index in 0 until 3) h[ErrorStateIndex.ATTITUDE + index] = attitudeRow[index]
        return (vVehicle[0] to h)
    }

    private fun injectErrorAndReset(
        state: EskfNominalState,
        delta: DoubleArray,
        covariance: EskfCovariance
    ): Pair<EskfNominalState, EskfCovariance> {
        val corrected = state.copy(
            position = DoubleArray(3) { state.position[it] + delta[ErrorStateIndex.POSITION + it] },
            velocity = DoubleArray(3) { state.velocity[it] + delta[ErrorStateIndex.VELOCITY + it] },
            quaternion = injectRightMultiplicativeAttitudeError(
                state.quaternion,
                delta.copyOfRange(ErrorStateIndex.ATTITUDE, ErrorStateIndex.ATTITUDE + 3)
            ),
            accelerometerBias = DoubleArray(3) { state.accelerometerBias[it] + delta[ErrorStateIndex.ACCELEROMETER_BIAS + it] },
            gyroscopeBias = DoubleArray(3) { state.gyroscopeBias[it] + delta[ErrorStateIndex.GYROSCOPE_BIAS + it] }
        )
        val reset = identity(ErrorStateIndex.SIZE)
        setBlock(
            reset,
            ErrorStateIndex.ATTITUDE,
            ErrorStateIndex.ATTITUDE,
            subtract(identity(3), scale(skew(delta.copyOfRange(6, 9)), 0.5))
        )
        return corrected to EskfCovariance.from(symmetrize(
            multiply(multiply(reset, covariance.values), transpose(reset))
        ))
    }

    private fun rejected(
        state: EskfNominalState,
        covariance: EskfCovariance,
        predicted: Double,
        innovation: Double,
        innovationVariance: Double,
        h: DoubleArray,
        reason: String
    ) = EskfTcnUpdateResult(
        state, covariance, false,
        if (reason == "NIS rejected") Double.NaN else Double.POSITIVE_INFINITY,
        predicted, innovation, innovationVariance, h, DoubleArray(ErrorStateIndex.SIZE)
    )

    companion object {
        private fun multiply(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray =
            DoubleArray(matrix.size) { row -> matrix[row].indices.sumOf { matrix[row][it] * vector[it] } }
        private fun multiply(left: DoubleArray, right: Array<DoubleArray>): DoubleArray =
            DoubleArray(right[0].size) { column -> right.indices.sumOf { left[it] * right[it][column] } }
        private fun multiply(left: Array<DoubleArray>, right: Array<DoubleArray>): Array<DoubleArray> =
            Array(left.size) { row -> DoubleArray(right[0].size) { column ->
                right.indices.sumOf { inner -> left[row][inner] * right[inner][column] }
            } }
        private fun add(left: Array<DoubleArray>, right: Array<DoubleArray>) =
            Array(left.size) { row -> DoubleArray(left[row].size) { column -> left[row][column] + right[row][column] } }
        private fun outerProduct(left: DoubleArray, right: DoubleArray, scale: Double) =
            Array(left.size) { row -> DoubleArray(right.size) { column -> scale * left[row] * right[column] } }
        private fun transpose(matrix: Array<DoubleArray>) =
            Array(matrix[0].size) { row -> DoubleArray(matrix.size) { column -> matrix[column][row] } }
        private fun identity(size: Int) =
            Array(size) { row -> DoubleArray(size) { column -> if (row == column) 1.0 else 0.0 } }
        private fun subtract(left: Array<DoubleArray>, right: Array<DoubleArray>) =
            Array(left.size) { row -> DoubleArray(left[row].size) { column -> left[row][column] - right[row][column] } }
        private fun scale(matrix: Array<DoubleArray>, factor: Double) =
            Array(matrix.size) { row -> DoubleArray(matrix[row].size) { column -> matrix[row][column] * factor } }
        private fun symmetrize(matrix: Array<DoubleArray>) =
            Array(matrix.size) { row -> DoubleArray(matrix[row].size) { column -> 0.5 * (matrix[row][column] + matrix[column][row]) } }
        private fun skew(vector: DoubleArray) = arrayOf(
            doubleArrayOf(0.0, -vector[2], vector[1]),
            doubleArrayOf(vector[2], 0.0, -vector[0]),
            doubleArrayOf(-vector[1], vector[0], 0.0)
        )
        private fun setBlock(target: Array<DoubleArray>, row: Int, column: Int, block: Array<DoubleArray>) {
            for (r in block.indices) for (c in block[r].indices) target[row + r][column + c] = block[r][c]
        }
        private fun isFiniteMatrix(matrix: Array<DoubleArray>) = matrix.all { row -> row.all(Double::isFinite) }
    }
}

private fun defaultPhoneToVehicleRotation(): PhoneToVehicleRotation =
    PhoneToVehicleRotation(arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0),
        doubleArrayOf(0.0, 1.0, 0.0),
        doubleArrayOf(0.0, 0.0, 1.0)
    ))
