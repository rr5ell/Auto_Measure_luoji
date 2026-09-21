package com.example.auto_measure_luoji.feature.camera.domain.centermetering

import kotlin.math.min
import kotlin.math.roundToInt

object CenterMeteringRegion {
    const val PREFERENCE_KEY = "center_exposure_metering_enabled"
    const val FRACTION = 0.30
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * 输入是当前请求坐标系中的有效裁切窗口，右/下边界不包含在区域内；输出仍用同一坐标系。
     * 预览缓冲区宽高必须是传感器方向，不能用已旋转的屏幕宽高；中心区域对 90 度旋转/镜像不变。
     * 先修正传感器裁切窗口与预览宽高比的差异：r=previewW/previewH，
     * visibleW=min(cropW,cropH*r)，visibleH=min(cropH,cropW/r)，两轴关于裁切中心对称。
     * 再取可见宽高各 30%（约 9% 面积）。纯 UI 中心放大 s 倍时宽高再除以 s，
     * 使参考区域仍占显示宽高的 30%；整数边界限制在裁切范围，最小为 1x1 像素。
     * CONTROL_ZOOM_RATIO 的 AE 坐标本来就是变焦后的视场，不能再除以 zoom；
     * SCALER_CROP_REGION 路径由调用者传入缩小后的 crop，避免高倍率时测光区域跑到画外。
     */
    fun calculate(crop: Bounds, previewWidth: Int, previewHeight: Int, displayScale: Float = 1f): Bounds? {
        val width = crop.right.toDouble() - crop.left
        val height = crop.bottom.toDouble() - crop.top
        if (width <= 0 || height <= 0 || previewWidth <= 0 || previewHeight <= 0) return null
        val ratio = previewWidth.toDouble() / previewHeight
        val scale = displayScale.takeIf { it.isFinite() && it >= 1f } ?: 1f
        val regionW = (min(width, height * ratio) * FRACTION / scale).roundToInt().coerceIn(1, width.toInt())
        val regionH = (min(height, width / ratio) * FRACTION / scale).roundToInt().coerceIn(1, height.toInt())
        val left = (crop.left + (width - regionW) / 2).roundToInt().coerceIn(crop.left, crop.right - regionW)
        val top = (crop.top + (height - regionH) / 2).roundToInt().coerceIn(crop.top, crop.bottom - regionH)
        return Bounds(left, top, left + regionW, top + regionH)
    }
}
