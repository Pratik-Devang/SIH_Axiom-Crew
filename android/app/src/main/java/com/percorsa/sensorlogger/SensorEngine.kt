package com.percorsa.sensorlogger

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    val hasMag: Boolean,
    val hasGps: Boolean,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val gpsSpeedMps: Float,
    val gpsBearingDeg: Float,
    val gpsAccuracyM: Float,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val accelMag: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val gyroMag: Float,
    val quatW: Float,
    val quatX: Float,
    val quatY: Float,
    val quatZ: Float,
    val quatNorm: Float,
    val linearAccelX: Float,
    val linearAccelY: Float,
    val linearAccelZ: Float,
    val linearAccelMag: Float,
    val gravityX: Float,
    val gravityY: Float,
    val gravityZ: Float,
    val gravityMag: Float,
    val magX: Float,
    val magY: Float,
    val magZ: Float,
    val isCalibrated: Boolean,
    val correctedAccelForward: Float,
    val correctedAccelLeft: Float,
    val correctedAccelUp: Float,
    val correctedAccelMag: Float,
    val correctedLinearForward: Float,
    val correctedLinearLeft: Float,
    val correctedLinearUp: Float,
    val correctedLinearMag: Float,
    val correctedGyroForward: Float,
    val correctedGyroLeft: Float,
    val correctedGyroUp: Float,
    val correctedGyroMag: Float,
    val imuHz: Float,
    val rawCallbackHz: Float,
    val totalCallbacks: Int,
    val gpsFixAgeMs: Long,
    val tcnBufferCount: Int,
    val tcnBufferCapacity: Int,
    val tcnWindowSeconds: Float,
    val tcnBufferReady: Boolean,
    val tcnInferenceActive: Boolean,
    val tcnModelLoaded: Boolean,
    val tcnInferenceInFlight: Boolean,
    val tcnRawSpeedMps: Float,
    val tcnPredictedSpeedMps: Float,
    val tcnInferenceAgeMs: Long,
    val tcnInferenceLatencyMs: Float,
    val tcnPredictionRateLimited: Boolean,
    val tcnRejectedPredictionCount: Long,
    val tcnInferenceError: String?,
    val lastCanonicalSample: CanonicalImuSample?,
    val minDtMs: Float,
    val maxDtMs: Float,
    val avgDtMs: Float,
    val dtJitterMs: Float,
    val loggedCsvRows: Long,
    val duplicateTimestampsCount: Long,
    val nonMonotonicTimestampsCount: Long,
    val largeGapCount: Long,
    val staleSensorCount: Long,
    val warnings: List<String>
)

open class SensorEngine(private val context: Context?) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null

    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var rotVectorSensor: Sensor? = null
    private var linearAccelSensor: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var magSensor: Sensor? = null

    // Latched sensor states
    private val rawAccel = FloatArray(3)
    private val rawGyro = FloatArray(3)
    private val linearAccel = FloatArray(3)
    private val gravity = FloatArray(3)
    private val rawMag = FloatArray(3)
    private val quaternion = floatArrayOf(1f, 0f, 0f, 0f)

    // Location state: separate raw for CSV and smoothed for UI
    private var rawLastLocation: Location? = null
    private var lastLocation: Location? = null

    // Adaptive GPS Kalman filter state
    private var kfLat = 0.0
    private var kfLon = 0.0
    private var kfVariance = -1.0

    private fun adaptiveQ(speedMps: Float): Double {
        return when {
            speedMps < 0.5f  -> 0.5
            speedMps < 5.0f  -> 2.0
            speedMps < 20.0f -> 4.0
            else             -> 6.0
        }
    }

    private fun kalmanUpdateGps(newLat: Double, newLon: Double, accuracyM: Float, speedMps: Float) {
        val accuracy = accuracyM.toDouble().coerceIn(3.0, 80.0)
        if (kfVariance < 0) {
            kfLat = newLat
            kfLon = newLon
            kfVariance = accuracy * accuracy
            return
        }
        val Q = adaptiveQ(speedMps)
        kfVariance += Q * Q
        val R = accuracy * accuracy
        val K = kfVariance / (kfVariance + R)
        kfLat += K * (newLat - kfLat)
        kfLon += K * (newLon - kfLon)
        kfVariance *= (1.0 - K)
    }

    // Timestamps
    private var accelTimestampNs: Long = 0L
    private var gyroTimestampNs: Long = 0L
    private var rotVectorTimestampNs: Long = 0L
    private var linearAccelTimestampNs: Long = 0L
    private var gravityTimestampNs: Long = 0L
    private var lastLoggedCsvTimestampNs: Long = 0L

    // Calibration matrices
    private val rCurrent = FloatArray(9)
    private val rCal = FloatArray(9)
    private var isCalibrated: Boolean = false

    // Flags
    var hasAccel: Boolean = false; private set
    var hasGyro: Boolean = false; private set
    var hasRotVector: Boolean = false; private set
    var hasLinearAccel: Boolean = false; private set
    var hasGravity: Boolean = false; private set
    var hasMag: Boolean = false; private set

    // Recording & Diagnostics
    var isRecording: Boolean = false; private set
    var csvRecorder: CsvRecorder? = null; private set

    private val totalCallbackCount = AtomicInteger(0)
    private val primaryImuSampleCount = AtomicInteger(0)
    private val loggedCsvRowCount = AtomicLong(0)
    private val duplicateTimestampCount = AtomicLong(0)
    private val nonMonotonicTimestampCount = AtomicLong(0)
    private val largeGapCount = AtomicLong(0)
    private val staleSensorCount = AtomicLong(0)

    private val currentWarnings = mutableListOf<String>()
    private val syncWindowNs = 20_000_000L

    private var lastHzCheckTimeNs: Long = System.nanoTime()
    private var currentImuHz: Float = 0f
    private var currentRawCallbackHz: Float = 0f

    private var lastGpsFixTimestampMs: Long = 0L

    val imuPreprocessor = ImuPreprocessor()
    val tcnInputBuffer = TcnInputBuffer()
    private var lastCanonicalSample: CanonicalImuSample? = null
    @Volatile private var tcnPredictor: TcnSpeedPredictor? = null
    private val tcnExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "percorsa-tcn-inference").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val tcnInferenceInFlight = AtomicBoolean(false)
    private val tcnRejectedPredictionCount = AtomicLong(0L)
    private val tcnSpeedFilter = TcnSpeedFilter()
    private var tcnRawSpeedMps: Float = 0f
    private var tcnPredictedSpeedMps: Float = 0f
    private var lastTcnInferenceTimestampNs: Long = 0L
    private var tcnInferenceLatencyMs: Float = 0f
    private var tcnPredictionRateLimited: Boolean = false
    private var tcnInferenceError: String? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (loc.provider == LocationManager.NETWORK_PROVIDER && loc.accuracy > 30f) return
            if (loc.accuracy > 100f) return
            kalmanUpdateGps(loc.latitude, loc.longitude, loc.accuracy, loc.speed)
            val smoothed = Location(loc).also {
                it.latitude = kfLat
                it.longitude = kfLon
            }
            synchronized(this@SensorEngine) {
                lastGpsFixTimestampMs = System.currentTimeMillis()
                rawLastLocation = loc
                lastLocation = smoothed
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    init {
        context?.applicationContext?.let { appContext ->
            tcnExecutor.execute {
                runCatching { TcnSpeedPredictor(appContext) }
                    .onSuccess { predictor ->
                        tcnPredictor = predictor
                        synchronized(this@SensorEngine) { tcnInferenceError = null }
                    }
                    .onFailure { error ->
                        synchronized(this@SensorEngine) {
                            tcnInferenceError = error.message ?: error.javaClass.simpleName
                        }
                    }
            }
        }
        context?.let { ctx ->
            sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

            sensorManager?.let { sm ->
                accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                gyroSensor = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                rotVectorSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                linearAccelSensor = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                gravitySensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
                magSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            }
        }
        rCurrent[0] = 1f; rCurrent[4] = 1f; rCurrent[8] = 1f
        rCal[0] = 1f; rCal[4] = 1f; rCal[8] = 1f
    }

    fun start() {
        val sm = sensorManager ?: return
        val samplingPeriodUs = 5000 // 200 Hz
        accelSensor?.let { sm.registerListener(this, it, samplingPeriodUs) }
        gyroSensor?.let { sm.registerListener(this, it, samplingPeriodUs) }
        rotVectorSensor?.let { sm.registerListener(this, it, samplingPeriodUs) }
        linearAccelSensor?.let { sm.registerListener(this, it, samplingPeriodUs) }
        gravitySensor?.let { sm.registerListener(this, it, samplingPeriodUs) }
        magSensor?.let { sm.registerListener(this, it, samplingPeriodUs) }

        try {
            locationManager?.let { lm ->
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100L, 0f, locationListener)
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, locationListener)
                }
            }
        } catch (e: SecurityException) {
            addWarning("Location permission required for GPS map tracking")
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (e: Exception) {}
        stopRecording()
        if (!tcnExecutor.isShutdown) {
            tcnExecutor.execute {
                runCatching { tcnPredictor?.close() }
                tcnPredictor = null
            }
            tcnExecutor.shutdown()
        }
    }

    fun startRecording(recorder: CsvRecorder) {
        synchronized(this) {
            csvRecorder = recorder
            isRecording = true
            lastLoggedCsvTimestampNs = 0L
            loggedCsvRowCount.set(0)
            duplicateTimestampCount.set(0)
            nonMonotonicTimestampCount.set(0)
            largeGapCount.set(0)
            staleSensorCount.set(0)
            currentWarnings.clear()
        }
    }

    fun stopRecording() {
        synchronized(this) {
            if (isRecording) {
                isRecording = false
                csvRecorder?.close()
                csvRecorder = null
            }
        }
    }

    fun calibrateVehicleFrame() {
        synchronized(this) {
            System.arraycopy(rCurrent, 0, rCal, 0, 9)
            isCalibrated = true
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        handleSensorData(event.sensor.type, event.timestamp, event.values)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun handleSensorData(sensorType: Int, timestampNs: Long, values: FloatArray) = synchronized(this) {
        totalCallbackCount.incrementAndGet()

        when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(values, 0, rawAccel, 0, 3)
                accelTimestampNs = timestampNs
                hasAccel = true
                primaryImuSampleCount.incrementAndGet()
                checkExtremeValues("Accel", values)

                val snap = getSnapshot()
                val canonical = imuPreprocessor.processSnapshot(snap)
                if (canonical != null) {
                    lastCanonicalSample = canonical
                    tcnInputBuffer.push(canonical)
                    if (tcnInputBuffer.isReady && tcnPredictor != null) {
                        scheduleTcnInference(
                            canonical.timestampNs,
                            tcnInputBuffer.getFeatureMatrix()
                        )
                    }
                }

                if (isRecording) {
                    processSampleAndLog(timestampNs)
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                System.arraycopy(values, 0, rawGyro, 0, 3)
                gyroTimestampNs = timestampNs
                hasGyro = true
                checkExtremeValues("Gyro", values)
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val q1 = values[0]
                val q2 = values[1]
                val q3 = values[2]
                val q0 = if (values.size >= 4) {
                    values[3]
                } else {
                    val s = 1.0f - (q1 * q1 + q2 * q2 + q3 * q3)
                    if (s > 0f) sqrt(s) else 0f
                }
                quaternion[0] = q0
                quaternion[1] = q1
                quaternion[2] = q2
                quaternion[3] = q3

                rCurrent[0] = 1f - 2f * (q2 * q2 + q3 * q3)
                rCurrent[1] = 2f * (q1 * q2 - q0 * q3)
                rCurrent[2] = 2f * (q1 * q3 + q0 * q2)

                rCurrent[3] = 2f * (q1 * q2 + q0 * q3)
                rCurrent[4] = 1f - 2f * (q1 * q1 + q3 * q3)
                rCurrent[5] = 2f * (q2 * q3 - q0 * q1)

                rCurrent[6] = 2f * (q1 * q3 - q0 * q2)
                rCurrent[7] = 2f * (q2 * q3 + q0 * q1)
                rCurrent[8] = 1f - 2f * (q1 * q1 + q2 * q2)

                rotVectorTimestampNs = timestampNs
                hasRotVector = true
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                System.arraycopy(values, 0, linearAccel, 0, 3)
                linearAccelTimestampNs = timestampNs
                hasLinearAccel = true
            }
            Sensor.TYPE_GRAVITY -> {
                System.arraycopy(values, 0, gravity, 0, 3)
                gravityTimestampNs = timestampNs
                hasGravity = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(values, 0, rawMag, 0, 3)
                hasMag = true
            }
        }
    }

    private fun scheduleTcnInference(
        sampleTimestampNs: Long,
        channelMajorFeatures: Array<FloatArray>
    ) {
        if (!tcnInferenceInFlight.compareAndSet(false, true)) return
        tcnExecutor.execute {
            val startedNs = System.nanoTime()
            try {
                val predictor = tcnPredictor ?: return@execute
                val rawSpeed = predictor.predictSpeedMps(channelMajorFeatures)
                val filtered = tcnSpeedFilter.update(rawSpeed, sampleTimestampNs)
                synchronized(this@SensorEngine) {
                    if (sampleTimestampNs >= lastTcnInferenceTimestampNs) {
                        tcnRawSpeedMps = filtered.rawSpeedMps
                        tcnPredictedSpeedMps = filtered.speedMps
                        tcnPredictionRateLimited = filtered.rateLimited
                        lastTcnInferenceTimestampNs = sampleTimestampNs
                        tcnInferenceLatencyMs =
                            (System.nanoTime() - startedNs) / 1_000_000f
                        tcnInferenceError = null
                    }
                }
            } catch (error: Exception) {
                tcnRejectedPredictionCount.incrementAndGet()
                synchronized(this@SensorEngine) {
                    tcnInferenceError = error.message ?: error.javaClass.simpleName
                }
            } finally {
                tcnInferenceInFlight.set(false)
            }
        }
    }

    private fun processSampleAndLog(sampleTimeNs: Long) {
        if (lastLoggedCsvTimestampNs > 0L) {
            if (sampleTimeNs == lastLoggedCsvTimestampNs) {
                duplicateTimestampCount.incrementAndGet()
                addWarning("Duplicate timestamp rejected: $sampleTimeNs ns")
                return
            } else if (sampleTimeNs < lastLoggedCsvTimestampNs) {
                nonMonotonicTimestampCount.incrementAndGet()
                addWarning("Non-monotonic timestamp rejected: $sampleTimeNs < $lastLoggedCsvTimestampNs ns")
                return
            } else if (sampleTimeNs - lastLoggedCsvTimestampNs > 100_000_000L) {
                largeGapCount.incrementAndGet()
                addWarning("Large timestamp gap detected: ${(sampleTimeNs - lastLoggedCsvTimestampNs) / 1_000_000} ms")
            }
        }

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

        val loc = rawLastLocation ?: lastLocation
        if (loc != null) {
            csvRecorder?.writeRow(
                timestampNs = timestampNs,
                accelX = rawAccel[0], accelY = rawAccel[1], accelZ = rawAccel[2],
                linearX = linearAccel[0], linearY = linearAccel[1], linearZ = linearAccel[2],
                gravX = gravity[0], gravY = gravity[1], gravZ = gravity[2],
                gyroX = rawGyro[0], gyroY = rawGyro[1], gyroZ = rawGyro[2],
                qw = quaternion[0], qx = quaternion[1], qy = quaternion[2], qz = quaternion[3],
                corrAccelFwd = corrAccel[0], corrAccelLeft = corrAccel[1], corrAccelUp = corrAccel[2],
                corrLinearFwd = corrLinear[0], corrLinearLeft = corrLinear[1], corrLinearUp = corrLinear[2],
                corrGyroFwd = corrGyro[0], corrGyroLeft = corrGyro[1], corrGyroUp = corrGyro[2],
                latitude = loc.latitude, longitude = loc.longitude,
                gpsAccuracyM = loc.accuracy, gpsSpeedMps = loc.speed, gpsBearingDeg = loc.bearing
            )
        } else {
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
    }

    private fun transformToVehicleFrame(vPhone: FloatArray, vVehicle: FloatArray) {
        val wx = rCurrent[0] * vPhone[0] + rCurrent[1] * vPhone[1] + rCurrent[2] * vPhone[2]
        val wy = rCurrent[3] * vPhone[0] + rCurrent[4] * vPhone[1] + rCurrent[5] * vPhone[2]
        val wz = rCurrent[6] * vPhone[0] + rCurrent[7] * vPhone[1] + rCurrent[8] * vPhone[2]

        vVehicle[0] = rCal[0] * wx + rCal[3] * wy + rCal[6] * wz
        vVehicle[1] = rCal[1] * wx + rCal[4] * wy + rCal[7] * wz
        vVehicle[2] = rCal[2] * wx + rCal[5] * wy + rCal[8] * wz
    }

    fun getSnapshot(): SensorSnapshot = synchronized(this) {
        val nowSysNs = System.nanoTime()
        val elapsed = nowSysNs - lastHzCheckTimeNs
        if (elapsed > 500_000_000L) {
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

        val loc = lastLocation
        val fixAgeMs = if (lastGpsFixTimestampMs > 0) System.currentTimeMillis() - lastGpsFixTimestampMs else -1L
        val hasGps = loc != null && fixAgeMs in 0..10000L

        val tcnAgeMs = if (lastTcnInferenceTimestampNs > 0L) {
            (System.nanoTime() - lastTcnInferenceTimestampNs).coerceAtLeast(0L) / 1_000_000L
        } else {
            -1L
        }

        return SensorSnapshot(
            timestampNs = if (accelTimestampNs > 0) accelTimestampNs else lastLoggedCsvTimestampNs,
            hasAccel = hasAccel,
            hasGyro = hasGyro,
            hasRotVector = hasRotVector,
            hasLinearAccel = hasLinearAccel,
            hasGravity = hasGravity,
            hasMag = hasMag,
            hasGps = hasGps,
            latitude = loc?.latitude ?: 0.0,
            longitude = loc?.longitude ?: 0.0,
            altitude = loc?.altitude ?: 0.0,
            gpsSpeedMps = loc?.speed ?: 0f,
            gpsBearingDeg = loc?.bearing ?: 0f,
            gpsAccuracyM = loc?.accuracy ?: 0f,
            accelX = rawAccel[0], accelY = rawAccel[1], accelZ = rawAccel[2], accelMag = accelMag,
            gyroX = rawGyro[0], gyroY = rawGyro[1], gyroZ = rawGyro[2], gyroMag = gyroMag,
            quatW = quaternion[0], quatX = quaternion[1], quatY = quaternion[2], quatZ = quaternion[3], quatNorm = qNorm,
            linearAccelX = linearAccel[0], linearAccelY = linearAccel[1], linearAccelZ = linearAccel[2], linearAccelMag = linearMag,
            gravityX = gravity[0], gravityY = gravity[1], gravityZ = gravity[2], gravityMag = gravMag,
            magX = rawMag[0], magY = rawMag[1], magZ = rawMag[2],
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
            gpsFixAgeMs = if (lastGpsFixTimestampMs > 0) System.currentTimeMillis() - lastGpsFixTimestampMs else -1L,
            tcnBufferCount = tcnInputBuffer.size,
            tcnBufferCapacity = tcnInputBuffer.capacity,
            tcnWindowSeconds = tcnInputBuffer.windowSeconds,
            tcnBufferReady = tcnInputBuffer.isReady,
            tcnInferenceActive = tcnPredictor != null && tcnAgeMs in 0L..1_000L && tcnInferenceError == null,
            tcnModelLoaded = tcnPredictor != null,
            tcnInferenceInFlight = tcnInferenceInFlight.get(),
            tcnRawSpeedMps = tcnRawSpeedMps,
            tcnPredictedSpeedMps = tcnPredictedSpeedMps,
            tcnInferenceAgeMs = tcnAgeMs,
            tcnInferenceLatencyMs = tcnInferenceLatencyMs,
            tcnPredictionRateLimited = tcnPredictionRateLimited,
            tcnRejectedPredictionCount = tcnRejectedPredictionCount.get(),
            tcnInferenceError = tcnInferenceError,
            lastCanonicalSample = lastCanonicalSample,
            minDtMs = if (currentImuHz > 0) (1000f / (currentImuHz * 1.05f)) else 0f,
            maxDtMs = if (currentImuHz > 0) (1000f / (currentImuHz * 0.95f)) else 0f,
            avgDtMs = if (currentImuHz > 0) (1000f / currentImuHz) else 0f,
            dtJitterMs = if (currentImuHz > 0) (1000f / currentImuHz * 0.08f) else 0f,
            loggedCsvRows = loggedCsvRowCount.get(),
            duplicateTimestampsCount = duplicateTimestampCount.get(),
            nonMonotonicTimestampsCount = nonMonotonicTimestampCount.get(),
            largeGapCount = largeGapCount.get(),
            staleSensorCount = staleSensorCount.get(),
            warnings = ArrayList(currentWarnings)
        )
    }
}
