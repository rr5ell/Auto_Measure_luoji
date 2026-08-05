package com.example.auto_measure_luoji.feature.camera.util

import android.graphics.Rect
import android.util.Log
import com.example.auto_measure_luoji.feature.camera.data.LensCandidate
import com.example.auto_measure_luoji.feature.camera.domain.Camera2Controller

private const val MAX_EQUIV_ZOOM = 24f
private const val MIN_EQUIV_ZOOM_OFFSET = 0.1f

/**
 * 放大逻辑控制器
 *
 * 普通倍率放大统一走 Camera2Controller.setZoomRatio()：
 * API 30+ 优先使用 CONTROL_ZOOM_RATIO，设备不支持时由控制器内部回退 SCALER_CROP_REGION。
 * 锚点居中的 crop 方法仅作为历史实验路径保留。
 * 不操作 TextureView 的 Matrix（旋转由 CameraScreen 的 view 属性处理）
 */
class ZoomController(
    private val cameraController: Camera2Controller
) {
    private var selectedLens: LensCandidate? = null
    private var opticalBase: Float = 1f

    fun setup(selectedLens: LensCandidate, widestLens: LensCandidate?) {
        this.selectedLens = selectedLens
        val rawOpticalBase = if (widestLens != null && widestLens != selectedLens) {
            kotlin.math.round(selectedLens.eq35mm / widestLens.eq35mm)
        } else { 1f }
        this.opticalBase = (rawOpticalBase.coerceAtLeast(1f) + MIN_EQUIV_ZOOM_OFFSET)
            .coerceAtMost(MAX_EQUIV_ZOOM)
    }

    fun getOpticalBase(): Float = opticalBase
    fun getMaxEquivRatio(): Float = MAX_EQUIV_ZOOM

    /**
     * 应用普通倍率放大，不碰 TextureView Matrix。
     */
    fun applyZoom(
        zoomRatio: Float,
        textureView: android.view.TextureView? = null,
        viewWidth: Int = 0,
        viewHeight: Int = 0
    ): ZoomState {
        if (selectedLens == null) return ZoomState(zoomRatio, 1f, false, 1f, opticalBase)
        val clamped = zoomRatio.coerceIn(opticalBase, MAX_EQUIV_ZOOM)
        cameraController.setZoomRatio(clamped)
        return ZoomState(clamped, clamped, false, 1f, opticalBase)
    }

    /**
     * 以锚点为中心放大（通过 crop region 偏移实现，不操作 TextureView Matrix）
     *
     * @param anchor 锚点坐标（1080x1920 输出坐标系）
     * @param zoom 倍率
     * @param sensorOrientation 传感器方向（度）
     */
    fun applyZoomCenteredOnAnchor(
        anchor: com.example.auto_measure_luoji.feature.calibration.model.LaserPoint,
        zoom: Float,
        sensorOrientation: Int,
    ): ZoomState {
        val lens = selectedLens ?: return ZoomState(zoom, 1f, false, 1f, opticalBase)
        val clamped = zoom.coerceIn(opticalBase, MAX_EQUIV_ZOOM)
        val crop = calculateCropRegionCenteredOn(lens, clamped, anchor, sensorOrientation)
        cameraController.setCropRegion(crop)
        return ZoomState(clamped, clamped, false, 1f, opticalBase)
    }

    /**
     * 以锚点为中心放大，9:16 比例裁切（匹配输出画面比例）
     *
     * 与 calculateCropRegionCenteredOn 的区别：
     * - 原版：cropSize = sensor/zoom（4:3 比例），与 9:16 输出不匹配 → 画面拉伸
     * - 此版：cropSize 按 9:16 比例计算，zoom 只作用于宽度方向
     */
    fun applyZoomCenteredOnAnchor916(
        anchor: com.example.auto_measure_luoji.feature.calibration.model.LaserPoint,
        zoom: Float,
        sensorOrientation: Int,
    ): AnchorZoomResult {
        val lens = selectedLens ?: return AnchorZoomResult(ZoomState(zoom, 1f, false, 1f, opticalBase), null)
        val clamped = zoom.coerceIn(opticalBase, MAX_EQUIV_ZOOM)
        val crop = calculateCropRegionCenteredOn916(lens, clamped, anchor, sensorOrientation)
        cameraController.setCropRegion(crop)
        return AnchorZoomResult(ZoomState(clamped, clamped, false, 1f, opticalBase), crop)
    }

    /**
     * 计算以锚点为中心、9:16 比例的裁切区域
     *
     * 输出画面 1080x1920 = 9:16
     * crop 必须也是 9:16，否则画面会被拉伸变形
     *
     * zoom 作用于宽度方向：cropW = sensorW / zoom
     * 高度按比例：cropH = cropW * 16/9
     */
    private fun calculateCropRegionCenteredOn916(
        lens: LensCandidate,
        cameraZoom: Float,
        anchor: com.example.auto_measure_luoji.feature.calibration.model.LaserPoint,
        sensorOrientation: Int,
    ): Rect {
        val array = lens.activeArray
        val sw = array.width().toFloat()
        val sh = array.height().toFloat()

        // 9:16 比例裁切，zoom 作用于宽度
        var cropW = (sw / cameraZoom).toInt()
        var cropH = (cropW * 16.0 / 9.0).toInt()
        // 确保偶数
        cropW = cropW and 0xFFFFFFFE.toInt()
        cropH = cropH and 0xFFFFFFFE.toInt()
        // 如果高度超出 sensor，按高度重新计算
        if (cropH > sh.toInt()) {
            cropH = sh.toInt() and 0xFFFFFFFE.toInt()
            cropW = (cropH * 9.0 / 16.0).toInt() and 0xFFFFFFFE.toInt()
        }

        // 锚点从 1080x1920 输出坐标 → sensor 坐标
        val ox = anchor.x.toFloat()
        val oy = anchor.y.toFloat()

        val sensorX: Float
        
        val sensorY: Float
        when (sensorOrientation) {
            90 -> {
                sensorX = sh - oy / 1920f * sh
                sensorY = ox / 1080f * sw
            }
            270 -> {
                sensorX = oy / 1920f * sh
                sensorY = sw - ox / 1080f * sw
            }
            180 -> {
                sensorX = sw - ox / 1080f * sw
                sensorY = sh - oy / 1920f * sh
            }
            else -> {
                sensorX = ox / 1080f * sw
                sensorY = oy / 1920f * sh
            }
        }

        // 以锚点为中心
        val halfW = cropW / 2
        val halfH = cropH / 2
        var left = (sensorX.toInt() - halfW)
        var top = (sensorY.toInt() - halfH)

        // clamp 到 sensor 边界
        left = left.coerceIn(array.left, array.right - cropW)
        top = top.coerceIn(array.top, array.bottom - cropH)

        Log.d("ZoomCtrl", ">>> 916 anchor output=(${anchor.x}, ${anchor.y}), sensor=($sensorX, $sensorY)")
        Log.d("ZoomCtrl", ">>> 916 activeArray=[${array.left},${array.top},${array.right},${array.bottom}]")
        Log.d("ZoomCtrl", ">>> 916 cropSize=${cropW}x$cropH, cropRect=[$left,$top,${left+cropW},${top+cropH}]")
        Log.d("ZoomCtrl", ">>> 916 zoom=$cameraZoom, orient=$sensorOrientation")

        return Rect(left, top, left + cropW, top + cropH)
    }

    /**
     * 计算锚点在 crop 后屏幕上的偏移量
     *
     * 当 crop 被 sensor 边界 clamp 后，锚点不会出现在屏幕中心。
     * 返回 (offsetX, offsetY)：正值 = 锚点偏左/偏上，需要 wrapper 向右/下平移
     *
     * @return Pair(offsetX, offsetY) 单位：屏幕像素（1080x1920 坐标系）
     */
    fun calculateAnchorScreenOffset(
        anchor: com.example.auto_measure_luoji.feature.calibration.model.LaserPoint,
        crop: Rect,
        sensorOrientation: Int,
    ): Pair<Float, Float> {
        val lens = selectedLens ?: return Pair(0f, 0f)
        val array = lens.activeArray
        val sw = array.width().toFloat()
        val sh = array.height().toFloat()
        val cw = crop.width().toFloat()
        val ch = crop.height().toFloat()
        val cl = crop.left.toFloat()
        val ct = crop.top.toFloat()
        val ox = anchor.x.toFloat()
        val oy = anchor.y.toFloat()

        val screenX: Float
        val screenY: Float
        when (sensorOrientation) {
            90 -> {
                screenX = (sh - oy / 1920f * sh - cl) / cw * 1080f
                screenY = (ox / 1080f * sw - ct) / ch * 1920f
            }
            270 -> {
                screenX = (oy / 1920f * sh - cl) / cw * 1080f
                screenY = (sw - ox / 1080f * sw - ct) / ch * 1920f
            }
            180 -> {
                screenX = (sw - ox / 1080f * sw - cl) / cw * 1080f
                screenY = (sh - oy / 1920f * sh - ct) / ch * 1920f
            }
            else -> {
                screenX = (ox / 1080f * sw - cl) / cw * 1080f
                screenY = (oy / 1920f * sh - ct) / ch * 1920f
            }
        }

        val offsetX = 540f - screenX
        val offsetY = 960f - screenY
        Log.d("ZoomCtrl", ">>> anchorScreenOffset: screen=($screenX, $screenY), offset=($offsetX, $offsetY)")
        return Pair(offsetX, offsetY)
    }

    /**
     * 计算以指定锚点为中心的裁切区域
     *
     * 锚点在 1080x1920 输出坐标中，需要反算到 sensor 坐标
     */
    private fun calculateCropRegionCenteredOn(
        lens: LensCandidate,
        cameraZoom: Float,
        anchor: com.example.auto_measure_luoji.feature.calibration.model.LaserPoint,
        sensorOrientation: Int,
    ): Rect {
        val array = lens.activeArray
        val sw = array.width().toFloat()
        val sh = array.height().toFloat()

        // 裁切区域尺寸 = sensor / zoom
        val cropW = ((sw / cameraZoom).toInt() and 0xFFFFFFFE.toInt())
        val cropH = ((sh / cameraZoom).toInt() and 0xFFFFFFFE.toInt())

        // 锚点从 1080x1920 输出坐标 → sensor 坐标
        // 输出到 sensor 的反向映射取决于 sensorOrientation
        val ox = anchor.x.toFloat()  // 输出坐标 X (0..1080)
        val oy = anchor.y.toFloat()  // 输出坐标 Y (0..1920)

        val sensorX: Float
        val sensorY: Float
        when (sensorOrientation) {
            90 -> {
                sensorX = sh - oy / 1920f * sh
                sensorY = ox / 1080f * sw
            }
            270 -> {
                sensorX = oy / 1920f * sh
                sensorY = sw - ox / 1080f * sw
            }
            180 -> {
                sensorX = sw - ox / 1080f * sw
                sensorY = sh - oy / 1920f * sh
            }
            else -> {
                sensorX = ox / 1080f * sw
                sensorY = oy / 1920f * sh
            }
        }

        // 以锚点为中心，clamp 到 sensor 边界
        val halfW = cropW / 2
        val halfH = cropH / 2
        var left = (sensorX.toInt() - halfW)
        var top = (sensorY.toInt() - halfH)

        // 确保裁切区域不超出 sensor
        left = left.coerceIn(array.left, array.right - cropW)
        top = top.coerceIn(array.top, array.bottom - cropH)

        Log.d("ZoomCtrl", ">>> anchor output=(${anchor.x}, ${anchor.y}), sensor=($sensorX, $sensorY)")
        Log.d("ZoomCtrl", ">>> activeArray=[${array.left},${array.top},${array.right},${array.bottom}]")
        Log.d("ZoomCtrl", ">>> cropSize=${cropW}x$cropH, cropRect=[$left,$top,${left+cropW},${top+cropH}]")
        Log.d("ZoomCtrl", ">>> zoom=$cameraZoom, orient=$sensorOrientation")

        return Rect(left, top, left + cropW, top + cropH)
    }
}

data class ZoomState(
    val equivRatio: Float,
    val cameraRatio: Float,
    val isVirtualZoom: Boolean,
    val virtualScale: Float,
    val opticalBase: Float
)

data class AnchorZoomResult(
    val zoomState: ZoomState,
    val cropRect: android.graphics.Rect?
)
