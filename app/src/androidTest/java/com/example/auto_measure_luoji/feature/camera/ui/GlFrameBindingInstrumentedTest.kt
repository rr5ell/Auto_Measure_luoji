package com.example.auto_measure_luoji.feature.camera.ui

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.view.Surface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.auto_measure_luoji.feature.camera.domain.RenderedPreviewFrame
import com.example.auto_measure_luoji.feature.camera.domain.gldetection.GlDetectionBox
import kotlinx.coroutines.runBlocking
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
        lateinit var preview: GlStabilizedPreviewView
        ActivityScenario.launch(FrameBoundPreviewTestActivity::class.java).use { scenario ->
            scenario.onActivity {
                preview = it.preview
                // 合成图测试使用固定像素尺寸，避免软件 GPU 全屏回读超过结果有效期。
                it.preview.layoutParams = android.widget.FrameLayout.LayoutParams(240, 426)
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
                    // 此接口内部 queueEvent；逐帧测试不经过 ActivityScenario 的主线程空闲屏障。
                    preview.setPreviewTransform(1f, dx, 0f, rotation, 240f, 426f, ts)
                }
                // 等待首次 Surface 尺寸稳定；冷启动布局可能重建 GL 缓存。
                var warmed = false
                for (i in 1..5) {
                    val ts = 500_000_000L + i * 10_000_000L
                    submit(ts)
                    produce(ts, 0.2f)
                    if (frames.poll(1, TimeUnit.SECONDS) != null) { warmed = true; break }
                }
                assertTrue("GL warmup failed", warmed)
                frames.clear()
                preview.queueEvent { preview.previewFrameRate.reset(0L) }
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
                val fpsSamples = LinkedBlockingQueue<Double>()
                preview.queueEvent { fpsSamples.offer(preview.previewFrameRate.sample(1_000_000_000L)!!) }
                assertEquals(2.0, fpsSamples.poll(5, TimeUnit.SECONDS)!!, 0.001)
                preview.requestRender()
                preview.queueEvent { fpsSamples.offer(preview.previewFrameRate.sample(2_000_000_000L)!!) }
                assertEquals(0.0, fpsSamples.poll(5, TimeUnit.SECONDS)!!, 0.001)
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

                // 故意不提交推理结果，后续六帧必须持续显示，不能冻结在被识别的旧图像。
                preview.setDetectionEnabled(true)
                submit(2_000_000_000L, 15f)
                produce(2_000_000_000L, 0.2f)
                assertEquals(2_000_000_000L, frames.poll(5, TimeUnit.SECONDS)!!.sourceTimestampNs)
                val slowTicket = runBlocking { preview.acquireDetectionFrame(2_000_000_000L, 240, 426, 0) }
                    ?: error("source metadata missing")
                var previous = 2_000_000_000L
                for (i in 1..6) {
                    val ts = 2_000_000_000L + i * 33_333_334L
                    submit(ts, 70f)
                    produce(ts, 0.7f)
                    val advanced = frames.poll(5, TimeUnit.SECONDS) ?: error("preview waited for YOLO")
                    assertTrue(advanced.sourceTimestampNs > previous)
                    assertTrue(advanced.raw.luma[20] > 140)
                    previous = advanced.sourceTimestampNs
                }
                assertNull("expired result accepted", runBlocking {
                    preview.presentDetection(slowTicket, listOf(GlDetectionBox(60f, 226f, 90f, 326f, "")))
                })

                submit(3_000_000_000L, 15f)
                produce(3_000_000_000L, 0.2f)
                frames.poll(5, TimeUnit.SECONDS) ?: error("fresh frame missing")
                val ticket = runBlocking { preview.acquireDetectionFrame(3_000_000_000L, 240, 426, 0) }
                    ?: error("fresh metadata missing")
                val presentation = runBlocking {
                    preview.presentDetection(ticket, listOf(GlDetectionBox(60f, 226f, 90f, 326f, "")))
                } ?: error("fresh asynchronous result rejected")
                submit(3_016_666_666L, 15f)
                produce(3_016_666_666L, 0.4f)
                val detected = frames.poll(5, TimeUnit.SECONDS) ?: error("asynchronous draw missing")
                assertEquals(3_000_000_000L, presentation.timestampNs)
                assertTrue(detected.sourceTimestampNs > presentation.timestampNs)
                assertEquals(detected.sourceTimestampNs, detected.transformTimestampNs)
                val white = bounds(detected.output.luma, detected.output.sampleWidth)
                val points = presentation.boxes.single().points
                val sx = detected.output.sampleWidth.toFloat() / presentation.viewWidth
                val sy = detected.output.sampleHeight.toFloat() / presentation.viewHeight
                assertEquals(points.minOf { it.x } * sx, white[0].toFloat(), 3f)
                assertEquals(points.minOf { it.y } * sy, white[1].toFloat(), 3f)
                assertEquals(points.maxOf { it.x } * sx, white[2].toFloat(), 3f)
                assertEquals(points.maxOf { it.y } * sy, white[3].toFloat(), 3f)
                assertNull("consumed ticket reused", runBlocking { preview.presentDetection(ticket, emptyList()) })

                // 软件 GPU 首次文字/混合着色器初始化可能耗时数秒，先预热再验证新帧。
                // 不延长生产结果有效期；预热结果允许正常过期。
                for (ts in listOf(3_500_000_000L, 3_700_000_000L)) {
                    // 分别预热屏幕合成与诊断 FBO 合成/回读。
                    submit(ts)
                    produce(ts, 0.2f)
                    frames.poll(5, TimeUnit.SECONDS) ?: error("label warmup source missing")
                    val warmTicket = runBlocking { preview.acquireDetectionFrame(ts, 240, 426, 0) }!!
                    runBlocking { preview.presentDetection(warmTicket,
                        listOf(GlDetectionBox(60f, 226f, 90f, 326f, "旗杆 0.99"))) }
                    submit(ts + 16_666_666L)
                    produce(ts + 16_666_666L, 0.2f)
                    frames.poll(10, TimeUnit.SECONDS) ?: error("label warmup draw missing")
                }

                submit(4_000_000_000L)
                produce(4_000_000_000L, 0.2f)
                frames.poll(5, TimeUnit.SECONDS) ?: error("label source missing")
                val labelTicket = runBlocking { preview.acquireDetectionFrame(4_000_000_000L, 240, 426, 0) }
                    ?: error("label metadata missing")
                val labeledPresentation = runBlocking { preview.presentDetection(labelTicket,
                    listOf(GlDetectionBox(60f, 226f, 90f, 326f, "旗杆 0.99"))) } ?: error("label result missing")
                submit(4_016_666_666L)
                produce(4_016_666_666L, 0.2f)
                val labeled = frames.poll(5, TimeUnit.SECONDS) ?: error("label pixels missing")
                val top = labeledPresentation.boxes.single().points.minOf { it.y } *
                    labeled.output.sampleHeight / labeledPresentation.viewHeight
                val labelMax = labeled.output.luma.indices.filter { it / labeled.output.sampleWidth < top - 4f }
                    .maxOf { labeled.output.luma[it] }
                assertTrue("label not composited: max=$labelMax top=$top view=${labeledPresentation.viewWidth}x${labeledPresentation.viewHeight} remainingMs=${(labeledPresentation.expiresElapsedNs - android.os.SystemClock.elapsedRealtimeNanos()) / 1_000_000L}",
                    labeled.output.luma.indices.any { it / labeled.output.sampleWidth < top - 4f &&
                        labeled.output.luma[it] > 200 })

                // 新的空结果立即替换旧框，旧票据也不能跨关闭/开启模式继续使用。
                val emptyTicket = runBlocking { preview.acquireDetectionFrame(4_016_666_666L, 240, 426, 0) }!!
                val emptyPresentation = runBlocking { preview.presentDetection(emptyTicket, emptyList()) }
                    ?: error("fresh empty result rejected")
                assertTrue(emptyPresentation.boxes.isEmpty())
                submit(4_033_333_333L)
                produce(4_033_333_333L, 0.2f)
                val cleared = frames.poll(5, TimeUnit.SECONDS) ?: error("clear draw missing")
                assertFalse("old label remained after empty result",
                    cleared.output.luma.indices.any { it / cleared.output.sampleWidth < top - 4f &&
                        cleared.output.luma[it] > 200 })
                val cancelled = runBlocking { preview.acquireDetectionFrame(4_033_333_333L, 240, 426, 0) }!!
                preview.setDetectionEnabled(false)
                preview.setDetectionEnabled(true)
                assertNull("old result crossed detection generation",
                    runBlocking { preview.presentDetection(cancelled, emptyList()) })
                // YUV 帧可能从未被 GL 消费；异步显示不能再强求源时间戳精确命中。
                submit(4_066_666_666L)
                produce(4_066_666_666L, 0.2f)
                frames.poll(5, TimeUnit.SECONDS) ?: error("skipped-source preview missing")
                val skippedSource = runBlocking { preview.acquireDetectionFrame(4_050_000_000L, 240, 426, 0) }
                    ?: error("unrendered YUV source was rejected")
                val skippedPresentation = runBlocking { preview.presentDetection(skippedSource,
                    listOf(GlDetectionBox(60f, 226f, 90f, 326f, ""))) }
                    ?: error("unrendered YUV result missing")
                assertEquals(4_050_000_000L, skippedPresentation.timestampNs)
                assertEquals(4_066_666_666L, skippedPresentation.previewTimestampNs)

                // 上屏对比度必须改变像素，但不能改变供算法读取的输出缓存。
                preview.setDetectionEnabled(false)
                for ((index, contrast) in listOf(1f, 0.5f, 2f, 1f).withIndex()) {
                    preview.setPreviewContrast(contrast)
                    val ts = 5_000_000_000L + index * 100_000_000L
                    submit(ts)
                    produce(ts, 0.2f)
                    val unmodified = frames.poll(5, TimeUnit.SECONDS) ?: error("对比度测试未出帧")
                    assertEquals(51f, unmodified.output.luma[20].toFloat(), 3f)
                    val bitmap = android.graphics.Bitmap.createBitmap(240, 426, android.graphics.Bitmap.Config.ARGB_8888)
                    try {
                        val copied = LinkedBlockingQueue<Int>()
                        val expected = (((0.2f - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f) * 255f)
                        var matched = false
                        for (attempt in 0..9) {
                            android.view.PixelCopy.request(preview, bitmap, { copied.offer(it) },
                                android.os.Handler(android.os.Looper.getMainLooper()))
                            assertEquals(android.view.PixelCopy.SUCCESS, copied.poll(5, TimeUnit.SECONDS))
                            if (abs(android.graphics.Color.red(bitmap.getPixel(10, 10)) - expected) < 4f) {
                                matched = true
                                break
                            }
                            Thread.sleep(50)
                        }
                        assertTrue("上屏对比度未生效：$contrast", matched)
                    } finally { bitmap.recycle() }
                }

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
