package com.example.auto_measure_luoji.feature.camera.domain.backlightbalance

import com.example.auto_measure_luoji.feature.camera.domain.imageadjustment.ExposureCompensationRange
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.ln
import kotlin.math.max

/** 五区统计控制整幅采集曝光；手动 EV 为基线，允许受限的正负补偿。 */
class HighlightExposurePolicy(private val range: ExposureCompensationRange, manualEv: Float) {
    private val baseline = range.indexFor(manualEv)
    private val stepCount = if (range.supported) maxOf(1, ceil((1f / 3f) / range.stepEv - 0.001f).toInt()) else 1
    private val lower = if (range.supported)
        maxOf(range.minimum, baseline - floor((2.0 / 3.0 + 0.0001) / range.stepEv).toInt()) else baseline
    private val upper = if (range.supported)
        minOf(range.maximum, baseline + floor((1.0 + 0.0001) / range.stepEv).toInt()) else baseline
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
    var decisionText = "等待采样"
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
            sample.centerDarkLuma !in 0..255 || sample.regions?.valid != true) {
            confirmations = 0
            direction = 0
            decisionText = "等待有效分区"
            return null
        }
        if (sample.sampledAtNs <= lastSample) return null
        lastSample = sample.sampledAtNs
        if (sample.sampledAtNs < settleUntil) { decisionText = "等待曝光稳定"; return null }
        // 中心 P20<64 暂停减曝，>=96 才退出保护，避免亮度跨一档就往返切换。
        // <40 时优先撤销已有负补偿；超过手动基线的提亮由五区综合亮度决定。
        // 这是 SDR Y 编码域的经验阈值，不是照度或 RAW 信噪比估计。
        if (sample.centerDarkLuma < 64) subjectProtected = true
        else if (sample.centerDarkLuma >= 96) subjectProtected = false
        val regions = sample.regions
        // e=log2(112/G) 是编码亮度的反馈误差，不是由 ISO/快门反推的物理 EV。
        // 每次仅沿误差方向移动一个小档并等待新图反馈，避免把非线性 ISP 当线性传感器。
        // 112 是经验中间调目标，可有明暗场景偏差；死区至少 +/-0.35，粗档时相应扩大。
        val error = ln(112f / regions.weightedLuma) / ln(2f)
        val deadband = max(0.35f, stepCount * range.stepEv * 0.6f)
        val lacksRecoveryHeadroom = current < baseline && sample.percentile99 >= recoveryCeiling
        val nextDirection = when {
            sample.centerDarkLuma < 40 && current < baseline -> 1
            error > deadband && regions.broadClipping -> 0
            error > deadband && lacksRecoveryHeadroom -> 0
            error > deadband && current < upper -> 1
            (error < -deadband || regions.broadClipping) && !subjectProtected && current > lower -> -1
            else -> 0
        }
        decisionText = when {
            nextDirection > 0 -> if (current < baseline && sample.centerDarkLuma < 40) "恢复暗部" else "分区提亮"
            nextDirection < 0 -> "分区降曝"
            error > deadband && regions.broadClipping -> "反差超限"
            error > deadband && lacksRecoveryHeadroom -> "保留高光余量"
            error > deadband && current >= upper -> "已达提亮上限"
            (error < -deadband || regions.broadClipping) && subjectProtected -> "中心暗部保护"
            error < -deadband && current <= lower -> "已达降曝下限"
            else -> "保持曝光"
        }
        confirmations = if (nextDirection != 0 && nextDirection == direction) confirmations + 1
            else if (nextDirection != 0) 1 else 0
        direction = nextDirection
        if (confirmations < 3) return null
        current = (current + direction * stepCount).coerceIn(lower, upper)
        confirmations = 0
        direction = 0
        settleUntil = nowNs + 1_500_000_000L
        return ev
    }
}
