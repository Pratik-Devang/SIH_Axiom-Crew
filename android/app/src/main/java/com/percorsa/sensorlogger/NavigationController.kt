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

    private var smoothedSpeedMps: Double = 0.0
    private var smoothedEtaSec: Double = 0.0

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
        val usingMlSpeed = !hasTrustedGnss &&
                snap.tcnInferenceActive &&
                drEngine.acceptsTcnSpeedEstimate
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
        } else if (usingMlSpeed) {
            drEngine.injectSpeedEstimate(snap.tcnPredictedSpeedMps)
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
                drActive = (gnssQuality == GnssQuality.DENIED || gnssQuality == GnssQuality.RECOVERING)
            }
            snap.hasGps && snap.latitude != 0.0 -> {
                lat = snap.latitude
                lon = snap.longitude
                heading = snap.gpsBearingDeg
                speed = snap.gpsSpeedMps
                accuracy = snap.gpsAccuracyM
                drActive = false
            }
            snap.latitude != 0.0 || snap.longitude != 0.0 -> {
                // Keep the last known point visible during a GPS outage while
                // the rotation-vector compass continues to update its heading.
                lat = snap.latitude
                lon = snap.longitude
                heading = snap.compassBearingDeg
                speed = 0f
                accuracy = snap.gpsAccuracyM
                drActive = true
            }
            else -> {
                updateState(_state.value.copy(
                    compassBearingDeg = snap.compassBearingDeg,
                    gnssQuality = gnssQuality,
                    isRecording = sensorEngine.isRecording,
                    recordedSamples = snap.loggedCsvRows,
                    mlModelLoaded = snap.tcnModelLoaded,
                    mlBufferReady = snap.tcnBufferReady,
                    mlInferenceActive = usingMlSpeed,
                    mlSpeedMps = if (usingMlSpeed) snap.tcnPredictedSpeedMps else 0f,
                    mlLatencyMs = snap.tcnInferenceLatencyMs,
                    mlError = snap.tcnInferenceError,
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
        var isOffRoute = current.offRoute
        var isRecalculating = current.recalculating

        if (route != null && isDrivingMode) {
            // 1. Segment projection gives cumulative route progress, not vertex distance.
            val routeMatch = RouteGeometry.project(LatLon(lat, lon), route.polyline)
            distRemaining = routeMatch?.let {
                (route.distanceM - it.distanceAlongM).coerceAtLeast(0.0)
            } ?: route.distanceM

            // 2. Exponentially smoothed speed to prevent jitter
            smoothedSpeedMps = 0.05 * speed.toDouble() + 0.95 * smoothedSpeedMps

            // 3. Robust ETA calculation: route baseline + EMA speed blending
            val routeProgressRatio = (distRemaining / route.distanceM.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
            val routeBaselineEtaS = route.durationSeconds * routeProgressRatio

            val targetEtaS = if (smoothedSpeedMps > 3.0) {
                // If moving steadily (>10 km/h), blend 70% route baseline + 30% instantaneous speed ETA
                val speedBasedEtaS = distRemaining / smoothedSpeedMps
                0.7 * routeBaselineEtaS + 0.3 * speedBasedEtaS
            } else {
                routeBaselineEtaS
            }

            if (smoothedEtaSec <= 0.0) {
                smoothedEtaSec = targetEtaS
            } else {
                // Smooth ETA transitions slowly (alpha = 0.03) so ETA never jumps abruptly
                smoothedEtaSec = 0.03 * targetEtaS + 0.97 * smoothedEtaSec
            }
            etaSec = smoothedEtaSec.toLong().coerceAtLeast(0L)

            val pair = findNextManeuvers(routeMatch?.distanceAlongM ?: 0.0, route)
            nextManeuver = pair.first
            secondManeuver = pair.second

            // Check off-route
            val offRouteState = offRouteDetector.checkPosition(lat, lon, accuracy, speed, heading, route)
            if (offRouteState == OffRouteState.OFF_ROUTE && !isRecalculating) {
                isOffRoute = true
                isRecalculating = true
                current.destination?.location?.let { triggerReroute(LatLon(lat, lon), it) }
            } else if (offRouteState == OffRouteState.RECALCULATING) {
                isRecalculating = true
            } else if (offRouteState == OffRouteState.ON_ROUTE && !isRecalculating) {
                isOffRoute = false
            }
        }

        updateState(current.copy(
            latitude = lat,
            longitude = lon,
            heading = heading,
            compassBearingDeg = snap.compassBearingDeg,
            speed = speed,
            positionAccuracy = accuracy,
            navMode = newMode,
            gnssQuality = gnssQuality,
            drActive = drActive || usingMlSpeed,
            drProvider = if (drActive || usingMlSpeed) drEngine.providerType else DrProviderType.NONE,
            mlModelLoaded = snap.tcnModelLoaded,
            mlBufferReady = snap.tcnBufferReady,
            mlInferenceActive = usingMlSpeed,
            mlSpeedMps = if (usingMlSpeed) snap.tcnPredictedSpeedMps else 0f,
            mlLatencyMs = snap.tcnInferenceLatencyMs,
            mlError = snap.tcnInferenceError,
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
        updateState(_state.value.copy(recalculating = true, routeError = null))
        rerouteJob?.cancel()
        rerouteJob = coroutineScope.launch {
            try {
                val newRoute = routingService.getRoute(origin, destination)
                if (newRoute != null) {
                    offRouteDetector.reset()
                    smoothedSpeedMps = 0.0
                    smoothedEtaSec = newRoute.durationSeconds.toDouble()
                    val pair = findNextManeuvers(0.0, newRoute)
                    updateState(_state.value.copy(
                        route = newRoute,
                        recalculating = false,
                        offRoute = false,
                        distanceRemainingM = newRoute.distanceM,
                        etaSeconds = newRoute.durationSeconds,
                        nextManeuver = pair.first,
                        secondManeuver = pair.second,
                        routeError = null
                    ))
                } else {
                    updateState(_state.value.copy(
                        recalculating = false,
                        routeError = "Unable to recalculate route"
                    ))
                }
            } catch (e: Exception) {
                updateState(_state.value.copy(
                    recalculating = false,
                    routeError = "Unable to recalculate route"
                ))
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
                    val pair = findNextManeuvers(0.0, route)
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
            mlModelLoaded = _state.value.mlModelLoaded,
            mlBufferReady = _state.value.mlBufferReady,
            mlInferenceActive = _state.value.mlInferenceActive,
            mlSpeedMps = _state.value.mlSpeedMps,
            mlLatencyMs = _state.value.mlLatencyMs,
            mlError = _state.value.mlError,
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

    fun searchNearby(category: String) {
        val near = _state.value.let {
            if (it.hasValidPosition) LatLon(it.latitude, it.longitude) else null
        }
        if (near == null) {
            search(category)
            return
        }

        searchJob?.cancel()
        updateState(_state.value.copy(
            navMode = NavMode.SEARCHING,
            searchLoading = true,
            searchResults = emptyList(),
            searchError = null
        ))
        searchJob = coroutineScope.launch {
            try {
                val results = searchService.searchNearby(category, near)
                updateState(_state.value.copy(
                    searchResults = results,
                    searchLoading = false,
                    searchError = if (results.isEmpty()) "No nearby $category places found" else null
                ))
            } catch (e: SearchException) {
                updateState(_state.value.copy(
                    searchLoading = false,
                    searchError = "Nearby search unavailable - check network connection"
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

    private fun findNextManeuvers(distanceAlongM: Double, route: Route): Pair<Maneuver?, Maneuver?> {
        if (route.maneuvers.isEmpty()) return Pair(null, null)
        val fraction = (distanceAlongM / route.distanceM.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
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
        val tcnStatus = when {
            snap.tcnInferenceActive -> HealthStatus.GOOD
            snap.tcnInferenceError != null -> HealthStatus.FAILED
            snap.tcnModelLoaded && snap.tcnBufferReady -> HealthStatus.DEGRADED
            else -> HealthStatus.UNKNOWN
        }
        return NavigationHealth(
            gnssHealth = gStatus,
            accelHealth = imuStatus,
            gyroHealth = imuStatus,
            rotationVectorHealth = imuStatus,
            filterHealth = HealthStatus.GOOD,
            tcnHealth = tcnStatus,
            routeHealth = if (_state.value.offRoute) HealthStatus.DEGRADED else HealthStatus.GOOD,
            details = "IMU: %.0fHz | GPS Acc: %.1fm | FixAge: %dms | TCN: %s".format(
                snap.imuHz,
                snap.gpsAccuracyM,
                snap.gpsFixAgeMs,
                if (snap.tcnInferenceActive) {
                    "%.2fm/s (%.2fms)".format(
                        snap.tcnPredictedSpeedMps,
                        snap.tcnInferenceLatencyMs
                    )
                } else {
                    "inactive"
                }
            )
        )
    }

}
