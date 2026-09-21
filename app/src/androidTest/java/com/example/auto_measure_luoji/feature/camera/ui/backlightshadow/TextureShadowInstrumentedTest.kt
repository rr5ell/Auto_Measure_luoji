package com.example.auto_measure_luoji.feature.camera.ui.backlightshadow

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.feature.camera.ui.FrameBoundPreviewTestActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class TextureShadowInstrumentedTest {
    @Test fun shaderLiftsOnlyDisplayAndRestoresContrastWithoutTouchingSource() {
        assumeTrue(TexturePreviewAdjustments.supported)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, FrameBoundPreviewTestActivity::class.java).putExtra("texture_preview", true)
        ActivityScenario.launch<FrameBoundPreviewTestActivity>(intent).use { scenario ->
            var ready = false
            for (attempt in 0..99) {
                scenario.onActivity { ready = it.texturePreview.isAvailable }
                if (ready) break
                Thread.sleep(50)
            }
            assertTrue("TextureView 未就绪", ready)
            scenario.onActivity {
                val view = it.texturePreview
                val canvas = view.lockCanvas() ?: error("无法取得合成图画布")
                canvas.drawColor(Color.rgb(51, 51, 51))
                canvas.drawRect(120f, 0f, 240f, 426f, Paint().apply { color = Color.rgb(204, 204, 204) })
                view.unlockCanvasAndPost(canvas)
            }
            val adjustments = TexturePreviewAdjustments()
            val cases = listOf(Triple(false, 1f, false), Triple(true, 1f, false), Triple(true, 0.8f, false),
                Triple(false, 0.8f, false), Triple(false, 1f, false), Triple(false, 0.8f, true), Triple(false, 1f, true))
            for ((shadows, contrast, legacy) in cases) {
                scenario.onActivity {
                    adjustments.apply(it.texturePreview, if (legacy) 1f else contrast, shadows).getOrThrow()
                    if (legacy) adjustments.applyLegacyContrast(it.texturePreview, contrast)
                }
                val bitmap = Bitmap.createBitmap(240, 426, Bitmap.Config.ARGB_8888)
                try {
                    var matched = false
                    for (attempt in 0..19) {
                        val copied = LinkedBlockingQueue<Int>()
                        scenario.onActivity {
                            val location = IntArray(2)
                            it.texturePreview.getLocationInWindow(location)
                            PixelCopy.request(it.window, Rect(location[0], location[1], location[0] + 240, location[1] + 426),
                                bitmap, { result -> copied.offer(result) }, Handler(Looper.getMainLooper()))
                        }
                        assertEquals(PixelCopy.SUCCESS, copied.poll(5, TimeUnit.SECONDS))
                        matched = listOf(30 to 0.2f, 180 to 0.8f).all { (x, value) ->
                            val gain = if (shadows) ShadowEnhancement.gain(value, value, value) else 1f
                            val expected = (((value * gain - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f) * 255f)
                            abs(Color.red(bitmap.getPixel(x, 100)) - expected) < 4f
                        }
                        if (matched) break
                        Thread.sleep(50)
                    }
                    assertTrue("普通预览效果不符：暗部=$shadows 对比度=$contrast，像素=${Color.red(bitmap.getPixel(30, 100))}", matched)
                    scenario.onActivity {
                        val source = it.texturePreview.bitmap ?: error("算法取帧失败")
                        try { assertEquals("原图被修改：暗部=$shadows 对比度=$contrast 旧系统路径=$legacy", 51, Color.red(source.getPixel(30, 100))) }
                        finally { source.recycle() }
                    }
                } finally { bitmap.recycle() }
            }
        }
    }
}
