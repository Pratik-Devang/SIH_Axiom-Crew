package com.percorsa.sensorlogger

import kotlin.math.abs

/** Route-aware turn state: a maneuver is only confirmed by active yaw evidence. */
class TurnDetector {
    private var previousHeadingDeg = Double.NaN
    private var evidenceTicks = 0

    fun update(
        maneuver: Maneuver?,
        activeHeadingDeg: Float,
        routeBearingDeg: Double,
        yawRateDegS: Float,
        speedMps: Float
    ): TurnState {
        if (maneuver == null || !maneuver.distanceM.isFinite() ||
            maneuver.distanceM > APPROACH_DISTANCE_M ||
            !activeHeadingDeg.isFinite() || !routeBearingDeg.isFinite() ||
            speedMps < MIN_TURN_SPEED_MPS
        ) {
            evidenceTicks = 0
            if (activeHeadingDeg.isFinite() && speedMps >= MIN_TURN_SPEED_MPS) {
                previousHeadingDeg = activeHeadingDeg.toDouble()
            }
            return TurnState.STRAIGHT
        }

        val heading = activeHeadingDeg.toDouble()
        val headingDelta = if (previousHeadingDeg.isFinite()) {
            signedBearingDelta(previousHeadingDeg, heading)
        } else 0.0
        previousHeadingDeg = heading

        if (maneuver.type == ManeuverType.U_TURN) {
            val actualEvidence = abs(yawRateDegS.toDouble()) >= MIN_YAW_RATE_DEG_S ||
                    abs(headingDelta) >= MIN_HEADING_DELTA_DEG
            evidenceTicks = if (actualEvidence) evidenceTicks + 1 else 0
            return if (evidenceTicks >= REQUIRED_EVIDENCE_TICKS) TurnState.U_TURN
            else TurnState.STRAIGHT
        }

        val expectedSign = when (maneuver.type) {
            ManeuverType.TURN_LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT -> -1
            ManeuverType.TURN_RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT -> 1
            else -> 0
        }
        if (expectedSign == 0) {
            evidenceTicks = 0
            return TurnState.STRAIGHT
        }

        val yaw = yawRateDegS.toDouble()
        val yawEvidence = yaw.isFinite() && abs(yaw) >= MIN_YAW_RATE_DEG_S &&
                yaw.signMatches(expectedSign)
        val headingEvidence = abs(headingDelta) >= MIN_HEADING_DELTA_DEG &&
                headingDelta.signMatches(expectedSign)
        val routeHeadingError = signedBearingDelta(routeBearingDeg, heading)
        val routeAgreement = routeHeadingError.signMatches(expectedSign) ||
                abs(routeHeadingError) < ROUTE_HEADING_TOLERANCE_DEG
        val actualEvidence = routeAgreement && (yawEvidence || headingEvidence)
        evidenceTicks = if (actualEvidence) evidenceTicks + 1 else maxOf(0, evidenceTicks - 1)

        return when {
            evidenceTicks >= REQUIRED_EVIDENCE_TICKS && expectedSign < 0 -> TurnState.TURNING_LEFT
            evidenceTicks >= REQUIRED_EVIDENCE_TICKS -> TurnState.TURNING_RIGHT
            expectedSign < 0 -> TurnState.APPROACHING_LEFT
            else -> TurnState.APPROACHING_RIGHT
        }
    }

    fun reset() {
        previousHeadingDeg = Double.NaN
        evidenceTicks = 0
    }

    private fun Double.signMatches(expectedSign: Int): Boolean =
        (expectedSign < 0 && this < 0.0) || (expectedSign > 0 && this > 0.0)

    companion object {
        private const val APPROACH_DISTANCE_M = 60.0
        private const val MIN_TURN_SPEED_MPS = 1.5f
        private const val MIN_YAW_RATE_DEG_S = 6.0
        private const val MIN_HEADING_DELTA_DEG = 2.0
        private const val ROUTE_HEADING_TOLERANCE_DEG = 12.0
        private const val REQUIRED_EVIDENCE_TICKS = 2

        internal fun signedBearingDelta(fromDeg: Double, toDeg: Double): Double =
            ((toDeg - fromDeg + 540.0) % 360.0) - 180.0
    }
}
