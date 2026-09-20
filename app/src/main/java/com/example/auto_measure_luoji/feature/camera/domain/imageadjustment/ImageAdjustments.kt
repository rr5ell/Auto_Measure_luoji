package com.example.auto_measure_luoji.feature.camera.domain.imageadjustment

import kotlin.math.roundToInt

data class ImageAdjustments(val ev: Float = 0f, val contrast: Float = 1f) {
    fun sanitized() = ImageAdjustments(
        ev.takeIf { it.isFinite() } ?: 0f,
        contrast.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f,
    )

    fun forCalibration(active: Boolean) = if (active) ImageAdjustments(-2f, 1f) else sanitized()
}

/** 相机补偿范围以整数档位表示，不能假设每档等于 1 EV。 */
data class ExposureCompensationRange(val minimum: Int = 0, val maximum: Int = 0, val stepEv: Float = 0f) {
    val supported: Boolean get() = minimum < maximum && minimum <= 0 && maximum >= 0 &&
        stepEv.isFinite() && stepEv > 0f && (minimum * stepEv).isFinite() && (maximum * stepEv).isFinite()

    fun indexFor(ev: Float): Int {
        if (!supported || !ev.isFinite()) return 0
        // EV = 档位 * 每档 EV；先限制到硬件范围，再四舍五入到可提交的整数档。
        return (ev.toDouble() / stepEv).coerceIn(minimum.toDouble(), maximum.toDouble()).roundToInt()
    }

    fun evFor(ev: Float): Float = if (supported) indexFor(ev) * stepEv else 0f
}
