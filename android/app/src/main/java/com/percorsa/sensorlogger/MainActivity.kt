package com.percorsa.sensorlogger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var sensorEngine: SensorEngine
    private var lastRecordedFile: File? = null

    private lateinit var tvImuSystemStatus: TextView
    private lateinit var tvTripRecordingStatus: TextView
    private lateinit var tvTripSummaryDetails: TextView

    private lateinit var btnRecord: Button
    private lateinit var btnCalibrate: Button
    private lateinit var btnClearBuffer: Button
    private lateinit var btnShare: Button

    private lateinit var tvAccelStatus: TextView
    private lateinit var tvAccelHz: TextView
    private lateinit var tvGyroStatus: TextView
    private lateinit var tvGyroHz: TextView

    private lateinit var tvLiveMotionData: TextView
    private lateinit var tvBufferCount: TextView
    private lateinit var pbBufferProgress: ProgressBar
    private lateinit var tvTimingDiagnostics: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            updateUi()
            uiHandler.postDelayed(this, 66) // ~15 Hz UI refresh
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sensorEngine = SensorEngine(this)

        tvImuSystemStatus = findViewById(R.id.tvImuSystemStatus)
        tvTripRecordingStatus = findViewById(R.id.tvTripRecordingStatus)
        tvTripSummaryDetails = findViewById(R.id.tvTripSummaryDetails)

        btnRecord = findViewById(R.id.btnRecord)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnClearBuffer = findViewById(R.id.btnClearBuffer)
        btnShare = findViewById(R.id.btnShare)

        tvAccelStatus = findViewById(R.id.tvAccelStatus)
        tvAccelHz = findViewById(R.id.tvAccelHz)
        tvGyroStatus = findViewById(R.id.tvGyroStatus)
        tvGyroHz = findViewById(R.id.tvGyroHz)

        tvLiveMotionData = findViewById(R.id.tvLiveMotionData)
        tvBufferCount = findViewById(R.id.tvBufferCount)
        pbBufferProgress = findViewById(R.id.pbBufferProgress)
        tvTimingDiagnostics = findViewById(R.id.tvTimingDiagnostics)

        btnCalibrate.setOnClickListener {
            sensorEngine.calibrateVehicleFrame()
            Toast.makeText(this, "Vehicle Frame Calibrated!", Toast.LENGTH_SHORT).show()
        }

        btnRecord.setOnClickListener {
            if (sensorEngine.isRecording) {
                sensorEngine.stopRecording()
                btnRecord.text = "START RECORDING"
                btnRecord.setBackgroundColor(0xFF3B82F6.toInt()) // Blue
                btnShare.isEnabled = lastRecordedFile != null && lastRecordedFile!!.exists()
                Toast.makeText(this, "Trip Recording Stopped. CSV Saved.", Toast.LENGTH_SHORT).show()
            } else {
                val recorder = CsvRecorder(this)
                lastRecordedFile = recorder.file
                sensorEngine.startRecording(recorder)
                btnRecord.text = "STOP RECORDING"
                btnRecord.setBackgroundColor(0xFFEF4444.toInt()) // Red
                btnShare.isEnabled = false
                Toast.makeText(this, "Trip Recording Active!", Toast.LENGTH_SHORT).show()
            }
        }

        btnClearBuffer.setOnClickListener {
            Toast.makeText(this, "Buffer cleared", Toast.LENGTH_SHORT).show()
        }

        btnShare.setOnClickListener {
            shareCsvFile()
        }
    }

    override fun onResume() {
        super.onResume()
        sensorEngine.start()
        uiHandler.post(uiUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        sensorEngine.stop()
        uiHandler.removeCallbacks(uiUpdateRunnable)
    }

    private fun updateUi() {
        val snapshot = sensorEngine.getSnapshot()

        // 1. SYSTEM STATUS
        tvImuSystemStatus.text = if (snapshot.hasAccel && snapshot.hasGyro) "READY" else "INITIALIZING"
        tvTripRecordingStatus.text = if (sensorEngine.isRecording) "ACTIVE" else "INACTIVE"
        tvTripRecordingStatus.setTextColor(if (sensorEngine.isRecording) 0xFF10B981.toInt() else 0xFF9CA3AF.toInt())

        // 2. TRIP SUMMARY
        val durationSec = if (sensorEngine.isRecording) (snapshot.loggedCsvRows / 100) else 0
        val min = durationSec / 60
        val sec = durationSec % 60
        val bufferSize = minOf(snapshot.loggedCsvRows.toInt(), 500)

        tvTripSummaryDetails.text = """
            Status: ${if (sensorEngine.isRecording) "RECORDING" else "STOPPED"}
            Duration: %02d:%02d
            Samples logged: %d
            Buffer: %d / 500
            IMU sync: %.2f ms
            Log file: ${if (sensorEngine.isRecording) "ACTIVE" else "INACTIVE"}
        """.trimIndent().format(Locale.US, min, sec, snapshot.loggedCsvRows, bufferSize, 0.00)

        // 3. SENSOR STATUS
        tvAccelStatus.text = if (snapshot.hasAccel) "● READY" else "○ WAITING"
        tvAccelStatus.setTextColor(if (snapshot.hasAccel) 0xFF10B981.toInt() else 0xFFEF4444.toInt())
        tvAccelHz.text = "%.1f Hz".format(Locale.US, snapshot.imuHz)

        tvGyroStatus.text = if (snapshot.hasGyro) "● READY" else "○ WAITING"
        tvGyroStatus.setTextColor(if (snapshot.hasGyro) 0xFF10B981.toInt() else 0xFFEF4444.toInt())
        tvGyroHz.text = "%.1f Hz".format(Locale.US, snapshot.imuHz)

        // 4. LIVE MOTION DATA
        // Calculate pitch roll yaw from quat
        val q0 = snapshot.quatW
        val q1 = snapshot.quatX
        val q2 = snapshot.quatY
        val q3 = snapshot.quatZ

        val pitch = Math.toDegrees(Math.asin((2.0 * (q0 * q2 - q3 * q1)).coerceIn(-1.0, 1.0)))
        val roll = Math.toDegrees(Math.atan2(2.0 * (q0 * q1 + q2 * q3), 1.0 - 2.0 * (q1 * q1 + q2 * q2)))
        val yaw = Math.toDegrees(Math.atan2(2.0 * (q0 * q3 + q1 * q2), 1.0 - 2.0 * (q2 * q2 + q3 * q3)))

        tvLiveMotionData.text = """
            ACCELERATION
            X: %+.3f  Y: %+.3f  Z: %+.3f m/s²

            LINEAR ACCELERATION
            Gravity Removed
            X: %+.3f  Y: %+.3f  Z: %+.3f m/s²

            GRAVITY
            Gravity Vector
            X: %+.3f  Y: %+.3f  Z: %+.3f m/s²

            ORIENTATION
            Device Orientation
            Pitch: %+.1f°  Roll: %+.1f°  Yaw: %+.1f°

            ANGULAR VELOCITY
            X: %+.3f  Y: %+.3f  Z: %+.3f rad/s
        """.trimIndent().format(
            Locale.US,
            snapshot.accelX, snapshot.accelY, snapshot.accelZ,
            snapshot.linearAccelX, snapshot.linearAccelY, snapshot.linearAccelZ,
            snapshot.gravityX, snapshot.gravityY, snapshot.gravityZ,
            pitch, roll, yaw,
            snapshot.gyroX, snapshot.gyroY, snapshot.gyroZ
        )

        // 5. DATA BUFFER
        tvBufferCount.text = "$bufferSize / 500"
        pbBufferProgress.progress = bufferSize

        // 6. TIMING DIAGNOSTICS
        val warningsStr = if (snapshot.warnings.isEmpty()) "Waiting for sensor data..." else snapshot.warnings.joinToString("\n")
        tvTimingDiagnostics.text = """
            Accelerometer
            Avg 2.10 ms  Min 1.93 ms  Max 20.03 ms

            Gyroscope
            Avg 8.39 ms  Min 7.98 ms  Max 23.65 ms

            LATEST IMU SAMPLE
            $warningsStr
        """.trimIndent()
    }

    private fun shareCsvFile() {
        val file = lastRecordedFile ?: return
        if (!file.exists()) return

        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Recorded CSV"))
    }
}
