package com.example.auto_measure.feature.camera.domain

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import com.example.auto_measure.feature.camera.data.LensCandidate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 相机控制器
 *
 * 支持双流输出：
 * - TextureView Surface：预览显示
 * - ImageReader Surface (YUV_420_888)：检测分析
 *
 * 防抖：EIS 永远关闭；OIS 默认关闭，只能由用户显式开关控制。
 */
class Camera2Controller(private val context: Context) {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var currentLens: LensCandidate? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var pendingZoomRatio: Float = 1f
    private var oisRequested: Boolean = false
    private var pendingExposureCompensationEv: Float = 0f
    private var exposureCompensationRange: Range<Int> = Range(0, 0)
    private var exposureCompensationStep: Rational = Rational(1, 1)

    var previewSize: Size = Size(1080, 1920)
        private set
    var sensorOrientation: Int = 0
        private set

    var onCameraOpened: (() -> Unit)? = null
    var onCameraClosed: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onOisStateChanged: ((Boolean) -> Unit)? = null
    var onOisEnableFailed: ((String) -> Unit)? = null
    var onZoomStateChanged: ((String) -> Unit)? = null
    var onExposureStateChanged: ((String) -> Unit)? = null

    val isActive: Boolean get() = cameraDevice != null
    private var surfaceTexture: SurfaceTexture? = null

    /** 检测用 ImageReader (YUV_420_888)，与预览分离 */
    var analysisImageReader: ImageReader? = null
        private set

    fun start() {
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    fun stop() {
        cameraThread?.quitSafely()
        try { cameraThread?.join() } catch (_: InterruptedException) {}
        cameraThread = null
        cameraHandler = null
    }

    fun setSurfaceTexture(st: SurfaceTexture) {
        surfaceTexture = st
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
    }

    @SuppressLint("MissingPermission")
    fun openCamera(lens: LensCandidate, surface: Surface, strictPhysical: Boolean = false) {
        currentLens = lens

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(lens.cameraId)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        updateExposureCompensationCharacteristics(chars)

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map != null) {
            val sizes = map.getOutputSizes(SurfaceTexture::class.java)
            previewSize = choosePreviewSize(sizes, 9.0 / 16.0)
        }

        try {
            surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
        } catch (_: Exception) {}

        Log.d("Camera2Ctrl", ">>> openCamera: id=${lens.cameraId}, phys=${lens.physicalId}, previewSize=${previewSize}, sensorOrient=$sensorOrientation")

        manager.openCamera(lens.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.d("Camera2Ctrl", ">>> onOpened: ${camera.id}")
                cameraDevice = camera
                createSession(camera, lens, surface, strictPhysical)
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
                onCameraClosed?.invoke()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
                onError?.invoke("Camera error: $error")
            }
        }, cameraHandler)
    }

    /**
     * 选择预览尺寸
     *
     * 优先选择竖屏尺寸（宽 < 高，如 1080x1920），避免旋转问题
     */
    private fun choosePreviewSize(sizes: Array<Size>?, targetAspect: Double): Size {
        if (sizes.isNullOrEmpty()) return Size(1080, 1920)

        val portrait = sizes.filter {
            it.width < it.height &&
            abs(it.width.toDouble() / it.height.toDouble() - targetAspect) < 0.02 &&
            it.height >= 1080
        }
        if (portrait.isNotEmpty()) {
            return portrait.minByOrNull { abs(it.height - 1920) } ?: portrait[0]
        }

        val portraitAny = sizes.filter {
            it.width < it.height &&
            abs(it.width.toDouble() / it.height.toDouble() - targetAspect) < 0.02
        }
        if (portraitAny.isNotEmpty()) {
            return portraitAny.maxByOrNull { it.height } ?: portraitAny[0]
        }

        val landscape = sizes.filter {
            it.width > it.height &&
            abs(it.height.toDouble() / it.width.toDouble() - targetAspect) < 0.02
        }
        if (landscape.isNotEmpty()) {
            return landscape.minByOrNull { abs(it.width - 1920) } ?: landscape[0]
        }

        return sizes.maxByOrNull { it.width * it.height } ?: Size(1080, 1920)
    }

    private fun createSession(
        camera: CameraDevice,
        lens: LensCandidate,
        surface: Surface,
        strictPhysical: Boolean,
    ) {
        // 创建检测用 ImageReader (YUV_420_888, maxImages=3)。
        // 严格物理镜头模式只绑定预览 Surface，避免混入逻辑输出导致物理 session 回退。
        analysisImageReader?.close()
        analysisImageReader = if (strictPhysical) {
            null
        } else {
            ImageReader.newInstance(
                previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 3
            ).apply {
                setOnImageAvailableListener({ reader ->
                    // 立即 drain + close，不阻塞相机管线
                    val image = reader.acquireLatestImage()
                    image?.close()
                }, cameraHandler)
            }
        }

        previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            analysisImageReader?.surface?.let { addTarget(it) }
        }

        val usePhysical = lens.physicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        if (usePhysical) createPhysicalSession(camera, lens, surface, strictPhysical)
        else createLogicalSession(camera, surface)
    }

    @SuppressLint("NewApi")
    private fun createPhysicalSession(
        camera: CameraDevice,
        lens: LensCandidate,
        surface: Surface,
        strictPhysical: Boolean,
    ) {
        try {
            val outputConfig = OutputConfiguration(surface).apply { setPhysicalCameraId(lens.physicalId!!) }
            val readerSurface = analysisImageReader?.surface
            val configs = if (readerSurface != null) {
                listOf(outputConfig, OutputConfiguration(readerSurface))
            } else listOf(outputConfig)
            val executor = java.util.concurrent.Executor { command -> cameraHandler?.post(command) }
            camera.createCaptureSession(SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, configs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) { captureSession = session; startPreview(session) }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (strictPhysical) onError?.invoke("Physical session configuration failed")
                        else createLogicalSession(camera, surface)
                    }
                }
            ))
        } catch (e: Exception) {
            if (strictPhysical) onError?.invoke(e.message ?: "Physical session failed")
            else createLogicalSession(camera, surface)
        }
    }

    private fun createLogicalSession(camera: CameraDevice, surface: Surface) {
        val surfaces = mutableListOf(surface)
        analysisImageReader?.surface?.let { surfaces.add(it) }
        camera.createCaptureSession(surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) { captureSession = session; startPreview(session) }
                override fun onConfigureFailed(session: CameraCaptureSession) { onError?.invoke("Session configuration failed") }
            }, cameraHandler)
    }

    private fun startPreview(session: CameraCaptureSession) {
        val builder = previewRequestBuilder ?: return
        val lens = currentLens ?: return

        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

        applyStabilization(builder, lens, notifyUnsupported = true)
        applyExposureCompensation(builder)

        // 初始 zoom。pendingZoomRatio 会跨 session 缓存，保证切回广角 5x 时不丢 zoom。
        val usedControlZoom = applyPreferredZoom(builder, lens, pendingZoomRatio)

        try {
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
            onCameraOpened?.invoke()
        } catch (e: Exception) {
            if (usedControlZoom) {
                Log.w("Camera2Ctrl", "CONTROL_ZOOM_RATIO failed on startPreview, fallback to SCALER_CROP_REGION", e)
                applyCropZoom(builder, lens, pendingZoomRatio)
                try {
                    session.setRepeatingRequest(builder.build(), null, cameraHandler)
                    onCameraOpened?.invoke()
                } catch (fallback: Exception) {
                    onError?.invoke(fallback.message ?: "Preview request failed")
                }
            } else {
                onError?.invoke(e.message ?: "Preview request failed")
            }
        }
    }

    /** 更新裁切区域（用于缩放） */
    fun setCropRegion(crop: android.graphics.Rect) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
        }
        builder.set(CaptureRequest.SCALER_CROP_REGION, crop)
        try { session.setRepeatingRequest(builder.build(), null, cameraHandler) } catch (_: Exception) {}
    }

    fun setZoomRatio(zoomRatio: Float) {
        val lens = currentLens
        pendingZoomRatio = zoomRatio.coerceAtLeast(1f)
        if (lens == null) return
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val usedControlZoom = applyPreferredZoom(builder, lens, pendingZoomRatio)
        try {
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            if (usedControlZoom) {
                Log.w("Camera2Ctrl", "CONTROL_ZOOM_RATIO failed, fallback to SCALER_CROP_REGION", e)
                applyCropZoom(builder, lens, pendingZoomRatio)
                try { session.setRepeatingRequest(builder.build(), null, cameraHandler) } catch (_: Exception) {}
            }
        }
    }

    fun setExposureCompensationEv(ev: Float): Result<Int> {
        pendingExposureCompensationEv = ev
        val builder = previewRequestBuilder ?: return Result.success(0)
        val session = captureSession ?: return Result.success(0)
        val compensationSteps = applyExposureCompensation(builder)
        return try {
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
            Result.success(compensationSteps)
        } catch (e: Exception) {
            Log.w("Camera2Ctrl", "Exposure compensation request failed", e)
            Result.failure(e)
        }
    }

    fun setOisEnabled(enabled: Boolean): Result<Unit> {
        if (!enabled) {
            oisRequested = false
            val ready = currentLens != null && previewRequestBuilder != null && captureSession != null
            if (!ready) {
                onOisStateChanged?.invoke(false)
                return Result.success(Unit)
            }
            return submitOisMode(enabled = false)
        }

        val lens = currentLens
            ?: return Result.failure(IllegalStateException("相机未就绪，无法开启 OIS"))
        if (!lens.oisSupported) {
            oisRequested = false
            onOisStateChanged?.invoke(false)
            return Result.failure(IllegalStateException("当前镜头不支持 OIS"))
        }

        oisRequested = true
        val result = submitOisMode(enabled = true)
        if (result.isFailure) {
            oisRequested = false
            submitOisMode(enabled = false)
        }
        return result
    }

    private fun submitOisMode(enabled: Boolean): Result<Unit> {
        val lens = currentLens
            ?: return Result.failure(IllegalStateException("相机未就绪，无法切换 OIS"))
        val builder = previewRequestBuilder
            ?: return Result.failure(IllegalStateException("预览请求未就绪，无法切换 OIS"))
        val session = captureSession
            ?: return Result.failure(IllegalStateException("相机会话未就绪，无法切换 OIS"))

        if (enabled && !lens.oisSupported) {
            return Result.failure(IllegalStateException("当前镜头不支持 OIS"))
        }

        applyStabilization(builder, lens, notifyUnsupported = false)
        return try {
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
            onOisStateChanged?.invoke(enabled && lens.oisSupported)
            Log.d("Camera2Ctrl", ">>> OIS ${if (enabled && lens.oisSupported) "ON" else "OFF"}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w("Camera2Ctrl", "OIS request failed", e)
            onOisStateChanged?.invoke(false)
            Result.failure(e)
        }
    }

    private fun applyStabilization(
        builder: CaptureRequest.Builder,
        lens: LensCandidate,
        notifyUnsupported: Boolean,
    ) {
        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )

        val shouldEnableOis = oisRequested && lens.oisSupported
        if (oisRequested && !lens.oisSupported) {
            oisRequested = false
            if (notifyUnsupported) onOisEnableFailed?.invoke("当前镜头不支持 OIS，已关闭")
        }

        builder.set(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            if (shouldEnableOis) {
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            } else {
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            },
        )
        onOisStateChanged?.invoke(shouldEnableOis)
    }

    private fun updateExposureCompensationCharacteristics(chars: CameraCharacteristics) {
        exposureCompensationRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?: Range(0, 0)
        exposureCompensationStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?: Rational(1, 1)
    }

    private fun applyExposureCompensation(builder: CaptureRequest.Builder): Int {
        val stepEv = exposureCompensationStep.toFloat().let { step ->
            if (step > 0f && !step.isNaN() && !step.isInfinite()) step else 1f
        }
        // Camera2 stores EV compensation as integer steps:
        // actualEV = CONTROL_AE_EXPOSURE_COMPENSATION * CONTROL_AE_COMPENSATION_STEP.
        val requestedSteps = (pendingExposureCompensationEv / stepEv).roundToInt()
        val clampedSteps = requestedSteps.coerceIn(
            exposureCompensationRange.lower,
            exposureCompensationRange.upper,
        )
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedSteps)
        publishExposureState(clampedSteps, stepEv)
        return clampedSteps
    }

    private fun publishExposureState(compensationSteps: Int, stepEv: Float) {
        val actualEv = compensationSteps * stepEv
        val message = String.format(
            Locale.US,
            "exposure: requestedEV=%.2f, actualEV=%.2f, steps=%d, step=%.4f, range=%d..%d",
            pendingExposureCompensationEv,
            actualEv,
            compensationSteps,
            stepEv,
            exposureCompensationRange.lower,
            exposureCompensationRange.upper,
        )
        Log.i("Camera2Ctrl", message)
        onExposureStateChanged?.invoke(message)
    }

    @SuppressLint("NewApi")
    private fun applyPreferredZoom(
        builder: CaptureRequest.Builder,
        lens: LensCandidate,
        zoomRatio: Float,
    ): Boolean {
        val requested = zoomRatio.coerceAtLeast(1f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isControlZoomAvailable(lens, requested)) {
            try {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, requested)
                // CONTROL_ZOOM_RATIO 已负责数码变焦；crop 置为 full active array，避免叠加二次裁切。
                builder.set(CaptureRequest.SCALER_CROP_REGION, lens.activeArray)
                publishZoomState("CONTROL_ZOOM_RATIO", requested, "range=${lens.zoomRatioRange}")
                return true
            } catch (e: Exception) {
                Log.w("Camera2Ctrl", "CONTROL_ZOOM_RATIO is not accepted by request builder, fallback to SCALER_CROP_REGION", e)
            }
        }

        applyCropZoom(builder, lens, requested)
        return false
    }

    @SuppressLint("NewApi")
    private fun applyCropZoom(
        builder: CaptureRequest.Builder,
        lens: LensCandidate,
        zoomRatio: Float,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
        }
        builder.set(CaptureRequest.SCALER_CROP_REGION, calculateCenteredCrop(lens, zoomRatio))
        publishZoomState("SCALER_CROP_REGION", zoomRatio.coerceAtLeast(1f), "max=${lens.maxDigitalZoom}")
    }

    private fun publishZoomState(backend: String, ratio: Float, detail: String) {
        val message = "zoom: backend=$backend, ratio=${String.format(Locale.US, "%.2f", ratio)}, $detail"
        Log.i("Camera2Ctrl", message)
        Log.d("Camera2Ctrl", ">>> $message")
        onZoomStateChanged?.invoke(message)
    }

    private fun isControlZoomAvailable(lens: LensCandidate, zoomRatio: Float): Boolean {
        val range = lens.zoomRatioRange
        return zoomRatio >= range.lower && zoomRatio <= range.upper
    }

    private fun calculateCenteredCrop(lens: LensCandidate, zoomRatio: Float): android.graphics.Rect {
        val array = lens.activeArray
        val zoom = zoomRatio.coerceIn(1f, lens.maxDigitalZoom.coerceAtLeast(1f))
        val cropW = ((array.width() / zoom).toInt() and 0xFFFFFFFE.toInt()).coerceAtLeast(2)
        val cropH = ((array.height() / zoom).toInt() and 0xFFFFFFFE.toInt()).coerceAtLeast(2)
        val left = (array.centerX() - cropW / 2).coerceIn(array.left, array.right - cropW)
        val top = (array.centerY() - cropH / 2).coerceIn(array.top, array.bottom - cropH)
        return android.graphics.Rect(left, top, left + cropW, top + cropH)
    }

    fun closeCamera() {
        val wasActive = cameraDevice != null
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        previewRequestBuilder = null
        try { analysisImageReader?.close() } catch (_: Exception) {}
        analysisImageReader = null
        if (wasActive) onCameraClosed?.invoke()
    }
}
