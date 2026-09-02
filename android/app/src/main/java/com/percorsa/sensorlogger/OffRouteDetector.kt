package com.percorsa.sensorlogger

import kotlin.math.cos
import kotlin.math.hypot
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

    private val OFF_ROUTE_DISTANCE_THRESHOLD_M = 80.0 // Meters away from polyline
    private val PERSISTENCE_REQUIRED_TICKS = 15        // Must be off route for ~1.5s (15 ticks at 10Hz)
    private val REROUTE_COOLDOWN_MS = 8000L           // 8 seconds between reroute attempts

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

        // If position accuracy is very poor (>50m), do not trigger off-route
        if (accuracyM > 50f) {
            return currentState
        }

        val nowMs = System.currentTimeMillis()

        // Calculate minimum distance to route segments, not just vertices.
        val minDistance = minDistanceToRoute(lat, lon, route.polyline)

        // Adjust threshold by accuracy if accuracy is degraded
        val effectiveThreshold = OFF_ROUTE_DISTANCE_THRESHOLD_M + (accuracyM.coerceIn(0f, 40f) * 0.5)

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
        if (polyline.size == 1) {
            return distanceHaversine(lat, lon, polyline.first().lat, polyline.first().lon)
        }
        var minDistance = Double.MAX_VALUE
        for (i in 0 until polyline.size - 1) {
            val d = distanceToSegmentMeters(lat, lon, polyline[i], polyline[i + 1])
            if (d < minDistance) {
                minDistance = d
            }
        }
        return minDistance
    }

    private fun distanceToSegmentMeters(lat: Double, lon: Double, a: LatLon, b: LatLon): Double {
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(lat))
        val ax = (a.lon - lon) * metersPerDegLon
        val ay = (a.lat - lat) * metersPerDegLat
        val bx = (b.lon - lon) * metersPerDegLon
        val by = (b.lat - lat) * metersPerDegLat
        val vx = bx - ax
        val vy = by - ay
        val lengthSq = vx * vx + vy * vy
        if (lengthSq <= 0.000001) return hypot(ax, ay)
        val t = (-(ax * vx + ay * vy) / lengthSq).coerceIn(0.0, 1.0)
        val cx = ax + t * vx
        val cy = ay + t * vy
        return hypot(cx, cy)
    }

    private fun distanceHaversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).let { it * it }
        return r * 2.0 * kotlin.math.asin(sqrt(a))
    }
}
