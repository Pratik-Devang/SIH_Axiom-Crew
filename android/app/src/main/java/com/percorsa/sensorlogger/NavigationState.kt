package com.percorsa.sensorlogger

/**
 * Navigation mode state machine states.
 * All UI transitions are driven by changes to NavMode.
 */
enum class NavMode {
    /** App just opened, no destination. */
    IDLE,
    /** User typed in search bar, results loading. */
    SEARCHING,
    /** Destination selected, route computed, awaiting START. */
    ROUTE_PREVIEW,
    /** Active navigation with good GNSS. */
    NAVIGATING,
    /** Navigation ongoing but GNSS signal degraded (accuracy > 30m). */
    GNSS_DEGRADED,
    /** GNSS fix lost; dead-reckoning active. */
    GNSS_DENIED,
    /** Arrived within arrival radius of destination. */
    ARRIVED,
    /** Unrecoverable error (network down, sensors failed). */
    ERROR
}

/**
 * GNSS quality classification based on accuracy, fix age, and consistency.
 */
enum class GnssQuality {
    /** accuracy ≤ 10m, fresh fix. */
    GOOD,
    /** accuracy 10–30m, or slightly stale. */
    FAIR,
    /** accuracy >30m, or stale. */
    POOR,
    /** No fix for more than 5 seconds. */
    DENIED,
    /** GNSS returned after outage, blending smoothly back. */
    RECOVERING;

    fun label(): String = when (this) {
        GOOD       -> "GPS"
        FAIR       -> "GPS • Fair"
        POOR       -> "GPS • Weak"
        DENIED     -> "GPS unavailable"
        RECOVERING -> "GPS • Recovering"
    }
}

/**
 * Identifies which dead-reckoning provider is active.
 * Allows the UI to communicate honestly what algorithm is running.
 */
enum class DrProviderType {
    /** Temporary simplified INS (linear accel integration + rotation vector heading). */
    SIMPLIFIED_INS,
    /**
     * Placeholder for the full Percorsa ESKF navigation stack.
     * Will replace SIMPLIFIED_INS once ported from Python.
     */
    PERCORSA_ESKF,
    /** No DR active. */
    NONE
}

/**
 * Single source of truth for the entire navigation UI and map layer.
 *
 * Created and emitted by [NavigationController].
 * [MainActivity] must only read this — never read raw [SensorSnapshot] directly.
 */
data class NavigationState(

    // ── Position ──────────────────────────────────────────────────────────────
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    /** Vehicle heading in degrees (0 = North, clockwise). */
    val heading: Float = 0f,
    /** Speed in m/s. */
    val speed: Float = 0f,
    /** Estimated position accuracy radius in metres. */
    val positionAccuracy: Float = Float.MAX_VALUE,

    // ── Navigation mode ────────────────────────────────────────────────────────
    val navMode: NavMode = NavMode.IDLE,
    val gnssQuality: GnssQuality = GnssQuality.DENIED,
    /** True when dead-reckoning is contributing to the position estimate. */
    val drActive: Boolean = false,
    val drProvider: DrProviderType = DrProviderType.NONE,

    // ── Search ────────────────────────────────────────────────────────────────
    val searchResults: List<GeocodingResult> = emptyList(),
    val searchLoading: Boolean = false,
    val searchError: String? = null,
    val recentSearches: List<GeocodingResult> = emptyList(),
    val homePlace: GeocodingResult? = null,
    val workPlace: GeocodingResult? = null,

    // ── Route / navigation progress ────────────────────────────────────────────
    val destination: GeocodingResult? = null,
    val route: Route? = null,
    val routeLoading: Boolean = false,
    val routeError: String? = null,
    /** Distance remaining to destination, in metres. */
    val distanceRemainingM: Double = 0.0,
    /** Estimated seconds to arrival. */
    val etaSeconds: Long = 0L,
    val nextManeuver: Maneuver? = null,
    val secondManeuver: Maneuver? = null,
    val offRoute: Boolean = false,
    val recalculating: Boolean = false,

    // ── UI Controls ───────────────────────────────────────────────────────────
    val compassBearingDeg: Float = 0f,

    // ── Diagnostics & Health (Developer Mode) ─────────────────────────────────
    val isRecording: Boolean = false,
    val recordedSamples: Long = 0L,
    val navigationHealth: NavigationHealth = NavigationHealth(),

    // ── Error ─────────────────────────────────────────────────────────────────
    val errorMessage: String? = null
) {
    /** Speed formatted as km/h integer string for the speed widget. */
    val speedKmh: Int get() = (speed * 3.6f).toInt()

    /** True if position is valid (not default 0,0). */
    val hasValidPosition: Boolean get() = latitude != 0.0 || longitude != 0.0

    /** ETA formatted as "HH:mm" or "mm min". */
    val etaFormatted: String get() {
        if (etaSeconds <= 0L) return "--"
        val h = etaSeconds / 3600
        val m = (etaSeconds % 3600) / 60
        return if (h > 0) "%dh %02dm".format(h, m) else "%d min".format(m)
    }

    /** Distance remaining formatted for display. */
    val distanceFormatted: String get() = when {
        distanceRemainingM <= 0.0  -> "--"
        distanceRemainingM < 1000  -> "%.0f m".format(distanceRemainingM)
        else                       -> "%.1f km".format(distanceRemainingM / 1000.0)
    }

    /** Human-readable status line for the bottom sheet sub-title. */
    val statusLine: String get() = when {
        recalculating ->
            "Off route • Recalculating..."
        drActive && gnssQuality == GnssQuality.DENIED && drProvider == DrProviderType.PERCORSA_ESKF ->
            "GNSS unavailable • Percorsa ESKF active"
        drActive && gnssQuality == GnssQuality.DENIED ->
            "GNSS unavailable • Continuing with Percorsa"
        gnssQuality == GnssQuality.RECOVERING ->
            "GNSS returned • Smoothly fusing position"
        gnssQuality == GnssQuality.POOR ->
            "Weak GPS signal — accuracy reduced"
        else -> ""
    }
}
