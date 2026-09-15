package com.example.auto_measure_luoji.feature.camera.ui

import android.Manifest
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.Surface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.feature.camera.data.LensScanner
import com.example.auto_measure_luoji.feature.camera.domain.Camera2Controller
import com.example.auto_measure_luoji.feature.camera.domain.SelectLensUseCase
import com.example.auto_measure_luoji.feature.camera.domain.gldetection.GlDetectionBox
import com.example.auto_measure_luoji.feature.detection.domain.YoloDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GlYuvModelInstrumentedTest {
    @Test fun ordinaryCameraYuvRunsModelWithoutStoppingPreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val lens = SelectLensUseCase()(LensScanner(context).scan()) ?: error("没有后置相机")
        val textures = LinkedBlockingQueue<SurfaceTexture>()
        val errors = LinkedBlockingQueue<String>()
        val previewFrames = java.util.concurrent.atomic.AtomicInteger()
        val controller = Camera2Controller(context)
        controller.start()
        controller.onError = { errors.offer(it) }
        try {
            ActivityScenario.launch(FrameBoundPreviewTestActivity::class.java).use { scenario ->
                lateinit var preview: GlStabilizedPreviewView
                scenario.onActivity {
                    preview = it.preview
                    preview.layoutParams = android.widget.FrameLayout.LayoutParams(240, 426)
                    preview.onCameraSurfaceTextureAvailable = { st -> textures.offer(st) }
                    preview.onPreviewError = { message -> errors.offer(message) }
                    preview.setFrameSynchronized(true)
                    preview.setDetectionEnabled(true)
                    preview.setDiagnosticRecording(true)
                    preview.canAcceptDiagnosticFrame = { true }
                    preview.onDiagnosticFrame = { previewFrames.incrementAndGet(); true }
                    controller.onFrameTimingChanged = { timing ->
                        preview.setPreviewTransform(1.18f, 7f, -4f, 2f, 240f, 426f, timing.sensorTimestampNs)
                    }
                }
                val texture = textures.poll(10, TimeUnit.SECONDS) ?: error("GL 表面未创建")
                val surface = Surface(texture)
                // 测试使用本地 CPU 后端，避免模拟器的 GMS/GPU 能力影响相机与帧配对验证。
                YoloDetector(context, "bestV34_float32.tflite", "classes.txt", useGpu = false).use { model ->
                    try {
                        controller.setSurfaceTexture(texture)
                        controller.openCamera(lens, surface)
                        // 先确认相机/GL 已开始出帧，再测量推理期间是否继续推进。
                        // 这只是测试启动屏障，不要求任何 YUV 时间戳与 GL 匹配。
                        val previewDeadline = SystemClock.elapsedRealtime() + 15_000L
                        while (previewFrames.get() < 2 && SystemClock.elapsedRealtime() < previewDeadline) {
                            Thread.sleep(20)
                        }
                        assertTrue("camera preview did not start", previewFrames.get() >= 2)
                        val deadline = SystemClock.elapsedRealtime() + 30_000L
                        var presented = 0
                        var previousTimestamp = 0L
                        while (presented < 2 && SystemClock.elapsedRealtime() < deadline) {
                            assertTrue("相机错误：$errors", errors.isEmpty())
                            val image = controller.acquireLatestAnalysisImage()
                            if (image == null) { Thread.sleep(20); continue }
                            image.use { frame ->
                                val timestamp = frame.timestamp
                                val capturedElapsedNs = SystemClock.elapsedRealtimeNanos()
                                val before = previewFrames.get()
                                val result = model.detectFromYuv(frame, controller.sensorOrientation)
                                // 模型调用必须发生在 GL 注册之前；不能因缺少 GL 配对而跳过输入。
                                val ticket = runBlocking { preview.acquireDetectionFrame(
                                    timestamp, frame.width, frame.height, controller.sensorOrientation, capturedElapsedNs,
                                ) }
                                try {
                                    // 人为慢推理也不能让预览停住；过期模型结果允许丢弃。
                                    Thread.sleep(250)
                                    assertTrue("preview blocked by inference", previewFrames.get() > before)
                                    // GL 忙或结果过期均允许无框，但模型调用不能被跳过。
                                    val shown = ticket?.let { registered -> runBlocking {
                                        preview.presentDetection(registered, result.detections.map {
                                            GlDetectionBox(it.box.left, it.box.top, it.box.right, it.box.bottom, it.label)
                                        })
                                    } }
                                    assertNull("expired inference result remained visible", shown)
                                    assertTrue(timestamp > previousTimestamp)
                                    previousTimestamp = timestamp
                                    presented++
                                } finally { ticket?.let { preview.cancelDetectionFrame(it) } }
                            }
                        }
                        assertEquals("未完成两帧异步 YUV 模型推理", 2, presented)
                        assertNotNull(controller.analysisImageReader)
                    } finally {
                        controller.closeCamera()
                        surface.release()
                    }
                }
            }
        } finally {
            controller.closeCamera()
            controller.stop()
        }
    }
}
