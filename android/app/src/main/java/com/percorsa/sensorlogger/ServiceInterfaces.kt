package com.percorsa.sensorlogger

/**
 * Lightweight lat/lon pair used throughout the service and navigation layers.
 */
data class LatLon(val lat: Double, val lon: Double)

/**
 * A geocoded place returned by a [SearchService] implementation.
 */
data class GeocodingResult(
    val id: String,
    val name: String,
    val address: String,
    val location: LatLon,
    val category: String = ""
)

/**
 * A single turn-by-turn maneuver in a route.
 */
data class Maneuver(
    val instruction: String,
    val distanceM: Double,
    val durationSeconds: Long,
    val type: ManeuverType = ManeuverType.STRAIGHT
)

enum class ManeuverType {
    STRAIGHT, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
    SHARP_LEFT, SHARP_RIGHT, U_TURN, ROUNDABOUT, ARRIVE, DEPART, UNKNOWN
}

/**
 * A computed route between two points.
 */
data class Route(
    /** Ordered list of lat/lon points forming the route polyline. */
    val polyline: List<LatLon>,
    val distanceM: Double,
    val durationSeconds: Long,
    val maneuvers: List<Maneuver>
) {
    val distanceFormatted: String get() = when {
        distanceM < 1000 -> "%.0f m".format(distanceM)
        else             -> "%.1f km".format(distanceM / 1000.0)
    }

    val durationFormatted: String get() {
        val h = durationSeconds / 3600
        val m = (durationSeconds % 3600) / 60
        return if (h > 0) "%dh %02dm".format(h, m) else "%d min".format(m)
    }
}

// ─── Search Service ───────────────────────────────────────────────────────────

/**
 * Abstraction layer for place search / geocoding.
 * Swap the implementation without touching [NavigationController] or [MainActivity].
 *
 * Current implementation: [NominatimSearchService]
 * Future implementation: Mappls, Google Places, HERE, etc.
 */
interface SearchService {
    /**
     * Search for places matching [query] near [near] (optional).
     * Returns empty list on no results.
     * Throws [SearchException] on network or provider error.
     */
    suspend fun search(query: String, near: LatLon? = null): List<GeocodingResult>

    /** Search a category in a small area around the current position. */
    suspend fun searchNearby(category: String, near: LatLon): List<GeocodingResult> =
        search(category, near)
}

class SearchException(message: String, cause: Throwable? = null) : Exception(message, cause)

// ─── Routing Service ──────────────────────────────────────────────────────────

/**
 * Abstraction layer for route calculation.
 * Swap the implementation without touching [NavigationController] or [MainActivity].
 *
 * Current implementation: [OsrmRoutingService]
 * Future implementation: Valhalla, Google Directions, Mappls, etc.
 */
interface RoutingService {
    /**
     * Calculate the best route from [origin] to [destination].
     * Returns null if no route found.
     * Throws [RoutingException] on network or provider error.
     */
    suspend fun getRoute(origin: LatLon, destination: LatLon): Route?
}

class RoutingException(message: String, cause: Throwable? = null) : Exception(message, cause)
