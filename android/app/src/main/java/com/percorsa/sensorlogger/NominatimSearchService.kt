package com.percorsa.sensorlogger

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Nominatim implementation of [SearchService].
 *
 * Uses the public Nominatim API (OpenStreetMap geocoding).
 * No API key required. Rate-limited to 1 request/second by OSM policy.
 *
 * To swap to a different provider (e.g. Mappls, Google Places):
 *   1. Create a new class implementing [SearchService]
 *   2. In NavigationController, change:
 *        private val searchService: SearchService = NominatimSearchService()
 *      to:
 *        private val searchService: SearchService = MyOtherSearchService(apiKey)
 *   No changes to UI or NavigationController logic required.
 */
class NominatimSearchService : SearchService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // Nominatim requires a User-Agent identifying the app
    private val userAgent = "Percorsa-Navigation/1.0 (SIH-Axiom-Crew)"

    override suspend fun search(query: String, near: LatLon?): List<GeocodingResult> =
        searchInternal(query, near, nearbyOnly = false)

    override suspend fun searchNearby(category: String, near: LatLon): List<GeocodingResult> =
        withContext(Dispatchers.IO) {
            val amenity = when (category.lowercase()) {
                "restaurant", "food" -> "restaurant"
                "hospital", "health" -> "hospital"
                "petrol", "fuel", "petrol pump" -> "fuel"
                else -> category.lowercase().replace("[^a-z]".toRegex(), "")
            }
            val amenityFilter = if (amenity == "restaurant") {
                "[\"amenity\"~\"restaurant|fast_food|cafe\"]"
            } else {
                "[\"amenity\"=\"$amenity\"]"
            }
            val query = "[out:json][timeout:12];nwr$amenityFilter(around:10000,${near.lat},${near.lon});out center tags;"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val endpoints = listOf(
                "https://overpass-api.de/api/interpreter",
                "https://overpass.kumi.systems/api/interpreter"
            )
            for (endpoint in endpoints) {
                try {
                    val response = client.newCall(
                        Request.Builder()
                            .url("$endpoint?data=$encodedQuery")
                            .header("User-Agent", userAgent)
                            .build()
                    ).execute()
                    if (!response.isSuccessful) {
                        response.close()
                        continue
                    }
                    val results = parseNearbyResults(response.body?.string().orEmpty(), near, amenity)
                    if (results.isNotEmpty()) return@withContext results
                } catch (_: IOException) {
                    // Try the next public Overpass instance.
                }
            }
            emptyList()
        }

    private suspend fun searchInternal(query: String, near: LatLon?, nearbyOnly: Boolean): List<GeocodingResult> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val viewbox = if (near != null) {
                val delta = if (nearbyOnly) 0.08 else 0.5
                val bounded = if (nearbyOnly) 1 else 0
                "&viewbox=${near.lon - delta},${near.lat + delta},${near.lon + delta},${near.lat - delta}&bounded=$bounded"
            } else ""

            val url = "https://nominatim.openstreetmap.org/search" +
                    "?q=$encoded" +
                    "&format=json" +
                    "&addressdetails=1" +
                    "&limit=${if (nearbyOnly) 20 else 8}" +
                    "&countrycodes=in" +    // India focus for SIH demo — removable
                    viewbox

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()

            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    throw SearchException("Nominatim returned ${response.code}")
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                val results = parseNominatimResults(body)
                if (nearbyOnly && near != null) {
                    results.sortedBy { distanceMeters(near, it.location) }
                } else results
            } catch (e: IOException) {
                throw SearchException("Network error: ${e.message}", e)
            }
    }

    private fun distanceMeters(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat = Math.toRadians((a.lat + b.lat) / 2.0)
        return 6371000.0 * kotlin.math.sqrt(dLat * dLat + kotlin.math.cos(lat) * kotlin.math.cos(lat) * dLon * dLon)
    }

    private fun parseNearbyResults(json: String, near: LatLon, amenity: String): List<GeocodingResult> {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            root.getAsJsonArray("elements").mapNotNull { element ->
                val item = element.asJsonObject
                val tags = item.getAsJsonObject("tags") ?: return@mapNotNull null
                val point = when {
                    item.has("lat") && item.has("lon") -> item
                    item.has("center") -> item.getAsJsonObject("center")
                    else -> return@mapNotNull null
                }
                val lat = point.get("lat")?.asDouble ?: return@mapNotNull null
                val lon = point.get("lon")?.asDouble ?: return@mapNotNull null
                val name = tags.get("name")?.asString?.takeIf { it.isNotBlank() }
                    ?: amenity.replaceFirstChar { it.uppercase() }
                val address = listOfNotNull(
                    tags.get("addr:street")?.asString,
                    tags.get("addr:housenumber")?.asString,
                    tags.get("addr:city")?.asString
                ).joinToString(", ")
                val id = item.get("id")?.asString ?: "$lat,$lon"
                GeocodingResult(
                    id = "overpass:$id",
                    name = name,
                    address = address.ifBlank { "Nearby ${amenity}" },
                    location = LatLon(lat, lon),
                    category = "amenity/$amenity"
                )
            }.sortedBy { distanceMeters(near, it.location) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseNominatimResults(json: String): List<GeocodingResult> {
        return try {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val items: List<Map<String, Any>> = gson.fromJson(json, type)
            items.mapNotNull { item ->
                val lat = (item["lat"] as? String)?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = (item["lon"] as? String)?.toDoubleOrNull() ?: return@mapNotNull null
                val displayName = item["display_name"] as? String ?: return@mapNotNull null
                val osmId = item["osm_id"]?.toString() ?: lat.toString()
                val type = item["type"] as? String ?: ""
                val category = item["class"] as? String ?: ""

                // Build a concise name + address from display_name
                val parts = displayName.split(", ")
                val name = parts.firstOrNull() ?: displayName
                val address = parts.drop(1).take(3).joinToString(", ")

                GeocodingResult(
                    id = osmId,
                    name = name,
                    address = address,
                    location = LatLon(lat, lon),
                    category = "$category/$type"
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
