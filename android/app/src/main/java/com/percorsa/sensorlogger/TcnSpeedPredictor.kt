package com.percorsa.sensorlogger

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import org.json.JSONObject
import java.nio.FloatBuffer

/** Runs the bundled deterministic TCN and returns forward speed in m/s. */
class TcnSpeedPredictor(context: Context) : AutoCloseable {

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val means: FloatArray
    private val standardDeviations: FloatArray

    init {
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        session = OrtSession.SessionOptions().use { options ->
            environment.createSession(modelBytes, options)
        }
        inputName = session.inputNames.first()
        validateModelContract()

        val normalization = context.assets.open(NORMALIZATION_ASSET).bufferedReader().use {
            JSONObject(it.readText())
        }
        val columns = normalization.getJSONArray("columns")
        require(columns.length() == FEATURE_NAMES.size) {
            "Expected ${FEATURE_NAMES.size} normalization columns, got ${columns.length()}"
        }
        FEATURE_NAMES.forEachIndexed { index, expected ->
            require(columns.getString(index) == expected) {
                "Normalization column $index must be $expected, got ${columns.getString(index)}"
            }
        }
        means = normalization.getJSONArray("mean").toFloatArray()
        standardDeviations = normalization.getJSONArray("std").toFloatArray()

        require(means.size == TcnInputBuffer.FEATURE_COUNT) {
            "Expected ${TcnInputBuffer.FEATURE_COUNT} normalization means, got ${means.size}"
        }
        require(standardDeviations.size == TcnInputBuffer.FEATURE_COUNT) {
            "Expected ${TcnInputBuffer.FEATURE_COUNT} normalization standard deviations, got ${standardDeviations.size}"
        }
        require(standardDeviations.all { it.isFinite() && it > 0f }) {
            "TCN normalization standard deviations must be finite and positive"
        }
    }

    @Synchronized
    fun predictSpeedMps(channelMajorFeatures: Array<FloatArray>): Float {
        require(channelMajorFeatures.size == TcnInputBuffer.FEATURE_COUNT) {
            "TCN expects ${TcnInputBuffer.FEATURE_COUNT} channels"
        }
        require(channelMajorFeatures.all { it.size == TcnInputBuffer.DEFAULT_CAPACITY }) {
            "TCN expects ${TcnInputBuffer.DEFAULT_CAPACITY} time samples per channel"
        }

        val normalized = FloatArray(
            TcnInputBuffer.FEATURE_COUNT * TcnInputBuffer.DEFAULT_CAPACITY
        )
        for (channel in 0 until TcnInputBuffer.FEATURE_COUNT) {
            for (time in 0 until TcnInputBuffer.DEFAULT_CAPACITY) {
                val raw = channelMajorFeatures[channel][time]
                require(raw.isFinite()) { "TCN input contains a non-finite value" }
                normalized[channel * TcnInputBuffer.DEFAULT_CAPACITY + time] =
                    (raw - means[channel]) / standardDeviations[channel]
            }
        }

        val inputShape = longArrayOf(
            1L,
            TcnInputBuffer.FEATURE_COUNT.toLong(),
            TcnInputBuffer.DEFAULT_CAPACITY.toLong()
        )
        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(normalized),
            inputShape
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val output = result[0].value
                val speed = when (output) {
                    is FloatArray -> output.firstOrNull()
                    is Array<*> -> (output.firstOrNull() as? FloatArray)?.firstOrNull()
                    else -> null
                } ?: error("Unexpected TCN output type: ${output::class.java.simpleName}")

                require(speed.isFinite()) { "TCN produced a non-finite speed" }
                return speed.coerceIn(MIN_SPEED_MPS, MAX_SPEED_MPS)
            }
        }
    }

    override fun close() {
        session.close()
    }

    private fun validateModelContract() {
        require(session.inputNames.size == 1) { "TCN must expose exactly one input" }
        require(session.outputNames.size == 1) { "TCN must expose exactly one output" }

        val inputInfo = session.inputInfo[inputName]?.info as? TensorInfo
            ?: error("TCN input must be a tensor")
        val shape = inputInfo.shape
        require(inputInfo.type == OnnxJavaType.FLOAT) { "TCN input must use Float32" }
        require(
            shape.size == 3 &&
                shape[1] == TcnInputBuffer.FEATURE_COUNT.toLong() &&
                shape[2] == TcnInputBuffer.DEFAULT_CAPACITY.toLong()
        ) {
            "TCN input must be [batch, ${TcnInputBuffer.FEATURE_COUNT}, ${TcnInputBuffer.DEFAULT_CAPACITY}], got ${shape.contentToString()}"
        }

        val outputInfo = session.outputInfo.values.single().info as? TensorInfo
            ?: error("TCN output must be a tensor")
        require(outputInfo.type == OnnxJavaType.FLOAT) { "TCN output must use Float32" }
        require(outputInfo.shape.size == 1) {
            "Deterministic TCN output must be [batch], got ${outputInfo.shape.contentToString()}"
        }
    }

    private fun org.json.JSONArray.toFloatArray(): FloatArray =
        FloatArray(length()) { index -> getDouble(index).toFloat() }

    companion object {
        private val FEATURE_NAMES = listOf(
            "accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y", "gyro_z"
        )
        private const val MODEL_ASSET = "tcn.onnx"
        private const val NORMALIZATION_ASSET = "normalization.json"
        private const val MIN_SPEED_MPS = 0f
        private const val MAX_SPEED_MPS = 70f
    }
}
