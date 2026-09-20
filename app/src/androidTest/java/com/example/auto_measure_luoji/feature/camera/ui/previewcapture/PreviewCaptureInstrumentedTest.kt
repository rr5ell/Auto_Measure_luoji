package com.example.auto_measure_luoji.feature.camera.ui.previewcapture

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.os.SystemClock
import android.view.Surface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.auto_measure_luoji.feature.camera.ui.FrameBoundPreviewTestActivity
import com.example.auto_measure_luoji.feature.camera.ui.GlStabilizedPreviewView
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PreviewCaptureInstrumentedTest {
    @Test fun captureUsesFreshFrameAndPreservesOrientationAndDisplayTransform() {
        val textures = LinkedBlockingQueue<SurfaceTexture>()
        lateinit var preview: GlStabilizedPreviewView
        ActivityScenario.launch(FrameBoundPreviewTestActivity::class.java).use { scenario ->
            scenario.onActivity {
                preview = it.preview
                preview.setFrameSynchronized(false)
                preview.onCameraSurfaceTextureAvailable = { texture -> textures.offer(texture) }
            }
            val texture = textures.poll(10, TimeUnit.SECONDS) ?: error("GL 表面未创建")
            texture.setDefaultBufferSize(240, 426)
            val surface = Surface(texture)
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE), 0, configs, 0, 1, count, 0))
            val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            val window = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, window, window, context))
            try {
                fun barrier() {
                    val latch = CountDownLatch(1)
                    preview.queueEvent { latch.countDown() }
                    assertTrue(latch.await(5, TimeUnit.SECONDS))
                }
                fun produce() {
                    GLES20.glViewport(0, 0, 240, 426)
                    GLES20.glClearColor(0f, 1f, 0f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                    GLES20.glScissor(0, 213, 240, 213)
                    GLES20.glClearColor(1f, 0f, 0f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                    check(EGLExt.eglPresentationTimeANDROID(display, window, SystemClock.elapsedRealtimeNanos()))
                    check(EGL14.eglSwapBuffers(display, window))
                }
                repeat(4) { produce(); Thread.sleep(150) }
                // 已有缓存不能满足新采集；无新帧必须能超时取消，之后仍可采集。
                assertNull(runBlocking { withTimeoutOrNull(150) { preview.capturePreviewBitmap(120, 213) } })
                val bitmap = runBlocking {
                    val capture = async(start = CoroutineStart.UNDISPATCHED) { preview.capturePreviewBitmap(120, 213) }
                    barrier()
                    produce()
                    capture.await()
                }
                try {
                    assertEquals(120, bitmap.width)
                    assertEquals(213, bitmap.height)
                    assertTrue("上半幅方向或颜色错误", Color.red(bitmap.getPixel(60, 30)) > 240)
                    assertTrue("下半幅方向或颜色错误", Color.green(bitmap.getPixel(60, 180)) > 240)
                } finally { bitmap.recycle() }

                preview.setPreviewTransform(1f, 60f, 0f, 0f, 240f, 426f)
                barrier()
                val translated = runBlocking {
                    val capture = async(start = CoroutineStart.UNDISPATCHED) { preview.capturePreviewBitmap(120, 213) }
                    barrier()
                    produce()
                    capture.await()
                }
                try {
                    // 60/240 的屏幕平移归一化为 30/120，验证没有重复应用矩阵。
                    assertEquals(Color.BLACK, translated.getPixel(10, 30))
                    assertTrue(Color.red(translated.getPixel(60, 30)) > 240)
                } finally { translated.recycle() }
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, window)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
                EGL14.eglReleaseThread()
                surface.release()
            }
        }
    }
}
