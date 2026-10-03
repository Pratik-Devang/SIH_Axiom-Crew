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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Comprehensive Developer Mode Engineering Telemetry Screen.
 *
 * Exposes 10 structured telemetry cards aligning with SIH specifications:
 * 1. Pipeline Architecture & Subsystem Health
 * 2. Seamless GNSS Deficit & Navigation Mode
 * 3. AI Speed Estimate & Fusion Visibility (TCN → ESKF)
 * 4. 15-State ESKF Estimator & Measurement Updates
 * 5. In-Vehicle Alignment & Arbitrary Mounting
 * 6. Map Matching & Vehicle Constraints
 * 7. GNSS Telemetry & 1D Adaptive KF
 * 8. Real-Time Pipeline Timing & Rates
 * 9. Raw Sensor Readings (Phone Frame)
 * 10. Data Logging & Calibration
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
    private lateinit var tvDbgVehicleFrameGyro: TextView
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
    private lateinit var tvDbgRouteTracking: TextView
    private lateinit var tvDbgEskfState: TextView
    private lateinit var tvDbgEskfVectors: TextView
    private lateinit var tvDbgEskfCov: TextView
    private lateinit var tvDbgEskfUpdates: TextView
    private lateinit var tvDbgTcnMetrics: TextView
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
        tvDbgVehicleFrameGyro  = findViewById(R.id.tvDbgVehicleFrameGyro)
        tvDbgFilterStatus      = findViewById(R.id.tvDbgFilterStatus)
        tvDbgOrient            = findViewById(R.id.tvDbgOrient)
        tvDbgGpsStatus         = findViewById(R.id.tvDbgGpsStatus)
        tvDbgGpsQualityReason  = findViewById(R.id.tvDbgGpsQualityReason)
        tvDbgGpsCoords         = findViewById(R.id.tvDbgGpsCoords)
        tvDbgGpsAccuracy       = findViewById(R.id.tvDbgGpsAccuracy)
        tvDbgGpsSpeed          = findViewById(R.id.tvDbgGpsSpeed)
        tvDbgTcnStatus         = findViewById(R.id.tvDbgTcnStatus)
        tvDbgTcnModel          = findViewById(R.id.tvDbgTcnModel)
        tvDbgTcnMetrics        = findViewById(R.id.tvDbgTcnMetrics)
        tvDbgProcessedStream   = findViewById(R.id.tvDbgProcessedStream)
        tvDbgNavMode           = findViewById(R.id.tvDbgNavMode)
        tvDbgDrProvider        = findViewById(R.id.tvDbgDrProvider)
        tvDbgGnssQuality       = findViewById(R.id.tvDbgGnssQuality)
        tvDbgAccuracy          = findViewById(R.id.tvDbgAccuracy)
        tvDbgRouteTracking     = findViewById(R.id.tvDbgRouteTracking)
        tvDbgEskfState         = findViewById(R.id.tvDbgEskfState)
        tvDbgEskfVectors       = findViewById(R.id.tvDbgEskfVectors)
        tvDbgEskfCov           = findViewById(R.id.tvDbgEskfCov)
        tvDbgEskfUpdates       = findViewById(R.id.tvDbgEskfUpdates)
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
                btnDebugRecord.text = "● Start Sensor Logging (CSV)"
                btnDebugRecord.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFFDC2626.toInt())
                btnDebugShare.isEnabled = lastRecordedFile?.exists() == true
                Toast.makeText(this, "Recording saved", Toast.LENGTH_SHORT).show()
            } else {
                val recorder = CsvRecorder(this)
                lastRecordedFile = recorder.file
                nc.startRecording(recorder)
                btnDebugRecord.text = "■ Stop Sensor Logging"
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
        val eskf = nc.eskfDiagnostics

        // ── 1. Pipeline Architecture & Subsystem Health ─────────────────────
        val tcnReadyBadge = if (snap.tcnBufferReady) {
            "[${snap.tcnBufferCapacity}/${snap.tcnBufferCapacity} READY]"
        } else {
            "[${snap.tcnBufferCount}/${snap.tcnBufferCapacity} WAITING]"
        }
        tvDbgPipelineDiagram.text = "IMU (200Hz) → VEHICLE FRAME → 10Hz CANONICAL → TCN BUFFER $tcnReadyBadge → 15-STATE ESKF\nGNSS FIX → 1D ADAPTIVE KF → NIS GATING → ESKF HYBRID FUSION"

        val imuStatusStr = if (snap.imuHz > 10) "ACTIVE (%.0f Hz)".format(Locale.US, snap.imuHz) else "STALE"
        val gpsAgeStr = if (snap.gpsFixAgeMs >= 0) "${snap.gpsFixAgeMs} ms ago" else "No fix yet"
        tvDbgHealthSummary.text = "IMU Stream: $imuStatusStr · GNSS Fix: $gpsAgeStr"
        tvDbgHealthSummary.setTextColor(if (snap.imuHz > 10 && snap.hasGps) 0xFF34D399.toInt() else 0xFFF59E0B.toInt())
        tvDbgHealthDetails.text = "Accel: %s  |  Gyro: %s  |  RotVec: %s\nGravity: %s  |  Mag: %s  |  GNSS: %s".format(
            if (snap.hasAccel) "ACTIVE" else "STALE",
            if (snap.hasGyro) "ACTIVE" else "STALE",
            if (snap.hasRotVector) "ACTIVE" else "STALE",
            if (snap.hasGravity) "ACTIVE" else "STALE",
            if (snap.hasMag) "ACTIVE" else "STALE",
            if (snap.hasGps) "ACTIVE" else "STALE"
        )

        // ── 2. Seamless GNSS Deficit & Navigation Mode ───────────────────────
        val modeColor = when (state.navMode) {
            NavMode.NAVIGATING -> 0xFF3DD6F5.toInt()
            NavMode.GNSS_DEGRADED, NavMode.GNSS_DENIED -> 0xFFF59E0B.toInt()
            NavMode.ERROR -> 0xFFEF4444.toInt()
            else -> 0xFFE2E8F0.toInt()
        }
        val modeLabel = when {
            state.drActive -> "DEAD RECKONING (Active DR)"
            state.gnssQuality == GnssQuality.RECOVERING -> "GNSS RECOVERING (Blending)"
            state.navMode == NavMode.GNSS_DEGRADED -> "DEGRADED (Weak GNSS)"
            state.navMode == NavMode.NAVIGATING -> "GNSS (Doppler Fix)"
            else -> state.navMode.name
        }
        tvDbgNavMode.text = modeLabel
        tvDbgNavMode.setTextColor(modeColor)

        tvDbgDrProvider.text = "PERCORSA_ESKF (15-State Error-State KF)"
        
        val deficitDesc = when {
            state.drActive -> "DEFICIT: DR ACTIVE (ESKF + TCN Speed)"
            state.gnssQuality == GnssQuality.RECOVERING -> "RECOVERY: Smoothing GNSS Re-entry"
            state.gnssQuality == GnssQuality.POOR -> "DEGRADED: Low Sat / High DOP"
            snap.hasGps -> "GNSS AVAILABLE · Doppler Speed Trusted"
            else -> "STANDBY · Awaiting Fix"
        }
        tvDbgGnssQuality.text = deficitDesc

        val accStr = if (state.positionAccuracy < Float.MAX_VALUE && state.positionAccuracy.isFinite()) {
            "± %.1f m (%s)".format(Locale.US, state.positionAccuracy, state.speedSource.name)
        } else {
            "-- m (Unavailable)"
        }
        tvDbgAccuracy.text = accStr

        // ── 3. AI Speed Estimate & Fusion Visibility ─────────────────────────
        val bufferReadyStr = if (snap.tcnBufferReady) {
            "${snap.tcnBufferCapacity}/${snap.tcnBufferCapacity} READY"
        } else {
            "${snap.tcnBufferCount}/${snap.tcnBufferCapacity} WAITING"
        }
        tvDbgTcnStatus.text = "TCN Input Buffer: $bufferReadyStr (%.1fs @ %d Hz canonical stream)".format(
            Locale.US, snap.tcnWindowSeconds, TcnInputBuffer.SAMPLE_RATE_HZ
        )
        tvDbgTcnStatus.setTextColor(if (snap.tcnInferenceActive) 0xFF34D399.toInt() else 0xFFF59E0B.toInt())
        tvDbgTcnModel.text = when {
            snap.tcnInferenceActive -> "Model: ACTIVE · Raw: %.2f m/s · Filtered: %.2f m/s (%.1f km/h)".format(
                Locale.US,
                snap.tcnRawSpeedMps,
                snap.tcnPredictedSpeedMps,
                snap.tcnPredictedSpeedMps * 3.6f
            )
            snap.tcnInferenceError != null -> "Model: ERROR · ${snap.tcnInferenceError}"
            snap.tcnInferenceInFlight -> "Model: INFERENCE IN FLIGHT"
            snap.tcnModelLoaded && snap.tcnBufferReady -> "Model: LOADED · Awaiting first inference"
            !snap.tcnModelLoaded -> "Model: LOADING ONNX MODEL"
            else -> "Model: WARMING UP · Collecting 5-second buffer"
        }
        tvDbgTcnMetrics.text = "Inference: %.2f ms · Fix Age: %d ms · Rate Limited: %s · Rejected: %d".format(
            Locale.US,
            snap.tcnInferenceLatencyMs,
            snap.tcnInferenceAgeMs,
            if (snap.tcnPredictionRateLimited) "YES" else "NO",
            snap.tcnRejectedPredictionCount
        )

        val lastCan = snap.lastCanonicalSample
        if (lastCan != null) {
            tvDbgProcessedStream.text = "10Hz Canonical: ax=%+.2f ay=%+.2f az=%+.2f gx=%+.2f gy=%+.2f gz=%+.2f\nFeature order: 1.accel_x 2.accel_y 3.accel_z 4.gyro_x 5.gyro_y 6.gyro_z".format(
                Locale.US, lastCan.accelX, lastCan.accelY, lastCan.accelZ, lastCan.gyroX, lastCan.gyroY, lastCan.gyroZ
            )
        } else {
            tvDbgProcessedStream.text = "Pipeline: RAW → GRAVITY → LINEAR → VEHICLE FRAME → 10 Hz CANONICAL"
        }

        // ── 4. 15-State ESKF Estimator Diagnostics & Fusion Updates ─────────
        val posStr = if (eskf.positionLatitude.isFinite() && eskf.positionLongitude.isFinite()) {
            "%.5f, %.5f".format(Locale.US, eskf.positionLatitude, eskf.positionLongitude)
        } else {
            "--, --"
        }
        tvDbgEskfState.text = "State: %s · Calibrated: %s · Pos: %s".format(
            eskf.runtimeState, if (eskf.calibrationActive) "YES" else "NO", posStr
        )
        tvDbgEskfVectors.text = "Vel ENU: (%+.2f, %+.2f, %+.2f) · Speed: %.2f m/s · Hdg: %.1f° · dt: %.3fs".format(
            Locale.US,
            eskf.velocityWorldEnu.getOrElse(0) { 0.0 },
            eskf.velocityWorldEnu.getOrElse(1) { 0.0 },
            eskf.velocityWorldEnu.getOrElse(2) { 0.0 },
            if (eskf.speedMps.isFinite()) eskf.speedMps else 0.0,
            if (eskf.headingDeg.isFinite()) eskf.headingDeg else 0.0,
            eskf.lastDtSeconds
        )
        tvDbgEskfCov.text = "Cov Trace: %.3g · Quat Norm: %.6f · Status: %s".format(
            Locale.US,
            if (eskf.covarianceTrace.isFinite()) eskf.covarianceTrace else 0.0,
            if (eskf.quaternionNorm.isFinite()) eskf.quaternionNorm else 1.0,
            if (eskf.isHealthy) "HEALTHY" else (eskf.degradationReason ?: "DEGRADED")
        )

        val gnssUpdateStr = when (eskf.lastGnssAccepted) {
            true -> "ACC (NIS %.2f, Innov %.1fm)".format(Locale.US, eskf.lastGnssNis, eskf.lastGnssInnovationMagnitudeM)
            false -> "REJ (%s)".format(eskf.lastGnssRejectionReason ?: "NIS")
            null -> "STANDBY"
        }
        val tcnUpdateStr = when (eskf.lastTcnAccepted) {
            true -> "ACC (NIS %.2f)".format(Locale.US, eskf.lastTcnNis)
            false -> "REJ (%s)".format(eskf.lastTcnRejectionReason ?: "NIS")
            null -> "STANDBY"
        }
        val nhcStr = "acc=%s (NIS %.2f)".format(eskf.lastNhcAccepted ?: false, if (eskf.lastNhcNis.isFinite()) eskf.lastNhcNis else 0.0)
        val zuptStr = "acc=%s (NIS %.2f)".format(eskf.lastZuptAccepted ?: false, if (eskf.lastZuptNis.isFinite()) eskf.lastZuptNis else 0.0)
        tvDbgEskfUpdates.text = "GNSS: %s · TCN: %s\nNHC: %s · ZUPT: %s".format(gnssUpdateStr, tcnUpdateStr, nhcStr, zuptStr)

        // ── 5. In-Vehicle Alignment & Arbitrary Mounting ─────────────────────
        val q0 = snap.quatW.toDouble(); val q1 = snap.quatX.toDouble()
        val q2 = snap.quatY.toDouble(); val q3 = snap.quatZ.toDouble()
        val pitch = Math.toDegrees(Math.asin((2.0 * (q0 * q2 - q3 * q1)).coerceIn(-1.0, 1.0)))
        val roll  = Math.toDegrees(Math.atan2(2.0 * (q0 * q1 + q2 * q3), 1.0 - 2.0 * (q1 * q1 + q2 * q2)))
        val yaw   = Math.toDegrees(Math.atan2(2.0 * (q0 * q3 + q1 * q2), 1.0 - 2.0 * (q2 * q2 + q3 * q3)))
        tvDbgOrient.text = "P %+.0f° R %+.0f° Y %+.0f° · %s (Arbitrary Mount)".format(
            Locale.US, pitch, roll, yaw, snap.rotationSource.name
        )

        tvDbgVehicleFrameAccel.text = "Fwd: %+.3f  Left: %+.3f  Up: %+.3f m/s² (Mag: %.2f)".format(
            Locale.US, snap.correctedLinearForward, snap.correctedLinearLeft, snap.correctedLinearUp, snap.correctedLinearMag)
        tvDbgVehicleFrameGyro.text = "Fwd: %+.3f  Left: %+.3f  Up: %+.3f rad/s (Mag: %.2f)".format(
            Locale.US, snap.correctedGyroForward, snap.correctedGyroLeft, snap.correctedGyroUp, snap.correctedGyroMag)

        tvDbgFilterStatus.text = if (snap.isCalibrated) {
            "CALIBRATED · Gravity + Forward Acceleration Aligned"
        } else {
            "UNCALIBRATED · Auto-Estimating from Gravity Vector"
        }

        // ── 6. Map Matching & Vehicle Constraints ───────────────────────────
        if (state.route != null) {
            tvDbgRouteTracking.text = "Map Match: ACTIVE (Seg #%d, Progress: %.1fm)\nCross-Track: %.1fm · Hdg Error: %.1f° · Turn: %s (%.1f°/s)".format(
                Locale.US,
                state.routeSegmentIndex,
                state.routeProgressM,
                if (state.routeLateralErrorM.isFinite()) state.routeLateralErrorM else 0.0,
                if (state.routeHeadingErrorDeg.isFinite()) state.routeHeadingErrorDeg else 0.0,
                state.turnState.name,
                if (state.turnYawRateDegS.isFinite()) state.turnYawRateDegS else 0f
            )
        } else {
            tvDbgRouteTracking.text = "Map Match: IDLE (No active route)\nConstraints: NHC=%s · ZUPT=%s · MotionObserved=%s".format(
                eskf.nhcEnabled, eskf.zuptEnabled, eskf.vehicleMotionObserved
            )
        }

        // ── 7. GNSS Telemetry & 1D Adaptive Kalman Filter ───────────────────
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
            tvDbgGpsCoords.text = "Raw Lat: %.5f  Lon: %.5f\nESKF Lat: %.5f  Lon: %.5f".format(
                Locale.US, snap.latitude, snap.longitude, state.latitude, state.longitude)
            tvDbgGpsAccuracy.text = "Accuracy: %.0f m".format(Locale.US, snap.gpsAccuracyM)
            tvDbgGpsSpeed.text   = "Speed: %.1f km/h (%.1f m/s)".format(Locale.US, snap.gpsSpeedMps * 3.6f, snap.gpsSpeedMps)
        } else {
            tvDbgGpsCoords.text  = "Raw Lat: --  Lon: --\nESKF Lat: --  Lon: --"
            tvDbgGpsAccuracy.text = "Accuracy: --"
            tvDbgGpsSpeed.text   = "Speed: --"
        }

        // ── 8. Real-Time Pipeline Timing & Rates ────────────────────────────
        tvDbgImuHz.text = "IMU Rate: %.1f Hz (Callbacks: %.1f Hz) · 10 Hz Canonical Resampled".format(
            Locale.US, snap.imuHz, snap.rawCallbackHz
        )
        tvDbgTimingStats.text = "Req dt: 5.00 ms | Avg dt: %.2f ms | Min: %.2f ms | Max: %.2f ms | Jitter: %.2f ms".format(
            Locale.US, snap.avgDtMs, snap.minDtMs, snap.maxDtMs, snap.dtJitterMs
        )

        // ── 9. Raw Sensor Readings (Phone Frame) ────────────────────────────
        tvDbgAccel.text = "Accel: X %+.3f  Y %+.3f  Z %+.3f (Mag: %.2f m/s²)".format(
            Locale.US, snap.accelX, snap.accelY, snap.accelZ, snap.accelMag)

        tvDbgGyro.text = "Gyro:  X %+.3f  Y %+.3f  Z %+.3f (Mag: %.2f rad/s)".format(
            Locale.US, snap.gyroX, snap.gyroY, snap.gyroZ, snap.gyroMag)

        val quatNorm = snap.quatNorm
        val quatValidStr = if (quatNorm in 0.95f..1.05f) "VALID" else "WARNING"
        tvDbgQuat.text = "Quat:  W %+.3f  X %+.3f  Y %+.3f  Z %+.3f (Norm: %.3f • %s)".format(
            Locale.US, snap.quatW, snap.quatX, snap.quatY, snap.quatZ, quatNorm, quatValidStr)

        val mx = snap.magX.toDouble()
        val my = snap.magY.toDouble()
        val mz = snap.magZ.toDouble()
        val magMag = sqrt(mx * mx + my * my + mz * mz).toFloat()
        tvDbgMag.text = "Mag:   X %+.1f  Y %+.1f  Z %+.1f µT (Mag: %.1f µT)".format(
            Locale.US, snap.magX, snap.magY, snap.magZ, magMag)

        val gravMag = snap.gravityMag
        val gravValidStr = if (gravMag in 9.3f..10.3f) "NORMAL" else "WARNING"
        tvDbgGravity.text = "Grav:  X %+.2f  Y %+.2f  Z %+.2f (Mag: %.2f m/s² • %s)".format(
            Locale.US, snap.gravityX, snap.gravityY, snap.gravityZ, gravMag, gravValidStr)

        tvDbgLinearAccel.text = "Linear: X %+.2f  Y %+.2f  Z %+.2f m/s² (Mag: %.2f m/s²)".format(
            Locale.US, snap.linearAccelX, snap.linearAccelY, snap.linearAccelZ, snap.linearAccelMag)

        // ── 10. Data Logging & Calibration ──────────────────────────────────
        val recording = nc.sensorEngine.isRecording
        tvDebugRecIndicator.text = if (recording) "● REC" else "● IDLE"
        tvDebugRecIndicator.setTextColor(if (recording) 0xFFEF4444.toInt() else 0xFF64748B.toInt())
        tvDbgTripStatus.text = if (recording) "● RECORDING" else "● IDLE"
        tvDbgTripStatus.setTextColor(if (recording) 0xFFEF4444.toInt() else 0xFF64748B.toInt())
        tvDbgSampleCount.text = "${snap.loggedCsvRows} samples logged"
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
