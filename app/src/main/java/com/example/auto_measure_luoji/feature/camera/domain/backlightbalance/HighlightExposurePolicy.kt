package com.example.auto_measure_luoji.feature.camera.domain.backlightbalance

import com.example.auto_measure_luoji.feature.camera.domain.imageadjustment.ExposureCompensationRange
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/** 仅下调相对于手动 EV 的补偿；迟滞、连续确认及稳定期避免追逐 AE 的暂态。 */
class HighlightExposurePolicy(private val range: ExposureCompensationRange, manualEv: Float) {
    private val baseline = range.indexFor(manualEv)
    private val stepCount = if (range.supported) maxOf(1, ceil((1f / 3f) / range.stepEv - 0.001f).toInt()) else 1
    private val lower = if (range.supported)
        maxOf(range.minimum, baseline - floor((2.0 / 3.0 + 0.0001) / range.stepEv).toInt()) else baseline
    // 仅凭阈值占比会在 234/235 两侧来回变档。恢复还要求 P99 留出一档余量。
    // 以有限范围黑端 16、白端 235 估计：T=16+219/2^(本次 EV 步幅)-8。
    // 编码 Y 并非线性辐射量，此式是保守迟滞门槛而非曝光预测；常见 1/3 EV 时约 182。
    private val recoveryCeiling = if (range.supported)
        floor(16 + 219 / 2.0.pow((stepCount * range.stepEv).toDouble()) - 8).toInt() else 0
    private var current = baseline
    private var lastSample = Long.MIN_VALUE
    private var settleUntil = Long.MIN_VALUE
    private var direction = 0
    private var confirmations = 0
    var subjectProtected = false
        private set
    val ev: Float get() = current * range.stepEv
    val correctionEv: Float get() = (current - baseline) * range.stepEv

    fun pause(nowNs: Long) {
        confirmations = 0
        direction = 0
        settleUntil = nowNs + 1_500_000_000L
    }

    fun update(sample: HighlightStatistics?, nowNs: Long): Float? {
        if (!range.supported) return null
        if (sample == null || nowNs < sample.sampledAtNs || nowNs - sample.sampledAtNs > 1_500_000_000L ||
            !sample.brightFraction.isFinite() || sample.brightFraction !in 0f..1f || sample.percentile99 !in 0..255 ||
            sample.centerDarkLuma !in 0..255) {
            confirmations = 0
            direction = 0
            return null
        }
        if (sample.sampledAtNs <= lastSample) return null
        lastSample = sample.sampledAtNs
        if (sample.sampledAtNs < settleUntil) return null
        // 中心 P20<64 暂停减曝，>=96 才退出保护，避免亮度跨一档就往返切换。
        // <40 时优先撤销已经施加的负补偿，即使天空仍很亮；永不超过手动基线。
        // 这是 SDR Y 编码域的经验阈值，不是照度或 RAW 信噪比估计。
        if (sample.centerDarkLuma < 64) subjectProtected = true
        else if (sample.centerDarkLuma >= 96) subjectProtected = false
        // >2% 高亮才减曝，小太阳点不单独触发；<0.5% 才缓慢回到手动基线。
        val nextDirection = when {
            sample.centerDarkLuma < 40 && current < baseline -> 1
            !subjectProtected && sample.brightFraction > 0.02f && current > lower -> -1
            sample.brightFraction < 0.005f && sample.percentile99 < recoveryCeiling && current < baseline -> 1
            else -> 0
        }
        confirmations = if (nextDirection != 0 && nextDirection == direction) confirmations + 1
            else if (nextDirection != 0) 1 else 0
        direction = nextDirection
        if (confirmations < 3) return null
        current = (current + direction * stepCount).coerceIn(lower, baseline)
        confirmations = 0
        direction = 0
        settleUntil = nowNs + 1_500_000_000L
        return ev
    }
}
