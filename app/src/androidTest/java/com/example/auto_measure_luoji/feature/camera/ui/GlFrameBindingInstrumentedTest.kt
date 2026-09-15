package com.example.auto_measure_luoji.feature.camera.ui

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.view.Surface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.auto_measure_luoji.feature.camera.domain.RenderedPreviewFrame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GlFrameBindingInstrumentedTest {
    @Test fun drawsOnlyMatchedPairsAndKeepsOutputWhenMetadataIsLate() {
        val textures = LinkedBlockingQueue<SurfaceTexture>()
        val frames = LinkedBlockingQueue<RenderedPreviewFrame>()
        ActivityScenario.launch(FrameBoundPreviewTestActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.preview.setFrameSynchronized(true)
                it.preview.onCameraSurfaceTextureAvailable = { st -> textures.offer(st) }
                it.preview.canAcceptDiagnosticFrame = { true }
                it.preview.onDiagnosticFrame = { frame -> frames.offer(frame) }
                it.preview.setDiagnosticRecording(true)
            }
            val texture = textures.poll(5, TimeUnit.SECONDS) ?: error("GL texture unavailable")
            texture.setDefaultBufferSize(240, 426)
            val surface = Surface(texture)
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE), 0, configs, 0, 1, count, 0))
            val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            val window = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            try {
                check(EGL14.eglMakeCurrent(display, window, window, context))
                fun produce(ts: Long, gray: Float) {
                    GLES20.glViewport(0, 0, 240, 426)
                    GLES20.glClearColor(gray, gray, gray, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                    GLES20.glScissor(60, 100, 30, 100)
                    GLES20.glClearColor(1f, 1f, 1f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                    check(EGLExt.eglPresentationTimeANDROID(display, window, ts))
                    check(EGL14.eglSwapBuffers(display, window))
                }
                fun submit(ts: Long, dx: Float = 0f, rotation: Float = 0f) {
                    scenario.onActivity { it.preview.setPreviewTransform(1f, dx, 0f, rotation, 240f, 426f, ts) }
                }
                submit(1_000_000_000L)
                produce(1_000_000_000L, 0.2f)
                val first = frames.poll(5, TimeUnit.SECONDS) ?: error("first draw missing")
                assertEquals(first.sourceTimestampNs, first.transformTimestampNs)
                assertTrue(first.output.luma.max() - first.output.luma.min() > 150)
                assertTrue(first.raw.luma.indices.all { abs(first.raw.luma[it] - first.output.luma[it]) <= 1 })

                submit(1_066_666_666L, 15f)
                produce(1_033_333_333L, 0.4f)
                assertNull("future transform used on earlier texture", frames.poll(250, TimeUnit.MILLISECONDS))
                submit(1_033_333_333L)
                val second = frames.poll(5, TimeUnit.SECONDS) ?: error("late matching transform missing")
                assertEquals(1_033_333_333L, second.sourceTimestampNs)
                assertEquals(second.sourceTimestampNs, second.transformTimestampNs)
                assertTrue(second.raw.luma[20] > first.raw.luma[20] + 30)
                submit(1_033_333_333L, 50f)
                assertNull("same image changed compensation twice", frames.poll(250, TimeUnit.MILLISECONDS))
                produce(1_066_666_666L, 0.6f)
                val third = frames.poll(5, TimeUnit.SECONDS) ?: error("future pair missing")
                assertEquals(1_066_666_666L, third.transformTimestampNs)
                assertTrue(third.raw.luma.indices.any { abs(third.raw.luma[it] - third.output.luma[it]) > 50 })

                submit(1_099_999_999L, rotation = 90f)
                produce(1_099_999_999L, 0.2f)
                val rotated = frames.poll(5, TimeUnit.SECONDS) ?: error("rotated pair missing")
                fun bounds(luma: IntArray, width: Int): IntArray {
                    val points = luma.indices.filter { luma[it] > 230 }
                    assertTrue("foreground missing", points.isNotEmpty())
                    return intArrayOf(points.minOf { it % width }, points.minOf { it / width },
                        points.maxOf { it % width }, points.maxOf { it / width })
                }
                val rawBounds = bounds(rotated.raw.luma, rotated.raw.sampleWidth)
                val outputBounds = bounds(rotated.output.luma, rotated.output.sampleWidth)
                // 竖屏像素空间刚性旋转 90 度后，矩形宽高互换，而非按 NDC 比例拉伸。
                assertEquals((rawBounds[3] - rawBounds[1]).toFloat(),
                    (outputBounds[2] - outputBounds[0]).toFloat(), 3f)
                assertEquals((rawBounds[2] - rawBounds[0]).toFloat(),
                    (outputBounds[3] - outputBounds[1]).toFloat(), 3f)
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, window)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
                surface.release()
            }
        }
    }
}
