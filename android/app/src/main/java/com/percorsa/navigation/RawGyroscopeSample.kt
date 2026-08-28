package com.percorsa.navigation

data class RawGyroscopeSample(
    val timestampNs: Long,
    val gx: Float,
    val gy: Float,
    val gz: Float
)