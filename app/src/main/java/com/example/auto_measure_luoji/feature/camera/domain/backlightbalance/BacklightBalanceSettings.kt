package com.example.auto_measure_luoji.feature.camera.domain.backlightbalance

/** 仅配置曝光判断；五区几何、采样、权重及显示映射保持独立。 */
data class BacklightBalanceSettings(
    val targetLuma: Float = 112f,
    val deadband: Float = 0.35f,
    val deepShadow: Int = 40,
    val protectBelow: Int = 64,
    val releaseAt: Int = 96,
    val weightedHighlightPercent: Float = 35f,
    val zoneHighlightPercent: Float = 25f,
    val highlightZoneCount: Int = 3,
    val stepEv: Float = 1f / 3f,
    val maxDecreaseEv: Float = 2f / 3f,
    val maxIncreaseEv: Float = 1f,
    val confirmations: Int = 3,
    val settleSeconds: Float = 1.5f,
    val recoveryMargin: Int = 8,
) {
    val validationError: String? get() {
        for (parameter in BacklightParameter.entries) {
            if (!parameter.accepts(parameter.read(this).toFloat())) return "${parameter.label}：${parameter.rangeLabel}"
        }
        if (deepShadow > protectBelow || protectBelow >= releaseAt) {
            return "中心 P20 须满足：优先恢复阈值 ≤ 保护阈值 < 解除阈值"
        }
        return null
    }
    val settleNs: Long get() = (settleSeconds.toDouble() * 1_000_000_000L).toLong()

    fun fields(): Map<BacklightParameter, String> = BacklightParameter.entries.associateWith { it.read(this).toString() }

    companion object {
        // 数据过期是安全边界，不随用户的曝光稳定等待时间放宽。
        const val MAX_SAMPLE_AGE_NS = 1_500_000_000L

        fun parse(fields: Map<BacklightParameter, String>): BacklightBalanceSettings? {
            val values = BacklightParameter.entries.associateWith { parameter ->
                val value = fields[parameter]?.trim()?.toFloatOrNull() ?: return null
                if (!parameter.accepts(value)) return null
                value
            }
            fun v(parameter: BacklightParameter) = values.getValue(parameter)
            return BacklightBalanceSettings(
                targetLuma = v(BacklightParameter.TARGET), deadband = v(BacklightParameter.DEADBAND),
                deepShadow = v(BacklightParameter.DEEP).toInt(), protectBelow = v(BacklightParameter.PROTECT).toInt(),
                releaseAt = v(BacklightParameter.RELEASE).toInt(),
                weightedHighlightPercent = v(BacklightParameter.WEIGHTED_HIGH),
                zoneHighlightPercent = v(BacklightParameter.ZONE_HIGH),
                highlightZoneCount = v(BacklightParameter.ZONE_COUNT).toInt(),
                stepEv = v(BacklightParameter.STEP), maxDecreaseEv = v(BacklightParameter.DECREASE),
                maxIncreaseEv = v(BacklightParameter.INCREASE), confirmations = v(BacklightParameter.CONFIRM).toInt(),
                settleSeconds = v(BacklightParameter.SETTLE), recoveryMargin = v(BacklightParameter.MARGIN).toInt(),
            ).takeIf { it.validationError == null }
        }
    }
}

/** 同一份字段约束供面板、持久化和校验使用，枚举名称为稳定存储键。 */
enum class BacklightParameter(
    val group: String, val label: String, val minimum: Float, val maximum: Float,
    val integer: Boolean = false, val read: (BacklightBalanceSettings) -> Number,
) {
    TARGET("亮度判断", "综合亮度目标（Y）", 1f, 254f, read = { it.targetLuma }),
    DEADBAND("亮度判断", "最小判断死区（档）", 0.05f, 2f, read = { it.deadband }),
    DEEP("中心暗部保护", "P20 优先恢复：小于（Y）", 0f, 254f, true, { it.deepShadow }),
    PROTECT("中心暗部保护", "P20 开启保护：小于（Y）", 1f, 254f, true, { it.protectBelow }),
    RELEASE("中心暗部保护", "P20 解除保护：大于等于（Y）", 2f, 255f, true, { it.releaseAt }),
    WEIGHTED_HIGH("高光保护", "加权高亮占比：大于等于（%）", 1f, 100f, read = { it.weightedHighlightPercent }),
    ZONE_HIGH("高光保护", "单区高亮占比：大于等于（%）", 1f, 100f, read = { it.zoneHighlightPercent }),
    ZONE_COUNT("高光保护", "单区占比达标的最少区域数", 1f, 5f, true, { it.highlightZoneCount }),
    MARGIN("高光保护", "P99 回升额外余量（Y）", 0f, 64f, true, { it.recoveryMargin }),
    STEP("曝光范围", "每次调整目标步幅（EV）", 0.1f, 1f, read = { it.stepEv }),
    DECREASE("曝光范围", "相对手动 EV 最大降低量", 0f, 3f, read = { it.maxDecreaseEv }),
    INCREASE("曝光范围", "相对手动 EV 最大增加量", 0f, 3f, read = { it.maxIncreaseEv }),
    CONFIRM("时序", "连续同方向确认次数", 1f, 10f, true, { it.confirmations }),
    SETTLE("时序", "调节后稳定等待（秒）", 0.5f, 10f, read = { it.settleSeconds });

    val rangeLabel: String get() = "$minimum ~ $maximum" + if (integer) "，整数" else ""
    fun accepts(value: Float): Boolean = value.isFinite() && value in minimum..maximum &&
        (!integer || value == value.toInt().toFloat())
}
