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
import kotlin.math.sqrt

/**
 * Comprehensive Developer Mode Engineering Telemetry Screen.
 * Exposes internal sensor pipeline, timing, GNSS 1D KF, TCN buffer readiness,
 * and subsystem health status.
 */
class DebugActivity : AppCompatActivity() {

    private var lastRecordedFile: File? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var tvDbgPipelineDiagram: TextView
    private lateinit var tvDbgHealthSummary: TextView
    private lateinit var tvDbgHealthDetails: TextView
    private lateinit var tvDbgImuHz: TextView
    private lateinit var tvDbgTimingStats: TextView
    private lateinit var tvDbgAccel: TextView
    private lateinit var tvDbgGyro: TextView
    private lateinit var tvDbgQuat: TextView
    private lateinit var tvDbgMag: TextView
    private lateinit var tvDbgGravity: TextView
    private lateinit var tvDbgLinearAccel: TextView
    private lateinit var tvDbgVehicleFrameAccel: TextView
    private lateinit var tvDbgFilterStatus: TextView
    private lateinit var tvDbgOrient: TextView
    private lateinit var tvDbgGpsStatus: TextView
    private lateinit var tvDbgGpsQualityReason: TextView
    private lateinit var tvDbgGpsCoords: TextView
    private lateinit var tvDbgGpsAccuracy: TextView
    private lateinit var tvDbgGpsSpeed: TextView
    private lateinit var tvDbgTcnStatus: TextView
    private lateinit var tvDbgTcnModel: TextView
    private lateinit var tvDbgProcessedStream: TextView
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

        tvDbgPipelineDiagram   = findViewById(R.id.tvDbgPipelineDiagram)
        tvDbgHealthSummary     = findViewById(R.id.tvDbgHealthSummary)
        tvDbgHealthDetails     = findViewById(R.id.tvDbgHealthDetails)
        tvDbgImuHz             = findViewById(R.id.tvDbgImuHz)
        tvDbgTimingStats       = findViewById(R.id.tvDbgTimingStats)
        tvDbgAccel             = findViewById(R.id.tvDbgAccel)
        tvDbgGyro              = findViewById(R.id.tvDbgGyro)
        tvDbgQuat              = findViewById(R.id.tvDbgQuat)
        tvDbgMag               = findViewById(R.id.tvDbgMag)
        tvDbgGravity           = findViewById(R.id.tvDbgGravity)
        tvDbgLinearAccel       = findViewById(R.id.tvDbgLinearAccel)
        tvDbgVehicleFrameAccel = findViewById(R.id.tvDbgVehicleFrameAccel)
        tvDbgFilterStatus      = findViewById(R.id.tvDbgFilterStatus)
        tvDbgOrient            = findViewById(R.id.tvDbgOrient)
        tvDbgGpsStatus         = findViewById(R.id.tvDbgGpsStatus)
        tvDbgGpsQualityReason  = findViewById(R.id.tvDbgGpsQualityReason)
        tvDbgGpsCoords         = findViewById(R.id.tvDbgGpsCoords)
        tvDbgGpsAccuracy       = findViewById(R.id.tvDbgGpsAccuracy)
        tvDbgGpsSpeed          = findViewById(R.id.tvDbgGpsSpeed)
        tvDbgTcnStatus         = findViewById(R.id.tvDbgTcnStatus)
        tvDbgTcnModel          = findViewById(R.id.tvDbgTcnModel)
        tvDbgProcessedStream   = findViewById(R.id.tvDbgProcessedStream)
        tvDbgNavMode           = findViewById(R.id.tvDbgNavMode)
        tvDbgDrProvider        = findViewById(R.id.tvDbgDrProvider)
        tvDbgGnssQuality       = findViewById(R.id.tvDbgGnssQuality)
        tvDbgAccuracy          = findViewById(R.id.tvDbgAccuracy)
        tvDbgTripStatus        = findViewById(R.id.tvDbgTripStatus)
        tvDbgSampleCount       = findViewById(R.id.tvDbgSampleCount)
        tvDebugRecIndicator    = findViewById(R.id.tvDebugRecIndicator)
        btnDebugRecord         = findViewById(R.id.btnDebugRecord)
        btnDebugCalibrate      = findViewById(R.id.btnDebugCalibrate)
        btnDebugShare          = findViewById(R.id.btnDebugShare)
        btnDebugBack           = findViewById(R.id.btnDebugBack)

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

        // ── 1. Pipeline Flow Visualization ──────────────────────────────────
        val tcnReadyBadge = if (snap.tcnBufferReady) "[20/20 READY]" else "[${snap.tcnBufferCount}/20 WAITING]"
        tvDbgPipelineDiagram.text = "RAW SENSORS (200Hz) → GRAVITY/LINEAR → FILTERING → VEHICLE FRAME → 10Hz CANONICAL → TCN BUFFER $tcnReadyBadge\nRAW GNSS → 1D ADAPTIVE KF → FILTERED GNSS → NAV STATE"

        // ── 2. Live Subsystem Health ─────────────────────────────────────────
        val imuStatusStr = if (snap.imuHz > 10) "ACTIVE (%.0f Hz)".format(Locale.US, snap.imuHz) else "STALE"
        val gpsAgeStr = if (snap.gpsFixAgeMs >= 0) "${snap.gpsFixAgeMs} ms ago" else "No fix yet"
        tvDbgHealthSummary.text = "IMU Stream: $imuStatusStr | GNSS Fix: $gpsAgeStr"
        tvDbgHealthDetails.text = "Accel: ${if (snap.hasAccel) "ACTIVE" else "STALE"} | Gyro: ${if (snap.hasGyro) "ACTIVE" else "STALE"} | RotVec: ${if (snap.hasRotVector) "ACTIVE" else "STALE"} | Grav: ${if (snap.hasGravity) "ACTIVE" else "STALE"} | Mag: ${if (snap.hasMag) "ACTIVE" else "STALE"} | GNSS: ${if (snap.hasGps) "ACTIVE" else "STALE"}"

        // ── 3. Sensor Timing & Delivery Rates ───────────────────────────────
        tvDbgImuHz.text = "Requested: 200 Hz | Actual IMU Rate: %.1f Hz (Raw Callbacks: %.1f Hz)".format(Locale.US, snap.imuHz, snap.rawCallbackHz)
        tvDbgTimingStats.text = "Req dt: 5.00 ms | Avg dt: %.2f ms | Min dt: %.2f ms | Max dt: %.2f ms | Jitter: %.2f ms".format(
            Locale.US, snap.avgDtMs, snap.minDtMs, snap.maxDtMs, snap.dtJitterMs)

        // ── 4. Raw Sensor Readings ──────────────────────────────────────────
        tvDbgAccel.text = "X: %+.3f  Y: %+.3f  Z: %+.3f  (Mag: %.2f m/s²)".format(
            Locale.US, snap.accelX, snap.accelY, snap.accelZ, snap.accelMag)

        tvDbgGyro.text = "X: %+.3f  Y: %+.3f  Z: %+.3f  (Mag: %.2f rad/s)".format(
            Locale.US, snap.gyroX, snap.gyroY, snap.gyroZ, snap.gyroMag)

        val q0 = snap.quatW.toDouble(); val q1 = snap.quatX.toDouble()
        val q2 = snap.quatY.toDouble(); val q3 = snap.quatZ.toDouble()
        val quatNorm = snap.quatNorm
        val quatValidStr = if (quatNorm in 0.95f..1.05f) "VALID" else "WARNING"
        tvDbgQuat.text = "W: %+.3f  X: %+.3f  Y: %+.3f  Z: %+.3f (Norm: %.3f • %s)".format(
            Locale.US, snap.quatW, snap.quatX, snap.quatY, snap.quatZ, quatNorm, quatValidStr)

        val magMag = sqrt(snap.magX * snap.magX + snap.magY * snap.magY + snap.magZ * snap.magZ)
        tvDbgMag.text = "X: %+.1f  Y: %+.1f  Z: %+.1f µT (Mag: %.1f µT)".format(
            Locale.US, snap.magX, snap.magY, snap.magZ, magMag)

        // ── 5. Android-Derived Data ─────────────────────────────────────────
        val gravMag = snap.gravityMag
        val gravValidStr = if (gravMag in 9.3f..10.3f) "NORMAL" else "WARNING"
        tvDbgGravity.text = "X: %+.2f  Y: %+.2f  Z: %+.2f (Mag: %.2f m/s² • %s)".format(
            Locale.US, snap.gravityX, snap.gravityY, snap.gravityZ, gravMag, gravValidStr)

        tvDbgLinearAccel.text = "X: %+.2f  Y: %+.2f  Z: %+.2f m/s² (Mag: %.2f m/s²)".format(
            Locale.US, snap.linearAccelX, snap.linearAccelY, snap.linearAccelZ, snap.linearAccelMag)

        // ── 6. Vehicle-Frame & Filtered Data ────────────────────────────────
        tvDbgVehicleFrameAccel.text = "Fwd: %+.3f  Left: %+.3f  Up: %+.3f m/s² (Mag: %.2f)".format(
            Locale.US, snap.correctedLinearForward, snap.correctedLinearLeft, snap.correctedLinearUp, snap.correctedLinearMag)

        tvDbgFilterStatus.text = "Filter: Accel Deadband (<0.6m/s²) + Gyro Tremor Filter | Status: ACTIVE (Calibrated: %s)".format(
            if (snap.isCalibrated) "YES" else "NO")

        val pitch = Math.toDegrees(Math.asin((2.0 * (q0 * q2 - q3 * q1)).coerceIn(-1.0, 1.0)))
        val roll  = Math.toDegrees(Math.atan2(2.0 * (q0 * q1 + q2 * q3), 1.0 - 2.0 * (q1 * q1 + q2 * q2)))
        val yaw   = Math.toDegrees(Math.atan2(2.0 * (q0 * q3 + q1 * q2), 1.0 - 2.0 * (q2 * q2 + q3 * q3)))
        tvDbgOrient.text = "P: %+.0f°  R: %+.0f°  Y: %+.0f°  (Source: ROTATION VECTOR)".format(Locale.US, pitch, roll, yaw)

        // ── 7. GNSS Telemetry & 1D Kalman Filter ────────────────────────────
        val hasGps = snap.hasGps && snap.latitude != 0.0
        val (gpsLabel, gpsColor) = when (state.gnssQuality) {
            GnssQuality.GOOD       -> "● GPS EXCELLENT" to 0xFF34D399.toInt()
            GnssQuality.FAIR       -> "● GPS GOOD"      to 0xFF38BDF8.toInt()
            GnssQuality.POOR       -> "● GPS WEAK"      to 0xFFF59E0B.toInt()
            GnssQuality.DENIED     -> "● NO FIX"        to 0xFFF87171.toInt()
            GnssQuality.RECOVERING -> "● RECOVERING"    to 0xFFA78BFA.toInt()
        }
        tvDbgGpsStatus.text = "$gpsLabel (Fix age: $gpsAgeStr)"
        tvDbgGpsStatus.setTextColor(gpsColor)

        tvDbgGpsQualityReason.text = "Quality: %s (Reason: Accuracy %.1fm, Fix age %d ms, Provider: %s)".format(
            state.gnssQuality.name, snap.gpsAccuracyM, snap.gpsFixAgeMs, if (hasGps) "GPS" else "NONE")

        if (hasGps) {
            tvDbgGpsCoords.text = "Raw Lat: %.5f  Lon: %.5f\nKF  Lat: %.5f  Lon: %.5f".format(
                Locale.US, snap.latitude, snap.longitude, state.latitude, state.longitude)
            tvDbgGpsAccuracy.text = "Accuracy: %.0f m".format(Locale.US, snap.gpsAccuracyM)
            tvDbgGpsSpeed.text   = "Speed: %.1f km/h (%.1f m/s)".format(Locale.US, snap.gpsSpeedMps * 3.6f, snap.gpsSpeedMps)
        } else {
            tvDbgGpsCoords.text  = "Raw Lat: --  Lon: --\nKF  Lat: --  Lon: --"
            tvDbgGpsAccuracy.text = "Accuracy: --"
            tvDbgGpsSpeed.text   = "Speed: --"
        }

        // ── 8. Canonical 10 Hz Stream & TCN Buffer ───────────────────────────
        val bufferReadyStr = if (snap.tcnBufferReady) "20/20 READY" else "${snap.tcnBufferCount}/20 WAITING"
        tvDbgTcnStatus.text = "TCN Input Buffer: $bufferReadyStr (2.0s @ 10 Hz canonical stream)"
        tvDbgTcnStatus.setTextColor(if (snap.tcnBufferReady) 0xFF34D399.toInt() else 0xFFF59E0B.toInt())
        tvDbgTcnModel.text = "Model Status: NOT ACTIVE (Input buffer ready for downstream feature/ml-speed-tcn)"
        val lastCan = snap.lastCanonicalSample
        if (lastCan != null) {
            tvDbgProcessedStream.text = "10Hz Canonical: ax=%+.2f ay=%+.2f az=%+.2f gx=%+.2f gy=%+.2f gz=%+.2f\nFeature order: 1.accel_x 2.accel_y 3.accel_z 4.gyro_x 5.gyro_y 6.gyro_z".format(
                Locale.US, lastCan.accelX, lastCan.accelY, lastCan.accelZ, lastCan.gyroX, lastCan.gyroY, lastCan.gyroZ
            )
        } else {
            tvDbgProcessedStream.text = "Pipeline: RAW → GRAVITY → LINEAR → VEHICLE FRAME → 10 Hz CANONICAL"
        }

        // ── 9. Navigation Engine & Future Fusion Status ──────────────────────
        tvDbgNavMode.text = "Nav Mode: ${state.navMode}"
        tvDbgDrProvider.text = "DR Engine: ${state.drProvider}" +
                if (state.drProvider == DrProviderType.SIMPLIFIED_INS)
                    " (Fallback INS — Percorsa ESKF stub inactive)" else ""
        tvDbgGnssQuality.text = "GNSS Filter: 1D Adaptive Lat/Lon KF | ESKF: NOT ACTIVE | TCN: NOT ACTIVE"
        tvDbgAccuracy.text = if (state.positionAccuracy < Float.MAX_VALUE)
            "Position Accuracy: %.0f m | Position Source: RAW GNSS / KF GNSS".format(Locale.US, state.positionAccuracy)
        else "Position Accuracy: -- | Position Source: RAW GNSS / KF GNSS"

        // ── 10. Recording ───────────────────────────────────────────────────
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
