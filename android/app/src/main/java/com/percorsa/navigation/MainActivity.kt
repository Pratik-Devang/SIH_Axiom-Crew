package com.percorsa.navigation

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.percorsa.navigation.ui.theme.PercorsaTheme
import kotlin.math.abs
import android.os.SystemClock

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var tripLogger: TripLogger

    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var linearAccelerometer: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var rotationSensor: Sensor? = null

    // ---------------------------------------------------------
    // LATEST ACCELEROMETER VALUES
    // ---------------------------------------------------------

    private var ax by mutableStateOf(0f)
    private var ay by mutableStateOf(0f)
    private var az by mutableStateOf(0f)

    // ---------------------------------------------------------
    // LATEST GYROSCOPE VALUES
    // ---------------------------------------------------------

    private var gx by mutableStateOf(0f)
    private var gy by mutableStateOf(0f)
    private var gz by mutableStateOf(0f)
    // ---------------------------------------------------------
// LINEAR ACCELERATION (GRAVITY REMOVED)
// ---------------------------------------------------------

    private var linearAx by mutableStateOf(0f)
    private var linearAy by mutableStateOf(0f)
    private var linearAz by mutableStateOf(0f)


// ---------------------------------------------------------
// GRAVITY VECTOR
// ---------------------------------------------------------

    private var gravityX by mutableStateOf(0f)
    private var gravityY by mutableStateOf(0f)
    private var gravityZ by mutableStateOf(0f)


// ---------------------------------------------------------
// ORIENTATION
// ---------------------------------------------------------

    private var pitch by mutableStateOf(0f)
    private var roll by mutableStateOf(0f)
    private var yaw by mutableStateOf(0f)

    // ---------------------------------------------------------
    // RAW SENSOR SAMPLES
    // ---------------------------------------------------------

    private var latestAccelerometer: RawAccelerometerSample? = null
    private var latestGyroscope: RawGyroscopeSample? = null

    // ---------------------------------------------------------
    // COMBINED IMU SAMPLE
    // ---------------------------------------------------------

    private var latestSample by mutableStateOf<SensorSample?>(null)

    // ---------------------------------------------------------
    // SENSOR BUFFER
    // ---------------------------------------------------------

    private val sensorBuffer = SensorBuffer()

    // ---------------------------------------------------------
    // RATE TRACKERS
    // ---------------------------------------------------------

    private val accelerometerRateTracker = SensorRateTracker()
    private val gyroscopeRateTracker = SensorRateTracker()

    // ---------------------------------------------------------
    // TIMING TRACKERS
    // ---------------------------------------------------------

    private val accelerometerTimingTracker = SensorTimingTracker()
    private val gyroscopeTimingTracker = SensorTimingTracker()

    // ---------------------------------------------------------
    // DISPLAYED RATES
    // ---------------------------------------------------------

    private var accelerometerRateHz by mutableStateOf(0f)
    private var gyroscopeRateHz by mutableStateOf(0f)

    // ---------------------------------------------------------
    // DISPLAYED TIMING STATISTICS
    // ---------------------------------------------------------

    private var accelerometerAverageMs by mutableStateOf(0f)
    private var gyroscopeAverageMs by mutableStateOf(0f)

    private var accelerometerMinMs by mutableStateOf(0f)
    private var gyroscopeMinMs by mutableStateOf(0f)

    private var accelerometerMaxMs by mutableStateOf(0f)
    private var gyroscopeMaxMs by mutableStateOf(0f)

    // ---------------------------------------------------------
    // BUFFER SIZE
    // ---------------------------------------------------------

    private var bufferSize by mutableStateOf(0)

    // ---------------------------------------------------------
    // TRIP RECORDING
    // ---------------------------------------------------------

    private var isRecording by mutableStateOf(false)
    // Trip statistics
    private var tripStartTimeMs by mutableStateOf(0L)
    private var tripDurationMs by mutableStateOf(0L)
    private var recordedSamples by mutableStateOf(0)

    // Latest sensor synchronization difference
    private var sensorSyncDifferenceMs by mutableStateOf(0f)
    private var lastUiUpdateMs = 0L


    // =========================================================
    // ACTIVITY LIFECYCLE
    // =========================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager =
            getSystemService(SENSOR_SERVICE) as SensorManager

        tripLogger = TripLogger(this)

        accelerometer =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_ACCELEROMETER
            )

        gyroscope =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_GYROSCOPE
            )
        linearAccelerometer =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_LINEAR_ACCELERATION
            )

        gravitySensor =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_GRAVITY
            )

        rotationSensor =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_ROTATION_VECTOR
            )

        // Start recording automatically for now.
        tripLogger.startTrip()
        tripStartTimeMs = android.os.SystemClock.elapsedRealtime()
        tripDurationMs = 0L
        recordedSamples = 0
        isRecording = true

        setContent {
            PercorsaTheme {

                SensorDashboard(

                    ax = ax,
                    ay = ay,
                    az = az,

                    gx = gx,
                    gy = gy,
                    gz = gz,

                    linearAx = linearAx,
                    linearAy = linearAy,
                    linearAz = linearAz,

                    gravityX = gravityX,
                    gravityY = gravityY,
                    gravityZ = gravityZ,

                    pitch = pitch,
                    roll = roll,
                    yaw = yaw,

                    timestampNs =
                        latestSample?.timestampNs ?: 0L,

                    bufferSize = bufferSize,

                    accelerometerRateHz =
                        accelerometerRateHz,

                    gyroscopeRateHz =
                        gyroscopeRateHz,

                    accelerometerAverageMs =
                        accelerometerAverageMs,

                    gyroscopeAverageMs =
                        gyroscopeAverageMs,

                    accelerometerMinMs =
                        accelerometerMinMs,

                    gyroscopeMinMs =
                        gyroscopeMinMs,

                    accelerometerMaxMs =
                        accelerometerMaxMs,

                    gyroscopeMaxMs =
                        gyroscopeMaxMs,

                    accelerometerAvailable =
                        accelerometer != null,

                    gyroscopeAvailable =
                        gyroscope != null,

                    isRecording = isRecording,
                    tripStartTimeMs = tripStartTimeMs,
                    tripDurationMs = tripDurationMs,
                    recordedSamples = recordedSamples,
                    sensorSyncDifferenceMs = sensorSyncDifferenceMs,

                    onStartTrip = {
                        if (!isRecording) {

                            tripLogger.startTrip()

                            tripStartTimeMs =
                                android.os.SystemClock.elapsedRealtime()

                            tripDurationMs = 0L
                            recordedSamples = 0

                            isRecording = true
                        }
                    },

                    onStopTrip = {
                        if (isRecording) {

                            tripLogger.stopTrip()

                            tripDurationMs =
                                android.os.SystemClock.elapsedRealtime() -
                                        tripStartTimeMs

                            isRecording = false
                        }
                    },

                    onClearBuffer = {

                        sensorBuffer.clear()

                        bufferSize =
                            sensorBuffer.size()
                    },

                    onExportLog = {
                        exportCurrentLog()
                    }
                )
            }
        }
    }


    override fun onResume() {

        super.onResume()

        accelerometer?.let { sensor ->

            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        gyroscope?.let { sensor ->

            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
        linearAccelerometer?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        gravitySensor?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        rotationSensor?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

    }


    override fun onPause() {

        super.onPause()

        sensorManager.unregisterListener(this)
    }


    override fun onDestroy() {

        if (isRecording) {

            tripLogger.stopTrip()
        }

        tripLogger.shutdown()

        super.onDestroy()
    }


    // =========================================================
    // SENSOR CALLBACK
    // =========================================================

    override fun onSensorChanged(event: SensorEvent) {

        when (event.sensor.type) {

            Sensor.TYPE_ACCELEROMETER -> {

                val eventAx = event.values[0]
                val eventAy = event.values[1]
                val eventAz = event.values[2]

                // Raw acquisition remains at full sensor rate.
                latestAccelerometer =
                    RawAccelerometerSample(
                        timestampNs = event.timestamp,
                        ax = eventAx,
                        ay = eventAy,
                        az = eventAz
                    )

                accelerometerTimingTracker.update(
                    event.timestamp
                )

                accelerometerRateTracker.update(
                    event.timestamp
                )

                // Only publish values to Compose at ~10 Hz.
                val nowMs = SystemClock.elapsedRealtime()

                if (nowMs - lastUiUpdateMs >= 100) {

                    ax = eventAx
                    ay = eventAy
                    az = eventAz

                    accelerometerAverageMs =
                        accelerometerTimingTracker.averageIntervalMs

                    accelerometerMinMs =
                        accelerometerTimingTracker.minimumIntervalMs

                    accelerometerMaxMs =
                        accelerometerTimingTracker.maximumIntervalMs

                    accelerometerRateHz =
                        accelerometerRateTracker.rateHz

                    lastUiUpdateMs = nowMs
                }
            }


            Sensor.TYPE_GYROSCOPE -> {

                val eventGx = event.values[0]
                val eventGy = event.values[1]
                val eventGz = event.values[2]

                // Raw acquisition remains at full sensor rate.
                latestGyroscope =
                    RawGyroscopeSample(
                        timestampNs = event.timestamp,
                        gx = eventGx,
                        gy = eventGy,
                        gz = eventGz
                    )

                gyroscopeTimingTracker.update(
                    event.timestamp
                )

                gyroscopeRateTracker.update(
                    event.timestamp
                )

                // Only publish values to Compose at ~10 Hz.
                val nowMs = SystemClock.elapsedRealtime()

                if (nowMs - lastUiUpdateMs >= 100) {

                    gx = eventGx
                    gy = eventGy
                    gz = eventGz

                    gyroscopeAverageMs =
                        gyroscopeTimingTracker.averageIntervalMs

                    gyroscopeMinMs =
                        gyroscopeTimingTracker.minimumIntervalMs

                    gyroscopeMaxMs =
                        gyroscopeTimingTracker.maximumIntervalMs

                    gyroscopeRateHz =
                        gyroscopeRateTracker.rateHz

                    lastUiUpdateMs = nowMs
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {

                val eventLinearAx = event.values[0]
                val eventLinearAy = event.values[1]
                val eventLinearAz = event.values[2]

                val nowMs = SystemClock.elapsedRealtime()

                if (nowMs - lastUiUpdateMs >= 100) {
                    linearAx = eventLinearAx
                    linearAy = eventLinearAy
                    linearAz = eventLinearAz
                }
            }


            Sensor.TYPE_GRAVITY -> {

                val eventGravityX = event.values[0]
                val eventGravityY = event.values[1]
                val eventGravityZ = event.values[2]

                val nowMs = SystemClock.elapsedRealtime()

                if (nowMs - lastUiUpdateMs >= 100) {
                    gravityX = eventGravityX
                    gravityY = eventGravityY
                    gravityZ = eventGravityZ
                }
            }


            Sensor.TYPE_ROTATION_VECTOR -> {

                val rotationMatrix = FloatArray(9)

                SensorManager.getRotationMatrixFromVector(
                    rotationMatrix,
                    event.values
                )

                val orientation = FloatArray(3)

                SensorManager.getOrientation(
                    rotationMatrix,
                    orientation
                )

                val nowMs = SystemClock.elapsedRealtime()

                if (nowMs - lastUiUpdateMs >= 100) {

                    // Convert radians → degrees
                    yaw =
                        Math.toDegrees(
                            orientation[0].toDouble()
                        ).toFloat()

                    pitch =
                        Math.toDegrees(
                            orientation[1].toDouble()
                        ).toFloat()

                    roll =
                        Math.toDegrees(
                            orientation[2].toDouble()
                        ).toFloat()
                }
            }
        }

        createAlignedSample()
    }


    // =========================================================
    // CREATE ALIGNED IMU SAMPLE
    // =========================================================

    private fun createAlignedSample() {

        val accel =
            latestAccelerometer ?: return

        val gyro =
            latestGyroscope ?: return

        val timeDifferenceNs =
            abs(
                accel.timestampNs -
                        gyro.timestampNs
            )
        val syncDifferenceMs =
            timeDifferenceNs / 1_000_000f

        // Accept measurements within 2 ms.
        if (timeDifferenceNs > 2_000_000L) {
            return
        }

        val sample =
            SensorSample(

                timestampNs =
                    maxOf(
                        accel.timestampNs,
                        gyro.timestampNs
                    ),

                ax = accel.ax,
                ay = accel.ay,
                az = accel.az,

                gx = gyro.gx,
                gy = gyro.gy,
                gz = gyro.gz
            )

        // Keep the actual sensor pipeline at full rate.
        sensorBuffer.add(sample)

        if (isRecording) {
            tripLogger.logSample(sample)
        }

        // Publish UI-facing state only ~10 times per second.
        val nowMs = SystemClock.elapsedRealtime()

        if (nowMs - lastUiUpdateMs >= 100) {

            latestSample = sample
            bufferSize = sensorBuffer.size()
            sensorSyncDifferenceMs = syncDifferenceMs

            if (isRecording) {
                recordedSamples = sensorBuffer.size()
                tripDurationMs = nowMs - tripStartTimeMs
            }

            lastUiUpdateMs = nowMs
        }
    }


    // =========================================================
    // EXPORT CURRENT TRIP LOG
    // =========================================================

    private fun exportCurrentLog() {

        if (isRecording) {
            Toast.makeText(
                this,
                "Stop the trip before exporting the log.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val file = tripLogger.getCurrentLogFile()

        if (file == null || !file.exists()) {
            Toast.makeText(
                this,
                "No trip log available.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {

            val uri =
                FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )

            val shareIntent =
                Intent(Intent.ACTION_SEND).apply {

                    type = "text/csv"

                    putExtra(
                        Intent.EXTRA_STREAM,
                        uri
                    )

                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

            startActivity(
                Intent.createChooser(
                    shareIntent,
                    "Share Percorsa Trip Log"
                )
            )

        } catch (exception: Exception) {

            Toast.makeText(
                this,
                "Unable to export trip log.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) {
        // Sensor accuracy handling can be added later.
    }
}


/*
 * =========================================================
 * PERCORSA SENSOR DASHBOARD
 * =========================================================
 */

@androidx.compose.runtime.Composable
fun SensorDashboard(

    ax: Float,
    ay: Float,
    az: Float,

    gx: Float,
    gy: Float,
    gz: Float,

    linearAx: Float,
    linearAy: Float,
    linearAz: Float,

    gravityX: Float,
    gravityY: Float,
    gravityZ: Float,

    pitch: Float,
    roll: Float,
    yaw: Float,

    timestampNs: Long,

    bufferSize: Int,

    accelerometerRateHz: Float,
    gyroscopeRateHz: Float,

    accelerometerAverageMs: Float,
    gyroscopeAverageMs: Float,

    accelerometerMinMs: Float,
    gyroscopeMinMs: Float,

    accelerometerMaxMs: Float,
    gyroscopeMaxMs: Float,

    accelerometerAvailable: Boolean,
    gyroscopeAvailable: Boolean,

    isRecording: Boolean,
    tripStartTimeMs: Long,
    tripDurationMs: Long,
    recordedSamples: Int,
    sensorSyncDifferenceMs: Float,

    onStartTrip: () -> Unit,
    onStopTrip: () -> Unit,
    onClearBuffer: () -> Unit,
    onExportLog: () -> Unit

) {

    val scrollState =
        rememberScrollState()

    Column(

        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    horizontal = 18.dp,
                    vertical = 20.dp
                ),

        verticalArrangement =
            Arrangement.spacedBy(14.dp)

    ) {

        // =====================================================
        // HEADER
        // =====================================================

        Text(
            text = "PERCORSA",
            style =
                MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Smartphone Navigation System",
            style =
                MaterialTheme.typography.bodyMedium
        )


        // =====================================================
        // NAVIGATION / MAP PLACEHOLDER
        // =====================================================

        Text(
            text = "NAVIGATION",
            style =
                MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        NavigationPlaceholder()


        // =====================================================
        // SYSTEM STATUS
        // =====================================================

        Text(
            text = "SYSTEM STATUS",
            style =
                MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(18.dp),

            colors =
                CardDefaults.cardColors(
                    containerColor =
                        MaterialTheme.colorScheme.surfaceVariant
                )
        ) {

            Column(

                modifier =
                    Modifier.padding(16.dp),

                verticalArrangement =
                    Arrangement.spacedBy(10.dp)

            ) {

                StatusRow(
                    title = "IMU SYSTEM",
                    status =
                        if (
                            accelerometerAvailable &&
                            gyroscopeAvailable
                        ) {
                            "READY"
                        } else {
                            "CHECK SENSORS"
                        }
                )

                HorizontalDivider()

                StatusRow(
                    title = "TRIP RECORDING",
                    status =
                        if (isRecording) {
                            "ACTIVE"
                        } else {
                            "STOPPED"
                        }
                )
            }
        }


        // =====================================================
        // TRIP CONTROLS
        // =====================================================

        Text(
            text = "TRIP CONTROL",
            style =
                MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Row(

            modifier =
                Modifier.fillMaxWidth(),

            horizontalArrangement =
                Arrangement.spacedBy(10.dp)

        ) {

            Button(

                onClick = onStartTrip,

                modifier =
                    Modifier.weight(1f),

                enabled =
                    !isRecording

            ) {

                Text("START")
            }


            OutlinedButton(

                onClick = onStopTrip,

                modifier =
                    Modifier.weight(1f),

                enabled =
                    isRecording

            ) {

                Text("STOP")
            }
        }
        // =====================================================
// TRIP SUMMARY
// =====================================================

        Text(
            text = "TRIP SUMMARY",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                TripStatRow(
                    label = "Status",
                    value =
                        if (isRecording) {
                            "RECORDING"
                        } else {
                            "STOPPED"
                        }
                )

                HorizontalDivider()

                TripStatRow(
                    label = "Duration",
                    value = formatDuration(tripDurationMs)
                )

                TripStatRow(
                    label = "Samples logged",
                    value = recordedSamples.toString()
                )

                TripStatRow(
                    label = "Buffer",
                    value = "$bufferSize / 500"
                )

                TripStatRow(
                    label = "IMU sync",
                    value =
                        "%.2f ms".format(
                            sensorSyncDifferenceMs
                        )
                )

                TripStatRow(
                    label = "Log file",
                    value =
                        if (isRecording) {
                            "ACTIVE"
                        } else {
                            "READY"
                        }
                )
            }
        }


        // =====================================================
        // SENSOR STATUS
        // =====================================================

        Text(
            text = "SENSOR STATUS",
            style =
                MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Row(

            modifier =
                Modifier.fillMaxWidth(),

            horizontalArrangement =
                Arrangement.spacedBy(10.dp)

        ) {

            SensorStatusCard(

                modifier =
                    Modifier.weight(1f),

                title = "ACCELEROMETER",

                available =
                    accelerometerAvailable,

                rateHz =
                    accelerometerRateHz
            )


            SensorStatusCard(

                modifier =
                    Modifier.weight(1f),

                title = "GYROSCOPE",

                available =
                    gyroscopeAvailable,

                rateHz =
                    gyroscopeRateHz
            )
        }


        // =====================================================
        // LIVE MOTION
        // =====================================================

        Text(
            text = "LIVE MOTION",
            style =
                MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Card(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(18.dp)

        ) {

            Column(

                modifier =
                    Modifier.padding(16.dp),

                verticalArrangement =
                    Arrangement.spacedBy(12.dp)

            ) {

                Text(
                    text = "ACCELERATION",
                    style =
                        MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Row(

                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.SpaceBetween

                ) {

                    MotionValue(
                        label = "X",
                        value = ax,
                        unit = "m/s²"
                    )

                    MotionValue(
                        label = "Y",
                        value = ay,
                        unit = "m/s²"
                    )

                    MotionValue(
                        label = "Z",
                        value = az,
                        unit = "m/s²"
                    )
                }
                // =====================================================
// LINEAR ACCELERATION
// GRAVITY REMOVED
// =====================================================

                Text(
                    text = "LINEAR ACCELERATION",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {

                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                        Text(
                            text = "Gravity Removed",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {

                            MotionValue(
                                label = "X",
                                value = linearAx,
                                unit = "m/s²"
                            )

                            MotionValue(
                                label = "Y",
                                value = linearAy,
                                unit = "m/s²"
                            )

                            MotionValue(
                                label = "Z",
                                value = linearAz,
                                unit = "m/s²"
                            )
                        }
                    }
                }
                // =====================================================
// GRAVITY VECTOR
// =====================================================

                Text(
                    text = "GRAVITY",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {

                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                        Text(
                            text = "Gravity Vector",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {

                            MotionValue(
                                label = "X",
                                value = gravityX,
                                unit = "m/s²"
                            )

                            MotionValue(
                                label = "Y",
                                value = gravityY,
                                unit = "m/s²"
                            )

                            MotionValue(
                                label = "Z",
                                value = gravityZ,
                                unit = "m/s²"
                            )
                        }
                    }
                }
                // =====================================================
// ORIENTATION / INCLINOMETER
// =====================================================

                Text(
                    text = "ORIENTATION",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {

                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                        Text(
                            text = "Device Orientation",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {

                            MotionValue(
                                label = "Pitch",
                                value = pitch,
                                unit = "°"
                            )

                            MotionValue(
                                label = "Roll",
                                value = roll,
                                unit = "°"
                            )

                            MotionValue(
                                label = "Yaw",
                                value = yaw,
                                unit = "°"
                            )
                        }
                    }
                }



                Spacer(
                    modifier =
                        Modifier.height(4.dp)
                )


                Text(
                    text = "ANGULAR VELOCITY",
                    style =
                        MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Row(

                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.SpaceBetween

                ) {

                    MotionValue(
                        label = "X",
                        value = gx,
                        unit = "rad/s"
                    )

                    MotionValue(
                        label = "Y",
                        value = gy,
                        unit = "rad/s"
                    )

                    MotionValue(
                        label = "Z",
                        value = gz,
                        unit = "rad/s"
                    )
                }
            }
        }


        // =====================================================
        // BUFFER
        // =====================================================

        Text(
            text = "DATA BUFFER",
            style =
                MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Card(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(18.dp)

        ) {

            Column(

                modifier =
                    Modifier.padding(16.dp),

                verticalArrangement =
                    Arrangement.spacedBy(10.dp)

            ) {

                Row(

                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.SpaceBetween

                ) {

                    Text(
                        text = "Buffered samples"
                    )

                    Text(
                        text = "$bufferSize / 500",
                        fontWeight =
                            FontWeight.Bold
                    )
                }


                LinearProgressIndicator(

                    progress = {
                        (bufferSize / 500f)
                            .coerceIn(0f, 1f)
                    },

                    modifier =
                        Modifier.fillMaxWidth()
                )


                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(10.dp)
                ) {

                    OutlinedButton(
                        onClick =
                            onClearBuffer,

                        modifier =
                            Modifier.weight(1f)
                    ) {
                        Text("CLEAR")
                    }

                    Button(
                        onClick =
                            onExportLog,

                        modifier =
                            Modifier.weight(1f)
                    ) {
                        Text("EXPORT CSV")
                    }
                }
            }
        }


        // =====================================================
        // TIMING DIAGNOSTICS
        // =====================================================

        Text(
            text = "TIMING DIAGNOSTICS",
            style =
                MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Card(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(18.dp)

        ) {

            Column(

                modifier =
                    Modifier.padding(16.dp),

                verticalArrangement =
                    Arrangement.spacedBy(12.dp)

            ) {

                TimingRow(
                    title = "Accelerometer",
                    average = accelerometerAverageMs,
                    minimum = accelerometerMinMs,
                    maximum = accelerometerMaxMs
                )

                HorizontalDivider()

                TimingRow(
                    title = "Gyroscope",
                    average = gyroscopeAverageMs,
                    minimum = gyroscopeMinMs,
                    maximum = gyroscopeMaxMs
                )
            }
        }


        // =====================================================
        // LATEST SAMPLE
        // =====================================================

        Card(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(18.dp),

            colors =
                CardDefaults.cardColors(
                    containerColor =
                        MaterialTheme.colorScheme.surfaceVariant
                )
        ) {

            Column(

                modifier =
                    Modifier.padding(16.dp),

                verticalArrangement =
                    Arrangement.spacedBy(5.dp)

            ) {

                Text(
                    text = "LATEST IMU SAMPLE",
                    style =
                        MaterialTheme.typography.labelLarge,
                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    text =
                        if (timestampNs == 0L) {
                            "Waiting for sensor data..."
                        } else {
                            "$timestampNs ns"
                        }
                )
            }
        }


        Spacer(
            modifier =
                Modifier.height(12.dp)
        )
    }
}


/*
 * =========================================================
 * NAVIGATION PLACEHOLDER
 *
 * IMPORTANT:
 * This area is intentionally kept independent from the
 * sensor system.
 *
 * Your teammate can later replace this composable with:
 *
 * - Google Maps
 * - OpenStreetMap
 * - Current location
 * - Route display
 * - Dead reckoning position
 * - GNSS recovery
 *
 * without changing the sensor pipeline above.
 * =========================================================
 */

@androidx.compose.runtime.Composable
fun NavigationPlaceholder() {

    Card(

        modifier =
            Modifier
                .fillMaxWidth()
                .height(230.dp),

        shape =
            RoundedCornerShape(20.dp),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surfaceVariant
            )
    ) {

        Box(
            modifier =
                Modifier.fillMaxSize(),

            contentAlignment =
                Alignment.Center
        ) {

            Column(

                horizontalAlignment =
                    Alignment.CenterHorizontally,

                verticalArrangement =
                    Arrangement.spacedBy(8.dp)

            ) {

                Text(
                    text = "MAP / NAVIGATION",
                    style =
                        MaterialTheme.typography.titleLarge,
                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    text = "Navigation module reserved"
                )

                Text(
                    text =
                        "GNSS • Dead Reckoning • Route",
                    style =
                        MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}


/*
 * =========================================================
 * SENSOR STATUS CARD
 * =========================================================
 */

@androidx.compose.runtime.Composable
fun SensorStatusCard(

    modifier: Modifier,

    title: String,

    available: Boolean,

    rateHz: Float

) {

    Card(

        modifier = modifier,

        shape =
            RoundedCornerShape(18.dp)

    ) {

        Column(

            modifier =
                Modifier.padding(14.dp),

            verticalArrangement =
                Arrangement.spacedBy(6.dp)

        ) {

            Text(
                text = title,
                style =
                    MaterialTheme.typography.labelMedium,
                fontWeight =
                    FontWeight.Bold
            )

            Text(
                text =
                    if (available) {
                        "● READY"
                    } else {
                        "○ UNAVAILABLE"
                    }
            )

            Text(
                text =
                    "%.1f Hz".format(rateHz),
                style =
                    MaterialTheme.typography.bodyLarge,
                fontWeight =
                    FontWeight.SemiBold
            )
        }
    }
}


/*
 * =========================================================
 * SYSTEM STATUS ROW
 * =========================================================
 */

@androidx.compose.runtime.Composable
fun StatusRow(

    title: String,

    status: String

) {

    Row(

        modifier =
            Modifier.fillMaxWidth(),

        horizontalArrangement =
            Arrangement.SpaceBetween,

        verticalAlignment =
            Alignment.CenterVertically

    ) {

        Text(
            text = title,
            fontWeight =
                FontWeight.SemiBold
        )

        Text(
            text = status
        )
    }
}


/*
 * =========================================================
 * TIMING ROW
 * =========================================================
 */

@androidx.compose.runtime.Composable
fun TimingRow(

    title: String,

    average: Float,

    minimum: Float,

    maximum: Float

) {

    Column(

        verticalArrangement =
            Arrangement.spacedBy(4.dp)

    ) {

        Text(
            text = title,
            fontWeight =
                FontWeight.SemiBold
        )

        Text(
            text =
                "Avg %.2f ms   Min %.2f ms   Max %.2f ms"
                    .format(
                        average,
                        minimum,
                        maximum
                    ),

            style =
                MaterialTheme.typography.bodyMedium
        )
    }
}


/*
 * =========================================================
 * SMALL MOTION VALUE COMPONENT
 * =========================================================
 */

@androidx.compose.runtime.Composable
fun MotionValue(

    label: String,

    value: Float,

    unit: String

) {

    Column(

        horizontalAlignment =
            Alignment.CenterHorizontally

    ) {

        Text(
            text = label,
            style =
                MaterialTheme.typography.labelLarge,
            fontWeight =
                FontWeight.Bold
        )

        Spacer(
            modifier =
                Modifier.height(3.dp)
        )

        Text(
            text =
                "%.3f".format(value),

            style =
                MaterialTheme.typography.bodyLarge
        )

        Text(
            text = unit,

            style =
                MaterialTheme.typography.labelSmall
        )
    }

}


@androidx.compose.runtime.Composable
fun TripStatRow(
    label: String,
    value: String
) {

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.SpaceBetween,
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Text(
            text = label,
            style =
                MaterialTheme.typography.bodyMedium
        )

        Text(
            text = value,
            fontWeight =
                FontWeight.SemiBold
        )
    }
}


fun formatDuration(durationMs: Long): String {

    val totalSeconds =
        durationMs / 1000

    val hours =
        totalSeconds / 3600

    val minutes =
        (totalSeconds % 3600) / 60

    val seconds =
        totalSeconds % 60

    return if (hours > 0) {

        "%02d:%02d:%02d"
            .format(
                hours,
                minutes,
                seconds
            )

    } else {

        "%02d:%02d"
            .format(
                minutes,
                seconds
            )
    }
}
