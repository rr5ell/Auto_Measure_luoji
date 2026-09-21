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

/** 仅处理 TextureView 的显示节点；TextureView.bitmap 和相机 YUV 保持原样。 */
class TexturePreviewAdjustments {
    private var effect: ShadowEffect? = null

    fun apply(view: TextureView, contrast: Float, shadows: Boolean): Result<Unit> = runCatching {
        val k = PreviewContrast.normalize(contrast)
        // TextureView 的 layer paint 会进入 getBitmap()，必须保持中性。
        view.setLayerPaint(Paint())
        if (Build.VERSION.SDK_INT >= 31) {
            view.setRenderEffect(if (k == 1f) null else RenderEffect.createColorFilterEffect(
                ColorMatrixColorFilter(PreviewContrast.colorMatrix(k))))
        } else {
            applyLegacyContrast(view, k)
        }
        if (shadows) {
            check(supported) { "当前 Android 版本的暗部增强需要 GL 预览" }
            if (Build.VERSION.SDK_INT >= 33) {
                val renderer = effect ?: ShadowEffect().also { effect = it }
                // 先构造完整效果再移除旧对比度，失败时仍保留可用的对比度设置。
                val renderEffect = renderer.create(k)
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
            ${ShadowEnhancement.GAIN_FUNCTION}
            half4 main(float2 position) {
                half4 c = preview.eval(position);
                float3 rgb = clamp(float3(c.rgb) / max(float(c.a), 0.0001), 0.0, 1.0);
                float y = dot(rgb, float3(0.2126, 0.7152, 0.0722));
                rgb *= shadowGain(y, max(rgb.r, max(rgb.g, rgb.b)));
                rgb = clamp((rgb - 0.5) * contrast + 0.5, 0.0, 1.0);
                return half4(half3(rgb * float(c.a)), c.a);
            }
        """.trimIndent())

        fun create(contrast: Float): RenderEffect {
            shader.setFloatUniform("contrast", contrast)
            return RenderEffect.createRuntimeShaderEffect(shader, "preview")
        }
    }
}
