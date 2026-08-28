package com.percorsa.sensorlogger

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Central navigation state machine.
 */
class NavigationController(private val context: Context) {

    // ── Navigation engine ─────────────────────────────────────────────────────
    private val drEngine: DeadReckoningProvider = SimplifiedInsProvider()

    // ── Supporting components ─────────────────────────────────────────────────
    val sensorEngine = SensorEngine(context)
    private val gnssMonitor = GnssQualityMonitor()
    val preferencesRepo = PreferencesRepository(context)
    private val offRouteDetector = OffRouteDetector()

    // Search and routing interfaces
    private val searchService: SearchService = NominatimSearchService()
    private val routingService: RoutingService = OsrmRoutingService()

    // ── State ─────────────────────────────────────────────────────────────────
    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var searchJob: Job? = null
    private var routeJob: Job? = null
    private var rerouteJob: Job? = null

    private var lastUpdateMs: Long = 0L
    private val ARRIVAL_RADIUS_M = 40.0
    private val GNSS_BLEND_SECONDS = 3.0

    init {
        // Load initial persisted searches/places
        _state.value = _state.value.copy(
            recentSearches = preferencesRepo.getRecentSearches(),
            homePlace = preferencesRepo.getHomePlace(),
            workPlace = preferencesRepo.getWorkPlace()
        )
    }

    fun start() {
        sensorEngine.start()
        lastUpdateMs = System.currentTimeMillis()
    }

    fun stop() {
        sensorEngine.stop()
        searchJob?.cancel()
        routeJob?.cancel()
        rerouteJob?.cancel()
    }

    fun tick() {
        val nowMs = System.currentTimeMillis()
        val dtSeconds = if (lastUpdateMs > 0L)
            ((nowMs - lastUpdateMs) / 1000.0).coerceIn(0.005, 0.5)
        else 0.1
        lastUpdateMs = nowMs

        val snap = sensorEngine.getSnapshot()
        val gnssQuality = gnssMonitor.update(snap)
        val current = _state.value

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

        drEngine.update(snap, dtSeconds)
        val drPos = drEngine.getEstimatedPosition()

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
                updateState(_state.value.copy(
                    gnssQuality = gnssQuality,
                    isRecording = sensorEngine.isRecording,
                    recordedSamples = snap.loggedCsvRows,
                    navigationHealth = computeHealth(snap, gnssQuality)
                ))
                return
            }
        }

        val currentMode = current.navMode
        val isDrivingMode = currentMode == NavMode.NAVIGATING ||
                currentMode == NavMode.GNSS_DEGRADED ||
                currentMode == NavMode.GNSS_DENIED

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
            NavMode.ARRIVED -> NavMode.ARRIVED
            else -> currentMode
        }

        val route = current.route
        var distRemaining = current.distanceRemainingM
        var etaSec = current.etaSeconds
        var nextManeuver: Maneuver? = current.nextManeuver
        var secondManeuver: Maneuver? = current.secondManeuver
        var isOffRoute = false
        var isRecalculating = current.recalculating

        if (route != null && isDrivingMode) {
            distRemaining = distanceTo(lat, lon,
                current.destination!!.location.lat,
                current.destination.location.lon)
            val avgSpeedMps = if (speed > 0.5f) speed.toDouble() else 8.33
            etaSec = (distRemaining / avgSpeedMps).toLong()

            val pair = findNextManeuvers(lat, lon, route)
            nextManeuver = pair.first
            secondManeuver = pair.second

            // Check off-route
            val offRouteState = offRouteDetector.checkPosition(lat, lon, accuracy, speed, route)
            if (offRouteState == OffRouteState.OFF_ROUTE && !isRecalculating) {
                isOffRoute = true
                triggerReroute(LatLon(lat, lon), current.destination!!.location)
            } else if (offRouteState == OffRouteState.RECALCULATING) {
                isRecalculating = true
            }
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
            secondManeuver = secondManeuver,
            offRoute = isOffRoute,
            recalculating = isRecalculating,
            isRecording = sensorEngine.isRecording,
            recordedSamples = snap.loggedCsvRows,
            navigationHealth = computeHealth(snap, gnssQuality)
        ))
    }

    private fun triggerReroute(origin: LatLon, destination: LatLon) {
        offRouteDetector.markRecalculating()
        updateState(_state.value.copy(recalculating = true))
        rerouteJob?.cancel()
        rerouteJob = coroutineScope.launch {
            try {
                val newRoute = routingService.getRoute(origin, destination)
                if (newRoute != null) {
                    offRouteDetector.reset()
                    updateState(_state.value.copy(
                        route = newRoute,
                        recalculating = false,
                        offRoute = false,
                        distanceRemainingM = newRoute.distanceM,
                        etaSeconds = newRoute.durationSeconds,
                        nextManeuver = newRoute.maneuvers.firstOrNull()
                    ))
                } else {
                    updateState(_state.value.copy(recalculating = false))
                }
            } catch (e: Exception) {
                updateState(_state.value.copy(recalculating = false))
            }
        }
    }

    fun startNavigation(destination: GeocodingResult) {
        preferencesRepo.addRecentSearch(destination)
        val origin = LatLon(_state.value.latitude, _state.value.longitude)
        if (origin.lat == 0.0 && origin.lon == 0.0) {
            updateState(_state.value.copy(
                navMode = NavMode.ERROR,
                errorMessage = "Waiting for location before calculating route"
            ))
            return
        }
        updateState(_state.value.copy(
            destination = destination,
            navMode = NavMode.ROUTE_PREVIEW,
            routeLoading = true,
            routeError = null,
            route = null,
            recentSearches = preferencesRepo.getRecentSearches()
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
                    val pair = findNextManeuvers(origin.lat, origin.lon, route)
                    updateState(_state.value.copy(
                        route = route,
                        routeLoading = false,
                        distanceRemainingM = route.distanceM,
                        etaSeconds = route.durationSeconds,
                        nextManeuver = pair.first,
                        secondManeuver = pair.second
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
            offRouteDetector.reset()
            updateState(_state.value.copy(navMode = NavMode.NAVIGATING, offRoute = false, recalculating = false))
        }
    }

    fun stopNavigation() {
        drEngine.reset()
        gnssMonitor.reset()
        offRouteDetector.reset()
        updateState(NavigationState(
            latitude = _state.value.latitude,
            longitude = _state.value.longitude,
            heading = _state.value.heading,
            speed = _state.value.speed,
            gnssQuality = _state.value.gnssQuality,
            isRecording = _state.value.isRecording,
            recordedSamples = _state.value.recordedSamples,
            recentSearches = preferencesRepo.getRecentSearches(),
            homePlace = preferencesRepo.getHomePlace(),
            workPlace = preferencesRepo.getWorkPlace()
        ))
    }

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
                    searchError = if (results.isEmpty()) "No places found for '$query'" else null
                ))
            } catch (e: SearchException) {
                updateState(_state.value.copy(
                    searchLoading = false,
                    searchError = "Search unavailable — check network connection"
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

    private fun updateState(new: NavigationState) {
        _state.value = new
    }

    private fun distanceTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).let { it * it }
        return r * 2.0 * asin(sqrt(a))
    }

    private fun findNextManeuvers(lat: Double, lon: Double, route: Route): Pair<Maneuver?, Maneuver?> {
        if (route.maneuvers.isEmpty()) return Pair(null, null)
        var closestDist = Double.MAX_VALUE
        var closestIdx = 0
        route.polyline.forEachIndexed { i, pt ->
            val d = distanceTo(lat, lon, pt.lat, pt.lon)
            if (d < closestDist) { closestDist = d; closestIdx = i }
        }
        val fraction = closestIdx.toDouble() / route.polyline.size.toDouble()
        val mIdx = min((fraction * route.maneuvers.size).toInt(), route.maneuvers.size - 1)
        val m1 = route.maneuvers.getOrNull(mIdx)
        val m2 = route.maneuvers.getOrNull(mIdx + 1)
        return Pair(m1, m2)
    }

    private fun computeHealth(snap: SensorSnapshot, gnssQuality: GnssQuality): NavigationHealth {
        val gStatus = when (gnssQuality) {
            GnssQuality.GOOD -> HealthStatus.GOOD
            GnssQuality.FAIR -> HealthStatus.FAIR
            GnssQuality.POOR -> HealthStatus.DEGRADED
            else -> HealthStatus.FAILED
        }
        val imuStatus = if (snap.imuHz > 50) HealthStatus.GOOD else HealthStatus.DEGRADED
        return NavigationHealth(
            gnssHealth = gStatus,
            accelHealth = imuStatus,
            gyroHealth = imuStatus,
            rotationVectorHealth = imuStatus,
            filterHealth = HealthStatus.GOOD,
            tcnHealth = HealthStatus.GOOD,
            routeHealth = if (_state.value.offRoute) HealthStatus.DEGRADED else HealthStatus.GOOD,
            details = "IMU: %.0fHz | GPS Acc: %.1fm".format(snap.imuHz, snap.gpsAccuracyM)
        )
    }
}
