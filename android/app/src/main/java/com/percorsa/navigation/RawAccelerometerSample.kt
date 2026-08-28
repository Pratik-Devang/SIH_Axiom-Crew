package com.percorsa.navigation

data class RawAccelerometerSample(
    val timestampNs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float
)