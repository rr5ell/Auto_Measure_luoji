package com.example.auto_measure_luoji.feature.camera.ui.localtone

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 实时显示实验版：固定 5x5 稀疏邻域，无逐帧 min/max 归一化，不处理相机/模型缓存。
 * 坐标单位由宿主决定：GL 为 [0,1] 纹理坐标，AGSL 为显示像素；采样半径为短边 2.5%，
 * 限制在 4~32 像素，步长=半径/2，两个轴均使用同一像素距离，避免纵横比影响滤波。
 * 空间权重使用 [1,4,6,4,1] 外积（近似高斯）；所有邻域坐标由宿主钳位到图像边缘。
 *
 * 保边模式：l=ln(max(Y,1/255))，w=空间权重*exp(-(邻域l-中心l)^2/(2*0.35^2))。
 * 基底 B=sum(w*l)/sum(w)，细节 D=l-B；B'=B+0.4*max(ln(0.7)-B,0)。
 * 输出亮度 exp(B'+D)=Y*exp(B'-B)，压缩暗部基底与参考亮度间差距，同时保留细节层。
 *
 * Retinex：L=高斯邻域平均亮度，R=ln(max(Y,epsilon))-ln(max(L,epsilon))；
 * 输出 exp(R+0.55*ln(L))，等价于 Y*L^(-0.45)。这是单尺度照明校正，不是 MSRCR。
 * 两种模式统一将增益限于 [1,4]，并以最高 RGB 通道约束色域，黑色保持黑色。
 * 固定参数避免全帧自适应拉伸造成的闪烁，但运动/噪声下仍须真机评估时间稳定性。
 * 在 SDR/HLG 各自编码域操作，仅是显示调色；不能解释为恢复物理辐射或丢失信息。
 */
object LocalToneShader {
    fun radius(width: Int, height: Int) = (min(width, height) * 0.025f).coerceIn(4f, 32f)

    /** CPU 参考仅用于数学与像素回归，不用于逐帧图像处理。 */
    fun gain(mode: PreviewToneMode, center: Float, neighborhood: FloatArray, peak: Float = center): Float {
        require(neighborhood.size == 25)
        if (mode !in listOf(PreviewToneMode.LOCAL_TONE, PreviewToneMode.RETINEX) ||
            !center.isFinite() || !peak.isFinite() || neighborhood.any { !it.isFinite() }) return 1f
        val weights = floatArrayOf(1f, 4f, 6f, 4f, 1f)
        val logCenter = ln(max(center.coerceIn(0f, 1f), 1f / 255f))
        var sum = 0f
        var weightSum = 0f
        for (y in 0..4) for (x in 0..4) {
            val sample = max(neighborhood[y * 5 + x].coerceIn(0f, 1f), 1f / 255f)
            val d = ln(sample) - logCenter
            val weight = weights[x] * weights[y] * if (mode == PreviewToneMode.LOCAL_TONE) exp(-d * d / 0.245f) else 1f
            sum += weight * if (mode == PreviewToneMode.LOCAL_TONE) ln(sample) else sample
            weightSum += weight
        }
        val base = sum / weightSum
        val delta = if (mode == PreviewToneMode.LOCAL_TONE) 0.4f * max(ln(0.7f) - base, 0f) else -0.45f * ln(base)
        return min(exp(delta).coerceIn(1f, 4f), 1f / max(peak.coerceIn(0f, 1f), 0.0001f))
    }

    // 宿主提供 toneLuma(position)，两种着色语言共用完整的滤波/增益逻辑。
    fun functions(agsl: Boolean): String {
        val vec2 = if (agsl) "float2" else "vec2"
        return """
            float spatialWeight(int offset) {
                if (offset == 0) return 6.0;
                if (offset == -1 || offset == 1) return 4.0;
                return 1.0;
            }
            float localToneGain($vec2 position, $vec2 stepSize, float center, float peak, int mode) {
                float logCenter = log(max(center, 1.0 / 255.0));
                float sum = 0.0;
                float weightSum = 0.0;
                for (int y = -2; y <= 2; y++) {
                    for (int x = -2; x <= 2; x++) {
                        float sampleY = max(toneLuma(position + $vec2(float(x), float(y)) * stepSize), 1.0 / 255.0);
                        float weight = spatialWeight(x) * spatialWeight(y);
                        float value = sampleY;
                        if (mode == 1) {
                            value = log(sampleY);
                            float d = value - logCenter;
                            weight *= exp(-d * d / 0.245);
                        }
                        sum += weight * value;
                        weightSum += weight;
                    }
                }
                float base = sum / max(weightSum, 0.0001);
                float delta = mode == 1 ? 0.4 * max(log(0.7) - base, 0.0) : -0.45 * log(max(base, 1.0 / 255.0));
                return min(clamp(exp(delta), 1.0, 4.0), 1.0 / max(peak, 0.0001));
            }
        """.trimIndent()
    }
}
