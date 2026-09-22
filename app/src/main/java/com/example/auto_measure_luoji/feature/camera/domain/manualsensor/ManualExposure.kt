package com.example.auto_measure_luoji.feature.camera.domain.manualsensor

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

data class ManualExposure(val timeNs: Long, val iso: Int, val aperture: Float? = null)

data class ManualSensorCapabilities(
    val minTimeNs: Long = 0, val maxTimeNs: Long = 0,
    val minIso: Int = 0, val maxIso: Int = 0, val maxFrameNs: Long = 0,
    val apertures: List<Float> = emptyList(), val apertureWritable: Boolean = false,
) {
    val supported get() = minTimeNs > 0 && maxTimeNs >= minTimeNs && minIso > 0 && maxIso >= minIso && maxFrameNs >= maxTimeNs
    val variableAperture get() = supported && apertureWritable && apertures.size > 1

    fun validate(value: ManualExposure): ManualExposure {
        require(supported) { "当前镜头不支持手动曝光" }
        require(value.timeNs in minTimeNs..maxTimeNs && value.iso in minIso..maxIso) { "快门或 ISO 超出镜头范围" }
        require(value.aperture == null || (variableAperture && value.aperture in apertures)) { "当前镜头不支持该光圈档位" }
        return value
    }

    fun frameDuration(value: ManualExposure): Long = maxOf(validate(value).timeNs, 33_333_333L).coerceAtMost(maxFrameNs)

    // 对数滑块：t = exp(ln(t_min) + p * ln(t_max/t_min))，使长短快门都有可用调节精度。
    // Camera2 使用纳秒；显示毫秒时除以 1e6，不能把 ms 值直接传给 SENSOR_EXPOSURE_TIME。
    fun timeAt(progress: Float): Long {
        require(supported && progress.isFinite())
        return exp(ln(minTimeNs.toDouble()) + progress.coerceIn(0f, 1f) *
            (ln(maxTimeNs.toDouble()) - ln(minTimeNs.toDouble()))).roundToLong().coerceIn(minTimeNs, maxTimeNs)
    }
    fun progress(timeNs: Long): Float = if (!supported || minTimeNs == maxTimeNs) 0f else
        ((ln(timeNs.coerceIn(minTimeNs, maxTimeNs).toDouble()) - ln(minTimeNs.toDouble())) /
            (ln(maxTimeNs.toDouble()) - ln(minTimeNs.toDouble()))).toFloat().coerceIn(0f, 1f)
}
