package com.percorsa.sensorlogger

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class CsvRecorderTest {

    @Test
    fun sensorEngineReadsDiagnosticSpeedProviderAtRowTime() {
        val file = File.createTempFile("sensor_engine_speed_test", ".csv")
        file.deleteOnExit()
        val recorder = CsvRecorder(context = null, overrideFile = file)
        val sensorEngine = SensorEngine(context = null)
        sensorEngine.setEstimatedSpeedProviderForDiagnostics { 0f }
        sensorEngine.startRecording(recorder)

        sensorEngine.handleSensorData(
            android.hardware.Sensor.TYPE_ACCELEROMETER,
            1_000_000_000L,
            floatArrayOf(0f, 0f, 9.81f)
        )
        sensorEngine.stopRecording()

        var csv = ""
        repeat(40) {
            csv = file.readText()
            if (csv.contains(",0.00,,,")) return@repeat
            Thread.sleep(25)
        }

        val lines = csv.trim().lines()
        val columns = lines.first().split(',')
        val values = lines.last().split(',')
        assertEquals("0.00", values[columns.indexOf("estimated_speed_mps")])
    }

    @Test
    fun estimatedSpeedIsSerializedSeparatelyFromGpsSpeed() {
        val file = File.createTempFile("csv_recorder_test", ".csv")
        file.deleteOnExit()
        val recorder = CsvRecorder(context = null, overrideFile = file)

        recorder.setEstimatedSpeedMps(2.5f)
        recorder.setTcnSpeedMps(3.5f)
        recorder.writeRow(
            timestampNs = 1_000_000_000L,
            accelX = 0f, accelY = 0f, accelZ = 9.81f,
            linearX = 0f, linearY = 0f, linearZ = 0f,
            gravX = 0f, gravY = 0f, gravZ = 9.81f,
            gyroX = 0f, gyroY = 0f, gyroZ = 0f,
            qw = 1f, qx = 0f, qy = 0f, qz = 0f,
            corrAccelFwd = 0f, corrAccelLeft = 0f, corrAccelUp = 9.81f,
            corrLinearFwd = 0f, corrLinearLeft = 0f, corrLinearUp = 0f,
            corrGyroFwd = 0f, corrGyroLeft = 0f, corrGyroUp = 0f,
            latitude = Double.NaN, longitude = Double.NaN,
            gpsAccuracyM = Float.NaN, gpsSpeedMps = Float.NaN, gpsBearingDeg = Float.NaN
        )
        recorder.close()

        var csv = ""
        repeat(40) {
            csv = file.readText()
            if (csv.contains(",2.50,3.50,,")) return@repeat
            Thread.sleep(25)
        }

        val lines = csv.trim().lines()
        val columns = lines.first().split(',')
        val values = lines.last().split(',')
        fun value(column: String) = values[columns.indexOf(column)]

        assertEquals("2.50", value("estimated_speed_mps"))
        assertEquals("3.50", value("tcn_speed_mps"))
        assertEquals("", value("gps_speed_mps"))
        assertEquals("", value("eskf_speed_mps"))
    }
}
