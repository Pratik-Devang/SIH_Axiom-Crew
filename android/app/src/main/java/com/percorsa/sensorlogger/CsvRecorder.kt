package com.percorsa.sensorlogger

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class CsvNavigationDiagnostics(
    val activeProvider: String? = null,
    val activeLatitude: Double = Double.NaN,
    val activeLongitude: Double = Double.NaN,
    val activeVelocityMps: Float = Float.NaN,
    val activeSpeedMps: Float = Float.NaN,
    val activeHeadingDeg: Float = Float.NaN,
    val tcnCanonicalTimestampNs: Long = 0L,
    val tcnInferenceActive: Boolean? = null,
    val tcnRawSpeedMps: Float = Float.NaN,
    val tcnFilteredSpeedMps: Float = Float.NaN,
    val tcnPredictionRateLimited: Boolean? = null,
    val tcnRejectedPredictionCount: Long? = null,
    val vehicleMotionObserved: Boolean? = null,
    val tcnInjectedIntoIns: Boolean? = null,
    val tcnAcceptedByEskf: Boolean? = null,
    val tcnNis: Double = Double.NaN,
    val eskfInitialized: Boolean? = null,
    val eskfValid: Boolean? = null,
    val eskfTimestampNs: Long = 0L,
    val eskfDtSeconds: Double = Double.NaN,
    val eskfPositionLatitude: Double = Double.NaN,
    val eskfPositionLongitude: Double = Double.NaN,
    val eskfPositionEastM: Double = Double.NaN,
    val eskfPositionNorthM: Double = Double.NaN,
    val eskfPositionUpM: Double = Double.NaN,
    val eskfVelocityEastMps: Double = Double.NaN,
    val eskfVelocityNorthMps: Double = Double.NaN,
    val eskfVelocityUpMps: Double = Double.NaN,
    val eskfSpeedMps: Double = Double.NaN,
    val eskfHeadingDeg: Double = Double.NaN,
    val eskfQuaternionW: Double = Double.NaN,
    val eskfQuaternionX: Double = Double.NaN,
    val eskfQuaternionY: Double = Double.NaN,
    val eskfQuaternionZ: Double = Double.NaN,
    val eskfQuaternionNorm: Double = Double.NaN,
    val eskfCovarianceTrace: Double = Double.NaN,
    val eskfStateFinite: Boolean? = null,
    val eskfCovarianceFinite: Boolean? = null,
    val eskfCovariancePsd: Boolean? = null,
    val eskfGnssAccepted: Boolean? = null,
    val eskfGnssNis: Double = Double.NaN,
    val eskfGnssInnovationM: Double = Double.NaN,
    val eskfNhcAccepted: Boolean? = null,
    val eskfZuptAccepted: Boolean? = null
)

open class CsvRecorder(context: Context? = null, overrideFile: File? = null) {

    val sessionId: String = UUID.randomUUID().toString()
    val file: File
    private var writer: BufferedWriter? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var firstTimestampNs: Long = -1L
    @Volatile private var estimatedSpeedMps: Float = Float.NaN
    @Volatile private var tcnSpeedMps: Float = Float.NaN
    @Volatile private var rawAccelTimestampNs: Long = 0L
    @Volatile private var rawGyroTimestampNs: Long = 0L
    @Volatile private var gnssTimestampMs: Long = 0L
    @Volatile private var gnssElapsedRealtimeNs: Long = 0L
    @Volatile private var gnssAltitudeM: Double = Double.NaN
    @Volatile private var navigationDiagnostics = CsvNavigationDiagnostics()

    init {
        if (overrideFile != null) {
            file = overrideFile
        } else if (context != null) {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            file = File(dir, "sensor_log_${timeStamp}_${sessionId.take(8)}.csv")
        } else {
            file = File.createTempFile("sensor_log_", ".csv")
        }

        try {
            val ht = HandlerThread("CsvWriterThread").apply { start() }
            handlerThread = ht
            handler = Handler(ht.looper)
            handler?.post { initWriter() }
        } catch (e: Throwable) {
            initWriter()
        }
    }

    private fun initWriter() {
        try {
            writer = BufferedWriter(FileWriter(file, true))
            writer?.write(
                "session_id,timestamp_ns,time_since_start_s,accel_timestamp_ns,gyro_timestamp_ns,accel_x,accel_y,accel_z," +
                "linear_accel_x,linear_accel_y,linear_accel_z," +
                "gravity_x,gravity_y,gravity_z," +
                "gyro_x,gyro_y,gyro_z," +
                "quat_w,quat_x,quat_y,quat_z," +
                "gnss_timestamp_ms,gnss_elapsed_realtime_ns,latitude,longitude,altitude_m,gps_accuracy_m,gps_speed_mps,gps_bearing_deg," +
                "estimated_speed_mps,tcn_speed_mps,eskf_speed_mps," +
                "corrected_accel_forward,corrected_accel_left,corrected_accel_up," +
                "corrected_linear_forward,corrected_linear_left,corrected_linear_up,corrected_gyro_forward,corrected_gyro_left,corrected_gyro_up," +
                "active_provider,active_latitude,active_longitude,active_velocity_mps,active_speed_mps,active_heading_deg," +
                "tcn_canonical_timestamp_ns,tcn_inference_active,tcn_raw_speed_mps,tcn_filtered_speed_mps,tcn_rate_limited,tcn_rejected_count,vehicle_motion_observed,tcn_injected_into_ins,tcn_accepted_by_eskf,tcn_nis," +
                "eskf_initialized,eskf_valid,eskf_timestamp_ns,eskf_dt_s,eskf_latitude,eskf_longitude,eskf_position_east_m,eskf_position_north_m,eskf_position_up_m," +
                "eskf_velocity_east_mps,eskf_velocity_north_mps,eskf_velocity_up_mps,eskf_speed_mps,eskf_heading_deg,eskf_quaternion_w,eskf_quaternion_x,eskf_quaternion_y,eskf_quaternion_z,eskf_quaternion_norm," +
                "eskf_covariance_trace,eskf_state_finite,eskf_covariance_finite,eskf_covariance_psd,eskf_gnss_accepted,eskf_gnss_nis,eskf_gnss_innovation_m,eskf_nhc_accepted,eskf_zupt_accepted\n"
            )
            writer?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Diagnostic-only value supplied by the active navigation estimator. */
    fun setEstimatedSpeedMps(speedMps: Float) {
        estimatedSpeedMps = speedMps
    }

    /** Diagnostic-only value supplied by the independent TCN predictor. */
    fun setTcnSpeedMps(speedMps: Float) {
        tcnSpeedMps = speedMps
    }

    fun setRawTimestamps(accelTimestampNs: Long, gyroTimestampNs: Long) {
        rawAccelTimestampNs = accelTimestampNs
        rawGyroTimestampNs = gyroTimestampNs
    }

    fun setGnssMetadata(timestampMs: Long, elapsedRealtimeNs: Long, altitudeM: Double) {
        gnssTimestampMs = timestampMs
        gnssElapsedRealtimeNs = elapsedRealtimeNs
        gnssAltitudeM = altitudeM
    }

    fun setNavigationDiagnostics(diagnostics: CsvNavigationDiagnostics) {
        navigationDiagnostics = diagnostics
    }

    open fun writeRow(
        timestampNs: Long,
        accelX: Float, accelY: Float, accelZ: Float,
        linearX: Float, linearY: Float, linearZ: Float,
        gravX: Float, gravY: Float, gravZ: Float,
        gyroX: Float, gyroY: Float, gyroZ: Float,
        qw: Float, qx: Float, qy: Float, qz: Float,
        corrAccelFwd: Float, corrAccelLeft: Float, corrAccelUp: Float,
        corrLinearFwd: Float, corrLinearLeft: Float, corrLinearUp: Float,
        corrGyroFwd: Float, corrGyroLeft: Float, corrGyroUp: Float
    ) {
        writeRow(
            timestampNs,
            accelX, accelY, accelZ,
            linearX, linearY, linearZ,
            gravX, gravY, gravZ,
            gyroX, gyroY, gyroZ,
            qw, qx, qy, qz,
            corrAccelFwd, corrAccelLeft, corrAccelUp,
            corrLinearFwd, corrLinearLeft, corrLinearUp,
            corrGyroFwd, corrGyroLeft, corrGyroUp,
            Double.NaN, Double.NaN, Float.NaN, Float.NaN, Float.NaN
        )
    }

    open fun writeRow(
        timestampNs: Long,
        accelX: Float, accelY: Float, accelZ: Float,
        linearX: Float, linearY: Float, linearZ: Float,
        gravX: Float, gravY: Float, gravZ: Float,
        gyroX: Float, gyroY: Float, gyroZ: Float,
        qw: Float, qx: Float, qy: Float, qz: Float,
        corrAccelFwd: Float, corrAccelLeft: Float, corrAccelUp: Float,
        corrLinearFwd: Float, corrLinearLeft: Float, corrLinearUp: Float,
        corrGyroFwd: Float, corrGyroLeft: Float, corrGyroUp: Float,
        latitude: Double, longitude: Double,
        gpsAccuracyM: Float, gpsSpeedMps: Float, gpsBearingDeg: Float
    ) {
        val runnable = Runnable {
            try {
                if (firstTimestampNs < 0L) {
                    firstTimestampNs = timestampNs
                }
                val timeSinceStartS = (timestampNs - firstTimestampNs) / 1_000_000_000.0
                val estimatedSpeed = estimatedSpeedMps
                val tcnSpeed = tcnSpeedMps

                writer?.let { w ->
                    val d = navigationDiagnostics
                    val latStr = if (latitude.isNaN()) "" else "%.7f".format(Locale.US, latitude)
                    val lonStr = if (longitude.isNaN()) "" else "%.7f".format(Locale.US, longitude)
                    val altitudeStr = if (gnssAltitudeM.isNaN()) "" else "%.3f".format(Locale.US, gnssAltitudeM)
                    val accStr = if (gpsAccuracyM.isNaN()) "" else "%.2f".format(Locale.US, gpsAccuracyM)
                    val spdStr = if (gpsSpeedMps.isNaN()) "" else "%.2f".format(Locale.US, gpsSpeedMps)
                    val brgStr = if (gpsBearingDeg.isNaN()) "" else "%.1f".format(Locale.US, gpsBearingDeg)

                    val estimatedSpeedStr = if (estimatedSpeed.isNaN()) "" else "%.2f".format(Locale.US, estimatedSpeed)
                    val tcnSpeedStr = if (tcnSpeed.isNaN()) "" else "%.2f".format(Locale.US, tcnSpeed)
                    val value = listOf(
                        sessionId, timestampNs.toString(), "%.4f".format(Locale.US, timeSinceStartS), rawAccelTimestampNs.takeIf { it > 0L }?.toString() ?: "", rawGyroTimestampNs.takeIf { it > 0L }?.toString() ?: "",
                        "%.6f".format(Locale.US, accelX), "%.6f".format(Locale.US, accelY), "%.6f".format(Locale.US, accelZ),
                        "%.6f".format(Locale.US, linearX), "%.6f".format(Locale.US, linearY), "%.6f".format(Locale.US, linearZ),
                        "%.6f".format(Locale.US, gravX), "%.6f".format(Locale.US, gravY), "%.6f".format(Locale.US, gravZ),
                        "%.6f".format(Locale.US, gyroX), "%.6f".format(Locale.US, gyroY), "%.6f".format(Locale.US, gyroZ),
                        "%.6f".format(Locale.US, qw), "%.6f".format(Locale.US, qx), "%.6f".format(Locale.US, qy), "%.6f".format(Locale.US, qz),
                        gnssTimestampMs.takeIf { it > 0L }?.toString() ?: "", gnssElapsedRealtimeNs.takeIf { it > 0L }?.toString() ?: "",
                        latStr, lonStr, altitudeStr, accStr, spdStr, brgStr, estimatedSpeedStr, tcnSpeedStr,
                        csvNumber(d.eskfSpeedMps),
                        "%.6f".format(Locale.US, corrAccelFwd), "%.6f".format(Locale.US, corrAccelLeft), "%.6f".format(Locale.US, corrAccelUp),
                        "%.6f".format(Locale.US, corrLinearFwd), "%.6f".format(Locale.US, corrLinearLeft), "%.6f".format(Locale.US, corrLinearUp),
                        "%.6f".format(Locale.US, corrGyroFwd), "%.6f".format(Locale.US, corrGyroLeft), "%.6f".format(Locale.US, corrGyroUp),
                        d.activeProvider ?: "", csvNumber(d.activeLatitude), csvNumber(d.activeLongitude), csvNumber(d.activeVelocityMps), csvNumber(d.activeSpeedMps), csvNumber(d.activeHeadingDeg),
                        d.tcnCanonicalTimestampNs.takeIf { it > 0L }?.toString() ?: "", csvBoolean(d.tcnInferenceActive), csvNumber(d.tcnRawSpeedMps), csvNumber(d.tcnFilteredSpeedMps), csvBoolean(d.tcnPredictionRateLimited), d.tcnRejectedPredictionCount?.toString() ?: "", csvBoolean(d.vehicleMotionObserved), csvBoolean(d.tcnInjectedIntoIns), csvBoolean(d.tcnAcceptedByEskf), csvNumber(d.tcnNis),
                        csvBoolean(d.eskfInitialized), csvBoolean(d.eskfValid), d.eskfTimestampNs.takeIf { it > 0L }?.toString() ?: "", csvNumber(d.eskfDtSeconds), csvNumber(d.eskfPositionLatitude), csvNumber(d.eskfPositionLongitude), csvNumber(d.eskfPositionEastM), csvNumber(d.eskfPositionNorthM), csvNumber(d.eskfPositionUpM),
                        csvNumber(d.eskfVelocityEastMps), csvNumber(d.eskfVelocityNorthMps), csvNumber(d.eskfVelocityUpMps), csvNumber(d.eskfSpeedMps), csvNumber(d.eskfHeadingDeg), csvNumber(d.eskfQuaternionW), csvNumber(d.eskfQuaternionX), csvNumber(d.eskfQuaternionY), csvNumber(d.eskfQuaternionZ), csvNumber(d.eskfQuaternionNorm), csvNumber(d.eskfCovarianceTrace), csvBoolean(d.eskfStateFinite), csvBoolean(d.eskfCovarianceFinite), csvBoolean(d.eskfCovariancePsd), csvBoolean(d.eskfGnssAccepted), csvNumber(d.eskfGnssNis), csvNumber(d.eskfGnssInnovationM), csvBoolean(d.eskfNhcAccepted), csvBoolean(d.eskfZuptAccepted)
                    ).joinToString(",")
                    w.write("$value\n")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (handler != null) {
            handler?.post(runnable)
        } else {
            runnable.run()
        }
    }

    open fun close() {
        val runnable = Runnable {
            try {
                writer?.flush()
                writer?.close()
                writer = null
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    handlerThread?.quitSafely()
                } catch (e: Throwable) {}
            }
        }

        if (handler != null && android.os.Looper.myLooper() != handler?.looper) {
            handler?.post(runnable)
            handlerThread?.quitSafely()
            handlerThread?.join(5000L)
            handler = null
            handlerThread = null
        } else {
            runnable.run()
        }
    }

    private fun csvNumber(value: Number): String {
        val number = value.toDouble()
        return if (number.isFinite()) "%.9f".format(Locale.US, number) else ""
    }

    private fun csvBoolean(value: Boolean?): String = value?.toString() ?: ""
}
