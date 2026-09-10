package com.percorsa.sensorlogger

import kotlin.math.roundToInt

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

enum class TurnState {
    STRAIGHT,
    APPROACHING_LEFT,
    APPROACHING_RIGHT,
    TURNING_LEFT,
    TURNING_RIGHT,
    U_TURN
}

enum class SpeedSource {
    GNSS,
    ESKF,
    FALLBACK
}

enum class RotationSource {
    ROTATION_VECTOR,
    GAME_ROTATION_VECTOR,
    NONE
}

enum class DeviceHeadingConfidence {
    HIGH,
    MEDIUM,
    LOW
}

enum class EskfHealthState {
    HEALTHY,
    DEGRADED,
    DIVERGED,
    UNINITIALIZED
}

data class NavigationState(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val heading: Float = 0f,
    val speed: Float = 0f,
    val positionAccuracy: Float = Float.MAX_VALUE,

    /** Direction the physical phone is pointing (degrees clockwise from North, 0..360). */
    val deviceAzimuthDeg: Float = 0f,
    /** Direction the vehicle is traveling/facing (degrees clockwise from North, 0..360). */
    val vehicleHeadingDeg: Float = 0f,
    /** Direction of the active matched road segment (degrees clockwise from North, 0..360). */
    val routeBearingDeg: Double = Double.NaN,
    /** Active source driving the user-facing speed display. */
    val speedSource: SpeedSource = SpeedSource.GNSS,
    /** Health state of the internal ESKF dead reckoning estimator. */
    val eskfHealthState: EskfHealthState = EskfHealthState.UNINITIALIZED,
    /** Sensor source driving the device attitude and azimuth. */
    val rotationSource: RotationSource = RotationSource.NONE,
    /** Confidence in the device azimuth's absolute geographic alignment. */
    val deviceHeadingConfidence: DeviceHeadingConfidence = DeviceHeadingConfidence.LOW,

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
    val routeSegmentIndex: Int = -1,
    val routeProgressM: Double = 0.0,
    val routeLateralErrorM: Double = Double.NaN,
    val routeHeadingErrorDeg: Double = Double.NaN,
    val turnState: TurnState = TurnState.STRAIGHT,
    val turnYawRateDegS: Float = Float.NaN,
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
        distanceRemainingM < 1000.0 -> "%.0f m".format(distanceRemainingM)
        else -> "%.1f km".format(distanceRemainingM / 1000.0)
    }

    val estimatedArrivalFormatted: String get() {
        if (etaSeconds <= 0L) return "--:--"
        val arrivalTimeMillis = System.currentTimeMillis() + (etaSeconds * 1000L)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = arrivalTimeMillis }
        val hour = cal.get(java.util.Calendar.HOUR)
        val displayHour = if (hour == 0) 12 else hour
        val min = cal.get(java.util.Calendar.MINUTE)
        val amPm = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
        return "%d:%02d %s".format(displayHour, min, amPm)
    }

    val routeProgressPercent: Int get() {
        val total = route?.distanceM ?: return 0
        if (total <= 0.0) return 0
        val progress = (routeProgressM / total) * 100.0
        return progress.roundToInt().coerceIn(0, 100)
    }

    val statusLine: String get() = when {
        offRoute -> "Off route â€” recalculating"
        drActive && mlInferenceActive -> "Dead reckoning â€” ML speed active"
        drActive -> "Dead reckoning â€” IMU tracking"
        gnssQuality == GnssQuality.POOR -> "Weak GPS signal"
        else -> ""
    }
}
