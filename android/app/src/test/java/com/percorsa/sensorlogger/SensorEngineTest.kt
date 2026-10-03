package com.percorsa.sensorlogger

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SensorEngineTest {

    @Test
    fun testTimestampSynchronizationAndMonotonicity() {
        val sensorEngine = SensorEngine(context = null)

        // Mock CsvRecorder to count rows and verify monotonicity
        val tempFile = File.createTempFile("test_log", ".csv")
        tempFile.deleteOnExit()
        
        var writtenRowsCount = 0
        var lastWrittenTimestamp = 0L
        var isStrictlyMonotonic = true

        val customRecorder = object : CsvRecorder(context = null, overrideFile = tempFile) {
            override fun writeRow(
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
                if (lastWrittenTimestamp > 0L && timestampNs <= lastWrittenTimestamp) {
                    isStrictlyMonotonic = false
                }
                lastWrittenTimestamp = timestampNs
                writtenRowsCount++
            }

            override fun close() {}
        }

        sensorEngine.startRecording(customRecorder)

        val dummyValues = floatArrayOf(0f, 0f, 9.81f)
        val dummyQuat = floatArrayOf(0f, 0f, 0f, 1f)

        // Simulate 100 timesteps of 5 interleaved sensor callbacks arriving asynchronously
        var totalCallbacksSent = 0
        val baseTimeNs = 1_000_000_000L
        val stepNs = 5_000_000L // 200 Hz = 5ms = 5_000_000 ns per step

        for (i in 0 until 100) {
            val tNs = baseTimeNs + i * stepNs

            // 1. Accelerometer (Primary IMU stream driver)
            sensorEngine.handleSensorData(Sensor.TYPE_ACCELEROMETER, tNs, dummyValues)
            totalCallbacksSent++

            // 2. Gyroscope callback (arrives at tNs)
            sensorEngine.handleSensorData(Sensor.TYPE_GYROSCOPE, tNs, dummyValues)
            totalCallbacksSent++

            // 3. Rotation Vector callback (arrives at tNs)
            sensorEngine.handleSensorData(Sensor.TYPE_ROTATION_VECTOR, tNs, dummyQuat)
            totalCallbacksSent++

            // 4. Linear Acceleration callback (arrives at tNs)
            sensorEngine.handleSensorData(Sensor.TYPE_LINEAR_ACCELERATION, tNs, dummyValues)
            totalCallbacksSent++

            // 5. Gravity callback (arrives at tNs)
            sensorEngine.handleSensorData(Sensor.TYPE_GRAVITY, tNs, dummyValues)
            totalCallbacksSent++

            // Duplicate timestamp test: send a DUPLICATE Accelerometer callback with the exact same tNs
            sensorEngine.handleSensorData(Sensor.TYPE_ACCELEROMETER, tNs, dummyValues)
            totalCallbacksSent++

            // Non-monotonic timestamp test: send a DECREASING Accelerometer callback with tNs - 1000
            sensorEngine.handleSensorData(Sensor.TYPE_ACCELEROMETER, tNs - 1000L, dummyValues)
            totalCallbacksSent++
        }

        sensorEngine.stopRecording()

        val snapshot = sensorEngine.getSnapshot()

        println("Total Callbacks Sent : $totalCallbacksSent")
        println("Written CSV Rows     : $writtenRowsCount")
        println("Rejected Duplicates  : ${snapshot.duplicateTimestampsCount}")
        println("Rejected Non-Monotonic: ${snapshot.nonMonotonicTimestampsCount}")

        // VERIFICATIONS
        // 1. Total callbacks sent (700) > written CSV rows (100)
        assertTrue("Callbacks ($totalCallbacksSent) should be > CSV rows ($writtenRowsCount)", totalCallbacksSent > writtenRowsCount)
        assertEquals("Written CSV rows must equal 100 primary IMU steps", 100, writtenRowsCount)

        // 2. Timestamps must be strictly increasing
        assertTrue("Output timestamps must be strictly monotonic", isStrictlyMonotonic)

        // 3. Rejected duplicates and non-monotonic counts must be exactly 100 each
        assertEquals("Should reject 100 duplicate timestamps", 100L, snapshot.duplicateTimestampsCount)
        assertEquals("Should reject 100 non-monotonic timestamps", 100L, snapshot.nonMonotonicTimestampsCount)
    }
}
