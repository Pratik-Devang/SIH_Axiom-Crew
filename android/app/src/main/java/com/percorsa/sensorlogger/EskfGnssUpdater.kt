package com.percorsa.sensorlogger

import kotlin.math.abs

data class EskfGnssPositionMeasurement(
    val positionWorldEnu: EskfVector3,
    val standardDeviationM: EskfVector3,
    val timestampSeconds: Double = 0.0
)

data class EskfGnssVelocityMeasurement(
    val velocityWorldEnu: EskfVector3,
    val standardDeviationMps: EskfVector3,
    val timestampSeconds: Double = 0.0
)

data class EskfMeasurementUpdateResult(
    val state: EskfNominalState,
    val covariance: EskfCovariance,
    val accepted: Boolean,
    val nis: Double,
    val innovation: DoubleArray,
    val innovationCovariance: Array<DoubleArray>,
    val kalmanGain: Array<DoubleArray>
)

/** Python gnss_update.py position/velocity measurement updater. */
class EskfGnssUpdater(
    private val nisThresholdDf3: Double = 16.26623619623813,
    private val covariancePsdTolerance: Double = -1e-7
) {

    fun updatePosition(
        state: EskfNominalState,
        covariance: EskfCovariance,
        measurement: EskfGnssPositionMeasurement
    ): EskfMeasurementUpdateResult = updateLinear(
        state,
        covariance,
        measurement.positionWorldEnu.asArray(),
        positionJacobian(),
        noiseMatrix(measurement.standardDeviationM.asArray()),
        measurement.timestampSeconds
    )

    fun updateVelocity(
        state: EskfNominalState,
        covariance: EskfCovariance,
        measurement: EskfGnssVelocityMeasurement
    ): EskfMeasurementUpdateResult = updateLinear(
        state,
        covariance,
        measurement.velocityWorldEnu.asArray(),
        velocityJacobian(),
        noiseMatrix(measurement.standardDeviationMps.asArray()),
        measurement.timestampSeconds
    )

    private fun updateLinear(
        state: EskfNominalState,
        covariance: EskfCovariance,
        measurement: DoubleArray,
        h: Array<DoubleArray>,
        r: Array<DoubleArray>,
        timestampSeconds: Double
    ): EskfMeasurementUpdateResult {
        require(timestampSeconds.isFinite()) { "GNSS timestamp must be finite" }
        require(measurement.size == 3 && measurement.all(Double::isFinite)) { "GNSS measurement must be finite 3D" }
        require(r.size == 3 && r.all { row -> row.size == 3 && row.all(Double::isFinite) }) { "GNSS noise must be finite 3x3" }
        require(r.indices.all { r[it][it] >= 0.0 }) { "GNSS noise variances must be non-negative" }
        require(covariance.maxAsymmetry() <= 1e-5) { "Covariance asymmetry exceeds tolerance" }

        val predicted = if (h[0][ErrorStateIndex.POSITION] == 1.0) state.position else state.velocity
        val innovation = DoubleArray(3) { measurement[it] - predicted[it] }
        require(innovation.all(Double::isFinite)) { "GNSS innovation contains NaN or Inf" }

        val p = covariance.values
        val s = add(multiply(multiply(h, p), transpose(h)), r)
        require(isFiniteMatrix(s)) { "Innovation covariance contains NaN or Inf" }

        val y: DoubleArray
        val k: Array<DoubleArray>
        try {
            y = solve(s, column(innovation)).map { it[0] }.toDoubleArray()
            k = transpose(solve(s, multiply(h, transpose(p))))
        } catch (error: IllegalArgumentException) {
            return rejected(state, covariance, innovation, s, error.message ?: "Singular innovation covariance")
        }

        require(y.all(Double::isFinite) && isFiniteMatrix(k)) { "GNSS Kalman gain is non-finite" }
        val nis = innovation.indices.sumOf { innovation[it] * y[it] }
        if (!nis.isFinite() || nis > nisThresholdDf3) {
            return rejected(state, covariance, innovation, s, "NIS rejected")
                .copy(nis = nis)
        }

        val delta = multiply(k, column(innovation)).map { it[0] }.toDoubleArray()
        require(delta.all(Double::isFinite)) { "GNSS error-state correction is non-finite" }

        // Joseph form: (I-KH)P(I-KH)^T + K R K^T
        val iKh = subtract(identity(ErrorStateIndex.SIZE), multiply(k, h))
        val pNew = add(
            multiply(multiply(iKh, p), transpose(iKh)),
            multiply(multiply(k, r), transpose(k))
        )
        require(isFiniteMatrix(pNew)) { "GNSS updated covariance contains NaN or Inf" }
        val covarianceAfterUpdate = EskfCovariance.from(symmetrize(pNew))

        val (stateAfterInjection, covarianceAfterReset) = injectErrorAndReset(
            state,
            delta,
            covarianceAfterUpdate
        )
        require(covarianceAfterReset.minimumEigenvalue() >= covariancePsdTolerance) {
            "GNSS updated covariance is not PSD within tolerance"
        }

        return EskfMeasurementUpdateResult(
            stateAfterInjection,
            covarianceAfterReset,
            true,
            nis,
            innovation,
            s,
            k
        )
    }

    private fun injectErrorAndReset(
        state: EskfNominalState,
        delta: DoubleArray,
        covariance: EskfCovariance
    ): Pair<EskfNominalState, EskfCovariance> {
        require(delta.size == ErrorStateIndex.SIZE) { "ESKF correction must have 15 entries" }
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
        require(corrected.position.all(Double::isFinite) && corrected.velocity.all(Double::isFinite)) {
            "GNSS injected state contains NaN or Inf"
        }

        val reset = identity(ErrorStateIndex.SIZE)
        val attitudeReset = subtract(
            identity(3),
            scale(skew(delta.copyOfRange(ErrorStateIndex.ATTITUDE, ErrorStateIndex.ATTITUDE + 3)), 0.5)
        )
        setBlock(reset, ErrorStateIndex.ATTITUDE, ErrorStateIndex.ATTITUDE, attitudeReset)
        val covarianceReset = EskfCovariance.from(symmetrize(
            multiply(multiply(reset, covariance.values), transpose(reset))
        ))
        return corrected to covarianceReset
    }

    private fun rejected(
        state: EskfNominalState,
        covariance: EskfCovariance,
        innovation: DoubleArray,
        s: Array<DoubleArray>,
        reason: String
    ) = EskfMeasurementUpdateResult(
        state,
        covariance,
        false,
        if (reason == "NIS rejected") Double.NaN else Double.POSITIVE_INFINITY,
        innovation,
        s,
        zeros(ErrorStateIndex.SIZE, 3)
    )

    companion object {
        private fun column(vector: DoubleArray) = Array(vector.size) { row -> doubleArrayOf(vector[row]) }

        private fun velocityJacobian(): Array<DoubleArray> = zeros(3, ErrorStateIndex.SIZE).also {
            for (i in 0 until 3) it[i][ErrorStateIndex.VELOCITY + i] = 1.0
        }

        private fun positionJacobian(): Array<DoubleArray> = zeros(3, ErrorStateIndex.SIZE).also {
            for (i in 0 until 3) it[i][ErrorStateIndex.POSITION + i] = 1.0
        }

        private fun solve(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
            val n = a.size
            require(a.all { it.size == n } && b.size == n) { "Invalid linear system dimensions" }
            val left = Array(n) { a[it].copyOf() }
            val right = Array(n) { b[it].copyOf() }
            val columns = b[0].size
            for (pivotColumn in 0 until n) {
                var pivot = pivotColumn
                for (row in pivotColumn + 1 until n) {
                    if (abs(left[row][pivotColumn]) > abs(left[pivot][pivotColumn])) pivot = row
                }
                require(abs(left[pivot][pivotColumn]) > 1e-12) { "Singular innovation covariance" }
                if (pivot != pivotColumn) {
                    val tempLeft = left[pivotColumn]; left[pivotColumn] = left[pivot]; left[pivot] = tempLeft
                    val tempRight = right[pivotColumn]; right[pivotColumn] = right[pivot]; right[pivot] = tempRight
                }
                val divisor = left[pivotColumn][pivotColumn]
                for (column in pivotColumn until n) left[pivotColumn][column] /= divisor
                for (column in 0 until columns) right[pivotColumn][column] /= divisor
                for (row in 0 until n) {
                    if (row == pivotColumn) continue
                    val factor = left[row][pivotColumn]
                    for (column in pivotColumn until n) left[row][column] -= factor * left[pivotColumn][column]
                    for (column in 0 until columns) right[row][column] -= factor * right[pivotColumn][column]
                }
            }
            require(isFiniteMatrix(right)) { "Linear solve produced NaN or Inf" }
            return right
        }

        private fun subtract(left: Array<DoubleArray>, right: Array<DoubleArray>) =
            Array(left.size) { row -> DoubleArray(left[row].size) { column -> left[row][column] - right[row][column] } }

        private fun zeros(rows: Int, columns: Int) = Array(rows) { DoubleArray(columns) }
        private fun identity(size: Int) = Array(size) { row -> DoubleArray(size) { column -> if (row == column) 1.0 else 0.0 } }
        private fun diagonal(values: DoubleArray) = Array(values.size) { row -> DoubleArray(values.size) { column -> if (row == column) values[row] else 0.0 } }
        private fun noiseMatrix(standardDeviations: DoubleArray): Array<DoubleArray> {
            require(standardDeviations.size == 3 && standardDeviations.all { it.isFinite() && it >= 0.0 }) {
                "GNSS standard deviations must be finite and non-negative"
            }
            return diagonal(standardDeviations.map { it * it }.toDoubleArray())
        }
        private fun transpose(matrix: Array<DoubleArray>) = Array(matrix[0].size) { column -> DoubleArray(matrix.size) { row -> matrix[row][column] } }
        private fun multiply(left: Array<DoubleArray>, right: Array<DoubleArray>): Array<DoubleArray> {
            require(left[0].size == right.size) { "Matrix dimensions do not conform" }
            return Array(left.size) { row -> DoubleArray(right[0].size) { column ->
                var sum = 0.0
                for (inner in right.indices) sum += left[row][inner] * right[inner][column]
                sum
            } }
        }
        private fun add(left: Array<DoubleArray>, right: Array<DoubleArray>) =
            Array(left.size) { row -> DoubleArray(left[row].size) { column -> left[row][column] + right[row][column] } }
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
