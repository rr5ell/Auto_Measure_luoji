package com.example.auto_measure_luoji.feature.calibration.domain

import com.example.auto_measure_luoji.feature.calibration.model.CalibrationParams
import com.example.auto_measure_luoji.feature.calibration.model.LaserPoint
import com.example.auto_measure_luoji.feature.calibration.model.PredictionCalculator
import com.example.auto_measure_luoji.feature.calibration.model.Sample
import kotlin.math.abs

class DualCalibrationManager {

    enum class State {
        IDLE,
        WAIT_DISTANCE,
        WAIT_WIDE,
        WAIT_TELE,
        CALIBRATED,
    }

    var state: State = State.IDLE
        private set

    private val wideSamples = mutableListOf<Sample>()
    private val teleSamples = mutableListOf<Sample>()

    var pendingDistance: Double? = null
        private set

    var wideCalibration: CalibrationParams? = null
        private set

    var teleCalibration: CalibrationParams? = null
        private set

    var wide100mPoint: LaserPoint? = null
        private set

    var tele100mPoint: LaserPoint? = null
        private set

    fun start() {
        wideSamples.clear()
        teleSamples.clear()
        pendingDistance = null
        wideCalibration = null
        teleCalibration = null
        wide100mPoint = null
        tele100mPoint = null
        state = State.WAIT_DISTANCE
    }

    fun reset() {
        wideSamples.clear()
        teleSamples.clear()
        pendingDistance = null
        wideCalibration = null
        teleCalibration = null
        wide100mPoint = null
        tele100mPoint = null
        state = State.IDLE
    }

    fun inputDistance(distanceMeters: Double): Result<Unit> {
        if (distanceMeters <= 0.0) {
            return Result.failure(IllegalArgumentException("距离必须大于 0"))
        }
        if (state == State.WAIT_WIDE || state == State.WAIT_TELE) {
            return Result.failure(IllegalStateException("请先完成当前距离的广角/长焦采集"))
        }
        if (wideSamples.size == 1 && abs(distanceMeters - wideSamples.first().distanceMeters) < 1e-6) {
            return Result.failure(IllegalArgumentException("d2 不能等于 d1"))
        }
        if (wideSamples.size >= 2 || teleSamples.size >= 2) {
            return Result.failure(IllegalStateException("双镜头校准已经完成"))
        }

        pendingDistance = distanceMeters
        state = State.WAIT_WIDE
        return Result.success(Unit)
    }

    fun recordWidePoint(point: LaserPoint, wideZoom: Double): Result<Unit> {
        val distance = pendingDistance
            ?: return Result.failure(IllegalStateException("请先输入距离"))
        if (state != State.WAIT_WIDE) {
            return Result.failure(IllegalStateException("当前步骤不是广角采集"))
        }
        if (wideSamples.size >= 2) {
            return Result.failure(IllegalStateException("广角样本已满"))
        }

        wideSamples += Sample(distanceMeters = distance, point = point, zoom = wideZoom)
        state = State.WAIT_TELE
        return Result.success(Unit)
    }

    fun recordTelePoint(point: LaserPoint): Result<Unit> {
        val distance = pendingDistance
            ?: return Result.failure(IllegalStateException("请先输入距离"))
        if (state != State.WAIT_TELE) {
            return Result.failure(IllegalStateException("当前步骤不是长焦采集"))
        }
        if (teleSamples.size >= 2) {
            return Result.failure(IllegalStateException("长焦样本已满"))
        }

        teleSamples += Sample(distanceMeters = distance, point = point, zoom = 1.0)
        pendingDistance = null

        state = if (wideSamples.size == 2 && teleSamples.size == 2) {
            computeCalibration().getOrElse { return Result.failure(it) }
            State.CALIBRATED
        } else {
            State.WAIT_DISTANCE
        }

        return Result.success(Unit)
    }

    fun predictAtDistance(targetDistance: Double): Result<DualTargetPrediction> {
        val wide = wideCalibration
            ?: return Result.failure(IllegalStateException("缺少广角标定参数"))
        if (targetDistance <= 0.0) {
            return Result.failure(IllegalArgumentException("目标距离必须大于 0"))
        }

        val widePoint = PredictionCalculator.predict1xCoordinateAtDistance(wide, targetDistance)
        val telePoint = teleCalibration?.let {
            PredictionCalculator.predict1xCoordinateAtDistance(it, targetDistance)
        }

        return Result.success(DualTargetPrediction(widePoint1x = widePoint, telePoint = telePoint))
    }

    fun getStatusText(telephotoMag: Int): String = when (state) {
        State.IDLE -> "双镜头校准未开始"
        State.WAIT_DISTANCE -> "输入 d${wideSamples.size + 1}，随后采集广角 5x 和长焦 ${telephotoMag}x"
        State.WAIT_WIDE -> "d=${pendingDistanceText()}m：采集广角 5x 绿光坐标"
        State.WAIT_TELE -> "d=${pendingDistanceText()}m：采集长焦 ${telephotoMag}x 绿光坐标"
        State.CALIBRATED -> "双镜头校准完成：广角/长焦各 ${wideSamples.size} 个样本"
    }

    fun getActionText(telephotoMag: Int): String = when (state) {
        State.WAIT_DISTANCE, State.IDLE -> "输入 d${wideSamples.size + 1}"
        State.WAIT_WIDE -> "采集广角 5x"
        State.WAIT_TELE -> "采集长焦 ${telephotoMag}x"
        State.CALIBRATED -> "已完成"
    }

    fun getSampleText(): String = "广角 ${wideSamples.size}/2，长焦 ${teleSamples.size}/2"

    private fun computeCalibration(): Result<Unit> {
        if (wideSamples.size < 2 || teleSamples.size < 2) {
            return Result.failure(IllegalStateException("样本不足"))
        }

        val ws1 = wideSamples[0]
        val ws2 = wideSamples[1]
        val wideZoom = (ws1.zoom + ws2.zoom) / 2.0
        val wideParams = PredictionCalculator.buildCalibration(
            firstDistance = ws1.distanceMeters,
            firstLaserPoint = ws1.point,
            secondDistance = ws2.distanceMeters,
            secondLaserPoint = ws2.point,
            currentZoom = wideZoom,
        )

        val ts1 = teleSamples[0]
        val ts2 = teleSamples[1]
        val teleParams = PredictionCalculator.buildCalibration(
            firstDistance = ts1.distanceMeters,
            firstLaserPoint = ts1.point,
            secondDistance = ts2.distanceMeters,
            secondLaserPoint = ts2.point,
            currentZoom = 1.0,
        )

        wideCalibration = wideParams
        teleCalibration = teleParams
        wide100mPoint = PredictionCalculator.predict1xCoordinateAtDistance(wideParams, 100.0)
        tele100mPoint = PredictionCalculator.predict1xCoordinateAtDistance(teleParams, 100.0)

        return Result.success(Unit)
    }

    private fun pendingDistanceText(): String = pendingDistance?.let { "%.2f".format(it) } ?: "-"
}

data class DualTargetPrediction(
    val widePoint1x: LaserPoint,
    val telePoint: LaserPoint?,
)
