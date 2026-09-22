package com.example.auto_measure_luoji.feature.camera.domain.exposure

import java.util.Locale

/** 实际 CaptureResult 值，不是请求值或帧间隔；接收时间使用单调时钟。 */
data class ExposureReading(
    val exposureTimeNs: Long?,
    val receivedAtNs: Long,
    val cameraId: String,
    val physical: Boolean,
    val sessionGeneration: Long = 0L,
    val iso: Int? = null,
    val aperture: Float? = null,
) {
    fun isFresh(nowNs: Long): Boolean = nowNs >= receivedAtNs && nowNs - receivedAtNs <= 2_000_000_000L

    fun panelText(nowNs: Long): String = "曝光时间：" + when {
        !isFresh(nowNs) -> "等待新帧"
        exposureTimeNs == null -> "未提供"
        exposureTimeNs <= 0L -> "读数异常"
        // Camera2 单位为 ns；1 ms = 1,000,000 ns，使用浮点除法保留亚毫秒曝光。
        else -> String.format(Locale.ROOT, "%.3f ms", exposureTimeNs / 1_000_000.0)
    }

    val logText: String
        get() = "SENSOR_EXPOSURE_TIME=$exposureTimeNs ns SENSOR_SENSITIVITY=$iso LENS_APERTURE=$aperture camera=$cameraId output=${if (physical) "physical" else "logical"} generation=$sessionGeneration"

    fun cameraParametersText(nowNs: Long): String {
        if (!isFresh(nowNs)) return "ISO：等待新帧\n光圈：等待新帧"
        val isoText = when { iso == null -> "未提供"; iso <= 0 -> "读数异常"; else -> iso.toString() }
        val apertureText = when {
            aperture == null -> "未提供"
            !aperture.isFinite() || aperture <= 0f -> "读数异常"
            else -> String.format(Locale.ROOT, "f/%.1f", aperture)
        }
        return "ISO：$isoText\n光圈：$apertureText"
    }
}
