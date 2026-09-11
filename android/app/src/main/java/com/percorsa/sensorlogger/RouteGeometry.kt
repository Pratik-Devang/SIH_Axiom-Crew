package com.percorsa.sensorlogger

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** A physically meaningful association between an estimate and a route segment. */
data class RouteMatch(
    val segmentIndex: Int,
    val lateralDistanceM: Double,
    val distanceAlongM: Double,
    val routeBearingDeg: Double
)

/** Equirectangular route projection, accurate over the short distances used here. */
object RouteGeometry {
    private const val METERS_PER_DEGREE = 111_320.0

    fun cumulativeDistances(polyline: List<LatLon>): DoubleArray {
        val cumulative = DoubleArray(polyline.size)
        for (i in 1 until polyline.size) {
            cumulative[i] = cumulative[i - 1] + distance(polyline[i - 1], polyline[i])
        }
        return cumulative
    }

    fun project(
        position: LatLon,
        polyline: List<LatLon>,
        startSegment: Int = 0,
        endSegment: Int = polyline.size - 2
    ): RouteMatch? {
        if (polyline.isEmpty()) return null
        if (polyline.size == 1) {
            return RouteMatch(0, distance(position, polyline[0]), 0.0, 0.0)
        }

        val cumulative = cumulativeDistances(polyline)
        val first = startSegment.coerceIn(0, polyline.size - 2)
        val last = endSegment.coerceIn(first, polyline.size - 2)
        var best: RouteMatch? = null

        for (i in first..last) {
            val a = polyline[i]
            val b = polyline[i + 1]
            val metersLon = METERS_PER_DEGREE * cos(Math.toRadians(position.lat))
            val ax = (a.lon - position.lon) * metersLon
            val ay = (a.lat - position.lat) * METERS_PER_DEGREE
            val bx = (b.lon - position.lon) * metersLon
            val by = (b.lat - position.lat) * METERS_PER_DEGREE
            val vx = bx - ax
            val vy = by - ay
            val lengthSq = vx * vx + vy * vy
            val t = if (lengthSq <= 1e-6) 0.0
            else (-(ax * vx + ay * vy) / lengthSq).coerceIn(0.0, 1.0)
            val cx = ax + t * vx
            val cy = ay + t * vy
            val lateral = hypot(cx, cy)
            val bearing = normalizeBearing(Math.toDegrees(atan2(vx, vy)))
            val along = cumulative[i] + t * hypot(vx, vy)
            if (best == null || lateral < best!!.lateralDistanceM) {
                best = RouteMatch(i, lateral, along, bearing)
            }
        }
        return best
    }

    fun distance(a: LatLon, b: LatLon): Double {
        val metersLon = METERS_PER_DEGREE * cos(Math.toRadians((a.lat + b.lat) * 0.5))
        return hypot((b.lon - a.lon) * metersLon, (b.lat - a.lat) * METERS_PER_DEGREE)
    }

    fun bearingDifference(first: Double, second: Double): Double {
        val delta = abs((first - second) % 360.0)
        return minOf(delta, 360.0 - delta)
    }

    fun normalizeBearing(value: Double): Double {
        val normalized = value % 360.0
        return if (normalized < 0.0) normalized + 360.0 else normalized
    }
}
