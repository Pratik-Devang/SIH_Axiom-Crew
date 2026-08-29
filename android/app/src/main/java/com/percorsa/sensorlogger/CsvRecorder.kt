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

open class CsvRecorder(context: Context? = null, overrideFile: File? = null) {

    val file: File
    private var writer: BufferedWriter? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var firstTimestampNs: Long = -1L

    init {
        if (overrideFile != null) {
            file = overrideFile
        } else if (context != null) {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            file = File(dir, "sensor_log_$timeStamp.csv")
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
                "timestamp_ns,time_since_start_s,accel_x,accel_y,accel_z," +
                "linear_accel_x,linear_accel_y,linear_accel_z," +
                "gravity_x,gravity_y,gravity_z," +
                "gyro_x,gyro_y,gyro_z," +
                "quat_w,quat_x,quat_y,quat_z," +
                "latitude,longitude,gps_accuracy_m,gps_speed_mps,gps_bearing_deg,vehicle_speed," +
                "corrected_accel_forward,corrected_accel_left,corrected_accel_up," +
                "corrected_linear_forward,corrected_linear_left,corrected_linear_up," +
                "corrected_gyro_forward,corrected_gyro_left,corrected_gyro_up\n"
            )
            writer?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
                val vehicleSpeedKmh = if (!gpsSpeedMps.isNaN()) gpsSpeedMps * 3.6f else 0f

                writer?.let { w ->
                    val latStr = if (latitude.isNaN()) "" else "%.7f".format(Locale.US, latitude)
                    val lonStr = if (longitude.isNaN()) "" else "%.7f".format(Locale.US, longitude)
                    val accStr = if (gpsAccuracyM.isNaN()) "" else "%.2f".format(Locale.US, gpsAccuracyM)
                    val spdStr = if (gpsSpeedMps.isNaN()) "" else "%.2f".format(Locale.US, gpsSpeedMps)
                    val brgStr = if (gpsBearingDeg.isNaN()) "" else "%.1f".format(Locale.US, gpsBearingDeg)

                    w.write("$timestampNs,%.4f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,$latStr,$lonStr,$accStr,$spdStr,$brgStr,%.2f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f\n".format(
                        Locale.US,
                        timeSinceStartS,
                        accelX, accelY, accelZ,
                        linearX, linearY, linearZ,
                        gravX, gravY, gravZ,
                        gyroX, gyroY, gyroZ,
                        qw, qx, qy, qz,
                        vehicleSpeedKmh,
                        corrAccelFwd, corrAccelLeft, corrAccelUp,
                        corrLinearFwd, corrLinearLeft, corrLinearUp,
                        corrGyroFwd, corrGyroLeft, corrGyroUp
                    ))
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

        if (handler != null) {
            handler?.post(runnable)
        } else {
            runnable.run()
        }
    }
}
