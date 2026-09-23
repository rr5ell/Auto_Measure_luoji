package com.example.auto_measure_luoji.feature.camera.ui.backlightbalance

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.view.TextureView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.feature.camera.ui.FrameBoundPreviewTestActivity
import com.example.auto_measure_luoji.feature.camera.ui.backlightshadow.TexturePreviewAdjustments
import com.example.auto_measure_luoji.feature.camera.ui.localtone.PreviewToneMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextureMeteringSourceTest {
    private fun <T> main(action: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { action() } }

    private fun withPreview(test: (TextureView) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, FrameBoundPreviewTestActivity::class.java).putExtra("texture_preview", true)
        ActivityScenario.launch<FrameBoundPreviewTestActivity>(intent).use { scenario ->
            lateinit var view: TextureView
            scenario.onActivity { view = it.texturePreview }
            for (attempt in 0..99) {
                if (main { view.isAvailable }) break
                Thread.sleep(50)
            }
            assertTrue(main { view.isAvailable })
            main {
                val canvas = view.lockCanvas() ?: error("取画布失败")
                canvas.drawColor(Color.rgb(51, 51, 51))
                canvas.drawRect(120f, 0f, 240f, 426f, Paint().apply { color = Color.rgb(204, 204, 204) })
                view.unlockCanvasAndPost(canvas)
            }
            var ready = false
            for (attempt in 0..99) {
                ready = main {
                    val bitmap = view.bitmap ?: return@main false
                    try { Color.red(bitmap.getPixel(30, 100)) == 51 && Color.red(bitmap.getPixel(180, 100)) == 204 }
                    finally { bitmap.recycle() }
                }
                if (ready) break
                Thread.sleep(50)
            }
            assertTrue("合成帧未更新", ready)
            test(view)
        }
    }

    @Test fun samplesTextureWithoutDisplayEffectsAndKeepsOriginalFrameAge() = withPreview { view ->
        val sampler = TextureMeteringSource()
        val ns = 2_000_000_000L
        val first = main {
            sampler.onFrame(view, 10, ns)
            sampler.sample(view, ns, 0)
        }!!
        assertEquals(60f, first.regions!!.left.mean, 1f)
        assertEquals(191f, first.regions!!.right.mean, 1f)
        assertEquals(ns, first.sampledAtNs)
        main {
            if (TexturePreviewAdjustments.supported) {
                TexturePreviewAdjustments().apply(view, 0.6f, false, PreviewToneMode.BACKLIGHT_BALANCE).getOrThrow()
            }
            // 同一帧重复回调不能刷新年龄或累加曝光确认。
            sampler.onFrame(view, 10, ns + 500_000_000L)
            assertSame(first, sampler.sample(view, ns + 500_000_000L, 0))
            assertNull(sampler.sample(view, ns + 1_500_000_001L, 0))
            assertNull(sampler.sample(view, ns, ns + 1))
            sampler.onFrame(view, 11, ns + 2_000_000_000L)
            val second = sampler.sample(view, ns + 2_000_000_000L, 0)!!
            assertEquals(first.regions, second.regions)
            sampler.invalidate(view)
            assertNull(sampler.sample(view, ns + 2_000_000_000L, 0))
        }
    }

    @Test fun unavailableOrDifferentViewNeverReusesPreviousSnapshot() = withPreview { view ->
        val sampler = TextureMeteringSource()
        main {
            assertNull(sampler.sample(view, 10, 0))
            sampler.onFrame(view, 1, 10)
            assertNotNull(sampler.sample(view, 10, 0))
            val other = TextureView(view.context)
            assertNull(sampler.sample(other, 10, 0))
            assertNull(sampler.sample(null, 10, 0))
            assertNull(sampler.sample(view, 9, 0))
            sampler.onFrame(other, 2, 20)
            assertNull(sampler.sample(view, 20, 0))
            assertNull(sampler.sample(other, 20, 0))
        }
    }
}
