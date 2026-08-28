package com.percorsa.sensorlogger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * Developer / Debug screen.
 */
class DebugActivity : AppCompatActivity() {

    private var lastRecordedFile: File? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var tvDbgHealthSummary: TextView
    private lateinit var tvDbgHealthDetails: TextView
    private lateinit var tvDbgGpsStatus: TextView
    private lateinit var tvDbgGpsCoords: TextView
    private lateinit var tvDbgGpsAccuracy: TextView
    private lateinit var tvDbgGpsSpeed: TextView
    private lateinit var tvDbgImuHz: TextView
    private lateinit var tvDbgSamples: TextView
    private lateinit var tvDbgAccel: TextView
    private lateinit var tvDbgGyro: TextView
    private lateinit var tvDbgOrient: TextView
    private lateinit var tvDbgNavMode: TextView
    private lateinit var tvDbgDrProvider: TextView
    private lateinit var tvDbgGnssQuality: TextView
    private lateinit var tvDbgAccuracy: TextView
    private lateinit var tvDbgTripStatus: TextView
    private lateinit var tvDbgSampleCount: TextView
    private lateinit var tvDebugRecIndicator: TextView
    private lateinit var btnDebugRecord: Button
    private lateinit var btnDebugCalibrate: Button
    private lateinit var btnDebugShare: Button
    private lateinit var btnDebugBack: TextView

    private val uiRunnable = object : Runnable {
        override fun run() {
            updateDebugUi()
            uiHandler.postDelayed(this, 150)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        tvDbgHealthSummary  = findViewById(R.id.tvDbgHealthSummary)
        tvDbgHealthDetails  = findViewById(R.id.tvDbgHealthDetails)
        tvDbgGpsStatus      = findViewById(R.id.tvDbgGpsStatus)
        tvDbgGpsCoords      = findViewById(R.id.tvDbgGpsCoords)
        tvDbgGpsAccuracy    = findViewById(R.id.tvDbgGpsAccuracy)
        tvDbgGpsSpeed       = findViewById(R.id.tvDbgGpsSpeed)
        tvDbgImuHz          = findViewById(R.id.tvDbgImuHz)
        tvDbgSamples        = findViewById(R.id.tvDbgSamples)
        tvDbgAccel          = findViewById(R.id.tvDbgAccel)
        tvDbgGyro           = findViewById(R.id.tvDbgGyro)
        tvDbgOrient         = findViewById(R.id.tvDbgOrient)
        tvDbgNavMode        = findViewById(R.id.tvDbgNavMode)
        tvDbgDrProvider     = findViewById(R.id.tvDbgDrProvider)
        tvDbgGnssQuality    = findViewById(R.id.tvDbgGnssQuality)
        tvDbgAccuracy       = findViewById(R.id.tvDbgAccuracy)
        tvDbgTripStatus     = findViewById(R.id.tvDbgTripStatus)
        tvDbgSampleCount    = findViewById(R.id.tvDbgSampleCount)
        tvDebugRecIndicator = findViewById(R.id.tvDebugRecIndicator)
        btnDebugRecord      = findViewById(R.id.btnDebugRecord)
        btnDebugCalibrate   = findViewById(R.id.btnDebugCalibrate)
        btnDebugShare       = findViewById(R.id.btnDebugShare)
        btnDebugBack        = findViewById(R.id.btnDebugBack)

        btnDebugBack.setOnClickListener { finish() }

        val navController = MainActivity.navController
        if (navController == null) {
            Toast.makeText(this, "Navigation engine not running", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        btnDebugRecord.setOnClickListener {
            val nc = MainActivity.navController ?: return@setOnClickListener
            if (nc.sensorEngine.isRecording) {
                nc.stopRecording()
                btnDebugRecord.text = "● Start Recording"
                btnDebugRecord.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFFDC2626.toInt())
                btnDebugShare.isEnabled = lastRecordedFile?.exists() == true
                Toast.makeText(this, "Recording saved", Toast.LENGTH_SHORT).show()
            } else {
                val recorder = CsvRecorder(this)
                lastRecordedFile = recorder.file
                nc.startRecording(recorder)
                btnDebugRecord.text = "■ Stop Recording"
                btnDebugRecord.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFF1E293B.toInt())
                btnDebugShare.isEnabled = false
                Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
            }
        }

        btnDebugCalibrate.setOnClickListener {
            MainActivity.navController?.calibrateVehicleFrame()
            Toast.makeText(this, "Vehicle frame calibrated ✓", Toast.LENGTH_SHORT).show()
        }

        btnDebugShare.setOnClickListener { shareLastCsv() }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(uiRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiRunnable)
    }

    private fun updateDebugUi() {
        val nc = MainActivity.navController ?: return
        val snap = nc.sensorEngine.getSnapshot()
        val state = nc.state.value
        val health = state.navigationHealth

        // ── Health ───────────────────────────────────────────────────────────
        tvDbgHealthSummary.text = "GNSS: ${health.gnssHealth} | IMU: ${health.accelHealth} | Route: ${health.routeHealth}"
        tvDbgHealthDetails.text = health.details

        // ── GPS ──────────────────────────────────────────────────────────────
        val hasGps = snap.hasGps && snap.latitude != 0.0
        val (gpsLabel, gpsColor) = when (state.gnssQuality) {
            GnssQuality.GOOD       -> "● GPS EXCELLENT" to 0xFF34D399.toInt()
            GnssQuality.FAIR       -> "● GPS GOOD"      to 0xFF38BDF8.toInt()
            GnssQuality.POOR       -> "● GPS WEAK"      to 0xFFF59E0B.toInt()
            GnssQuality.DENIED     -> "● NO FIX"        to 0xFFF87171.toInt()
            GnssQuality.RECOVERING -> "● RECOVERING"    to 0xFFA78BFA.toInt()
        }
        tvDbgGpsStatus.text = gpsLabel
        tvDbgGpsStatus.setTextColor(gpsColor)

        if (hasGps) {
            tvDbgGpsCoords.text = "Lat: %.5f  Lon: %.5f".format(Locale.US, snap.latitude, snap.longitude)
            tvDbgGpsAccuracy.text = "Accuracy: %.0f m".format(Locale.US, snap.gpsAccuracyM)
            tvDbgGpsSpeed.text   = "Speed: %.1f km/h".format(Locale.US, snap.gpsSpeedMps * 3.6f)
        } else {
            tvDbgGpsCoords.text  = "Waiting for satellite fix..."
            tvDbgGpsAccuracy.text = "Accuracy: --"
            tvDbgGpsSpeed.text   = "Speed: --"
        }

        // ── IMU ───────────────────────────────────────────────────────────────
        tvDbgImuHz.text = "IMU: %.0f Hz".format(Locale.US, snap.imuHz)
        tvDbgSamples.text = "Samples: ${snap.loggedCsvRows}"

        tvDbgAccel.text = "X: %+.3f  Y: %+.3f  Z: %+.3f".format(
            Locale.US, snap.accelX, snap.accelY, snap.accelZ)

        tvDbgGyro.text = "X: %+.3f  Y: %+.3f  Z: %+.3f".format(
            Locale.US, snap.gyroX, snap.gyroY, snap.gyroZ)

        val q0 = snap.quatW.toDouble(); val q1 = snap.quatX.toDouble()
        val q2 = snap.quatY.toDouble(); val q3 = snap.quatZ.toDouble()
        val pitch = Math.toDegrees(Math.asin((2.0 * (q0 * q2 - q3 * q1)).coerceIn(-1.0, 1.0)))
        val roll  = Math.toDegrees(Math.atan2(2.0 * (q0 * q1 + q2 * q3), 1.0 - 2.0 * (q1 * q1 + q2 * q2)))
        val yaw   = Math.toDegrees(Math.atan2(2.0 * (q0 * q3 + q1 * q2), 1.0 - 2.0 * (q2 * q2 + q3 * q3)))
        tvDbgOrient.text = "P: %+.0f°  R: %+.0f°  Y: %+.0f°".format(Locale.US, pitch, roll, yaw)

        // ── Navigation engine ─────────────────────────────────────────────────
        tvDbgNavMode.text = "Mode: ${state.navMode}"
        tvDbgDrProvider.text = "DR Provider: ${state.drProvider}" +
                if (state.drProvider == DrProviderType.SIMPLIFIED_INS)
                    "  ⚠ Placeholder" else ""
        tvDbgGnssQuality.text = "GNSS Quality: ${state.gnssQuality}"
        tvDbgAccuracy.text = if (state.positionAccuracy < Float.MAX_VALUE)
            "Position Accuracy: %.0f m".format(Locale.US, state.positionAccuracy)
        else "Position Accuracy: --"

        // ── Recording ─────────────────────────────────────────────────────────
        val recording = nc.sensorEngine.isRecording
        tvDebugRecIndicator.text = if (recording) "● REC" else "● IDLE"
        tvDebugRecIndicator.setTextColor(if (recording) 0xFFEF4444.toInt() else 0xFF64748B.toInt())
        tvDbgTripStatus.text = if (recording) "● RECORDING" else "● IDLE"
        tvDbgTripStatus.setTextColor(if (recording) 0xFFEF4444.toInt() else 0xFF64748B.toInt())
        tvDbgSampleCount.text = "${snap.loggedCsvRows} logged"
    }

    private fun shareLastCsv() {
        val f = lastRecordedFile ?: return
        if (!f.exists()) return
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share Sensor CSV"))
    }
}
