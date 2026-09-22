package com.example.auto_measure_luoji.feature.camera.domain.tapmetering

/** 未旋转预览缓冲区内的归一化位置；仅当前视场有效，不持久化目标坐标。 */
data class TapMeteringPoint(val x: Float, val y: Float) {
    val valid: Boolean get() = x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f

    companion object {
        const val PREFERENCE_KEY = "tap_exposure_metering_enabled"
    }
}
