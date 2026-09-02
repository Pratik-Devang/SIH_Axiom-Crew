package com.percorsa.sensorlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometryTest {

    private val eastRoute = listOf(
        LatLon(19.00000, 72.00000),
        LatLon(19.00000, 72.00100),
        LatLon(19.00100, 72.00100)
    )

    @Test
    fun projectsOntoSegmentAndReportsCumulativeProgress() {
        val match = RouteGeometry.project(LatLon(19.00000, 72.00050), eastRoute)!!

        assertEquals(0, match.segmentIndex)
        assertTrue(match.lateralDistanceM < 1.0)
        assertTrue(match.distanceAlongM > 45.0)
        assertTrue(match.distanceAlongM < 60.0)
        assertTrue(RouteGeometry.bearingDifference(match.routeBearingDeg, 90.0) < 1.0)
    }

    @Test
    fun bearingDifferenceWrapsAcrossNorth() {
        assertEquals(2.0, RouteGeometry.bearingDifference(359.0, 1.0), 0.001)
    }

    @Test
    fun offRouteDetectorRequiresPersistentDeviation() {
        val detector = OffRouteDetector()
        val offRoad = LatLon(19.00050, 72.00200)

        repeat(14) {
            assertEquals(
                OffRouteState.OFF_ROUTE_CANDIDATE,
                detector.checkPosition(offRoad.lat, offRoad.lon, 5f, 8f, 90f, Route(eastRoute, 220.0, 30, emptyList()))
            )
        }
        assertEquals(
            OffRouteState.OFF_ROUTE,
            detector.checkPosition(offRoad.lat, offRoad.lon, 5f, 8f, 90f, Route(eastRoute, 220.0, 30, emptyList()))
        )
    }
}
