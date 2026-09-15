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
import android.view.Display
import android.view.Surface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.feature.camera.data.LensScanner
import com.example.auto_measure_luoji.feature.camera.domain.Camera2Controller
import com.example.auto_measure_luoji.feature.camera.domain.RenderedPreviewFrame
import com.example.auto_measure_luoji.feature.camera.domain.SelectLensUseCase
import com.example.auto_measure_luoji.feature.camera.domain.hdr.HdrPreviewCapabilities
import com.example.auto_measure_luoji.feature.camera.ui.FrameBoundPreviewTestActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HdrPreviewInstrumentedTest {
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
                scenario.onActivity { activity ->
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
                    assertNull(controller.analysisImageReader)
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
