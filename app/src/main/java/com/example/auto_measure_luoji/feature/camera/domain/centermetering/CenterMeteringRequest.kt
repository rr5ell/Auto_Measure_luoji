package com.example.auto_measure_luoji.feature.camera.domain.centermetering

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import android.util.Size

/** 只设置 AE 区域，不改变 AE 模式、锁定、EV、AF 或防抖。 */
class CenterMeteringRequest {
    private var known = false
    private var canSetRegions = false
    private var activeArray: Rect? = null
    private var preCorrectionArray: Rect? = null
    private var defaultRegions: Array<MeteringRectangle>? = null
    var rejected = false
        private set
    val supported: Boolean? get() = if (known) canSetRegions && !rejected else null

    fun configure(chars: CameraCharacteristics) {
        known = true
        rejected = false
        activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        preCorrectionArray = chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        canSetRegions = (chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0 &&
            chars.availableCaptureRequestKeys?.contains(CaptureRequest.CONTROL_AE_REGIONS) == true &&
            activeArray?.isEmpty == false
    }

    fun rememberTemplate(builder: CaptureRequest.Builder) {
        defaultRegions = builder.get(CaptureRequest.CONTROL_AE_REGIONS)?.copyOf()
    }

    fun restore(builder: CaptureRequest.Builder) {
        if (!canSetRegions) return
        val defaults = defaultRegions?.takeIf { regions -> regions.any {
            it.meteringWeight > 0 && it.width > 0 && it.height > 0
        } }
        // 某些 HAL 忽略模板 (0,0,0,0,weight=0)，导致旧中心区域残留。
        // 全幅合法矩形 + 零权重同样表示不指定测光重点，但不会被当成非法空矩形丢弃。
        val regions = defaults?.copyOf() ?: coordinateArray(builder)?.let {
            arrayOf(MeteringRectangle(it, MeteringRectangle.METERING_WEIGHT_DONT_CARE))
        }
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
    }

    fun reject(builder: CaptureRequest.Builder) {
        rejected = true
        restore(builder)
    }

    fun apply(builder: CaptureRequest.Builder, preview: Size, enabled: Boolean, displayScale: Float = 1f): Rect? {
        if (!enabled || supported != true) {
            restore(builder)
            return null
        }
        // 畸变校正关闭时 AE 与 crop 使用校正前坐标；不主动改变相机模板的校正模式。
        val bounds = coordinateArray(builder) ?: error("缺少测光坐标范围")
        val crop = Rect(builder.get(CaptureRequest.SCALER_CROP_REGION) ?: bounds)
        check(crop.intersect(bounds)) { "测光裁切窗口超出传感器范围" }
        val region = CenterMeteringRegion.calculate(
            CenterMeteringRegion.Bounds(crop.left, crop.top, crop.right, crop.bottom), preview.width, preview.height, displayScale)
            ?: error("测光窗口无效")
        val rect = Rect(region.left, region.top, region.right, region.bottom)
        // 单一区域任意非零权重等价；1000 不是让厂商无条件忽略其他区域的保证。
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(MeteringRectangle(rect, 1000)))
        return rect
    }

    private fun coordinateArray(builder: CaptureRequest.Builder): Rect? =
        if (Build.VERSION.SDK_INT >= 28 &&
            builder.get(CaptureRequest.DISTORTION_CORRECTION_MODE) == CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            preCorrectionArray ?: activeArray else activeArray
}
