package com.example.auto_measure_luoji.feature.camera.domain.backlightbalance

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

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

data class MeteringZone(val mean: Float, val brightFraction: Float, val count: Int) {
    val valid: Boolean get() = count > 0 && mean.isFinite() && mean in 0f..255f &&
        brightFraction.isFinite() && brightFraction in 0f..1f
}

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
