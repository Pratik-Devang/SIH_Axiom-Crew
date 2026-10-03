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
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import android.view.Surface

/** Android elapsed-realtime clock, with a JVM-test fallback for framework stubs. */
internal fun elapsedRealtimeNanosCompat(): Long = try {
    SystemClock.elapsedRealtimeNanos()
} catch (_: RuntimeException) {
    System.nanoTime()
}

data class SensorSnapshot(
    val timestampNs: Long,
    val hasAccel: Boolean,
    val hasGyro: Boolean,
    val hasRotVector: Boolean,
    val hasLinearAccel: Boolean,
    val hasGravity: Boolean,
    val hasMag: Boolean,
    val hasGps: Boolean,
    val gpsTimestampNs: Long = 0L,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val gpsSpeedMps: Float,
    val gpsBearingDeg: Float,
    val gpsAccuracyM: Float,
    val compassBearingDeg: Float,
    val deviceAzimuthDeg: Float = compassBearingDeg,
    val rotationSource: RotationSource = RotationSource.NONE,
    val deviceHeadingConfidence: DeviceHeadingConfidence = DeviceHeadingConfidence.LOW,
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
    private var gameRotVectorSensor: Sensor? = null   // Magnetic-disturbance-immune tilt source
    private var linearAccelSensor: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var magSensor: Sensor? = null

    // Latched rotation matrix from best available orientation source:
    //   TYPE_ROTATION_VECTOR  -> north-referenced; used when magnetometer is reliable
    //   TYPE_GAME_ROTATION_VECTOR -> magnetic-immune; used for tilt when mag may be distorted
    private val gameQuaternion = floatArrayOf(1f, 0f, 0f, 0f) // Game RV quat [w, x, y, z]
    private var hasGameRotVector: Boolean = false
    private var gameRotVectorTimestampNs: Long = 0L

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
    private var csvRecorder: CsvRecorder? = null
    @Volatile private var estimatedSpeedMps: Float = Float.NaN
    @Volatile private var estimatedSpeedProvider: (() -> Float)? = null
    @Volatile private var navigationDiagnosticsProvider: (() -> CsvNavigationDiagnostics)? = null

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

    private var lastGpsFixMonotonicNs: Long = 0L

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
    // TCN is independent and must remain empty until a real model output exists.
    private var tcnRawSpeedMps: Float = Float.NaN
    private var tcnPredictedSpeedMps: Float = Float.NaN
    private var lastTcnInferenceTimestampNs: Long = 0L
    private var tcnInferenceLatencyMs: Float = 0f
    private var tcnPredictionRateLimited: Boolean = false
    private var tcnInferenceError: String? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (loc.provider == LocationManager.NETWORK_PROVIDER && loc.accuracy > 30f) return
            if (!loc.latitude.isFinite() || !loc.longitude.isFinite() ||
                !loc.accuracy.isFinite() || loc.accuracy <= 0f || loc.accuracy > 100f) return
            kalmanUpdateGps(loc.latitude, loc.longitude, loc.accuracy, loc.speed)
            val smoothed = Location(loc).also {
                it.latitude = kfLat
                it.longitude = kfLon
            }
            synchronized(this@SensorEngine) {
                lastGpsFixMonotonicNs = loc.elapsedRealtimeNanos.takeIf { it > 0L } ?: elapsedRealtimeNanosCompat()
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
                        if (tcnExecutor.isShutdown) {
                            runCatching { predictor.close() }
                        } else {
                            tcnPredictor = predictor
                            synchronized(this@SensorEngine) { tcnInferenceError = null }
                        }
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
                gameRotVectorSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
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
        gameRotVectorSensor?.let { sm.registerListener(this, it, samplingPeriodUs) }
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

    /**
     * Fixed R_v_p for ESKF vehicle-frame measurements.  The calibrated vehicle
     * frame is the frame captured by the existing orientation calibration;
     * therefore R_v_p is the transpose of that phone-to-world basis.
     */
    @Synchronized
    fun getPhoneToVehicleRotation(): PhoneToVehicleRotation? {
        if (!isCalibrated) return null
        return PhoneToVehicleRotation(arrayOf(
            doubleArrayOf(rCal[0].toDouble(), rCal[3].toDouble(), rCal[6].toDouble()),
            doubleArrayOf(rCal[1].toDouble(), rCal[4].toDouble(), rCal[7].toDouble()),
            doubleArrayOf(rCal[2].toDouble(), rCal[5].toDouble(), rCal[8].toDouble())
        ))
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
                if (isRecording) {
                    csvRecorder?.writeRawImuEvent(
                        sensorType = "accelerometer",
                        timestampNs = timestampNs,
                        accelX = values[0], accelY = values[1], accelZ = values[2]
                    )
                }
                System.arraycopy(values, 0, rawAccel, 0, 3)
                accelTimestampNs = timestampNs
                hasAccel = true
                primaryImuSampleCount.incrementAndGet()
                checkExtremeValues("Accel", values)

                val snap = getSnapshot()
                val canonical = imuPreprocessor.processSnapshot(snap)
                if (canonical != null && tcnInputBuffer.push(canonical)) {
                    lastCanonicalSample = canonical
                    // TCN inference is only meaningful with calibrated vehicle-frame inputs.
                    // Uncalibrated phone-frame inputs produce ~138 km/h outliers from the
                    // benchmark-trained model; gate them to prevent spurious diagnostics.
                    if (canonical.vehicleFrameCalibrated && tcnInputBuffer.isReady && tcnPredictor != null) {
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
                if (isRecording) {
                    csvRecorder?.writeRawImuEvent(
                        sensorType = "gyroscope",
                        timestampNs = timestampNs,
                        gyroX = values[0], gyroY = values[1], gyroZ = values[2]
                    )
                }
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
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val gq1 = values[0]
                val gq2 = values[1]
                val gq3 = values[2]
                val gq0 = if (values.size >= 4) {
                    values[3]
                } else {
                    val s = 1.0f - (gq1 * gq1 + gq2 * gq2 + gq3 * gq3)
                    if (s > 0f) sqrt(s) else 0f
                }
                gameQuaternion[0] = gq0
                gameQuaternion[1] = gq1
                gameQuaternion[2] = gq2
                gameQuaternion[3] = gq3
                gameRotVectorTimestampNs = timestampNs
                hasGameRotVector = true
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
        try {
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
        } catch (_: RejectedExecutionException) {
            tcnInferenceInFlight.set(false)
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
        val diagnosticEstimatedSpeed = estimatedSpeedProvider?.invoke() ?: estimatedSpeedMps
        csvRecorder?.setEstimatedSpeedMps(diagnosticEstimatedSpeed)
        csvRecorder?.setTcnSpeedMps(tcnRawSpeedMps)
        csvRecorder?.setRawTimestamps(accelTimestampNs, gyroTimestampNs)
        val locForMetadata = rawLastLocation ?: lastLocation
        csvRecorder?.setGnssMetadata(
            timestampMs = locForMetadata?.time ?: 0L,
            elapsedRealtimeNs = locForMetadata?.elapsedRealtimeNanos ?: 0L,
            altitudeM = if (locForMetadata != null && hasAltitude(locForMetadata)) locForMetadata.altitude else Double.NaN
        )
        csvRecorder?.setNavigationDiagnostics(navigationDiagnosticsProvider?.invoke() ?: CsvNavigationDiagnostics())
        val corrAccel = FloatArray(3)
        val corrLinear = FloatArray(3)
        val corrGyro = FloatArray(3)

        if (isCalibrated) {
            transformToVehicleFrame(rawAccel, corrAccel)
            transformToVehicleFrame(linearAccel, corrLinear)
            transformToVehicleFrame(rawGyro, corrGyro)
        }

        // A latched zero is not a measurement. Keep the navigation row
        // explicit about which asynchronous sensor channels were available.
        val loggedLinear = if (hasLinearAccel) linearAccel.copyOf() else FloatArray(3) { Float.NaN }
        val loggedGravity = if (hasGravity) gravity.copyOf() else FloatArray(3) { Float.NaN }
        val loggedGyro = if (hasGyro) rawGyro.copyOf() else FloatArray(3) { Float.NaN }
        val loggedQuaternion = if (hasRotVector) quaternion.copyOf() else FloatArray(4) { Float.NaN }
        val loggedCorrAccel = if (isCalibrated) corrAccel else FloatArray(3) { Float.NaN }
        val loggedCorrLinear = if (isCalibrated && hasLinearAccel) corrLinear else FloatArray(3) { Float.NaN }
        val loggedCorrGyro = if (isCalibrated && hasGyro) corrGyro else FloatArray(3) { Float.NaN }

        val loc = rawLastLocation ?: lastLocation
        if (loc != null) {
            csvRecorder?.writeRow(
                timestampNs = timestampNs,
                accelX = rawAccel[0], accelY = rawAccel[1], accelZ = rawAccel[2],
                linearX = loggedLinear[0], linearY = loggedLinear[1], linearZ = loggedLinear[2],
                gravX = loggedGravity[0], gravY = loggedGravity[1], gravZ = loggedGravity[2],
                gyroX = loggedGyro[0], gyroY = loggedGyro[1], gyroZ = loggedGyro[2],
                qw = loggedQuaternion[0], qx = loggedQuaternion[1], qy = loggedQuaternion[2], qz = loggedQuaternion[3],
                corrAccelFwd = loggedCorrAccel[0], corrAccelLeft = loggedCorrAccel[1], corrAccelUp = loggedCorrAccel[2],
                corrLinearFwd = loggedCorrLinear[0], corrLinearLeft = loggedCorrLinear[1], corrLinearUp = loggedCorrLinear[2],
                corrGyroFwd = loggedCorrGyro[0], corrGyroLeft = loggedCorrGyro[1], corrGyroUp = loggedCorrGyro[2],
                latitude = loc.latitude, longitude = loc.longitude,
                gpsAccuracyM = loc.accuracy,
                gpsSpeedMps = if (hasSpeed(loc)) loc.speed else Float.NaN,
                gpsBearingDeg = if (hasBearing(loc)) loc.bearing else Float.NaN
            )
        } else {
            csvRecorder?.writeRow(
                timestampNs = timestampNs,
                accelX = rawAccel[0], accelY = rawAccel[1], accelZ = rawAccel[2],
                linearX = loggedLinear[0], linearY = loggedLinear[1], linearZ = loggedLinear[2],
                gravX = loggedGravity[0], gravY = loggedGravity[1], gravZ = loggedGravity[2],
                gyroX = loggedGyro[0], gyroY = loggedGyro[1], gyroZ = loggedGyro[2],
                qw = loggedQuaternion[0], qx = loggedQuaternion[1], qy = loggedQuaternion[2], qz = loggedQuaternion[3],
                corrAccelFwd = loggedCorrAccel[0], corrAccelLeft = loggedCorrAccel[1], corrAccelUp = loggedCorrAccel[2],
                corrLinearFwd = loggedCorrLinear[0], corrLinearLeft = loggedCorrLinear[1], corrLinearUp = loggedCorrLinear[2],
                corrGyroFwd = loggedCorrGyro[0], corrGyroLeft = loggedCorrGyro[1], corrGyroUp = loggedCorrGyro[2]
            )
        }
    }

    fun setEstimatedSpeedForDiagnostics(speedMps: Float) {
        estimatedSpeedMps = speedMps
    }

    fun setEstimatedSpeedProviderForDiagnostics(provider: (() -> Float)?) {
        estimatedSpeedProvider = provider
    }

    fun setNavigationDiagnosticsProvider(provider: (() -> CsvNavigationDiagnostics)?) {
        navigationDiagnosticsProvider = provider
    }

    @Volatile var displayRotation: Int = Surface.ROTATION_0
        private set

    fun setDisplayRotation(rotation: Int) {
        displayRotation = rotation
    }

    /**
     * Computes the direction the physical phone's top edge is pointing in world coordinates
     * (0° North, 90° East, 180° South, 270° West, clockwise).
     *
     * Correctly accounts for:
     * 1. Display rotation (Surface.ROTATION_0, ROTATION_90, ROTATION_180, ROTATION_270).
     * 2. Vertical/steep car-dock mounts (> 60° pitch): when the phone is mounted upright
     *    facing the driver, the top edge points to the sky, and the back-normal (-Z) points
     *    forward through the windshield towards the road.
     */
    fun computeDeviceAzimuth(r: FloatArray, rotation: Int): Float {
        // Remap screen visual top edge vector based on display orientation
        val (topX, topY, topZ) = when (rotation) {
            Surface.ROTATION_90  -> Triple(-r[0], -r[3], -r[6]) // -X of device = top of screen
            Surface.ROTATION_180 -> Triple(-r[1], -r[4], -r[7]) // -Y of device = top of screen
            Surface.ROTATION_270 -> Triple( r[0],  r[3],  r[6]) // +X of device = top of screen
            else                 -> Triple( r[1],  r[4],  r[7]) // +Y of device = top of screen
        }

        val horizNormSq = topX * topX + topY * topY
        val azimuth = if (horizNormSq > 0.05f) {
            // Standard handheld or angled mount
            Math.toDegrees(atan2(topX.toDouble(), topY.toDouble())).toFloat()
        } else {
            // Near-vertical upright mount (car holder on dashboard):
            // Visual top of screen points at roof. The forward view is along the back normal (-Z).
            // In Android ENU world frame, -Z column is (-r[2], -r[5], -r[8]).
            val backX = -r[2]
            val backY = -r[5]
            if (backX * backX + backY * backY > 0.01f) {
                Math.toDegrees(atan2(backX.toDouble(), backY.toDouble())).toFloat()
            } else {
                Math.toDegrees(atan2(topX.toDouble(), topY.toDouble())).toFloat()
            }
        }
        return normalizeHeading(azimuth)
    }

    private fun transformToVehicleFrame(vPhone: FloatArray, vVehicle: FloatArray) {
        val wx = rCurrent[0] * vPhone[0] + rCurrent[1] * vPhone[1] + rCurrent[2] * vPhone[2]
        val wy = rCurrent[3] * vPhone[0] + rCurrent[4] * vPhone[1] + rCurrent[5] * vPhone[2]
        val wz = rCurrent[6] * vPhone[0] + rCurrent[7] * vPhone[1] + rCurrent[8] * vPhone[2]

        vVehicle[0] = rCal[0] * wx + rCal[3] * wy + rCal[6] * wz
        vVehicle[1] = rCal[1] * wx + rCal[4] * wy + rCal[7] * wz
        vVehicle[2] = rCal[2] * wx + rCal[5] * wy + rCal[8] * wz
    }

    fun setPhoneToVehicleRotation(r: FloatArray) {
        require(r.size == 9) { "Rotation matrix must be 9 elements" }
        synchronized(this) {
            System.arraycopy(r, 0, rCal, 0, 9)
            isCalibrated = true
        }
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
        val deviceAzimuth = if (hasRotVector) {
            computeDeviceAzimuth(rCurrent, displayRotation)
        } else 0f
        val compassHeadingDeg = deviceAzimuth

        val magMag = sqrt(rawMag[0] * rawMag[0] + rawMag[1] * rawMag[1] + rawMag[2] * rawMag[2])
        val (rotSource, headingConf) = when {
            hasRotVector -> {
                val conf = when {
                    hasMag && magMag in 25f..65f -> DeviceHeadingConfidence.HIGH
                    hasMag && magMag in 15f..80f -> DeviceHeadingConfidence.MEDIUM
                    hasMag -> DeviceHeadingConfidence.LOW // Severe magnetic anomaly
                    else -> DeviceHeadingConfidence.MEDIUM
                }
                Pair(RotationSource.ROTATION_VECTOR, conf)
            }
            hasGameRotVector -> {
                // Game Rotation Vector provides magnetic-immune tilt/relative orientation, but no geographic north
                Pair(RotationSource.GAME_ROTATION_VECTOR, DeviceHeadingConfidence.LOW)
            }
            else -> Pair(RotationSource.NONE, DeviceHeadingConfidence.LOW)
        }

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
        val nowMonotonicNs = elapsedRealtimeNanosCompat()
        val fixAgeMs = if (lastGpsFixMonotonicNs > 0L) {
            ((nowMonotonicNs - lastGpsFixMonotonicNs).coerceAtLeast(0L) / 1_000_000L)
        } else {
            -1L
        }
        val hasGps = loc != null && fixAgeMs in 0..10000L

        val tcnAgeMs = if (lastTcnInferenceTimestampNs > 0L) {
            (elapsedRealtimeNanosCompat() - lastTcnInferenceTimestampNs).coerceAtLeast(0L) / 1_000_000L
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
            gpsTimestampNs = lastGpsFixMonotonicNs,
            latitude = loc?.latitude ?: 0.0,
            longitude = loc?.longitude ?: 0.0,
            altitude = if (loc != null && hasAltitude(loc)) loc.altitude else Double.NaN,
            gpsSpeedMps = if (loc != null && hasSpeed(loc)) loc.speed else Float.NaN,
            gpsBearingDeg = if (loc != null && hasBearing(loc)) loc.bearing else Float.NaN,
            gpsAccuracyM = loc?.accuracy ?: Float.NaN,
            compassBearingDeg = compassHeadingDeg,
            deviceAzimuthDeg = deviceAzimuth,
            rotationSource = rotSource,
            deviceHeadingConfidence = headingConf,
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
            gpsFixAgeMs = fixAgeMs,
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

    private fun normalizeHeading(valueDeg: Float): Float {
        val normalized = valueDeg % 360f
        return if (normalized < 0f) normalized + 360f else normalized
    }

    // Location.has*() was added in API 26. On older devices the platform does
    // not expose a reliable presence bit, so report those optional fields as
    // missing rather than treating a default zero as a measurement.
    private fun hasAltitude(location: Location): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasAltitude()

    private fun hasSpeed(location: Location): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeed() && location.speed.isFinite()

    private fun hasBearing(location: Location): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasBearing() && location.bearing.isFinite()
}
