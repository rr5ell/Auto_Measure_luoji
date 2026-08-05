package com.example.auto_measure_luoji.feature.calibration.model

import kotlin.math.max

object PredictionCalculator {

    private const val OUT_W = 1080.0
    private const val OUT_H = 1920.0
    private const val TARGET_DISTANCE_100M = 100.0

    /**
     * 你要求的 5 参数函数：
     * 第一次距离
     * 第一次绿色激光坐标
     * 第二次距离
     * 第二次绿色激光坐标
     * 当前倍数
     *
     * 返回：100m 下 1x 的坐标
     */
    fun calculate100m1xCoordinate(
        firstDistance: Double,
        firstLaserPoint: LaserPoint,
        secondDistance: Double,
        secondLaserPoint: LaserPoint,
        currentZoom: Double,
    ): LaserPoint {
        require(firstDistance > 0.0) { "firstDistance 必须 > 0" }
        require(secondDistance > 0.0) { "secondDistance 必须 > 0" }
        require(firstDistance != secondDistance) { "两次距离不能相同" }
        require(currentZoom > 0.0) { "currentZoom 必须 > 0" }

        val cx = OUT_W / 2.0
        val cy = OUT_H / 2.0

        val dx1 = firstLaserPoint.x - cx
        val dx2 = secondLaserPoint.x - cx
        val dy1 = firstLaserPoint.y - cy
        val dy2 = secondLaserPoint.y - cy

        val ax = (dx1 - dx2) * firstDistance * secondDistance / (secondDistance - firstDistance)
        val bx = dx1 - ax / firstDistance
        val ay = (dy1 - dy2) * firstDistance * secondDistance / (secondDistance - firstDistance)
        val by = dy1 - ay / firstDistance

        val dxAtCurrentZoom = ax / TARGET_DISTANCE_100M + bx
        val dyAtCurrentZoom = ay / TARGET_DISTANCE_100M + by

        val zoom = max(currentZoom, 1e-6)
        val dxAt1x = dxAtCurrentZoom / zoom
        val dyAt1x = dyAtCurrentZoom / zoom

        return LaserPoint(
            x = (cx + dxAt1x).coerceIn(0.0, OUT_W - 1.0),
            y = (cy + dyAt1x).coerceIn(0.0, OUT_H - 1.0),
        )
    }

    fun buildCalibration(
        firstDistance: Double,
        firstLaserPoint: LaserPoint,
        secondDistance: Double,
        secondLaserPoint: LaserPoint,
        currentZoom: Double,
    ): CalibrationParams {
        require(firstDistance > 0.0)
        require(secondDistance > 0.0)
        require(firstDistance != secondDistance)
        require(currentZoom > 0.0)

        val cx = OUT_W / 2.0
        val cy = OUT_H / 2.0

        val dx1 = firstLaserPoint.x - cx
        val dx2 = secondLaserPoint.x - cx
        val dy1 = firstLaserPoint.y - cy
        val dy2 = secondLaserPoint.y - cy

        val ax = (dx1 - dx2) * firstDistance * secondDistance / (secondDistance - firstDistance)
        val bx = dx1 - ax / firstDistance
        val ay = (dy1 - dy2) * firstDistance * secondDistance / (secondDistance - firstDistance)
        val by = dy1 - ay / firstDistance

        return CalibrationParams(ax, bx, ay, by, currentZoom)
    }

    fun predict1xCoordinateAtDistance(
        params: CalibrationParams,
        targetDistance: Double,
        outW: Double = OUT_W,
        outH: Double = OUT_H,
    ): LaserPoint {
        require(targetDistance > 0.0)

        val cx = outW / 2.0
        val cy = outH / 2.0

        val dxCal = params.ax / targetDistance + params.bx
        val dyCal = params.ay / targetDistance + params.by
        val zoom = max(params.calibrationZoom, 1e-6)

        return LaserPoint(
            x = (cx + dxCal / zoom).coerceIn(0.0, outW - 1.0),
            y = (cy + dyCal / zoom).coerceIn(0.0, outH - 1.0),
        )
    }
}