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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private fun EskfProviderDiagnostics.asInsDiagnostics(): InsDiagnostics = InsDiagnostics(
    timestampNs = lastPropagationTimestampNs,
    dtSeconds = lastDtSeconds,
    velocityAfterMps = speedMps.toFloat(),
    tcnSpeedMps = Float.NaN,
    tcnSpeedInjected = lastTcnInjected,
    vehicleMotionObserved = vehicleMotionObserved,
    vehicleMotionEvidence = if (vehicleMotionObserved) {
        "OBSERVED: trusted GNSS speed ≥ 4.0 m/s and accuracy ≤ 15 m"
    } else {
        "WAITING: trusted GNSS speed ≥ 4.0 m/s and accuracy ≤ 15 m"
    },
    positionAfter = LatLon(positionLatitude, positionLongitude)
)

/**
 * Central navigation state machine.
 */
class NavigationController(private val context: Context) {

    // ── Navigation engine ─────────────────────────────────────────────────────
    /** The single authoritative active navigation estimator. */
    private val drEngine: DeadReckoningProvider = PercorsaEskfProvider()
    private val activeEskf: PercorsaEskfProvider get() = drEngine as PercorsaEskfProvider
    private val activeEskfDiagnostics: EskfProviderDiagnostics get() = activeEskf.status

    // ── Supporting components ─────────────────────────────────────────────────
    val sensorEngine = SensorEngine(context)
    private val gnssMonitor = GnssQualityMonitor()
    val preferencesRepo = PreferencesRepository(context)
    private val offRouteDetector = OffRouteDetector()
    private val turnDetector = TurnDetector()

    // Search and routing interfaces
    private val searchService: SearchService = NominatimSearchService()
    private val routingService: RoutingService = OsrmRoutingService()

    // ── State ─────────────────────────────────────────────────────────────────
    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()
    val insDiagnostics: InsDiagnostics
        get() = activeEskfDiagnostics.asInsDiagnostics()
    val tcnSpeedInjected: Boolean
        get() = insDiagnostics.tcnSpeedInjected
    val eskfDiagnostics: EskfProviderDiagnostics
        get() = activeEskf.status

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var searchJob: Job? = null
    private var routeJob: Job? = null
    private var rerouteJob: Job? = null
    private var lastRouteDistanceAlongM = 0.0

    private var lastSensorTimestampNs: Long = 0L
    private val ARRIVAL_RADIUS_M = 40.0
    private val GNSS_BLEND_SECONDS = 3.0
    private val MAX_MONOTONIC_DT_SECONDS = 0.5

    init {
        sensorEngine.setEstimatedSpeedProviderForDiagnostics {
            activeEskfDiagnostics.speedMps.toFloat()
        }
        sensorEngine.setNavigationDiagnosticsProvider {
            val snap = sensorEngine.getSnapshot()
            val stateNow = _state.value
            val active = drEngine.getEstimatedPosition()
            val eskf = eskfDiagnostics
            CsvNavigationDiagnostics(
                activeProvider = if (stateNow.drProvider == DrProviderType.NONE) null else stateNow.drProvider.name,
                activeLatitude = active?.latitude ?: Double.NaN,
                activeLongitude = active?.longitude ?: Double.NaN,
                activeVelocityMps = active?.speedMps ?: Float.NaN,
                activeSpeedMps = active?.speedMps ?: Float.NaN,
                activeHeadingDeg = active?.heading ?: Float.NaN,
                tcnCanonicalTimestampNs = snap.lastCanonicalSample?.timestampNs ?: 0L,
                tcnInferenceActive = snap.tcnInferenceActive,
                tcnRawSpeedMps = snap.tcnRawSpeedMps,
                tcnFilteredSpeedMps = snap.tcnPredictedSpeedMps,
                tcnPredictionRateLimited = snap.tcnPredictionRateLimited,
                tcnRejectedPredictionCount = snap.tcnRejectedPredictionCount,
                vehicleMotionObserved = eskf.vehicleMotionObserved,
                tcnInjectedIntoIns = eskf.lastTcnInjected,
                tcnAcceptedByEskf = eskf.lastTcnAccepted,
                tcnNis = eskf.lastTcnNis,
                tcnRejectionReason = eskf.lastTcnRejectionReason,
                eskfInitialized = eskf.initialized,
                eskfValid = eskf.valid,
                eskfRuntimeState = eskf.runtimeState.name,
                eskfDegradationReason = eskf.degradationReason,
                eskfCalibrationActive = eskf.calibrationActive,
                eskfTimestampNs = eskf.lastPropagationTimestampNs,
                eskfDtSeconds = eskf.lastDtSeconds,
                eskfPositionLatitude = eskf.positionLatitude,
                eskfPositionLongitude = eskf.positionLongitude,
                eskfPositionEastM = eskf.positionWorldEnu.getOrNull(0) ?: Double.NaN,
                eskfPositionNorthM = eskf.positionWorldEnu.getOrNull(1) ?: Double.NaN,
                eskfPositionUpM = eskf.positionWorldEnu.getOrNull(2) ?: Double.NaN,
                eskfVelocityEastMps = eskf.velocityWorldEnu.getOrNull(0) ?: Double.NaN,
                eskfVelocityNorthMps = eskf.velocityWorldEnu.getOrNull(1) ?: Double.NaN,
                eskfVelocityUpMps = eskf.velocityWorldEnu.getOrNull(2) ?: Double.NaN,
                eskfSpeedMps = eskf.speedMps,
                eskfHeadingDeg = eskf.headingDeg,
                eskfQuaternionW = eskf.quaternionW,
                eskfQuaternionX = eskf.quaternionX,
                eskfQuaternionY = eskf.quaternionY,
                eskfQuaternionZ = eskf.quaternionZ,
                eskfQuaternionNorm = eskf.quaternionNorm,
                eskfCovarianceTrace = eskf.covarianceTrace,
                eskfStateFinite = eskf.stateFinite,
                eskfCovarianceFinite = eskf.covarianceFinite,
                eskfCovariancePsd = eskf.covariancePsd,
                eskfGnssAccepted = eskf.lastGnssAccepted,
                eskfGnssNis = eskf.lastGnssNis,
                eskfGnssInnovationM = eskf.lastGnssInnovationMagnitudeM,
                eskfStationary = eskf.stationary,
                eskfNhcEnabled = eskf.nhcEnabled,
                eskfNhcAccepted = eskf.lastNhcAccepted,
                eskfNhcNis = eskf.lastNhcNis,
                eskfZuptEnabled = eskf.zuptEnabled,
                eskfZuptAccepted = eskf.lastZuptAccepted,
                routeSegmentIndex = stateNow.routeSegmentIndex.takeIf { it >= 0 },
                routeProgressM = stateNow.routeProgressM,
                routeLateralErrorM = stateNow.routeLateralErrorM,
                routeHeadingErrorDeg = stateNow.routeHeadingErrorDeg,
                turnState = stateNow.turnState.name,
                turnYawRateDegS = stateNow.turnYawRateDegS,
                offRoute = stateNow.offRoute,
                rerouting = stateNow.recalculating
            )
        }
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
        lastSensorTimestampNs = 0L
    }

    fun stop() {
        sensorEngine.stop()
        searchJob?.cancel()
        routeJob?.cancel()
        rerouteJob?.cancel()
    }

    fun tick() {
        val snap = sensorEngine.getSnapshot()
        val dtSeconds = nextMonotonicDtSeconds(snap.timestampNs)
        val gnssQuality = gnssMonitor.update(snap)
        val current = _state.value

        val hasTrustedGnss = gnssMonitor.shouldUseMeasurement() && snap.hasGps &&
                snap.latitude.isFinite() && snap.longitude.isFinite()
        if (snap.hasRotVector && snap.quatNorm.isFinite() && snap.quatNorm in 0.95f..1.05f) {
            activeEskf.setInitialOrientation(
                EskfQuaternion(
                    snap.quatW.toDouble(), snap.quatX.toDouble(),
                    snap.quatY.toDouble(), snap.quatZ.toDouble()
                )
            )
        }
        sensorEngine.getPhoneToVehicleRotation()?.let(activeEskf::setPhoneToVehicleRotation)
        if (hasTrustedGnss) {
            val blendWindow = if (gnssMonitor.isGnssDenied()) 0.0 else GNSS_BLEND_SECONDS
            drEngine.injectGnssCorrection(
                lat = snap.latitude,
                lon = snap.longitude,
                accuracyM = snap.gpsAccuracyM,
                speedMps = snap.gpsSpeedMps,
                bearingDeg = snap.gpsBearingDeg,
                blendWindowSeconds = blendWindow,
                sourceTimestampNs = snap.gpsTimestampNs
            )
        } else if (shouldInjectTcnSpeed(
                hasTrustedGnss,
                snap.tcnInferenceActive,
                drEngine.acceptsTcnSpeedEstimate
            )) {
            drEngine.injectSpeedEstimate(
                snap.tcnPredictedSpeedMps,
                snap.lastCanonicalSample?.timestampNs ?: 0L
            )
        }

        dtSeconds?.let { drEngine.update(snap, it) }
        val drPos = drEngine.getEstimatedPosition()
        // Diagnostics only: preserve the raw active DR estimate separately
        // from the display speed, which may intentionally prefer trusted GPS.
        val diagnosticSpeed = drPos?.speedMps ?: Float.NaN
        sensorEngine.setEstimatedSpeedForDiagnostics(diagnosticSpeed)

        val isGnssUnavailable = gnssMonitor.isGnssDenied() || gnssQuality == GnssQuality.DENIED || gnssQuality == GnssQuality.POOR

        val eskfDiag = activeEskfDiagnostics
        val eskfHealth = when {
            !activeEskf.isInitialized -> EskfHealthState.UNINITIALIZED
            !eskfDiag.valid || eskfDiag.runtimeState == EskfRuntimeState.INVALID -> EskfHealthState.DIVERGED
            eskfDiag.isHealthy -> EskfHealthState.HEALTHY
            else -> EskfHealthState.DEGRADED
        }
        val rawEskfSpeed = if (eskfDiag.speedMps.isFinite()) eskfDiag.speedMps.toFloat() else Float.NaN
        val eskfHealthReason = eskfDiag.healthReason

        // SPEED POLICY:
        // 1. When trusted GNSS is available, GNSS Doppler speed is the primary ground truth.
        // 2. When GNSS is degraded or denied, use ESKF dead reckoning ONLY IF strictly HEALTHY.
        // 3. If ESKF is DEGRADED, DIVERGED, or INVALID, NEVER display it. Explicit fallback to 0.
        val (speed, speedSource) = when {
            hasTrustedGnss && snap.gpsSpeedMps.isFinite() && snap.gpsSpeedMps >= 0f -> {
                Pair(snap.gpsSpeedMps, SpeedSource.GNSS)
            }
            drPos != null && drPos.speedMps.isFinite() && drPos.speedMps >= 0f && eskfHealth == EskfHealthState.HEALTHY -> {
                Pair(drPos.speedMps, SpeedSource.ESKF)
            }
            else -> {
                val fallback = if (hasTrustedGnss && snap.gpsSpeedMps.isFinite() && snap.gpsSpeedMps >= 0f) snap.gpsSpeedMps else 0f
                Pair(fallback, SpeedSource.FALLBACK)
            }
        }

        // POSITION POLICY:
        val lat: Double
        val lon: Double
        val accuracy: Float
        val drActive: Boolean

        when {
            drPos != null && isGnssUnavailable -> {
                lat = drPos.latitude
                lon = drPos.longitude
                accuracy = drPos.estimatedAccuracyM
                drActive = true
            }
            hasTrustedGnss -> {
                lat = drPos?.latitude ?: snap.latitude
                lon = drPos?.longitude ?: snap.longitude
                accuracy = snap.gpsAccuracyM
                drActive = false
            }
            drPos != null -> {
                lat = drPos.latitude
                lon = drPos.longitude
                accuracy = drPos.estimatedAccuracyM
                drActive = false
            }
            snap.latitude != 0.0 || snap.longitude != 0.0 -> {
                lat = snap.latitude
                lon = snap.longitude
                accuracy = snap.gpsAccuracyM
                drActive = false
            }
            else -> {
                updateState(_state.value.copy(
                    compassBearingDeg = snap.compassBearingDeg,
                    deviceAzimuthDeg = snap.deviceAzimuthDeg,
                    rotationSource = snap.rotationSource,
                    deviceHeadingConfidence = snap.deviceHeadingConfidence,
                    gnssQuality = gnssQuality,
                    speedSource = speedSource,
                    eskfHealthState = eskfHealth,
                    eskfHealthReason = eskfHealthReason,
                    eskfRawSpeedMps = rawEskfSpeed,
                    isRecording = sensorEngine.isRecording,
                    recordedSamples = snap.loggedCsvRows,
                    navigationHealth = computeHealth(snap, gnssQuality)
                ))
                return
            }
        }

        // ORIENTATION SIGNALS:
        // A. Device azimuth = physical direction phone is pointing (controls map pointer)
        val deviceAzimuth = snap.deviceAzimuthDeg
        // B. Vehicle heading = direction vehicle is moving/traveling
        val vehicleHeading = when {
            drPos != null && drPos.heading.isFinite() -> drPos.heading
            snap.hasGps && snap.gpsBearingDeg.isFinite() && (snap.gpsSpeedMps.takeIf { it.isFinite() } ?: 0f) >= 1.5f -> snap.gpsBearingDeg
            else -> deviceAzimuth
        }
        val heading = vehicleHeading

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
        var routeSegmentIndex = current.routeSegmentIndex
        var routeProgressM = current.routeProgressM
        var routeLateralErrorM = current.routeLateralErrorM
        var routeHeadingErrorDeg = current.routeHeadingErrorDeg
        var turnState = current.turnState
        var turnYawRateDegS = current.turnYawRateDegS
        var routeBearingDeg: Double = current.routeBearingDeg

        if (route != null && isDrivingMode) {
            // 1. Segment projection gives cumulative route progress, not vertex distance.
            val routeMatch = RouteGeometry.project(LatLon(lat, lon), route.polyline)
            // Let the continuity-aware detector reject a topology jump before
            // committing route progress.
            val offRouteState = offRouteDetector.checkPosition(lat, lon, accuracy, speed, heading, route)
            val matchedRoute = offRouteDetector.routeMatch ?: routeMatch
            routeBearingDeg = matchedRoute?.routeBearingDeg ?: Double.NaN
            val routeDistanceAlongM = matchedRoute?.let {
                max(lastRouteDistanceAlongM, it.distanceAlongM)
            } ?: lastRouteDistanceAlongM
            lastRouteDistanceAlongM = routeDistanceAlongM.coerceIn(0.0, route.distanceM)
            distRemaining = (route.distanceM - lastRouteDistanceAlongM).coerceAtLeast(0.0)

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

            val pair = findNextManeuvers(lastRouteDistanceAlongM, route)
            nextManeuver = pair.first
            secondManeuver = pair.second

            // Apply the route decision using the same association exposed in
            // the route diagnostics above.
            if (matchedRoute != null) {
                routeSegmentIndex = matchedRoute.segmentIndex
                routeProgressM = lastRouteDistanceAlongM
                routeLateralErrorM = matchedRoute.lateralDistanceM
                routeHeadingErrorDeg = TurnDetector.signedBearingDelta(matchedRoute.routeBearingDeg, heading.toDouble())
                turnYawRateDegS = activeEskfDiagnostics.yawRateDegS
                turnState = turnDetector.update(
                    nextManeuver,
                    heading,
                    matchedRoute.routeBearingDeg,
                    turnYawRateDegS,
                    speed
                )
            } else {
                routeSegmentIndex = -1
                routeProgressM = lastRouteDistanceAlongM
                routeLateralErrorM = Double.NaN
                routeHeadingErrorDeg = Double.NaN
                turnYawRateDegS = Float.NaN
                turnState = TurnState.STRAIGHT
            }
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
            deviceAzimuthDeg = deviceAzimuth,
            rotationSource = snap.rotationSource,
            deviceHeadingConfidence = snap.deviceHeadingConfidence,
            vehicleHeadingDeg = vehicleHeading,
            routeBearingDeg = routeBearingDeg,
            speedSource = speedSource,
            eskfHealthState = eskfHealth,
            eskfHealthReason = eskfHealthReason,
            eskfRawSpeedMps = rawEskfSpeed,
            compassBearingDeg = snap.compassBearingDeg,
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
            routeSegmentIndex = routeSegmentIndex,
            routeProgressM = routeProgressM,
            routeLateralErrorM = routeLateralErrorM,
            routeHeadingErrorDeg = routeHeadingErrorDeg,
            turnState = turnState,
            turnYawRateDegS = turnYawRateDegS,
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
                        routeSegmentIndex = -1,
                        routeProgressM = 0.0,
                        routeLateralErrorM = Double.NaN,
                        routeHeadingErrorDeg = Double.NaN,
                        turnState = TurnState.STRAIGHT,
                        turnYawRateDegS = Float.NaN,
                        routeError = null
                    ))
                    lastRouteDistanceAlongM = 0.0
                    turnDetector.reset()
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
        lastRouteDistanceAlongM = 0.0
        turnDetector.reset()
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
                    lastRouteDistanceAlongM = 0.0
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
            lastRouteDistanceAlongM = 0.0
            turnDetector.reset()
            updateState(_state.value.copy(navMode = NavMode.NAVIGATING, offRoute = false, recalculating = false))
        }
    }

    fun stopNavigation() {
        drEngine.reset()
        gnssMonitor.reset()
        offRouteDetector.reset()
        lastRouteDistanceAlongM = 0.0
        turnDetector.reset()
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

    companion object {
        internal fun monotonicDtSeconds(
            previousTimestampNs: Long,
            currentTimestampNs: Long,
            maxDtSeconds: Double = 0.5
        ): Double? {
            if (currentTimestampNs <= 0L || previousTimestampNs <= 0L) return null
            val deltaNs = currentTimestampNs - previousTimestampNs
            if (deltaNs <= 0L) return null
            val dtSeconds = deltaNs / 1_000_000_000.0
            return dtSeconds.takeIf { it.isFinite() && it <= maxDtSeconds }
        }

        internal fun shouldInjectTcnSpeed(
            hasTrustedGnss: Boolean,
            tcnInferenceActive: Boolean,
            acceptsTcnSpeedEstimate: Boolean
        ): Boolean = !hasTrustedGnss && tcnInferenceActive && acceptsTcnSpeedEstimate
    }

    private fun nextMonotonicDtSeconds(currentTimestampNs: Long): Double? {
        if (currentTimestampNs <= 0L) return null
        val previousTimestampNs = lastSensorTimestampNs
        if (previousTimestampNs == 0L) {
            lastSensorTimestampNs = currentTimestampNs
            return null
        }

        val dtSeconds = monotonicDtSeconds(
            previousTimestampNs,
            currentTimestampNs,
            MAX_MONOTONIC_DT_SECONDS
        )
        if (dtSeconds != null) {
            lastSensorTimestampNs = currentTimestampNs
        } else if (currentTimestampNs > previousTimestampNs &&
            currentTimestampNs - previousTimestampNs >
            (MAX_MONOTONIC_DT_SECONDS * 1_000_000_000L).toLong()
        ) {
            // Drop the interval and re-baseline. No synthetic dt is supplied.
            lastSensorTimestampNs = currentTimestampNs
            drEngine.rebaselineSensorTimestamp(currentTimestampNs)
        }
        return dtSeconds
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
        val indexed = route.maneuvers.indexOfFirst {
            !it.distanceAlongM.isNaN() && it.distanceAlongM > distanceAlongM + 1.0
        }
        val mIdx = if (indexed >= 0) indexed else {
            // Preserve a useful fallback for route providers that do not
            // expose cumulative step distances.
            val fraction = (distanceAlongM / route.distanceM.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
            min((fraction * route.maneuvers.size).toInt(), route.maneuvers.size - 1)
        }
        val m1 = route.maneuvers.getOrNull(mIdx)?.let { maneuver ->
            if (maneuver.distanceAlongM.isNaN()) maneuver
            else maneuver.copy(distanceM = (maneuver.distanceAlongM - distanceAlongM).coerceAtLeast(0.0))
        }
        val m2 = route.maneuvers.getOrNull(mIdx + 1)?.let { maneuver ->
            if (maneuver.distanceAlongM.isNaN()) maneuver
            else maneuver.copy(distanceM = (maneuver.distanceAlongM - distanceAlongM).coerceAtLeast(0.0))
        }
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
                snap.imuHz, snap.gpsAccuracyM, snap.gpsFixAgeMs,
                if (snap.tcnInferenceActive) "%.2fm/s".format(snap.tcnPredictedSpeedMps) else "inactive"
            )
        )
    }

}
