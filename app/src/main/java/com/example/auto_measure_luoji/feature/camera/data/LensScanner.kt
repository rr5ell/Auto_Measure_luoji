package com.example.auto_measure_luoji.feature.camera.data

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import kotlin.math.sqrt

/**
 * 第一步：扫描所有后置镜头
 *
 * 流程：
 * 1. 遍历 CameraManager.cameraIdList
 * 2. 过滤 LENS_FACING_BACK
 * 3. 对每个逻辑镜头，读取 physicalCameraIds（物理子镜头）
 * 4. 对每个物理镜头读取独立的 CameraCharacteristics
 * 5. 计算 eq35mm 并分类
 *
 * 输出：List<LensCandidate>
 */
class LensScanner(private val context: Context) {

    companion object {
        // 35mm 对角线常量：sqrt(36² + 24²) = 43.266mm
        private const val FULL_FRAME_DIAGONAL = 43.266f
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /**
     * 扫描所有后置镜头，返回候选列表
     */
    fun scan(): List<LensCandidate> {
        val candidates = mutableListOf<LensCandidate>()

        for (cameraId in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(cameraId)

            // 优先只要后置镜头。部分机型会把独立长焦错误标成非后置，
            // 因此保留“非后置但物理焦距明显偏长”的兜底候选。
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val primaryFocalLength = focalLengths?.firstOrNull() ?: 0f
            val isBackFacing = facing == CameraMetadata.LENS_FACING_BACK
            val isSuspectedRearTelephoto = !isBackFacing && primaryFocalLength > 4f
            if (!isBackFacing && !isSuspectedRearTelephoto) continue

            // 获取物理子镜头集合（多摄系统）
            val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                chars.physicalCameraIds
            } else {
                emptySet()
            }

            if (physicalIds.isNotEmpty()) {
                // 逻辑镜头包含多个物理子镜头 → 逐个读取
                for (physicalId in physicalIds) {
                    val physicalChars = cameraManager.getCameraCharacteristics(physicalId)
                    val candidate = buildCandidate(
                        logicalCameraId = cameraId,
                        physicalId = physicalId,
                        chars = physicalChars
                    )
                    if (candidate != null) candidates.add(candidate)
                }
            } else {
                // 单一镜头（无物理子镜头）
                val candidate = buildCandidate(
                    logicalCameraId = cameraId,
                    physicalId = null,
                    chars = chars
                )
                if (candidate != null) candidates.add(candidate)
            }
        }

        return candidates
    }

    /**
     * 从 CameraCharacteristics 构建 LensCandidate
     */
    private fun buildCandidate(
        logicalCameraId: String,
        physicalId: String?,
        chars: CameraCharacteristics
    ): LensCandidate? {
        // 焦距数组
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        if (focalLengths == null || focalLengths.isEmpty()) return null
        val focalLength = focalLengths[0] // 取第一个

        // 传感器物理尺寸
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val sensorWidth = sensorSize.width   // mm
        val sensorHeight = sensorSize.height  // mm

        // 活跃像素区域
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null

        // 最大数码变焦
        val maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

        // 逻辑 zoom 范围
        val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                ?: android.util.Range(1f, 1f)
        } else {
            android.util.Range(1f, 1f)
        }

        val oisSupported = chars
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

        // 计算等效 35mm 焦距
        // 公式：eq35mm = focalLength × 43.266 / sqrt(sensorWidth² + sensorHeight²)
        val sensorDiagonal = sqrt(sensorWidth * sensorWidth + sensorHeight * sensorHeight)
        val eq35mm = focalLength * FULL_FRAME_DIAGONAL / sensorDiagonal

        // 分类
        val category = when {
            eq35mm >= 45f -> LensCategory.TELE
            eq35mm >= 20f -> LensCategory.WIDE
            else -> LensCategory.ULTRA_WIDE
        }

        return LensCandidate(
            cameraId = logicalCameraId,
            physicalId = physicalId,
            focalLength = focalLength,
            sensorWidth = sensorWidth,
            sensorHeight = sensorHeight,
            activeArray = activeArray,
            maxDigitalZoom = maxDigitalZoom,
            zoomRatioRange = zoomRange,
            oisSupported = oisSupported,
            eq35mm = eq35mm,
            category = category
        )
    }
}
