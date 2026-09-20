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
    private var nextDecisionAtNs = 0L

    fun transitionCompleted(nowNs: Long, settings: AutoHdrSettings) {
        require(settings.isValid)
        nextDecisionAtNs = nowNs + settings.cooldownSeconds * 1_000_000_000L
    }

    fun remainingSeconds(nowNs: Long): Int =
        ((nextDecisionAtNs - nowNs).coerceAtLeast(0L).let { (it + 999_999_999L) / 1_000_000_000L }).toInt()

    fun decide(enabled: Boolean, blocked: Boolean, hdrEnabled: Boolean, reading: ExposureReading?,
               settings: AutoHdrSettings, nowNs: Long): Boolean? {
        // 先检查停判和业务状态，不在停判期间计算曝光条件或排队旧决定。
        if (!enabled || blocked || !settings.isValid || nowNs < nextDecisionAtNs) return null
        if (reading == null || !reading.isFresh(nowNs)) return null
        val exposureNs = reading.exposureTimeNs?.takeIf { it > 0L } ?: return null
        val exposureMs = exposureNs / 1_000_000.0
        val target = exposureMs < settings.lowerMs || exposureMs > settings.upperMs
        return target.takeIf { it != hdrEnabled }
    }
}
