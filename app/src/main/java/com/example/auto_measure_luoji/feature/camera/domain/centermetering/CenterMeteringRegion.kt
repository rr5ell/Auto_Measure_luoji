package com.example.auto_measure_luoji.feature.camera.domain.centermetering

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.floor

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
    fun calculate(crop: Bounds, previewWidth: Int, previewHeight: Int, displayScale: Float = 1f,
                  centerX: Float = 0.5f, centerY: Float = 0.5f, fraction: Double = FRACTION): Bounds? {
        val width = crop.right.toDouble() - crop.left
        val height = crop.bottom.toDouble() - crop.top
        if (width <= 0 || height <= 0 || previewWidth <= 0 || previewHeight <= 0 ||
            !centerX.isFinite() || !centerY.isFinite() || centerX !in 0f..1f || centerY !in 0f..1f ||
            !fraction.isFinite() || fraction <= 0.0 || fraction > 1.0) return null
        val ratio = previewWidth.toDouble() / previewHeight
        val scale = displayScale.takeIf { it.isFinite() && it >= 1f } ?: 1f
        val regionW = (min(width, height * ratio) * fraction / scale).roundToInt().coerceIn(1, width.toInt())
        val regionH = (min(height, width / ratio) * fraction / scale).roundToInt().coerceIn(1, height.toInt())
        // 点击坐标已撤销显示矩阵/纹理旋转，属于原始预览的 [0,1] 坐标域。
        // 有效预览范围关于 crop 中心裁切：origin=(cropSize-visibleSize)/2。
        // 传感器目标位置=原点+归一化位置*visibleSize，再减区域半宽得到矩形左上角。
        // 靠边时整体平移区域保证合法，不改变 crop 或重复除以相机 zoomRatio。
        val visibleW = min(width, height * ratio)
        val visibleH = min(height, width / ratio)
        val minLeft = ceil(crop.left + (width - visibleW) / 2).toInt().coerceIn(crop.left, crop.right - regionW)
        val maxLeft = floor(crop.left + (width + visibleW) / 2 - regionW).toInt().coerceIn(minLeft, crop.right - regionW)
        val minTop = ceil(crop.top + (height - visibleH) / 2).toInt().coerceIn(crop.top, crop.bottom - regionH)
        val maxTop = floor(crop.top + (height + visibleH) / 2 - regionH).toInt().coerceIn(minTop, crop.bottom - regionH)
        val left = (crop.left + (width - visibleW) / 2 + centerX * visibleW - regionW / 2.0)
            .roundToInt().coerceIn(minLeft, maxLeft)
        val top = (crop.top + (height - visibleH) / 2 + centerY * visibleH - regionH / 2.0)
            .roundToInt().coerceIn(minTop, maxTop)
        return Bounds(left, top, left + regionW, top + regionH)
    }
}
