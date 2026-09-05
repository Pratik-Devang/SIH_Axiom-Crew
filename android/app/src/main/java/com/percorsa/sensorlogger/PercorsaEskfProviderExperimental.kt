package com.percorsa.sensorlogger

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class EskfProviderDiagnostics(
    val initialized: Boolean = false,
    val valid: Boolean = true,
    val lastPropagationTimestampNs: Long = 0L,
    val stateFinite: Boolean = false,
    val covarianceFinite: Boolean = false,
    val covariancePsd: Boolean = false,
    val lastGnssAccepted: Boolean? = null,
    val lastTcnAccepted: Boolean? = null,
    val lastNhcAccepted: Boolean? = null,
    val lastZuptAccepted: Boolean? = null,
    val lastNis: Double = Double.NaN,
    val lastDtSeconds: Double = 0.0,
    val covarianceTrace: Double = Double.NaN,
    val quaternionNorm: Double = Double.NaN,
    val positionWorldEnu: List<Double> = listOf(0.0, 0.0, 0.0),
    val velocityWorldEnu: List<Double> = listOf(0.0, 0.0, 0.0),
    val speedMps: Double = Double.NaN,
    val headingDeg: Double = Double.NaN,
    val positionLatitude: Double = Double.NaN,
    val positionLongitude: Double = Double.NaN,
    val lastGnssNis: Double = Double.NaN,
    val lastTcnNis: Double = Double.NaN,
    val lastGnssTimestampSeconds: Double = Double.NaN,
    val lastTcnTimestampSeconds: Double = Double.NaN,
    val lastGnssInnovationMagnitudeM: Double = Double.NaN,
    val vehicleMotionObserved: Boolean = false,
    val error: String? = null
)

/** Experimental standalone ESKF provider. It is intentionally not runtime-wired. */
class PercorsaEskfProvider(
    private val config: EskfConfig = EskfConfig(),
    private val phoneToVehicle: PhoneToVehicleRotation = defaultProviderPhoneToVehicle()
) : DeadReckoningProvider {
    override val providerType = DrProviderType.PERCORSA_ESKF
    private val propagator = EskfPropagator(config)
    private val covariancePropagator = EskfCovariancePropagator(config)
    private val gnssUpdater = EskfGnssUpdater(covariancePsdTolerance = config.covariancePsdTolerance)
    private val tcnUpdater = EskfTcnUpdater(covariancePsdTolerance = config.covariancePsdTolerance)
    private val nhcUpdater = EskfNhcUpdater(covariancePsdTolerance = config.covariancePsdTolerance)
    private val zuptUpdater = EskfZuptUpdater(covariancePsdTolerance = config.covariancePsdTolerance)

    private var state: EskfNominalState? = null
    private var covariance: EskfCovariance? = null
    private var lastTimestampNs = 0L
    private var pendingTcnSpeedMps: Float? = null
    private var vehicleMotionObserved = false
    private var origin: LatLon? = null
    @Volatile private var diagnostics = EskfProviderDiagnostics()
    private var shadowSuspended = false

    val status: EskfProviderDiagnostics get() = diagnostics
    val currentState: EskfNominalState? get() = state?.copyArrays()
    val currentCovariance: EskfCovariance? get() = covariance?.let { EskfCovariance.from(it.values) }
    val isInitialized: Boolean get() = state != null && diagnostics.valid
    val isSuspended: Boolean get() = shadowSuspended
    override val acceptsTcnSpeedEstimate: Boolean get() = isInitialized && vehicleMotionObserved

    fun initialize(
        positionWorldEnu: DoubleArray = DoubleArray(3),
        velocityWorldEnu: DoubleArray = DoubleArray(3),
        quaternionPhoneToWorld: EskfQuaternion = EskfQuaternion.IDENTITY,
        timestampNs: Long = 0L
    ) {
        require(timestampNs >= 0L) { "Initialization timestamp must be non-negative" }
        state = EskfNominalState(
            position = positionWorldEnu.copyOf(),
            velocity = velocityWorldEnu.copyOf(),
            quaternion = quaternionPhoneToWorld.normalized(),
            timestampSeconds = timestampNs / 1e9
        )
        covariance = config.initialCovariance()
        lastTimestampNs = timestampNs
        pendingTcnSpeedMps = null
        diagnostics = EskfProviderDiagnostics(initialized = true, valid = true, lastPropagationTimestampNs = timestampNs)
        shadowSuspended = false
        refreshDiagnostics()
    }

    fun setVehicleMotionObserved(observed: Boolean) {
        vehicleMotionObserved = observed
        if (!observed) pendingTcnSpeedMps = null
    }

    override fun update(snapshot: SensorSnapshot, dtSeconds: Double) {
        if (!isInitialized) return
        if (snapshot.timestampNs <= 0L) return invalidate("Invalid IMU timestamp")
        if (lastTimestampNs == 0L) { lastTimestampNs = snapshot.timestampNs; return }
        val deltaNs = snapshot.timestampNs - lastTimestampNs
        if (deltaNs <= 0L) return invalidate("Non-monotonic IMU timestamp")
        val dt = deltaNs / 1e9
        if (!dt.isFinite() || dt > config.maxPropagationDtSeconds) return invalidate("Invalid IMU timestamp delta")
        try {
            val sample = EskfImuSample(
                snapshot.timestampNs / 1e9,
                EskfVector3(snapshot.accelX.toDouble(), snapshot.accelY.toDouble(), snapshot.accelZ.toDouble()),
                EskfVector3(snapshot.gyroX.toDouble(), snapshot.gyroY.toDouble(), snapshot.gyroZ.toDouble()),
                snapshot.hasLinearAccel
            )
            val propagated = propagator.propagate(state!!, sample, dt)
            val propagatedCovariance = covariancePropagator.propagate(covariance!!, propagated, sample, dt).covariance
            state = propagated; covariance = propagatedCovariance; lastTimestampNs = snapshot.timestampNs
            diagnostics = diagnostics.copy(lastDtSeconds = dt)
            pendingTcnSpeedMps?.let { speed ->
                val result = tcnUpdater.update(propagated, propagatedCovariance, EskfTcnMeasurement(speed.toDouble(), timestampSeconds = propagated.timestampSeconds), phoneToVehicle, vehicleMotionObserved)
                diagnostics = diagnostics.copy(lastTcnAccepted = result.accepted, lastNis = result.nis, lastTcnNis = result.nis, lastTcnTimestampSeconds = propagated.timestampSeconds)
                if (result.accepted) { state = result.state; covariance = result.covariance }
                pendingTcnSpeedMps = null
            }
            refreshDiagnostics()
        } catch (error: IllegalArgumentException) { invalidate(error.message ?: "ESKF update failed") }
    }

    fun processTcn(speedMps: Double, motionObserved: Boolean): EskfTcnUpdateResult? {
        if (!isInitialized) return null
        vehicleMotionObserved = motionObserved
        val result = tcnUpdater.update(state!!, covariance!!, EskfTcnMeasurement(speedMps, timestampSeconds = state!!.timestampSeconds), phoneToVehicle, motionObserved)
        diagnostics = diagnostics.copy(lastTcnAccepted = result.accepted, lastNis = result.nis, lastTcnNis = result.nis, lastTcnTimestampSeconds = state!!.timestampSeconds)
        if (result.accepted) { state = result.state; covariance = result.covariance }
        refreshDiagnostics(); return result
    }

    fun processNhc(measurement: EskfNhcMeasurement = EskfNhcMeasurement(), enabled: Boolean): EskfConstraintUpdateResult? {
        if (!enabled || !isInitialized) return null
        val result = try { nhcUpdater.update(state!!, covariance!!, measurement, phoneToVehicle, true) } catch (_: IllegalArgumentException) { return null }
        diagnostics = diagnostics.copy(lastNhcAccepted = result.accepted, lastNis = result.nis)
        if (result.accepted) { state = result.state; covariance = result.covariance }
        refreshDiagnostics(); return result
    }

    fun processZupt(measurement: EskfZuptMeasurement = EskfZuptMeasurement(), enabled: Boolean): EskfConstraintUpdateResult? {
        if (!enabled || !isInitialized) return null
        val result = try { zuptUpdater.update(state!!, covariance!!, measurement, true) } catch (_: IllegalArgumentException) { return null }
        diagnostics = diagnostics.copy(lastZuptAccepted = result.accepted, lastNis = result.nis)
        if (result.accepted) { state = result.state; covariance = result.covariance }
        refreshDiagnostics(); return result
    }

    override fun injectSpeedEstimate(speedMps: Float) {
        pendingTcnSpeedMps = if (acceptsTcnSpeedEstimate && speedMps.isFinite() && speedMps >= 0f) speedMps else null
    }

    override fun injectGnssCorrection(lat: Double, lon: Double, accuracyM: Float, speedMps: Float, bearingDeg: Float, blendWindowSeconds: Double) {
        if (!lat.isFinite() || !lon.isFinite() || !accuracyM.isFinite() || accuracyM <= 0f || !speedMps.isFinite() || speedMps < 0f) return
        if (state == null) {
            origin = LatLon(lat, lon)
            val bearing = Math.toRadians(bearingDeg.toDouble())
            initialize(velocityWorldEnu = doubleArrayOf(speedMps * sin(bearing), speedMps * cos(bearing), 0.0))
            vehicleMotionObserved = speedMps >= 4f && accuracyM <= 15f
            refreshDiagnostics()
            return
        }
        if (origin == null) origin = LatLon(lat, lon)
        val position = latLonToEnu(lat, lon, origin!!)
        val result = gnssUpdater.updatePosition(state!!, covariance!!, EskfGnssPositionMeasurement(EskfVector3(position[0], position[1], 0.0), EskfVector3(accuracyM.toDouble(), accuracyM.toDouble(), max(accuracyM.toDouble(), 5.0)), state!!.timestampSeconds))
        diagnostics = diagnostics.copy(
            lastGnssAccepted = result.accepted,
            lastNis = result.nis,
            lastGnssNis = result.nis,
            lastGnssTimestampSeconds = state!!.timestampSeconds,
            lastGnssInnovationMagnitudeM = sqrt(result.innovation.sumOf { it * it })
        )
        if (result.accepted) { state = result.state; covariance = result.covariance }
        if (bearingDeg.isFinite()) {
            val bearing = Math.toRadians(bearingDeg.toDouble())
            val velocityResult = gnssUpdater.updateVelocity(
                state!!,
                covariance!!,
                EskfGnssVelocityMeasurement(
                    EskfVector3(speedMps * sin(bearing), speedMps * cos(bearing), 0.0),
                    EskfVector3(0.5, 0.5, 1.0),
                    state!!.timestampSeconds
                )
            )
            diagnostics = diagnostics.copy(lastGnssAccepted = result.accepted && velocityResult.accepted, lastNis = velocityResult.nis, lastGnssNis = velocityResult.nis, lastGnssTimestampSeconds = state!!.timestampSeconds)
            if (velocityResult.accepted) { state = velocityResult.state; covariance = velocityResult.covariance }
        }
        if (speedMps >= 4f && accuracyM <= 15f) vehicleMotionObserved = true
        refreshDiagnostics()
    }

    override fun getEstimatedPosition(): DrPosition? {
        val current = state ?: return null
        val speed = sqrt(current.velocity[0] * current.velocity[0] + current.velocity[1] * current.velocity[1])
        val heading = if (speed > 0.01) Math.toDegrees(atan2(current.velocity[0], current.velocity[1])).toFloat().let { if (it < 0f) it + 360f else it } else 0f
        val reference = origin ?: LatLon(0.0, 0.0)
        val ll = enuToLatLon(current.position[0], current.position[1], reference)
        val positionVariance = covariance?.values?.let { max(it[0][0], max(it[1][1], it[2][2])) } ?: Double.POSITIVE_INFINITY
        return DrPosition(ll[0], ll[1], heading, speed.toFloat(), sqrt(max(0.0, positionVariance)).toFloat())
    }

    override fun reset() { state = null; covariance = null; lastTimestampNs = 0L; pendingTcnSpeedMps = null; vehicleMotionObserved = false; origin = null; shadowSuspended = false; diagnostics = EskfProviderDiagnostics() }

    fun markInvalid(message: String) { shadowSuspended = true; diagnostics = diagnostics.copy(valid = false, error = message); refreshDiagnostics() }
    private fun invalidate(message: String) { markInvalid(message) }
    private fun refreshDiagnostics() {
        val current = state; val p = covariance
        diagnostics = diagnostics.copy(
            initialized = current != null,
            lastPropagationTimestampNs = lastTimestampNs,
            stateFinite = current?.let { it.position.all(Double::isFinite) && it.velocity.all(Double::isFinite) && it.quaternion.norm().isFinite() } == true,
            covarianceFinite = p?.values?.all { row -> row.all(Double::isFinite) } == true,
            covariancePsd = p?.let { it.minimumEigenvalue() >= config.covariancePsdTolerance } == true,
            vehicleMotionObserved = vehicleMotionObserved,
            positionWorldEnu = current?.position?.map { it } ?: listOf(0.0, 0.0, 0.0),
            velocityWorldEnu = current?.velocity?.map { it } ?: listOf(0.0, 0.0, 0.0),
            speedMps = current?.let { sqrt(it.velocity[0] * it.velocity[0] + it.velocity[1] * it.velocity[1]) } ?: Double.NaN,
            headingDeg = current?.let { Math.toDegrees(atan2(it.velocity[0], it.velocity[1])) } ?: Double.NaN,
            positionLatitude = current?.let { enuToLatLon(it.position[0], it.position[1], origin ?: LatLon(0.0, 0.0))[0] } ?: Double.NaN,
            positionLongitude = current?.let { enuToLatLon(it.position[0], it.position[1], origin ?: LatLon(0.0, 0.0))[1] } ?: Double.NaN,
            covarianceTrace = p?.values?.indices?.sumOf { index -> p.values[index][index] } ?: Double.NaN,
            quaternionNorm = current?.quaternion?.norm() ?: Double.NaN,
        )
    }

    private fun latLonToEnu(lat: Double, lon: Double, ref: LatLon): DoubleArray { val r = 6_378_137.0; val lat0 = Math.toRadians(ref.lat); return doubleArrayOf(r * cos(lat0) * Math.toRadians(lon - ref.lon), r * Math.toRadians(lat - ref.lat)) }
    private fun enuToLatLon(east: Double, north: Double, ref: LatLon): DoubleArray { val r = 6_378_137.0; val lat0 = Math.toRadians(ref.lat); return doubleArrayOf(ref.lat + Math.toDegrees(north / r), ref.lon + Math.toDegrees(east / (r * cos(lat0)))) }
}

private fun defaultProviderPhoneToVehicle(): PhoneToVehicleRotation = PhoneToVehicleRotation(arrayOf(
    doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0)
))
