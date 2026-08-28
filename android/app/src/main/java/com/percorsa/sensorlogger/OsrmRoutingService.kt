package com.percorsa.sensorlogger

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OSRM implementation of [RoutingService].
 *
 * Uses the public OSRM demo server (OpenStreetMap routing).
 * No API key required.
 *
 * To swap to a different provider (e.g. Valhalla, Google Directions, Mappls):
 *   1. Create a new class implementing [RoutingService]
 *   2. In NavigationController, change:
 *        private val routingService: RoutingService = OsrmRoutingService()
 *      to:
 *        private val routingService: RoutingService = MyOtherRoutingService(apiKey)
 *   No changes to UI or NavigationController logic required.
 */
class OsrmRoutingService : RoutingService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // OSRM demo server — replace with self-hosted for production
    private val baseUrl = "https://router.project-osrm.org"

    override suspend fun getRoute(origin: LatLon, destination: LatLon): Route? =
        withContext(Dispatchers.IO) {
            // OSRM uses lon,lat ordering
            val coords = "${origin.lon},${origin.lat};${destination.lon},${destination.lat}"
            val url = "$baseUrl/route/v1/driving/$coords" +
                    "?overview=full&geometries=geojson&steps=true&annotations=false"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Percorsa-Navigation/1.0")
                .build()

            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw RoutingException("OSRM returned ${response.code}")
                }
                val body = response.body?.string() ?: return@withContext null
                parseOsrmResponse(body)
            } catch (e: IOException) {
                throw RoutingException("Network error: ${e.message}", e)
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun parseOsrmResponse(json: String): Route? {
        return try {
            val root = gson.fromJson(json, Map::class.java) as Map<String, Any>
            val code = root["code"] as? String
            if (code != "Ok") return null

            val routes = root["routes"] as? List<*> ?: return null
            val route = routes.firstOrNull() as? Map<String, Any> ?: return null

            val distanceM = (route["distance"] as? Double) ?: 0.0
            val durationS = ((route["duration"] as? Double) ?: 0.0).toLong()

            // Parse polyline from GeoJSON coordinates
            val geometry = route["geometry"] as? Map<String, Any>
            val coordinates = geometry?.get("coordinates") as? List<*> ?: emptyList<Any>()
            val polyline = coordinates.mapNotNull { pt ->
                val pair = pt as? List<*> ?: return@mapNotNull null
                val lon = (pair[0] as? Double) ?: return@mapNotNull null
                val lat = (pair[1] as? Double) ?: return@mapNotNull null
                LatLon(lat, lon)
            }

            // Parse turn-by-turn steps
            val legs = route["legs"] as? List<*> ?: emptyList<Any>()
            val maneuvers = mutableListOf<Maneuver>()
            for (leg in legs) {
                val legMap = leg as? Map<String, Any> ?: continue
                val steps = legMap["steps"] as? List<*> ?: continue
                for (step in steps) {
                    val stepMap = step as? Map<String, Any> ?: continue
                    val stepDist = (stepMap["distance"] as? Double) ?: 0.0
                    val stepDur = ((stepMap["duration"] as? Double) ?: 0.0).toLong()
                    val maneuverMap = stepMap["maneuver"] as? Map<String, Any>
                    val maneuverType = maneuverMap?.get("type") as? String ?: "straight"
                    val modifier = maneuverMap?.get("modifier") as? String ?: ""
                    val instruction = buildInstruction(maneuverType, modifier)
                    val type = classifyManeuver(maneuverType, modifier)
                    if (stepDist > 1.0) {
                        maneuvers.add(Maneuver(instruction, stepDist, stepDur, type))
                    }
                }
            }

            Route(
                polyline = polyline,
                distanceM = distanceM,
                durationSeconds = durationS,
                maneuvers = maneuvers
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun buildInstruction(type: String, modifier: String): String = when (type) {
        "depart"       -> "Head ${modifier.ifEmpty { "forward" }}"
        "arrive"       -> "You have arrived"
        "turn"         -> when (modifier) {
            "left"        -> "Turn left"
            "right"       -> "Turn right"
            "slight left" -> "Keep left"
            "slight right"-> "Keep right"
            "sharp left"  -> "Sharp left"
            "sharp right" -> "Sharp right"
            "uturn"       -> "Make a U-turn"
            else          -> "Turn"
        }
        "continue"     -> "Continue ${modifier.ifEmpty { "straight" }}"
        "merge"        -> "Merge ${modifier.ifEmpty { "" }}"
        "roundabout"   -> "Enter roundabout"
        "exit roundabout" -> "Exit roundabout"
        "fork"         -> "Keep ${modifier.ifEmpty { "straight" }} at fork"
        else           -> modifier.ifEmpty { type }.replaceFirstChar { it.uppercase() }
    }

    private fun classifyManeuver(type: String, modifier: String): ManeuverType = when (type) {
        "arrive"   -> ManeuverType.ARRIVE
        "depart"   -> ManeuverType.DEPART
        "turn"     -> when (modifier) {
            "left"        -> ManeuverType.TURN_LEFT
            "right"       -> ManeuverType.TURN_RIGHT
            "slight left" -> ManeuverType.SLIGHT_LEFT
            "slight right"-> ManeuverType.SLIGHT_RIGHT
            "sharp left"  -> ManeuverType.SHARP_LEFT
            "sharp right" -> ManeuverType.SHARP_RIGHT
            "uturn"       -> ManeuverType.U_TURN
            else          -> ManeuverType.STRAIGHT
        }
        "roundabout" -> ManeuverType.ROUNDABOUT
        else         -> ManeuverType.STRAIGHT
    }
}
