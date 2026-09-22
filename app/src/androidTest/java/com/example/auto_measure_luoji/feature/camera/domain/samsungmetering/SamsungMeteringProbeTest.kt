package com.example.auto_measure_luoji.feature.camera.domain.samsungmetering

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.feature.camera.domain.centermetering.CenterMeteringRequest
import com.example.auto_measure_luoji.feature.camera.domain.tapmetering.TapMeteringPoint
import com.example.auto_measure_luoji.feature.camera.ui.FrameBoundPreviewTestActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 显式启用的硬件探针，不持久化参数，不进入正式应用；4 的私有枚举含义尚未确认。 */
@RunWith(AndroidJUnit4::class)
class SamsungMeteringProbeTest {
    @Test fun requestFourReadBackAndRestore() {
        assumeTrue("需显式传入 samsungMeteringProbe=true", InstrumentationRegistry.getArguments()
            .getString("samsungMeteringProbe") == "true")
        assumeTrue("仅在三星设备运行", Build.MANUFACTURER.equals("samsung", ignoreCase = true))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        assertTrue("公开镜头 0 不存在", "0" in manager.cameraIdList)
        val chars = manager.getCameraCharacteristics("0")
        @Suppress("UNCHECKED_CAST")
        val listedRequestKey = chars.availableCaptureRequestKeys?.firstOrNull { it.name == KEY }
            as CaptureRequest.Key<Int>?
        @Suppress("UNCHECKED_CAST")
        val listedResultKey = chars.availableCaptureResultKeys?.firstOrNull { it.name == KEY }
            as CaptureResult.Key<Int>?
        Log.i(TAG, "镜头=0 请求字段=${listedRequestKey != null} 结果字段=${listedResultKey != null}")
        val direct = InstrumentationRegistry.getArguments().getString("allowUnlistedVendorKey") == "true"
        if (!direct) {
            assertNotNull("第三方请求列表未开放 meteringMode，停止写入", listedRequestKey)
            assertNotNull("结果列表未开放 meteringMode，无法验证回传", listedResultKey)
        }
        // 仅额外显式授权的探针尝试公共 Key 构造函数；不反射，不伪装包名，不扩大相机权限。
        val requestKey = listedRequestKey ?: CaptureRequest.Key(KEY, Int::class.javaObjectType)
        val resultKey = listedResultKey ?: CaptureResult.Key(KEY, Int::class.javaObjectType)
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.YUV_420_888)
        val size = Size(1920, 1080)
        assertTrue("测试所需 1920x1080 YUV 不支持", size in sizes)
        ActivityScenario.launch<FrameBoundPreviewTestActivity>(Intent(context,
            FrameBoundPreviewTestActivity::class.java).putExtra("texture_preview", true)).use {
            val thread = HandlerThread("SamsungMeterProbe").apply { start() }
            val handler = Handler(thread.looper)
            val alive = AtomicBoolean(true)
            val opened = LinkedBlockingQueue<CameraDevice>(1)
            val configured = LinkedBlockingQueue<CameraCaptureSession>(1)
            val errors = LinkedBlockingQueue<String>(16)
            val frames = LinkedBlockingQueue<TotalCaptureResult>(64)
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            var device: CameraDevice? = null
            var session: CameraCaptureSession? = null
            try {
                reader.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.close() }, handler)
                manager.openCamera("0", object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (!alive.get() || !opened.offer(camera)) camera.close()
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        errors.offer("相机断开"); camera.close()
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        errors.offer("相机错误=$error"); camera.close()
                    }
                }, handler)
                val camera = opened.poll(10, TimeUnit.SECONDS) ?: error("打开超时：${errors.peek()}")
                device = camera
                camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(value: CameraCaptureSession) {
                        if (!alive.get() || !configured.offer(value)) value.close()
                    }
                    override fun onConfigureFailed(value: CameraCaptureSession) {
                        errors.offer("会话配置失败"); value.close()
                    }
                }, handler)
                val captureSession = configured.poll(10, TimeUnit.SECONDS)
                    ?: error("配置超时：${errors.peek()}")
                session = captureSession
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                }
                val original = builder.get(requestKey) ?: error("模板没有原值，停止试验，避免无法精确恢复")
                Log.i(TAG, "模板模式=$original 直接构造字段=$direct")
                verifyToggleGuards(builder, original, requestKey)
                val modeRequest = SamsungMeteringRequest().apply { rememberTemplate(builder, Build.MANUFACTURER) }
                val metering = CenterMeteringRequest().apply { configure(chars); rememberTemplate(builder) }
                // 复用原有坐标换算；三个阶段的缓冲区归一化目标和 30% 窗口完全相同。
                // 此点不声称等同前一轮用户点击物体，仅用于同一会话内单变量对照。
                assertNotNull(metering.apply(builder, size, true, tap = TapMeteringPoint(0.65f, 0.6f)))
                val baseline = builder.build()
                val callback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        if (!frames.offer(result)) { frames.poll(); frames.offer(result) }
                    }
                    override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        errors.offer("采集失败=${failure.reason} 阶段=${request.tag}")
                    }
                }
                fun sample(label: String, mode: Int): List<TotalCaptureResult> {
                    frames.clear()
                    builder.setTag(label)
                    lateinit var request: CaptureRequest
                    modeRequest.submit(builder, enabled = label == "模式4", suspended = false) {
                        request = it
                        captureSession.setRepeatingRequest(it, callback, handler)
                    }
                    assertArrayEquals(baseline.get(CaptureRequest.CONTROL_AE_REGIONS), request.get(CaptureRequest.CONTROL_AE_REGIONS))
                    assertEquals(baseline.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION), request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION))
                    assertEquals(baseline.get(CaptureRequest.CONTROL_AF_MODE), request.get(CaptureRequest.CONTROL_AF_MODE))
                    assertEquals(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF, request.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE))
                    Log.i(TAG, "开始=$label 请求模式=$mode EV=${request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION)} 区域=${request.get(CaptureRequest.CONTROL_AE_REGIONS)?.contentToString()}")
                    val deadline = SystemClock.elapsedRealtime() + 8_000
                    var firstSensorNs: Long? = null
                    val samples = mutableListOf<TotalCaptureResult>()
                    while (SystemClock.elapsedRealtime() < deadline && samples.size < 12) {
                        errors.poll()?.let { error(it) }
                        val result = frames.poll(500, TimeUnit.MILLISECONDS) ?: continue
                        if (result.request.tag != label) continue
                        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: continue
                        if (firstSensorNs == null) firstSensorNs = timestamp
                        // 仅接收本阶段真实新帧，按传感器时间跳过前 3 秒 AE 过渡，不拼接上阶段回调。
                        if (timestamp - firstSensorNs!! < 3_000_000_000L) continue
                        samples.add(result)
                    }
                    assertEquals("$label 结果不足：${errors.peek()}", 12, samples.size)
                    for (result in samples) Log.i(TAG, "阶段=$label 帧=${result.frameNumber} 回传模式=${result.get(resultKey)} " +
                        "曝光ns=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} ISO=${result.get(CaptureResult.SENSOR_SENSITIVITY)} " +
                        "光圈=${result.get(CaptureResult.LENS_APERTURE)} AE=${result.get(CaptureResult.CONTROL_AE_STATE)}")
                    assertTrue("$label 回传模式未采用请求值 $mode", samples.all { it.get(resultKey) == mode })
                    modeRequest.onResult(request, samples.last())
                    assertTrue(modeRequest.status, modeRequest.status.contains("请求 $mode，回传 $mode"))
                    return samples
                }
                try {
                    sample("基线", original)
                    sample("模式4", 4)
                } finally {
                    // 即便设置 4 或断言失败，也尝试提交并验证原值；最外层始终释放硬件。
                    sample("恢复", original)
                }
            } finally {
                alive.set(false)
                session?.close()
                device?.close()
                while (true) (configured.poll() ?: break).close()
                while (true) (opened.poll() ?: break).close()
                reader.setOnImageAvailableListener(null, null)
                reader.close()
                thread.quitSafely()
                thread.join(5_000)
            }
        }
    }

    private fun verifyToggleGuards(builder: CaptureRequest.Builder, original: Int, key: CaptureRequest.Key<Int>) {
        val control = SamsungMeteringRequest()
        control.rememberTemplate(builder, "other")
        assertFalse(control.available)
        control.submit(builder, true, false) { assertEquals(original, it.get(key)) }
        control.rememberTemplate(builder, "samsung")
        assertTrue(control.available)
        control.submit(builder, false, false) { assertEquals(original, it.get(key)) }
        control.submit(builder, true, false) { assertEquals(4, it.get(key)) }
        control.submit(builder, true, true) { assertEquals(original, it.get(key)) }
        control.submit(builder, true, false) { assertEquals(4, it.get(key)) }
        control.submit(builder, false, false) { assertEquals(original, it.get(key)) }
        var attempts = 0
        control.submit(builder, true, false) {
            if (++attempts == 1) throw IllegalArgumentException("模拟厂商拒绝")
            assertEquals(original, it.get(key))
        }
        assertEquals(2, attempts)
        assertTrue(control.status.contains("请求失败"))
        control.submit(builder, true, false) { assertEquals(original, it.get(key)) }
        control.submit(builder, false, false) { assertEquals(original, it.get(key)) }
        control.submit(builder, true, false) { assertEquals(4, it.get(key)) }
        control.submit(builder, false, false) { assertEquals(original, it.get(key)) }
    }

    companion object {
        private const val TAG = "SamsungMeterProbe"
        private const val KEY = "samsung.android.control.meteringMode"
    }
}
