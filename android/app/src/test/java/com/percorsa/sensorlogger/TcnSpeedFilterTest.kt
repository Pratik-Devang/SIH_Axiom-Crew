package com.percorsa.sensorlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcnSpeedFilterTest {

    @Test
    fun firstPredictionPassesThrough() {
        val result = TcnSpeedFilter().update(12f, 1_000_000_000L)

        assertEquals(12f, result.speedMps, 0f)
        assertFalse(result.rateLimited)
    }

    @Test
    fun isolatedJumpIsRateLimitedAndSmoothed() {
        val filter = TcnSpeedFilter(smoothingAlpha = 0.25f, maxAccelerationMps2 = 8f)
        filter.update(10f, 1_000_000_000L)

        val result = filter.update(40f, 1_100_000_000L)

        assertTrue(result.rateLimited)
        assertEquals(10.2f, result.speedMps, 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFinitePredictionIsRejected() {
        TcnSpeedFilter().update(Float.NaN, 1_000_000_000L)
    }

    @Test
    fun resetClearsPreviousPrediction() {
        val filter = TcnSpeedFilter()
        filter.update(20f, 1_000_000_000L)
        filter.reset()

        val result = filter.update(2f, 1_100_000_000L)

        assertEquals(2f, result.speedMps, 0f)
        assertFalse(result.rateLimited)
    }
}
