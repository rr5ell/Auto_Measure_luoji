package com.example.auto_measure_luoji.feature.camera.ui.glaresuppression

import kotlin.math.min

/**
 * 轻量白雾抑制试验，不是光学眩光反演或细节重建。仅用于最终显示，不写回算法取帧。
 * 在宿主的 SDR/HLG 编码域，以 5x5 稀疏亮度邻域的最小值 m 估计局部灰底上限。
 * 设邻域范围 d=max(Y)-min(Y)，纹理权重 q=smoothstep(0.015,0.15,d)：
 * 平坦区域缺少白雾证据，保持不变；这只能抑制误处理，不能可靠区分白雾与正常场景。
 * 高光保护 h=1-smoothstep(0.7,0.98,Y_center)。扣除量
 * v=min(0.12,0.35*m)*q*h，再限制 v<=0.5*min(R,G,B)，避免色彩通道被直接扣成零。
 * 输出 C'=(C-v)/(1-v)：白色端点不变，已有明暗差可扩大；v<=0.12 保证分母>=0.88。
 * 邻域坐标复用 LocalToneShader 的像素半径/步长定义，宿主负责边界钳位，不移动中心像素。
 * 无时间滤波，运动时可能闪烁，稀疏邻域可能产生光晕；不能保证恢复已过曝信息。
 */
object GlareSuppressionShader {
    private fun smoothstep(low: Float, high: Float, value: Float): Float {
        val t = ((value - low) / (high - low)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** CPU 参考仅供回归测试；生产预览全程 GPU 处理。 */
    fun veil(centerLuma: Float, minChannel: Float, neighborhood: FloatArray): Float {
        require(neighborhood.size == 25)
        if (!centerLuma.isFinite() || !minChannel.isFinite() || neighborhood.any { !it.isFinite() }) return 0f
        val low = neighborhood.minOrNull()!!.coerceIn(0f, 1f)
        val high = neighborhood.maxOrNull()!!.coerceIn(0f, 1f)
        val texture = smoothstep(0.015f, 0.15f, high - low)
        val highlights = 1f - smoothstep(0.7f, 0.98f, centerLuma)
        return min(min(0.12f, 0.35f * low) * texture * highlights, 0.5f * minChannel.coerceIn(0f, 1f))
    }

    fun channel(value: Float, veil: Float): Float {
        if (!value.isFinite()) return 0f
        val v = if (veil.isFinite()) veil.coerceIn(0f, 0.12f) else 0f
        return ((value.coerceIn(0f, 1f) - v) / (1f - v)).coerceIn(0f, 1f)
    }

    fun functions(agsl: Boolean): String {
        val vec2 = if (agsl) "float2" else "vec2"
        val vec3 = if (agsl) "float3" else "vec3"
        return """
            $vec3 suppressGlare($vec2 position, $vec2 stepSize, $vec3 rgb, float centerY) {
                float low = 1.0;
                float high = 0.0;
                for (int y = -2; y <= 2; y++) {
                    for (int x = -2; x <= 2; x++) {
                        float sampleY = clamp(toneLuma(position + $vec2(float(x), float(y)) * stepSize), 0.0, 1.0);
                        low = min(low, sampleY);
                        high = max(high, sampleY);
                    }
                }
                float textureWeight = smoothstep(0.015, 0.15, high - low);
                float highlightWeight = 1.0 - smoothstep(0.7, 0.98, centerY);
                float veil = min(min(0.12, 0.35 * low) * textureWeight * highlightWeight,
                    0.5 * min(rgb.r, min(rgb.g, rgb.b)));
                return clamp((rgb - veil) / (1.0 - veil), 0.0, 1.0);
            }
        """.trimIndent()
    }
}
