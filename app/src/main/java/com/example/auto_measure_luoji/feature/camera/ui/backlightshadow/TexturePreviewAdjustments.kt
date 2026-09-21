package com.example.auto_measure_luoji.feature.camera.ui.backlightshadow

import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.example.auto_measure_luoji.feature.camera.ui.imageadjustment.PreviewContrast
import com.example.auto_measure_luoji.feature.camera.ui.localtone.LocalToneShader
import com.example.auto_measure_luoji.feature.camera.ui.localtone.PreviewToneMode
import com.example.auto_measure_luoji.feature.camera.ui.glaresuppression.GlareSuppressionShader

/** 仅处理 TextureView 的显示节点；TextureView.bitmap 和相机 YUV 保持原样。 */
class TexturePreviewAdjustments {
    private var effect: ShadowEffect? = null

    fun apply(view: TextureView, contrast: Float, shadows: Boolean, tone: PreviewToneMode = PreviewToneMode.OFF): Result<Unit> = runCatching {
        val k = PreviewContrast.normalize(contrast)
        // TextureView 的 layer paint 会进入 getBitmap()，必须保持中性。
        view.setLayerPaint(Paint())
        if (Build.VERSION.SDK_INT >= 31) {
            view.setRenderEffect(if (k == 1f) null else RenderEffect.createColorFilterEffect(
                ColorMatrixColorFilter(PreviewContrast.colorMatrix(k))))
        } else {
            applyLegacyContrast(view, k)
        }
        if (shadows || tone != PreviewToneMode.OFF) {
            check(supported) { "当前 Android 版本的预览增强需要 GL 预览" }
            // 首次布局完成后 CameraScreen 会按实际尺寸重新提交，不能用 0 尺寸做邻域采样。
            if (view.width <= 0 || view.height <= 0) return@runCatching
            if (Build.VERSION.SDK_INT >= 33) {
                val renderer = effect ?: ShadowEffect().also { effect = it }
                // 先构造完整效果再移除旧对比度，失败时仍保留可用的对比度设置。
                val renderEffect = renderer.create(k, shadows, tone, view.width, view.height)
                view.setRenderEffect(renderEffect)
            }
        }
    }

    // API 24~30 只能在专属父容器合成对比度，不能污染 TextureView 或界面叠加元素。
    internal fun applyLegacyContrast(view: TextureView, contrast: Float) {
        val parent = view.parent as? FrameLayout ?: error("预览缺少专属显示容器")
        check(parent.childCount == 1 && parent.getChildAt(0) === view) { "预览显示容器不能包含其他界面元素" }
        val k = PreviewContrast.normalize(contrast)
        parent.setLayerType(if (k == 1f) View.LAYER_TYPE_NONE else View.LAYER_TYPE_HARDWARE,
            Paint().apply { colorFilter = if (k == 1f) null else ColorMatrixColorFilter(PreviewContrast.colorMatrix(k)) })
    }

    companion object {
        val supported: Boolean get() = Build.VERSION.SDK_INT >= 33
    }

    @RequiresApi(33)
    private class ShadowEffect {
        private val shader = RuntimeShader("""
            uniform shader preview;
            uniform float contrast;
            uniform float shadows;
            uniform int toneMode;
            uniform float2 toneSize;
            uniform float2 toneStep;
            ${ShadowEnhancement.GAIN_FUNCTION}
            float toneLuma(float2 position) {
                half4 sampleColor = preview.eval(clamp(position, float2(0.5), toneSize - 0.5));
                float3 rgb = clamp(float3(sampleColor.rgb) / max(float(sampleColor.a), 0.0001), 0.0, 1.0);
                return dot(rgb, float3(0.2126, 0.7152, 0.0722));
            }
            ${LocalToneShader.functions(true)}
            ${GlareSuppressionShader.functions(true)}
            half4 main(float2 position) {
                half4 c = preview.eval(position);
                float3 rgb = clamp(float3(c.rgb) / max(float(c.a), 0.0001), 0.0, 1.0);
                float y = dot(rgb, float3(0.2126, 0.7152, 0.0722));
                if (toneMode == ${PreviewToneMode.GLARE_SUPPRESSION.shaderValue}) {
                    rgb = suppressGlare(position, toneStep, rgb, y);
                } else if (toneMode != 0) {
                    rgb *= localToneGain(position, toneStep, y, max(rgb.r, max(rgb.g, rgb.b)), toneMode);
                } else if (shadows > 0.5) {
                    rgb *= shadowGain(y, max(rgb.r, max(rgb.g, rgb.b)));
                }
                rgb = clamp((rgb - 0.5) * contrast + 0.5, 0.0, 1.0);
                return half4(half3(rgb * float(c.a)), c.a);
            }
        """.trimIndent())

        fun create(contrast: Float, shadows: Boolean, tone: PreviewToneMode, width: Int, height: Int): RenderEffect {
            shader.setFloatUniform("contrast", contrast)
            shader.setFloatUniform("shadows", if (shadows) 1f else 0f)
            shader.setIntUniform("toneMode", tone.shaderValue)
            shader.setFloatUniform("toneSize", width.coerceAtLeast(1).toFloat(), height.coerceAtLeast(1).toFloat())
            val step = LocalToneShader.radius(width, height) / 2f
            shader.setFloatUniform("toneStep", step, step)
            return RenderEffect.createRuntimeShaderEffect(shader, "preview")
        }
    }
}
