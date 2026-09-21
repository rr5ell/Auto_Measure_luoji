package com.example.auto_measure_luoji.feature.camera.ui.imageadjustment

import com.example.auto_measure_luoji.feature.camera.ui.backlightshadow.ShadowEnhancement
import com.example.auto_measure_luoji.feature.camera.ui.localtone.LocalToneShader
import com.example.auto_measure_luoji.feature.camera.ui.localtone.PreviewToneMode
import com.example.auto_measure_luoji.feature.camera.ui.glaresuppression.GlareSuppressionShader

/**
 * 显示端艺术对比度：C' = clamp((C - 0.5) * k + 0.5, 0, 1)。
 * k=1 原样输出；SDR 和 HLG 均在各自编码信号域处理，不将 HLG 当作 sRGB 解码。
 * 8-bit ColorMatrix 的平移项为 255 * 0.5 * (1-k)，与 GL 的归一化计算一致。
 * 只调整颜色，不改变坐标、采样矩阵或帧时间戳；不写入模型及校准取帧缓存。
 */
object PreviewContrast {
    fun normalize(value: Float) = value.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f

    fun colorMatrix(value: Float): FloatArray {
        val k = normalize(value)
        val offset = 127.5f * (1f - k)
        return floatArrayOf(k, 0f, 0f, 0f, offset, 0f, k, 0f, 0f, offset,
            0f, 0f, k, 0f, offset, 0f, 0f, 0f, 1f, 0f)
    }

    val FRAGMENT = """
        precision highp float;
        uniform sampler2D uTexture;
        uniform float uContrast;
        uniform float uShadows;
        uniform vec3 uLumaWeights;
        uniform int uToneMode;
        uniform vec2 uToneStep;
        uniform vec2 uToneTexel;
        varying vec2 vTexCoord;
        ${ShadowEnhancement.GAIN_FUNCTION}
        float toneLuma(vec2 position) {
            return dot(texture2D(uTexture, clamp(position, uToneTexel * 0.5, vec2(1.0) - uToneTexel * 0.5)).rgb, uLumaWeights);
        }
        ${LocalToneShader.functions(false)}
        ${GlareSuppressionShader.functions(false)}
        void main() {
            vec4 c = texture2D(uTexture, vTexCoord);
            if (uToneMode == ${PreviewToneMode.GLARE_SUPPRESSION.shaderValue}) {
                c.rgb = suppressGlare(vTexCoord, uToneStep, c.rgb, dot(c.rgb, uLumaWeights));
            } else if (uToneMode != 0) {
                c.rgb *= localToneGain(vTexCoord, uToneStep, dot(c.rgb, uLumaWeights), max(c.r, max(c.g, c.b)), uToneMode);
            } else if (uShadows > 0.5) {
                c.rgb *= shadowGain(dot(c.rgb, uLumaWeights), max(c.r, max(c.g, c.b)));
            }
            gl_FragColor = vec4(clamp((c.rgb - 0.5) * uContrast + 0.5, 0.0, 1.0), c.a);
        }
    """
}
