package com.example.auto_measure_luoji.feature.camera.domain.tapmetering

import com.example.auto_measure_luoji.feature.camera.domain.backlightbalance.HighlightStatistics
import com.example.auto_measure_luoji.feature.camera.domain.imageadjustment.ExposureCompensationRange
import kotlin.math.floor
import kotlin.math.ln

/** 全画面 AE 上的点击微调，不把点击区域直接交给 HAL，不手动接管快门或 ISO。 */
class TapExposurePolicy(private val range: ExposureCompensationRange, manualEv: Float,
                        private val point: TapMeteringPoint, startedAtNs: Long) {
    private val baseline = range.indexFor(manualEv)
    // 向下量化保证硬件档位不会越过限幅；大于 1/3 EV 的粗档设备保持原曝光。
    private val stepCount = if (range.supported) floor((1.0 / 3 + 0.00001) / range.stepEv).toInt() else 0
    private val limit = if (range.supported) floor((1.0 + 0.00001) / range.stepEv).toInt() else 0
    private val highlightLimit = if (range.supported) floor((2.0 / 3 + 0.00001) / range.stepEv).toInt() else 0
    private val lower = maxOf(range.minimum, baseline - limit)
    private val upper = minOf(range.maximum, baseline + limit)
    private var current = baseline
    private var lastSample = startedAtNs
    private var settleUntil = startedAtNs + SETTLE_NS
    private var direction = 0
    private var confirmations = 0
    private var protectHighlights = false
    private var protectShadows = false
    var decisionText = "等待曝光稳定"
        private set
    val correctionEv: Float get() = if (range.supported) (current - baseline) * range.stepEv else 0f

    fun pause(nowNs: Long) {
        direction = 0
        confirmations = 0
        settleUntil = nowNs + SETTLE_NS
    }

    fun accepts(sample: HighlightStatistics?, nowNs: Long): Boolean = sample != null &&
        sample.tapPoint == point && sample.tapZone?.valid == true && sample.tapZone.count >= 4 &&
        sample.globalZone?.valid == true && sample.brightFraction.isFinite() && sample.brightFraction in 0f..1f &&
        sample.darkFraction.isFinite() && sample.darkFraction in 0f..1f &&
        nowNs >= sample.sampledAtNs && nowNs - sample.sampledAtNs <= 1_500_000_000L

    fun update(sample: HighlightStatistics?, nowNs: Long): Float? {
        if (!range.supported || stepCount == 0) {
            decisionText = "保持全画面（无细分 EV 档位）"
            return null
        }
        if (!accepts(sample, nowNs)) {
            confirmations = 0
            direction = 0
            decisionText = "等待有效点击亮度"
            return null
        }
        sample!!
        if (sample.sampledAtNs <= lastSample || sample.sampledAtNs - lastSample < HighlightStatistics.INTERVAL_NS) return null
        if (sample.sampledAtNs - lastSample > 1_500_000_000L) { direction = 0; confirmations = 0 }
        lastSample = sample.sampledAtNs
        if (sample.sampledAtNs < settleUntil) { decisionText = "等待曝光稳定"; return null }
        val local = sample.tapZone!!.mean.coerceAtLeast(1f)
        val global = sample.globalZone!!.mean.coerceAtLeast(1f)
        // e=0.8*log2(112/Ytap)+0.2*log2(112/Yglobal)，提高点击目标权重，避免亮背景抵消暗目标。
        // 两个均值都在原始 SDR 分析流的编码 Y 域，两端各去掉 5% 极值。
        // 该误差只决定方向，不是线性辐射量，也不能直接换算 ISO/快门；112 是经验目标。
        // |e|<=0.25 为死区。每两个独立 500ms 读数同向才走一小档，再等待 500ms 新图。
        val error = (0.8f * ln(112f / local) + 0.2f * ln(112f / global)) / ln(2f)
        // 保护采用不同进入/退出阈值，避免一小档变化跨过同一门槛后反复撤销/增加。
        if (sample.brightFraction >= 0.50f || global >= 210f) protectHighlights = true
        else if (sample.brightFraction < 0.35f && global < 195f) protectHighlights = false
        if (global <= 96f || sample.darkFraction >= 0.45f) protectShadows = true
        else if (global >= 112f && sample.darkFraction <= 0.30f) protectShadows = false
        // 高光只将正补偿上限由 +1 EV 收紧至 +2/3 EV，不直接撤回基线。
        val effectiveUpper = if (protectHighlights) minOf(upper, baseline + highlightLimit) else upper
        val wanted = when {
            current > effectiveUpper -> -1
            protectShadows && current < baseline -> 1
            error > 0.25f -> 1
            error < -0.25f && !protectShadows -> -1
            else -> 0
        }
        // 上限突然收紧时也只回退一小档，不能直接 clamp 到新上限造成超步幅跳变。
        val next = when {
            current > effectiveUpper -> maxOf(effectiveUpper, current - stepCount)
            wanted > 0 -> minOf(effectiveUpper, current + stepCount)
            wanted < 0 -> maxOf(lower, current - stepCount)
            else -> current
        }
        val nextDirection = next.compareTo(current)
        decisionText = when {
            nextDirection > 0 -> "小幅提亮"
            nextDirection < 0 -> "小幅降曝"
            error > 0.25f && protectHighlights -> "高光限幅"
            error < -0.25f && protectShadows -> "全画面暗部保护"
            wanted != 0 -> "已达微调上限"
            else -> "保持曝光"
        }
        confirmations = if (nextDirection == 0) 0 else if (direction == nextDirection) confirmations + 1 else 1
        direction = nextDirection
        if (confirmations < 2) return null
        current = next
        pause(nowNs)
        return current * range.stepEv
    }

    companion object {
        private const val SETTLE_NS = 500_000_000L
    }
}
