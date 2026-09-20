package com.example.auto_measure_luoji.feature.camera.domain.exposure

import java.util.Locale

/** 实际 CaptureResult 值，不是请求值或帧间隔；接收时间使用单调时钟。 */
data class ExposureReading(
    val exposureTimeNs: Long?,
    val receivedAtNs: Long,
    val cameraId: String,
    val physical: Boolean,
) {
    fun panelText(nowNs: Long): String = "曝光时间：" + when {
        nowNs < receivedAtNs || nowNs - receivedAtNs > 2_000_000_000L -> "等待新帧"
        exposureTimeNs == null -> "未提供"
        exposureTimeNs <= 0L -> "读数异常"
        // Camera2 单位为 ns；1 ms = 1,000,000 ns，使用浮点除法保留亚毫秒曝光。
        else -> String.format(Locale.ROOT, "%.3f ms", exposureTimeNs / 1_000_000.0)
    }

    val logText: String
        get() = "SENSOR_EXPOSURE_TIME=$exposureTimeNs ns camera=$cameraId output=${if (physical) "physical" else "logical"}"
}
