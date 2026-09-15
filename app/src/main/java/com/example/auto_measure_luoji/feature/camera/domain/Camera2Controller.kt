package com.example.auto_measure_luoji.feature.camera.domain

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.DynamicRangeProfiles
import com.example.auto_measure_luoji.feature.camera.domain.hdr.HdrPreviewCapabilities
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import com.example.auto_measure_luoji.feature.camera.data.LensCandidate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

data class CameraFrameTiming(
    val frameNumber: Long,
    val sensorTimestampNs: Long,
    val exposureTimeNs: Long?,
    val rollingShutterSkewNs: Long?,
)

/**
 * 相机控制器
 *
 * 支持双流输出：
 * - TextureView Surface：预览显示
 * - ImageReader Surface (YUV_420_888)：检测分析
 *
 * 防抖：OIS 默认关闭；系统 EIS 固定关闭，自研补偿由显示层负责。
 */
class Camera2Controller(private val context: Context) {

    companion object {
        private const val VIDEO_STABILIZATION_MODE_PREVIEW = 2
        const val SHADING_MODE_AUTO = -1

        fun shadingModeChoices(): List<Int> = listOf(
            SHADING_MODE_AUTO,
            CaptureRequest.SHADING_MODE_OFF,
            CaptureRequest.SHADING_MODE_FAST,
            CaptureRequest.SHADING_MODE_HIGH_QUALITY,
        )

        fun getDefaultShadingMode(): Int = SHADING_MODE_AUTO

        fun shadingModeName(mode: Int): String = when (mode) {
            SHADING_MODE_AUTO -> "AUTO"
            CaptureRequest.SHADING_MODE_OFF -> "OFF"
            CaptureRequest.SHADING_MODE_FAST -> "FAST"
            CaptureRequest.SHADING_MODE_HIGH_QUALITY -> "QUALITY"
            else -> "UNKNOWN($mode)"
        }

        fun videoStabilizationModeChoices(): List<Int> = listOf(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )

        fun getDefaultVideoStabilizationMode(): Int = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF

        fun videoStabilizationModeName(mode: Int): String = when (mode) {
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "OFF"
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "ON"
            VIDEO_STABILIZATION_MODE_PREVIEW -> "PREVIEW"
            else -> "UNKNOWN($mode)"
        }

        fun oisStabilizationModeName(mode: Int?): String = when (mode) {
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "OFF"
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON -> "ON"
            null -> "UNKNOWN"
            else -> "UNKNOWN($mode)"
        }
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var currentLens: LensCandidate? = null
    private var currentStrictPhysical: Boolean = false
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var pendingZoomRatio: Float = 1f
    private var oisRequested: Boolean = false
    private var hdrPreviewRequested = false
    private var pendingShadingMode = SHADING_MODE_AUTO
    private var availableShadingModes = intArrayOf()
    private var shadingCharacteristicsKnown = false
    private var sensorInfoLensShadingApplied: Boolean? = null
    private var lastShadingResult: Int? = null
    @Volatile private var cameraGeneration = 0L
    private var pendingVideoStabilizationMode: Int = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
    private var availableVideoStabilizationModes: IntArray = intArrayOf(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
    private var lastActivePhysicalCameraId: String? = null
    private var hasPublishedActivePhysicalCameraId: Boolean = false
    private var pendingExposureCompensationEv: Float = 0f
    private var exposureCompensationRange: Range<Int> = Range(0, 0)
    private var exposureCompensationStep: Rational = Rational(1, 1)
    private var lastCaptureResultRequestedVideoStabilizationMode: Int? = null
    private var lastCaptureResultVideoStabilizationMode: Int? = null
    private var lastCaptureResultRequestedOisMode: Int? = null
    private var lastCaptureResultOisMode: Int? = null

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
    var onActivePhysicalCameraChanged: ((String?) -> Unit)? = null
    var onVideoStabilizationStateChanged: ((Int, String) -> Unit)? = null
    var onFrameTimingChanged: ((CameraFrameTiming) -> Unit)? = null
    var onShadingModeStateChanged: ((Int, String) -> Unit)? = null

    /** 回调在分析线程运行，只借用图像；Image 的关闭仍由控制器负责。 */
    @Volatile var onAnalysisImageAvailable: ((Image, Int) -> Unit)? = null
    private var analysisThread: HandlerThread? = null
    private var analysisHandler: Handler? = null

    val isActive: Boolean get() = cameraDevice != null
    private var surfaceTexture: SurfaceTexture? = null
    private val analysisImageLock = Any()
    private var latestAnalysisImage: Image? = null

    /** 检测用 ImageReader (YUV_420_888)，与预览分离 */
    var analysisImageReader: ImageReader? = null
        private set

    fun acquireLatestAnalysisImage(): Image? = synchronized(analysisImageLock) {
        latestAnalysisImage.also { latestAnalysisImage = null }
    }

    private fun closeLatestAnalysisImage() {
        synchronized(analysisImageLock) {
            latestAnalysisImage?.close()
            latestAnalysisImage = null
        }
    }

    fun start() {
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
        analysisThread = HandlerThread("PreviewAnalysisThread").apply { start() }
        analysisHandler = Handler(analysisThread!!.looper)
    }

    fun stop() {
        onAnalysisImageAvailable = null
        analysisThread?.quitSafely()
        try { analysisThread?.join() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        analysisThread = null
        analysisHandler = null
        cameraThread?.quitSafely()
        try { cameraThread?.join() } catch (_: InterruptedException) {}
        cameraThread = null
        cameraHandler = null
    }

    fun setSurfaceTexture(st: SurfaceTexture) {
        surfaceTexture = st
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
    }

    /** 输出动态范围变更前必须关闭旧相机，并重建对应 HDR/SDR 的 GL 表面。 */
    fun setHdrPreviewEnabled(enabled: Boolean) {
        hdrPreviewRequested = enabled
        if (enabled) pendingVideoStabilizationMode = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
    }

    @SuppressLint("MissingPermission")
    fun openCamera(
        lens: LensCandidate,
        surface: Surface,
        strictPhysical: Boolean = false,
    ) {
        if (hdrPreviewRequested) {
            if (strictPhysical) {
                onError?.invoke("HDR 预览不支持校准采集")
                return
            }
            val failure = HdrPreviewCapabilities.checkCamera(context, lens).exceptionOrNull()
            if (failure != null) {
                onError?.invoke(failure.message ?: "当前相机不支持 HDR 预览")
                return
            }
        }
        val generation = ++cameraGeneration
        currentLens = lens
        currentStrictPhysical = strictPhysical &&
            lens.physicalId != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        lastActivePhysicalCameraId = null
        hasPublishedActivePhysicalCameraId = false

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(lens.cameraId)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        updateExposureCompensationCharacteristics(chars)
        updateVideoStabilizationCharacteristics(chars)
        updateShadingCharacteristics(chars)

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map != null) {
            val sizes = map.getOutputSizes(SurfaceTexture::class.java)
            previewSize = choosePreviewSize(sizes, 9.0 / 16.0)
        }

        try {
            surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
        } catch (_: Exception) {}

        val mode = if (currentStrictPhysical) "strict-physical" else "logical"
        Log.d("Camera2Ctrl", ">>> openCamera: id=${lens.cameraId}, phys=${lens.physicalId}, mode=$mode, previewSize=${previewSize}, sensorOrient=$sensorOrientation")

        manager.openCamera(lens.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.d("Camera2Ctrl", ">>> onOpened: ${camera.id}")
                if (generation != cameraGeneration) {
                    camera.close()
                    return
                }
                cameraDevice = camera
                createSession(camera, lens, surface, currentStrictPhysical)
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (generation != cameraGeneration) return
                cameraDevice = null
                onCameraClosed?.invoke()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (generation != cameraGeneration) return
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
        // 仅双摄校准 strictPhysical 模式绑定物理输出；普通预览保持父逻辑相机 Session。
        val usePhysicalOutput = strictPhysical &&
            lens.physicalId != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        closeLatestAnalysisImage()
        analysisImageReader?.close()
        val hdrYuvSupported = !hdrPreviewRequested || HdrPreviewCapabilities.checkYuvAnalysis(context, lens)
            .onFailure { Log.w("Camera2Ctrl", "hdr-yuv: unavailable: ${it.message}") }.isSuccess
        analysisImageReader = if (usePhysicalOutput || !hdrYuvSupported) {
            null
        } else {
            ImageReader.newInstance(
                previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 3
            ).apply {
                setOnImageAvailableListener({ reader ->
                    // 只保留最新分析帧，供 YOLO 在 YUV 数据源模式下取用。
                    val image = try {
                        reader.acquireLatestImage()
                    } catch (e: Exception) {
                        Log.w("Camera2Ctrl", "analysis image acquire failed: ${e.message}")
                        null
                    } ?: return@setOnImageAvailableListener
                    if (reader !== analysisImageReader) {
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    try {
                        onAnalysisImageAvailable?.invoke(image, sensorOrientation)
                    } catch (error: Exception) {
                        Log.w("Camera2Ctrl", "visual motion analysis failed", error)
                    }
                    synchronized(analysisImageLock) {
                        if (reader !== analysisImageReader) {
                            image.close()
                            return@synchronized
                        }
                        latestAnalysisImage?.close()
                        latestAnalysisImage = image
                    }
                }, analysisHandler ?: cameraHandler)
            }
        }

        previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            analysisImageReader?.surface?.let { addTarget(it) }
        }

        if (hdrPreviewRequested && Build.VERSION.SDK_INT >= 33) createHdrSession(camera, lens, surface)
        else if (usePhysicalOutput) createPhysicalSession(camera, lens, surface, strictPhysical)
        else createLogicalSession(camera, surface)
    }

    @androidx.annotation.RequiresApi(33)
    private fun createHdrSession(camera: CameraDevice, lens: LensCandidate, surface: Surface) {
        try {
            val output = OutputConfiguration(surface).apply {
                setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
            }
            // HLG10 只用于预览。模型沿用同一逻辑相机会话的 8-bit SDR YUV，不读取 HLG 编码像素。
            val outputs = mutableListOf(output)
            analysisImageReader?.surface?.let { analysisSurface ->
                outputs.add(OutputConfiguration(analysisSurface).apply {
                    setDynamicRangeProfile(DynamicRangeProfiles.STANDARD)
                })
            }
            camera.createCaptureSession(SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outputs,
                java.util.concurrent.Executor { command -> cameraHandler?.post(command) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice !== camera || !hdrPreviewRequested) {
                            session.close()
                            return
                        }
                        Log.i("Camera2Ctrl", "hdr-preview: configured HLG10 camera=${lens.cameraId} " +
                            "output=logical size=$previewSize yuv=${analysisImageReader != null}")
                        captureSession = session
                        startPreview(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (cameraDevice === camera && hdrPreviewRequested) {
                            // 不在 HDR GL 表面上暗中重试 SDR，也不偷偷换成逻辑镜头。
                            onError?.invoke("当前镜头或分辨率的 HLG10 会话被设备拒绝")
                        }
                    }
                },
            ))
        } catch (error: Exception) {
            onError?.invoke("HDR 预览创建失败：${error.message}")
        }
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
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice !== camera) { session.close(); return }
                        captureSession = session
                        startPreview(session)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (cameraDevice !== camera) { session.close(); return }
                        if (strictPhysical) {
                            onError?.invoke("Physical session configuration failed")
                        } else {
                            createLogicalSession(camera, surface)
                        }
                    }
                }
            ))
        } catch (e: Exception) {
            if (strictPhysical) {
                onError?.invoke(e.message ?: "Physical session failed")
            } else {
                createLogicalSession(camera, surface)
            }
        }
    }

    private fun createLogicalSession(camera: CameraDevice, surface: Surface) {
        val surfaces = mutableListOf(surface)
        analysisImageReader?.surface?.let { surfaces.add(it) }
        camera.createCaptureSession(surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice !== camera) { session.close(); return }
                    captureSession = session
                    startPreview(session)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cameraDevice !== camera) { session.close(); return }
                    onError?.invoke("Session configuration failed")
                }
            }, cameraHandler)
    }

    private fun resetStabilizationCaptureResultLog() {
        lastCaptureResultRequestedVideoStabilizationMode = null
        lastCaptureResultVideoStabilizationMode = null
        lastCaptureResultRequestedOisMode = null
        lastCaptureResultOisMode = null
    }

    private fun videoStabilizationModeNameOrUnknown(mode: Int?): String {
        return mode?.let { videoStabilizationModeName(it) } ?: "UNKNOWN"
    }

    private fun publishStabilizationCaptureResult(
        request: CaptureRequest,
        result: TotalCaptureResult,
    ) {
        val shadingResult = result.get(CaptureResult.SHADING_MODE)
        if (shadingResult != lastShadingResult) {
            lastShadingResult = shadingResult
            Log.i("Camera2Ctrl", "lsc-result: request=${request.get(CaptureRequest.SHADING_MODE)}, " +
                "result=$shadingResult frame=${result.frameNumber}")
        }
        val requestedVideoMode = request.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE)
        val resultVideoMode = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
        val requestedOisMode = request.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)
        val resultOisMode = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)

        val changed = requestedVideoMode != lastCaptureResultRequestedVideoStabilizationMode ||
            resultVideoMode != lastCaptureResultVideoStabilizationMode ||
            requestedOisMode != lastCaptureResultRequestedOisMode ||
            resultOisMode != lastCaptureResultOisMode
        if (!changed) return

        lastCaptureResultRequestedVideoStabilizationMode = requestedVideoMode
        lastCaptureResultVideoStabilizationMode = resultVideoMode
        lastCaptureResultRequestedOisMode = requestedOisMode
        lastCaptureResultOisMode = resultOisMode

        Log.i(
            "Camera2Ctrl",
            "videoStab result: request=${videoStabilizationModeNameOrUnknown(requestedVideoMode)}, " +
                "result=${videoStabilizationModeNameOrUnknown(resultVideoMode)}, " +
                "oisRequest=${oisStabilizationModeName(requestedOisMode)}, " +
                "oisResult=${oisStabilizationModeName(resultOisMode)}, " +
                "frame=${result.frameNumber}",
        )
    }

    private fun publishFrameTiming(result: TotalCaptureResult) {
        val sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        onFrameTimingChanged?.invoke(
            CameraFrameTiming(
                frameNumber = result.frameNumber,
                sensorTimestampNs = sensorTimestampNs,
                exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                rollingShutterSkewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
            ),
        )
    }

    private fun startPreview(session: CameraCaptureSession) {
        val builder = previewRequestBuilder ?: return
        val lens = currentLens ?: return
        resetStabilizationCaptureResultLog()

        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

        applyStabilization(builder, lens, notifyUnsupported = true)
        applyExposureCompensation(builder)
        applyShadingMode(builder, notify = true)

        // 初始 zoom。普通预览走父逻辑相机 zoom 范围；双摄 strictPhysical 保持物理会话范围。
        val usedControlZoom = applyPreferredZoom(builder, lens, pendingZoomRatio)

        try {
            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
            onCameraOpened?.invoke()
        } catch (e: Exception) {
            if (usedControlZoom) {
                Log.w("Camera2Ctrl", "CONTROL_ZOOM_RATIO failed on startPreview, fallback to SCALER_CROP_REGION", e)
                applyCropZoom(builder, lens, pendingZoomRatio)
                try {
                    session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
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
        try { session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler) } catch (_: Exception) {}
    }

    fun setZoomRatio(zoomRatio: Float) {
        val lens = currentLens
        pendingZoomRatio = zoomRatio.coerceAtLeast(1f)
        if (lens == null) return
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val usedControlZoom = applyPreferredZoom(builder, lens, pendingZoomRatio)
        try {
            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
        } catch (e: Exception) {
            if (usedControlZoom) {
                Log.w("Camera2Ctrl", "CONTROL_ZOOM_RATIO failed, fallback to SCALER_CROP_REGION", e)
                applyCropZoom(builder, lens, pendingZoomRatio)
                try { session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler) } catch (_: Exception) {}
            }
        }
    }

    fun setExposureCompensationEv(ev: Float): Result<Int> {
        pendingExposureCompensationEv = ev
        val builder = previewRequestBuilder ?: return Result.success(0)
        val session = captureSession ?: return Result.success(0)
        val compensationSteps = applyExposureCompensation(builder)
        return try {
            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
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
        if (!isOisSupported(lens)) {
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

    fun getAvailableVideoStabilizationModes(): List<Int> {
        return availableVideoStabilizationModes
            .toList()
            .distinct()
            .sorted()
    }

    fun getVideoStabilizationMode(): Int = pendingVideoStabilizationMode

    fun getShadingMode(): Int = pendingShadingMode

    fun getSensorInfoLensShadingApplied(): Boolean? = sensorInfoLensShadingApplied

    fun getAvailableShadingModes(): List<Int> {
        return availableShadingModes
            .toList()
            .distinct()
            .sorted()
    }

    fun getSelectableShadingModes(): List<Int> {
        if (availableShadingModes.isEmpty()) return listOf(SHADING_MODE_AUTO)
        return shadingModeChoices().filter { mode ->
            mode == SHADING_MODE_AUTO || mode in availableShadingModes
        }
    }

    fun setShadingMode(mode: Int): Result<Int> {
        if (mode !in shadingModeChoices()) {
            return Result.failure(IllegalArgumentException("未知 Shading 模式: $mode"))
        }
        if (shadingCharacteristicsKnown && mode != SHADING_MODE_AUTO && mode !in availableShadingModes) {
            return Result.failure(
                IllegalStateException("当前镜头不支持 Shading ${shadingModeName(mode)}")
            )
        }

        val previous = pendingShadingMode
        pendingShadingMode = mode
        val builder = previewRequestBuilder
        val session = captureSession
        if (builder == null || session == null) {
            publishShadingModeState("pending")
            return Result.success(mode)
        }

        return try {
            applyShadingMode(builder, notify = false)
            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
            publishShadingModeState("set")
            Result.success(mode)
        } catch (e: Exception) {
            pendingShadingMode = previous
            applyShadingMode(builder, notify = false)
            Log.w("Camera2Ctrl", "Shading request failed", e)
            publishShadingModeState("failed")
            Result.failure(e)
        }
    }

    private fun applyShadingMode(builder: CaptureRequest.Builder, notify: Boolean = false) {
        builder.set(CaptureRequest.SHADING_MODE, resolveShadingModeForRequest())
        if (notify) publishShadingModeState("apply")
    }

    private fun resolveShadingModeForRequest(): Int? {
        val requested = pendingShadingMode
        if (requested == SHADING_MODE_AUTO) return null
        if (requested !in availableShadingModes) return null
        return requested
    }

    private fun updateShadingCharacteristics(chars: CameraCharacteristics) {
        shadingCharacteristicsKnown = true
        availableShadingModes = chars.get(CameraCharacteristics.SHADING_AVAILABLE_MODES) ?: intArrayOf()
        sensorInfoLensShadingApplied = chars.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED)
        lastShadingResult = null
        if (pendingShadingMode != SHADING_MODE_AUTO && pendingShadingMode !in availableShadingModes) {
            pendingShadingMode = SHADING_MODE_AUTO
        }
        publishShadingModeState("chars")
    }

    private fun publishShadingModeState(reason: String) {
        val message = "lsc: mode=${shadingModeName(pendingShadingMode)}, reason=$reason, " +
            "supported=${availableShadingModes.joinToString { shadingModeName(it) }}, " +
            "lensShadingApplied=$sensorInfoLensShadingApplied"
        Log.i("Camera2Ctrl", message)
        onShadingModeStateChanged?.invoke(pendingShadingMode, message)
    }

    fun setVideoStabilizationMode(mode: Int): Result<Int> {
        if (mode != CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) {
            return Result.failure(IllegalStateException("系统 EIS 已禁用，请使用 OIS 或自研补偿"))
        }
        val supportedModes = getAvailableVideoStabilizationModes()
        if (mode !in supportedModes) {
            return Result.failure(
                IllegalStateException("当前镜头不支持 EIS ${videoStabilizationModeName(mode)}")
            )
        }

        val builder = previewRequestBuilder
            ?: return Result.failure(IllegalStateException("预览请求未就绪，无法切换 EIS"))
        val session = captureSession
            ?: return Result.failure(IllegalStateException("相机会话未就绪，无法切换 EIS"))
        val lens = currentLens
            ?: return Result.failure(IllegalStateException("相机未就绪，无法切换 EIS"))

        pendingVideoStabilizationMode = mode
        applyStabilization(builder, lens, notifyUnsupported = false)
        return try {
            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
            publishVideoStabilizationState(mode, "set")
            Result.success(mode)
        } catch (e: Exception) {
            Log.w("Camera2Ctrl", "EIS request failed", e)
            Result.failure(e)
        }
    }

    private fun submitOisMode(enabled: Boolean): Result<Unit> {
        val lens = currentLens
            ?: return Result.failure(IllegalStateException("相机未就绪，无法切换 OIS"))
        val builder = previewRequestBuilder
            ?: return Result.failure(IllegalStateException("预览请求未就绪，无法切换 OIS"))
        val session = captureSession
            ?: return Result.failure(IllegalStateException("相机会话未就绪，无法切换 OIS"))

        if (enabled && !isOisSupported(lens)) {
            return Result.failure(IllegalStateException("当前镜头不支持 OIS"))
        }

        applyStabilization(builder, lens, notifyUnsupported = false)
        return try {
            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
            val enabledNow = enabled && isOisSupported(lens)
            onOisStateChanged?.invoke(enabledNow)
            Log.d("Camera2Ctrl", ">>> OIS ${if (enabledNow) "ON" else "OFF"}")
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
        val supportedVideoModes = getAvailableVideoStabilizationModes()
        val requestedVideoMode = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        val videoMode = if (requestedVideoMode in supportedVideoModes) {
            requestedVideoMode
        } else {
            pendingVideoStabilizationMode = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            if (notifyUnsupported) {
                onVideoStabilizationStateChanged?.invoke(
                    pendingVideoStabilizationMode,
                    "当前镜头不支持 EIS ${videoStabilizationModeName(requestedVideoMode)}，已切回 OFF",
                )
            }
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        }
        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            videoMode,
        )

        val oisSupported = isOisSupported(lens)
        val shouldEnableOis = oisRequested && oisSupported
        if (oisRequested && !oisSupported) {
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
        publishVideoStabilizationState(videoMode, "apply")
    }

    private fun updateExposureCompensationCharacteristics(chars: CameraCharacteristics) {
        exposureCompensationRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?: Range(0, 0)
        exposureCompensationStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?: Rational(1, 1)
    }

    private fun updateVideoStabilizationCharacteristics(chars: CameraCharacteristics) {
        val modes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?: intArrayOf(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        availableVideoStabilizationModes = modes
        if (pendingVideoStabilizationMode !in modes) {
            pendingVideoStabilizationMode = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        }
        publishVideoStabilizationState(pendingVideoStabilizationMode, "chars")
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

    private fun publishVideoStabilizationState(mode: Int, reason: String) {
        val message = "videoStab: mode=${videoStabilizationModeName(mode)}, reason=$reason, supported=${
            getAvailableVideoStabilizationModes().joinToString { videoStabilizationModeName(it) }
        }"
        Log.i("Camera2Ctrl", message)
        onVideoStabilizationStateChanged?.invoke(mode, message)
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
                builder.set(CaptureRequest.SCALER_CROP_REGION, activeArrayForCurrentMode(lens))
                publishZoomState("CONTROL_ZOOM_RATIO", requested, "range=${zoomRatioRangeForCurrentMode(lens)}")
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
        publishZoomState("SCALER_CROP_REGION", zoomRatio.coerceAtLeast(1f), "center crop")
    }

    private fun publishZoomState(backend: String, ratio: Float, detail: String) {
        val message = "zoom: backend=$backend, ratio=${String.format(Locale.US, "%.2f", ratio)}, $detail"
        Log.i("Camera2Ctrl", message)
        Log.d("Camera2Ctrl", ">>> $message")
        onZoomStateChanged?.invoke(message)
    }

    private fun isControlZoomAvailable(lens: LensCandidate, zoomRatio: Float): Boolean {
        val range = zoomRatioRangeForCurrentMode(lens)
        return zoomRatio >= range.lower && zoomRatio <= range.upper
    }

    private fun calculateCenteredCrop(lens: LensCandidate, zoomRatio: Float): android.graphics.Rect {
        val array = activeArrayForCurrentMode(lens)
        val zoom = zoomRatio.coerceAtLeast(1f)
        val cropW = ((array.width() / zoom).toInt() and 0xFFFFFFFE.toInt()).coerceAtLeast(2)
        val cropH = ((array.height() / zoom).toInt() and 0xFFFFFFFE.toInt()).coerceAtLeast(2)
        val left = (array.centerX() - cropW / 2).coerceIn(array.left, array.right - cropW)
        val top = (array.centerY() - cropH / 2).coerceIn(array.top, array.bottom - cropH)
        return android.graphics.Rect(left, top, left + cropW, top + cropH)
    }

    fun closeCamera() {
        cameraGeneration++
        shadingCharacteristicsKnown = false
        val wasActive = cameraDevice != null
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        currentStrictPhysical = false
        previewRequestBuilder = null
        closeLatestAnalysisImage()
        try { analysisImageReader?.close() } catch (_: Exception) {}
        analysisImageReader = null
        if (wasActive) onCameraClosed?.invoke()
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (session !== captureSession) return
            publishFrameTiming(result)
            publishActivePhysicalCameraId(result)
            publishStabilizationCaptureResult(request, result)
        }
    }

    @SuppressLint("NewApi")
    private fun publishActivePhysicalCameraId(result: TotalCaptureResult) {
        val activeId = if (currentStrictPhysical) {
            currentLens?.physicalId ?: currentLens?.cameraId
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        } else {
            null
        }

        if (!hasPublishedActivePhysicalCameraId || activeId != lastActivePhysicalCameraId) {
            hasPublishedActivePhysicalCameraId = true
            lastActivePhysicalCameraId = activeId
            Log.i("Camera2Ctrl", "active-physical-camera: ${activeId ?: "not reported"}")
            onActivePhysicalCameraChanged?.invoke(activeId)
        }
    }

    private fun activeArrayForCurrentMode(lens: LensCandidate): android.graphics.Rect {
        return if (currentStrictPhysical) lens.activeArray else lens.logicalActiveArray
    }

    private fun zoomRatioRangeForCurrentMode(lens: LensCandidate): Range<Float> {
        return if (currentStrictPhysical) lens.zoomRatioRange else lens.logicalZoomRatioRange
    }

    private fun isOisSupported(lens: LensCandidate): Boolean {
        return if (currentStrictPhysical) lens.oisSupported else lens.logicalOisSupported
    }
}
