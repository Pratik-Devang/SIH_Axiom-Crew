package com.percorsa.sensorlogger

/**
 * Developer-visible navigation health metrics.
 * Hidden from normal navigation UI, accessible only in Developer Mode.
 */
data class NavigationHealth(
    val gnssHealth: HealthStatus = HealthStatus.UNKNOWN,
    val accelHealth: HealthStatus = HealthStatus.UNKNOWN,
    val gyroHealth: HealthStatus = HealthStatus.UNKNOWN,
    val rotationVectorHealth: HealthStatus = HealthStatus.UNKNOWN,
    val filterHealth: HealthStatus = HealthStatus.UNKNOWN,
    val tcnHealth: HealthStatus = HealthStatus.UNKNOWN,
    val routeHealth: HealthStatus = HealthStatus.UNKNOWN,
    val details: String = ""
)

enum class HealthStatus {
    GOOD, FAIR, DEGRADED, FAILED, UNKNOWN
}
