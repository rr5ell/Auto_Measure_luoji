package com.example.auto_measure_luoji.feature.camera.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.auto_measure_luoji.feature.bluetooth.domain.BleConnectionManager
import com.example.auto_measure_luoji.feature.bluetooth.ui.BluetoothPanel
import com.example.auto_measure_luoji.feature.bluetooth.ui.DeviceControls
import com.example.auto_measure_luoji.feature.calibration.domain.CalibrationManager
import com.example.auto_measure_luoji.feature.calibration.domain.DualCalibrationManager
import com.example.auto_measure_luoji.feature.common.ui.LogViewerOverlay
import com.example.auto_measure_luoji.feature.calibration.detector.LaserDetector
import com.example.auto_measure_luoji.feature.calibration.model.LaserResult
import com.example.auto_measure_luoji.feature.calibration.model.LaserPoint
import com.example.auto_measure_luoji.feature.calibration.ui.CalibrationPanel
import com.example.auto_measure_luoji.feature.calibration.ui.DualCalibrationPanel
import com.example.auto_measure_luoji.feature.calibration.util.GallerySaver
import com.example.auto_measure_luoji.feature.calibration.util.ImageUtils
import com.example.auto_measure_luoji.feature.camera.data.LensCandidate
import com.example.auto_measure_luoji.feature.camera.data.LensCategory
import com.example.auto_measure_luoji.feature.camera.data.LensScanner
import com.example.auto_measure_luoji.feature.camera.domain.Camera2Controller
import com.example.auto_measure_luoji.feature.camera.domain.SelectLensUseCase
import com.example.auto_measure_luoji.feature.detection.domain.YoloDetector
import com.example.auto_measure_luoji.feature.detection.ui.DetectionOverlay
import com.example.auto_measure_luoji.feature.detection.ui.RecognitionArea
import com.example.auto_measure_luoji.feature.voice.domain.ChineseVoiceHotwordRecognizer
import com.example.auto_measure_luoji.feature.voice.domain.VoiceModelDownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val WIDE_CALIBRATION_ZOOM = 5f
private const val NORMAL_PREVIEW_MAX_ZOOM = 24f
private const val NORMAL_MIN_ZOOM_OFFSET = 0.1f
private const val CALIBRATION_EXPOSURE_EV = -2.0f
private const val CAMERA_SCREEN_PREFS = "camera_screen_settings"
private const val KEY_YOLO_FRAME_SOURCE = "yolo_frame_source"

/**
 * 相机预览屏幕
 *
 * 变换方案：
 * - TextureView 完全不变换（setTransform/rotation/scaleX 都会黑屏）
 * - 变换应用在 FrameLayout 容器上（scaleX/scaleY/translationX/translationY）
 * - 参考项目用 CameraX PreviewView 做同样的事，PreviewView 也是 ViewGroup
 */
private enum class YoloCaptureMode(
    val label: String,
    val width: Int,
    val height: Int,
) {
    P1080("1080P", 1080, 1920),
    UHD4K("4K", 2160, 3840);

    fun next(): YoloCaptureMode = if (this == P1080) UHD4K else P1080
}

private enum class YoloFrameSource(val label: String) {
    PREVIEW_BITMAP("预览截图"),
    IMAGE_READER_YUV("YUV数据流");

    fun next(): YoloFrameSource = if (this == PREVIEW_BITMAP) IMAGE_READER_YUV else PREVIEW_BITMAP
}

private data class LaserFrameCapture(
    val normalizedBitmap: Bitmap,
    val pngBytes: ByteArray,
    val sourceLabel: String,
)

@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cameraPrefs = remember(context.applicationContext) {
        context.applicationContext.getSharedPreferences(CAMERA_SCREEN_PREFS, Context.MODE_PRIVATE)
    }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val isLaserCompareApp = remember(context.packageName) {
        context.packageName.endsWith(".lasercompare")
    }
    val scope = rememberCoroutineScope()

    val cameraController = remember { Camera2Controller(context) }
    val lensScanner = remember { LensScanner(context) }
    val selectLensUseCase = remember { SelectLensUseCase() }
    val calManager = remember { CalibrationManager() }
    val dualCalManager = remember { DualCalibrationManager() }
    val bleManager = remember { BleConnectionManager(context) }
    val voiceRecognizer = remember { ChineseVoiceHotwordRecognizer(context.applicationContext) }
    val isBleConnected = bleManager.state == BleConnectionManager.State.CONNECTED

    var statusText by remember { mutableStateOf("正在扫描镜头...") }
    var isVoiceListening by remember { mutableStateOf(false) }
    var voiceDownloadDialogVisible by remember { mutableStateOf(false) }
    var voiceDownloadState by remember { mutableStateOf<VoiceModelDownloadState?>(null) }
    var voiceHitText by remember { mutableStateOf<String?>(null) }
    var lensCandidates by remember { mutableStateOf<List<LensCandidate>>(emptyList()) }
    var defaultLens by remember { mutableStateOf<LensCandidate?>(null) }
    var selectedLens by remember { mutableStateOf<LensCandidate?>(null) }
    var currentEquivRatio by remember { mutableFloatStateOf(1f) }
    var isCameraReady by remember { mutableStateOf(false) }
    var oisEnabled by remember { mutableStateOf(false) }
    var oisFailureText by remember { mutableStateOf<String?>(null) }
    var showStabilizationDialog by remember { mutableStateOf(false) }
    var eisMode by remember { mutableIntStateOf(Camera2Controller.getDefaultVideoStabilizationMode()) }
    var videoStabilizationText by remember { mutableStateOf("") }
    var showLaserComparePanel by remember { mutableStateOf(false) }
    var laserCompareEnabled by remember { mutableStateOf(false) }
    var laserCompareText by remember { mutableStateOf("") }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var wrapperRef by remember { mutableStateOf<FrameLayout?>(null) }

    var showCalPanel by remember { mutableStateOf(false) }
    var showDualCalPanel by remember { mutableStateOf(false) }
    var showCalibrationModeDialog by remember { mutableStateOf(false) }
    var showCalChoiceDialog by remember { mutableStateOf(false) }
    var showDualLensDialog by remember { mutableStateOf(false) }
    var showDualCalChoiceDialog by remember { mutableStateOf(false) }
    var showDualCoordInputDialog by remember { mutableStateOf(false) }
    var dualScanSummary by remember { mutableStateOf("") }
    var isDualCalibMode by remember { mutableStateOf(false) }
    var dualWideLens by remember { mutableStateOf<LensCandidate?>(null) }
    var dualTeleOptions by remember { mutableStateOf<List<LensCandidate>>(emptyList()) }
    var dualTeleLens by remember { mutableStateOf<LensCandidate?>(null) }
    var pendingDualTeleLens by remember { mutableStateOf<LensCandidate?>(null) }
    var isUsingDualTele by remember { mutableStateOf(false) }
    var dualTargetPrediction by remember {
        mutableStateOf<com.example.auto_measure_luoji.feature.calibration.domain.DualTargetPrediction?>(null)
    }
    var dualLastResultText by remember { mutableStateOf("") }
    var showCoordInputDialog by remember { mutableStateOf(false) }
    var coordInputX by remember { mutableStateOf("") }
    var coordInputY by remember { mutableStateOf("") }
    var dualWideCoordX by remember { mutableStateOf("") }
    var dualWideCoordY by remember { mutableStateOf("") }
    var dualTeleCoordX by remember { mutableStateOf("") }
    var dualTeleCoordY by remember { mutableStateOf("") }
    var dualCoordError by remember { mutableStateOf<String?>(null) }
    var showLogViewer by remember { mutableStateOf(false) }
    var toolsMenuExpanded by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var lastResultText by remember { mutableStateOf("") }
    var anchorCenter1x by remember { mutableStateOf<LaserPoint?>(null) }
    var currentCenterPx by remember { mutableStateOf<LaserPoint?>(null) }
    var surfaceAvailable by remember { mutableStateOf(false) }

    // 变换状态
    var anchorZoom by remember { mutableFloatStateOf(1f) }
    var anchorActive by remember { mutableStateOf(false) }

    // wrapper 变换值（同步到 overlay 组件，保持与预览内容一致）
    var wrapperTx by remember { mutableFloatStateOf(0f) }
    var wrapperTy by remember { mutableFloatStateOf(0f) }
    var wrapperScale by remember { mutableFloatStateOf(1f) }
    var zoomBackendText by remember { mutableStateOf("") }
    var activePhysicalCameraId by remember { mutableStateOf<String?>(null) }
    var activePhysicalLensReported by remember { mutableStateOf(false) }

    // 检测状态
    var modelLoadStatus by remember { mutableStateOf("未加载") }
    var yoloDetector by remember { mutableStateOf<YoloDetector?>(null) }
    var yoloCaptureMode by remember { mutableStateOf(YoloCaptureMode.P1080) }
    var yoloFrameSource by remember {
        mutableStateOf(
            YoloFrameSource.values().firstOrNull {
                it.name == cameraPrefs.getString(KEY_YOLO_FRAME_SOURCE, YoloFrameSource.PREVIEW_BITMAP.name)
            } ?: YoloFrameSource.PREVIEW_BITMAP,
        )
    }

    // 在后台线程加载模型（避免 Tasks.await 阻塞主线程）
    LaunchedEffect(Unit) {
        modelLoadStatus = "加载中..."
        Log.d("CameraScreen", "开始加载 YOLO 模型...")
        try {
            val det = withContext(Dispatchers.Default) {
                YoloDetector(
                    context = context,
                    modelAssetPath = "bestV34_float32.tflite",
                    labelsAssetPath = "classes.txt",
                    useGpu = true,
                )
            }
            yoloDetector = det
            modelLoadStatus = "已加载 (${det.runtimeInfo})"
            Log.d("CameraScreen", "模型加载成功: ${det.runtimeInfo}")
        } catch (e: Exception) {
            Log.e("CameraScreen", "模型加载失败", e)
            modelLoadStatus = "加载失败: ${e.message}"
        }
    }
    var detectionEnabled by remember { mutableStateOf(false) }
    var detectionBoxes by remember { mutableStateOf<List<Pair<RectF, String>>>(emptyList()) }
    var detectionPerfText by remember { mutableStateOf("") }
    var showMeasurePrompt by remember { mutableStateOf(false) }
    val isDetecting = remember { AtomicBoolean(false) }
    // 测距触发：检测循环中上升沿设为 true，测距启动后设为 false
    var measureTrigger by remember { mutableStateOf(false) }
    var wasTargetInArea by remember { mutableStateOf(false) }

    // 30 秒统计
    var statMeasuring by remember { mutableStateOf(false) }
    var statTotal by remember { mutableIntStateOf(0) }
    var statHit by remember { mutableIntStateOf(0) }
    var statEndMs by remember { mutableLongStateOf(0L) }
    var statResultText by remember { mutableStateOf("") }

    // 设备测距状态
    var isDeviceMeasuring by remember { mutableStateOf(false) }
    var lastDeviceDistance by remember { mutableStateOf<String?>(null) }

    // 识别区域尺寸（px）：可调节
    var areaWidthPx by remember { mutableFloatStateOf(300f) }
    var areaHeightPx by remember { mutableFloatStateOf(450f) }
    var showAreaDialog by remember { mutableStateOf(false) }
    var areaInputW by remember { mutableStateOf("300") }
    var areaInputH by remember { mutableStateOf("450") }

    // 判定点列表（百分比坐标，相对于检测框左上角）
    // 默认只有中心点 (50%, 50%)
    var checkPoints by remember { mutableStateOf(listOf(0.5f to 0.5f)) }
    var showPointDialog by remember { mutableStateOf(false) }
    var pointInputW by remember { mutableStateOf("50") }
    var pointInputH by remember { mutableStateOf("50") }
    // 判定模式：true = 所有点同时命中，false = 任一点命中
    var requireAllPoints by remember { mutableStateOf(false) }

    val dualTelephotoMag = dualTeleLens?.let { tele ->
        dualWideLens?.let { wide ->
            (tele.eq35mm / wide.eq35mm).roundToInt().coerceAtLeast(2)
        }
    } ?: 10

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceRecognizer.start()
        } else {
            statusText = "语音识别需要录音权限"
        }
    }

    fun toggleVoiceRecognition() {
        if (isVoiceListening) {
            voiceRecognizer.stop()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            voiceRecognizer.start()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun showOisFailure(message: String) {
        oisFailureText = message
        statusText = message
    }

    fun toggleOis() {
        val enable = !oisEnabled
        val result = cameraController.setOisEnabled(enable)
        if (result.isFailure) {
            if (enable) {
                showOisFailure(result.exceptionOrNull()?.message ?: "OIS 开启失败")
            }
            oisEnabled = false
        } else {
            oisEnabled = enable
            statusText = if (enable) "OIS 已开启" else "OIS 已关闭"
        }
    }

    fun applyEisMode(mode: Int) {
        val label = Camera2Controller.videoStabilizationModeName(mode)
        cameraController.setVideoStabilizationMode(mode)
            .onSuccess {
                eisMode = it
                statusText = "EIS 已切换为 $label"
            }
            .onFailure { error ->
                showOisFailure(error.message ?: "EIS $label 切换失败")
            }
    }

    fun formatLaserCompareResult(result: LaserResult?): String {
        return result?.let { "(${it.cx.toInt()},${it.cy.toInt()}) c=${"%.2f".format(it.confidence)}" } ?: "未命中"
    }

    fun formatLaserCompareDelta(current: LaserResult?, legacy: LaserResult?): String {
        if (current == null || legacy == null) return "差值: 无"
        val dx = current.cx - legacy.cx
        val dy = current.cy - legacy.cy
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        return "差值: dx=${"%.1f".format(dx)}, dy=${"%.1f".format(dy)}, d=${"%.1f".format(distance)}px"
    }

    suspend fun captureLaserFrameForCalibration(): LaserFrameCapture {
        return when (yoloFrameSource) {
            YoloFrameSource.PREVIEW_BITMAP -> {
                val tv = textureViewRef ?: throw IllegalStateException("预览未就绪")
                if (!tv.isAvailable) throw IllegalStateException("预览未就绪")
                var snapshot: Bitmap? = null
                var normalized: Bitmap? = null
                try {
                    snapshot = withContext(Dispatchers.Main) { tv.getBitmap() }
                        ?: throw IllegalStateException("截取预览失败")
                    val frame = snapshot ?: throw IllegalStateException("截取预览失败")
                    normalized = withContext(Dispatchers.Default) {
                        ImageUtils.resize(frame, ImageUtils.OUT_W, ImageUtils.OUT_H)
                    }
                    val normalizedFrame = normalized ?: throw IllegalStateException("归一化预览失败")
                    val bytes = withContext(Dispatchers.Default) {
                        ImageUtils.bitmapToPngBytes(normalizedFrame)
                    }
                    val label = "PreviewBitmap ${frame.width}x${frame.height}"
                    if (normalizedFrame !== frame) {
                        frame.recycle()
                        snapshot = null
                    }
                    normalized = null
                    LaserFrameCapture(normalizedFrame, bytes, label)
                } catch (e: Exception) {
                    try {
                        if (normalized != null && normalized !== snapshot) normalized.recycle()
                        snapshot?.recycle()
                    } catch (_: Exception) {
                    }
                    throw e
                }
            }

            YoloFrameSource.IMAGE_READER_YUV -> {
                if (cameraController.analysisImageReader == null) {
                    throw IllegalStateException("YUV数据流不可用：当前相机会话未创建ImageReader")
                }
                cameraController.acquireLatestAnalysisImage()?.close()
                val deadline = android.os.SystemClock.elapsedRealtime() + 700L
                var image = cameraController.acquireLatestAnalysisImage()
                while (image == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                    delay(20)
                    image = cameraController.acquireLatestAnalysisImage()
                }
                val frame = image ?: throw IllegalStateException("等待YUV帧超时")
                var normalized: Bitmap? = null
                try {
                    val rotation = cameraController.sensorOrientation
                    normalized = withContext(Dispatchers.Default) {
                        ImageUtils.normalizeYuv420ForCalibration(frame, rotation)
                    }
                    val normalizedFrame = normalized ?: throw IllegalStateException("归一化YUV帧失败")
                    val bytes = withContext(Dispatchers.Default) {
                        ImageUtils.bitmapToPngBytes(normalizedFrame)
                    }
                    val label = "ImageReaderYUV ${frame.width}x${frame.height} rot=$rotation"
                    normalized = null
                    LaserFrameCapture(normalizedFrame, bytes, label)
                } catch (e: Exception) {
                    try {
                        normalized?.recycle()
                    } catch (_: Exception) {
                    }
                    throw e
                } finally {
                    frame.close()
                }
            }
        }
    }

    fun toggleLaserComparePanel() {
        val nextVisible = !showLaserComparePanel
        showLaserComparePanel = nextVisible
        if (nextVisible) {
            if (laserCompareText.isBlank()) {
                laserCompareText = "打开识别开关后，会用同一帧对比新旧绿光算法"
            }
        } else {
            laserCompareEnabled = false
            laserCompareText = "绿光算法对比已关闭"
        }
    }

    fun runLaserCompareRecognition() {
        if (laserCompareEnabled) return
        val tv = textureViewRef
        if (tv == null || !tv.isAvailable) {
            laserCompareText = "预览未就绪"
            statusText = "绿光识别：预览未就绪"
            return
        }

        scope.launch {
            var snapshot: Bitmap? = null
            var normalized: Bitmap? = null
            laserCompareEnabled = true
            laserCompareText = "正在对比新旧绿光算法..."
            statusText = "绿光新旧算法对比中..."
            try {
                snapshot = withContext(Dispatchers.Main) { tv.getBitmap() }
                val frame = snapshot
                if (frame == null) {
                    laserCompareText = "截取预览失败"
                    statusText = "绿光识别：截取预览失败"
                    return@launch
                }

                normalized = withContext(Dispatchers.Default) {
                    ImageUtils.resize(frame, ImageUtils.OUT_W, ImageUtils.OUT_H)
                }
                val normalizedFrame = normalized ?: return@launch
                val ts = System.currentTimeMillis()
                val uint8Bytes = withContext(Dispatchers.Default) {
                    ImageUtils.bitmapToPngBytes(normalizedFrame)
                }

                GallerySaver.savePng(context, uint8Bytes, "laser_compare_raw_$ts.png")
                val currentResult = withContext(Dispatchers.Default) {
                    LaserDetector.detectLaserCenterFromBytes(uint8Bytes)
                }
                if (currentResult != null) {
                    val currentMarked = withContext(Dispatchers.Default) {
                        ImageUtils.drawMarkOnBitmap(
                            normalizedFrame,
                            currentResult.cx,
                            currentResult.cy,
                            1f,
                            labelPrefix = "new",
                            markColor = android.graphics.Color.rgb(0, 230, 118),
                        )
                    }
                    GallerySaver.savePng(context, currentMarked, "laser_new_marked_$ts.png")
                }

                val legacyResult = withContext(Dispatchers.Default) {
                    LaserDetector.detectLaserCenterFromBytesLegacy(uint8Bytes)
                }
                if (legacyResult != null) {
                    val legacyMarked = withContext(Dispatchers.Default) {
                        ImageUtils.drawMarkOnBitmap(
                            normalizedFrame,
                            legacyResult.cx,
                            legacyResult.cy,
                            1f,
                            labelPrefix = "old",
                            markColor = android.graphics.Color.rgb(255, 82, 82),
                        )
                    }
                    GallerySaver.savePng(context, legacyMarked, "laser_old_marked_$ts.png")
                }

                val currentText = formatLaserCompareResult(currentResult)
                val legacyText = formatLaserCompareResult(legacyResult)
                val deltaText = formatLaserCompareDelta(currentResult, legacyResult)
                laserCompareText = "新: $currentText | 旧: $legacyText | $deltaText"
                statusText = "绿光算法对比完成：$deltaText"
                Log.d("CameraScreen", "绿光算法对比: new=$currentText, old=$legacyText, $deltaText")
            } catch (e: Exception) {
                Log.e("CameraScreen", "绿光对比识别异常", e)
                laserCompareText = "识别异常: ${e.message}"
                statusText = "绿光识别异常"
            } finally {
                try {
                    val norm = normalized
                    val frame = snapshot
                    if (norm != null && norm !== frame) norm.recycle()
                    frame?.recycle()
                } catch (_: Exception) {
                }
                laserCompareEnabled = false
            }
        }
    }

    DisposableEffect(voiceRecognizer) {
        voiceRecognizer.onStatusChanged = { statusText = it }
        voiceRecognizer.onListeningChanged = { isVoiceListening = it }
        voiceRecognizer.onLanguageModelRequired = { message ->
            voiceDownloadState = VoiceModelDownloadState(false, null, message)
            voiceDownloadDialogVisible = true
        }
        voiceRecognizer.onLanguageModelDownloadChanged = { state ->
            voiceDownloadState = state
            voiceDownloadDialogVisible = true
            statusText = state.message
        }
        voiceRecognizer.onHotwordHit = { hit ->
            val text = "语音命中：${hit.category.displayName}"
            voiceHitText = text
            statusText = "$text（${hit.sourceText}）"
        }
        onDispose { voiceRecognizer.destroy() }
    }

    fun describeLens(lens: LensCandidate, wide: LensCandidate? = null): String {
        val mag = wide?.let { (lens.eq35mm / it.eq35mm).roundToInt().coerceAtLeast(1) }
        val magText = mag?.let { " ${it}x" } ?: ""
        val physicalText = lens.physicalId?.let { "物理#$it" } ?: "逻辑#${lens.cameraId}"
        return "${lens.category}$magText · ${"%.1f".format(lens.eq35mm)}mm · $physicalText"
    }

    fun lensByCameraId(cameraId: String?): LensCandidate? {
        if (cameraId == null) return null
        return lensCandidates.firstOrNull { it.physicalId == cameraId }
            ?: lensCandidates.firstOrNull { it.physicalId == null && it.cameraId == cameraId }
    }

    fun activeFrameLensText(): String {
        if (!isCameraReady) return ""
        if (!activePhysicalLensReported) return "画面镜头: 等待回传"
        val activeId = activePhysicalCameraId
            ?: return "画面镜头: 未上报 activePhysicalId"
        val lens = lensByCameraId(activeId)
            ?: return "画面镜头: id=$activeId (未匹配焦距)"
        return String.format(
            Locale.US,
            "画面镜头: 35mm等效 %.0fmm (id=%s)",
            lens.eq35mm,
            activeId,
        )
    }

    fun findWideLens(candidates: List<LensCandidate>): LensCandidate? {
        return candidates
            .filter { it.category == LensCategory.WIDE }
            .minByOrNull { kotlin.math.abs(it.eq35mm - 24f) }
            ?: candidates.minByOrNull { it.eq35mm }
    }

    fun findTelephotoLenses(candidates: List<LensCandidate>, wide: LensCandidate): List<LensCandidate> {
        return candidates
            .filter { it != wide && it.eq35mm > wide.eq35mm }
            .filter {
                it.category == LensCategory.TELE ||
                    (it.eq35mm / wide.eq35mm).roundToInt().coerceAtLeast(1) > 1
            }
            .sortedBy { it.eq35mm }
    }

    fun findWideLensInSameLogicalCamera(lens: LensCandidate?, candidates: List<LensCandidate>): LensCandidate? {
        if (lens == null) return findWideLens(candidates)
        val sameLogical = candidates.filter { it.cameraId == lens.cameraId }
        return findWideLens(sameLogical.ifEmpty { candidates })
    }

    fun normalMinZoomFor(lens: LensCandidate?): Float {
        if (lens == null) return 1f
        val wide = findWideLensInSameLogicalCamera(lens, lensCandidates)
        val rawOpticalBase = if (wide != null && wide != lens && wide.eq35mm > 0f) {
            (lens.eq35mm / wide.eq35mm).roundToInt().coerceAtLeast(1).toFloat()
        } else {
            1f
        }
        return (rawOpticalBase.coerceAtLeast(1f) + NORMAL_MIN_ZOOM_OFFSET)
            .coerceAtMost(NORMAL_PREVIEW_MAX_ZOOM)
    }

    fun normalMaxZoomFor(lens: LensCandidate?): Float {
        return NORMAL_PREVIEW_MAX_ZOOM.coerceAtLeast(normalMinZoomFor(lens))
    }

    fun clampNormalZoom(zoom: Float, lens: LensCandidate?): Float {
        return zoom.coerceIn(normalMinZoomFor(lens), normalMaxZoomFor(lens))
    }

    fun postCalibrationZoomFor(lens: LensCandidate?): Float {
        return max(2f, normalMinZoomFor(lens)).coerceAtMost(normalMaxZoomFor(lens))
    }

    fun applyLensSwitch(target: LensCandidate, message: String) {
        selectedLens = target
        currentEquivRatio = normalMinZoomFor(target)
        anchorCenter1x = null
        anchorActive = false
        anchorZoom = currentEquivRatio
        currentCenterPx = null
        detectionBoxes = emptyList()
        statusText = "$message：${describeLens(target)}，起始 ${"%.1f".format(currentEquivRatio)}x"
    }

    fun toggleWideLens() {
        if (showCalPanel || showDualCalPanel || isDualCalibMode) {
            statusText = "校准流程中暂不切换广角镜头"
            return
        }
        val wide = findWideLens(lensCandidates)
        if (wide == null) {
            statusText = "未找到可切换的广角镜头"
            return
        }
        if (selectedLens == wide) {
            val fallback = defaultLens ?: selectLensUseCase(lensCandidates)
            if (fallback != null && fallback != wide) {
                applyLensSwitch(fallback, "已切回默认镜头")
            } else {
                statusText = "当前已是广角镜头：${describeLens(wide)}"
            }
            return
        }
        val message = if (wide.category == LensCategory.WIDE) {
            "已切换广角镜头"
        } else {
            "未找到标准广角，已切到最短焦距"
        }
        applyLensSwitch(wide, message)
    }

    fun centerFromAnchorAtZoom(anchor: LaserPoint, zoom: Float): LaserPoint {
        val cx = ImageUtils.OUT_W / 2.0
        val cy = ImageUtils.OUT_H / 2.0
        val z = zoom.coerceAtLeast(1f).toDouble()

        // 1x 锚点是目标距离在原始 1080x1920 坐标系中的位置。
        // 镜头做中心数码变焦时，画面中心不变，因此目标相对中心的偏移会按 zoom 线性放大：
        // current = center + (anchor1x - center) * zoom。
        return LaserPoint(
            x = (cx + (anchor.x - cx) * z).coerceIn(0.0, (ImageUtils.OUT_W - 1).toDouble()),
            y = (cy + (anchor.y - cy) * z).coerceIn(0.0, (ImageUtils.OUT_H - 1).toDouble()),
        )
    }

    fun updateCenterFromAnchorForZoom(zoom: Float) {
        val anchor = anchorCenter1x
        currentCenterPx = if (anchorActive && anchor != null) {
            centerFromAnchorAtZoom(anchor, zoom)
        } else {
            null
        }
    }

    fun minCoverScaleForFocus(focusX: Float, focusY: Float, width: Float, height: Float): Float {
        val minLR = max(1f, min(focusX, width - focusX))
        val minTB = max(1f, min(focusY, height - focusY))
        return max(1f, max(width / (2f * minLR), height / (2f * minTB)))
    }

    fun prepareSingleCalibrationZoom(): Float {
        val lens = selectedLens
        val targetZoom = if (lens?.category == LensCategory.WIDE) {
            WIDE_CALIBRATION_ZOOM
        } else {
            clampNormalZoom(currentEquivRatio, lens)
        }
        currentEquivRatio = targetZoom
        anchorActive = false
        anchorZoom = 1f
        currentCenterPx = null
        cameraController.setZoomRatio(targetZoom)
        statusText = if (lens?.category == LensCategory.WIDE) {
            "普通校准：广角镜头已请求 5x"
        } else {
            "普通校准：保持当前 ${"%.1f".format(targetZoom)}x"
        }
        return targetZoom
    }

    fun applyDualZoom(value: Float, keepAnchor: Boolean = true) {
        val wide = dualWideLens ?: return
        val tele = dualTeleLens ?: return
        val effective = value.coerceIn(1f, dualTelephotoMag.toFloat())
        currentEquivRatio = effective

        if (effective >= dualTelephotoMag.toFloat()) {
            isUsingDualTele = true
            cameraController.setZoomRatio(1f)
            selectedLens = tele
            dualTargetPrediction?.telePoint?.let {
                anchorCenter1x = it
                anchorZoom = 1f
                anchorActive = true
                currentCenterPx = it
            } ?: run {
                anchorActive = false
                currentCenterPx = null
            }
            statusText = "双镜头：长焦 ${dualTelephotoMag}x"
        } else {
            isUsingDualTele = false
            selectedLens = wide
            if (dualTargetPrediction != null && keepAnchor) {
                anchorCenter1x = dualTargetPrediction!!.widePoint1x
                anchorZoom = effective
                anchorActive = true
                updateCenterFromAnchorForZoom(effective)
            } else if (dualTargetPrediction == null) {
                anchorActive = false
                currentCenterPx = null
            }
            cameraController.setZoomRatio(effective)
            statusText = "双镜头：广角 ${"%.1f".format(effective)}x"
        }
    }

    fun startDualLensScan() {
        scope.launch {
            statusText = "正在扫描双镜头..."
            val candidates = withContext(Dispatchers.IO) { lensScanner.scan() }
            lensCandidates = candidates
            val wide = findWideLens(candidates)
            val teleList = wide?.let { findTelephotoLenses(candidates, it) }.orEmpty()

            dualWideLens = wide
            dualTeleOptions = teleList
            dualScanSummary = buildString {
                appendLine("扫描到 ${candidates.size} 个候选镜头")
                appendLine("广角: ${wide?.let { describeLens(it) } ?: "未找到"}")
                appendLine("长焦: ${if (teleList.isEmpty()) "未找到" else teleList.joinToString { describeLens(it, wide) }}")
            }.trim()

            if (wide == null || teleList.isEmpty()) {
                statusText = "双镜头不可用：未找到广角和长焦组合"
                dualLastResultText = statusText
            } else {
                showDualLensDialog = true
                statusText = "请选择长焦镜头"
            }
        }
    }

    fun chooseDualTelephoto(tele: LensCandidate) {
        pendingDualTeleLens = tele
        dualTeleLens = tele
        dualCoordError = null
        dualWideCoordX = dualTargetPrediction?.widePoint1x?.x?.toInt()?.toString() ?: ""
        dualWideCoordY = dualTargetPrediction?.widePoint1x?.y?.toInt()?.toString() ?: ""
        dualTeleCoordX = dualTargetPrediction?.telePoint?.x?.toInt()?.toString() ?: ""
        dualTeleCoordY = dualTargetPrediction?.telePoint?.y?.toInt()?.toString() ?: ""
        showDualLensDialog = false
        showDualCalChoiceDialog = true
    }

    fun activateDualCalibration(tele: LensCandidate) {
        val wide = dualWideLens ?: return
        val selectedTelephotoMag = (tele.eq35mm / wide.eq35mm).roundToInt().coerceAtLeast(2)
        dualTeleLens = tele
        isDualCalibMode = true
        isUsingDualTele = false
        showCalPanel = false
        showDualCalPanel = true
        showDualLensDialog = false
        showDualCalChoiceDialog = false
        showDualCoordInputDialog = false
        dualTargetPrediction = null
        pendingDualTeleLens = null
        val laserText = if (bleManager.state == BleConnectionManager.State.CONNECTED) {
            bleManager.laserOn()
            "，已发送打开绿光命令"
        } else {
            ""
        }
        dualLastResultText = "已锁定广角和 ${selectedTelephotoMag}x 长焦，请输入 d1$laserText"
        dualCalManager.start()
        anchorActive = false
        anchorZoom = 1f
        currentCenterPx = null
        cameraController.setZoomRatio(1f)
        selectedLens = wide
    }

    fun activateDualCalibrationWithCoordinates(
        tele: LensCandidate,
        widePoint: LaserPoint,
        telePoint: LaserPoint,
    ) {
        val wide = dualWideLens ?: return
        val selectedTelephotoMag = (tele.eq35mm / wide.eq35mm).roundToInt().coerceAtLeast(2)
        dualTeleLens = tele
        isDualCalibMode = true
        isUsingDualTele = false
        showCalPanel = false
        showDualCalPanel = false
        showDualLensDialog = false
        showDualCalChoiceDialog = false
        showDualCoordInputDialog = false
        pendingDualTeleLens = null
        dualCalManager.reset()
        dualTargetPrediction = com.example.auto_measure_luoji.feature.calibration.domain.DualTargetPrediction(
            widePoint1x = widePoint,
            telePoint = telePoint,
        )
        anchorCenter1x = widePoint
        anchorZoom = 1f
        anchorActive = true
        currentCenterPx = widePoint
        currentEquivRatio = 1f
        cameraController.setZoomRatio(1f)
        selectedLens = wide
        if (bleManager.state == BleConnectionManager.State.CONNECTED) {
            bleManager.laserOn()
        }
        dualLastResultText =
            "已使用双镜头坐标：广角=(${widePoint.x.toInt()}, ${widePoint.y.toInt()})，" +
                "长焦=(${telePoint.x.toInt()}, ${telePoint.y.toInt()})"
        statusText = "已锁定广角和 ${selectedTelephotoMag}x 长焦坐标"
    }

    fun exitDualCalibration() {
        isDualCalibMode = false
        showDualCalPanel = false
        showDualLensDialog = false
        showDualCalChoiceDialog = false
        showDualCoordInputDialog = false
        isUsingDualTele = false
        dualTargetPrediction = null
        pendingDualTeleLens = null
        dualCalManager.reset()
        anchorActive = false
        currentCenterPx = null
        val fallback = defaultLens ?: selectLensUseCase(lensCandidates)
        if (fallback != null) {
            selectedLens = fallback
            currentEquivRatio = normalMinZoomFor(fallback)
            anchorZoom = currentEquivRatio
            cameraController.setZoomRatio(currentEquivRatio)
        } else {
            currentEquivRatio = 1f
            anchorZoom = 1f
            cameraController.setZoomRatio(1f)
        }
        statusText = "已退出双镜头校准"
    }

    DisposableEffect(Unit) {
        cameraController.start()
        onDispose {
            cameraController.closeCamera(); cameraController.stop()
            if (bleManager.state == BleConnectionManager.State.CONNECTED) bleManager.disconnect()
            yoloDetector?.close()
            yoloDetector = null
        }
    }

    LaunchedEffect(Unit) {
        cameraController.onCameraOpened = { isCameraReady = true; statusText = "相机已打开" }
        cameraController.onCameraClosed = {
            isCameraReady = false
            activePhysicalCameraId = null
            activePhysicalLensReported = false
        }
        cameraController.onError = { msg -> isCameraReady = false; statusText = "错误: $msg" }
        cameraController.onOisStateChanged = { enabled -> oisEnabled = enabled }
        cameraController.onOisEnableFailed = { msg ->
            oisEnabled = false
            showOisFailure(msg)
        }
        cameraController.onZoomStateChanged = { msg ->
            mainHandler.post {
                zoomBackendText = msg
                Log.i("CameraScreen", msg)
            }
        }
        cameraController.onExposureStateChanged = { msg ->
            mainHandler.post {
                Log.i("CameraScreen", msg)
            }
        }
        cameraController.onVideoStabilizationStateChanged = { mode, msg ->
            mainHandler.post {
                eisMode = mode
                videoStabilizationText = msg
                Log.i("CameraScreen", msg)
            }
        }
        cameraController.onActivePhysicalCameraChanged = { cameraId ->
            mainHandler.post {
                activePhysicalCameraId = cameraId
                activePhysicalLensReported = true
                Log.i("CameraScreen", "active-physical-camera: ${cameraId ?: "not reported"}")
            }
        }
    }

    // BLE 距离回调：只显示 flag=1 的数据
    LaunchedEffect(Unit) {
        bleManager.onDistanceReceived = { data ->
            if (data.flag == 1) {
                val text = if (data.line < 200) String.format("%.2f m", data.line) else String.format("%.0f m", data.line)
                lastDeviceDistance = text
                Log.d("CameraScreen", "设备测距: ${data.line}m, flag=${data.flag}")
            }
        }
    }

    // 测距数据 2 秒后自动清空
    LaunchedEffect(lastDeviceDistance) {
        if (lastDeviceDistance != null) {
            delay(2000)
            lastDeviceDistance = null
        }
    }

    LaunchedEffect(isDualCalibMode, showDualCalPanel, isBleConnected) {
        if (isDualCalibMode && showDualCalPanel && isBleConnected) {
            bleManager.laserOn()
        }
    }

    // 生命周期监听
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPaused by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    hasPaused = true; cameraController.closeCamera(); isCameraReady = false
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (hasPaused) {
                        hasPaused = false
                        val lens = selectedLens; val tv = textureViewRef
                        if (lens != null && tv != null && tv.isAvailable) {
                            // 重新设置 buffer 尺寸，避免拉伸
                            val st = tv.surfaceTexture!!
                            cameraController.setSurfaceTexture(st)
                            val resumeZoom = if (isDualCalibMode && isUsingDualTele) {
                                1f
                            } else if (isDualCalibMode) {
                                currentEquivRatio.coerceAtLeast(1f)
                            } else {
                                clampNormalZoom(currentEquivRatio, lens)
                            }
                            cameraController.setZoomRatio(resumeZoom)
                            cameraController.openCamera(
                                lens,
                                android.view.Surface(st),
                                strictPhysical = isDualCalibMode && lens.physicalId != null,
                            )
                        }
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 镜头扫描：锁定最大长焦
    LaunchedEffect(Unit) {
        val candidates = withContext(Dispatchers.IO) { lensScanner.scan() }
        if (candidates.isEmpty()) { statusText = "未找到后置镜头"; return@LaunchedEffect }
        lensCandidates = candidates
        val lens = selectLensUseCase(candidates)
        if (lens == null) { statusText = "无可用镜头"; return@LaunchedEffect }
        defaultLens = lens
        selectedLens = lens
        currentEquivRatio = normalMinZoomFor(lens)
        anchorZoom = currentEquivRatio
        cameraController.setZoomRatio(currentEquivRatio)
        statusText = "选中: ${describeLens(lens)}，逻辑相机起始 ${"%.1f".format(currentEquivRatio)}x"
    }

    // 统一相机打开
    LaunchedEffect(selectedLens, surfaceAvailable, isDualCalibMode, isUsingDualTele) {
        val lens = selectedLens ?: return@LaunchedEffect
        if (!surfaceAvailable) return@LaunchedEffect
        val tv = textureViewRef ?: return@LaunchedEffect
        if (!tv.isAvailable) return@LaunchedEffect
        val initialZoom = if (isDualCalibMode && isUsingDualTele) {
            1f
        } else if (isDualCalibMode) {
            currentEquivRatio.coerceAtLeast(1f)
        } else {
            clampNormalZoom(currentEquivRatio, lens)
        }
        cameraController.setZoomRatio(initialZoom)
        cameraController.closeCamera()
        cameraController.openCamera(
            lens,
            android.view.Surface(tv.surfaceTexture!!),
            strictPhysical = isDualCalibMode && lens.physicalId != null,
        )
    }

    LaunchedEffect(isCameraReady, isDualCalibMode, isUsingDualTele, currentEquivRatio, selectedLens) {
        if (!isCameraReady) return@LaunchedEffect
        val cameraZoom = if (isDualCalibMode && isUsingDualTele) {
            1f
        } else if (isDualCalibMode) {
            currentEquivRatio.coerceAtLeast(1f)
        } else {
            clampNormalZoom(currentEquivRatio, selectedLens)
        }
        Log.i(
            "CameraScreen",
            String.format(
                Locale.US,
                "zoom-map: equiv=%.2fx, camera=%.2fx, lens=%s",
                currentEquivRatio,
                cameraZoom,
                selectedLens?.physicalId ?: selectedLens?.cameraId ?: "none",
            ),
        )
        cameraController.setZoomRatio(cameraZoom)
    }

    LaunchedEffect(isCameraReady, showCalPanel, showDualCalPanel) {
        val calibrationActive = showCalPanel || showDualCalPanel
        val targetEv = if (calibrationActive) CALIBRATION_EXPOSURE_EV else 0f
        cameraController.setExposureCompensationEv(targetEv)
            .onFailure { error ->
                Log.w("CameraScreen", "校准 EV 设置失败", error)
                if (calibrationActive) {
                    statusText = "校准 EV 设置失败: ${error.message}"
                }
            }
    }

    // 变换效果：倍率放大交给 Camera2；这里只做校准点居中和必要的边界覆盖。
    LaunchedEffect(currentCenterPx, anchorCenter1x, anchorZoom, anchorActive, currentEquivRatio, isCameraReady, isDualCalibMode, isUsingDualTele) {
        val wrapper = wrapperRef ?: return@LaunchedEffect
        if (!isCameraReady) return@LaunchedEffect
        val point = if (isDualCalibMode) {
            currentCenterPx ?: anchorCenter1x?.takeIf { anchorActive }?.let {
                centerFromAnchorAtZoom(it, anchorZoom)
            }
        } else {
            currentCenterPx ?: anchorCenter1x?.takeIf { anchorActive }?.let {
                centerFromAnchorAtZoom(it, currentEquivRatio)
            }
        }
        wrapper.post {
            val w = wrapper.width.toFloat()
            val h = wrapper.height.toFloat()
            if (w <= 0f || h <= 0f) return@post
            val screenCX = w / 2f
            val screenCY = h / 2f

            if (anchorActive && point != null) {
                val focusX = (point.x / ImageUtils.OUT_W.toDouble() * w).toFloat()
                val focusY = (point.y / ImageUtils.OUT_H.toDouble() * h).toFloat()
                val coverScale = minCoverScaleForFocus(focusX, focusY, w, h)
                val scale = coverScale

                // 1080x1920 归一化坐标先映射到实际预览容器，再用中心 pivot 变换。
                // 以中心为 pivot 时：screen = (focus - center) * scale + center + translation。
                // 令 screen = center，可得 translation = (center - focus) * scale。
                wrapper.pivotX = screenCX
                wrapper.pivotY = screenCY
                wrapper.translationX = (screenCX - focusX) * scale
                wrapper.translationY = (screenCY - focusY) * scale
                wrapper.scaleX = scale
                wrapper.scaleY = scale
                wrapperTx = wrapper.translationX
                wrapperTy = wrapper.translationY
                wrapperScale = scale
            } else if (isDualCalibMode) {
                wrapper.pivotX = screenCX
                wrapper.pivotY = screenCY
                wrapper.translationX = 0f
                wrapper.translationY = 0f
                wrapper.scaleX = 1f
                wrapper.scaleY = 1f
                wrapperTx = 0f
                wrapperTy = 0f
                wrapperScale = 1f
            } else {
                wrapper.pivotX = screenCX
                wrapper.pivotY = screenCY
                wrapper.translationX = 0f
                wrapper.translationY = 0f
                wrapper.scaleX = 1f
                wrapper.scaleY = 1f
                wrapperTx = 0f
                wrapperTy = 0f
                wrapperScale = 1f
            }
        }
    }

    // 检测帧处理循环：支持 TextureView.getBitmap() 和 ImageReader YUV 两种数据源。

    LaunchedEffect(detectionEnabled, yoloCaptureMode, yoloFrameSource) {
        if (!detectionEnabled || yoloDetector == null) {
            detectionBoxes = emptyList()
            detectionPerfText = ""
            return@LaunchedEffect
        }
        detectionPerfText = "等待首帧..."
        Log.d("CameraScreen", "detection loop started: source=${yoloFrameSource.label}")

        try {
            while (detectionEnabled) {
                if (!isCameraReady) { delay(10); continue }
                val tv = textureViewRef
                if (isProcessing) { delay(10); continue }
                if (yoloFrameSource == YoloFrameSource.PREVIEW_BITMAP && (tv == null || !tv.isAvailable)) {
                    delay(10)
                    continue
                }
                if (isDetecting.get()) { delay(10); continue }

                isDetecting.set(true)
                try {
                    val detector = yoloDetector
                    if (detector != null) {
                        val captureMode = yoloCaptureMode
                        val t0 = System.currentTimeMillis()
                        var sourceLabel = ""
                        var sourceWidth = 0
                        var sourceHeight = 0
                        val result = when (yoloFrameSource) {
                            YoloFrameSource.PREVIEW_BITMAP -> {
                                val snapshot = withContext(Dispatchers.Main) {
                                    tv?.getBitmap(captureMode.width, captureMode.height)
                                }
                                if (snapshot == null) {
                                    null
                                } else {
                                    try {
                                        sourceWidth = snapshot.width
                                        sourceHeight = snapshot.height
                                        sourceLabel = "PreviewBitmap ${captureMode.label} ${snapshot.width}x${snapshot.height}"
                                        Log.d("CameraScreen", "YOLO source: $sourceLabel")
                                        withContext(Dispatchers.Default) { detector.detect(snapshot) }
                                    } finally {
                                        snapshot.recycle()
                                    }
                                }
                            }

                            YoloFrameSource.IMAGE_READER_YUV -> {
                                if (cameraController.analysisImageReader == null) {
                                    detectionBoxes = emptyList()
                                    showMeasurePrompt = false
                                    wasTargetInArea = false
                                    detectionPerfText = "YUV数据流不可用：当前相机会话未创建ImageReader"
                                    null
                                } else {
                                    val image = cameraController.acquireLatestAnalysisImage()
                                    if (image == null) {
                                        detectionPerfText = "等待YUV帧..."
                                        null
                                    } else {
                                        try {
                                            val rotation = cameraController.sensorOrientation
                                            sourceWidth = if (rotation == 90 || rotation == 270) image.height else image.width
                                            sourceHeight = if (rotation == 90 || rotation == 270) image.width else image.height
                                            sourceLabel = "ImageReaderYUV ${image.width}x${image.height} rot=$rotation"
                                            Log.d("CameraScreen", "YOLO source: $sourceLabel")
                                            withContext(Dispatchers.Default) { detector.detectFromYuv(image, rotation) }
                                        } finally {
                                            image.close()
                                        }
                                    }
                                }
                            }
                        }

                        if (result != null && sourceWidth > 0 && sourceHeight > 0) {
                            val totalMs = System.currentTimeMillis() - t0
                            Log.d(
                                "CameraScreen",
                                "YOLO timing source=$sourceLabel pre=${result.timings.preprocessMs}ms " +
                                    "inf=${result.timings.inferenceMs}ms post=${result.timings.postprocessMs}ms " +
                                    "total=${totalMs}ms",
                            )

                            val wrapper = wrapperRef
                            if (wrapper != null && wrapper.width > 0 && wrapper.height > 0) {
                                val viewW = wrapper.width.toFloat()
                                val viewH = wrapper.height.toFloat()
                                val srcW = sourceWidth.toFloat()
                                val srcH = sourceHeight.toFloat()

                                val scale = max(viewW / srcW, viewH / srcH)
                                val dx = (viewW - srcW * scale) / 2f
                                val dy = (viewH - srcH * scale) / 2f

                                val mapped = result.detections.map { d ->
                                    val r = RectF(
                                        d.box.left * scale + dx,
                                        d.box.top * scale + dy,
                                        d.box.right * scale + dx,
                                        d.box.bottom * scale + dy,
                                    )
                                    r to "${d.label} ${"%.2f".format(d.score)}"
                                }
                                detectionBoxes = mapped
                                detectionPerfText = "$sourceLabel | pre=${result.timings.preprocessMs}ms inf=${result.timings.inferenceMs}ms post=${result.timings.postprocessMs}ms total=${totalMs}ms | ${result.detections.size}obj"
                                Log.d("CameraScreen", "detection done: objects=${result.detections.size}, total=${totalMs}ms")

                                val screenCX = viewW / 2f
                                val screenCY = viewH / 2f
                                val areaHalfW = areaWidthPx / 2f
                                val areaHalfH = areaHeightPx / 2f
                                val tx = wrapperTx; val ty = wrapperTy; val s = wrapperScale
                                val pts = checkPoints
                                val hasTargetInArea = mapped.any { (box, _) ->
                                    fun isPointInside(pctW: Float, pctH: Float): Boolean {
                                        val px = box.left + box.width() * pctW
                                        val py = box.top + box.height() * pctH
                                        val cx = (px - screenCX) * s + screenCX + tx
                                        val cy = (py - screenCY) * s + screenCY + ty
                                        return cx in (screenCX - areaHalfW)..(screenCX + areaHalfW) &&
                                                cy in (screenCY - areaHalfH)..(screenCY + areaHalfH)
                                    }
                                    if (requireAllPoints) pts.isNotEmpty() && pts.all { isPointInside(it.first, it.second) }
                                    else pts.any { isPointInside(it.first, it.second) }
                                }
                                showMeasurePrompt = hasTargetInArea
                                if (hasTargetInArea && !wasTargetInArea) {
                                    measureTrigger = true
                                }
                                wasTargetInArea = hasTargetInArea
                            }

                            if (statMeasuring) {
                                if (android.os.SystemClock.elapsedRealtime() > statEndMs) {
                                    statMeasuring = false
                                    val rate = if (statTotal > 0) statHit * 100f / statTotal else 0f
                                    statResultText = "成功率 ${"%.1f".format(rate)}%  命中=$statHit/$statTotal"
                                } else {
                                    statTotal++
                                    if (result.detections.isNotEmpty()) statHit++
                                }
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("CameraScreen", "检测异常", e)
                    detectionPerfText = "异常: ${e.message}"
                } finally {
                    isDetecting.set(false)
                }
                delay(10)
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // LaunchedEffect 被取消，正常退出。

        }
        Log.d("CameraScreen", "detection loop stopped")
        detectionBoxes = emptyList()
        detectionPerfText = ""
    }
    // UI
    Box(modifier = modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        // 预览区域：FrameLayout 包裹 TextureView，变换应用在 FrameLayout 上
        Box(modifier = Modifier.fillMaxHeight().aspectRatio(9f / 16f)) {
            AndroidView(
                factory = { ctx ->
                    val wrapper = FrameLayout(ctx)
                    val tv = TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                surfaceAvailable = true; cameraController.setSurfaceTexture(st)
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                surfaceAvailable = false; cameraController.closeCamera(); isCameraReady = false; return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                    // TextureView 填满容器，不做任何变换
                    wrapper.addView(tv, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                    textureViewRef = tv
                    wrapperRef = wrapper
                    wrapper
                },
                modifier = Modifier.fillMaxSize()
            )
            CrosshairOverlay(modifier = Modifier.fillMaxSize())
            // 检测框跟随 wrapper 变换（与预览内容一致）
            DetectionOverlay(
                boxes = detectionBoxes,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    translationX = wrapperTx; translationY = wrapperTy
                    scaleX = wrapperScale; scaleY = wrapperScale
                },
            )
            // 识别区域固定在屏幕中心（校准后屏幕中心就是 100m 坐标位置）
            if (detectionEnabled) {
                RecognitionArea(
                    areaWidthPx = areaWidthPx,
                    areaHeightPx = areaHeightPx,
                    isTargetInside = showMeasurePrompt,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val wideLens = findWideLens(lensCandidates)
                val wideLensActive = !isDualCalibMode && wideLens != null && selectedLens == wideLens
                // 顶部固定区只保留核心操作，其他入口收进“更多”，避免小屏把“识别”挤到不可见。
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.52f))
                        .horizontalScroll(rememberScrollState())
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TopActionButton(
                        text = if (detectionEnabled) "识别中" else "识别",
                        active = detectionEnabled,
                        enabled = yoloDetector != null,
                        accent = Color(0xFFFF9800),
                        onClick = { detectionEnabled = !detectionEnabled },
                    )
                    TopActionButton(
                        text = if (isDualCalibMode) "双摄" else if (showCalPanel) "校准中" else "校准",
                        active = showCalPanel || showDualCalPanel || isDualCalibMode,
                        onClick = {
                            when {
                                isDualCalibMode && dualCalManager.state == DualCalibrationManager.State.IDLE && dualTargetPrediction != null -> {
                                    pendingDualTeleLens = dualTeleLens
                                    dualWideCoordX = dualTargetPrediction?.widePoint1x?.x?.toInt()?.toString() ?: ""
                                    dualWideCoordY = dualTargetPrediction?.widePoint1x?.y?.toInt()?.toString() ?: ""
                                    dualTeleCoordX = dualTargetPrediction?.telePoint?.x?.toInt()?.toString() ?: ""
                                    dualTeleCoordY = dualTargetPrediction?.telePoint?.y?.toInt()?.toString() ?: ""
                                    dualCoordError = null
                                    showDualCalChoiceDialog = true
                                }
                                isDualCalibMode -> showDualCalPanel = true
                                !showCalPanel && !showDualCalPanel -> showCalibrationModeDialog = true
                            }
                        },
                    )
                    if (isLaserCompareApp) {
                        TopActionButton(
                            text = if (laserCompareEnabled) "对比中" else "绿光对比",
                            active = showLaserComparePanel || laserCompareEnabled,
                            accent = Color(0xFF00C853),
                            onClick = { toggleLaserComparePanel() },
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box {
                    TopActionButton(
                        text = "更多",
                        active = toolsMenuExpanded ||
                            wideLensActive ||
                            oisEnabled ||
                            eisMode != Camera2Controller.getDefaultVideoStabilizationMode() ||
                            isVoiceListening ||
                            yoloCaptureMode == YoloCaptureMode.UHD4K ||
                            yoloFrameSource == YoloFrameSource.IMAGE_READER_YUV ||
                            showLaserComparePanel ||
                            statMeasuring,
                        accent = Color(0xFF607D8B),
                        onClick = { toolsMenuExpanded = true },
                    )
                    DropdownMenu(
                        expanded = toolsMenuExpanded,
                        onDismissRequest = { toolsMenuExpanded = false },
                        modifier = Modifier.background(Color(0xF0222222)),
                    ) {
                        ToolMenuItem(
                            text = if (wideLensActive) "广角中" else "广角",
                            active = wideLensActive,
                            enabled = lensCandidates.isNotEmpty() && !showCalPanel && !showDualCalPanel && !isDualCalibMode,
                            accent = Color(0xFF26A69A),
                            onClick = {
                                toolsMenuExpanded = false
                                toggleWideLens()
                            },
                        )
                        ToolMenuItem(
                            text = if (oisEnabled) "OIS开" else "OIS",
                            active = oisEnabled,
                            accent = Color(0xFF00BCD4),
                            onClick = {
                                toolsMenuExpanded = false
                                toggleOis()
                            },
                        )
                        ToolMenuItem(
                            text = "防抖 EIS:${Camera2Controller.videoStabilizationModeName(eisMode)}",
                            active = eisMode != Camera2Controller.getDefaultVideoStabilizationMode(),
                            accent = Color(0xFFFF7043),
                            onClick = {
                                toolsMenuExpanded = false
                                showStabilizationDialog = true
                            },
                        )
                        ToolMenuItem(
                            text = if (isVoiceListening) "语音中" else "语音",
                            active = isVoiceListening,
                            accent = Color(0xFF9C27B0),
                            onClick = {
                                toolsMenuExpanded = false
                                toggleVoiceRecognition()
                            },
                        )
                        ToolMenuItem(
                            text = "Log",
                            onClick = {
                                toolsMenuExpanded = false
                                showLogViewer = true
                            },
                        )
                        ToolMenuItem(
                            text = "图源 ${yoloCaptureMode.label}",
                            active = yoloCaptureMode == YoloCaptureMode.UHD4K,
                            onClick = {
                                toolsMenuExpanded = false
                                val nextMode = yoloCaptureMode.next()
                                yoloCaptureMode = nextMode
                                statusText = "模型图源: ${nextMode.label} ${nextMode.width}x${nextMode.height} -> 模型输入"
                            },
                        )
                        ToolMenuItem(
                            text = "YOLO源 ${yoloFrameSource.label}",
                            active = yoloFrameSource == YoloFrameSource.IMAGE_READER_YUV,
                            accent = Color(0xFF03A9F4),
                            onClick = {
                                toolsMenuExpanded = false
                                val nextSource = yoloFrameSource.next()
                                yoloFrameSource = nextSource
                                cameraPrefs.edit()
                                    .putString(KEY_YOLO_FRAME_SOURCE, nextSource.name)
                                    .apply()
                                detectionBoxes = emptyList()
                                showMeasurePrompt = false
                                wasTargetInArea = false
                                statusText = "YOLO数据源: ${nextSource.label}"
                            },
                        )
                        if (!isLaserCompareApp) {
                            ToolMenuItem(
                                text = if (laserCompareEnabled) "绿光识别中" else "绿光识别",
                                active = showLaserComparePanel || laserCompareEnabled,
                                accent = Color(0xFF00C853),
                                onClick = {
                                    toolsMenuExpanded = false
                                    toggleLaserComparePanel()
                                },
                            )
                        }
                        ToolMenuItem(
                            text = "区域",
                            onClick = {
                                toolsMenuExpanded = false
                                areaInputW = areaWidthPx.toInt().toString()
                                areaInputH = areaHeightPx.toInt().toString()
                                showAreaDialog = true
                            },
                        )
                        ToolMenuItem(
                            text = "判定点",
                            onClick = {
                                toolsMenuExpanded = false
                                showPointDialog = true
                            },
                        )
                        ToolMenuItem(
                            text = if (statMeasuring) "统计中" else "统计",
                            active = statMeasuring,
                            enabled = detectionEnabled && !statMeasuring,
                            accent = Color(0xFF2196F3),
                            onClick = {
                                toolsMenuExpanded = false
                                statTotal = 0
                                statHit = 0
                                statEndMs = android.os.SystemClock.elapsedRealtime() + 30_000L
                                statMeasuring = true
                                statResultText = "统计中..."
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (showLaserComparePanel) {
                LaserComparePanel(
                    enabled = laserCompareEnabled,
                    statusText = laserCompareText,
                    onEnabledChange = { enabled ->
                        if (enabled) {
                            runLaserCompareRecognition()
                        } else {
                            laserCompareEnabled = false
                            laserCompareText = "绿光算法对比已关闭"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                val leftMs = (statEndMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                StatusHud(
                    statusText = statusText,
                    modelText = when {
                        yoloDetector == null -> "模型: $modelLoadStatus"
                        detectionEnabled -> detectionPerfText
                        else -> ""
                    },
                    statText = when {
                        statMeasuring -> "统计中 ${"%.1f".format(leftMs / 1000f)}s · $statHit/$statTotal"
                        statResultText.isNotEmpty() -> statResultText
                        else -> ""
                    },
                    lensText = activeFrameLensText(),
                    zoomText = zoomBackendText,
                    anchorText = if (anchorActive && anchorCenter1x != null) {
                        val a = anchorCenter1x!!
                        "锁定 (${a.x.toInt()}, ${a.y.toInt()}) · ${"%.1f".format(currentEquivRatio)}x"
                    } else {
                        ""
                    },
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                BluetoothPanel(
                    manager = bleManager,
                    modifier = Modifier.widthIn(min = 132.dp, max = 190.dp),
                )
            }
        }

        // 测距逻辑：measureTrigger 上升沿触发，scope.launch 独立跑满 3 秒
        LaunchedEffect(measureTrigger) {
            if (measureTrigger) {
                measureTrigger = false
                if (detectionEnabled && isBleConnected && !isDeviceMeasuring) {
                    scope.launch {
                        isDeviceMeasuring = true
                        lastDeviceDistance = null
                        bleManager.measure()
                        Log.d("CameraScreen", "设备测距: 发送 AT+Measure")
                        delay(3000)
                        bleManager.stopMeasure()
                        isDeviceMeasuring = false
                        Log.d("CameraScreen", "设备测距: 发送 AT+Stop")
                    }
                }
            }
        }

        // 测距提示（未连接设备时显示弹框）
        if (showMeasurePrompt && detectionEnabled && !isBleConnected) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 120.dp)
                    .background(Color(0xFF4CAF50), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "测距",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
            }
        }

        // 设备测距中提示
        if (isDeviceMeasuring) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 120.dp)
                    .background(Color(0xFFFF9800), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "测距中...",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
            }
        }

        // 语音热词命中提示
        LaunchedEffect(voiceHitText) {
            if (voiceHitText != null) {
                delay(2000)
                voiceHitText = null
            }
        }
        if (voiceHitText != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 64.dp)
                    .background(Color(0xFF7B1FA2), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = voiceHitText!!,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
            }
        }

        LaunchedEffect(oisFailureText) {
            if (oisFailureText != null) {
                delay(3000)
                oisFailureText = null
            }
        }
        if (oisFailureText != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 64.dp)
                    .background(Color(0xFFD32F2F), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = oisFailureText!!,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 设备测距结果
        if (lastDeviceDistance != null && !isDeviceMeasuring) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 120.dp)
                    .background(Color(0xFF2196F3), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = lastDeviceDistance!!,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
            }
        }

        // 校准面板
        if (showCalPanel) {
            CalibrationPanel(
                calManager = calManager,
                bleManager = bleManager,
                onCollect = { distance, onResult ->
                    scope.launch {
                        isProcessing = true
                        lastResultText = "正在检测...（${yoloFrameSource.label}）"
                        var success = false
                        var capture: LaserFrameCapture? = null
                        try {
                            val frameCapture = captureLaserFrameForCalibration()
                            capture = frameCapture
                            val normalized = frameCapture.normalizedBitmap
                            val ts = System.currentTimeMillis()
                            val rawBytes = frameCapture.pngBytes
                            val sourcePrefix = if (yoloFrameSource == YoloFrameSource.IMAGE_READER_YUV) {
                                "cal_yuv"
                            } else {
                                "cal_preview"
                            }
                            try {
                                GallerySaver.savePng(context, rawBytes, "${sourcePrefix}_raw_$ts.png")
                            } catch (e: Exception) {
                                Log.e("CameraScreen", "保存原始图失败", e)
                            }
                            val result = withContext(Dispatchers.Default) {
                                calManager.collectSample(rawBytes, distance, currentEquivRatio.toDouble())
                            }
                            Log.d(
                                "CameraScreen",
                                "single calibration source=${frameCapture.sourceLabel}, " +
                                    "result=${if (result != null) "success" else "failed"}, " +
                                    "state=${calManager.state}, samples=${calManager.getSampleCount()}",
                            )
                            if (result != null) {
                                success = true
                                try {
                                    val markedBytes = withContext(Dispatchers.Default) {
                                        ImageUtils.drawMarkOnBitmap(normalized, result.cx, result.cy, 1f)
                                    }
                                    GallerySaver.savePng(context, markedBytes, "${sourcePrefix}_marked_$ts.png")
                                } catch (_: Exception) {
                                }
                                lastResultText = "检测成功[${frameCapture.sourceLabel}]: (${result.cx.toInt()}, ${result.cy.toInt()}) conf=${"%.2f".format(result.confidence)} | 第${calManager.state.ordinal}/2次"
                            } else {
                                lastResultText = "绿光识别失败：未检测到绿光，请调整瞄准后重试"
                            }
                        } catch (e: Exception) {
                            Log.e("CameraScreen", "采集异常", e)
                            lastResultText = "采集异常: ${e.message}"
                        } finally {
                            try { capture?.normalizedBitmap?.recycle() } catch (_: Exception) {}
                            isProcessing = false
                            onResult(success)
                        }
                    }
                },
                onApplyTarget = { targetDistance ->
                    val predicted = calManager.predictAtDistance(targetDistance) ?: return@CalibrationPanel
                    val params = calManager.calibrationParams
                    if (params != null) {
                        Log.d("CameraScreen", "校准应用: 目标距离=${targetDistance}m, 预测坐标=(${predicted.x.toInt()}, ${predicted.y.toInt()})")
                    }
                    anchorCenter1x = predicted
                    lastResultText = "已锁定: (${predicted.x.toInt()}, ${predicted.y.toInt()})"
                    val targetZoom = postCalibrationZoomFor(selectedLens)
                    currentEquivRatio = targetZoom
                    anchorZoom = targetZoom
                    anchorActive = true
                    updateCenterFromAnchorForZoom(targetZoom)
                    showCalPanel = false
                },
                onClose = {
                    showCalPanel = false
                    anchorActive = false
                    anchorZoom = 1f
                    currentCenterPx = null
                },
                isProcessing = isProcessing, lastResultText = lastResultText,
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(12.dp)
            )
        }

        if (showDualCalPanel) {
            DualCalibrationPanel(
                manager = dualCalManager,
                bleManager = bleManager,
                telephotoMag = dualTelephotoMag,
                isProcessing = isProcessing,
                lastResultText = dualLastResultText,
                onInputDistance = { distance ->
                    val result = dualCalManager.inputDistance(distance)
                    result
                        .onSuccess {
                            dualLastResultText = "已输入 d=${"%.2f".format(distance)}m，切到广角 5x"
                            applyDualZoom(5f, keepAnchor = false)
                        }
                        .onFailure { dualLastResultText = it.message ?: "距离输入失败" }
                },
                onCollect = {
                    scope.launch {
                        isProcessing = true
                        dualLastResultText = "正在检测绿光...（${yoloFrameSource.label}）"
                        var capture: LaserFrameCapture? = null
                        try {
                            val frameCapture = captureLaserFrameForCalibration()
                            capture = frameCapture
                            val normalized = frameCapture.normalizedBitmap
                            val ts = System.currentTimeMillis()
                            val prefixBase = if (dualCalManager.state == DualCalibrationManager.State.WAIT_WIDE) {
                                "dual_wide"
                            } else {
                                "dual_tele"
                            }
                            val sourcePrefix = if (yoloFrameSource == YoloFrameSource.IMAGE_READER_YUV) {
                                "${prefixBase}_yuv"
                            } else {
                                "${prefixBase}_preview"
                            }

                            val rawBytes = frameCapture.pngBytes
                            try {
                                GallerySaver.savePng(context, rawBytes, "${sourcePrefix}_raw_$ts.png")
                            } catch (e: Exception) {
                                Log.e("CameraScreen", "保存双摄原始图失败", e)
                            }

                            val result = withContext(Dispatchers.Default) {
                                LaserDetector.detectLaserCenterFromBytes(rawBytes)
                            }
                            if (result == null) {
                                dualLastResultText = "未检测到绿光，请调整瞄准后重试"
                                return@launch
                            }

                            try {
                                val markedBytes = withContext(Dispatchers.Default) {
                                    ImageUtils.drawMarkOnBitmap(normalized, result.cx, result.cy, 1f)
                                }
                                GallerySaver.savePng(context, markedBytes, "${sourcePrefix}_marked_$ts.png")
                            } catch (e: Exception) {
                                Log.e("CameraScreen", "保存双摄标记图失败", e)
                            }

                            val point = LaserPoint(result.cx, result.cy)
                            when (dualCalManager.state) {
                                DualCalibrationManager.State.WAIT_WIDE -> {
                                    dualCalManager.recordWidePoint(point, wideZoom = 5.0)
                                        .onSuccess {
                                            dualLastResultText =
                                                "广角5x成功[${frameCapture.sourceLabel}]: (${point.x.toInt()}, ${point.y.toInt()})，切到长焦${dualTelephotoMag}x"
                                            applyDualZoom(dualTelephotoMag.toFloat(), keepAnchor = false)
                                        }
                                        .onFailure { dualLastResultText = it.message ?: "广角采集失败" }
                                }

                                DualCalibrationManager.State.WAIT_TELE -> {
                                    dualCalManager.recordTelePoint(point)
                                        .onSuccess {
                                            if (dualCalManager.state == DualCalibrationManager.State.CALIBRATED) {
                                                val wide100 = dualCalManager.wide100mPoint
                                                val tele100 = dualCalManager.tele100mPoint
                                                dualLastResultText =
                                                    "双摄标定完成：广角100m=(${wide100?.x?.toInt()}, ${wide100?.y?.toInt()})，" +
                                                        "长焦100m=(${tele100?.x?.toInt()}, ${tele100?.y?.toInt()})"
                                            } else {
                                                dualLastResultText =
                                                    "长焦${dualTelephotoMag}x成功[${frameCapture.sourceLabel}]: (${point.x.toInt()}, ${point.y.toInt()})，请输入d2"
                                            }
                                        }
                                        .onFailure { dualLastResultText = it.message ?: "长焦采集失败" }
                                }

                                else -> {
                                    dualLastResultText = "请按当前步骤操作"
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("CameraScreen", "双摄采集异常", e)
                            dualLastResultText = "采集异常: ${e.message}"
                        } finally {
                            try { capture?.normalizedBitmap?.recycle() } catch (_: Exception) {}
                            isProcessing = false
                        }
                    }
                },
                onApplyTarget = { targetDistance ->
                    dualCalManager.predictAtDistance(targetDistance)
                        .onSuccess { prediction ->
                            dualTargetPrediction = prediction
                            anchorCenter1x = prediction.widePoint1x
                            anchorZoom = 1f
                            anchorActive = true
                            currentCenterPx = prediction.widePoint1x
                            dualLastResultText =
                                "D=${"%.2f".format(targetDistance)}m：广角=(${prediction.widePoint1x.x.toInt()}, ${prediction.widePoint1x.y.toInt()})" +
                                    (prediction.telePoint?.let { "，长焦=(${it.x.toInt()}, ${it.y.toInt()})" } ?: "")
                            applyDualZoom(1f, keepAnchor = true)
                            showDualCalPanel = false
                        }
                        .onFailure { dualLastResultText = it.message ?: "目标距离应用失败" }
                },
                onClose = { exitDualCalibration() },
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(12.dp),
            )
        }

        // 倍率滑块（固定在底部）
        if (isCameraReady && !showCalPanel && !showDualCalPanel) {
            ZoomSlider(
                currentRatio = currentEquivRatio,
                minRatio = if (isDualCalibMode) 1f else normalMinZoomFor(selectedLens),
                maxRatio = if (isDualCalibMode) {
                    dualTelephotoMag.toFloat()
                } else {
                    normalMaxZoomFor(selectedLens)
                },
                labelSuffix = if (isDualCalibMode) {
                    if (isUsingDualTele) " (T)" else " (W)"
                } else {
                    ""
                },
                onRatioChanged = { ratio ->
                    if (isDualCalibMode) {
                        applyDualZoom(ratio, keepAnchor = true)
                    } else {
                        currentEquivRatio = ratio
                        if (anchorActive) {
                            anchorZoom = ratio
                            updateCenterFromAnchorForZoom(ratio)
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = if (isBleConnected) 100.dp else 8.dp)
            )
        }

        // 设备控制栏（蓝牙连接后显示，固定在最底部）
        if (isBleConnected && !showCalPanel && !showDualCalPanel) {
            DeviceControls(
                manager = bleManager,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 0.dp)
            )
        }

        // 日志查看器
        LogViewerOverlay(visible = showLogViewer, onDismiss = { showLogViewer = false })

        // 校准方式选择对话框
        if (showCalibrationModeDialog) {
            AlertDialog(
                onDismissRequest = { showCalibrationModeDialog = false },
                title = { Text("选择校准方式") },
                text = { Text("普通校准保持原流程；双镜头校准会扫描广角和长焦，并按 5x/长焦倍率依次采集。") },
                confirmButton = {
                    TextButton(onClick = {
                        showCalibrationModeDialog = false
                        showCalChoiceDialog = true
                    }) { Text("普通校准") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showCalibrationModeDialog = false
                        startDualLensScan()
                    }) { Text("双镜头校准") }
                },
            )
        }

        // 双镜头扫描结果对话框
        if (showDualLensDialog) {
            AlertDialog(
                onDismissRequest = { showDualLensDialog = false },
                title = { Text("双镜头扫描结果") },
                text = {
                    Column(
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(dualScanSummary, fontSize = 12.sp)
                        dualTeleOptions.forEach { tele ->
                            val wide = dualWideLens
                            val mag = wide?.let { (tele.eq35mm / it.eq35mm).roundToInt().coerceAtLeast(2) } ?: 10
                            Button(
                                onClick = { chooseDualTelephoto(tele) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            ) {
                                Text("选择 ${mag}x 长焦")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDualLensDialog = false }) { Text("取消") }
                },
            )
        }

        // 双镜头校准数据选择
        if (showDualCalChoiceDialog) {
            val tele = pendingDualTeleLens ?: dualTeleLens
            val wide = dualWideLens
            val mag = if (tele != null && wide != null) {
                (tele.eq35mm / wide.eq35mm).roundToInt().coerceAtLeast(2)
            } else {
                dualTelephotoMag
            }
            AlertDialog(
                onDismissRequest = { showDualCalChoiceDialog = false },
                title = { Text("双镜头校准") },
                text = { Text("是否已有广角和 ${mag}x 长焦的 100m 校准坐标？") },
                confirmButton = {
                    TextButton(onClick = {
                        showDualCalChoiceDialog = false
                        dualCoordError = null
                        showDualCoordInputDialog = true
                    }) { Text("有，输入坐标") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        val selectedTele = pendingDualTeleLens ?: dualTeleLens ?: return@TextButton
                        showDualCalChoiceDialog = false
                        activateDualCalibration(selectedTele)
                    }) { Text("没有，开始校准") }
                },
            )
        }

        // 双镜头 100m 坐标输入对话框
        if (showDualCoordInputDialog) {
            AlertDialog(
                onDismissRequest = { showDualCoordInputDialog = false },
                title = { Text("输入双镜头100m坐标") },
                text = {
                    Column(
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("坐标使用 1080x1920 坐标系。广角坐标用于 1x 到长焦阈值前；长焦坐标用于长焦物理镜头。")
                        Text("广角 100m 坐标", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = dualWideCoordX,
                                onValueChange = { dualWideCoordX = it; dualCoordError = null },
                                label = { Text("广角 X") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = dualWideCoordY,
                                onValueChange = { dualWideCoordY = it; dualCoordError = null },
                                label = { Text("广角 Y") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Text("长焦 100m 坐标", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = dualTeleCoordX,
                                onValueChange = { dualTeleCoordX = it; dualCoordError = null },
                                label = { Text("长焦 X") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = dualTeleCoordY,
                                onValueChange = { dualTeleCoordY = it; dualCoordError = null },
                                label = { Text("长焦 Y") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        if (dualCoordError != null) {
                            Text(dualCoordError!!, color = Color(0xFFFF9800), fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val selectedTele = pendingDualTeleLens ?: dualTeleLens
                        val wx = dualWideCoordX.toDoubleOrNull()
                        val wy = dualWideCoordY.toDoubleOrNull()
                        val tx = dualTeleCoordX.toDoubleOrNull()
                        val ty = dualTeleCoordY.toDoubleOrNull()
                        if (selectedTele == null) {
                            dualCoordError = "请先选择长焦镜头"
                            return@TextButton
                        }
                        if (wx == null || wy == null || tx == null || ty == null) {
                            dualCoordError = "请完整输入四个坐标值"
                            return@TextButton
                        }
                        val widePoint = LaserPoint(
                            x = wx.coerceIn(0.0, (ImageUtils.OUT_W - 1).toDouble()),
                            y = wy.coerceIn(0.0, (ImageUtils.OUT_H - 1).toDouble()),
                        )
                        val telePoint = LaserPoint(
                            x = tx.coerceIn(0.0, (ImageUtils.OUT_W - 1).toDouble()),
                            y = ty.coerceIn(0.0, (ImageUtils.OUT_H - 1).toDouble()),
                        )
                        activateDualCalibrationWithCoordinates(selectedTele, widePoint, telePoint)
                    }) { Text("确认") }
                },
                dismissButton = {
                    TextButton(onClick = { showDualCoordInputDialog = false }) { Text("取消") }
                },
            )
        }

        // 校准选择对话框
        if (showCalChoiceDialog) {
            AlertDialog(
                onDismissRequest = { showCalChoiceDialog = false },
                title = { Text("校准") },
                text = { Text("是否有校准数据？") },
                confirmButton = {
                    TextButton(onClick = {
                        showCalChoiceDialog = false
                        val predicted = calManager.predictAtDistance(100.0)
                        coordInputX = predicted?.x?.toInt()?.toString() ?: ""
                        coordInputY = predicted?.y?.toInt()?.toString() ?: ""
                        showCoordInputDialog = true
                    }) { Text("有，输入坐标") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showCalChoiceDialog = false
                        val calibrationZoom = prepareSingleCalibrationZoom()
                        calManager.startCalibration()
                        showCalPanel = true
                        lastResultText = "校准倍率: ${"%.1f".format(calibrationZoom)}x"
                    }) { Text("没有，开始校准") }
                },
            )
        }

        // 100m 坐标输入对话框
        if (showCoordInputDialog) {
            AlertDialog(
                onDismissRequest = { showCoordInputDialog = false },
                title = { Text("输入100m坐标") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("请输入100m目标的坐标（1080x1920 坐标系）")
                        OutlinedTextField(
                            value = coordInputX,
                            onValueChange = { coordInputX = it },
                            label = { Text("X 坐标") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = coordInputY,
                            onValueChange = { coordInputY = it },
                            label = { Text("Y 坐标") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val x = coordInputX.toDoubleOrNull()
                        val y = coordInputY.toDoubleOrNull()
                        if (x != null && y != null) {
                            val point = LaserPoint(x.coerceIn(0.0, 1079.0), y.coerceIn(0.0, 1919.0))
                            val targetZoom = postCalibrationZoomFor(selectedLens)
                            anchorCenter1x = point
                            lastResultText = "已锁定: (${point.x.toInt()}, ${point.y.toInt()})"
                            currentEquivRatio = targetZoom
                            anchorZoom = targetZoom
                            anchorActive = true
                            updateCenterFromAnchorForZoom(targetZoom)
                            showCoordInputDialog = false
                        }
                    }) { Text("确认") }
                },
                dismissButton = {
                    TextButton(onClick = { showCoordInputDialog = false }) { Text("取消") }
                },
            )
        }

        if (showStabilizationDialog) {
            val supportedEisModes = cameraController.getAvailableVideoStabilizationModes()
            AlertDialog(
                onDismissRequest = { showStabilizationDialog = false },
                title = { Text("防抖") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("OIS：${if (oisEnabled) "ON" else "OFF"}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { if (!oisEnabled) toggleOis() }) { Text("OIS ON") }
                            TextButton(onClick = { if (oisEnabled) toggleOis() }) { Text("OIS OFF") }
                        }
                        Divider(color = Color.White.copy(alpha = 0.16f))
                        Text("EIS 实验模式：${Camera2Controller.videoStabilizationModeName(eisMode)}")
                        Camera2Controller.videoStabilizationModeChoices().forEach { mode ->
                            val supported = mode in supportedEisModes
                            val selected = mode == eisMode
                            TextButton(
                                enabled = supported,
                                onClick = { applyEisMode(mode) },
                            ) {
                                Text(
                                    text = buildString {
                                        append(Camera2Controller.videoStabilizationModeName(mode))
                                        if (selected) append(" 当前")
                                        if (!supported) append(" 不支持")
                                    },
                                    color = when {
                                        selected -> Color(0xFFFF7043)
                                        supported -> Color.White
                                        else -> Color.White.copy(alpha = 0.38f)
                                    },
                                )
                            }
                        }
                        if (videoStabilizationText.isNotBlank()) {
                            Text(
                                text = videoStabilizationText,
                                color = Color.White.copy(alpha = 0.70f),
                                fontSize = 12.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showStabilizationDialog = false }) { Text("关闭") }
                },
            )
        }

        if (voiceDownloadDialogVisible) {
            val downloadState = voiceDownloadState
            AlertDialog(
                onDismissRequest = {
                    if (downloadState?.inProgress != true) {
                        voiceDownloadDialogVisible = false
                    }
                },
                title = {
                    Text(if (downloadState?.fallbackActive == true) "第三方语音包" else "中文语音包")
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(downloadState?.message ?: "需要下载中文语音包后才能离线持续识别。")
                        if (downloadState?.inProgress == true) {
                            val progress = downloadState.progressPercent
                            if (progress != null) {
                                LinearProgressIndicator(
                                    progress = { progress / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("$progress%", fontSize = 12.sp)
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text("等待系统返回下载进度...", fontSize = 12.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    when {
                        downloadState?.inProgress == true -> {
                            TextButton(onClick = { }) { Text("下载中") }
                        }
                        downloadState?.downloadAvailable == false -> {
                            TextButton(onClick = {
                                voiceDownloadDialogVisible = false
                            }) { Text("知道了") }
                        }
                        downloadState?.progressPercent == 100 -> {
                            TextButton(onClick = {
                                voiceDownloadDialogVisible = false
                                voiceRecognizer.start()
                            }) { Text("开始识别") }
                        }
                        else -> {
                            TextButton(onClick = {
                                if (downloadState?.fallbackActive == true) {
                                    voiceRecognizer.requestThirdPartyVoiceModelDownload()
                                } else {
                                    voiceRecognizer.requestChineseLanguageModelDownload()
                                }
                            }) {
                                Text(if (downloadState?.fallbackActive == true) "下载第三方包" else "下载")
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        voiceDownloadDialogVisible = false
                        if (downloadState?.inProgress != true && downloadState?.fallbackActive != true) {
                            voiceRecognizer.stop()
                        }
                    }) {
                        Text(
                            when {
                                downloadState?.inProgress == true -> "隐藏"
                                downloadState?.fallbackActive == true -> "关闭"
                                else -> "取消"
                            }
                        )
                    }
                },
            )
        }

        // 识别区域调节对话框
        if (showAreaDialog) {
            AlertDialog(
                onDismissRequest = { showAreaDialog = false },
                title = { Text("识别区域大小") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("调节识别区域的宽度和高度（px）")
                        OutlinedTextField(
                            value = areaInputW,
                            onValueChange = { areaInputW = it },
                            label = { Text("宽度 (px)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = areaInputH,
                            onValueChange = { areaInputH = it },
                            label = { Text("高度 (px)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val w = areaInputW.toFloatOrNull()
                        val h = areaInputH.toFloatOrNull()
                        if (w != null && w > 0f && h != null && h > 0f) {
                            areaWidthPx = w.coerceIn(50f, 1080f)
                            areaHeightPx = h.coerceIn(50f, 1920f)
                            showAreaDialog = false
                        }
                    }) { Text("确认") }
                },
                dismissButton = {
                    TextButton(onClick = { showAreaDialog = false }) { Text("取消") }
                },
            )
        }

        // 判定点管理对话框
        if (showPointDialog) {
            AlertDialog(
                onDismissRequest = { showPointDialog = false },
                title = { Text("判定点管理") },
                text = {
                    Column(
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("设置检测框上的判定点（百分比坐标）")
                        Text("0%=左/上边缘, 50%=中心, 100%=右/下边缘", fontSize = 11.sp, color = Color.Gray)
                        // 判定模式切换
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("判定模式:", fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { requireAllPoints = false },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (!requireAllPoints) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.15f),
                                    ),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                    modifier = Modifier.weight(1f).height(36.dp),
                                ) { Text("任一点命中", fontSize = 12.sp, color = Color.White, maxLines = 1) }
                                Button(
                                    onClick = { requireAllPoints = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (requireAllPoints) Color(0xFFFF9800) else Color.White.copy(alpha = 0.15f),
                                    ),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                    modifier = Modifier.weight(1f).height(36.dp),
                                ) { Text("所有点命中", fontSize = 12.sp, color = Color.White, maxLines = 1) }
                            }
                        }
                        // 当前判定点列表
                        checkPoints.forEachIndexed { idx, (pw, ph) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "${idx + 1}. (${(pw * 100).toInt()}%, ${(ph * 100).toInt()}%)",
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                if (checkPoints.size > 1) {
                                    TextButton(onClick = {
                                        checkPoints = checkPoints.toMutableList().apply { removeAt(idx) }
                                    }) { Text("删除", color = Color(0xFFFF5722), fontSize = 12.sp) }
                                }
                            }
                        }
                        Divider()
                        // 添加新判定点
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = pointInputW,
                                onValueChange = { pointInputW = it },
                                label = { Text("W%") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = pointInputH,
                                onValueChange = { pointInputH = it },
                                label = { Text("H%") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val w = pointInputW.toFloatOrNull()
                        val h = pointInputH.toFloatOrNull()
                        if (w != null && h != null) {
                            val pw = (w / 100f).coerceIn(0f, 1f)
                            val ph = (h / 100f).coerceIn(0f, 1f)
                            if (checkPoints.none { it.first == pw && it.second == ph }) {
                                checkPoints = checkPoints + (pw to ph)
                            }
                            pointInputW = "50"
                            pointInputH = "50"
                        }
                    }) { Text("添加") }
                },
                dismissButton = {
                    TextButton(onClick = { showPointDialog = false }) { Text("关闭") }
                },
            )
        }
    }
}

@Composable
private fun LaserComparePanel(
    enabled: Boolean,
    statusText: String,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.58f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "绿光新旧算法对比",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "识别开关",
                    color = Color.White.copy(alpha = 0.86f),
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.height(32.dp),
                )
            }
        }
        if (statusText.isNotBlank()) {
            Text(
                text = statusText,
                color = Color(0xFFB2FF59),
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TopActionButton(
    text: String,
    active: Boolean = false,
    enabled: Boolean = true,
    accent: Color = Color(0xFF4CAF50),
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) accent else Color.White.copy(alpha = 0.12f),
            disabledContainerColor = Color.White.copy(alpha = 0.06f),
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier.height(36.dp).widthIn(min = 56.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ToolMenuItem(
    text: String,
    active: Boolean = false,
    enabled: Boolean = true,
    accent: Color = Color(0xFF4CAF50),
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                color = when {
                    !enabled -> Color.White.copy(alpha = 0.38f)
                    active -> accent
                    else -> Color.White.copy(alpha = 0.92f)
                },
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun StatusHud(
    statusText: String,
    modelText: String,
    statText: String,
    lensText: String,
    zoomText: String,
    anchorText: String,
    modifier: Modifier = Modifier,
) {
    val lines = listOf(statusText, lensText, modelText, statText, zoomText, anchorText).filter { it.isNotBlank() }
    if (lines.isEmpty()) return

    Column(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        lines.take(5).forEachIndexed { index, text ->
            Text(
                text = text,
                color = when {
                    index == 0 -> Color.White.copy(alpha = 0.9f)
                    text.startsWith("画面镜头") -> Color(0xFFA5D6A7)
                    text.startsWith("模型") -> Color(0xFFFF7043)
                    text.startsWith("统计") -> Color(0xFF64B5F6)
                    text.startsWith("zoom") -> Color(0xFF90CAF9)
                    text.startsWith("锁定") -> Color(0xFF81C784)
                    else -> Color(0xFFFFB74D)
                },
                fontSize = if (index == 0) 11.sp else 10.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CrosshairOverlay(modifier: Modifier = Modifier) {
    val color = Color.White; val lineLen = 40.dp; val lineW = 1.5.dp; val r = 8.dp
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val ll = lineLen.toPx(); val lw = lineW.toPx(); val rr = r.toPx(); val gap = rr + lw
        drawLine(color, androidx.compose.ui.geometry.Offset(cx - gap - ll, cy), androidx.compose.ui.geometry.Offset(cx - gap, cy), lw)
        drawLine(color, androidx.compose.ui.geometry.Offset(cx + gap, cy), androidx.compose.ui.geometry.Offset(cx + gap + ll, cy), lw)
        drawLine(color, androidx.compose.ui.geometry.Offset(cx, cy - gap - ll), androidx.compose.ui.geometry.Offset(cx, cy - gap), lw)
        drawLine(color, androidx.compose.ui.geometry.Offset(cx, cy + gap), androidx.compose.ui.geometry.Offset(cx, cy + gap + ll), lw)
        drawCircle(color, rr, androidx.compose.ui.geometry.Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(lw))
    }
}
