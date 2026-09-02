package com.percorsa.sensorlogger

enum class OffRouteState {
    ON_ROUTE,
    OFF_ROUTE_CANDIDATE,
    OFF_ROUTE,
    RECALCULATING
}

/**
 * Robust off-route decision state machine.
 * Considers distance to route, accuracy, persistence/hysteresis, and reroute cooldowns.
 */
class OffRouteDetector {

    private val OFF_ROUTE_DISTANCE_THRESHOLD_M = 60.0 // metres; wider than a lane, below a parallel road
    private val HEADING_MISMATCH_DEGREES = 55.0      // reject opposing route direction while moving
    private val PERSISTENCE_REQUIRED_TICKS = 15      // 1.5s at the 10Hz navigation tick
    private val REROUTE_COOLDOWN_MS = 30_000L        // avoid route churn from GPS noise

    private var currentState = OffRouteState.ON_ROUTE
    private var candidateTicks = 0
    private var lastRerouteTimeMs = 0L
    private var lastMatch: RouteMatch? = null

    val state: OffRouteState get() = currentState
    val routeMatch: RouteMatch? get() = lastMatch

    fun checkPosition(
        lat: Double,
        lon: Double,
        accuracyM: Float,
        speedMps: Float,
        headingDeg: Float,
        route: Route?
    ): OffRouteState {
        if (route == null || route.polyline.isEmpty()) {
            currentState = OffRouteState.ON_ROUTE
            candidateTicks = 0
            return currentState
        }

        // An uncertain fix cannot distinguish a deviation from measurement noise.
        if (accuracyM > 50f) {
            return currentState
        }

        val nowMs = System.currentTimeMillis()

        val position = LatLon(lat, lon)
        val localStart = (lastMatch?.segmentIndex ?: 0) - 40
        val localEnd = (lastMatch?.segmentIndex ?: 0) + 40
        var match = RouteGeometry.project(position, route.polyline, localStart, localEnd)
        if (match == null || match.lateralDistanceM > OFF_ROUTE_DISTANCE_THRESHOLD_M + 50.0) {
            // A genuine road change can leave the local route window; search all segments once.
            match = RouteGeometry.project(position, route.polyline)
        }
        lastMatch = match
        val minDistance = match?.lateralDistanceM ?: Double.MAX_VALUE

        // Adjust threshold by accuracy if accuracy is degraded
        val effectiveThreshold = OFF_ROUTE_DISTANCE_THRESHOLD_M + (accuracyM.coerceIn(0f, 40f) * 0.5)
        val headingMismatch = match != null && speedMps > 3.0f &&
                RouteGeometry.bearingDifference(headingDeg.toDouble(), match.routeBearingDeg) > HEADING_MISMATCH_DEGREES

        if (minDistance > effectiveThreshold || (headingMismatch && minDistance > 35.0)) {
            candidateTicks++
            if (candidateTicks >= PERSISTENCE_REQUIRED_TICKS) {
                if (nowMs - lastRerouteTimeMs > REROUTE_COOLDOWN_MS) {
                    currentState = OffRouteState.OFF_ROUTE
                } else {
                    currentState = OffRouteState.OFF_ROUTE_CANDIDATE
                }
            } else {
                currentState = OffRouteState.OFF_ROUTE_CANDIDATE
            }
        } else {
            candidateTicks = 0
            currentState = OffRouteState.ON_ROUTE
        }

        return currentState
    }

    fun markRecalculating() {
        currentState = OffRouteState.RECALCULATING
        lastRerouteTimeMs = System.currentTimeMillis()
        candidateTicks = 0
    }

    fun reset() {
        currentState = OffRouteState.ON_ROUTE
        candidateTicks = 0
        lastRerouteTimeMs = 0L
        lastMatch = null
    }
}
