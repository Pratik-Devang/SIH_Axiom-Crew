package com.percorsa.sensorlogger

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnDetectorTest {
    private val right = Maneuver("Turn right", 20.0, 5L, ManeuverType.TURN_RIGHT)

    @Test
    fun maneuverAloneDoesNotClaimTurning() {
        val detector = TurnDetector()
        assertEquals(TurnState.APPROACHING_RIGHT, detector.update(right, 0f, 0.0, 0f, 5f))
    }

    @Test
    fun alignedYawEvidencePersistsIntoTurning() {
        val detector = TurnDetector()
        detector.update(right, 0f, 0.0, 0f, 5f)
        detector.update(right, 8f, 0.0, 8f, 5f)
        assertEquals(TurnState.TURNING_RIGHT, detector.update(right, 16f, 0.0, 8f, 5f))
    }

    @Test
    fun oppositeYawDoesNotConfirmExpectedTurn() {
        val detector = TurnDetector()
        detector.update(right, 0f, 0.0, 0f, 5f)
        assertEquals(TurnState.APPROACHING_RIGHT, detector.update(right, 352f, 0.0, -8f, 5f))
    }
}
