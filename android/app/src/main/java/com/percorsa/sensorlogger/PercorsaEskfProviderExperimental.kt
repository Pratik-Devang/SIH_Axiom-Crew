package com.percorsa.sensorlogger

import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

enum class EskfRuntimeState {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    MOVING,
    STATIONARY,
    DEGRADED,
    INVALID
}

data class EskfProviderDiagnostics(
    val initialized: Boolean = false,
    val valid: Boolean = false,
    val runtimeState: EskfRuntimeState = EskfRuntimeState.UNINITIALIZED,
    val degradationReason: String? = null,
    val lastPropagationTimestampNs: Long = 0L,
    val stateFinite: Boolean = false,
    val covarianceFinite: Boolean = false,
    val covariancePsd: Boolean = false,
    val lastGnssAccepted: Boolean? = null,
    val lastTcnAccepted: Boolean? = null,
    val lastNhcAccepted: Boolean? = null,
    val lastZuptAccepted: Boolean? = null,
    val stationary: Boolean = false,
    val nhcEnabled: Boolean = false,
    val zuptEnabled: Boolean = false,
    val lastNis: Double = Double.NaN,
    val lastDtSeconds: Double = 0.0,
    val covarianceTrace: Double = Double.NaN,
    val quaternionNorm: Double = Double.NaN,
    val quaternionW: Double = Double.NaN,
    val quaternionX: Double = Double.NaN,
    val quaternionY: Double = Double.NaN,
    val quaternionZ: Double = Double.NaN,
    val positionWorldEnu: List<Double> = listOf(Double.NaN, Double.NaN, Double.NaN),
    val velocityWorldEnu: List<Double> = listOf(Double.NaN, Double.NaN, Double.NaN),
    val speedMps: Double = Double.NaN,
    val headingDeg: Double = Double.NaN,
    val positionLatitude: Double = Double.NaN,
    val positionLongitude: Double = Double.NaN,
    val lastGnssNis: Double = Double.NaN,
    val lastTcnNis: Double = Double.NaN,
    val lastTcnRejectionReason: String? = null,
    val lastGnssRejectionReason: String? = null,
    val lastNhcNis: Double = Double.NaN,
    val lastZuptNis: Double = Double.NaN,
    val lastGnssTimestampSeconds: Double = Double.NaN,
    val lastTcnTimestampSeconds: Double = Double.NaN,
    val lastGnssInnovationMagnitudeM: Double = Double.NaN,
    val vehicleMotionObserved: Boolean = false,
    val calibrationActive: Boolean = false,
    val lastTcnInjected: Boolean = false,
    val yawRateDegS: Float = Float.NaN,
    val error: String? = null
)

/** Authoritative active navigation provider backed by the Kotlin 15-state ESKF. */
class PercorsaEskfProvider(
    private val config: EskfConfig = EskfConfig(),
    initialPhoneToVehicle: PhoneToVehicleRotation = defaultProviderPhoneToVehicle()
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
    private var phoneToVehicle = initialPhoneToVehicle
    private var calibrationActive = false
    private var pendingTcnSpeedMps: Float? = null
    private var pendingTcnTimestampNs = 0L
    private var lastTcnTimestampNs = 0L
    private var lastGnssSourceTimestampNs = 0L
    private var pendingInitialQuaternion = EskfQuaternion.IDENTITY
    private var initialOrientationAvailable = false
    private var vehicleMotionObserved = false
    private var stationaryAccumSeconds = 0.0
    private var origin: LatLon? = null
    @Volatile private var diagnostics = EskfProviderDiagnostics()
    private var estimatorInvalid = false

    val status: EskfProviderDiagnostics get() = diagnostics
    val currentState: EskfNominalState? get() = state?.copyArrays()
    val currentCovariance: EskfCovariance? get() = covariance?.let { EskfCovariance.from(it.values) }
    val isInitialized: Boolean get() = state != null && diagnostics.valid
    val isSuspended: Boolean get() = estimatorInvalid
    override val acceptsTcnSpeedEstimate: Boolean get() = isInitialized && vehicleMotionObserved && calibrationActive

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
        initialOrientationAvailable = initialOrientationAvailable ||
                sqrt(velocityWorldEnu[0] * velocityWorldEnu[0] + velocityWorldEnu[1] * velocityWorldEnu[1]) > 0.01
        lastTimestampNs = timestampNs
        pendingTcnSpeedMps = null
        diagnostics = EskfProviderDiagnostics(
            initialized = true,
            valid = true,
            runtimeState = EskfRuntimeState.READY,
            lastPropagationTimestampNs = timestampNs,
            calibrationActive = calibrationActive
        )
        estimatorInvalid = false
        refreshDiagnostics()
    }

    fun setVehicleMotionObserved(observed: Boolean) {
        vehicleMotionObserved = observed
        if (!observed) {
            pendingTcnSpeedMps = null
            pendingTcnTimestampNs = 0L
            diagnostics = diagnostics.copy(lastTcnInjected = false)
        }
        refreshDiagnostics()
    }

    /** Update the fixed R_v_p used by vehicle-frame measurements. */
    fun setPhoneToVehicleRotation(transform: PhoneToVehicleRotation) {
        phoneToVehicle = PhoneToVehicleRotation(transform.copyArray())
        calibrationActive = true
        refreshDiagnostics()
    }

    /** Supply the rotation-vector attitude for first active-estimator initialization. */
    internal fun setInitialOrientation(quaternionPhoneToWorld: EskfQuaternion) {
        if (state == null && quaternionPhoneToWorld.norm().isFinite()) {
            pendingInitialQuaternion = quaternionPhoneToWorld.normalized()
            initialOrientationAvailable = true
        }
    }

    /** Drop a rejected sensor interval without resetting estimator state. */
    override fun rebaselineSensorTimestamp(timestampNs: Long) {
        if (timestampNs > 0L) {
            lastTimestampNs = timestampNs
            refreshDiagnostics()
        }
    }

    override fun update(snapshot: SensorSnapshot, dtSeconds: Double) {
        if (!isInitialized) return
        if (!dtSeconds.isFinite() || dtSeconds <= 0.0 || dtSeconds > config.maxPropagationDtSeconds) {
            return invalidate("Invalid supplied IMU dt")
        }
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
                // accelX/Y/Z are the raw Android accelerometer channels and
                // intentionally include gravity. Do not use the availability
                // flag for the representation selector.
                false
            )
            val propagated = propagator.propagate(state!!, sample, dt)
            val propagatedCovariance = covariancePropagator.propagate(covariance!!, propagated, sample, dt).covariance
            state = propagated; covariance = propagatedCovariance; lastTimestampNs = snapshot.timestampNs
            val correctedGyroWorld = phoneToWorld(
                EskfVector3(
                    snapshot.gyroX.toDouble() - propagated.gyroscopeBias[0],
                    snapshot.gyroY.toDouble() - propagated.gyroscopeBias[1],
                    snapshot.gyroZ.toDouble() - propagated.gyroscopeBias[2]
                ),
                propagated.quaternion
            )
            diagnostics = diagnostics.copy(lastDtSeconds = dt, yawRateDegS = Math.toDegrees(correctedGyroWorld.z).toFloat())
            pendingTcnSpeedMps?.let { speed ->
                val tcnTimestampNs = pendingTcnTimestampNs
                if (tcnTimestampNs == 0L || tcnTimestampNs > lastTcnTimestampNs) {
                    val result = tcnUpdater.update(propagated, propagatedCovariance, EskfTcnMeasurement(speed.toDouble(), timestampSeconds = if (tcnTimestampNs > 0L) tcnTimestampNs / 1e9 else propagated.timestampSeconds), phoneToVehicle, vehicleMotionObserved)
                    diagnostics = diagnostics.copy(
                        lastTcnAccepted = result.accepted,
                        lastNis = result.nis,
                        lastTcnNis = result.nis,
                        lastTcnTimestampSeconds = if (tcnTimestampNs > 0L) tcnTimestampNs / 1e9 else propagated.timestampSeconds,
                        lastTcnRejectionReason = result.rejectionReason
                    )
                    if (result.accepted) { state = result.state; covariance = result.covariance }
                    if (tcnTimestampNs > 0L) lastTcnTimestampNs = tcnTimestampNs
                }
                pendingTcnSpeedMps = null
                pendingTcnTimestampNs = 0L
            }
            val lowMotion = (snapshot.hasLinearAccel && snapshot.linearAccelMag < 0.35f ||
                    abs(snapshot.accelMag - 9.81f) < 0.35f) && snapshot.gyroMag < 0.15f
            stationaryAccumSeconds = if (lowMotion) stationaryAccumSeconds + dt else 0.0
            val stationary = stationaryAccumSeconds >= 0.3
            diagnostics = diagnostics.copy(
                stationary = stationary,
                nhcEnabled = vehicleMotionObserved && !stationary && calibrationActive,
                zuptEnabled = stationary
            )
            if (vehicleMotionObserved && !stationary && calibrationActive) {
                processNhc(EskfNhcMeasurement(timestampSeconds = propagated.timestampSeconds), enabled = true)
            } else if (stationary) {
                processZupt(EskfZuptMeasurement(timestampSeconds = propagated.timestampSeconds), enabled = true)
            }
            refreshDiagnostics()
        } catch (error: Exception) { invalidate(error.message ?: "ESKF update failed") }
    }

    fun processTcn(speedMps: Double, motionObserved: Boolean): EskfTcnUpdateResult? {
        if (!isInitialized) return null
        vehicleMotionObserved = motionObserved
        val result = tcnUpdater.update(state!!, covariance!!, EskfTcnMeasurement(speedMps, timestampSeconds = state!!.timestampSeconds), phoneToVehicle, motionObserved)
        diagnostics = diagnostics.copy(lastTcnAccepted = result.accepted, lastNis = result.nis, lastTcnNis = result.nis, lastTcnTimestampSeconds = state!!.timestampSeconds, lastTcnRejectionReason = result.rejectionReason)
        if (result.accepted) { state = result.state; covariance = result.covariance }
        refreshDiagnostics(); return result
    }

    fun processNhc(measurement: EskfNhcMeasurement = EskfNhcMeasurement(), enabled: Boolean): EskfConstraintUpdateResult? {
        if (!enabled || !isInitialized || !calibrationActive) return null
        val result = try { nhcUpdater.update(state!!, covariance!!, measurement, phoneToVehicle, true) } catch (_: IllegalArgumentException) { return null }
        diagnostics = diagnostics.copy(lastNhcAccepted = result.accepted, lastNis = result.nis, lastNhcNis = result.nis)
        if (result.accepted) { state = result.state; covariance = result.covariance }
        refreshDiagnostics(); return result
    }

    fun processZupt(measurement: EskfZuptMeasurement = EskfZuptMeasurement(), enabled: Boolean): EskfConstraintUpdateResult? {
        if (!enabled || !isInitialized) return null
        val result = try { zuptUpdater.update(state!!, covariance!!, measurement, true) } catch (_: IllegalArgumentException) { return null }
        diagnostics = diagnostics.copy(lastZuptAccepted = result.accepted, lastNis = result.nis, lastZuptNis = result.nis)
        if (result.accepted) { state = result.state; covariance = result.covariance }
        refreshDiagnostics(); return result
    }

    override fun injectSpeedEstimate(speedMps: Float, timestampNs: Long) {
        val acceptedForHandoff = acceptsTcnSpeedEstimate && speedMps.isFinite() && speedMps >= 0f
        pendingTcnSpeedMps = if (acceptedForHandoff) speedMps else null
        pendingTcnTimestampNs = if (acceptedForHandoff) timestampNs else 0L
        diagnostics = diagnostics.copy(lastTcnInjected = acceptedForHandoff)
    }

    override fun injectGnssCorrection(lat: Double, lon: Double, accuracyM: Float, speedMps: Float, bearingDeg: Float, blendWindowSeconds: Double, sourceTimestampNs: Long) {
        if (estimatorInvalid) return
        if (!lat.isFinite() || !lon.isFinite() || !accuracyM.isFinite() || accuracyM <= 0f ||
            (!speedMps.isFinite() && !speedMps.isNaN()) || speedMps < 0f) return
        if (sourceTimestampNs > 0L && sourceTimestampNs <= lastGnssSourceTimestampNs) return
        if (sourceTimestampNs > 0L) lastGnssSourceTimestampNs = sourceTimestampNs
        val suppliedSpeed = speedMps.takeIf { it.isFinite() } ?: 0f
        if (state == null) {
            origin = LatLon(lat, lon)
            val hasCourse = bearingDeg.isFinite() && speedMps.isFinite()
            val bearing = if (hasCourse) Math.toRadians(bearingDeg.toDouble()) else 0.0
            if (!initialOrientationAvailable && hasCourse) {
                pendingInitialQuaternion = quaternionForHeading(bearing)
                initialOrientationAvailable = true
            }
            initialize(
                velocityWorldEnu = if (hasCourse) doubleArrayOf(suppliedSpeed * sin(bearing), suppliedSpeed * cos(bearing), 0.0) else DoubleArray(3),
                quaternionPhoneToWorld = pendingInitialQuaternion
            )
            vehicleMotionObserved = speedMps.isFinite() && speedMps >= 4f && accuracyM <= 15f
            diagnostics = diagnostics.copy(
                lastGnssAccepted = true,
                lastGnssTimestampSeconds = if (sourceTimestampNs > 0L) sourceTimestampNs / 1e9 else state!!.timestampSeconds
            )
            refreshDiagnostics()
            return
        }
        if (origin == null) origin = LatLon(lat, lon)
        val position = latLonToEnu(lat, lon, origin!!)
        val gnssTimestampSeconds = if (sourceTimestampNs > 0L) sourceTimestampNs / 1e9 else state!!.timestampSeconds
        val result = gnssUpdater.updatePosition(state!!, covariance!!, EskfGnssPositionMeasurement(EskfVector3(position[0], position[1], 0.0), EskfVector3(accuracyM.toDouble(), accuracyM.toDouble(), max(accuracyM.toDouble(), 5.0)), gnssTimestampSeconds))
        var gnssAccepted = result.accepted
        diagnostics = diagnostics.copy(
            lastGnssAccepted = result.accepted,
            lastNis = result.nis,
            lastGnssNis = result.nis,
            lastGnssTimestampSeconds = gnssTimestampSeconds,
            lastGnssInnovationMagnitudeM = sqrt(result.innovation.sumOf { it * it }),
            lastGnssRejectionReason = if (result.accepted) null else "NIS or innovation rejected"
        )
        if (result.accepted) { state = result.state; covariance = result.covariance }
        if (bearingDeg.isFinite() && speedMps.isFinite()) {
            val bearing = Math.toRadians(bearingDeg.toDouble())
            val velocityResult = gnssUpdater.updateVelocity(
                state!!,
                covariance!!,
                EskfGnssVelocityMeasurement(
                    EskfVector3(speedMps * sin(bearing), speedMps * cos(bearing), 0.0),
                    EskfVector3(0.5, 0.5, 1.0),
                    gnssTimestampSeconds
                )
            )
            gnssAccepted = result.accepted && velocityResult.accepted
            diagnostics = diagnostics.copy(lastGnssAccepted = gnssAccepted, lastNis = velocityResult.nis, lastGnssNis = velocityResult.nis, lastGnssTimestampSeconds = gnssTimestampSeconds, lastGnssRejectionReason = if (velocityResult.accepted) null else "NIS or innovation rejected")
            if (velocityResult.accepted) { state = velocityResult.state; covariance = velocityResult.covariance }
        }
        if (speedMps.isFinite() && speedMps >= 4f && accuracyM <= 15f) vehicleMotionObserved = true
        refreshDiagnostics()
    }

    override fun getEstimatedPosition(): DrPosition? {
        if (!isInitialized) return null
        val current = state ?: return null
        val speed = sqrt(current.velocity[0] * current.velocity[0] + current.velocity[1] * current.velocity[1])
        val vehicleForwardPhone = doubleArrayOf(phoneToVehicle.values[0][0], phoneToVehicle.values[1][0], phoneToVehicle.values[2][0])
        val forwardWorld = phoneToWorld(EskfVector3.fromArray(vehicleForwardPhone), current.quaternion).asArray()
        val heading = if (initialOrientationAvailable) attitudeHeading(current) else Float.NaN
        val reference = origin ?: LatLon(0.0, 0.0)
        val ll = enuToLatLon(current.position[0], current.position[1], reference)
        val positionVariance = covariance?.values?.let { max(it[0][0], max(it[1][1], it[2][2])) } ?: Double.POSITIVE_INFINITY
        return DrPosition(ll[0], ll[1], heading, speed.toFloat(), sqrt(max(0.0, positionVariance)).toFloat())
    }

    override fun reset() { state = null; covariance = null; lastTimestampNs = 0L; pendingTcnSpeedMps = null; pendingTcnTimestampNs = 0L; lastTcnTimestampNs = 0L; lastGnssSourceTimestampNs = 0L; pendingInitialQuaternion = EskfQuaternion.IDENTITY; initialOrientationAvailable = false; vehicleMotionObserved = false; stationaryAccumSeconds = 0.0; origin = null; estimatorInvalid = false; diagnostics = EskfProviderDiagnostics(calibrationActive = calibrationActive) }

    fun markInvalid(message: String) { estimatorInvalid = true; diagnostics = diagnostics.copy(valid = false, runtimeState = EskfRuntimeState.INVALID, degradationReason = message, error = message); refreshDiagnostics() }
    private fun invalidate(message: String) { markInvalid(message) }
    private fun refreshDiagnostics() {
        val current = state; val p = covariance
        val stateFinite = current?.let {
            it.position.all(Double::isFinite) &&
                    it.velocity.all(Double::isFinite) &&
                    it.quaternion.norm().isFinite()
        } == true
        val covarianceFinite = p?.values?.all { row -> row.all(Double::isFinite) } == true
        val covariancePsd = p?.let { it.minimumEigenvalue() >= config.covariancePsdTolerance } == true
        val quaternionNorm = current?.quaternion?.norm() ?: Double.NaN
        val runtimeState = when {
            estimatorInvalid -> EskfRuntimeState.INVALID
            current == null -> EskfRuntimeState.UNINITIALIZED
            !stateFinite || !covarianceFinite || !covariancePsd || !quaternionNorm.isFinite() -> EskfRuntimeState.INVALID
            diagnostics.stationary -> EskfRuntimeState.STATIONARY
            vehicleMotionObserved && !calibrationActive -> EskfRuntimeState.DEGRADED
            vehicleMotionObserved -> EskfRuntimeState.MOVING
            else -> EskfRuntimeState.READY
        }
        val degradationReason = when {
            runtimeState == EskfRuntimeState.INVALID -> diagnostics.error
            runtimeState == EskfRuntimeState.DEGRADED -> "Vehicle-frame calibration required for TCN/NHC"
            else -> null
        }
        diagnostics = diagnostics.copy(
            initialized = current != null,
            valid = !estimatorInvalid && current != null && stateFinite && covarianceFinite &&
                    covariancePsd && quaternionNorm.isFinite() && abs(quaternionNorm - 1.0) <= 1e-10,
            lastPropagationTimestampNs = lastTimestampNs,
            stateFinite = stateFinite,
            covarianceFinite = covarianceFinite,
            covariancePsd = covariancePsd,
            runtimeState = runtimeState,
            calibrationActive = calibrationActive,
            degradationReason = degradationReason,
            vehicleMotionObserved = vehicleMotionObserved,
            positionWorldEnu = current?.position?.map { it } ?: listOf(Double.NaN, Double.NaN, Double.NaN),
            velocityWorldEnu = current?.velocity?.map { it } ?: listOf(Double.NaN, Double.NaN, Double.NaN),
            speedMps = current?.let { sqrt(it.velocity[0] * it.velocity[0] + it.velocity[1] * it.velocity[1]) } ?: Double.NaN,
            headingDeg = current?.takeIf { initialOrientationAvailable }?.let { attitudeHeading(it).toDouble() } ?: Double.NaN,
            positionLatitude = current?.let { enuToLatLon(it.position[0], it.position[1], origin ?: LatLon(0.0, 0.0))[0] } ?: Double.NaN,
            positionLongitude = current?.let { enuToLatLon(it.position[0], it.position[1], origin ?: LatLon(0.0, 0.0))[1] } ?: Double.NaN,
            covarianceTrace = p?.values?.indices?.sumOf { index -> p.values[index][index] } ?: Double.NaN,
            quaternionNorm = quaternionNorm,
            quaternionW = current?.quaternion?.w ?: Double.NaN,
            quaternionX = current?.quaternion?.x ?: Double.NaN,
            quaternionY = current?.quaternion?.y ?: Double.NaN,
            quaternionZ = current?.quaternion?.z ?: Double.NaN,
        )
    }

    private fun attitudeHeading(current: EskfNominalState): Float {
        val vehicleForwardPhone = doubleArrayOf(phoneToVehicle.values[0][0], phoneToVehicle.values[1][0], phoneToVehicle.values[2][0])
        val forwardWorld = phoneToWorld(EskfVector3.fromArray(vehicleForwardPhone), current.quaternion).asArray()
        return Math.toDegrees(atan2(forwardWorld[0], forwardWorld[1])).toFloat().let {
            if (it < 0f) it + 360f else it
        }
    }

    private fun quaternionForHeading(headingRadians: Double): EskfQuaternion {
        val yaw = PI / 2.0 - headingRadians
        return EskfQuaternion(cos(yaw / 2.0), 0.0, 0.0, sin(yaw / 2.0)).normalized()
    }


    private fun latLonToEnu(lat: Double, lon: Double, ref: LatLon): DoubleArray { val r = 6_378_137.0; val lat0 = Math.toRadians(ref.lat); return doubleArrayOf(r * cos(lat0) * Math.toRadians(lon - ref.lon), r * Math.toRadians(lat - ref.lat)) }
    private fun enuToLatLon(east: Double, north: Double, ref: LatLon): DoubleArray { val r = 6_378_137.0; val lat0 = Math.toRadians(ref.lat); return doubleArrayOf(ref.lat + Math.toDegrees(north / r), ref.lon + Math.toDegrees(east / (r * cos(lat0)))) }
}

private fun defaultProviderPhoneToVehicle(): PhoneToVehicleRotation = PhoneToVehicleRotation(arrayOf(
    doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0)
))
