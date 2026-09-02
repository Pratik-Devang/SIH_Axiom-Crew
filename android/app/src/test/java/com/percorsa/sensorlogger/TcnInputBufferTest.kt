package com.percorsa.sensorlogger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcnInputBufferTest {

    @Test
    fun defaultBufferMatchesDeployedOnnxContract() {
        val buffer = TcnInputBuffer()

        assertEquals(50, buffer.capacity)
        assertEquals(5.0f, buffer.windowSeconds, 0.0f)
        assertFalse(buffer.isReady)

        repeat(buffer.capacity) { index -> buffer.push(sample(index)) }

        assertTrue(buffer.isReady)
        assertEquals(50, buffer.size)
        val matrix = buffer.getFeatureMatrix()
        assertEquals(6, matrix.size)
        matrix.forEach { channel -> assertEquals(50, channel.size) }
        assertArrayEquals(floatArrayOf(0f, 1f, 2f), matrix[0].copyOfRange(0, 3), 0.0f)
        assertArrayEquals(floatArrayOf(5f, 6f, 7f), matrix[5].copyOfRange(0, 3), 0.0f)
    }

    @Test
    fun fullBufferDropsOldestSample() {
        val buffer = TcnInputBuffer(capacity = 3)
        repeat(4) { index -> buffer.push(sample(index)) }

        assertEquals(3, buffer.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), buffer.getFeatureMatrix()[0], 0.0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun capacityMustBePositive() {
        TcnInputBuffer(capacity = 0)
    }

    private fun sample(index: Int) = CanonicalImuSample(
        timestampNs = index.toLong(),
        accelX = index.toFloat(),
        accelY = (index + 1).toFloat(),
        accelZ = (index + 2).toFloat(),
        gyroX = (index + 3).toFloat(),
        gyroY = (index + 4).toFloat(),
        gyroZ = (index + 5).toFloat()
    )
}
