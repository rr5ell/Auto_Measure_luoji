package com.example.auto_measure_luoji.feature.calibration.model

data class LaserResult(
    val cx: Double,
    val cy: Double,
    val confidence: Double,
    val finalScore: Double,
    val debugReport: String? = null,
)
