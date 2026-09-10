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
    val accelBiasMag: Double = 0.0,
    val gyroBiasMag: Double = 0.0,
    val consecutiveGnssRejections: Int = 0,
    val error: String? = null
) {
    val isHealthy: Boolean get() = valid && stateFinite && covarianceFinite && covariancePsd &&
            speedMps.isFinite() && speedMps >= 0.0 && speedMps < 50.0 &&
            covarianceTrace.isFinite() && covarianceTrace < 2500.0 &&
            quaternionNorm.isFinite() && kotlin.math.abs(quaternionNorm - 1.0) < 0.05 &&
            accelBiasMag < 5.0 && gyroBiasMag < 0.5 &&
            consecutiveGnssRejections < 3 &&
            runtimeState != EskfRuntimeState.INVALID &&
            runtimeState != EskfRuntimeState.DEGRADED
}

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
    /** Counts consecutive high-quality GNSS fixes rejected by the NIS gate.
     *  When this reaches [GNSS_DIVERGENCE_RECOVERY_THRESHOLD] a controlled
     *  position/velocity reset is triggered to escape a divergence latch. */
    private var consecutiveGnssRejections = 0


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
            val useLinearAccel = snapshot.hasLinearAccel && snapshot.linearAccelMag.isFinite()
            val sample = EskfImuSample(
                snapshot.timestampNs / 1e9,
                if (useLinearAccel) {
                    EskfVector3(snapshot.linearAccelX.toDouble(), snapshot.linearAccelY.toDouble(), snapshot.linearAccelZ.toDouble())
                } else {
                    EskfVector3(snapshot.accelX.toDouble(), snapshot.accelY.toDouble(), snapshot.accelZ.toDouble())
                },
                EskfVector3(snapshot.gyroX.toDouble(), snapshot.gyroY.toDouble(), snapshot.gyroZ.toDouble()),
                useLinearAccel
            )
            val propagated = propagator.propagate(state!!, sample, dt)
            val propagatedCovariance = covariancePropagator.propagate(covariance!!, propagated, sample, dt).covariance
            state = propagated; covariance = propagatedCovariance; lastTimestampNs = snapshot.timestampNs

            // Continuous tilt leveling: keeps pitch and roll strictly aligned to true gravity
            // without corrupting yaw (since deltaTheta_tilt is perpendicular to Up, deltaTheta . Up = 0).
            // Dynamic trust gating: attenuate or suspend leveling during aggressive acceleration,
            // hard braking, potholes, or high angular velocity so dynamic forces are not treated as gravity.
            if (snapshot.hasRotVector && snapshot.quatNorm in 0.95f..1.05f) {
                val accelMag = if (snapshot.accelMag.isFinite() && snapshot.accelMag > 0f) {
                    snapshot.accelMag
                } else {
                    kotlin.math.sqrt(snapshot.accelX * snapshot.accelX + snapshot.accelY * snapshot.accelY + snapshot.accelZ * snapshot.accelZ)
                }
                val devFromG = kotlin.math.abs(accelMag - 9.81f).toDouble()
                val gyroMag = snapshot.gyroMag.toDouble()

                // Catch horizontal acceleration directly: during braking/turning/acceleration,
                // norm ||a|| = sqrt(g^2 + a_h^2) only increases slightly, but linear acceleration
                // or sqrt(|a^2 - g^2|) directly catches the perturbing vehicle force.
                val perturbAccel = if (snapshot.hasLinearAccel && snapshot.linearAccelMag.isFinite() && snapshot.linearAccelMag > 0f) {
                    kotlin.math.max(devFromG, snapshot.linearAccelMag.toDouble())
                } else {
                    val aSq = accelMag.toDouble() * accelMag.toDouble()
                    val gSq = 9.81 * 9.81
                    kotlin.math.sqrt(kotlin.math.abs(aSq - gSq))
                }

                // Continuous confidence weights based on physical sensor characteristics:
                // 1. Gravity weight: full confidence within ±0.4 m/s² noise, decays smoothly to 0 beyond 2.0 m/s²
                val wGrav = (1.0 - (perturbAccel / 2.0)).coerceIn(0.0, 1.0).let { it * it }
                // 2. Gyro weight: full confidence when stationary/low-rotation (< 0.1 rad/s), decays smoothly to 0 at 0.8 rad/s (~46°/s)
                val wGyro = (1.0 - (gyroMag / 0.8)).coerceIn(0.0, 1.0).let { it * it }
                val dynamicTrust = wGrav * wGyro

                if (dynamicTrust > 0.02) {
                    val qRv = EskfQuaternion(
                        snapshot.quatW.toDouble(), snapshot.quatX.toDouble(),
                        snapshot.quatY.toDouble(), snapshot.quatZ.toDouble()
                    ).normalized()
                    val rRv = qRv.toRotationMatrix()
                    // Row 2 of rotation matrix is the Up unit vector in phone coordinates
                    val uUpRefX = rRv[2][0]
                    val uUpRefY = rRv[2][1]
                    val uUpRefZ = rRv[2][2]

                    val rEskf = state!!.quaternion.toRotationMatrix()
                    val uUpEskfX = rEskf[2][0]
                    val uUpEskfY = rEskf[2][1]
                    val uUpEskfZ = rEskf[2][2]

                    // Correct right-multiplication sign: c = uUpRef × uUpEskf
                    val cx = uUpRefY * uUpEskfZ - uUpRefZ * uUpEskfY
                    val cy = uUpRefZ * uUpEskfX - uUpRefX * uUpEskfZ
                    val cz = uUpRefX * uUpEskfY - uUpRefY * uUpEskfX
                    val tiltNorm = kotlin.math.sqrt(cx * cx + cy * cy + cz * cz)
                    if (tiltNorm > 1e-6) {
                        val baseGain = 0.08
                        val factor = baseGain * dynamicTrust * dt.coerceIn(0.005, 0.1) * 10.0
                        val deltaTheta = doubleArrayOf(cx * factor, cy * factor, cz * factor)
                        val deltaQ = deltaQuaternionFromRotationVector(deltaTheta)
                        state = state!!.copy(quaternion = (state!!.quaternion * deltaQ).normalized())
                    }
                }
            }

            val correctedGyroWorld = phoneToWorld(
                EskfVector3(
                    snapshot.gyroX.toDouble() - propagated.gyroscopeBias[0],
                    snapshot.gyroY.toDouble() - propagated.gyroscopeBias[1],
                    snapshot.gyroZ.toDouble() - propagated.gyroscopeBias[2]
                ),
                state!!.quaternion
            )
            diagnostics = diagnostics.copy(lastDtSeconds = dt, yawRateDegS = (-Math.toDegrees(correctedGyroWorld.z)).toFloat())
            pendingTcnSpeedMps?.let { speed ->
                val tcnTimestampNs = pendingTcnTimestampNs
                // Reject stale TCN measurements: if the pending sample is older than 1.0s
                // relative to the current propagated state, the velocity estimate is
                // no longer representative of present motion.
                val tcnAgeSeconds = if (tcnTimestampNs > 0L)
                    (propagated.timestampSeconds - tcnTimestampNs / 1e9)
                else 0.0
                if (tcnAgeSeconds <= 1.0 && tcnTimestampNs > lastTcnTimestampNs) {
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
            consecutiveGnssRejections = 0
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
        if (result.accepted) {
            state = result.state
            covariance = result.covariance
            consecutiveGnssRejections = 0
        } else if (accuracyM <= 10f) {
            // Only count rejections from high-quality fixes as evidence of divergence.
            consecutiveGnssRejections++
            if (consecutiveGnssRejections >= GNSS_DIVERGENCE_RECOVERY_THRESHOLD) {
                // Controlled recovery: snap position and velocity to the GNSS fix,
                // inflate their covariance to reflect the jump, but preserve attitude
                // and IMU biases which are still valid.
                val bearing = if (bearingDeg.isFinite() && suppliedSpeed >= 1.5f)
                    Math.toRadians(bearingDeg.toDouble()) else null
                val recoveryVelocity = if (bearing != null)
                    doubleArrayOf(suppliedSpeed * sin(bearing), suppliedSpeed * cos(bearing), 0.0)
                else state!!.velocity
                state = state!!.copy(
                    position = doubleArrayOf(position[0], position[1], state!!.position[2]),
                    velocity = recoveryVelocity
                )
                covariance = covariance!!.withInflatedPosVel(
                    posStd = (accuracyM * 2.0).coerceAtLeast(5.0),
                    velStd = 3.0
                )
                consecutiveGnssRejections = 0
                diagnostics = diagnostics.copy(lastGnssRejectionReason = "Divergence recovery applied")
                gnssAccepted = true
            }
        }
        // Only inject GNSS velocity when speed is reliable (≥ 1.5 m/s).
        // Below this threshold GNSS course is undefined or heavily quantised
        // and injects false velocity innovations that degrade the filter.
        if (bearingDeg.isFinite() && speedMps.isFinite() && suppliedSpeed >= 1.5f) {
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
        // Row 0 of R_v_p is the vehicle-forward axis expressed in phone coordinates.
        // (Previously used column 0 which was the wrong direction, causing a 90° offset.)
        val vehicleForwardPhone = phoneToVehicle.forwardPhone.asArray()
        val forwardWorld = phoneToWorld(EskfVector3.fromArray(vehicleForwardPhone), current.quaternion).asArray()
        val heading = if (initialOrientationAvailable) attitudeHeading(current) else Float.NaN
        val reference = origin ?: LatLon(0.0, 0.0)
        val ll = enuToLatLon(current.position[0], current.position[1], reference)
        val positionVariance = covariance?.values?.let { max(it[0][0], max(it[1][1], it[2][2])) } ?: Double.POSITIVE_INFINITY
        return DrPosition(ll[0], ll[1], heading, speed.toFloat(), sqrt(max(0.0, positionVariance)).toFloat())
    }

    override fun reset() { state = null; covariance = null; lastTimestampNs = 0L; pendingTcnSpeedMps = null; pendingTcnTimestampNs = 0L; lastTcnTimestampNs = 0L; lastGnssSourceTimestampNs = 0L; pendingInitialQuaternion = EskfQuaternion.IDENTITY; initialOrientationAvailable = false; vehicleMotionObserved = false; stationaryAccumSeconds = 0.0; origin = null; estimatorInvalid = false; consecutiveGnssRejections = 0; diagnostics = EskfProviderDiagnostics(calibrationActive = calibrationActive) }

    fun markInvalid(message: String) { estimatorInvalid = true; diagnostics = diagnostics.copy(valid = false, runtimeState = EskfRuntimeState.INVALID, degradationReason = message, error = message); refreshDiagnostics() }
    private fun invalidate(message: String) { markInvalid(message) }
    private fun refreshDiagnostics() {
        val current = state; val p = covariance
        val stateFinite = current?.let {
            it.position.all(Double::isFinite) &&
                    it.velocity.all(Double::isFinite) &&
                    it.quaternion.norm().isFinite() &&
                    it.accelerometerBias.all(Double::isFinite) &&
                    it.gyroscopeBias.all(Double::isFinite)
        } == true
        val covarianceFinite = p?.values?.all { row -> row.all(Double::isFinite) } == true
        val covariancePsd = p?.let { it.minimumEigenvalue() >= config.covariancePsdTolerance } == true
        val quaternionNorm = current?.quaternion?.norm() ?: Double.NaN
        val accelBiasMag = current?.let {
            sqrt(it.accelerometerBias[0] * it.accelerometerBias[0] +
                 it.accelerometerBias[1] * it.accelerometerBias[1] +
                 it.accelerometerBias[2] * it.accelerometerBias[2])
        } ?: 0.0
        val gyroBiasMag = current?.let {
            sqrt(it.gyroscopeBias[0] * it.gyroscopeBias[0] +
                 it.gyroscopeBias[1] * it.gyroscopeBias[1] +
                 it.gyroscopeBias[2] * it.gyroscopeBias[2])
        } ?: 0.0
        val runtimeState = when {
            estimatorInvalid -> EskfRuntimeState.INVALID
            current == null -> EskfRuntimeState.UNINITIALIZED
            !stateFinite || !covarianceFinite || !covariancePsd || !quaternionNorm.isFinite() -> EskfRuntimeState.INVALID
            accelBiasMag >= 5.0 || gyroBiasMag >= 0.5 -> EskfRuntimeState.INVALID
            diagnostics.stationary -> EskfRuntimeState.STATIONARY
            vehicleMotionObserved && !calibrationActive -> EskfRuntimeState.DEGRADED
            vehicleMotionObserved -> EskfRuntimeState.MOVING
            else -> EskfRuntimeState.READY
        }
        val degradationReason = when {
            runtimeState == EskfRuntimeState.INVALID -> diagnostics.error ?: if (accelBiasMag >= 5.0 || gyroBiasMag >= 0.5) "Exploding sensor bias" else null
            runtimeState == EskfRuntimeState.DEGRADED -> "Vehicle-frame calibration required for TCN/NHC"
            else -> null
        }
        diagnostics = diagnostics.copy(
            initialized = current != null,
            valid = !estimatorInvalid && current != null && stateFinite && covarianceFinite &&
                    covariancePsd && quaternionNorm.isFinite() && abs(quaternionNorm - 1.0) <= 1e-10 &&
                    accelBiasMag < 5.0 && gyroBiasMag < 0.5,
            lastPropagationTimestampNs = lastTimestampNs,
            stateFinite = stateFinite,
            covarianceFinite = covarianceFinite,
            covariancePsd = covariancePsd,
            runtimeState = runtimeState,
            calibrationActive = calibrationActive,
            degradationReason = degradationReason,
            vehicleMotionObserved = vehicleMotionObserved,
            accelBiasMag = accelBiasMag,
            gyroBiasMag = gyroBiasMag,
            consecutiveGnssRejections = consecutiveGnssRejections,
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
        // Row 0 of R_v_p = vehicle-forward axis in phone coordinates.
        // Using column 0 previously gave the vehicle-lateral axis → 90° offset.
        val vehicleForwardPhone = phoneToVehicle.forwardPhone.asArray()
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

/** Number of consecutive high-accuracy GNSS position rejections that trigger a controlled
 *  divergence recovery.  Requires accuracy ≤ 10 m on every rejected fix to prevent a noisy
 *  GPS epoch from prematurely resetting a healthy estimator. */
private const val GNSS_DIVERGENCE_RECOVERY_THRESHOLD = 5
