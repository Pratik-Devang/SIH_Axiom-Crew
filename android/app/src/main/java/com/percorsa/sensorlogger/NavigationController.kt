package com.percorsa.sensorlogger

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Central navigation state machine.
 *
 * This class:
 * - Owns the [SensorEngine] lifecycle
 * - Owns the [DeadReckoningProvider] — currently [SimplifiedInsProvider]
 *   (replace with [PercorsaEskfProvider] when TCN + ESKF are ported)
 * - Owns [GnssQualityMonitor]
 * - Owns [SearchService] and [RoutingService] (interfaces — not hardcoded providers)
 * - Emits [NavigationState] as a [StateFlow]
 * - MainActivity consumes ONLY the StateFlow — never raw sensors
 *
 * Navigation pipeline:
 *   SensorEngine → phone→vehicle transform (inside SensorEngine) →
 *   DeadReckoningProvider.update() → GnssQualityMonitor.update() →
 *   GnssCorrection when quality ≥ FAIR → NavigationState → UI
 */
class NavigationController(private val context: Context) {

    // ── Navigation engine ─────────────────────────────────────────────────────
    /**
     * Active dead-reckoning / navigation engine.
     *
     * Currently: [SimplifiedInsProvider] — temporary INS fallback.
     * Replace with: [PercorsaEskfProvider] — real Percorsa TCN + ESKF.
     *
     * To switch: change this single line. Zero other changes needed.
     */
    private val drEngine: DeadReckoningProvider = SimplifiedInsProvider()

    // ── Supporting components ─────────────────────────────────────────────────
    val sensorEngine = SensorEngine(context)
    private val gnssMonitor = GnssQualityMonitor()

    // Search and routing — interfaces, not hardcoded providers
    private val searchService: SearchService = NominatimSearchService()
    private val routingService: RoutingService = OsrmRoutingService()

    // ── State ─────────────────────────────────────────────────────────────────
    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var searchJob: Job? = null
    private var routeJob: Job? = null

    // Timing for DR dt calculation
    private var lastUpdateMs: Long = 0L

    // Arrival detection
    private val ARRIVAL_RADIUS_M = 50.0

    // GNSS recovery blend — passed through to DR engine
    private val GNSS_BLEND_SECONDS = 3.0

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        sensorEngine.start()
        lastUpdateMs = System.currentTimeMillis()
    }

    fun stop() {
        sensorEngine.stop()
        searchJob?.cancel()
        routeJob?.cancel()
    }

    /**
     * Called by MainActivity's UI handler (~10 Hz).
     * Reads the latest SensorSnapshot and advances the navigation pipeline.
     */
    fun tick() {
        val nowMs = System.currentTimeMillis()
        val dtSeconds = if (lastUpdateMs > 0L)
            ((nowMs - lastUpdateMs) / 1000.0).coerceIn(0.005, 0.5)
        else 0.1
        lastUpdateMs = nowMs

        val snap = sensorEngine.getSnapshot()
        val gnssQuality = gnssMonitor.update(snap)

        val current = _state.value

        // ── Feed GNSS correction to DR engine when trusted ──────────────────
        val hasTrustedGnss = gnssMonitor.shouldUseMeasurement() && snap.hasGps && snap.latitude != 0.0
        if (hasTrustedGnss) {
            val blendWindow = if (gnssMonitor.isGnssDenied()) 0.0 else GNSS_BLEND_SECONDS
            drEngine.injectGnssCorrection(
                lat = snap.latitude,
                lon = snap.longitude,
                accuracyM = snap.gpsAccuracyM,
                speedMps = snap.gpsSpeedMps,
                bearingDeg = snap.gpsBearingDeg,
                blendWindowSeconds = blendWindow
            )
        }

        // ── Advance DR engine ────────────────────────────────────────────────
        drEngine.update(snap, dtSeconds)
        val drPos = drEngine.getEstimatedPosition()

        // ── Derive position, speed, heading for NavigationState ──────────────
        val lat: Double
        val lon: Double
        val heading: Float
        val speed: Float
        val accuracy: Float
        val drActive: Boolean

        when {
            drPos != null -> {
                lat = drPos.latitude
                lon = drPos.longitude
                heading = drPos.heading
                speed = if (snap.hasGps && hasTrustedGnss) snap.gpsSpeedMps else drPos.speedMps
                accuracy = drPos.estimatedAccuracyM
                drActive = !hasTrustedGnss || drPos.gnssBlendFactor < 1f
            }
            snap.hasGps && snap.latitude != 0.0 -> {
                lat = snap.latitude
                lon = snap.longitude
                heading = snap.gpsBearingDeg
                speed = snap.gpsSpeedMps
                accuracy = snap.gpsAccuracyM
                drActive = false
            }
            else -> {
                // Nothing available yet
                val prev = _state.value
                updateState(prev.copy(
                    gnssQuality = gnssQuality,
                    isRecording = sensorEngine.isRecording,
                    recordedSamples = snap.loggedCsvRows
                ))
                return
            }
        }

        // ── Navigation mode transitions ───────────────────────────────────────
        val currentMode = current.navMode
        val newMode: NavMode = when (currentMode) {
            NavMode.NAVIGATING, NavMode.GNSS_DEGRADED, NavMode.GNSS_DENIED -> {
                when {
                    current.route != null && distanceTo(lat, lon,
                        current.destination?.location?.lat ?: lat,
                        current.destination?.location?.lon ?: lon) < ARRIVAL_RADIUS_M ->
                        NavMode.ARRIVED
                    gnssQuality == GnssQuality.DENIED   -> NavMode.GNSS_DENIED
                    gnssQuality == GnssQuality.POOR     -> NavMode.GNSS_DEGRADED
                    else                                -> NavMode.NAVIGATING
                }
            }
            NavMode.ARRIVED -> NavMode.ARRIVED  // sticky
            else -> currentMode
        }

        // ── Route progress ────────────────────────────────────────────────────
        val route = current.route
        val distRemaining: Double
        val etaSec: Long
        val nextManeuver: Maneuver?

        if (route != null && (newMode == NavMode.NAVIGATING ||
                newMode == NavMode.GNSS_DEGRADED || newMode == NavMode.GNSS_DENIED)) {
            distRemaining = distanceTo(lat, lon,
                current.destination!!.location.lat,
                current.destination.location.lon)
            val avgSpeedMps = if (speed > 0.5f) speed.toDouble() else 8.33 // default 30 km/h
            etaSec = (distRemaining / avgSpeedMps).toLong()
            nextManeuver = findNextManeuver(lat, lon, route)
        } else {
            distRemaining = current.distanceRemainingM
            etaSec = current.etaSeconds
            nextManeuver = current.nextManeuver
        }

        updateState(current.copy(
            latitude = lat,
            longitude = lon,
            heading = heading,
            speed = speed,
            positionAccuracy = accuracy,
            navMode = newMode,
            gnssQuality = gnssQuality,
            drActive = drActive,
            drProvider = if (drActive) drEngine.providerType else DrProviderType.NONE,
            distanceRemainingM = distRemaining,
            etaSeconds = etaSec,
            nextManeuver = nextManeuver,
            isRecording = sensorEngine.isRecording,
            recordedSamples = snap.loggedCsvRows
        ))
    }

    // ── Navigation actions ────────────────────────────────────────────────────

    fun startNavigation(destination: GeocodingResult) {
        val origin = LatLon(_state.value.latitude, _state.value.longitude)
        if (origin.lat == 0.0 && origin.lon == 0.0) {
            updateState(_state.value.copy(
                navMode = NavMode.ERROR,
                errorMessage = "Waiting for GPS fix before calculating route"
            ))
            return
        }
        updateState(_state.value.copy(
            destination = destination,
            navMode = NavMode.ROUTE_PREVIEW,
            routeLoading = true,
            routeError = null,
            route = null
        ))
        routeJob?.cancel()
        routeJob = coroutineScope.launch {
            try {
                val route = routingService.getRoute(origin, destination.location)
                if (route == null) {
                    updateState(_state.value.copy(
                        routeLoading = false,
                        routeError = "No route found to destination",
                        navMode = NavMode.IDLE
                    ))
                } else {
                    updateState(_state.value.copy(
                        route = route,
                        routeLoading = false,
                        distanceRemainingM = route.distanceM,
                        etaSeconds = route.durationSeconds,
                        nextManeuver = route.maneuvers.firstOrNull()
                    ))
                }
            } catch (e: RoutingException) {
                updateState(_state.value.copy(
                    routeLoading = false,
                    routeError = "Routing unavailable: ${e.message}",
                    navMode = NavMode.IDLE
                ))
            }
        }
    }

    fun beginDriving() {
        if (_state.value.navMode == NavMode.ROUTE_PREVIEW && _state.value.route != null) {
            updateState(_state.value.copy(navMode = NavMode.NAVIGATING))
        }
    }

    fun stopNavigation() {
        drEngine.reset()
        gnssMonitor.reset()
        updateState(NavigationState(
            latitude = _state.value.latitude,
            longitude = _state.value.longitude,
            heading = _state.value.heading,
            speed = _state.value.speed,
            gnssQuality = _state.value.gnssQuality,
            isRecording = _state.value.isRecording,
            recordedSamples = _state.value.recordedSamples
        ))
    }

    // ── Search ────────────────────────────────────────────────────────────────

    fun search(query: String) {
        if (query.isBlank()) {
            updateState(_state.value.copy(
                navMode = NavMode.IDLE,
                searchResults = emptyList(),
                searchLoading = false,
                searchError = null
            ))
            return
        }
        searchJob?.cancel()
        updateState(_state.value.copy(
            navMode = NavMode.SEARCHING,
            searchLoading = true,
            searchResults = emptyList(),
            searchError = null
        ))
        val near = _state.value.let {
            if (it.hasValidPosition) LatLon(it.latitude, it.longitude) else null
        }
        searchJob = coroutineScope.launch {
            try {
                val results = searchService.search(query, near)
                updateState(_state.value.copy(
                    searchResults = results,
                    searchLoading = false,
                    searchError = if (results.isEmpty()) "No results found" else null
                ))
            } catch (e: SearchException) {
                updateState(_state.value.copy(
                    searchLoading = false,
                    searchError = "Search unavailable — check internet connection"
                ))
            }
        }
    }

    fun cancelSearch() {
        searchJob?.cancel()
        updateState(_state.value.copy(
            navMode = NavMode.IDLE,
            searchResults = emptyList(),
            searchLoading = false,
            searchError = null
        ))
    }

    // ── Recording (proxy to SensorEngine) ────────────────────────────────────

    fun startRecording(recorder: CsvRecorder) {
        sensorEngine.startRecording(recorder)
        updateState(_state.value.copy(isRecording = true))
    }

    fun stopRecording() {
        sensorEngine.stopRecording()
        updateState(_state.value.copy(isRecording = false))
    }

    fun calibrateVehicleFrame() {
        sensorEngine.calibrateVehicleFrame()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateState(new: NavigationState) {
        _state.value = new
    }

    /** Haversine distance in metres between two lat/lon points. */
    private fun distanceTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).let { it * it }
        return r * 2.0 * asin(sqrt(a))
    }

    /** Find the next upcoming maneuver based on distance from current position. */
    private fun findNextManeuver(lat: Double, lon: Double, route: Route): Maneuver? {
        // Traverse the polyline to find roughly where we are, then return next step
        if (route.maneuvers.isEmpty()) return null
        var closestDist = Double.MAX_VALUE
        var closestIdx = 0
        route.polyline.forEachIndexed { i, pt ->
            val d = distanceTo(lat, lon, pt.lat, pt.lon)
            if (d < closestDist) { closestDist = d; closestIdx = i }
        }
        // Estimate how far along the route we are (fraction)
        val fraction = closestIdx.toDouble() / route.polyline.size.toDouble()
        val maneuverIdx = min(
            (fraction * route.maneuvers.size).toInt(),
            route.maneuvers.size - 1
        )
        return route.maneuvers[maneuverIdx]
    }
}
