package com.percorsa.sensorlogger

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class NavMode {
    IDLE,
    SEARCHING,
    ROUTE_PREVIEW,
    NAVIGATING,
    GNSS_DEGRADED,
    GNSS_DENIED,
    ARRIVED,
    ERROR
}

enum class GnssQuality {
    GOOD,
    FAIR,
    POOR,
    DENIED,
    RECOVERING;

    fun label(): String = when (this) {
        GOOD -> "GPS Locked"
        FAIR -> "GPS Fair"
        POOR -> "GPS Weak"
        DENIED -> "Tracking on sensors"
        RECOVERING -> "GPS Recovering"
    }
}

enum class DrProviderType {
    SIMPLIFIED_INS,
    PERCORSA_ESKF,
    NONE
}

data class NavigationState(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val heading: Float = 0f,
    val speed: Float = 0f,
    val positionAccuracy: Float = Float.MAX_VALUE,

    val navMode: NavMode = NavMode.IDLE,
    val gnssQuality: GnssQuality = GnssQuality.DENIED,
    val drActive: Boolean = false,
    val drProvider: DrProviderType = DrProviderType.NONE,
    val mlModelLoaded: Boolean = false,
    val mlBufferReady: Boolean = false,
    val mlInferenceActive: Boolean = false,
    val mlSpeedMps: Float = 0f,
    val mlLatencyMs: Float = 0f,
    val mlError: String? = null,

    val searchResults: List<GeocodingResult> = emptyList(),
    val searchLoading: Boolean = false,
    val searchError: String? = null,
    val recentSearches: List<GeocodingResult> = emptyList(),
    val homePlace: GeocodingResult? = null,
    val workPlace: GeocodingResult? = null,

    val destination: GeocodingResult? = null,
    val route: Route? = null,
    val routeLoading: Boolean = false,
    val routeError: String? = null,
    val distanceRemainingM: Double = 0.0,
    val etaSeconds: Long = 0L,
    val nextManeuver: Maneuver? = null,
    val secondManeuver: Maneuver? = null,
    val offRoute: Boolean = false,
    val recalculating: Boolean = false,

    val compassBearingDeg: Float = 0f,

    val isRecording: Boolean = false,
    val recordedSamples: Long = 0L,
    val navigationHealth: NavigationHealth = NavigationHealth(),

    val errorMessage: String? = null
) {
    val speedKmh: Int get() = (speed * 3.6f).toInt()

    val hasValidPosition: Boolean get() = latitude != 0.0 || longitude != 0.0

    val etaFormatted: String get() {
        if (etaSeconds <= 0L) return "--"
        val h = etaSeconds / 3600
        val m = (etaSeconds % 3600) / 60
        return if (h > 0) "%dh %02dm".format(h, m) else "%d min".format(m)
    }

    val distanceFormatted: String get() = when {
        distanceRemainingM <= 0.0 -> "--"
        distanceRemainingM < 1000 -> "%.0f m".format(distanceRemainingM)
        else -> "%.1f km".format(distanceRemainingM / 1000.0)
    }

    val routeProgressPercent: Int get() {
        val total = route?.distanceM ?: return 0
        if (total <= 0.0 || distanceRemainingM <= 0.0) return 0
        return (((total - distanceRemainingM) / total) * 100.0).toInt().coerceIn(0, 100)
    }

    val estimatedArrivalFormatted: String get() {
        if (etaSeconds <= 0L) return "--"
        val arrivalMs = System.currentTimeMillis() + etaSeconds * 1000L
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(arrivalMs))
    }

    val statusLine: String get() = when {
        recalculating ->
            "Off route - recalculating..."
        drActive && mlInferenceActive ->
            "GNSS unavailable - ML speed fused at %.1f km/h".format(mlSpeedMps * 3.6f)
        drActive && gnssQuality == GnssQuality.DENIED && drProvider == DrProviderType.PERCORSA_ESKF ->
            "GNSS unavailable - Percorsa ESKF active"
        drActive && gnssQuality == GnssQuality.DENIED ->
            "GNSS unavailable - continuing with Percorsa"
        gnssQuality == GnssQuality.RECOVERING ->
            "GNSS returned - smoothly fusing position"
        gnssQuality == GnssQuality.POOR ->
            "Weak GPS signal - accuracy reduced"
        else -> ""
    }

    val mlStatusLabel: String get() = when {
        mlInferenceActive -> "ML ACTIVE"
        mlError != null -> "ML ERROR"
        mlModelLoaded && mlBufferReady -> "ML READY"
        mlModelLoaded -> "ML WARMING"
        else -> "ML LOADING"
    }
}
