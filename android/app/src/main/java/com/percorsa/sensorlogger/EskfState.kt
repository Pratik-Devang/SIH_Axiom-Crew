package com.percorsa.sensorlogger

/**
 * Python-compatible nominal ESKF state.
 *
 * Position and velocity are ENU/world vectors. The quaternion is Hamilton
 * [w, x, y, z] and maps phone-frame vectors into world/ENU. Biases are kept
 * in the phone frame, matching src/navigation/types.py.
 */
data class EskfNominalState(
    val position: DoubleArray = DoubleArray(3),
    val velocity: DoubleArray = DoubleArray(3),
    val quaternion: EskfQuaternion = EskfQuaternion.IDENTITY,
    val accelerometerBias: DoubleArray = DoubleArray(3),
    val gyroscopeBias: DoubleArray = DoubleArray(3),
    val timestampSeconds: Double = 0.0
) {
    init {
        require(position.size == 3 && velocity.size == 3) { "Position and velocity must be 3D" }
        require(accelerometerBias.size == 3 && gyroscopeBias.size == 3) { "Biases must be 3D" }
        require(position.all(Double::isFinite) && velocity.all(Double::isFinite)) { "State contains non-finite position/velocity" }
        require(accelerometerBias.all(Double::isFinite) && gyroscopeBias.all(Double::isFinite)) { "State contains non-finite bias" }
        require(timestampSeconds.isFinite()) { "State timestamp must be finite" }
    }

    fun copyArrays(): EskfNominalState = copy(
        position = position.copyOf(),
        velocity = velocity.copyOf(),
        accelerometerBias = accelerometerBias.copyOf(),
        gyroscopeBias = gyroscopeBias.copyOf()
    )
}

/** Explicit ordering of the 15-dimensional right-multiplicative error state. */
object ErrorStateIndex {
    const val POSITION = 0
    const val VELOCITY = 3
    const val ATTITUDE = 6
    const val ACCELEROMETER_BIAS = 9
    const val GYROSCOPE_BIAS = 12
    const val SIZE = 15
}

/** Immutable wrapper for the 15x15 ESKF error covariance. */
data class EskfCovariance private constructor(val values: Array<DoubleArray>) {
    init {
        require(values.size == ErrorStateIndex.SIZE && values.all { it.size == ErrorStateIndex.SIZE }) {
            "ESKF covariance must be 15x15"
        }
        require(values.all { row -> row.all(Double::isFinite) }) { "Covariance contains NaN or Inf" }
    }

    fun symmetrized(): EskfCovariance {
        val result = Array(ErrorStateIndex.SIZE) { DoubleArray(ErrorStateIndex.SIZE) }
        for (row in result.indices) {
            for (column in result[row].indices) {
                result[row][column] = 0.5 * (values[row][column] + values[column][row])
            }
        }
        return from(result)
    }

    fun maxAsymmetry(): Double {
        var maximum = 0.0
        for (row in values.indices) {
            for (column in values[row].indices) {
                maximum = maxOf(maximum, kotlin.math.abs(values[row][column] - values[column][row]))
            }
        }
        return maximum
    }

    fun copyArray(): Array<DoubleArray> = Array(values.size) { values[it].copyOf() }

    companion object {
        fun from(values: Array<DoubleArray>): EskfCovariance =
            EskfCovariance(Array(values.size) { values[it].copyOf() })

        fun diagonal(diagonal: DoubleArray): EskfCovariance {
            require(diagonal.size == ErrorStateIndex.SIZE) { "ESKF covariance diagonal must have 15 entries" }
            require(diagonal.all { it.isFinite() && it >= 0.0 }) { "Covariance diagonal must be finite and non-negative" }
            return from(Array(ErrorStateIndex.SIZE) { row ->
                DoubleArray(ErrorStateIndex.SIZE) { column -> if (row == column) diagonal[row] else 0.0 }
            })
        }
    }
}
