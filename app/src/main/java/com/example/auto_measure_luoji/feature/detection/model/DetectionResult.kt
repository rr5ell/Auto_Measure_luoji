package com.example.auto_measure_luoji.feature.detection.model

import android.graphics.RectF

data class Detection(
    val classId: Int,
    val label: String,
    val score: Float,
    val box: RectF,
)

data class Timings(
    var preprocessMs: Long = 0,
    var inferenceMs: Long = 0,
    var postprocessMs: Long = 0,
    var totalMs: Long = 0,
)

data class DetectResult(
    val detections: List<Detection>,
    val timings: Timings,
)
