package com.example.auto_measure.feature.calibration.domain

import com.example.auto_measure.feature.calibration.detector.LaserDetector
import com.example.auto_measure.feature.calibration.model.*
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 校准管理器
 *
 * 状态机：IDLE → SAMPLE_1 → SAMPLE_2 → CALIBRATED
 *
 * 流程：
 * 1. 用户输入距离 → 采集第 1 次（拍照 → 绿光识别 → 记录 Sample）
 * 2. 用户输入距离 → 采集第 2 次
 * 3. 自动标定 → 得到 CalibrationParams
 * 4. 用户输入目标距离 → 预测 100m 坐标
 * 5. 应用变换：以预测坐标为中心放大预览
 */
class CalibrationManager {

    enum class State {
        IDLE,          // 未开始
        SAMPLE_1,      // 等待第 1 次采集
        SAMPLE_2,      // 等待第 2 次采集
        CALIBRATED,    // 已标定
    }

    var state: State = State.IDLE
        private set

    private val samples = mutableListOf<Sample>()
    var calibrationParams: CalibrationParams? = null
        private set

    var lastDetectedPoint: LaserPoint? = null
        private set

    /** 重置 */
    fun reset() {
        state = State.IDLE
        samples.clear()
        calibrationParams = null
        lastDetectedPoint = null
    }

    /** 开始校准流程 */
    fun startCalibration() {
        reset()
        state = State.SAMPLE_1
    }

    /**
     * 采集样本
     *
     * @param snapshotBytes    已归一化预览帧的图片 bytes（PNG/JPEG 等 BitmapFactory 支持格式）
     * @param distanceMeters  目标距离（米）
     * @param currentZoom     当前镜头倍率
     * @return 检测到的激光点，null 表示未检测到
     */
    suspend fun collectSample(
        snapshotBytes: ByteArray,
        distanceMeters: Double,
        currentZoom: Double,
    ): LaserResult? = withContext(Dispatchers.Default) {
        // 绿光检测
        val result = LaserDetector.detectLaserCenterFromBytes(snapshotBytes)

        if (result != null) {
            val point = LaserPoint(result.cx, result.cy)
            lastDetectedPoint = point

            samples.add(Sample(
                distanceMeters = distanceMeters,
                point = point,
                zoom = currentZoom,
            ))

            // 推进状态
            state = when (state) {
                State.SAMPLE_1 -> State.SAMPLE_2
                State.SAMPLE_2 -> {
                    // 两次采集完成，自动标定
                    computeCalibration()
                    State.CALIBRATED
                }
                else -> state
            }
        }

        result
    }

    /** 计算标定参数 */
    private fun computeCalibration() {
        if (samples.size < 2) return
        val s1 = samples[0]
        val s2 = samples[1]

        Log.d("CalMgr", "样本1: 距离=${s1.distanceMeters}m, 坐标=(${s1.point.x}, ${s1.point.y}), 倍率=${s1.zoom}x")
        Log.d("CalMgr", "样本2: 距离=${s2.distanceMeters}m, 坐标=(${s2.point.x}, ${s2.point.y}), 倍率=${s2.zoom}x")

        calibrationParams = PredictionCalculator.buildCalibration(
            firstDistance = s1.distanceMeters,
            firstLaserPoint = s1.point,
            secondDistance = s2.distanceMeters,
            secondLaserPoint = s2.point,
            currentZoom = s1.zoom, // 两次采集应使用相同倍率
        )

        calibrationParams?.let {
            Log.d("CalMgr", "标定参数: ax=${it.ax}, bx=${it.bx}, ay=${it.ay}, by=${it.by}, 倍率=${it.calibrationZoom}x")
            // 打印 100m 预测坐标，方便验证
            val predicted = predictAtDistance(100.0)
            if (predicted != null) {
                Log.d("CalMgr", "预测100m坐标: x=${predicted.x}, y=${predicted.y}")
            }
        }
    }

    /**
     * 预测目标距离下的 1x 坐标
     *
     * @param targetDistance 目标距离（米），通常为 100
     * @return 1x 倍率下的预测坐标
     */
    fun predictAtDistance(targetDistance: Double): LaserPoint? {
        val params = calibrationParams ?: return null
        return PredictionCalculator.predict1xCoordinateAtDistance(params, targetDistance)
    }

    fun getSampleCount(): Int = samples.size

    /** 获取采集状态描述 */
    fun getStatusText(): String = when (state) {
        State.IDLE -> "点击校准按钮开始"
        State.SAMPLE_1 -> "第 1 次采集：对准目标，输入距离，点击采集"
        State.SAMPLE_2 -> "第 2 次采集：换个距离，再次对准目标"
        State.CALIBRATED -> "已标定（${samples.size} 个样本）"
    }
}
