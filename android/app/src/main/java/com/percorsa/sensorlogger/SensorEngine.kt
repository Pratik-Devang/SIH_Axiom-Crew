package com.percorsa.sensorlogger

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

data class SensorSnapshot(
    val timestampNs: Long,
    val hasAccel: Boolean,
    val hasGyro: Boolean,
    val hasRotVector: Boolean,
    val hasLinearAccel: Boolean,
    val hasGravity: Boolean,
    
    // Raw Accel
    val accelX: Float, val accelY: Float, val accelZ: Float, val accelMag: Float,
    
    // Gyro
    val gyroX: Float, val gyroY: Float, val gyroZ: Float, val gyroMag: Float,
    
    // Rotation Vector
    val quatW: Float, val quatX: Float, val quatY: Float, val quatZ: Float, val quatNorm: Float,
    
    // Derived
    val linearAccelX: Float, val linearAccelY: Float, val linearAccelZ: Float, val linearAccelMag: Float,
    val gravityX: Float, val gravityY: Float, val gravityZ: Float, val gravityMag: Float,
    
    // Corrected Vehicle Frame
    val isCalibrated: Boolean,
    val correctedAccelForward: Float, val correctedAccelLeft: Float, val correctedAccelUp: Float, val correctedAccelMag: Float,
    val correctedLinearForward: Float, val correctedLinearLeft: Float, val correctedLinearUp: Float, val correctedLinearMag: Float,
    val correctedGyroForward: Float, val correctedGyroLeft: Float, val correctedGyroUp: Float, val correctedGyroMag: Float,
    
    // Diagnostics & Frequencies
    val imuHz: Float,
    val rawCallbackHz: Float,
    val totalCallbacks: Long,
    val loggedCsvRows: Long,
    val duplicateTimestampsCount: Long,
    val nonMonotonicTimestampsCount: Long,
    val largeGapCount: Long,
    val staleSensorCount: Long,
    val warnings: List<String>
)

class SensorEngine(context: Context? = null) : SensorEventListener {

    private val sensorManager: SensorManager? = context?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    val hasAccel: Boolean = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null || context == null
    val hasGyro: Boolean = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null || context == null
    val hasRotVector: Boolean = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null || context == null
    val hasLinearAccel: Boolean = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null || context == null
    val hasGravity: Boolean = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY) != null || context == null

    // Latest Raw Sensor Buffers & Timestamps
    @Volatile private var accelTimestampNs: Long = 0L
    @Volatile private var gyroTimestampNs: Long = 0L
    @Volatile private var rotVectorTimestampNs: Long = 0L
    @Volatile private var linearAccelTimestampNs: Long = 0L
    @Volatile private var gravityTimestampNs: Long = 0L

    @Volatile private var lastLoggedCsvTimestampNs: Long = 0L

    private val rawAccel = FloatArray(3)
    private val rawGyro = FloatArray(3)
    private val rotVector = FloatArray(5)
    private val linearAccel = FloatArray(3)
    private val gravity = FloatArray(3)
    private val quaternion = floatArrayOf(1f, 0f, 0f, 0f) // w, x, y, z

    // Rotation Matrices
    private val rCurrent = FloatArray(9).apply {
        this[0] = 1f; this[4] = 1f; this[8] = 1f
    }
    private val rCal = FloatArray(9).apply {
        this[0] = 1f; this[4] = 1f; this[8] = 1f
    }
    @Volatile var isCalibrated: Boolean = false
        private set

    // Diagnostic Counters
    private val totalCallbackCount = AtomicLong(0)
    private val primaryImuSampleCount = AtomicLong(0)
    private val loggedCsvRowCount = AtomicLong(0)

    private val duplicateTimestampCount = AtomicLong(0)
    private val nonMonotonicTimestampCount = AtomicLong(0)
    private val largeGapCount = AtomicLong(0)
    private val staleSensorCount = AtomicLong(0)

    private var lastHzCheckTimeNs: Long = System.nanoTime()
    @Volatile private var currentImuHz: Float = 0f
    @Volatile private var currentRawCallbackHz: Float = 0f

    private val currentWarnings = mutableListOf<String>()

    private var csvRecorder: CsvRecorder? = null
    @Volatile var isRecording: Boolean = false
        private set

    // Synchronization Window: 50 milliseconds
    private val syncWindowNs: Long = 50_000_000L

    fun start() {
        val sm = sensorManager ?: return
        val delay = SensorManager.SENSOR_DELAY_GAME
        if (hasAccel) sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), delay)
        if (hasGyro) sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE), delay)
        if (hasRotVector) sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), delay)
        if (hasLinearAccel) sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION), delay)
        if (hasGravity) sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_GRAVITY), delay)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        stopRecording()
    }

    fun calibrateVehicleFrame() {
        synchronized(this) {
            System.arraycopy(rCurrent, 0, rCal, 0, 9)
            isCalibrated = true
        }
    }

    fun startRecording(recorder: CsvRecorder) {
        synchronized(this) {
            this.csvRecorder = recorder
            this.lastLoggedCsvTimestampNs = 0L
            this.isRecording = true
        }
    }

    fun stopRecording() {
        synchronized(this) {
            this.isRecording = false
            this.csvRecorder?.close()
            this.csvRecorder = null
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        handleSensorData(event.sensor.type, event.timestamp, event.values)
    }

    /**
     * Public method to allow both real SensorEvent and synthetic unit test processing.
     */
    fun handleSensorData(sensorType: Int, timestampNs: Long, values: FloatArray) {
        totalCallbackCount.incrementAndGet()

        synchronized(this) {
            when (sensorType) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accelTimestampNs = timestampNs
                    System.arraycopy(values, 0, rawAccel, 0, 3)
                    checkExtremeValues("Accel", rawAccel)

                    // Primary IMU Driver Trigger
                    primaryImuSampleCount.incrementAndGet()

                    if (isRecording) {
                        processAndRecordImuSample(timestampNs)
                    }
                }
                Sensor.TYPE_GYROSCOPE -> {
                    gyroTimestampNs = timestampNs
                    System.arraycopy(values, 0, rawGyro, 0, 3)
                    checkExtremeValues("Gyro", rawGyro)
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    rotVectorTimestampNs = timestampNs
                    updateRotationVector(values)
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    linearAccelTimestampNs = timestampNs
                    System.arraycopy(values, 0, linearAccel, 0, 3)
                    checkExtremeValues("LinearAccel", linearAccel)
                }
                Sensor.TYPE_GRAVITY -> {
                    gravityTimestampNs = timestampNs
                    System.arraycopy(values, 0, gravity, 0, 3)
                }
            }
        }
    }

    private fun updateRotationVector(values: FloatArray) {
        val len = values.size.coerceAtMost(5)
        System.arraycopy(values, 0, rotVector, 0, len)
        try {
            SensorManager.getRotationMatrixFromVector(rCurrent, rotVector)
            SensorManager.getQuaternionFromVector(quaternion, rotVector)
        } catch (e: Throwable) {
            // Fallback for unit test / JVM environment where SensorManager methods are stubbed
            val qx = rotVector[0]
            val qy = rotVector[1]
            val qz = rotVector[2]
            val qw = if (len >= 4 && rotVector[3] != 0f) rotVector[3] else sqrt((1f - (qx * qx + qy * qy + qz * qz)).coerceAtLeast(0f))

            quaternion[0] = qw
            quaternion[1] = qx
            quaternion[2] = qy
            quaternion[3] = qz

            rCurrent[0] = 1f - 2f * (qy * qy + qz * qz)
            rCurrent[1] = 2f * (qx * qy - qw * qz)
            rCurrent[2] = 2f * (qx * qz + qw * qy)

            rCurrent[3] = 2f * (qx * qy + qw * qz)
            rCurrent[4] = 1f - 2f * (qx * qx + qz * qz)
            rCurrent[5] = 2f * (qy * qz - qw * qx)

            rCurrent[6] = 2f * (qx * qz - qw * qy)
            rCurrent[7] = 2f * (qy * qz + qw * qx)
            rCurrent[8] = 1f - 2f * (qx * qx + qy * qy)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Process a primary IMU sample at sampleTimeNs with strict monotonicity checks and CSV logging.
     */
    private fun processAndRecordImuSample(sampleTimeNs: Long) {
        // Enforce STRICT MONOTONICITY
        if (lastLoggedCsvTimestampNs > 0) {
            if (sampleTimeNs == lastLoggedCsvTimestampNs) {
                duplicateTimestampCount.incrementAndGet()
                addWarning("Duplicate timestamp rejected: $sampleTimeNs ns")
                return // REJECT ROW
            } else if (sampleTimeNs < lastLoggedCsvTimestampNs) {
                nonMonotonicTimestampCount.incrementAndGet()
                addWarning("Non-monotonic timestamp rejected: $sampleTimeNs < $lastLoggedCsvTimestampNs ns")
                return // REJECT ROW
            } else if (sampleTimeNs - lastLoggedCsvTimestampNs > 100_000_000L) { // > 100 ms gap
                largeGapCount.incrementAndGet()
                addWarning("Large timestamp gap detected: ${(sampleTimeNs - lastLoggedCsvTimestampNs) / 1_000_000} ms")
            }
        }

        // Synchronization Window Checks
        checkStaleSensor("Gyro", sampleTimeNs, gyroTimestampNs)
        checkStaleSensor("RotVector", sampleTimeNs, rotVectorTimestampNs)
        checkStaleSensor("LinearAccel", sampleTimeNs, linearAccelTimestampNs)
        checkStaleSensor("Gravity", sampleTimeNs, gravityTimestampNs)

        lastLoggedCsvTimestampNs = sampleTimeNs
        loggedCsvRowCount.incrementAndGet()

        recordCurrentState(sampleTimeNs)
    }

    private fun checkStaleSensor(name: String, primaryNs: Long, sensorNs: Long) {
        if (sensorNs > 0L && abs(primaryNs - sensorNs) > syncWindowNs) {
            staleSensorCount.incrementAndGet()
            addWarning("Stale sensor data ($name): dt=${abs(primaryNs - sensorNs) / 1_000_000} ms")
        }
    }

    private fun addWarning(msg: String) {
        if (currentWarnings.size > 5) currentWarnings.removeAt(0)
        if (!currentWarnings.contains(msg)) {
            currentWarnings.add(msg)
        }
    }

    private fun checkExtremeValues(tag: String, values: FloatArray) {
        for (v in values) {
            if (v.isNaN()) addWarning("NaN detected in $tag")
            if (v.isInfinite()) addWarning("Infinity detected in $tag")
        }
        if (tag == "Accel") {
            val mag = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
            if (mag > 50f) addWarning("Extreme Accel value: %.1f m/s²".format(mag))
        }
    }

    private fun recordCurrentState(timestampNs: Long) {
        val corrAccel = FloatArray(3)
        val corrLinear = FloatArray(3)
        val corrGyro = FloatArray(3)

        if (isCalibrated) {
            transformToVehicleFrame(rawAccel, corrAccel)
            transformToVehicleFrame(linearAccel, corrLinear)
            transformToVehicleFrame(rawGyro, corrGyro)
        }

        csvRecorder?.writeRow(
            timestampNs = timestampNs,
            accelX = rawAccel[0], accelY = rawAccel[1], accelZ = rawAccel[2],
            linearX = linearAccel[0], linearY = linearAccel[1], linearZ = linearAccel[2],
            gravX = gravity[0], gravY = gravity[1], gravZ = gravity[2],
            gyroX = rawGyro[0], gyroY = rawGyro[1], gyroZ = rawGyro[2],
            qw = quaternion[0], qx = quaternion[1], qy = quaternion[2], qz = quaternion[3],
            corrAccelFwd = corrAccel[0], corrAccelLeft = corrAccel[1], corrAccelUp = corrAccel[2],
            corrLinearFwd = corrLinear[0], corrLinearLeft = corrLinear[1], corrLinearUp = corrLinear[2],
            corrGyroFwd = corrGyro[0], corrGyroLeft = corrGyro[1], corrGyroUp = corrGyro[2]
        )
    }

    /**
     * Transform a 3D vector vP (in phone coordinates) into vehicle coordinates vV:
     * vW = R_current * vP  (Phone -> World)
     * vV = R_cal^T * vW    (World -> Vehicle)
     */
    private fun transformToVehicleFrame(vPhone: FloatArray, vVehicle: FloatArray) {
        // vW = R_current * vPhone
        val wx = rCurrent[0] * vPhone[0] + rCurrent[1] * vPhone[1] + rCurrent[2] * vPhone[2]
        val wy = rCurrent[3] * vPhone[0] + rCurrent[4] * vPhone[1] + rCurrent[5] * vPhone[2]
        val wz = rCurrent[6] * vPhone[0] + rCurrent[7] * vPhone[1] + rCurrent[8] * vPhone[2]

        // vV = R_cal^T * vW
        vVehicle[0] = rCal[0] * wx + rCal[3] * wy + rCal[6] * wz // Forward
        vVehicle[1] = rCal[1] * wx + rCal[4] * wy + rCal[7] * wz // Left
        vVehicle[2] = rCal[2] * wx + rCal[5] * wy + rCal[8] * wz // Up
    }

    fun getSnapshot(): SensorSnapshot = synchronized(this) {
        // Calculate IMU Frequency and Raw Callback Frequency
        val nowSysNs = System.nanoTime()
        val elapsed = nowSysNs - lastHzCheckTimeNs
        if (elapsed > 500_000_000L) { // update Hz every 0.5s
            val imuSamples = primaryImuSampleCount.getAndSet(0)
            val rawCallbacks = totalCallbackCount.getAndSet(0)
            currentImuHz = (imuSamples * 1_000_000_000f) / elapsed
            currentRawCallbackHz = (rawCallbacks * 1_000_000_000f) / elapsed
            lastHzCheckTimeNs = nowSysNs
        }

        val accelMag = sqrt(rawAccel[0] * rawAccel[0] + rawAccel[1] * rawAccel[1] + rawAccel[2] * rawAccel[2])
        val gyroMag = sqrt(rawGyro[0] * rawGyro[0] + rawGyro[1] * rawGyro[1] + rawGyro[2] * rawGyro[2])
        val linearMag = sqrt(linearAccel[0] * linearAccel[0] + linearAccel[1] * linearAccel[1] + linearAccel[2] * linearAccel[2])
        val gravMag = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        val qNorm = sqrt(quaternion[0] * quaternion[0] + quaternion[1] * quaternion[1] + quaternion[2] * quaternion[2] + quaternion[3] * quaternion[3])

        if (qNorm.isNaN() || abs(qNorm - 1.0f) > 0.05f) {
            addWarning("Quaternion norm anomaly: %.4f".format(qNorm))
        }

        val corrAccel = FloatArray(3)
        val corrLinear = FloatArray(3)
        val corrGyro = FloatArray(3)

        if (isCalibrated) {
            transformToVehicleFrame(rawAccel, corrAccel)
            transformToVehicleFrame(linearAccel, corrLinear)
            transformToVehicleFrame(rawGyro, corrGyro)
        }

        return SensorSnapshot(
            timestampNs = if (accelTimestampNs > 0) accelTimestampNs else lastLoggedCsvTimestampNs,
            hasAccel = hasAccel,
            hasGyro = hasGyro,
            hasRotVector = hasRotVector,
            hasLinearAccel = hasLinearAccel,
            hasGravity = hasGravity,
            accelX = rawAccel[0], accelY = rawAccel[1], accelZ = rawAccel[2], accelMag = accelMag,
            gyroX = rawGyro[0], gyroY = rawGyro[1], gyroZ = rawGyro[2], gyroMag = gyroMag,
            quatW = quaternion[0], quatX = quaternion[1], quatY = quaternion[2], quatZ = quaternion[3], quatNorm = qNorm,
            linearAccelX = linearAccel[0], linearAccelY = linearAccel[1], linearAccelZ = linearAccel[2], linearAccelMag = linearMag,
            gravityX = gravity[0], gravityY = gravity[1], gravityZ = gravity[2], gravityMag = gravMag,
            isCalibrated = isCalibrated,
            correctedAccelForward = corrAccel[0], correctedAccelLeft = corrAccel[1], correctedAccelUp = corrAccel[2],
            correctedAccelMag = sqrt(corrAccel[0]*corrAccel[0] + corrAccel[1]*corrAccel[1] + corrAccel[2]*corrAccel[2]),
            correctedLinearForward = corrLinear[0], correctedLinearLeft = corrLinear[1], correctedLinearUp = corrLinear[2],
            correctedLinearMag = sqrt(corrLinear[0]*corrLinear[0] + corrLinear[1]*corrLinear[1] + corrLinear[2]*corrLinear[2]),
            correctedGyroForward = corrGyro[0], correctedGyroLeft = corrGyro[1], correctedGyroUp = corrGyro[2],
            correctedGyroMag = sqrt(corrGyro[0]*corrGyro[0] + corrGyro[1]*corrGyro[1] + corrGyro[2]*corrGyro[2]),
            imuHz = currentImuHz,
            rawCallbackHz = currentRawCallbackHz,
            totalCallbacks = totalCallbackCount.get(),
            loggedCsvRows = loggedCsvRowCount.get(),
            duplicateTimestampsCount = duplicateTimestampCount.get(),
            nonMonotonicTimestampsCount = nonMonotonicTimestampCount.get(),
            largeGapCount = largeGapCount.get(),
            staleSensorCount = staleSensorCount.get(),
            warnings = ArrayList(currentWarnings)
        )
    }
}
