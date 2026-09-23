package com.example.auto_measure_luoji.feature.camera.domain.backlightbalance

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 五区测光与受限自动曝光：单文件集成入口。
 *
 * 依赖：Kotlin标准库（1.9+）和java.nio.ByteBuffer；无需Android、Camera2、Compose或本项目其他文件。
 * 可只交付此文件；接入方可修改package行。文件内其他类型均是本算法的数据类型/配置/实现。
 * 不包含图像增强、相机打开、UI、偏好存储或模型；不启用EIS。
 *
 * ## 输入约定
 * 1. 构造：相机EV硬件范围[BacklightExposureRange]、用户EV基线、[BacklightBalanceSettings]。
 * 2. 每帧：[FrameInput]的8-bit SDR亮度Y平面。不能直接传JPEG、RGB、RAW或10-bit P010/HLG。
 * 3. sampledAtNs和nowNs必须来自同一单调时钟，单位ns；不要把毫秒、Unix时间或不同时间基准混用。
 *    Android可在拿到图像时记录elapsedRealtimeNanos()；不要未经核实直接混用Image.timestamp。
 *
 * ## 输出约定
 * [Output.requestedEv]非null：新的完整EV补偿请求，不是增量，也不是实测曝光。
 * [Output.requestedIndex]是与之对应的Camera2整数补偿档位，不是EV值。
 * 两者是同一个请求的两种表示，只需下发其中一种。null表示不下发；0表示需要下发零补偿。
 * [Output.statistics]包含五区统计/G/P20/P99；其亮度单位是Y编码值，占比是0..1。
 * 不输出ISO、快门、光圈或处理后的图像；由宿主开启AE并提交EV，等待AE调节实际采集曝光。
 *
 * ## 生命周期与线程
 * 每个相机会话持有一个实例，只从一个串行线程调用process/evaluate/pause/reset，不可每帧new。
 * 首次有效输入先等待settleSeconds；最多每500ms接受一条新统计，默认连续3条同方向才调节。
 * process会同步读取Y平面，但不持有ByteBuffer、不改position/limit；返回后宿主可关闭Image。
 * 默认500ms间隔不是相机帧率，宿主可每帧调用process，由本入口丢弃过密/重复输入。
 * 无图像时也要每约500ms调用process(null, nowNs)，否则无法发现断流并发出基线恢复请求。
 * 宿主必须及时且成功下发每个非null请求；下发失败时停止循环、恢复基线并重建/重置实例。
 * 设置、用户EV、镜头或会话变化时，先恢复旧实例基线，再使用新配置构造新实例。
 * 暂停期间调用pause保留当前请求EV并清计数；关闭功能用reset并下发其返回的基线请求。
 * 手动曝光(AE_OFF)、校准、点击测光或其他EV控制器工作时应由宿主暂停/退出本控制器。
 *
 * ## 最小调用示例（相机取帧/提交由宿主实现）
 * val algorithm = BacklightMeteringAlgorithm(BacklightExposureRange(-9, 9, 1f / 3f), manualEv = 0f)
 * val result = algorithm.process(
 *     BacklightMeteringAlgorithm.FrameInput(yBuffer, width, height, rowStride, pixelStride, sampledAtNs, 90),
 *     nowNs
 * )
 * result.requestedIndex?.let { index -> /* 宿主提交Camera2 CONTROL_AE_EXPOSURE_COMPENSATION=index */ }
 *
 * Android已有独立分析线程时，可使用本文件的HighlightStatistics.sample取统计快照，
 * 再在唯一的曝光线程调用evaluate(snapshot, nowNs)；不要把同一帧再传process一次。
 */
class BacklightMeteringAlgorithm(
    private val range: BacklightExposureRange,
    private val manualEv: Float = 0f,
    private val settings: BacklightBalanceSettings = BacklightBalanceSettings(),
) {
    init {
        require(manualEv.isFinite()) { "手动EV基线必须是有限数值" }
        require(settings.validationError == null) { settings.validationError ?: "参数无效" }
    }

    /**
     * 一帧图像输入；宽高为Y平面像素数，不是屏幕控件尺寸。
     * @property yPlane 每个Y为无符号8位值；Kotlin Byte有符号，内部用and 255解释。
     *   position()为左上有效像素偏移，limit()为可读末端；支持只读/直接缓冲区。
     * @property width 正整数，源Y平面宽度；须与缓冲区布局匹配。
     * @property height 正整数，源Y平面高度。
     * @property rowStrideBytes 相邻行首的字节跨度，包含行尾padding。
     * @property pixelStrideBytes 同一行相邻Y像素的字节跨度，通常为1。
     *   像素地址=position+y*rowStrideBytes+x*pixelStrideBytes；不读取UV。
     * @property sampledAtNs 图像取得/统计时记录的单调时钟ns；需<=本次nowNs。
     * @property rotationDegrees 源图方向到目标显示方向的顺时针整90度旋转；支持负数/360度归一化。
     *   只置换区域标签，不转动/复制图像，也不处理前摄镜像、裁剪、UI平移或EIS坐标变换。
     */
    data class FrameInput(
        val yPlane: ByteBuffer,
        val width: Int,
        val height: Int,
        val rowStrideBytes: Int,
        val pixelStrideBytes: Int,
        val sampledAtNs: Long,
        val rotationDegrees: Int = 0,
    )

    /** 稳定状态码供集成逻辑使用；reason是中文显示文本，不应解析reason驱动业务。 */
    enum class Status {
        EXPOSURE_UNSUPPORTED, // 硬件范围不可用；不发出EV请求。
        INVALID_SAMPLE,      // 输入缺失/非法/过期，且当前无自动补偿需要撤销。
        RESTORE_BASELINE,    // reset或数据异常，要求宿主恢复手动基线。
        SKIPPED,             // 重复/乱序/间隔不足500ms，不参与连续确认。
        HOLD,                // 等待稳定/确认不足/保护/死区/边界，保持当前请求EV。
        UPDATE_EXPOSURE,     // 需要宿主下发新的完整EV补偿。
    }

    /**
     * 单次调用结果，为值快照，不持有源图像。
     * @property statistics 本次有效统计；非法/不支持/跳帧时为null，按status区分。
     * @property requestedEv 新请求EV，null表示不要写相机；0f不是null。
     * @property requestedIndex 同一请求的硬件整数档位，EV=index*range.stepEv。
     * @property currentEv 算法期望的当前EV，包括本次请求；不是CaptureResult实测值。
     * @property correctionEv 相对硬件量化后的手动基线的补偿，负数为降曝。
     * @property subjectProtected 中心P20保护锁存状态。
     * @property status 机器可读状态码。
     * @property reason 中文决策/等待原因，用于日志或显示。
     */
    data class Output(
        val statistics: HighlightStatistics?,
        val requestedEv: Float?,
        val requestedIndex: Int?,
        val currentEv: Float,
        val correctionEv: Float,
        val subjectProtected: Boolean,
        val status: Status,
        val reason: String,
    )

    private var policy = HighlightExposurePolicy(range, manualEv, settings)
    private var started = false
    private var lastAcceptedNs: Long? = null

    /** 输入原始Y平面，输出统计与可选EV请求；所有调用必须使用同一串行线程。 */
    fun process(frame: FrameInput?, nowNs: Long): Output {
        if (!range.supported) return result(null, null, Status.EXPOSURE_UNSUPPORTED, "相机不支持曝光补偿")
        if (frame == null || !fresh(frame.sampledAtNs, nowNs)) return evaluate(null, nowNs)
        if (tooSoon(frame.sampledAtNs)) return result(null, null, Status.SKIPPED, "等待新的500ms采样")
        val sample = HighlightStatistics.sample(frame.yPlane, frame.width, frame.height,
            frame.rowStrideBytes, frame.pixelStrideBytes, frame.sampledAtNs, frame.rotationDegrees)
        return evaluate(sample, nowNs)
    }

    /**
     * 输入本文件采样器生成的快照，适合采样线程与控制线程分离的宿主。
     * 空/非法/超过1.5秒的快照撤销已有自动补偿，并重新开始稳定等待。
     * 不接受全局亮度代替五区统计；每区都必须有有效采样。
     */
    fun evaluate(sample: HighlightStatistics?, nowNs: Long): Output {
        if (!range.supported) return result(null, null, Status.EXPOSURE_UNSUPPORTED, "相机不支持曝光补偿")
        if (sample == null || !fresh(sample.sampledAtNs, nowNs) ||
            !sample.brightFraction.isFinite() || sample.brightFraction !in 0f..1f ||
            sample.percentile99 !in 0..255 || sample.centerDarkLuma !in 0..255 || sample.regions?.valid != true) {
            // 与原App断流保护一致：只有存在自动补偿时才请求恢复，避免每次空输入都重写相机。
            val restore = policy.correctionEv != 0f
            if (restore) policy = HighlightExposurePolicy(range, manualEv, settings)
            pause(nowNs)
            return result(null, if (restore) policy.ev else null,
                if (restore) Status.RESTORE_BASELINE else Status.INVALID_SAMPLE, "等待有效五区亮度")
        }
        if (tooSoon(sample.sampledAtNs)) return result(null, null, Status.SKIPPED, "等待新的500ms采样")
        if (!started) pause(nowNs)
        lastAcceptedNs = sample.sampledAtNs
        val request = policy.update(sample, nowNs)
        return result(sample, request, if (request == null) Status.HOLD else Status.UPDATE_EXPOSURE, policy.decisionText)
    }

    /** 保留当前EV、清空连续确认并从nowNs开始稳定等待；此函数不产生相机命令。 */
    fun pause(nowNs: Long) {
        policy.pause(nowNs)
        started = true
    }

    /**
     * 关闭功能/硬件下发失败时重置；宿主必须提交返回的基线EV请求。
     * 参数或镜头变化时，应在旧范围仍有效时先恢复，再构造新实例；不要跨会话复用状态。
     */
    fun reset(nowNs: Long): Output {
        policy = HighlightExposurePolicy(range, manualEv, settings)
        lastAcceptedNs = null
        pause(nowNs)
        return if (range.supported) result(null, policy.ev, Status.RESTORE_BASELINE, "恢复手动EV基线")
        else result(null, null, Status.EXPOSURE_UNSUPPORTED, "相机不支持曝光补偿")
    }

    private fun fresh(sampleNs: Long, nowNs: Long): Boolean =
        nowNs >= sampleNs && nowNs - sampleNs in 0..BacklightBalanceSettings.MAX_SAMPLE_AGE_NS

    private fun tooSoon(sampleNs: Long): Boolean = lastAcceptedNs?.let {
        sampleNs <= it || sampleNs - it < HighlightStatistics.INTERVAL_NS
    } ?: false

    private fun result(sample: HighlightStatistics?, request: Float?, status: Status, reason: String) = Output(
        sample, request, request?.let(range::indexFor),
        if (range.supported) policy.ev else 0f,
        if (range.supported) policy.correctionEv else 0f, policy.subjectProtected, status, reason,
    )
}

/**
 * 不依赖Camera2的硬件EV能力；minimum/maximum是整数档位而非EV值。
 * 例如范围[-9,9]、stepEv=1/3表示物理EV范围[-3,+3]。
 * Android从CONTROL_AE_COMPENSATION_RANGE和CONTROL_AE_COMPENSATION_STEP读取后填入。
 * 全零默认值表示不支持；非有限/非正步进、反向或不包含0的范围也视为不支持。
 */
data class BacklightExposureRange(val minimum: Int = 0, val maximum: Int = 0, val stepEv: Float = 0f) {
    val supported: Boolean get() = minimum < maximum && minimum <= 0 && maximum >= 0 &&
        stepEv.isFinite() && stepEv > 0f && (minimum * stepEv).isFinite() && (maximum * stepEv).isFinite()

    /** EV/s先限制到硬件范围再四舍五入，得到可提交的整数档；非有限EV回退0。 */
    fun indexFor(ev: Float): Int {
        if (!supported || !ev.isFinite()) return 0
        return (ev.toDouble() / stepEv).coerceIn(minimum.toDouble(), maximum.toDouble()).roundToInt()
    }
}



/**
 * 输入配置，构造算法后不再改变；单位/默认值如下，数值范围由BacklightParameter统一校验。
 * 五区几何、采样、权重保持固定，显示映射不在本文件职责内。
 * @property targetLuma 综合亮度目标，默认112 Y，允许1..254，不是lux。
 * @property deadband log2(目标/G)误差的最小死区，默认0.35档，允许0.05..2。
 * @property deepShadow 中心P20低于此值且已有负补偿时，优先恢复；默认40 Y，允许0..254。
 * @property protectBelow 中心P20低于此值禁止继续降曝；默认64 Y，允许1..254。
 * @property releaseAt 中心P20大于等于此值解除保护；默认96 Y，允许2..255。
 *   必须deepShadow<=protectBelow<releaseAt，二者之间保持原保护状态。
 * @property weightedHighlightPercent 加权高亮占比达到此百分数触发大范围高光保护；默认35%，允许1..100。
 * @property zoneHighlightPercent 单区高亮占比达标阈值；默认25%，允许1..100。
 * @property highlightZoneCount 达标区域至少几个时触发保护，与加权条件为或关系；默认3，允许1..5。
 * @property stepEv 一次调节目标步幅，默认1/3 EV，允许0.1..1，实际向上量化到硬件档位。
 * @property maxDecreaseEv 相对手动基线最大降低量的正值，默认2/3 EV，允许0..3；0禁用负补偿。
 * @property maxIncreaseEv 相对手动基线最大增加量，默认1 EV，允许0..3；0禁用正补偿。
 * @property confirmations 连续同方向新统计数量，默认3，允许整数1..10。
 * @property settleSeconds 初始/暂停/调整后稳定等待，默认1.5秒，允许0.5..10。
 * @property recoveryMargin P99恢复上限公式的额外扣除量，默认8 Y，允许整数0..64；越大越保守。
 *   默认T=floor(16+219/2^(实际步幅)-8)，1/3 EV时T=181。
 */
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

/** 五区互不重叠、覆盖整个分析帧；方向是竖屏后摄的原始预览方向，不含 UI 平移/放大。 */
enum class MeteringRegion {
    TOP, BOTTOM, LEFT, RIGHT, CENTER;

    companion object {
        /**
         * u=(采样点x+0.5)/宽，v=(采样点y+0.5)/高，均属于 [0,1]。
         * 将传感器图像顺时针旋转到本项目锁定的竖屏方向：
         * 90度：(u',v')=(1-v,u)；180度：(1-u,1-v)；270度：(v,1-u)。
         * 中心为 |u-0.5|<=0.15 且 |v-0.5|<=0.15 的矩形；
         * 外围先按源图归一化中心偏移的主轴归属四边，再旋转区域标签。
         * 这样恰好落在对角边界的点在旋转前后仍归属同一物理区域，不重复计角点。
         * 使用归一化距离而非像素距离，避免长宽比导致某条边占据额外权重。
         */
        fun at(u: Float, v: Float, rotation: Int): MeteringRegion {
            val dx = u - 0.5f
            val dy = v - 0.5f
            val source = when {
                abs(dx) <= 0.15f && abs(dy) <= 0.15f -> CENTER
                abs(dx) > abs(dy) -> if (dx < 0) LEFT else RIGHT
                else -> if (dy < 0) TOP else BOTTOM
            }
            return when (rotation) {
                0 -> source
                90 -> when (source) { TOP -> RIGHT; RIGHT -> BOTTOM; BOTTOM -> LEFT; LEFT -> TOP; CENTER -> CENTER }
                180 -> when (source) { TOP -> BOTTOM; RIGHT -> LEFT; BOTTOM -> TOP; LEFT -> RIGHT; CENTER -> CENTER }
                270 -> when (source) { TOP -> LEFT; RIGHT -> TOP; BOTTOM -> RIGHT; LEFT -> BOTTOM; CENTER -> CENTER }
                else -> error("分区测光仅支持直角旋转")
            }
        }
    }
}

/**
 * 单区输出：mean为两端各裁去floor(5%*count)后的均值，单位Y(0..255)。
 * brightFraction为未截尾样本中Y>=235的占比(0..1)；count是未截尾采样点数，不是区域像素总数。
 * count=0表示该区缺少采样，不能参与曝光决策。
 */
data class MeteringZone(val mean: Float, val brightFraction: Float, val count: Int) {
    val valid: Boolean get() = count > 0 && mean.isFinite() && mean in 0f..255f &&
        brightFraction.isFinite() && brightFraction in 0f..1f
}

/** 五区输出，字段按指定旋转后的上/下/左/右/中心排列；不是检测框或目标分割。 */
data class RegionalMetering(val top: MeteringZone, val bottom: MeteringZone,
                           val left: MeteringZone, val right: MeteringZone, val center: MeteringZone) {
    private val all = listOf(top, bottom, left, right, center)
    val valid: Boolean get() = all.all { it.valid }

    /**
     * 每区先从亮度直方图两端各去掉 floor(N*0.05) 个点求均值 M_i。
     * 综合亮度 G=exp(0.4*ln(max(M_center,1))+0.15*sum四边 ln(max(M_i,1)))。
     * 权重按区域给定，不再按像素面积乘一次，避免天空面积大就独占测光。
     * 对数域平均压低单个极亮区域的支配程度，仍不等于 RAW 辐射亮度或目标分割。
     */
    val weightedLuma: Float get() = exp(0.4f * ln(center.mean.coerceAtLeast(1f)) +
        0.15f * (ln(top.mean.coerceAtLeast(1f)) + ln(bottom.mean.coerceAtLeast(1f)) +
            ln(left.mean.coerceAtLeast(1f)) + ln(right.mean.coerceAtLeast(1f))))

    val weightedHighlights: Float get() = 0.4f * center.brightFraction + 0.15f *
        (top.brightFraction + bottom.brightFraction + left.brightFraction + right.brightFraction)
    val broadClipping: Boolean get() = hasBroadClipping(BacklightBalanceSettings())

    fun hasBroadClipping(settings: BacklightBalanceSettings): Boolean =
        weightedHighlights >= settings.weightedHighlightPercent / 100f ||
            all.count { it.brightFraction >= settings.zoneHighlightPercent / 100f } >= settings.highlightZoneCount
}

/**
 * 图像统计输出，可在分析线程生成后作为不可变快照交给控制线程。
 * @property sampledAtNs 采样单调时间(ns)，与控制器nowNs同一时间基准。
 * @property brightFraction 全图未截尾采样中Y>=235的比例，0..1，不是百分数0..100。
 * @property percentile99 全图第ceil(0.99*N)个由暗到亮排序的Y值，0..255，不是均值。
 * @property centerDarkLuma 中心未截尾采样的第ceil(0.20*Ncenter)个Y值(P20)，0..255。
 *   小图无中心样本时仅提供全图P20诊断回退，但regions无效，禁止驱动曝光。
 * @property regions 五区统计；若某区没有采样则null。G通过regions.weightedLuma读取。
 * 单帧稀疏采样最多3072点，不转换RGB、不修改缓冲区位置、不持有源图像。
 */
data class HighlightStatistics(val sampledAtNs: Long, val brightFraction: Float, val percentile99: Int,
                               val centerDarkLuma: Int, val regions: RegionalMetering? = null) {
    companion object {
        const val INTERVAL_NS = 500_000_000L

        /**
         * 无状态采样入口；布局与FrameInput一致，rowStride/pixelStride的单位是字节。
         * buffer必须在同步调用期间有效，像素地址=position+y*rowStride+x*pixelStride。
         * null表示尺寸/步长/容量/旋转非法；返回非null仍需检查regions有效性。
         * 此低层函数不节流；独立集成推荐process，由统一入口限制500ms统计频率。
         */
        fun sample(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
                   nowNs: Long, rotationDegrees: Int = 0): HighlightStatistics? {
            if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return null
            val rotation = (rotationDegrees % 360 + 360) % 360
            if (rotation % 90 != 0) return null
            val origin = buffer.position().toLong()
            val last = origin + (height - 1L) * rowStride + (width - 1L) * pixelStride
            if (last >= buffer.limit() || (width - 1L) * pixelStride >= rowStride) return null
            // 网格不超过 64x48，取每格中心，索引遵循 Y 平面的行/像素步长。
            // Y>=235 是 8-bit SDR 分析流的保守高亮代理：有限范围接近白端，
            // 全范围约为 92%。它不是传感器 RAW 饱和率，也不是 HLG 的物理亮度。
            val columns = minOf(width, 64)
            val rows = minOf(height, 48)
            var bright = 0
            val histogram = IntArray(256)
            val regionHistograms = Array(5) { IntArray(256) }
            val regionCounts = IntArray(5)
            for (gy in 0 until rows) for (gx in 0 until columns) {
                val x = ((2L * gx + 1) * width / (2L * columns)).toInt()
                val y = ((2L * gy + 1) * height / (2L * rows)).toInt()
                val index = (origin + y.toLong() * rowStride + x.toLong() * pixelStride).toInt()
                val value = buffer.get(index).toInt() and 255
                histogram[value]++
                val region = MeteringRegion.at((x + 0.5f) / width, (y + 0.5f) / height, rotation).ordinal
                regionHistograms[region][value]++
                regionCounts[region]++
                if (value >= 235) bright++
            }
            val count = columns * rows
            fun percentile(values: IntArray, size: Int, percent: Int): Int {
                val rank = (size * percent + 99) / 100
                var accumulated = 0
                return values.indices.first { accumulated += values[it]; accumulated >= rank }
            }
            // 用直方图累计秩裁去两端 5%，即使阈值落在同一亮度桶，也只减去相应数量。
            fun zone(index: Int): MeteringZone {
                val size = regionCounts[index]
                if (size == 0) return MeteringZone(0f, 0f, 0)
                val trim = size / 20
                var rank = 0
                var sum = 0L
                var high = 0
                regionHistograms[index].forEachIndexed { value, number ->
                    val kept = (minOf(rank + number, size - trim) - maxOf(rank, trim)).coerceAtLeast(0)
                    sum += value.toLong() * kept
                    rank += number
                    if (value >= 235) high += number
                }
                return MeteringZone(sum.toFloat() / (size - 2 * trim), high.toFloat() / size, size)
            }
            val regions = RegionalMetering(zone(0), zone(1), zone(2), zone(3), zone(4)).takeIf { it.valid }
            val centerCount = regionCounts[MeteringRegion.CENTER.ordinal]
            return HighlightStatistics(nowNs, bright.toFloat() / count,
                percentile(histogram, count, 99), if (centerCount > 0)
                    percentile(regionHistograms[MeteringRegion.CENTER.ordinal], centerCount, 20)
                else percentile(histogram, count, 20), regions)
        }
    }
}

/**
 * 曝光决策内部实现，保留原类名便于原有回归测试复用。
 * 独立集成推荐BacklightMeteringAlgorithm：它还负责初始等待、500ms节流和断流恢复。
 * 本类只决策不下发命令；实例保存保护状态、方向、计数与稳定期，不得逐帧重建。
 */
class HighlightExposurePolicy(
    private val range: BacklightExposureRange,
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

    /**
     * 输入有效五区快照和同一单调时钟ns；输出新的完整EV请求或null（不写相机）。
     * 只读属性ev/correctionEv是期望值而非实测，decisionText供显示。
     * 本低层方法遇到坏数据只清确认，不负责撤销补偿；统一入口evaluate处理撤销。
     */
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
