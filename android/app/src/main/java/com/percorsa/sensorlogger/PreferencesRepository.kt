package com.percorsa.sensorlogger

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persists recent searches and saved places across app launches.
 */
class PreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("percorsa_nav_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val KEY_RECENT_SEARCHES = "recent_searches"
    private val KEY_HOME_PLACE = "home_place"
    private val KEY_WORK_PLACE = "work_place"

    fun getRecentSearches(): List<GeocodingResult> {
        val json = prefs.getString(KEY_RECENT_SEARCHES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<GeocodingResult>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addRecentSearch(result: GeocodingResult) {
        val current = getRecentSearches().toMutableList()
        current.removeAll { it.id == result.id || (it.name == result.name && it.address == result.address) }
        current.add(0, result)
        if (current.size > 10) {
            current.removeAt(current.size - 1)
        }
        prefs.edit().putString(KEY_RECENT_SEARCHES, gson.toJson(current)).apply()
    }

    fun clearRecentSearches() {
        prefs.edit().remove(KEY_RECENT_SEARCHES).apply()
    }

    fun getHomePlace(): GeocodingResult? {
        val json = prefs.getString(KEY_HOME_PLACE, null) ?: return null
        return try {
            gson.fromJson(json, GeocodingResult::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun setHomePlace(result: GeocodingResult) {
        prefs.edit().putString(KEY_HOME_PLACE, gson.toJson(result)).apply()
    }

    fun getWorkPlace(): GeocodingResult? {
        val json = prefs.getString(KEY_WORK_PLACE, null) ?: return null
        return try {
            gson.fromJson(json, GeocodingResult::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun setWorkPlace(result: GeocodingResult) {
        prefs.edit().putString(KEY_WORK_PLACE, gson.toJson(result)).apply()
    }
}
