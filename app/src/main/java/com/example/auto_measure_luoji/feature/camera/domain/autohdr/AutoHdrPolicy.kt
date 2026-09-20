package com.example.auto_measure_luoji.feature.camera.domain.autohdr

import com.example.auto_measure_luoji.feature.camera.domain.exposure.ExposureReading

data class AutoHdrSettings(
    val lowerMs: Double = 0.3,
    val upperMs: Double = 25.0,
    val cooldownSeconds: Int = 10,
) {
    val isValid: Boolean
        get() = lowerMs.isFinite() && upperMs.isFinite() && lowerMs > 0 && upperMs > lowerMs &&
            cooldownSeconds in 1..3600

    companion object {
        fun parse(lower: String, upper: String, cooldown: String): AutoHdrSettings? {
            val settings = AutoHdrSettings(lower.trim().toDoubleOrNull() ?: return null,
                upper.trim().toDoubleOrNull() ?: return null, cooldown.trim().toIntOrNull() ?: return null)
            return settings.takeIf { it.isValid }
        }
    }
}

/** 主线程状态机。仅在会话成功切换后计时，使用单调时钟，开和关采用同一停判规则。 */
class AutoHdrPolicy {
    companion object { const val REQUIRED_SAMPLES = 4 }

    private var nextDecisionAtNs = 0L
    private var lastReading: ExposureReading? = null
    private var lastSettings: AutoHdrSettings? = null
    private var lastHdrEnabled: Boolean? = null
    private var candidateTarget: Boolean? = null
    private var candidateCount = 0

    val confirmationText: String?
        get() = candidateTarget?.let {
            "${if (it) "开启" else "关闭"}确认 $candidateCount/$REQUIRED_SAMPLES"
        }

    fun resetConfirmation() {
        candidateTarget = null
        candidateCount = 0
    }

    fun transitionCompleted(nowNs: Long, settings: AutoHdrSettings) {
        require(settings.isValid)
        resetConfirmation()
        nextDecisionAtNs = nowNs + settings.cooldownSeconds * 1_000_000_000L
    }

    fun remainingSeconds(nowNs: Long): Int =
        ((nextDecisionAtNs - nowNs).coerceAtLeast(0L).let { (it + 999_999_999L) / 1_000_000_000L }).toInt()

    fun decide(enabled: Boolean, blocked: Boolean, hdrEnabled: Boolean, reading: ExposureReading?,
               settings: AutoHdrSettings, nowNs: Long): Boolean? {
        if (lastSettings != settings || lastHdrEnabled != hdrEnabled) resetConfirmation()
        lastSettings = settings
        lastHdrEnabled = hdrEnabled
        // 停判和业务锁打断确认，不缓存条件；记住跳过的帧，恢复时只计新数据。
        if (!enabled || blocked || !settings.isValid || nowNs < nextDecisionAtNs ||
            reading == null || !reading.isFresh(nowNs) || reading.receivedAtNs < nextDecisionAtNs ||
            (reading.exposureTimeNs ?: 0L) <= 0L) {
            resetConfirmation()
            rememberLatest(reading)
            return null
        }
        val previous = lastReading
        if (previous != null) {
            if (!sameSource(previous, reading)) resetConfirmation()
            else if (reading.receivedAtNs <= previous.receivedAtNs) {
                if (reading.receivedAtNs < previous.receivedAtNs) resetConfirmation()
                return null
            }
        }
        lastReading = reading
        val target = targetFor(reading, settings)
        if (target == hdrEnabled) {
            resetConfirmation()
            return null
        }
        if (candidateTarget != target) resetConfirmation()
        candidateTarget = target
        candidateCount = (candidateCount + 1).coerceAtMost(REQUIRED_SAMPLES)
        return target.takeIf { candidateCount == REQUIRED_SAMPLES }
    }

    /** 异步能力检查完成后的复核：不累计样本，也不能把同一帧重复计数。 */
    fun canApplyConfirmedDecision(target: Boolean, hdrEnabled: Boolean, reading: ExposureReading?,
                                  settings: AutoHdrSettings, nowNs: Long): Boolean {
        val previous = lastReading
        val valid = candidateCount == REQUIRED_SAMPLES && candidateTarget == target && target != hdrEnabled &&
            lastSettings == settings && lastHdrEnabled == hdrEnabled && settings.isValid &&
            nowNs >= nextDecisionAtNs && reading != null && previous != null &&
            sameSource(previous, reading) && reading.receivedAtNs >= previous.receivedAtNs &&
            reading.isFresh(nowNs) && (reading.exposureTimeNs ?: 0L) > 0L && targetFor(reading, settings) == target
        if (!valid) resetConfirmation()
        return valid
    }

    private fun rememberLatest(reading: ExposureReading?) {
        val previous = lastReading
        if (reading != null && (previous == null || !sameSource(previous, reading) || reading.receivedAtNs > previous.receivedAtNs)) {
            lastReading = reading
        }
    }

    private fun sameSource(a: ExposureReading, b: ExposureReading): Boolean =
        a.cameraId == b.cameraId && a.physical == b.physical && a.sessionGeneration == b.sessionGeneration

    private fun targetFor(reading: ExposureReading, settings: AutoHdrSettings): Boolean {
        val exposureMs = reading.exposureTimeNs!! / 1_000_000.0
        return exposureMs < settings.lowerMs || exposureMs > settings.upperMs
    }
}
