package com.percorsa.sensorlogger

import kotlin.math.abs

data class EskfNhcMeasurement(
    val stdLateralMps: Double = 0.1,
    val stdVerticalMps: Double = 0.1,
    val timestampSeconds: Double = 0.0
)

data class EskfZuptMeasurement(
    val stdVelocityMps: Double = 0.01,
    val timestampSeconds: Double = 0.0
)

data class EskfConstraintUpdateResult(
    val state: EskfNominalState,
    val covariance: EskfCovariance,
    val accepted: Boolean,
    val nis: Double,
    val predictedMeasurement: DoubleArray,
    val innovation: DoubleArray,
    val innovationCovariance: Array<DoubleArray>,
    val jacobian: Array<DoubleArray>,
    val kalmanGain: Array<DoubleArray>
)

/** Python-compatible standalone NHC and ZUPT ESKF updates. */
class EskfNhcUpdater(
    private val nisThresholdDf2: Double = 13.815510557964274,
    private val covariancePsdTolerance: Double = -1e-7
) {
    fun update(
        state: EskfNominalState,
        covariance: EskfCovariance,
        measurement: EskfNhcMeasurement,
        phoneToVehicle: PhoneToVehicleRotation = defaultPhoneToVehicleRotation(),
        enabled: Boolean = true
    ): EskfConstraintUpdateResult {
        require(measurement.timestampSeconds.isFinite()) { "NHC timestamp must be finite" }
        require(measurement.stdLateralMps.isFinite() && measurement.stdLateralMps >= 0.0)
        require(measurement.stdVerticalMps.isFinite() && measurement.stdVerticalMps >= 0.0)
        val (predicted, h) = computeMeasurement(state, phoneToVehicle)
        val r = arrayOf(doubleArrayOf(measurement.stdLateralMps * measurement.stdLateralMps, 0.0), doubleArrayOf(0.0, measurement.stdVerticalMps * measurement.stdVerticalMps))
        return EskfConstraintUpdateMath.update(state, covariance, predicted, doubleArrayOf(0.0, 0.0), h, r, enabled, nisThresholdDf2, covariancePsdTolerance)
    }

    fun computeMeasurement(
        state: EskfNominalState,
        phoneToVehicle: PhoneToVehicleRotation = defaultPhoneToVehicleRotation()
    ): Pair<DoubleArray, Array<DoubleArray>> {
        val rWorldPhone = transposeMatrix(state.quaternion.toRotationMatrix())
        val vPhone = matvec(rWorldPhone, state.velocity)
        val r = phoneToVehicle.values
        val e = arrayOf(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val predicted = matvec(matmul(e, r), vPhone)
        val h = zerosMatrix(2, 15)
        setBlockMatrix(h, 0, ErrorStateIndex.VELOCITY, matmul(matmul(e, r), rWorldPhone))
        setBlockMatrix(h, 0, ErrorStateIndex.ATTITUDE, matmul(matmul(e, r), skewMatrix(vPhone)))
        return predicted to h
    }
}

class EskfZuptUpdater(
    private val nisThresholdDf3: Double = 16.26623619623813,
    private val covariancePsdTolerance: Double = -1e-7
) {
    fun update(
        state: EskfNominalState,
        covariance: EskfCovariance,
        measurement: EskfZuptMeasurement,
        enabled: Boolean
    ): EskfConstraintUpdateResult {
        require(measurement.timestampSeconds.isFinite()) { "ZUPT timestamp must be finite" }
        require(measurement.stdVelocityMps.isFinite() && measurement.stdVelocityMps >= 0.0)
        val predicted = state.velocity.copyOf()
        val h = zerosMatrix(3, 15)
        for (i in 0 until 3) h[i][ErrorStateIndex.VELOCITY + i] = 1.0
        val variance = measurement.stdVelocityMps * measurement.stdVelocityMps
        val r = Array(3) { row -> DoubleArray(3) { column -> if (row == column) variance else 0.0 } }
        return EskfConstraintUpdateMath.update(state, covariance, predicted, DoubleArray(3), h, r, enabled, nisThresholdDf3, covariancePsdTolerance)
    }
}

private object EskfConstraintUpdateMath {
    fun update(
        state: EskfNominalState,
        covariance: EskfCovariance,
        predicted: DoubleArray,
        measurement: DoubleArray,
        h: Array<DoubleArray>,
        r: Array<DoubleArray>,
        enabled: Boolean,
        nisThreshold: Double,
        covariancePsdTolerance: Double
    ): EskfConstraintUpdateResult {
        require(covariance.maxAsymmetry() <= 1e-5) { "Covariance asymmetry exceeds tolerance" }
        require(predicted.all(Double::isFinite) && measurement.all(Double::isFinite)) { "Constraint measurement is non-finite" }
        require(r.size == predicted.size && r.all { row -> row.size == predicted.size && row.all(Double::isFinite) }) { "Constraint covariance is invalid" }
        require(r.indices.all { r[it][it] >= 0.0 }) { "Constraint variances must be non-negative" }
        val innovation = DoubleArray(predicted.size) { measurement[it] - predicted[it] }
        val p = covariance.values
        val s = add(multiply(multiply(h, p), transpose(h)), r)
        require(isFiniteMatrix(s)) { "Constraint innovation covariance is non-finite" }
        if (!enabled) return result(state, covariance, false, Double.POSITIVE_INFINITY, predicted, innovation, s, h, h.size)
        val y: DoubleArray
        val k: Array<DoubleArray>
        try {
            y = solve(s, column(innovation)).map { it[0] }.toDoubleArray()
            k = transpose(solve(s, multiply(h, transpose(p))))
        } catch (_: IllegalArgumentException) {
            return result(state, covariance, false, Double.POSITIVE_INFINITY, predicted, innovation, s, h, h.size)
        }
        require(y.all(Double::isFinite) && isFiniteMatrix(k)) { "Constraint Kalman gain is non-finite" }
        val nis = innovation.indices.sumOf { innovation[it] * y[it] }
        if (!nis.isFinite() || nis > nisThreshold) return result(state, covariance, false, nis, predicted, innovation, s, h, h.size)
        val delta = multiply(k, column(innovation)).map { it[0] }.toDoubleArray()
        val iKh = subtract(identity(15), multiply(k, h))
        val joseph = add(multiply(multiply(iKh, p), transpose(iKh)), multiply(multiply(k, r), transpose(k)))
        val afterUpdate = EskfCovariance.from(symmetrize(joseph))
        val injected = inject(state, delta, afterUpdate)
        require(injected.second.minimumEigenvalue() >= covariancePsdTolerance) { "Constraint covariance is not PSD" }
        return EskfConstraintUpdateResult(injected.first, injected.second, true, nis, predicted, innovation, s, h, k)
    }

    private fun result(state: EskfNominalState, covariance: EskfCovariance, accepted: Boolean, nis: Double, predicted: DoubleArray, innovation: DoubleArray, s: Array<DoubleArray>, h: Array<DoubleArray>, rows: Int) =
        EskfConstraintUpdateResult(state, covariance, accepted, nis, predicted, innovation, s, h, zeros(15, rows))

    private fun inject(state: EskfNominalState, delta: DoubleArray, covariance: EskfCovariance): Pair<EskfNominalState, EskfCovariance> {
        val corrected = state.copy(
            position = DoubleArray(3) { state.position[it] + delta[it] },
            velocity = DoubleArray(3) { state.velocity[it] + delta[3 + it] },
            quaternion = injectRightMultiplicativeAttitudeError(state.quaternion, delta.copyOfRange(6, 9)),
            accelerometerBias = DoubleArray(3) { state.accelerometerBias[it] + delta[9 + it] },
            gyroscopeBias = DoubleArray(3) { state.gyroscopeBias[it] + delta[12 + it] }
        )
        val reset = identity(15)
        setBlock(reset, 6, 6, subtract(identity(3), scale(skew(delta.copyOfRange(6, 9)), 0.5)))
        return corrected to EskfCovariance.from(symmetrize(multiply(multiply(reset, covariance.values), transpose(reset))))
    }

    private fun solve(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val n = a.size; val left = Array(n) { a[it].copyOf() }; val right = Array(n) { b[it].copyOf() }; val cols = b[0].size
        for (pc in 0 until n) {
            var pivot = pc
            for (row in pc + 1 until n) if (abs(left[row][pc]) > abs(left[pivot][pc])) pivot = row
            require(abs(left[pivot][pc]) > 1e-12) { "Singular innovation covariance" }
            if (pivot != pc) { val x = left[pc]; left[pc] = left[pivot]; left[pivot] = x; val y = right[pc]; right[pc] = right[pivot]; right[pivot] = y }
            val divisor = left[pc][pc]
            for (c in pc until n) left[pc][c] /= divisor
            for (c in 0 until cols) right[pc][c] /= divisor
            for (row in 0 until n) if (row != pc) { val factor = left[row][pc]; for (c in pc until n) left[row][c] -= factor * left[pc][c]; for (c in 0 until cols) right[row][c] -= factor * right[pc][c] }
        }
        require(isFiniteMatrix(right)) { "Linear solve produced non-finite result" }; return right
    }

    private fun column(v: DoubleArray) = Array(v.size) { doubleArrayOf(v[it]) }
    private fun zeros(rows: Int, cols: Int) = Array(rows) { DoubleArray(cols) }
    private fun identity(n: Int) = Array(n) { r -> DoubleArray(n) { c -> if (r == c) 1.0 else 0.0 } }
    private fun transpose(a: Array<DoubleArray>) = Array(a[0].size) { r -> DoubleArray(a.size) { c -> a[c][r] } }
    private fun multiply(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(a.size) { r -> DoubleArray(b[0].size) { c -> b.indices.sumOf { k -> a[r][k] * b[k][c] } } }
    private fun multiply(a: Array<DoubleArray>, v: DoubleArray) = DoubleArray(a.size) { r -> a[r].indices.sumOf { c -> a[r][c] * v[c] } }
    private fun multiply(a: Array<DoubleArray>, b: Array<DoubleArray>, unused: Int = 0) = multiply(a, b)
    private fun add(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(a.size) { r -> DoubleArray(a[r].size) { c -> a[r][c] + b[r][c] } }
    private fun subtract(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(a.size) { r -> DoubleArray(a[r].size) { c -> a[r][c] - b[r][c] } }
    private fun scale(a: Array<DoubleArray>, f: Double) = Array(a.size) { r -> DoubleArray(a[r].size) { c -> a[r][c] * f } }
    private fun symmetrize(a: Array<DoubleArray>) = Array(a.size) { r -> DoubleArray(a[r].size) { c -> 0.5 * (a[r][c] + a[c][r]) } }
    private fun skew(v: DoubleArray) = arrayOf(doubleArrayOf(0.0, -v[2], v[1]), doubleArrayOf(v[2], 0.0, -v[0]), doubleArrayOf(-v[1], v[0], 0.0))
    private fun setBlock(target: Array<DoubleArray>, row: Int, col: Int, block: Array<DoubleArray>) { for (r in block.indices) for (c in block[r].indices) target[row + r][col + c] = block[r][c] }
    private fun isFiniteMatrix(a: Array<DoubleArray>) = a.all { it.all(Double::isFinite) }
}

private fun defaultPhoneToVehicleRotation(): PhoneToVehicleRotation = PhoneToVehicleRotation(arrayOf(
    doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0)
))

private fun matmul(left: Array<DoubleArray>, right: Array<DoubleArray>): Array<DoubleArray> = Array(left.size) { r -> DoubleArray(right[0].size) { c -> right.indices.sumOf { k -> left[r][k] * right[k][c] } } }
private fun matvec(left: Array<DoubleArray>, vector: DoubleArray): DoubleArray = DoubleArray(left.size) { r -> left[r].indices.sumOf { c -> left[r][c] * vector[c] } }
private fun transposeMatrix(matrix: Array<DoubleArray>): Array<DoubleArray> = Array(matrix[0].size) { r -> DoubleArray(matrix.size) { c -> matrix[c][r] } }
private fun zerosMatrix(rows: Int, columns: Int) = Array(rows) { DoubleArray(columns) }
private fun skewMatrix(v: DoubleArray) = arrayOf(doubleArrayOf(0.0, -v[2], v[1]), doubleArrayOf(v[2], 0.0, -v[0]), doubleArrayOf(-v[1], v[0], 0.0))
private fun setBlockMatrix(target: Array<DoubleArray>, row: Int, column: Int, block: Array<DoubleArray>) { for (r in block.indices) for (c in block[r].indices) target[row + r][column + c] = block[r][c] }
