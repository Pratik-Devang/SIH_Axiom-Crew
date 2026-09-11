package com.percorsa.sensorlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationControllerTimingTest {

    @Test
    fun controllerDtUsesMonotonicSensorTimestampDelta() {
        assertEquals(
            0.1,
            NavigationController.monotonicDtSeconds(1_000_000_000L, 1_100_000_000L)!!,
            1e-12
        )
    }

    @Test
    fun controllerDoesNotInventDtForFirstDuplicateOrNonMonotonicSample() {
        assertNull(NavigationController.monotonicDtSeconds(0L, 1_000_000_000L))
        assertNull(NavigationController.monotonicDtSeconds(1_000_000_000L, 1_000_000_000L))
        assertNull(NavigationController.monotonicDtSeconds(1_100_000_000L, 1_000_000_000L))
    }

    @Test
    fun controllerRejectsUnboundedTimestampGap() {
        assertNull(
            NavigationController.monotonicDtSeconds(
                1_000_000_000L,
                1_600_000_000L
            )
        )
    }
}
