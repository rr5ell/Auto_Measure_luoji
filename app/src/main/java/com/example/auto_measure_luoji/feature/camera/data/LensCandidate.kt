package com.example.auto_measure_luoji.feature.camera.data

/**
 * 镜头分类：基于等效 35mm 焦距
 */
enum class LensCategory {
    ULTRA_WIDE, // eq35mm < 20
    WIDE,       // 20 <= eq35mm < 45
    TELE        // eq35mm >= 45
}

/**
 * 单个镜头的完整信息
 *
 * @param cameraId        逻辑相机 ID（openCamera 用）
 * @param physicalId      物理子镜头 ID（物理绑定会话用），逻辑镜头为空
 * @param focalLength     实际焦距（mm）
 * @param sensorWidth     传感器物理宽度（mm）
 * @param sensorHeight    传感器物理高度（mm）
 * @param activeArray     活跃像素区域（用于裁切计算）
 * @param maxDigitalZoom  当前镜头特征中的最大数码变焦倍率
 * @param zoomRatioRange  当前镜头特征中的 zoom 范围（CONTROL_ZOOM_RATIO）
 * @param oisSupported    当前镜头是否支持光学防抖（LENS_OPTICAL_STABILIZATION_MODE_ON）
 * @param logicalActiveArray     父逻辑相机活跃像素区域，普通逻辑相机预览裁切使用
 * @param logicalMaxDigitalZoom  父逻辑相机最大数码变焦倍率，普通逻辑相机预览使用
 * @param logicalZoomRatioRange  父逻辑相机 CONTROL_ZOOM_RATIO 范围，普通逻辑相机预览使用
 * @param logicalOisSupported    父逻辑相机是否支持 OIS，普通逻辑相机预览使用
 * @param eq35mm          等效 35mm 焦距
 * @param category        镜头分类
 */
data class LensCandidate(
    val cameraId: String,
    val physicalId: String?,
    val focalLength: Float,
    val sensorWidth: Float,
    val sensorHeight: Float,
    val activeArray: android.graphics.Rect,
    val maxDigitalZoom: Float,
    val zoomRatioRange: android.util.Range<Float>,
    val oisSupported: Boolean,
    val logicalActiveArray: android.graphics.Rect = activeArray,
    val logicalMaxDigitalZoom: Float = maxDigitalZoom,
    val logicalZoomRatioRange: android.util.Range<Float> = zoomRatioRange,
    val logicalOisSupported: Boolean = oisSupported,
    val eq35mm: Float,
    val category: LensCategory
)
