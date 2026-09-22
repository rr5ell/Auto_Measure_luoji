package com.example.auto_measure_luoji.feature.camera.domain.centermetering

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import android.util.Size
import com.example.auto_measure_luoji.feature.camera.domain.tapmetering.TapMeteringPoint

/** 中心模式只设置区域；点击模式临时使用自动 AE 并解锁，退出恢复，不改变 EV、AF 或防抖。 */
class CenterMeteringRequest {
    private var known = false
    private var canSetRegions = false
    private var activeArray: Rect? = null
    private var preCorrectionArray: Rect? = null
    private var defaultRegions: Array<MeteringRectangle>? = null
    private var tapAeApplied = false
    private var previousAeMode: Int? = null
    private var previousAeLock: Boolean? = null
    private var canUnlockAe = false
    var rejected = false
        private set
    val supported: Boolean? get() = if (known) canSetRegions && !rejected else null

    fun configure(chars: CameraCharacteristics) {
        known = true
        rejected = false
        canUnlockAe = chars.availableCaptureRequestKeys?.contains(CaptureRequest.CONTROL_AE_LOCK) == true
        activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        preCorrectionArray = chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        canSetRegions = (chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0 &&
            chars.availableCaptureRequestKeys?.contains(CaptureRequest.CONTROL_AE_REGIONS) == true &&
            activeArray?.isEmpty == false
    }

    fun rememberTemplate(builder: CaptureRequest.Builder) {
        tapAeApplied = false
        defaultRegions = builder.get(CaptureRequest.CONTROL_AE_REGIONS)?.copyOf()
    }

    fun restore(builder: CaptureRequest.Builder) {
        restoreTapAe(builder)
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

    private fun restoreTapAe(builder: CaptureRequest.Builder) {
        if (!tapAeApplied) return
        builder.set(CaptureRequest.CONTROL_AE_MODE, previousAeMode)
        if (canUnlockAe) builder.set(CaptureRequest.CONTROL_AE_LOCK, previousAeLock)
        tapAeApplied = false
    }

    fun apply(builder: CaptureRequest.Builder, preview: Size, enabled: Boolean, displayScale: Float = 1f,
              tap: TapMeteringPoint? = null): Rect? {
        if (!enabled || supported != true) {
            restore(builder)
            return null
        }
        require(tap == null || tap.valid) { "点击测光位置无效" }
        if (tap == null) restoreTapAe(builder)
        // 畸变校正关闭时 AE 与 crop 使用校正前坐标；不主动改变相机模板的校正模式。
        val bounds = coordinateArray(builder) ?: error("缺少测光坐标范围")
        val crop = Rect(builder.get(CaptureRequest.SCALER_CROP_REGION) ?: bounds)
        check(crop.intersect(bounds)) { "测光裁切窗口超出传感器范围" }
        val region = CenterMeteringRegion.calculate(
            CenterMeteringRegion.Bounds(crop.left, crop.top, crop.right, crop.bottom), preview.width, preview.height, displayScale,
            tap?.x ?: 0.5f, tap?.y ?: 0.5f)
            ?: error("测光窗口无效")
        val rect = Rect(region.left, region.top, region.right, region.bottom)
        // 单一区域任意非零权重等价；1000 不是让厂商无条件忽略其他区域的保证。
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(MeteringRectangle(rect, 1000)))
        if (tap != null) {
            if (!tapAeApplied) {
                previousAeMode = builder.get(CaptureRequest.CONTROL_AE_MODE)
                previousAeLock = builder.get(CaptureRequest.CONTROL_AE_LOCK)
                tapAeApplied = true
            }
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            if (canUnlockAe) builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        }
        return rect
    }

    private fun coordinateArray(builder: CaptureRequest.Builder): Rect? =
        if (Build.VERSION.SDK_INT >= 28 &&
            builder.get(CaptureRequest.DISTORTION_CORRECTION_MODE) == CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            preCorrectionArray ?: activeArray else activeArray
}
