package com.example.auto_measure_luoji.feature.camera.ui.backlightshadow

import kotlin.math.max
import kotlin.math.min

/**
 * 显示端暗部增强，不是镜头暗角矫正或多帧 HDR。
 * 在编码信号域取亮度 Y，增益 g=1+2*(1-Y)^4；低亮度增益大，高光增益趋近 1。
 * 所有 RGB 通道乘相同增益，并以 1/max(R,G,B) 限制增益，避免单通道裁切引起色偏。
 * 灰阶输出 f(Y)=Y+2Y(1-Y)^4：黑白端点不动，导数最小 0.568，保持灰阶单调。
 * SDR 使用 BT.709 亮度权重，HLG 使用 BT.2020 权重；均为显示调色，不是物理亮度估计。
 * 仅应用于最终上屏，不改变采样坐标、模型/校准输入，不恢复已丢失的暗部信息。
 */
object ShadowEnhancement {
    const val PREFERENCE_KEY = "preview_shadow_enhancement"

    fun gain(red: Float, green: Float, blue: Float, hlg: Boolean = false): Float {
        if (!red.isFinite() || !green.isFinite() || !blue.isFinite()) return 1f
        val r = red.coerceIn(0f, 1f)
        val g = green.coerceIn(0f, 1f)
        val b = blue.coerceIn(0f, 1f)
        val y = if (hlg) 0.2627f * r + 0.6780f * g + 0.0593f * b
            else 0.2126f * r + 0.7152f * g + 0.0722f * b
        val t = 1f - y.coerceIn(0f, 1f)
        return min(1f + 2f * t * t * t * t, 1f / max(max(r, max(g, b)), 0.0001f))
    }

    // GLSL 与 AGSL 共用增益函数，避免两条预览链路出现不同的提亮曲线。
    const val GAIN_FUNCTION = """
        float shadowGain(float y, float peak) {
            float t = 1.0 - clamp(y, 0.0, 1.0);
            return min(1.0 + 2.0 * t * t * t * t, 1.0 / max(peak, 0.0001));
        }
    """
}
