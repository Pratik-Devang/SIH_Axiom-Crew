package com.percorsa.sensorlogger

import com.google.gson.Gson
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
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val viewbox = if (near != null) {
                val delta = 0.5  // ~55km box
                "&viewbox=${near.lon - delta},${near.lat + delta},${near.lon + delta},${near.lat - delta}&bounded=0"
            } else ""

            val url = "https://nominatim.openstreetmap.org/search" +
                    "?q=$encoded" +
                    "&format=json" +
                    "&addressdetails=1" +
                    "&limit=8" +
                    "&countrycodes=in" +    // India focus for SIH demo — removable
                    viewbox

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()

            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw SearchException("Nominatim returned ${response.code}")
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                parseNominatimResults(body)
            } catch (e: IOException) {
                throw SearchException("Network error: ${e.message}", e)
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
