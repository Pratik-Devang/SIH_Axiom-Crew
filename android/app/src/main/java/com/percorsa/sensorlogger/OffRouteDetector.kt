package com.percorsa.sensorlogger

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

    private val OFF_ROUTE_DISTANCE_THRESHOLD_M = 75.0 // Meters away from polyline
    private val PERSISTENCE_REQUIRED_TICKS = 8         // Must be off route for ~0.8s (8 ticks at 10Hz)
    private val REROUTE_COOLDOWN_MS = 6000L           // 6 seconds between reroute attempts

    private var currentState = OffRouteState.ON_ROUTE
    private var candidateTicks = 0
    private var lastRerouteTimeMs = 0L

    val state: OffRouteState get() = currentState

    fun checkPosition(
        lat: Double,
        lon: Double,
        accuracyM: Float,
        speedMps: Float,
        route: Route?
    ): OffRouteState {
        if (route == null || route.polyline.isEmpty()) {
            currentState = OffRouteState.ON_ROUTE
            candidateTicks = 0
            return currentState
        }

        val nowMs = System.currentTimeMillis()

        // Calculate minimum distance to any segment on route
        val minDistance = minDistanceToRoute(lat, lon, route.polyline)

        // Adjust threshold by accuracy if accuracy is degraded
        val effectiveThreshold = OFF_ROUTE_DISTANCE_THRESHOLD_M + (accuracyM.coerceIn(0f, 50f) * 0.5)

        if (minDistance > effectiveThreshold) {
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
    }

    private fun minDistanceToRoute(lat: Double, lon: Double, polyline: List<LatLon>): Double {
        var minDistance = Double.MAX_VALUE
        for (pt in polyline) {
            val d = distanceHaversine(lat, lon, pt.lat, pt.lon)
            if (d < minDistance) {
                minDistance = d
            }
        }
        return minDistance
    }

    private fun distanceHaversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).let { it * it }
        return r * 2.0 * asin(sqrt(a))
    }
}
