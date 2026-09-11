package com.percorsa.sensorlogger

import kotlin.math.abs
import kotlin.math.sqrt

/** Matrices produced by one Python-compatible covariance prediction step. */
data class EskfCovarianceStep(
    val covariance: EskfCovariance,
    val continuousF: Array<DoubleArray>,
    val noiseMappingG: Array<DoubleArray>,
    val discreteF: Array<DoubleArray>,
    val discreteQ: Array<DoubleArray>
)

/**
 * Phase 3 covariance propagation for the 15-state ESKF.
 *
 * The caller supplies the nominal state after [EskfPropagator.propagate],
 * matching RouteAwareNavigator's Python ordering: nominal propagation first,
 * covariance prediction second. This class does not apply measurements and
 * is not connected to the Android runtime.
 */
class EskfCovariancePropagator(private val config: EskfConfig = EskfConfig()) {

    fun continuousF(state: EskfNominalState, sample: EskfImuSample): Array<DoubleArray> {
        val fPhone = DoubleArray(3) { sample.accelerometerPhone.asArray()[it] - state.accelerometerBias[it] }
        val omegaPhone = DoubleArray(3) { sample.gyroscopePhone.asArray()[it] - state.gyroscopeBias[it] }
        val rotation = state.quaternion.toRotationMatrix()
        val fSkew = skew(fPhone)
        val omegaSkew = skew(omegaPhone)
        val result = zeros(15, 15)

        setBlock(result, 0, 3, identity(3))
        setBlock(result, 3, 6, scale(multiply(rotation, fSkew), -1.0))
        setBlock(result, 3, 9, scale(rotation, -1.0))
        setBlock(result, 6, 6, scale(omegaSkew, -1.0))
        setBlock(result, 6, 12, scale(identity(3), -1.0))
        require(isFiniteMatrix(result)) { "F_c contains NaN or Inf" }
        return result
    }

    fun noiseMappingG(state: EskfNominalState): Array<DoubleArray> {
        val result = zeros(15, 12)
        val rotation = state.quaternion.toRotationMatrix()
        setBlock(result, 3, 0, scale(rotation, -1.0))
        setBlock(result, 6, 3, scale(identity(3), -1.0))
        setBlock(result, 9, 6, identity(3))
        setBlock(result, 12, 9, identity(3))
        require(isFiniteMatrix(result)) { "G_c contains NaN or Inf" }
        return result
    }

    fun propagate(
        covariance: EskfCovariance,
        stateAfterNominalPropagation: EskfNominalState,
        sample: EskfImuSample,
        dtSeconds: Double
    ): EskfCovarianceStep {
        require(dtSeconds.isFinite() && dtSeconds > 0.0 && dtSeconds <= config.maxPropagationDtSeconds) {
            "Invalid covariance propagation dt=$dtSeconds"
        }
        require(covariance.maxAsymmetry() <= config.covarianceSymmetryTolerance) {
            "Covariance asymmetry exceeds configured tolerance"
        }

        val fC = continuousF(stateAfterNominalPropagation, sample)
        val gC = noiseMappingG(stateAfterNominalPropagation)
        val qC = diagonal(doubleArrayOf(
            config.sigmaAccel * config.sigmaAccel,
            config.sigmaAccel * config.sigmaAccel,
            config.sigmaAccel * config.sigmaAccel,
            config.sigmaGyro * config.sigmaGyro,
            config.sigmaGyro * config.sigmaGyro,
            config.sigmaGyro * config.sigmaGyro,
            config.sigmaAccelBias * config.sigmaAccelBias,
            config.sigmaAccelBias * config.sigmaAccelBias,
            config.sigmaAccelBias * config.sigmaAccelBias,
            config.sigmaGyroBias * config.sigmaGyroBias,
            config.sigmaGyroBias * config.sigmaGyroBias,
            config.sigmaGyroBias * config.sigmaGyroBias
        ))

        val fC2 = multiply(fC, fC)
        val fK = add(add(identity(15), scale(fC, dtSeconds)), scale(fC2, 0.5 * dtSeconds * dtSeconds))
        val qD = multiply(multiply(gC, scale(qC, dtSeconds)), transpose(gC))
        val pNextRaw = add(
            multiply(multiply(fK, covariance.values), transpose(fK)),
            qD
        )
        require(isFiniteMatrix(fK)) { "F_k contains NaN or Inf" }
        require(isFiniteMatrix(qD)) { "Q_d contains NaN or Inf" }
        require(isFiniteMatrix(pNextRaw)) { "Propagated covariance contains NaN or Inf" }

        val pNext = EskfCovariance.from(symmetrize(pNextRaw))
        require(pNext.minimumEigenvalue() >= config.covariancePsdTolerance) {
            "Propagated covariance is not positive semidefinite within tolerance"
        }
        return EskfCovarianceStep(pNext, fC, gC, fK, qD)
    }

    companion object {
        internal fun zeros(rows: Int, columns: Int) = Array(rows) { DoubleArray(columns) }

        internal fun identity(size: Int): Array<DoubleArray> = Array(size) { row ->
            DoubleArray(size) { column -> if (row == column) 1.0 else 0.0 }
        }

        internal fun diagonal(values: DoubleArray): Array<DoubleArray> = Array(values.size) { row ->
            DoubleArray(values.size) { column -> if (row == column) values[row] else 0.0 }
        }

        internal fun transpose(matrix: Array<DoubleArray>): Array<DoubleArray> =
            Array(matrix[0].size) { column -> DoubleArray(matrix.size) { row -> matrix[row][column] } }

        internal fun multiply(left: Array<DoubleArray>, right: Array<DoubleArray>): Array<DoubleArray> {
            require(left[0].size == right.size) { "Matrix dimensions do not conform" }
            return Array(left.size) { row ->
                DoubleArray(right[0].size) { column ->
                    var sum = 0.0
                    for (inner in right.indices) sum += left[row][inner] * right[inner][column]
                    sum
                }
            }
        }

        internal fun add(left: Array<DoubleArray>, right: Array<DoubleArray>): Array<DoubleArray> =
            Array(left.size) { row -> DoubleArray(left[row].size) { column -> left[row][column] + right[row][column] } }

        internal fun scale(matrix: Array<DoubleArray>, factor: Double): Array<DoubleArray> =
            Array(matrix.size) { row -> DoubleArray(matrix[row].size) { column -> matrix[row][column] * factor } }

        internal fun setBlock(target: Array<DoubleArray>, row: Int, column: Int, block: Array<DoubleArray>) {
            for (r in block.indices) for (c in block[r].indices) target[row + r][column + c] = block[r][c]
        }

        internal fun skew(vector: DoubleArray): Array<DoubleArray> = arrayOf(
            doubleArrayOf(0.0, -vector[2], vector[1]),
            doubleArrayOf(vector[2], 0.0, -vector[0]),
            doubleArrayOf(-vector[1], vector[0], 0.0)
        )

        internal fun symmetrize(matrix: Array<DoubleArray>): Array<DoubleArray> =
            Array(matrix.size) { row -> DoubleArray(matrix[row].size) { column -> 0.5 * (matrix[row][column] + matrix[column][row]) } }

        internal fun isFiniteMatrix(matrix: Array<DoubleArray>): Boolean =
            matrix.all { row -> row.all(Double::isFinite) }

        /** Jacobi eigenvalue sweep, used only for deterministic PSD validation. */
        internal fun minimumEigenvalue(input: Array<DoubleArray>): Double {
            val matrix = symmetrize(input)
            val n = matrix.size
            repeat(n * n * 20) {
                var p = 0
                var q = 0
                var largest = 0.0
                for (row in 0 until n) for (column in row + 1 until n) {
                    if (abs(matrix[row][column]) > largest) {
                        largest = abs(matrix[row][column]); p = row; q = column
                    }
                }
                if (largest < 1e-12) {
                    var minimum = matrix[0][0]
                    for (index in 1 until n) minimum = minOf(minimum, matrix[index][index])
                    return minimum
                }
                val angle = 0.5 * kotlin.math.atan2(2.0 * matrix[p][q], matrix[q][q] - matrix[p][p])
                val cosine = kotlin.math.cos(angle)
                val sine = kotlin.math.sin(angle)
                for (index in 0 until n) {
                    val mip = matrix[index][p]
                    val miq = matrix[index][q]
                    matrix[index][p] = cosine * mip - sine * miq
                    matrix[index][q] = sine * mip + cosine * miq
                }
                for (index in 0 until n) {
                    val mpi = matrix[p][index]
                    val mqi = matrix[q][index]
                    matrix[p][index] = cosine * mpi - sine * mqi
                    matrix[q][index] = sine * mpi + cosine * mqi
                }
            }
            return matrix.indices.minOf { matrix[it][it] }
        }
    }
}

fun EskfCovariance.minimumEigenvalue(): Double =
    EskfCovariancePropagator.minimumEigenvalue(values)
