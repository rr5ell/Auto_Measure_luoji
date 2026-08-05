package com.example.auto_measure.feature.camera.data

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
 * @param maxDigitalZoom  最大数码变焦倍率
 * @param zoomRatioRange  逻辑 zoom 范围（CONTROL_ZOOM_RATIO）
 * @param oisSupported    是否支持光学防抖（LENS_OPTICAL_STABILIZATION_MODE_ON）
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
    val eq35mm: Float,
    val category: LensCategory
)
