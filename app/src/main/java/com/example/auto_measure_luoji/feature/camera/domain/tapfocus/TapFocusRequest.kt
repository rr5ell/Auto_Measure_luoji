package com.example.auto_measure_luoji.feature.camera.domain.tapfocus

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build

/** AF 与 AE 共享已映射的传感器区域；触发只用于单次 capture，重复请求始终为 IDLE。 */
class TapFocusRequest {
    private data class FocusTag(val serial: Long)
    private var supported = false
    private var bounds: Rect? = null
    private var preCorrectionBounds: Rect? = null
    private var defaultRegions: Array<MeteringRectangle>? = null
    private var defaultMode: Int? = null
    private var defaultTag: Any? = null
    private var active = false
    private var lastTapId: Long? = null
    private var serial = 0L
    private var lastFrame = -1L
    var previewMode = CaptureRequest.CONTROL_AF_MODE_OFF
        private set
    @Volatile var status = "对焦：等待相机"
        private set

    fun configure(chars: CameraCharacteristics) {
        val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        previewMode = when {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            CaptureRequest.CONTROL_AF_MODE_AUTO in modes -> CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> CaptureRequest.CONTROL_AF_MODE_OFF
        }
        bounds = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        preCorrectionBounds = chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        val keys = chars.availableCaptureRequestKeys.orEmpty()
        supported = CaptureRequest.CONTROL_AF_MODE_AUTO in modes &&
            (chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0 &&
            CaptureRequest.CONTROL_AF_REGIONS in keys && CaptureRequest.CONTROL_AF_TRIGGER in keys &&
            bounds?.isEmpty == false
    }

    @Synchronized
    fun rememberTemplate(builder: CaptureRequest.Builder) {
        defaultMode = builder.get(CaptureRequest.CONTROL_AF_MODE)
        defaultRegions = builder.get(CaptureRequest.CONTROL_AF_REGIONS)?.copyOf()
        defaultTag = builder.build().tag
        active = false
        lastTapId = null
        status = defaultStatus()
    }

    private fun defaultStatus() = if (supported) "对焦：自动" else "对焦：不支持点击区域（保留测光）"

    private fun pulse(builder: CaptureRequest.Builder, trigger: Int, single: (CaptureRequest) -> Unit) {
        try {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
            single(builder.build())
        } finally {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        }
    }

    @Synchronized
    fun update(builder: CaptureRequest.Builder, region: Rect?, tapId: Long,
               single: (CaptureRequest) -> Unit, repeating: (CaptureRequest) -> Unit): Long? {
        if (region == null) {
            lastTapId = null
            if (active) restore(builder, single, repeating)
            else status = defaultStatus()
            return null
        }
        if (!supported) { status = defaultStatus(); return null }
        if (lastTapId == tapId) return null
        require(!region.isEmpty) { "点击对焦区域无效" }
        lastTapId = tapId
        serial++
        lastFrame = -1L
        active = true
        try {
            pulse(builder, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL, single)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            // region 原样复用 CenterMeteringRegion 的结果：同一 crop、畸变坐标系及屏幕逆映射，
            // 不再次旋转/缩放；AF 和 AE 窗口中心、宽高、边界完全一致，避免 GL/HDR 下坐标漂移。
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(Rect(region), 1000)))
            builder.setTag(FocusTag(serial))
            pulse(builder, CaptureRequest.CONTROL_AF_TRIGGER_START, single)
            repeating(builder.build())
            status = "对焦：已触发，等待结果"
            return serial
        } catch (error: Exception) {
            try {
                restore(builder, single, repeating)
                status = "对焦：请求失败，已恢复自动"
            } catch (restoreError: Exception) {
                error.addSuppressed(restoreError)
                status = "对焦：恢复失败"
            }
            throw error
        }
    }

    private fun restore(builder: CaptureRequest.Builder, single: (CaptureRequest) -> Unit,
                        repeating: (CaptureRequest) -> Unit) {
        // 即使单次 CANCEL 被拒绝，仍恢复请求模式和区域，不能遗留 AUTO 锁定请求。
        // 恢复成功前保留 active，以便关闭/暂停能重试；恢复期间忽略旧 AF 状态。
        lastFrame = Long.MAX_VALUE
        val cancel = runCatching { pulse(builder, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL, single) }
        builder.set(CaptureRequest.CONTROL_AF_MODE, defaultMode)
        val array = if (Build.VERSION.SDK_INT >= 28 &&
            builder.get(CaptureRequest.DISTORTION_CORRECTION_MODE) == CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            preCorrectionBounds ?: bounds else bounds
        val regions = defaultRegions?.takeIf { it.any { r -> r.width > 0 && r.height > 0 && r.meteringWeight > 0 } }
            ?: array?.let { arrayOf(MeteringRectangle(it, MeteringRectangle.METERING_WEIGHT_DONT_CARE)) }
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
        builder.setTag(defaultTag)
        repeating(builder.build())
        active = false
        status = if (cancel.isSuccess) defaultStatus() else "对焦：取消被拒绝，已恢复自动请求"
    }

    @Synchronized
    fun expire(builder: CaptureRequest.Builder, token: Long, single: (CaptureRequest) -> Unit,
               repeating: (CaptureRequest) -> Unit) {
        if (!active || token != serial) return
        val outcome = status
        try {
            restore(builder, single, repeating)
            if (outcome == "对焦：已触发，等待结果" || outcome == "对焦：搜索中")
                status = "对焦：超时，已恢复自动"
        } catch (error: Exception) {
            status = "对焦：恢复失败"
            throw error
        }
    }

    @Synchronized
    fun onResult(request: CaptureRequest, result: TotalCaptureResult) {
        if (!active || request.tag != FocusTag(serial) || result.frameNumber <= lastFrame ||
            request.get(CaptureRequest.CONTROL_AF_TRIGGER) == CaptureRequest.CONTROL_AF_TRIGGER_CANCEL) return
        lastFrame = result.frameNumber
        status = when (result.get(CaptureResult.CONTROL_AF_STATE)) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "对焦：已合焦（点击）"
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "对焦：未合焦（可重新点击）"
            CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "对焦：搜索中"
            else -> status
        }
    }

    companion object { const val RESTORE_DELAY_MS = 5_000L }
}
