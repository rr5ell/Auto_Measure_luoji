package com.example.auto_measure.feature.calibration.model

data class Sample(
    val distanceMeters: Double,
    val point: LaserPoint,
    val zoom: Double,
)
