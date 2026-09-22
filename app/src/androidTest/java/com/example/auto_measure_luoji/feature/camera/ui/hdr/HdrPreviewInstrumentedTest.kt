package com.example.auto_measure_luoji.feature.camera.ui.hdr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.DataSpace
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.feature.camera.data.LensScanner
import com.example.auto_measure_luoji.feature.camera.data.LensCategory
import com.example.auto_measure_luoji.feature.camera.domain.Camera2Controller
import com.example.auto_measure_luoji.feature.camera.domain.RenderedPreviewFrame
import com.example.auto_measure_luoji.feature.camera.domain.SelectLensUseCase
import com.example.auto_measure_luoji.feature.camera.domain.hdr.HdrPreviewCapabilities
import com.example.auto_measure_luoji.feature.camera.ui.FrameBoundPreviewTestActivity
import com.example.auto_measure_luoji.feature.camera.ui.GlStabilizedPreviewView
import com.example.auto_measure_luoji.feature.camera.domain.gldetection.GlDetectionBox
import com.example.auto_measure_luoji.feature.camera.domain.tapmetering.TapMeteringPoint
import com.example.auto_measure_luoji.feature.detection.domain.YoloDetector
import com.example.auto_measure_luoji.feature.calibration.detector.LaserDetector
import com.example.auto_measure_luoji.feature.calibration.util.ImageUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HdrPreviewInstrumentedTest {
    @Test fun physicalHdrCalibrationKeepsHlgAcrossLensSwitches() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        assumeTrue(HlgPreviewGl.checkSupport(display).isSuccess)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val lenses = LensScanner(context).scan().filter {
            it.physicalId != null && HdrPreviewCapabilities.checkCamera(context, it, strictPhysical = true).isSuccess
        }.distinctBy { it.cameraId to it.physicalId }.sortedBy { it.eq35mm }
        assumeTrue("设备未开放两颗支持 HLG10 的物理镜头", lenses.size >= 2)
        val wide = lenses.firstOrNull { it.category == LensCategory.WIDE } ?: lenses.first()
        val tele = lenses.last()
        assumeTrue("缺少不同的广角和长焦镜头", wide != tele)
        val textures = LinkedBlockingQueue<SurfaceTexture>()
        val opened = LinkedBlockingQueue<Boolean>()
        val errors = LinkedBlockingQueue<String>()
        val controller = Camera2Controller(context)
        controller.start()
        controller.setHdrPreviewEnabled(true)
        controller.onCameraOpened = { opened.offer(true) }
        controller.onError = { errors.offer(it) }
        val intent = Intent(context, FrameBoundPreviewTestActivity::class.java).putExtra("hdr_preview", true)
        try {
            ActivityScenario.launch<FrameBoundPreviewTestActivity>(intent).use { scenario ->
                lateinit var preview: GlStabilizedPreviewView
                scenario.onActivity {
                    preview = it.preview
                    preview.onPreviewError = { message -> errors.offer(message) }
                    preview.onCameraSurfaceTextureAvailable = { texture -> textures.offer(texture) }
                    preview.setFrameSynchronized(false)
                }
                val texture = textures.poll(10, TimeUnit.SECONDS) ?: error("HDR GL 表面未创建")
                val surface = Surface(texture)
                try {
                    for (lens in listOf(wide, tele, wide)) {
                        controller.closeCamera()
                        controller.setZoomRatio(if (lens == wide) 5f else 1f)
                        controller.setSurfaceTexture(texture)
                        controller.openCamera(lens, surface, strictPhysical = true)
                        assertNotNull("HDR 物理会话失败：$errors", opened.poll(15, TimeUnit.SECONDS))
                        val deadline = SystemClock.elapsedRealtime() + 5000L
                        while (controller.latestSensorTimestampNs == 0L && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
                        assertTrue("当前物理镜头未出帧", controller.latestSensorTimestampNs > 0L)
                        // 校准锁定物理输出，不混入来自另一逻辑视角的 SDR 分析流。
                        assertNull(controller.analysisImageReader)
                        val bitmap = runBlocking { preview.capturePreviewBitmap(240, 426, controller.latestSensorTimestampNs) }
                        bitmap.recycle()
                        assertEquals(DataSpace.STANDARD_BT2020, DataSpace.getStandard(texture.dataSpace))
                        assertEquals(DataSpace.TRANSFER_HLG, DataSpace.getTransfer(texture.dataSpace))
                        assertTrue("HDR 校准发生错误：$errors", errors.isEmpty())
                    }
                } finally { controller.closeCamera(); surface.release() }
            }
        } finally { controller.closeCamera(); controller.stop() }
    }

    @Test fun missingDisplayDoesNotEnableHdr() {
        assertTrue(HlgPreviewGl.checkSupport(null).isFailure)
    }

    @Test fun hlgCameraFramesUseTenBitSurfaceAndExactTransforms() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val displaySupport = HlgPreviewGl.checkSupport(display)
        assumeTrue(displaySupport.exceptionOrNull()?.message, displaySupport.isSuccess)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val lens = SelectLensUseCase()(LensScanner(context).scan()) ?: error("没有后置镜头")
        val cameraSupport = HdrPreviewCapabilities.checkCamera(context, lens)
        assumeTrue(cameraSupport.exceptionOrNull()?.message, cameraSupport.isSuccess)
        val textures = LinkedBlockingQueue<SurfaceTexture>()
        val frames = LinkedBlockingQueue<RenderedPreviewFrame>()
        val errors = LinkedBlockingQueue<String>()
        val precision = LinkedBlockingQueue<IntArray>()
        val controller = Camera2Controller(context)
        controller.start()
        controller.setHdrPreviewEnabled(true)
        controller.onError = { errors.offer(it) }
        val intent = Intent(context, FrameBoundPreviewTestActivity::class.java).putExtra("hdr_preview", true)
        try {
            ActivityScenario.launch<FrameBoundPreviewTestActivity>(intent).use { scenario ->
                lateinit var preview: GlStabilizedPreviewView
                scenario.onActivity { activity ->
                    preview = activity.preview
                    activity.preview.onPreviewError = { errors.offer(it) }
                    activity.preview.onCameraSurfaceTextureAvailable = { textures.offer(it) }
                    activity.preview.setFrameSynchronized(true)
                    activity.preview.canAcceptDiagnosticFrame = { true }
                    activity.preview.onDiagnosticFrame = { frames.offer(it) }
                    activity.preview.setDiagnosticRecording(true)
                    controller.onFrameTimingChanged = { timing ->
                        activity.preview.setPreviewTransform(1f, 0f, 0f, 0f, 240f, 426f, timing.sensorTimestampNs)
                    }
                }
                val texture = textures.poll(10, TimeUnit.SECONDS) ?: error("HDR GL 表面未创建：$errors")
                val surface = Surface(texture)
                try {
                    controller.setSurfaceTexture(texture)
                    controller.openCamera(lens, surface)
                    var previousTimestamp = 0L
                    repeat(30) {
                        val frame = frames.poll(10, TimeUnit.SECONDS) ?: error("HDR 预览未出帧：$errors")
                        assertTrue("HDR 渲染错误：$errors", errors.isEmpty())
                        assertEquals(frame.sourceTimestampNs, frame.transformTimestampNs)
                        assertTrue(frame.sourceTimestampNs > previousTimestamp)
                        assertTrue("HDR 像素未输出", frame.output.luma.any { value -> value > 0 })
                        previousTimestamp = frame.sourceTimestampNs
                    }
                    assertEquals(DataSpace.STANDARD_BT2020, DataSpace.getStandard(texture.dataSpace))
                    assertEquals(DataSpace.TRANSFER_HLG, DataSpace.getTransfer(texture.dataSpace))
                    val calibrationBitmap = runBlocking { preview.capturePreviewBitmap() }
                    try {
                        assertEquals(1080, calibrationBitmap.width)
                        assertEquals(1920, calibrationBitmap.height)
                        val pixels = IntArray(1080 * 1920)
                        calibrationBitmap.getPixels(pixels, 0, 1080, 0, 0, 1080, 1920)
                        assertTrue("HDR 校准取帧为空", pixels.any { it and 0xFFFFFF != 0 })
                        val result = LaserDetector.detectLaserCenterFromBytes(ImageUtils.bitmapToPngBytes(calibrationBitmap))
                        result?.let {
                            assertTrue(it.cx.isFinite() && it.cx in 0.0..1079.0)
                            assertTrue(it.cy.isFinite() && it.cy in 0.0..1919.0)
                        }
                        assertEquals(DataSpace.TRANSFER_HLG, DataSpace.getTransfer(texture.dataSpace))
                    } finally { calibrationBitmap.recycle() }
                    val analysisSupported = HdrPreviewCapabilities.checkYuvAnalysis(context, lens).isSuccess
                    if (analysisSupported) {
                        assertNotNull("HDR session did not create YUV input", controller.analysisImageReader)
                        val point = TapMeteringPoint(0.65f, 0.6f)
                        controller.setCenterMeteringEnabled(false, tapEnabled = true, tapPoint = point).getOrThrow()
                        controller.highlightAnalysisEnabled = true
                        val sampleDeadline = SystemClock.elapsedRealtime() + 5000L
                        while (controller.latestHighlightStatistics?.tapZone?.valid != true &&
                            SystemClock.elapsedRealtime() < sampleDeadline) Thread.sleep(50)
                        val statistics = controller.latestHighlightStatistics ?: error("HDR 点击亮度分析未返回")
                        assertEquals(point, statistics.tapPoint)
                        assertTrue(statistics.tapZone?.valid == true)
                        assertTrue(statistics.globalZone?.valid == true)
                        assertEquals("测光：全画面 + 点击微调", controller.centerMeteringStatus)
                        assertEquals(DataSpace.TRANSFER_HLG, DataSpace.getTransfer(texture.dataSpace))
                        controller.highlightAnalysisEnabled = false
                        preview.setDetectionEnabled(true)
                        YoloDetector(context, "bestV34_float32.tflite", "classes.txt", useGpu = false).use { model ->
                            val source = controller.acquireLatestAnalysisImage() ?: error("HDR YUV input missing")
                            source.use { image ->
                                val captured = SystemClock.elapsedRealtimeNanos()
                                val result = model.detectFromYuv(image, controller.sensorOrientation)
                                assertTrue(result.timings.totalMs >= 0L)
                                val ticket = runBlocking { preview.acquireDetectionFrame(image.timestamp,
                                    image.width, image.height, controller.sensorOrientation, captured) }
                                ticket?.let {
                                    runBlocking { preview.presentDetection(it, result.detections.map { d ->
                                        GlDetectionBox(d.box.left, d.box.top, d.box.right, d.box.bottom, d.label)
                                    }) }
                                }
                            }
                        }
                        // 模型耗时不用于判定框是否可显示；用新帧合成确定性框验证 HDR 渲染准入。
                        var accepted = false
                        val deadline = SystemClock.elapsedRealtime() + 10_000L
                        while (!accepted && SystemClock.elapsedRealtime() < deadline) {
                            val source = controller.acquireLatestAnalysisImage()
                            if (source == null) { Thread.sleep(20); continue }
                            source.use { image ->
                                val captured = SystemClock.elapsedRealtimeNanos()
                                val ticket = runBlocking { preview.acquireDetectionFrame(image.timestamp,
                                    image.width, image.height, controller.sensorOrientation, captured) } ?: return@use
                                val w = if (controller.sensorOrientation % 180 == 0) image.width else image.height
                                val h = if (controller.sensorOrientation % 180 == 0) image.height else image.width
                                val presentation = runBlocking { preview.presentDetection(ticket,
                                    listOf(GlDetectionBox(w * 0.25f, h * 0.25f, w * 0.75f, h * 0.75f, "旗杆测试"))) }
                                accepted = presentation?.boxes?.size == 1
                            }
                        }
                        assertTrue("HDR rejected all fresh detection results", accepted)
                        frames.clear()
                        val afterInference = frames.poll(10, TimeUnit.SECONDS) ?: error("HDR stopped after inference")
                        assertTrue(afterInference.sourceTimestampNs > previousTimestamp)
                        assertTrue("HDR overlay caused GL failure: $errors", errors.isEmpty())
                        preview.setDetectionEnabled(false)
                    } else {
                        assertNull(controller.analysisImageReader)
                    }
                    scenario.onActivity { activity ->
                        activity.preview.queueEvent {
                            val bits = IntArray(1)
                            val colorspace = IntArray(1)
                            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                            GLES20.glGetIntegerv(GLES20.GL_RED_BITS, bits, 0)
                            val queried = EGL14.eglQuerySurface(EGL14.eglGetCurrentDisplay(),
                                EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW), 0x309D, colorspace, 0)
                            precision.offer(intArrayOf(bits[0], colorspace[0], if (queried) 1 else 0, GLES20.glGetError()))
                        }
                    }
                    val result = precision.poll(5, TimeUnit.SECONDS) ?: error("HDR 表面精度未返回")
                    assertTrue(result[0] >= 10)
                    assertEquals(0x3540, result[1])
                    assertEquals(1, result[2])
                    assertEquals(GLES20.GL_NO_ERROR, result[3])
                } finally {
                    controller.closeCamera()
                    surface.release()
                }
            }
        } finally {
            controller.closeCamera()
            controller.stop()
        }
    }
}
