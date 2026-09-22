package com.example.auto_measure_luoji.feature.camera.domain.backlightbalance

import com.example.auto_measure_luoji.feature.camera.domain.imageadjustment.ExposureCompensationRange
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.ln
import kotlin.math.max

/** 五区统计控制整幅采集曝光；手动 EV 为基线，允许受限的正负补偿。 */
class HighlightExposurePolicy(
    private val range: ExposureCompensationRange,
    manualEv: Float,
    private val settings: BacklightBalanceSettings = BacklightBalanceSettings(),
) {
    init { require(settings.validationError == null) }
    private val baseline = range.indexFor(manualEv)
    // 硬件每档s EV：一次调节n=max(1,ceil(目标步幅/s-0.001))档，实际Δ=n*s。
    // 相对基线b的边界为b-floor(最大降低量/s)与b+floor(最大增加量/s)，再与硬件范围求交。
    // 0.001/0.0001仅抵消浮点边界误差；小于硬件一档的范围不会被强行扩大。
    private val stepCount = if (range.supported) maxOf(1, ceil(settings.stepEv / range.stepEv - 0.001f).toInt()) else 1
    private val lower = if (range.supported)
        maxOf(range.minimum, baseline - floor((settings.maxDecreaseEv.toDouble() + 0.0001) / range.stepEv).toInt()) else baseline
    private val upper = if (range.supported)
        minOf(range.maximum, baseline + floor((settings.maxIncreaseEv.toDouble() + 0.0001) / range.stepEv).toInt()) else baseline
    // 仅凭阈值占比会在 234/235 两侧来回变档。恢复还要求 P99 留出一档余量。
    // 以有限范围黑端16、白端235估计：T=floor(16+219/2^(硬件量化步幅)-余量)。
    // 编码Y并非线性辐射量，此式是保守迟滞门槛而非曝光预测；默认1/3 EV、余量8时为181。
    private val recoveryCeiling = if (range.supported)
        floor(16 + 219 / 2.0.pow((stepCount * range.stepEv).toDouble()) - settings.recoveryMargin).toInt() else 0
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
        settleUntil = nowNs + settings.settleNs
    }

    fun update(sample: HighlightStatistics?, nowNs: Long): Float? {
        if (!range.supported) return null
        if (sample == null || nowNs < sample.sampledAtNs || nowNs - sample.sampledAtNs > BacklightBalanceSettings.MAX_SAMPLE_AGE_NS ||
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
        // 中心P20用不同的进入/解除阈值形成迟滞；默认<64保护、>=96解除。
        // 默认<40优先恢复已有负补偿；自定义阈值须满足恢复<=保护<解除。
        // 这是 SDR Y 编码域的经验阈值，不是照度或 RAW 信噪比估计。
        if (sample.centerDarkLuma < settings.protectBelow) subjectProtected = true
        else if (sample.centerDarkLuma >= settings.releaseAt) subjectProtected = false
        val regions = sample.regions
        // e=log2(目标/G)是编码亮度反馈误差，不是由ISO/快门反推的物理EV。
        // 每次仅沿误差方向移动一个小档并等待新图反馈，避免把非线性 ISP 当线性传感器。
        // 默认目标112、最小死区0.35；死区还须>=实际硬件步幅的0.6倍，防止粗档震荡。
        val error = ln(settings.targetLuma / regions.weightedLuma) / ln(2f)
        val deadband = max(settings.deadband, stepCount * range.stepEv * 0.6f)
        val broadClipping = regions.hasBroadClipping(settings)
        val lacksRecoveryHeadroom = current < baseline && sample.percentile99 >= recoveryCeiling
        val nextDirection = when {
            sample.centerDarkLuma < settings.deepShadow && current < baseline -> 1
            error > deadband && broadClipping -> 0
            error > deadband && lacksRecoveryHeadroom -> 0
            error > deadband && current < upper -> 1
            (error < -deadband || broadClipping) && !subjectProtected && current > lower -> -1
            else -> 0
        }
        decisionText = when {
            nextDirection > 0 -> if (current < baseline && sample.centerDarkLuma < settings.deepShadow) "恢复暗部" else "分区提亮"
            nextDirection < 0 -> "分区降曝"
            error > deadband && broadClipping -> "反差超限"
            error > deadband && lacksRecoveryHeadroom -> "保留高光余量"
            error > deadband && current >= upper -> "已达提亮上限"
            (error < -deadband || broadClipping) && subjectProtected -> "中心暗部保护"
            error < -deadband && current <= lower -> "已达降曝下限"
            else -> "保持曝光"
        }
        confirmations = if (nextDirection != 0 && nextDirection == direction) confirmations + 1
            else if (nextDirection != 0) 1 else 0
        direction = nextDirection
        if (confirmations < settings.confirmations) return null
        current = (current + direction * stepCount).coerceIn(lower, upper)
        confirmations = 0
        direction = 0
        settleUntil = nowNs + settings.settleNs
        return ev
    }
}
