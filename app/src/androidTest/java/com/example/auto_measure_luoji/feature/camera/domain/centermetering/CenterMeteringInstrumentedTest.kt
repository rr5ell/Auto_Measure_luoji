package com.example.auto_measure_luoji.feature.camera.domain.centermetering

import android.Manifest
import android.content.Context
import android.graphics.Rect
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import com.example.auto_measure_luoji.feature.camera.domain.tapmetering.TapMeteringPoint
import com.example.auto_measure_luoji.feature.camera.domain.tapfocus.TapFocusRequest
import com.example.auto_measure_luoji.feature.camera.domain.manualsensor.ManualExposure
import com.example.auto_measure_luoji.feature.camera.domain.manualsensor.ManualSensorRequest

@RunWith(AndroidJUnit4::class)
class CenterMeteringInstrumentedTest {
    @Test fun realTemplateRegionsRestoreAndOtherControlsStayUnchanged() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.first()
        val chars = manager.getCameraCharacteristics(id)
        val handlerThread = HandlerThread("CenterMeteringTest").apply { start() }
        val opened = LinkedBlockingQueue<CameraDevice>()
        val errors = LinkedBlockingQueue<Int>()
        var device: CameraDevice? = null
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { opened.offer(camera) }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { errors.offer(error); camera.close() }
            }, Handler(handlerThread.looper))
            device = opened.poll(10, TimeUnit.SECONDS) ?: error("打开相机失败：${errors.peek()}")
            verifyTapFocusRequest(device, chars)
            verifyManualSensorTemplate(device, chars)
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            val defaults = builder.get(CaptureRequest.CONTROL_AE_REGIONS)
            val af = builder.get(CaptureRequest.CONTROL_AF_MODE)
            val ev = builder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION)
            val lock = builder.get(CaptureRequest.CONTROL_AE_LOCK)
            val eis = builder.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE)
            val state = CenterMeteringRequest()
            assertNull(state.supported)
            state.configure(chars)
            state.rememberTemplate(builder)
            fun assertDefaultsRestored() {
                val actual = builder.get(CaptureRequest.CONTROL_AE_REGIONS)
                if (defaults?.any { it.meteringWeight > 0 && it.width > 0 && it.height > 0 } == true) {
                    assertArrayEquals(defaults, actual)
                } else {
                    assertNotNull(actual)
                    assertTrue(actual!!.all { it.meteringWeight == 0 && it.width > 0 && it.height > 0 })
                }
            }
            if (state.supported == true) {
                val bounds = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
                builder.set(CaptureRequest.SCALER_CROP_REGION, bounds)
                val first = state.apply(builder, Size(1920, 1080), true)!!
                assertEquals(first, builder.get(CaptureRequest.CONTROL_AE_REGIONS)!![0].rect)
                assertEquals(1000, builder.get(CaptureRequest.CONTROL_AE_REGIONS)!![0].meteringWeight)
                val crop = Rect(bounds.centerX() - bounds.width() / 4, bounds.centerY() - bounds.height() / 4,
                    bounds.centerX() + bounds.width() / 4, bounds.centerY() + bounds.height() / 4)
                builder.set(CaptureRequest.SCALER_CROP_REGION, crop)
                val zoomed = state.apply(builder, Size(1920, 1080), true)!!
                assertTrue(crop.contains(zoomed))
                assertTrue(zoomed.width() < first.width())
                val tap = state.apply(builder, Size(1920, 1080), true, tap = TapMeteringPoint(0.8f, 0.2f))!!
                assertTrue(tap.centerX() > zoomed.centerX())
                assertTrue(tap.centerY() < zoomed.centerY())
                assertEquals(CaptureRequest.CONTROL_AE_MODE_ON, builder.get(CaptureRequest.CONTROL_AE_MODE))
                if (chars.availableCaptureRequestKeys?.contains(CaptureRequest.CONTROL_AE_LOCK) == true)
                    assertEquals(false, builder.get(CaptureRequest.CONTROL_AE_LOCK))
                assertNull(state.apply(builder, Size(1920, 1080), false))
                assertDefaultsRestored()
                verifyActualRegions(device, chars, state, Handler(handlerThread.looper))
                state.apply(builder, Size(1920, 1080), true)
                state.reject(builder)
                assertEquals(false, state.supported)
                assertDefaultsRestored()
            } else {
                assertNull(state.apply(builder, Size(1920, 1080), true))
                assertArrayEquals(defaults, builder.get(CaptureRequest.CONTROL_AE_REGIONS))
            }
            assertEquals(af, builder.get(CaptureRequest.CONTROL_AF_MODE))
            assertEquals(ev, builder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION))
            assertEquals(lock, builder.get(CaptureRequest.CONTROL_AE_LOCK))
            assertEquals(eis, builder.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE))
        } finally {
            device?.close()
            handlerThread.quitSafely()
            handlerThread.join(5_000)
        }
    }

    private fun verifyTapFocusRequest(device: CameraDevice, chars: CameraCharacteristics) {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        val focus = TapFocusRequest().apply { configure(chars) }
        builder.set(CaptureRequest.CONTROL_AF_MODE, focus.previewMode)
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        focus.rememberTemplate(builder)
        val baseline = builder.build()
        val bounds = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        val region = Rect(bounds.centerX() - 50, bounds.centerY() - 50, bounds.centerX() + 50, bounds.centerY() + 50)
        val single = mutableListOf<CaptureRequest>()
        val repeating = mutableListOf<CaptureRequest>()
        val token = focus.update(builder, region, 1, { single.add(it) }, { repeating.add(it) })
        if (token == null) {
            assertTrue(focus.status.contains("不支持"))
            assertTrue(single.isEmpty())
            return
        }
        assertEquals(listOf(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL, CaptureRequest.CONTROL_AF_TRIGGER_START),
            single.map { it.get(CaptureRequest.CONTROL_AF_TRIGGER) })
        assertEquals(CaptureRequest.CONTROL_AF_TRIGGER_IDLE, repeating.last().get(CaptureRequest.CONTROL_AF_TRIGGER))
        assertEquals(region, repeating.last().get(CaptureRequest.CONTROL_AF_REGIONS)!![0].rect)
        assertEquals(CaptureRequest.CONTROL_AF_MODE_AUTO, repeating.last().get(CaptureRequest.CONTROL_AF_MODE))
        assertNull(focus.update(builder, region, 1, { single.add(it) }, { repeating.add(it) }))
        assertEquals(2, single.size)
        val next = focus.update(builder, region, 2, { single.add(it) }, { repeating.add(it) })!!
        assertEquals(4, single.size)
        focus.expire(builder, token, { single.add(it) }, { repeating.add(it) })
        assertEquals(4, single.size)
        focus.expire(builder, next, { single.add(it) }, { repeating.add(it) })
        assertEquals(5, single.size)
        assertEquals(baseline.get(CaptureRequest.CONTROL_AF_MODE), builder.get(CaptureRequest.CONTROL_AF_MODE))
        assertTrue(builder.get(CaptureRequest.CONTROL_AF_REGIONS)!!.all { it.meteringWeight == 0 })
        assertNull(focus.update(builder, region, 2, { single.add(it) }, { repeating.add(it) }))
        focus.update(builder, region, 3, { single.add(it) }, { repeating.add(it) })
        focus.update(builder, null, 3, { single.add(it) }, { repeating.add(it) })
        assertEquals(baseline.get(CaptureRequest.CONTROL_AF_MODE), builder.get(CaptureRequest.CONTROL_AF_MODE))
        try {
            focus.update(builder, region, 4, {
                if (it.get(CaptureRequest.CONTROL_AF_TRIGGER) == CaptureRequest.CONTROL_AF_TRIGGER_START)
                    throw IllegalArgumentException("模拟 AF 拒绝")
            }, { repeating.add(it) })
            fail("AF 拒绝应报告")
        } catch (_: IllegalArgumentException) {
            assertTrue(focus.status.contains("已恢复"))
        }
        assertEquals(CaptureRequest.CONTROL_AF_TRIGGER_IDLE, builder.get(CaptureRequest.CONTROL_AF_TRIGGER))
        assertEquals(baseline.get(CaptureRequest.CONTROL_AF_MODE), builder.get(CaptureRequest.CONTROL_AF_MODE))
        assertArrayEquals(baseline.get(CaptureRequest.CONTROL_AE_REGIONS), builder.get(CaptureRequest.CONTROL_AE_REGIONS))
        assertEquals(baseline.get(CaptureRequest.CONTROL_AE_MODE), builder.get(CaptureRequest.CONTROL_AE_MODE))
        assertEquals(baseline.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION), builder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION))
        assertEquals(baseline.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE), builder.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE))
        try {
            focus.update(builder, region, 5, {}, { throw IllegalArgumentException("模拟恢复提交失败") })
            fail("恢复失败应报告")
        } catch (_: IllegalArgumentException) {
            assertEquals("对焦：恢复失败", focus.status)
        }
        var recoverySent = false
        focus.update(builder, null, 5, {}, { recoverySent = true })
        assertTrue("关闭必须重试失败的恢复", recoverySent)
        assertEquals(baseline.get(CaptureRequest.CONTROL_AF_MODE), builder.get(CaptureRequest.CONTROL_AF_MODE))
    }

    private fun verifyManualSensorTemplate(device: CameraDevice, chars: CameraCharacteristics) {
        val sensor = ManualSensorRequest().apply { configure(chars) }
        if (!sensor.capabilities.supported) return
        val caps = sensor.capabilities
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        val baseline = builder.build()
        val metering = CenterMeteringRequest().apply { configure(chars); rememberTemplate(builder) }
        val focus = TapFocusRequest().apply { configure(chars); rememberTemplate(builder) }
        val selection = ManualExposure(10_000_000L.coerceIn(caps.minTimeNs, caps.maxTimeNs),
            100.coerceIn(caps.minIso, caps.maxIso), if (caps.variableAperture) caps.apertures.first() else null)
        metering.apply(builder, Size(1920, 1080), true, tap = TapMeteringPoint(0.5f, 0.5f))
        sensor.select(builder, selection)
        val region = metering.apply(builder, Size(1920, 1080), true, tap = TapMeteringPoint(0.5f, 0.5f), allowTapAe = false)
        sensor.apply(builder)
        fun verify(request: CaptureRequest) {
            assertEquals(CaptureRequest.CONTROL_AE_MODE_OFF, request.get(CaptureRequest.CONTROL_AE_MODE))
            assertEquals(selection.timeNs, request.get(CaptureRequest.SENSOR_EXPOSURE_TIME))
            assertEquals(selection.iso, request.get(CaptureRequest.SENSOR_SENSITIVITY))
            assertEquals(baseline.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE), request.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE))
        }
        verify(builder.build())
        val token = focus.update(builder, region, 1, ::verify, ::verify)
        token?.let { focus.expire(builder, it, ::verify, ::verify) }
        sensor.select(builder, null)
        assertNull(sensor.selection)
        assertEquals(baseline.get(CaptureRequest.CONTROL_AE_MODE), builder.get(CaptureRequest.CONTROL_AE_MODE))
        assertEquals(baseline.get(CaptureRequest.SENSOR_EXPOSURE_TIME), builder.get(CaptureRequest.SENSOR_EXPOSURE_TIME))
        assertEquals(baseline.get(CaptureRequest.SENSOR_SENSITIVITY), builder.get(CaptureRequest.SENSOR_SENSITIVITY))
        assertEquals(baseline.get(CaptureRequest.SENSOR_FRAME_DURATION), builder.get(CaptureRequest.SENSOR_FRAME_DURATION))
        assertEquals(baseline.get(CaptureRequest.LENS_APERTURE), builder.get(CaptureRequest.LENS_APERTURE))
    }

    private fun verifyActualRegions(device: CameraDevice, chars: CameraCharacteristics,
        state: CenterMeteringRequest, handler: Handler) {
        if (chars.availableCaptureResultKeys?.contains(CaptureResult.CONTROL_AE_REGIONS) != true) return
        val size = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.YUV_420_888).minBy { it.width.toLong() * it.height }
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        val configured = LinkedBlockingQueue<CameraCaptureSession>()
        var session: CameraCaptureSession? = null
        try {
            reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
            device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) { configured.offer(value) }
                override fun onConfigureFailed(value: CameraCaptureSession) { value.close() }
            }, handler)
            session = configured.poll(10, TimeUnit.SECONDS) ?: error("测试会话配置失败")
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(reader.surface) }
            state.rememberTemplate(builder)
            for (mode in listOf(1, 2, 3, 0)) {
                val enabled = mode != 0
                val point = when (mode) { 2 -> TapMeteringPoint(0.2f, 0.7f); 3 -> TapMeteringPoint(0.8f, 0.3f); else -> null }
                val expected = state.apply(builder, size, enabled, tap = point)
                val results = LinkedBlockingQueue<TotalCaptureResult>()
                session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        results.offer(result)
                    }
                }, handler)
                val result = results.poll(10, TimeUnit.SECONDS) ?: error("测光结果未返回")
                val regions = result.get(CaptureResult.CONTROL_AE_REGIONS) ?: error("设备未回传测光区域")
                if (enabled) {
                    assertTrue(regions.any { it.meteringWeight > 0 && Rect.intersects(it.rect, expected!!) })
                } else {
                    assertTrue("关闭后不能残留旧中心加权区域", regions.all { it.meteringWeight == 0 })
                }
            }
            val sensor = ManualSensorRequest().apply { configure(chars) }
            val caps = sensor.capabilities
            if (caps.supported) {
                for (requested in listOf(1_000_000L, 10_000_000L, 20_000_000L)) {
                    val value = ManualExposure(requested.coerceIn(caps.minTimeNs, caps.maxTimeNs), 100.coerceIn(caps.minIso, caps.maxIso))
                    sensor.select(builder, value)
                    sensor.apply(builder)
                    val results = LinkedBlockingQueue<TotalCaptureResult>()
                    session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            results.offer(result)
                        }
                    }, handler)
                    val result = results.poll(10, TimeUnit.SECONDS) ?: error("手动曝光未回传")
                    assertEquals(CaptureResult.CONTROL_AE_MODE_OFF, result.get(CaptureResult.CONTROL_AE_MODE))
                    assertEquals(value.timeNs.toDouble(), result.get(CaptureResult.SENSOR_EXPOSURE_TIME)!!.toDouble(), value.timeNs * 0.05)
                    assertEquals(value.iso.toDouble(), result.get(CaptureResult.SENSOR_SENSITIVITY)!!.toDouble(), value.iso * 0.05)
                    android.util.Log.i("ManualSensorTest", "requested=$value actualTime=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} actualISO=${result.get(CaptureResult.SENSOR_SENSITIVITY)} aperture=${result.get(CaptureResult.LENS_APERTURE)}")
                }
                sensor.select(builder, null)
                assertEquals(CaptureRequest.CONTROL_AE_MODE_ON, builder.get(CaptureRequest.CONTROL_AE_MODE))
            }
        } finally {
            session?.close()
            reader.close()
        }
    }
}
