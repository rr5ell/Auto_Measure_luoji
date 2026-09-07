package com.example.auto_measure_luoji.feature.camera.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
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
import com.example.auto_measure_luoji.feature.camera.domain.GyroPreviewStabilizer
import com.example.auto_measure_luoji.feature.camera.domain.SelectLensUseCase
import com.example.auto_measure_luoji.feature.detection.domain.YoloDetector
import com.example.auto_measure_luoji.feature.detection.domain.YoloPostprocessOptions
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val WIDE_CALIBRATION_ZOOM = 5f
private const val NORMAL_PREVIEW_MAX_ZOOM = 24f
private const val NORMAL_MIN_ZOOM_OFFSET = 0.1f
private const val CALIBRATION_EXPOSURE_EV = -2.0f
private const val CAMERA_SCREEN_PREFS = "camera_screen_settings"
private const val KEY_YOLO_TOP_DETECTION_ENABLED = "yolo_top_detection_enabled"
private const val KEY_YOLO_TOP_CONFIDENCE_THRESHOLD = "yolo_top_confidence_threshold"
private const val KEY_YOLO_FRAME_SOURCE = "yolo_frame_source"
private const val KEY_UI_ZOOM_ONLY_ENABLED = "ui_zoom_only_enabled"
private const val KEY_GYRO_PREVIEW_CONFIG_VERSION = "gyro_preview_config_version"
private const val KEY_GYRO_PREVIEW_STABILIZATION_ENABLED = "gyro_preview_stabilization_enabled"
private const val KEY_GYRO_PREVIEW_STRENGTH = "gyro_preview_strength"
private const val KEY_GYRO_PREVIEW_PATH_FOLLOW = "gyro_preview_path_follow"
private const val KEY_GYRO_PREVIEW_OUTPUT_ALPHA = "gyro_preview_output_alpha"
private const val KEY_GYRO_PREVIEW_OUTPUT_RATE_LIMIT = "gyro_preview_output_rate_limit"
private const val KEY_GYRO_PREVIEW_OUTPUT_ACCELERATION_LIMIT = "gyro_preview_output_acceleration_limit"
private const val KEY_GYRO_PREVIEW_CROP_SCALE = "gyro_preview_crop_scale"
private const val KEY_GYRO_PREVIEW_MAX_SHIFT = "gyro_preview_max_shift"
private const val KEY_GYRO_PREVIEW_SOFT_BOUNDARY_ENABLED = "gyro_preview_soft_boundary_enabled"
private const val KEY_GYRO_PREVIEW_SOFT_BOUNDARY_START_RATIO = "gyro_preview_soft_boundary_start_ratio"
private const val KEY_GYRO_PREVIEW_SOFT_BOUNDARY_STRENGTH = "gyro_preview_soft_boundary_strength"
private const val KEY_GYRO_PREVIEW_RE_AIM_ENABLED = "gyro_preview_re_aim_enabled"
private const val KEY_GYRO_PREVIEW_RE_AIM_RPS_THRESHOLD = "gyro_preview_re_aim_rps_threshold"
private const val KEY_GYRO_PREVIEW_RE_AIM_RESIDUAL_ENTER_RATIO =
    "gyro_preview_re_aim_residual_enter_ratio"
private const val KEY_GYRO_PREVIEW_RE_AIM_RESIDUAL_EXIT_RATIO =
    "gyro_preview_re_aim_residual_exit_ratio"
private const val KEY_GYRO_PREVIEW_RE_AIM_STILL_MS = "gyro_preview_re_aim_still_ms"
private const val KEY_GYRO_PREVIEW_RE_AIM_PATH_FOLLOW = "gyro_preview_re_aim_path_follow"
private const val KEY_GYRO_PREVIEW_RE_AIM_BLEND_IN = "gyro_preview_re_aim_blend_in"
private const val KEY_GYRO_PREVIEW_RE_AIM_BLEND_OUT = "gyro_preview_re_aim_blend_out"
private const val KEY_GYRO_PREVIEW_DEADBAND_PX = "gyro_preview_deadband_px"
private const val KEY_GYRO_PREVIEW_HORIZONTAL_GAIN = "gyro_preview_horizontal_gain"
private const val KEY_GYRO_PREVIEW_VERTICAL_GAIN = "gyro_preview_vertical_gain"
private const val KEY_GYRO_PREVIEW_AIM_STABILIZATION_ENABLED = "gyro_preview_aim_stabilization_enabled"
private const val KEY_GYRO_PREVIEW_AIM_RPS_THRESHOLD = "gyro_preview_aim_rps_threshold"
private const val KEY_GYRO_PREVIEW_AIM_STRENGTH_MULTIPLIER = "gyro_preview_aim_strength_multiplier"
private const val KEY_GYRO_PREVIEW_AIM_PATH_FOLLOW = "gyro_preview_aim_path_follow"
private const val KEY_GYRO_PREVIEW_AIM_DEADBAND_PX = "gyro_preview_aim_deadband_px"
private const val KEY_GYRO_PREVIEW_AIM_OUTPUT_RATE_LIMIT = "gyro_preview_aim_output_rate_limit"
private const val KEY_GYRO_PREVIEW_AIM_OUTPUT_ACCELERATION_LIMIT =
    "gyro_preview_aim_output_acceleration_limit"
private const val KEY_GYRO_PREVIEW_RESIDUAL_RELEASE_RPS_THRESHOLD =
    "gyro_preview_residual_release_rps_threshold"
private const val KEY_GYRO_PREVIEW_ZOOM_COMPENSATION_ENABLED = "gyro_preview_zoom_compensation_enabled"
private const val KEY_GYRO_PREVIEW_ZOOM_COMPENSATION_STRENGTH = "gyro_preview_zoom_compensation_strength"
private const val KEY_GYRO_PREVIEW_MAX_COMPENSATION_ZOOM = "gyro_preview_max_compensation_zoom"
private const val KEY_GYRO_PREVIEW_FRAME_SYNC_ENABLED = "gyro_preview_frame_sync_enabled"
private const val KEY_GYRO_PREVIEW_FRAME_TIMESTAMP_OFFSET_MS = "gyro_preview_frame_timestamp_offset_ms"
private const val KEY_GYRO_PREVIEW_GL_RENDER_ENABLED = "gyro_preview_gl_render_enabled"
private const val KEY_GYRO_PREVIEW_DIAGNOSTICS_ENABLED = "gyro_preview_diagnostics_enabled"
private const val KEY_GYRO_PREVIEW_ROLL_COMPENSATION_ENABLED = "gyro_preview_roll_compensation_enabled"
private const val KEY_GYRO_PREVIEW_ROLL_STRENGTH = "gyro_preview_roll_strength"
private const val KEY_GYRO_PREVIEW_MAX_ROLL_DEGREES = "gyro_preview_max_roll_degrees"
private const val KEY_GYRO_PREVIEW_INVERT_ROLL = "gyro_preview_invert_roll"
private const val KEY_GYRO_PREVIEW_SWAP_AXES = "gyro_preview_swap_axes"
private const val KEY_GYRO_PREVIEW_INVERT_HORIZONTAL = "gyro_preview_invert_horizontal"
private const val KEY_GYRO_PREVIEW_INVERT_VERTICAL = "gyro_preview_invert_vertical"
private const val KEY_GYRO_STABILIZATION_CALIBRATION_ENABLED = "gyro_stabilization_calibration_enabled"
private const val KEY_GYRO_STABILIZATION_CALIBRATION_FINE_SAMPLE_ENABLED =
    "gyro_stabilization_calibration_fine_sample_enabled"
private const val GYRO_PREVIEW_STABILIZATION_SENSOR_DELAY_US = 5_000
private const val GYRO_PREVIEW_MATRIX_UPDATE_INTERVAL_MS = 16L
private const val GYRO_PREVIEW_OVERLAY_UPDATE_INTERVAL_MS = 33L
private const val GYRO_PREVIEW_HUD_UPDATE_INTERVAL_MS = 180L
private const val GYRO_PREVIEW_MODEL_LOG_INTERVAL_MS = 500L
private const val GYRO_STABILIZATION_CALIBRATION_INTERVAL_MS = 220L
private const val GYRO_STABILIZATION_CALIBRATION_SAMPLE_W = 180
private const val GYRO_STABILIZATION_CALIBRATION_FINE_SAMPLE_W = 540
private const val GYRO_STABILIZATION_CALIBRATION_TEMPLATE_SIZE = 48
private const val GYRO_STABILIZATION_CALIBRATION_FINE_TEMPLATE_SIZE = 96
private const val GYRO_STABILIZATION_CALIBRATION_SEARCH_RADIUS = 14
private const val GYRO_STABILIZATION_CALIBRATION_FINE_SEARCH_RADIUS = 24
private const val GYRO_STABILIZATION_CALIBRATION_FAST_MAX_SAMPLE_H = 360
private const val GYRO_STABILIZATION_CALIBRATION_FINE_MAX_SAMPLE_H = 1280
private const val GYRO_STABILIZATION_CALIBRATION_MIN_SCORE = 0.18f
private const val GYRO_STABILIZATION_CALIBRATION_HINT_MIN_SIGNAL_PX = 0.6f
private const val GYRO_STABILIZATION_CALIBRATION_HINT_WINDOW_MS = 1_200L
private const val GYRO_PREVIEW_CONFIG_VERSION = 19
private const val MIN_GYRO_PREVIEW_STRENGTH = 0.0f
private const val MAX_GYRO_PREVIEW_STRENGTH = 1.5f
private const val MIN_GYRO_PREVIEW_PATH_FOLLOW = 1.0f
private const val MAX_GYRO_PREVIEW_PATH_FOLLOW = 30.0f
private const val MIN_GYRO_PREVIEW_OUTPUT_ALPHA = 0.05f
private const val MAX_GYRO_PREVIEW_OUTPUT_ALPHA = 1.0f
private const val MIN_GYRO_PREVIEW_OUTPUT_RATE_LIMIT = 0.0f
private const val MAX_GYRO_PREVIEW_OUTPUT_RATE_LIMIT = 5_000.0f
private const val MIN_GYRO_PREVIEW_OUTPUT_ACCELERATION_LIMIT = 0.0f
private const val MAX_GYRO_PREVIEW_OUTPUT_ACCELERATION_LIMIT = 50_000.0f
private const val MIN_GYRO_PREVIEW_CROP_SCALE = 1.0f
private const val MAX_GYRO_PREVIEW_CROP_SCALE = 1.25f
private const val MIN_GYRO_PREVIEW_MAX_SHIFT = 0.0f
private const val MAX_GYRO_PREVIEW_MAX_SHIFT = 0.10f
private const val MIN_GYRO_PREVIEW_SOFT_BOUNDARY_START_RATIO = 0.05f
private const val MAX_GYRO_PREVIEW_SOFT_BOUNDARY_START_RATIO = 0.98f
private const val MIN_GYRO_PREVIEW_SOFT_BOUNDARY_STRENGTH = 0.0f
private const val MAX_GYRO_PREVIEW_SOFT_BOUNDARY_STRENGTH = 8.0f
private const val MIN_GYRO_PREVIEW_RE_AIM_RPS_THRESHOLD = 0.001f
private const val MAX_GYRO_PREVIEW_RE_AIM_RPS_THRESHOLD = 0.300f
private const val MIN_GYRO_PREVIEW_RE_AIM_RATIO = 0.0f
private const val MAX_GYRO_PREVIEW_RE_AIM_RATIO = 0.98f
private const val MIN_GYRO_PREVIEW_RE_AIM_STILL_MS = 0.0f
private const val MAX_GYRO_PREVIEW_RE_AIM_STILL_MS = 2_000.0f
private const val MIN_GYRO_PREVIEW_RE_AIM_PATH_FOLLOW = 0.2f
private const val MAX_GYRO_PREVIEW_RE_AIM_PATH_FOLLOW = 30.0f
private const val MIN_GYRO_PREVIEW_RE_AIM_BLEND_RATE = 0.0f
private const val MAX_GYRO_PREVIEW_RE_AIM_BLEND_RATE = 20.0f
private const val MIN_GYRO_PREVIEW_DEADBAND_PX = 0.0f
private const val MAX_GYRO_PREVIEW_DEADBAND_PX = 5.0f
private const val MIN_GYRO_PREVIEW_AXIS_GAIN = 0.0f
private const val MAX_GYRO_PREVIEW_AXIS_GAIN = 4.0f
private const val MIN_GYRO_PREVIEW_AIM_RPS_THRESHOLD = 0.001f
private const val MAX_GYRO_PREVIEW_AIM_RPS_THRESHOLD = 0.300f
private const val MIN_GYRO_PREVIEW_AIM_STRENGTH_MULTIPLIER = 1.0f
private const val MAX_GYRO_PREVIEW_AIM_STRENGTH_MULTIPLIER = 3.0f
private const val MIN_GYRO_PREVIEW_AIM_PATH_FOLLOW = 0.2f
private const val MAX_GYRO_PREVIEW_AIM_PATH_FOLLOW = 10.0f
private const val MIN_GYRO_PREVIEW_AIM_DEADBAND_PX = 0.0f
private const val MAX_GYRO_PREVIEW_AIM_DEADBAND_PX = 2.0f
private const val MIN_GYRO_PREVIEW_AIM_OUTPUT_RATE_LIMIT = 0.0f
private const val MAX_GYRO_PREVIEW_AIM_OUTPUT_RATE_LIMIT = 5_000.0f
private const val MIN_GYRO_PREVIEW_AIM_OUTPUT_ACCELERATION_LIMIT = 0.0f
private const val MAX_GYRO_PREVIEW_AIM_OUTPUT_ACCELERATION_LIMIT = 50_000.0f
private const val MIN_GYRO_PREVIEW_RESIDUAL_RELEASE_RPS_THRESHOLD = 0.001f
private const val MAX_GYRO_PREVIEW_RESIDUAL_RELEASE_RPS_THRESHOLD = 0.300f
private const val MIN_GYRO_PREVIEW_ZOOM_COMPENSATION_STRENGTH = 0.0f
private const val MAX_GYRO_PREVIEW_ZOOM_COMPENSATION_STRENGTH = 2.0f
private const val MIN_GYRO_PREVIEW_MAX_COMPENSATION_ZOOM = 1.0f
private const val MAX_GYRO_PREVIEW_MAX_COMPENSATION_ZOOM = 80.0f
private const val MIN_GYRO_PREVIEW_FRAME_TIMESTAMP_OFFSET_MS = -80.0f
private const val MAX_GYRO_PREVIEW_FRAME_TIMESTAMP_OFFSET_MS = 80.0f
private const val MIN_GYRO_PREVIEW_ROLL_STRENGTH = 0.0f
private const val MAX_GYRO_PREVIEW_ROLL_STRENGTH = 1.5f
private const val MIN_GYRO_PREVIEW_MAX_ROLL_DEGREES = 0.0f
private const val MAX_GYRO_PREVIEW_MAX_ROLL_DEGREES = 5.0f
private val DEFAULT_GYRO_PREVIEW_CONFIG = GyroPreviewStabilizer.Config()

/**
 * 相机预览屏幕
 *
 * 变换方案：
 * - 默认倍率放大交给 Camera2，校准点居中仍应用在 FrameLayout 容器上。
 * - 纯 UI 放大实验模式使用 TextureView.setTransform(Matrix) 在显示层做 GPU 矩阵变换。
 * - 检测框 overlay 复用同一组 scale/translation，保持和预览画面一致。
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

private enum class StabilizationCalibrationSampleMode(
    val label: String,
    val sampleWidth: Int,
    val templateSize: Int,
    val searchRadius: Int,
    val maxSampleHeight: Int,
) {
    FAST(
        label = "快速",
        sampleWidth = GYRO_STABILIZATION_CALIBRATION_SAMPLE_W,
        templateSize = GYRO_STABILIZATION_CALIBRATION_TEMPLATE_SIZE,
        searchRadius = GYRO_STABILIZATION_CALIBRATION_SEARCH_RADIUS,
        maxSampleHeight = GYRO_STABILIZATION_CALIBRATION_FAST_MAX_SAMPLE_H,
    ),
    FINE(
        label = "精细",
        sampleWidth = GYRO_STABILIZATION_CALIBRATION_FINE_SAMPLE_W,
        templateSize = GYRO_STABILIZATION_CALIBRATION_FINE_TEMPLATE_SIZE,
        searchRadius = GYRO_STABILIZATION_CALIBRATION_FINE_SEARCH_RADIUS,
        maxSampleHeight = GYRO_STABILIZATION_CALIBRATION_FINE_MAX_SAMPLE_H,
    ),
}

private data class GyroLensProjection(
    val focalPxX: Float = 0f,
    val focalPxY: Float = 0f,
    val fovXDegrees: Float = 0f,
    val fovYDegrees: Float = 0f,
)

private data class GyroPreviewParameterPreset(
    val label: String,
    val strength: Float,
    val pathFollowPerSecond: Float,
    val outputLowPassAlpha: Float,
    val outputRateLimitPxPerSecond: Float = DEFAULT_GYRO_PREVIEW_CONFIG.outputRateLimitPxPerSecond,
    val outputAccelerationLimitPxPerSecond2: Float =
        DEFAULT_GYRO_PREVIEW_CONFIG.outputAccelerationLimitPxPerSecond2,
    val cropScale: Float,
    val maxShiftRatio: Float,
    val softBoundaryEnabled: Boolean = DEFAULT_GYRO_PREVIEW_CONFIG.softBoundaryEnabled,
    val softBoundaryStartRatio: Float = DEFAULT_GYRO_PREVIEW_CONFIG.softBoundaryStartRatio,
    val softBoundaryStrength: Float = DEFAULT_GYRO_PREVIEW_CONFIG.softBoundaryStrength,
    val reAimEnabled: Boolean = DEFAULT_GYRO_PREVIEW_CONFIG.reAimEnabled,
    val reAimRpsThreshold: Float = DEFAULT_GYRO_PREVIEW_CONFIG.reAimRpsThreshold,
    val reAimResidualEnterRatio: Float = DEFAULT_GYRO_PREVIEW_CONFIG.reAimResidualEnterRatio,
    val reAimResidualExitRatio: Float = DEFAULT_GYRO_PREVIEW_CONFIG.reAimResidualExitRatio,
    val reAimStillMs: Float = DEFAULT_GYRO_PREVIEW_CONFIG.reAimStillMs,
    val reAimPathFollowPerSecond: Float = DEFAULT_GYRO_PREVIEW_CONFIG.reAimPathFollowPerSecond,
    val reAimBlendInPerSecond: Float = DEFAULT_GYRO_PREVIEW_CONFIG.reAimBlendInPerSecond,
    val reAimBlendOutPerSecond: Float = DEFAULT_GYRO_PREVIEW_CONFIG.reAimBlendOutPerSecond,
    val deadbandPx: Float,
    val horizontalGain: Float,
    val verticalGain: Float,
    val aimStabilizationEnabled: Boolean,
    val aimRpsThreshold: Float,
    val aimStrengthMultiplier: Float,
    val aimPathFollowPerSecond: Float,
    val aimDeadbandPx: Float,
    val aimOutputRateLimitPxPerSecond: Float = DEFAULT_GYRO_PREVIEW_CONFIG.aimOutputRateLimitPxPerSecond,
    val aimOutputAccelerationLimitPxPerSecond2: Float =
        DEFAULT_GYRO_PREVIEW_CONFIG.aimOutputAccelerationLimitPxPerSecond2,
    val residualReleaseRpsThreshold: Float = DEFAULT_GYRO_PREVIEW_CONFIG.residualReleaseRpsThreshold,
    val zoomCompensationEnabled: Boolean,
    val zoomCompensationStrength: Float,
    val maxCompensationZoom: Float,
    val frameSyncEnabled: Boolean = false,
    val frameTimestampOffsetMs: Float = 0f,
    val rollCompensationEnabled: Boolean,
    val rollStrength: Float,
    val maxRollDegrees: Float,
    val invertRoll: Boolean,
)

private val GYRO_PREVIEW_PARAMETER_PRESETS = listOf(
    GyroPreviewParameterPreset(
        label = "帧同步",
        strength = 0.62f,
        pathFollowPerSecond = 7.0f,
        outputLowPassAlpha = 0.86f,
        cropScale = 1.18f,
        maxShiftRatio = 0.060f,
        deadbandPx = 0.0f,
        horizontalGain = DEFAULT_GYRO_PREVIEW_CONFIG.horizontalGain,
        verticalGain = DEFAULT_GYRO_PREVIEW_CONFIG.verticalGain,
        aimStabilizationEnabled = true,
        aimRpsThreshold = 0.060f,
        aimStrengthMultiplier = 1.6f,
        aimPathFollowPerSecond = 1.2f,
        aimDeadbandPx = 0.0f,
        zoomCompensationEnabled = DEFAULT_GYRO_PREVIEW_CONFIG.zoomCompensationEnabled,
        zoomCompensationStrength = DEFAULT_GYRO_PREVIEW_CONFIG.zoomCompensationStrength,
        maxCompensationZoom = DEFAULT_GYRO_PREVIEW_CONFIG.maxCompensationZoom,
        frameSyncEnabled = true,
        rollCompensationEnabled = DEFAULT_GYRO_PREVIEW_CONFIG.rollCompensationEnabled,
        rollStrength = DEFAULT_GYRO_PREVIEW_CONFIG.rollStrength,
        maxRollDegrees = DEFAULT_GYRO_PREVIEW_CONFIG.maxRollDegrees,
        invertRoll = DEFAULT_GYRO_PREVIEW_CONFIG.invertRoll,
    ),
    GyroPreviewParameterPreset(
        label = "瞄准稳定",
        strength = 0.54f,
        pathFollowPerSecond = 9.0f,
        outputLowPassAlpha = 0.72f,
        cropScale = 1.14f,
        maxShiftRatio = 0.040f,
        deadbandPx = 0.15f,
        horizontalGain = DEFAULT_GYRO_PREVIEW_CONFIG.horizontalGain,
        verticalGain = DEFAULT_GYRO_PREVIEW_CONFIG.verticalGain,
        aimStabilizationEnabled = true,
        aimRpsThreshold = 0.060f,
        aimStrengthMultiplier = 1.8f,
        aimPathFollowPerSecond = 1.8f,
        aimDeadbandPx = 0.0f,
        zoomCompensationEnabled = DEFAULT_GYRO_PREVIEW_CONFIG.zoomCompensationEnabled,
        zoomCompensationStrength = DEFAULT_GYRO_PREVIEW_CONFIG.zoomCompensationStrength,
        maxCompensationZoom = DEFAULT_GYRO_PREVIEW_CONFIG.maxCompensationZoom,
        rollCompensationEnabled = DEFAULT_GYRO_PREVIEW_CONFIG.rollCompensationEnabled,
        rollStrength = DEFAULT_GYRO_PREVIEW_CONFIG.rollStrength,
        maxRollDegrees = DEFAULT_GYRO_PREVIEW_CONFIG.maxRollDegrees,
        invertRoll = DEFAULT_GYRO_PREVIEW_CONFIG.invertRoll,
    ),
    GyroPreviewParameterPreset(
        label = "强稳",
        strength = 0.42f,
        pathFollowPerSecond = 12.0f,
        outputLowPassAlpha = 0.25f,
        cropScale = 1.12f,
        maxShiftRatio = 0.025f,
        deadbandPx = 0.50f,
        horizontalGain = DEFAULT_GYRO_PREVIEW_CONFIG.horizontalGain,
        verticalGain = DEFAULT_GYRO_PREVIEW_CONFIG.verticalGain,
        aimStabilizationEnabled = true,
        aimRpsThreshold = 0.050f,
        aimStrengthMultiplier = 1.6f,
        aimPathFollowPerSecond = 2.0f,
        aimDeadbandPx = 0.0f,
        zoomCompensationEnabled = DEFAULT_GYRO_PREVIEW_CONFIG.zoomCompensationEnabled,
        zoomCompensationStrength = DEFAULT_GYRO_PREVIEW_CONFIG.zoomCompensationStrength,
        maxCompensationZoom = DEFAULT_GYRO_PREVIEW_CONFIG.maxCompensationZoom,
        rollCompensationEnabled = DEFAULT_GYRO_PREVIEW_CONFIG.rollCompensationEnabled,
        rollStrength = DEFAULT_GYRO_PREVIEW_CONFIG.rollStrength,
        maxRollDegrees = DEFAULT_GYRO_PREVIEW_CONFIG.maxRollDegrees,
        invertRoll = DEFAULT_GYRO_PREVIEW_CONFIG.invertRoll,
    ),
    GyroPreviewParameterPreset(
        label = "均衡",
        strength = DEFAULT_GYRO_PREVIEW_CONFIG.strength,
        pathFollowPerSecond = DEFAULT_GYRO_PREVIEW_CONFIG.pathFollowPerSecond,
        outputLowPassAlpha = DEFAULT_GYRO_PREVIEW_CONFIG.outputLowPassAlpha,
        cropScale = DEFAULT_GYRO_PREVIEW_CONFIG.cropScale,
        maxShiftRatio = DEFAULT_GYRO_PREVIEW_CONFIG.maxShiftRatio,
        deadbandPx = DEFAULT_GYRO_PREVIEW_CONFIG.deadbandPx,
        horizontalGain = DEFAULT_GYRO_PREVIEW_CONFIG.horizontalGain,
        verticalGain = DEFAULT_GYRO_PREVIEW_CONFIG.verticalGain,
        aimStabilizationEnabled = DEFAULT_GYRO_PREVIEW_CONFIG.aimStabilizationEnabled,
        aimRpsThreshold = DEFAULT_GYRO_PREVIEW_CONFIG.aimRpsThreshold,
        aimStrengthMultiplier = DEFAULT_GYRO_PREVIEW_CONFIG.aimStrengthMultiplier,
        aimPathFollowPerSecond = DEFAULT_GYRO_PREVIEW_CONFIG.aimPathFollowPerSecond,
        aimDeadbandPx = DEFAULT_GYRO_PREVIEW_CONFIG.aimDeadbandPx,
        zoomCompensationEnabled = DEFAULT_GYRO_PREVIEW_CONFIG.zoomCompensationEnabled,
        zoomCompensationStrength = DEFAULT_GYRO_PREVIEW_CONFIG.zoomCompensationStrength,
        maxCompensationZoom = DEFAULT_GYRO_PREVIEW_CONFIG.maxCompensationZoom,
        frameSyncEnabled = DEFAULT_GYRO_PREVIEW_CONFIG.frameSyncEnabled,
        rollCompensationEnabled = DEFAULT_GYRO_PREVIEW_CONFIG.rollCompensationEnabled,
        rollStrength = DEFAULT_GYRO_PREVIEW_CONFIG.rollStrength,
        maxRollDegrees = DEFAULT_GYRO_PREVIEW_CONFIG.maxRollDegrees,
        invertRoll = DEFAULT_GYRO_PREVIEW_CONFIG.invertRoll,
    ),
    GyroPreviewParameterPreset(
        label = "快响应",
        strength = 0.34f,
        pathFollowPerSecond = 22.0f,
        outputLowPassAlpha = 0.75f,
        cropScale = 1.08f,
        maxShiftRatio = 0.018f,
        deadbandPx = 0.80f,
        horizontalGain = DEFAULT_GYRO_PREVIEW_CONFIG.horizontalGain,
        verticalGain = DEFAULT_GYRO_PREVIEW_CONFIG.verticalGain,
        aimStabilizationEnabled = false,
        aimRpsThreshold = DEFAULT_GYRO_PREVIEW_CONFIG.aimRpsThreshold,
        aimStrengthMultiplier = DEFAULT_GYRO_PREVIEW_CONFIG.aimStrengthMultiplier,
        aimPathFollowPerSecond = DEFAULT_GYRO_PREVIEW_CONFIG.aimPathFollowPerSecond,
        aimDeadbandPx = DEFAULT_GYRO_PREVIEW_CONFIG.aimDeadbandPx,
        zoomCompensationEnabled = DEFAULT_GYRO_PREVIEW_CONFIG.zoomCompensationEnabled,
        zoomCompensationStrength = DEFAULT_GYRO_PREVIEW_CONFIG.zoomCompensationStrength,
        maxCompensationZoom = DEFAULT_GYRO_PREVIEW_CONFIG.maxCompensationZoom,
        rollCompensationEnabled = DEFAULT_GYRO_PREVIEW_CONFIG.rollCompensationEnabled,
        rollStrength = DEFAULT_GYRO_PREVIEW_CONFIG.rollStrength,
        maxRollDegrees = DEFAULT_GYRO_PREVIEW_CONFIG.maxRollDegrees,
        invertRoll = DEFAULT_GYRO_PREVIEW_CONFIG.invertRoll,
    ),
    GyroPreviewParameterPreset(
        label = "高倍强补偿",
        strength = 1.05f,
        pathFollowPerSecond = 6.0f,
        outputLowPassAlpha = 0.82f,
        cropScale = 1.20f,
        maxShiftRatio = 0.080f,
        deadbandPx = 0.05f,
        horizontalGain = DEFAULT_GYRO_PREVIEW_CONFIG.horizontalGain,
        verticalGain = DEFAULT_GYRO_PREVIEW_CONFIG.verticalGain,
        aimStabilizationEnabled = true,
        aimRpsThreshold = 0.035f,
        aimStrengthMultiplier = 1.4f,
        aimPathFollowPerSecond = 0.8f,
        aimDeadbandPx = 0.0f,
        zoomCompensationEnabled = DEFAULT_GYRO_PREVIEW_CONFIG.zoomCompensationEnabled,
        zoomCompensationStrength = 1.0f,
        maxCompensationZoom = 30.0f,
        rollCompensationEnabled = DEFAULT_GYRO_PREVIEW_CONFIG.rollCompensationEnabled,
        rollStrength = DEFAULT_GYRO_PREVIEW_CONFIG.rollStrength,
        maxRollDegrees = DEFAULT_GYRO_PREVIEW_CONFIG.maxRollDegrees,
        invertRoll = DEFAULT_GYRO_PREVIEW_CONFIG.invertRoll,
    ),
    GyroPreviewParameterPreset(
        label = "10倍长焦",
        strength = 0.36f,
        pathFollowPerSecond = 20.0f,
        outputLowPassAlpha = 0.55f,
        cropScale = 1.12f,
        maxShiftRatio = 0.018f,
        deadbandPx = 0.70f,
        horizontalGain = DEFAULT_GYRO_PREVIEW_CONFIG.horizontalGain,
        verticalGain = DEFAULT_GYRO_PREVIEW_CONFIG.verticalGain,
        aimStabilizationEnabled = true,
        aimRpsThreshold = 0.060f,
        aimStrengthMultiplier = 1.6f,
        aimPathFollowPerSecond = 2.5f,
        aimDeadbandPx = 0.0f,
        zoomCompensationEnabled = DEFAULT_GYRO_PREVIEW_CONFIG.zoomCompensationEnabled,
        zoomCompensationStrength = DEFAULT_GYRO_PREVIEW_CONFIG.zoomCompensationStrength,
        maxCompensationZoom = DEFAULT_GYRO_PREVIEW_CONFIG.maxCompensationZoom,
        rollCompensationEnabled = DEFAULT_GYRO_PREVIEW_CONFIG.rollCompensationEnabled,
        rollStrength = DEFAULT_GYRO_PREVIEW_CONFIG.rollStrength,
        maxRollDegrees = DEFAULT_GYRO_PREVIEW_CONFIG.maxRollDegrees,
        invertRoll = DEFAULT_GYRO_PREVIEW_CONFIG.invertRoll,
    ),
)

private data class LaserFrameCapture(
    val normalizedBitmap: Bitmap,
    val pngBytes: ByteArray,
    val sourceLabel: String,
)

private data class StabilizationCalibrationFrame(
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val viewWidth: Int,
    val viewHeight: Int,
    val sampleModeLabel: String,
    val templateSize: Int,
    val searchRadius: Int,
    val luma: IntArray,
    val captureCostMs: Long,
    val lumaCostMs: Long,
    val frameBuildCostMs: Long,
    val compensationX: Float,
    val compensationY: Float,
    val rotationDegrees: Float,
    val rps: Float,
    val effectiveZoom: Float,
)

private data class StabilizationCalibrationResult(
    val actualDxPx: Float,
    val actualDyPx: Float,
    val score: Float,
    val sampleDx: Int,
    val sampleDy: Int,
)

private data class StabilizationCalibrationHintSample(
    val timestampMs: Long,
    val xHint: String,
    val yHint: String,
)

@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cameraPrefs = remember(context.applicationContext) {
        context.applicationContext.getSharedPreferences(CAMERA_SCREEN_PREFS, Context.MODE_PRIVATE)
    }
    remember(cameraPrefs) {
        if (cameraPrefs.getInt(KEY_GYRO_PREVIEW_CONFIG_VERSION, 0) < GYRO_PREVIEW_CONFIG_VERSION) {
            cameraPrefs.edit()
                .putInt(KEY_GYRO_PREVIEW_CONFIG_VERSION, GYRO_PREVIEW_CONFIG_VERSION)
                .putFloat(KEY_GYRO_PREVIEW_STRENGTH, DEFAULT_GYRO_PREVIEW_CONFIG.strength)
                .putFloat(KEY_GYRO_PREVIEW_PATH_FOLLOW, DEFAULT_GYRO_PREVIEW_CONFIG.pathFollowPerSecond)
                .putFloat(KEY_GYRO_PREVIEW_OUTPUT_ALPHA, DEFAULT_GYRO_PREVIEW_CONFIG.outputLowPassAlpha)
                .putFloat(
                    KEY_GYRO_PREVIEW_OUTPUT_RATE_LIMIT,
                    DEFAULT_GYRO_PREVIEW_CONFIG.outputRateLimitPxPerSecond,
                )
                .putFloat(
                    KEY_GYRO_PREVIEW_OUTPUT_ACCELERATION_LIMIT,
                    DEFAULT_GYRO_PREVIEW_CONFIG.outputAccelerationLimitPxPerSecond2,
                )
                .putFloat(KEY_GYRO_PREVIEW_CROP_SCALE, DEFAULT_GYRO_PREVIEW_CONFIG.cropScale)
                .putFloat(KEY_GYRO_PREVIEW_MAX_SHIFT, DEFAULT_GYRO_PREVIEW_CONFIG.maxShiftRatio)
                .putBoolean(
                    KEY_GYRO_PREVIEW_SOFT_BOUNDARY_ENABLED,
                    DEFAULT_GYRO_PREVIEW_CONFIG.softBoundaryEnabled,
                )
                .putFloat(
                    KEY_GYRO_PREVIEW_SOFT_BOUNDARY_START_RATIO,
                    DEFAULT_GYRO_PREVIEW_CONFIG.softBoundaryStartRatio,
                )
                .putFloat(
                    KEY_GYRO_PREVIEW_SOFT_BOUNDARY_STRENGTH,
                    DEFAULT_GYRO_PREVIEW_CONFIG.softBoundaryStrength,
                )
                .putBoolean(KEY_GYRO_PREVIEW_RE_AIM_ENABLED, DEFAULT_GYRO_PREVIEW_CONFIG.reAimEnabled)
                .putFloat(KEY_GYRO_PREVIEW_RE_AIM_RPS_THRESHOLD, DEFAULT_GYRO_PREVIEW_CONFIG.reAimRpsThreshold)
                .putFloat(
                    KEY_GYRO_PREVIEW_RE_AIM_RESIDUAL_ENTER_RATIO,
                    DEFAULT_GYRO_PREVIEW_CONFIG.reAimResidualEnterRatio,
                )
                .putFloat(
                    KEY_GYRO_PREVIEW_RE_AIM_RESIDUAL_EXIT_RATIO,
                    DEFAULT_GYRO_PREVIEW_CONFIG.reAimResidualExitRatio,
                )
                .putFloat(KEY_GYRO_PREVIEW_RE_AIM_STILL_MS, DEFAULT_GYRO_PREVIEW_CONFIG.reAimStillMs)
                .putFloat(
                    KEY_GYRO_PREVIEW_RE_AIM_PATH_FOLLOW,
                    DEFAULT_GYRO_PREVIEW_CONFIG.reAimPathFollowPerSecond,
                )
                .putFloat(KEY_GYRO_PREVIEW_RE_AIM_BLEND_IN, DEFAULT_GYRO_PREVIEW_CONFIG.reAimBlendInPerSecond)
                .putFloat(KEY_GYRO_PREVIEW_RE_AIM_BLEND_OUT, DEFAULT_GYRO_PREVIEW_CONFIG.reAimBlendOutPerSecond)
                .putFloat(KEY_GYRO_PREVIEW_DEADBAND_PX, DEFAULT_GYRO_PREVIEW_CONFIG.deadbandPx)
                .putFloat(KEY_GYRO_PREVIEW_HORIZONTAL_GAIN, DEFAULT_GYRO_PREVIEW_CONFIG.horizontalGain)
                .putFloat(KEY_GYRO_PREVIEW_VERTICAL_GAIN, DEFAULT_GYRO_PREVIEW_CONFIG.verticalGain)
                .putBoolean(
                    KEY_GYRO_PREVIEW_AIM_STABILIZATION_ENABLED,
                    DEFAULT_GYRO_PREVIEW_CONFIG.aimStabilizationEnabled,
                )
                .putFloat(KEY_GYRO_PREVIEW_AIM_RPS_THRESHOLD, DEFAULT_GYRO_PREVIEW_CONFIG.aimRpsThreshold)
                .putFloat(
                    KEY_GYRO_PREVIEW_AIM_STRENGTH_MULTIPLIER,
                    DEFAULT_GYRO_PREVIEW_CONFIG.aimStrengthMultiplier,
                )
                .putFloat(KEY_GYRO_PREVIEW_AIM_PATH_FOLLOW, DEFAULT_GYRO_PREVIEW_CONFIG.aimPathFollowPerSecond)
                .putFloat(KEY_GYRO_PREVIEW_AIM_DEADBAND_PX, DEFAULT_GYRO_PREVIEW_CONFIG.aimDeadbandPx)
                .putFloat(
                    KEY_GYRO_PREVIEW_AIM_OUTPUT_RATE_LIMIT,
                    DEFAULT_GYRO_PREVIEW_CONFIG.aimOutputRateLimitPxPerSecond,
                )
                .putFloat(
                    KEY_GYRO_PREVIEW_AIM_OUTPUT_ACCELERATION_LIMIT,
                    DEFAULT_GYRO_PREVIEW_CONFIG.aimOutputAccelerationLimitPxPerSecond2,
                )
                .putFloat(
                    KEY_GYRO_PREVIEW_RESIDUAL_RELEASE_RPS_THRESHOLD,
                    DEFAULT_GYRO_PREVIEW_CONFIG.residualReleaseRpsThreshold,
                )
                .putBoolean(
                    KEY_GYRO_PREVIEW_ZOOM_COMPENSATION_ENABLED,
                    DEFAULT_GYRO_PREVIEW_CONFIG.zoomCompensationEnabled,
                )
                .putFloat(
                    KEY_GYRO_PREVIEW_ZOOM_COMPENSATION_STRENGTH,
                    DEFAULT_GYRO_PREVIEW_CONFIG.zoomCompensationStrength,
                )
                .putFloat(KEY_GYRO_PREVIEW_MAX_COMPENSATION_ZOOM, DEFAULT_GYRO_PREVIEW_CONFIG.maxCompensationZoom)
                .putBoolean(KEY_GYRO_PREVIEW_FRAME_SYNC_ENABLED, DEFAULT_GYRO_PREVIEW_CONFIG.frameSyncEnabled)
                .putFloat(
                    KEY_GYRO_PREVIEW_FRAME_TIMESTAMP_OFFSET_MS,
                    DEFAULT_GYRO_PREVIEW_CONFIG.frameTimestampOffsetMs,
                )
                .putBoolean(KEY_GYRO_PREVIEW_INVERT_VERTICAL, DEFAULT_GYRO_PREVIEW_CONFIG.invertVertical)
                .apply()
        }
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
    val gyroPreviewStabilizer = remember { GyroPreviewStabilizer() }
    val sensorManager = remember(context.applicationContext) {
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val gyroscopeSensor = remember(sensorManager) { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
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
    var uiZoomOnlyEnabled by remember {
        mutableStateOf(cameraPrefs.getBoolean(KEY_UI_ZOOM_ONLY_ENABLED, false))
    }
    var gyroPreviewStabilizationEnabled by remember {
        mutableStateOf(cameraPrefs.getBoolean(KEY_GYRO_PREVIEW_STABILIZATION_ENABLED, false))
    }
    var gyroPreviewStrength by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(KEY_GYRO_PREVIEW_STRENGTH, DEFAULT_GYRO_PREVIEW_CONFIG.strength)
                .coerceIn(MIN_GYRO_PREVIEW_STRENGTH, MAX_GYRO_PREVIEW_STRENGTH),
        )
    }
    var gyroPreviewPathFollow by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(KEY_GYRO_PREVIEW_PATH_FOLLOW, DEFAULT_GYRO_PREVIEW_CONFIG.pathFollowPerSecond)
                .coerceIn(MIN_GYRO_PREVIEW_PATH_FOLLOW, MAX_GYRO_PREVIEW_PATH_FOLLOW),
        )
    }
    var gyroPreviewOutputAlpha by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(KEY_GYRO_PREVIEW_OUTPUT_ALPHA, DEFAULT_GYRO_PREVIEW_CONFIG.outputLowPassAlpha)
                .coerceIn(MIN_GYRO_PREVIEW_OUTPUT_ALPHA, MAX_GYRO_PREVIEW_OUTPUT_ALPHA),
        )
    }
    var gyroPreviewOutputRateLimit by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_OUTPUT_RATE_LIMIT,
                DEFAULT_GYRO_PREVIEW_CONFIG.outputRateLimitPxPerSecond,
            ).coerceIn(MIN_GYRO_PREVIEW_OUTPUT_RATE_LIMIT, MAX_GYRO_PREVIEW_OUTPUT_RATE_LIMIT),
        )
    }
    var gyroPreviewOutputAccelerationLimit by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_OUTPUT_ACCELERATION_LIMIT,
                DEFAULT_GYRO_PREVIEW_CONFIG.outputAccelerationLimitPxPerSecond2,
            ).coerceIn(
                MIN_GYRO_PREVIEW_OUTPUT_ACCELERATION_LIMIT,
                MAX_GYRO_PREVIEW_OUTPUT_ACCELERATION_LIMIT,
            ),
        )
    }
    var gyroPreviewCropScale by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(KEY_GYRO_PREVIEW_CROP_SCALE, DEFAULT_GYRO_PREVIEW_CONFIG.cropScale)
                .coerceIn(MIN_GYRO_PREVIEW_CROP_SCALE, MAX_GYRO_PREVIEW_CROP_SCALE),
        )
    }
    var gyroPreviewMaxShift by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(KEY_GYRO_PREVIEW_MAX_SHIFT, DEFAULT_GYRO_PREVIEW_CONFIG.maxShiftRatio)
                .coerceIn(MIN_GYRO_PREVIEW_MAX_SHIFT, MAX_GYRO_PREVIEW_MAX_SHIFT),
        )
    }
    var gyroPreviewSoftBoundaryEnabled by remember {
        mutableStateOf(
            cameraPrefs.getBoolean(
                KEY_GYRO_PREVIEW_SOFT_BOUNDARY_ENABLED,
                DEFAULT_GYRO_PREVIEW_CONFIG.softBoundaryEnabled,
            ),
        )
    }
    var gyroPreviewSoftBoundaryStartRatio by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_SOFT_BOUNDARY_START_RATIO,
                DEFAULT_GYRO_PREVIEW_CONFIG.softBoundaryStartRatio,
            ).coerceIn(
                MIN_GYRO_PREVIEW_SOFT_BOUNDARY_START_RATIO,
                MAX_GYRO_PREVIEW_SOFT_BOUNDARY_START_RATIO,
            ),
        )
    }
    var gyroPreviewSoftBoundaryStrength by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_SOFT_BOUNDARY_STRENGTH,
                DEFAULT_GYRO_PREVIEW_CONFIG.softBoundaryStrength,
            ).coerceIn(
                MIN_GYRO_PREVIEW_SOFT_BOUNDARY_STRENGTH,
                MAX_GYRO_PREVIEW_SOFT_BOUNDARY_STRENGTH,
            ),
        )
    }
    var gyroPreviewReAimEnabled by remember {
        mutableStateOf(
            cameraPrefs.getBoolean(
                KEY_GYRO_PREVIEW_RE_AIM_ENABLED,
                DEFAULT_GYRO_PREVIEW_CONFIG.reAimEnabled,
            ),
        )
    }
    var gyroPreviewReAimRpsThreshold by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_RE_AIM_RPS_THRESHOLD,
                DEFAULT_GYRO_PREVIEW_CONFIG.reAimRpsThreshold,
            ).coerceIn(
                MIN_GYRO_PREVIEW_RE_AIM_RPS_THRESHOLD,
                MAX_GYRO_PREVIEW_RE_AIM_RPS_THRESHOLD,
            ),
        )
    }
    var gyroPreviewReAimResidualEnterRatio by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_RE_AIM_RESIDUAL_ENTER_RATIO,
                DEFAULT_GYRO_PREVIEW_CONFIG.reAimResidualEnterRatio,
            ).coerceIn(
                MIN_GYRO_PREVIEW_RE_AIM_RATIO,
                MAX_GYRO_PREVIEW_RE_AIM_RATIO,
            ),
        )
    }
    var gyroPreviewReAimResidualExitRatio by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_RE_AIM_RESIDUAL_EXIT_RATIO,
                DEFAULT_GYRO_PREVIEW_CONFIG.reAimResidualExitRatio,
            ).coerceIn(
                MIN_GYRO_PREVIEW_RE_AIM_RATIO,
                MAX_GYRO_PREVIEW_RE_AIM_RATIO,
            ),
        )
    }
    var gyroPreviewReAimStillMs by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_RE_AIM_STILL_MS,
                DEFAULT_GYRO_PREVIEW_CONFIG.reAimStillMs,
            ).coerceIn(
                MIN_GYRO_PREVIEW_RE_AIM_STILL_MS,
                MAX_GYRO_PREVIEW_RE_AIM_STILL_MS,
            ),
        )
    }
    var gyroPreviewReAimPathFollow by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_RE_AIM_PATH_FOLLOW,
                DEFAULT_GYRO_PREVIEW_CONFIG.reAimPathFollowPerSecond,
            ).coerceIn(
                MIN_GYRO_PREVIEW_RE_AIM_PATH_FOLLOW,
                MAX_GYRO_PREVIEW_RE_AIM_PATH_FOLLOW,
            ),
        )
    }
    var gyroPreviewReAimBlendIn by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_RE_AIM_BLEND_IN,
                DEFAULT_GYRO_PREVIEW_CONFIG.reAimBlendInPerSecond,
            ).coerceIn(
                MIN_GYRO_PREVIEW_RE_AIM_BLEND_RATE,
                MAX_GYRO_PREVIEW_RE_AIM_BLEND_RATE,
            ),
        )
    }
    var gyroPreviewReAimBlendOut by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_RE_AIM_BLEND_OUT,
                DEFAULT_GYRO_PREVIEW_CONFIG.reAimBlendOutPerSecond,
            ).coerceIn(
                MIN_GYRO_PREVIEW_RE_AIM_BLEND_RATE,
                MAX_GYRO_PREVIEW_RE_AIM_BLEND_RATE,
            ),
        )
    }
    var gyroPreviewDeadbandPx by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(KEY_GYRO_PREVIEW_DEADBAND_PX, DEFAULT_GYRO_PREVIEW_CONFIG.deadbandPx)
                .coerceIn(MIN_GYRO_PREVIEW_DEADBAND_PX, MAX_GYRO_PREVIEW_DEADBAND_PX),
        )
    }
    var gyroPreviewHorizontalGain by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(KEY_GYRO_PREVIEW_HORIZONTAL_GAIN, DEFAULT_GYRO_PREVIEW_CONFIG.horizontalGain)
                .coerceIn(MIN_GYRO_PREVIEW_AXIS_GAIN, MAX_GYRO_PREVIEW_AXIS_GAIN),
        )
    }
    var gyroPreviewVerticalGain by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(KEY_GYRO_PREVIEW_VERTICAL_GAIN, DEFAULT_GYRO_PREVIEW_CONFIG.verticalGain)
                .coerceIn(MIN_GYRO_PREVIEW_AXIS_GAIN, MAX_GYRO_PREVIEW_AXIS_GAIN),
        )
    }
    var gyroPreviewAimStabilizationEnabled by remember {
        mutableStateOf(
            cameraPrefs.getBoolean(
                KEY_GYRO_PREVIEW_AIM_STABILIZATION_ENABLED,
                DEFAULT_GYRO_PREVIEW_CONFIG.aimStabilizationEnabled,
            ),
        )
    }
    var gyroPreviewAimRpsThreshold by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(KEY_GYRO_PREVIEW_AIM_RPS_THRESHOLD, DEFAULT_GYRO_PREVIEW_CONFIG.aimRpsThreshold)
                .coerceIn(MIN_GYRO_PREVIEW_AIM_RPS_THRESHOLD, MAX_GYRO_PREVIEW_AIM_RPS_THRESHOLD),
        )
    }
    var gyroPreviewAimStrengthMultiplier by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_AIM_STRENGTH_MULTIPLIER,
                DEFAULT_GYRO_PREVIEW_CONFIG.aimStrengthMultiplier,
            ).coerceIn(MIN_GYRO_PREVIEW_AIM_STRENGTH_MULTIPLIER, MAX_GYRO_PREVIEW_AIM_STRENGTH_MULTIPLIER),
        )
    }
    var gyroPreviewAimPathFollow by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_AIM_PATH_FOLLOW,
                DEFAULT_GYRO_PREVIEW_CONFIG.aimPathFollowPerSecond,
            ).coerceIn(MIN_GYRO_PREVIEW_AIM_PATH_FOLLOW, MAX_GYRO_PREVIEW_AIM_PATH_FOLLOW),
        )
    }
    var gyroPreviewAimDeadbandPx by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(KEY_GYRO_PREVIEW_AIM_DEADBAND_PX, DEFAULT_GYRO_PREVIEW_CONFIG.aimDeadbandPx)
                .coerceIn(MIN_GYRO_PREVIEW_AIM_DEADBAND_PX, MAX_GYRO_PREVIEW_AIM_DEADBAND_PX),
        )
    }
    var gyroPreviewAimOutputRateLimit by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_AIM_OUTPUT_RATE_LIMIT,
                DEFAULT_GYRO_PREVIEW_CONFIG.aimOutputRateLimitPxPerSecond,
            ).coerceIn(MIN_GYRO_PREVIEW_AIM_OUTPUT_RATE_LIMIT, MAX_GYRO_PREVIEW_AIM_OUTPUT_RATE_LIMIT),
        )
    }
    var gyroPreviewAimOutputAccelerationLimit by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_AIM_OUTPUT_ACCELERATION_LIMIT,
                DEFAULT_GYRO_PREVIEW_CONFIG.aimOutputAccelerationLimitPxPerSecond2,
            ).coerceIn(
                MIN_GYRO_PREVIEW_AIM_OUTPUT_ACCELERATION_LIMIT,
                MAX_GYRO_PREVIEW_AIM_OUTPUT_ACCELERATION_LIMIT,
            ),
        )
    }
    var gyroPreviewResidualReleaseRpsThreshold by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_RESIDUAL_RELEASE_RPS_THRESHOLD,
                DEFAULT_GYRO_PREVIEW_CONFIG.residualReleaseRpsThreshold,
            ).coerceIn(
                MIN_GYRO_PREVIEW_RESIDUAL_RELEASE_RPS_THRESHOLD,
                MAX_GYRO_PREVIEW_RESIDUAL_RELEASE_RPS_THRESHOLD,
            ),
        )
    }
    var gyroPreviewZoomCompensationEnabled by remember {
        mutableStateOf(
            cameraPrefs.getBoolean(
                KEY_GYRO_PREVIEW_ZOOM_COMPENSATION_ENABLED,
                DEFAULT_GYRO_PREVIEW_CONFIG.zoomCompensationEnabled,
            ),
        )
    }
    var gyroPreviewZoomCompensationStrength by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_ZOOM_COMPENSATION_STRENGTH,
                DEFAULT_GYRO_PREVIEW_CONFIG.zoomCompensationStrength,
            ).coerceIn(
                MIN_GYRO_PREVIEW_ZOOM_COMPENSATION_STRENGTH,
                MAX_GYRO_PREVIEW_ZOOM_COMPENSATION_STRENGTH,
            ),
        )
    }
    var gyroPreviewMaxCompensationZoom by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_MAX_COMPENSATION_ZOOM,
                DEFAULT_GYRO_PREVIEW_CONFIG.maxCompensationZoom,
            ).coerceIn(MIN_GYRO_PREVIEW_MAX_COMPENSATION_ZOOM, MAX_GYRO_PREVIEW_MAX_COMPENSATION_ZOOM),
        )
    }
    var gyroPreviewFrameSyncEnabled by remember {
        mutableStateOf(
            cameraPrefs.getBoolean(
                KEY_GYRO_PREVIEW_FRAME_SYNC_ENABLED,
                DEFAULT_GYRO_PREVIEW_CONFIG.frameSyncEnabled,
            ),
        )
    }
    var gyroPreviewFrameTimestampOffsetMs by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_GYRO_PREVIEW_FRAME_TIMESTAMP_OFFSET_MS,
                DEFAULT_GYRO_PREVIEW_CONFIG.frameTimestampOffsetMs,
            ).coerceIn(MIN_GYRO_PREVIEW_FRAME_TIMESTAMP_OFFSET_MS, MAX_GYRO_PREVIEW_FRAME_TIMESTAMP_OFFSET_MS),
        )
    }
    var gyroPreviewGlRenderEnabled by remember {
        mutableStateOf(cameraPrefs.getBoolean(KEY_GYRO_PREVIEW_GL_RENDER_ENABLED, false))
    }
    var gyroPreviewDiagnosticsEnabled by remember {
        mutableStateOf(cameraPrefs.getBoolean(KEY_GYRO_PREVIEW_DIAGNOSTICS_ENABLED, false))
    }
    var gyroPreviewRollCompensationEnabled by remember {
        mutableStateOf(
            cameraPrefs.getBoolean(
                KEY_GYRO_PREVIEW_ROLL_COMPENSATION_ENABLED,
                DEFAULT_GYRO_PREVIEW_CONFIG.rollCompensationEnabled,
            ),
        )
    }
    var gyroPreviewRollStrength by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(KEY_GYRO_PREVIEW_ROLL_STRENGTH, DEFAULT_GYRO_PREVIEW_CONFIG.rollStrength)
                .coerceIn(MIN_GYRO_PREVIEW_ROLL_STRENGTH, MAX_GYRO_PREVIEW_ROLL_STRENGTH),
        )
    }
    var gyroPreviewMaxRollDegrees by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(KEY_GYRO_PREVIEW_MAX_ROLL_DEGREES, DEFAULT_GYRO_PREVIEW_CONFIG.maxRollDegrees)
                .coerceIn(MIN_GYRO_PREVIEW_MAX_ROLL_DEGREES, MAX_GYRO_PREVIEW_MAX_ROLL_DEGREES),
        )
    }
    var gyroPreviewInvertRoll by remember {
        mutableStateOf(cameraPrefs.getBoolean(KEY_GYRO_PREVIEW_INVERT_ROLL, DEFAULT_GYRO_PREVIEW_CONFIG.invertRoll))
    }
    var gyroPreviewSwapAxes by remember {
        mutableStateOf(
            cameraPrefs.getBoolean(KEY_GYRO_PREVIEW_SWAP_AXES, DEFAULT_GYRO_PREVIEW_CONFIG.swapPitchYawAxes),
        )
    }
    var gyroPreviewInvertHorizontal by remember {
        mutableStateOf(
            cameraPrefs.getBoolean(KEY_GYRO_PREVIEW_INVERT_HORIZONTAL, DEFAULT_GYRO_PREVIEW_CONFIG.invertHorizontal),
        )
    }
    var gyroPreviewInvertVertical by remember {
        mutableStateOf(
            cameraPrefs.getBoolean(KEY_GYRO_PREVIEW_INVERT_VERTICAL, DEFAULT_GYRO_PREVIEW_CONFIG.invertVertical),
        )
    }
    var gyroStabilizationCalibrationEnabled by remember {
        mutableStateOf(cameraPrefs.getBoolean(KEY_GYRO_STABILIZATION_CALIBRATION_ENABLED, false))
    }
    var gyroStabilizationCalibrationFineSampleEnabled by remember {
        mutableStateOf(
            cameraPrefs.getBoolean(KEY_GYRO_STABILIZATION_CALIBRATION_FINE_SAMPLE_ENABLED, false),
        )
    }
    var showGyroPreviewSettingsDialog by remember { mutableStateOf(false) }
    var gyroPreviewSettingsError by remember { mutableStateOf<String?>(null) }
    var gyroPreviewStrengthInput by remember { mutableStateOf(formatFloatSetting(gyroPreviewStrength)) }
    var gyroPreviewPathFollowInput by remember { mutableStateOf(formatFloatSetting(gyroPreviewPathFollow)) }
    var gyroPreviewOutputAlphaInput by remember { mutableStateOf(formatFloatSetting(gyroPreviewOutputAlpha)) }
    var gyroPreviewOutputRateLimitInput by remember { mutableStateOf(formatFloatSetting(gyroPreviewOutputRateLimit)) }
    var gyroPreviewOutputAccelerationLimitInput by remember {
        mutableStateOf(formatFloatSetting(gyroPreviewOutputAccelerationLimit))
    }
    var gyroPreviewCropScaleInput by remember { mutableStateOf(formatFloatSetting(gyroPreviewCropScale)) }
    var gyroPreviewMaxShiftInput by remember { mutableStateOf(formatFloatSetting(gyroPreviewMaxShift)) }
    var gyroPreviewSoftBoundaryEnabledInput by remember { mutableStateOf(gyroPreviewSoftBoundaryEnabled) }
    var gyroPreviewSoftBoundaryStartRatioInput by remember {
        mutableStateOf(formatFloatSetting(gyroPreviewSoftBoundaryStartRatio))
    }
    var gyroPreviewSoftBoundaryStrengthInput by remember {
        mutableStateOf(formatFloatSetting(gyroPreviewSoftBoundaryStrength))
    }
    var gyroPreviewReAimEnabledInput by remember { mutableStateOf(gyroPreviewReAimEnabled) }
    var gyroPreviewReAimRpsThresholdInput by remember {
        mutableStateOf(formatGyroRps(gyroPreviewReAimRpsThreshold))
    }
    var gyroPreviewReAimResidualEnterRatioInput by remember {
        mutableStateOf(formatFloatSetting(gyroPreviewReAimResidualEnterRatio))
    }
    var gyroPreviewReAimResidualExitRatioInput by remember {
        mutableStateOf(formatFloatSetting(gyroPreviewReAimResidualExitRatio))
    }
    var gyroPreviewReAimStillMsInput by remember {
        mutableStateOf(formatFloatSetting(gyroPreviewReAimStillMs))
    }
    var gyroPreviewReAimPathFollowInput by remember {
        mutableStateOf(formatFloatSetting(gyroPreviewReAimPathFollow))
    }
    var gyroPreviewReAimBlendInInput by remember {
        mutableStateOf(formatFloatSetting(gyroPreviewReAimBlendIn))
    }
    var gyroPreviewReAimBlendOutInput by remember {
        mutableStateOf(formatFloatSetting(gyroPreviewReAimBlendOut))
    }
    var gyroPreviewDeadbandInput by remember { mutableStateOf(formatFloatSetting(gyroPreviewDeadbandPx)) }
    var gyroPreviewHorizontalGainInput by remember { mutableStateOf(formatFloatSetting(gyroPreviewHorizontalGain)) }
    var gyroPreviewVerticalGainInput by remember { mutableStateOf(formatFloatSetting(gyroPreviewVerticalGain)) }
    var gyroPreviewAimStabilizationEnabledInput by remember {
        mutableStateOf(gyroPreviewAimStabilizationEnabled)
    }
    var gyroPreviewAimRpsThresholdInput by remember { mutableStateOf(formatGyroRps(gyroPreviewAimRpsThreshold)) }
    var gyroPreviewAimStrengthMultiplierInput by remember {
        mutableStateOf(formatFloatSetting(gyroPreviewAimStrengthMultiplier))
    }
    var gyroPreviewAimPathFollowInput by remember { mutableStateOf(formatFloatSetting(gyroPreviewAimPathFollow)) }
    var gyroPreviewAimDeadbandInput by remember { mutableStateOf(formatFloatSetting(gyroPreviewAimDeadbandPx)) }
    var gyroPreviewAimOutputRateLimitInput by remember {
        mutableStateOf(formatFloatSetting(gyroPreviewAimOutputRateLimit))
    }
    var gyroPreviewAimOutputAccelerationLimitInput by remember {
        mutableStateOf(formatFloatSetting(gyroPreviewAimOutputAccelerationLimit))
    }
    var gyroPreviewResidualReleaseRpsThresholdInput by remember {
        mutableStateOf(formatGyroRps(gyroPreviewResidualReleaseRpsThreshold))
    }
    var gyroPreviewZoomCompensationEnabledInput by remember {
        mutableStateOf(gyroPreviewZoomCompensationEnabled)
    }
    var gyroPreviewZoomCompensationStrengthInput by remember {
        mutableStateOf(formatFloatSetting(gyroPreviewZoomCompensationStrength))
    }
    var gyroPreviewMaxCompensationZoomInput by remember {
        mutableStateOf(formatFloatSetting(gyroPreviewMaxCompensationZoom))
    }
    var gyroPreviewFrameSyncEnabledInput by remember { mutableStateOf(gyroPreviewFrameSyncEnabled) }
    var gyroPreviewFrameTimestampOffsetInput by remember {
        mutableStateOf(formatFloatSetting(gyroPreviewFrameTimestampOffsetMs))
    }
    var gyroPreviewGlRenderEnabledInput by remember { mutableStateOf(gyroPreviewGlRenderEnabled) }
    var gyroPreviewDiagnosticsEnabledInput by remember { mutableStateOf(gyroPreviewDiagnosticsEnabled) }
    var gyroStabilizationCalibrationFineSampleInput by remember {
        mutableStateOf(gyroStabilizationCalibrationFineSampleEnabled)
    }
    var gyroPreviewRollCompensationEnabledInput by remember {
        mutableStateOf(gyroPreviewRollCompensationEnabled)
    }
    var gyroPreviewRollStrengthInput by remember { mutableStateOf(formatFloatSetting(gyroPreviewRollStrength)) }
    var gyroPreviewMaxRollDegreesInput by remember { mutableStateOf(formatFloatSetting(gyroPreviewMaxRollDegrees)) }
    var gyroPreviewInvertRollInput by remember { mutableStateOf(gyroPreviewInvertRoll) }
    var gyroPreviewSwapAxesInput by remember { mutableStateOf(gyroPreviewSwapAxes) }
    var gyroPreviewInvertHorizontalInput by remember { mutableStateOf(gyroPreviewInvertHorizontal) }
    var gyroPreviewInvertVerticalInput by remember { mutableStateOf(gyroPreviewInvertVertical) }
    var showLaserComparePanel by remember { mutableStateOf(false) }
    var laserCompareEnabled by remember { mutableStateOf(false) }
    var laserCompareText by remember { mutableStateOf("") }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var glPreviewViewRef by remember { mutableStateOf<GlStabilizedPreviewView?>(null) }
    var previewSurfaceTextureRef by remember { mutableStateOf<SurfaceTexture?>(null) }
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
    var wrapperRotation by remember { mutableFloatStateOf(0f) }
    var textureBaseScale by remember { mutableFloatStateOf(1f) }
    var textureBaseTx by remember { mutableFloatStateOf(0f) }
    var textureBaseTy by remember { mutableFloatStateOf(0f) }
    var zoomBackendText by remember { mutableStateOf("") }
    var activePhysicalCameraId by remember { mutableStateOf<String?>(null) }
    var activePhysicalLensReported by remember { mutableStateOf(false) }
    var gyroPreviewTransform by remember { mutableStateOf(GyroPreviewStabilizer.Transform.Identity) }
    var gyroPreviewText by remember { mutableStateOf("") }
    var gyroPreviewLastMatrixUpdateMs by remember { mutableLongStateOf(0L) }
    var gyroPreviewLastOverlayUpdateMs by remember { mutableLongStateOf(0L) }
    var gyroPreviewLastHudUpdateMs by remember { mutableLongStateOf(0L) }
    var gyroPreviewLastModelLogMs by remember { mutableLongStateOf(0L) }
    var gyroStabilizationCalibrationText by remember { mutableStateOf("") }
    var gyroStabilizationCalibrationFrame by remember { mutableStateOf<StabilizationCalibrationFrame?>(null) }
    val gyroStabilizationCalibrationHints = remember { mutableStateListOf<StabilizationCalibrationHintSample>() }

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
    var yoloTopDetectionEnabled by remember {
        mutableStateOf(cameraPrefs.getBoolean(KEY_YOLO_TOP_DETECTION_ENABLED, true))
    }
    var yoloTopConfidenceThreshold by remember {
        mutableFloatStateOf(
            cameraPrefs.getFloat(
                KEY_YOLO_TOP_CONFIDENCE_THRESHOLD,
                YoloPostprocessOptions.DEFAULT_TOP_CONFIDENCE_THRESHOLD,
            ).coerceIn(
                YoloPostprocessOptions.MIN_CONFIDENCE_THRESHOLD,
                YoloPostprocessOptions.MAX_CONFIDENCE_THRESHOLD,
            ),
        )
    }
    var showYoloFilterDialog by remember { mutableStateOf(false) }
    var yoloTopDetectionEnabledInput by remember { mutableStateOf(yoloTopDetectionEnabled) }
    var yoloConfidenceThresholdInput by remember { mutableStateOf(formatYoloConfidence(yoloTopConfidenceThreshold)) }
    var yoloFilterError by remember { mutableStateOf<String?>(null) }

    // 在后台线程加载模型（避免 Tasks.await 阻塞主线程）
    LaunchedEffect(gyroPreviewGlRenderEnabled) {
        if (gyroPreviewGlRenderEnabled) {
            yoloDetector?.close()
            yoloDetector = null
            modelLoadStatus = "GL逐帧预览实验，未加载"
            Log.i("CameraScreen", "gyro-preview-gl: YOLO model disabled")
            return@LaunchedEffect
        }
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

    fun currentGyroPreviewConfig(): GyroPreviewStabilizer.Config {
        return GyroPreviewStabilizer.Config(
            strength = gyroPreviewStrength.coerceIn(MIN_GYRO_PREVIEW_STRENGTH, MAX_GYRO_PREVIEW_STRENGTH),
            pathFollowPerSecond = gyroPreviewPathFollow.coerceIn(
                MIN_GYRO_PREVIEW_PATH_FOLLOW,
                MAX_GYRO_PREVIEW_PATH_FOLLOW,
            ),
            outputLowPassAlpha = gyroPreviewOutputAlpha.coerceIn(
                MIN_GYRO_PREVIEW_OUTPUT_ALPHA,
                MAX_GYRO_PREVIEW_OUTPUT_ALPHA,
            ),
            outputRateLimitPxPerSecond = gyroPreviewOutputRateLimit.coerceIn(
                MIN_GYRO_PREVIEW_OUTPUT_RATE_LIMIT,
                MAX_GYRO_PREVIEW_OUTPUT_RATE_LIMIT,
            ),
            outputAccelerationLimitPxPerSecond2 = gyroPreviewOutputAccelerationLimit.coerceIn(
                MIN_GYRO_PREVIEW_OUTPUT_ACCELERATION_LIMIT,
                MAX_GYRO_PREVIEW_OUTPUT_ACCELERATION_LIMIT,
            ),
            cropScale = gyroPreviewCropScale.coerceIn(MIN_GYRO_PREVIEW_CROP_SCALE, MAX_GYRO_PREVIEW_CROP_SCALE),
            maxShiftRatio = gyroPreviewMaxShift.coerceIn(MIN_GYRO_PREVIEW_MAX_SHIFT, MAX_GYRO_PREVIEW_MAX_SHIFT),
            softBoundaryEnabled = gyroPreviewSoftBoundaryEnabled,
            softBoundaryStartRatio = gyroPreviewSoftBoundaryStartRatio.coerceIn(
                MIN_GYRO_PREVIEW_SOFT_BOUNDARY_START_RATIO,
                MAX_GYRO_PREVIEW_SOFT_BOUNDARY_START_RATIO,
            ),
            softBoundaryStrength = gyroPreviewSoftBoundaryStrength.coerceIn(
                MIN_GYRO_PREVIEW_SOFT_BOUNDARY_STRENGTH,
                MAX_GYRO_PREVIEW_SOFT_BOUNDARY_STRENGTH,
            ),
            reAimEnabled = gyroPreviewReAimEnabled,
            reAimRpsThreshold = gyroPreviewReAimRpsThreshold.coerceIn(
                MIN_GYRO_PREVIEW_RE_AIM_RPS_THRESHOLD,
                MAX_GYRO_PREVIEW_RE_AIM_RPS_THRESHOLD,
            ),
            reAimResidualEnterRatio = gyroPreviewReAimResidualEnterRatio.coerceIn(
                MIN_GYRO_PREVIEW_RE_AIM_RATIO,
                MAX_GYRO_PREVIEW_RE_AIM_RATIO,
            ),
            reAimResidualExitRatio = gyroPreviewReAimResidualExitRatio.coerceIn(
                MIN_GYRO_PREVIEW_RE_AIM_RATIO,
                MAX_GYRO_PREVIEW_RE_AIM_RATIO,
            ),
            reAimStillMs = gyroPreviewReAimStillMs.coerceIn(
                MIN_GYRO_PREVIEW_RE_AIM_STILL_MS,
                MAX_GYRO_PREVIEW_RE_AIM_STILL_MS,
            ),
            reAimPathFollowPerSecond = gyroPreviewReAimPathFollow.coerceIn(
                MIN_GYRO_PREVIEW_RE_AIM_PATH_FOLLOW,
                MAX_GYRO_PREVIEW_RE_AIM_PATH_FOLLOW,
            ),
            reAimBlendInPerSecond = gyroPreviewReAimBlendIn.coerceIn(
                MIN_GYRO_PREVIEW_RE_AIM_BLEND_RATE,
                MAX_GYRO_PREVIEW_RE_AIM_BLEND_RATE,
            ),
            reAimBlendOutPerSecond = gyroPreviewReAimBlendOut.coerceIn(
                MIN_GYRO_PREVIEW_RE_AIM_BLEND_RATE,
                MAX_GYRO_PREVIEW_RE_AIM_BLEND_RATE,
            ),
            deadbandPx = gyroPreviewDeadbandPx.coerceIn(
                MIN_GYRO_PREVIEW_DEADBAND_PX,
                MAX_GYRO_PREVIEW_DEADBAND_PX,
            ),
            horizontalGain = gyroPreviewHorizontalGain.coerceIn(
                MIN_GYRO_PREVIEW_AXIS_GAIN,
                MAX_GYRO_PREVIEW_AXIS_GAIN,
            ),
            verticalGain = gyroPreviewVerticalGain.coerceIn(
                MIN_GYRO_PREVIEW_AXIS_GAIN,
                MAX_GYRO_PREVIEW_AXIS_GAIN,
            ),
            aimStabilizationEnabled = gyroPreviewAimStabilizationEnabled,
            aimRpsThreshold = gyroPreviewAimRpsThreshold.coerceIn(
                MIN_GYRO_PREVIEW_AIM_RPS_THRESHOLD,
                MAX_GYRO_PREVIEW_AIM_RPS_THRESHOLD,
            ),
            aimStrengthMultiplier = gyroPreviewAimStrengthMultiplier.coerceIn(
                MIN_GYRO_PREVIEW_AIM_STRENGTH_MULTIPLIER,
                MAX_GYRO_PREVIEW_AIM_STRENGTH_MULTIPLIER,
            ),
            aimPathFollowPerSecond = gyroPreviewAimPathFollow.coerceIn(
                MIN_GYRO_PREVIEW_AIM_PATH_FOLLOW,
                MAX_GYRO_PREVIEW_AIM_PATH_FOLLOW,
            ),
            aimDeadbandPx = gyroPreviewAimDeadbandPx.coerceIn(
                MIN_GYRO_PREVIEW_AIM_DEADBAND_PX,
                MAX_GYRO_PREVIEW_AIM_DEADBAND_PX,
            ),
            aimOutputRateLimitPxPerSecond = gyroPreviewAimOutputRateLimit.coerceIn(
                MIN_GYRO_PREVIEW_AIM_OUTPUT_RATE_LIMIT,
                MAX_GYRO_PREVIEW_AIM_OUTPUT_RATE_LIMIT,
            ),
            aimOutputAccelerationLimitPxPerSecond2 = gyroPreviewAimOutputAccelerationLimit.coerceIn(
                MIN_GYRO_PREVIEW_AIM_OUTPUT_ACCELERATION_LIMIT,
                MAX_GYRO_PREVIEW_AIM_OUTPUT_ACCELERATION_LIMIT,
            ),
            residualReleaseRpsThreshold = gyroPreviewResidualReleaseRpsThreshold.coerceIn(
                MIN_GYRO_PREVIEW_RESIDUAL_RELEASE_RPS_THRESHOLD,
                MAX_GYRO_PREVIEW_RESIDUAL_RELEASE_RPS_THRESHOLD,
            ),
            zoomCompensationEnabled = gyroPreviewZoomCompensationEnabled,
            zoomCompensationStrength = gyroPreviewZoomCompensationStrength.coerceIn(
                MIN_GYRO_PREVIEW_ZOOM_COMPENSATION_STRENGTH,
                MAX_GYRO_PREVIEW_ZOOM_COMPENSATION_STRENGTH,
            ),
            maxCompensationZoom = gyroPreviewMaxCompensationZoom.coerceIn(
                MIN_GYRO_PREVIEW_MAX_COMPENSATION_ZOOM,
                MAX_GYRO_PREVIEW_MAX_COMPENSATION_ZOOM,
            ),
            frameSyncEnabled = gyroPreviewFrameSyncEnabled,
            frameTimestampOffsetMs = gyroPreviewFrameTimestampOffsetMs.coerceIn(
                MIN_GYRO_PREVIEW_FRAME_TIMESTAMP_OFFSET_MS,
                MAX_GYRO_PREVIEW_FRAME_TIMESTAMP_OFFSET_MS,
            ),
            rollCompensationEnabled = gyroPreviewRollCompensationEnabled,
            rollStrength = gyroPreviewRollStrength.coerceIn(
                MIN_GYRO_PREVIEW_ROLL_STRENGTH,
                MAX_GYRO_PREVIEW_ROLL_STRENGTH,
            ),
            maxRollDegrees = gyroPreviewMaxRollDegrees.coerceIn(
                MIN_GYRO_PREVIEW_MAX_ROLL_DEGREES,
                MAX_GYRO_PREVIEW_MAX_ROLL_DEGREES,
            ),
            invertRoll = gyroPreviewInvertRoll,
            swapPitchYawAxes = gyroPreviewSwapAxes,
            invertHorizontal = gyroPreviewInvertHorizontal,
            invertVertical = gyroPreviewInvertVertical,
        )
    }

    fun gyroPreviewAxisText(): String {
        val axes = if (gyroPreviewSwapAxes) "YX" else "XY"
        val horizontal = if (gyroPreviewInvertHorizontal) "H-" else "H+"
        val vertical = if (gyroPreviewInvertVertical) "V-" else "V+"
        val roll = if (gyroPreviewRollCompensationEnabled) {
            if (gyroPreviewInvertRoll) "R-" else "R+"
        } else {
            "R关"
        }
        return "$axes $horizontal $vertical $roll"
    }

    fun gyroPreviewSettingsChanged(): Boolean {
        return currentGyroPreviewConfig() != DEFAULT_GYRO_PREVIEW_CONFIG ||
            gyroPreviewDiagnosticsEnabled ||
            gyroPreviewGlRenderEnabled ||
            gyroStabilizationCalibrationFineSampleEnabled
    }

    fun stabilizationCalibrationSampleMode(): StabilizationCalibrationSampleMode {
        return if (gyroStabilizationCalibrationFineSampleEnabled) {
            StabilizationCalibrationSampleMode.FINE
        } else {
            StabilizationCalibrationSampleMode.FAST
        }
    }

    fun fillGyroPreviewSettingInputs(config: GyroPreviewStabilizer.Config) {
        gyroPreviewStrengthInput = formatFloatSetting(config.strength)
        gyroPreviewPathFollowInput = formatFloatSetting(config.pathFollowPerSecond)
        gyroPreviewOutputAlphaInput = formatFloatSetting(config.outputLowPassAlpha)
        gyroPreviewOutputRateLimitInput = formatFloatSetting(config.outputRateLimitPxPerSecond)
        gyroPreviewOutputAccelerationLimitInput =
            formatFloatSetting(config.outputAccelerationLimitPxPerSecond2)
        gyroPreviewCropScaleInput = formatFloatSetting(config.cropScale)
        gyroPreviewMaxShiftInput = formatFloatSetting(config.maxShiftRatio)
        gyroPreviewSoftBoundaryEnabledInput = config.softBoundaryEnabled
        gyroPreviewSoftBoundaryStartRatioInput = formatFloatSetting(config.softBoundaryStartRatio)
        gyroPreviewSoftBoundaryStrengthInput = formatFloatSetting(config.softBoundaryStrength)
        gyroPreviewReAimEnabledInput = config.reAimEnabled
        gyroPreviewReAimRpsThresholdInput = formatGyroRps(config.reAimRpsThreshold)
        gyroPreviewReAimResidualEnterRatioInput = formatFloatSetting(config.reAimResidualEnterRatio)
        gyroPreviewReAimResidualExitRatioInput = formatFloatSetting(config.reAimResidualExitRatio)
        gyroPreviewReAimStillMsInput = formatFloatSetting(config.reAimStillMs)
        gyroPreviewReAimPathFollowInput = formatFloatSetting(config.reAimPathFollowPerSecond)
        gyroPreviewReAimBlendInInput = formatFloatSetting(config.reAimBlendInPerSecond)
        gyroPreviewReAimBlendOutInput = formatFloatSetting(config.reAimBlendOutPerSecond)
        gyroPreviewDeadbandInput = formatFloatSetting(config.deadbandPx)
        gyroPreviewHorizontalGainInput = formatFloatSetting(config.horizontalGain)
        gyroPreviewVerticalGainInput = formatFloatSetting(config.verticalGain)
        gyroPreviewAimStabilizationEnabledInput = config.aimStabilizationEnabled
        gyroPreviewAimRpsThresholdInput = formatGyroRps(config.aimRpsThreshold)
        gyroPreviewAimStrengthMultiplierInput = formatFloatSetting(config.aimStrengthMultiplier)
        gyroPreviewAimPathFollowInput = formatFloatSetting(config.aimPathFollowPerSecond)
        gyroPreviewAimDeadbandInput = formatFloatSetting(config.aimDeadbandPx)
        gyroPreviewAimOutputRateLimitInput = formatFloatSetting(config.aimOutputRateLimitPxPerSecond)
        gyroPreviewAimOutputAccelerationLimitInput =
            formatFloatSetting(config.aimOutputAccelerationLimitPxPerSecond2)
        gyroPreviewResidualReleaseRpsThresholdInput = formatGyroRps(config.residualReleaseRpsThreshold)
        gyroPreviewZoomCompensationEnabledInput = config.zoomCompensationEnabled
        gyroPreviewZoomCompensationStrengthInput = formatFloatSetting(config.zoomCompensationStrength)
        gyroPreviewMaxCompensationZoomInput = formatFloatSetting(config.maxCompensationZoom)
        gyroPreviewFrameSyncEnabledInput = config.frameSyncEnabled
        gyroPreviewFrameTimestampOffsetInput = formatFloatSetting(config.frameTimestampOffsetMs)
        gyroPreviewGlRenderEnabledInput = gyroPreviewGlRenderEnabled
        gyroPreviewDiagnosticsEnabledInput = gyroPreviewDiagnosticsEnabled
        gyroStabilizationCalibrationFineSampleInput = gyroStabilizationCalibrationFineSampleEnabled
        gyroPreviewRollCompensationEnabledInput = config.rollCompensationEnabled
        gyroPreviewRollStrengthInput = formatFloatSetting(config.rollStrength)
        gyroPreviewMaxRollDegreesInput = formatFloatSetting(config.maxRollDegrees)
        gyroPreviewInvertRollInput = config.invertRoll
        gyroPreviewSwapAxesInput = config.swapPitchYawAxes
        gyroPreviewInvertHorizontalInput = config.invertHorizontal
        gyroPreviewInvertVerticalInput = config.invertVertical
        gyroPreviewSettingsError = null
    }

    fun applyGyroPreviewParameterPreset(preset: GyroPreviewParameterPreset) {
        gyroPreviewStrengthInput = formatFloatSetting(preset.strength)
        gyroPreviewPathFollowInput = formatFloatSetting(preset.pathFollowPerSecond)
        gyroPreviewOutputAlphaInput = formatFloatSetting(preset.outputLowPassAlpha)
        gyroPreviewOutputRateLimitInput = formatFloatSetting(preset.outputRateLimitPxPerSecond)
        gyroPreviewOutputAccelerationLimitInput =
            formatFloatSetting(preset.outputAccelerationLimitPxPerSecond2)
        gyroPreviewCropScaleInput = formatFloatSetting(preset.cropScale)
        gyroPreviewMaxShiftInput = formatFloatSetting(preset.maxShiftRatio)
        gyroPreviewSoftBoundaryEnabledInput = preset.softBoundaryEnabled
        gyroPreviewSoftBoundaryStartRatioInput = formatFloatSetting(preset.softBoundaryStartRatio)
        gyroPreviewSoftBoundaryStrengthInput = formatFloatSetting(preset.softBoundaryStrength)
        gyroPreviewReAimEnabledInput = preset.reAimEnabled
        gyroPreviewReAimRpsThresholdInput = formatGyroRps(preset.reAimRpsThreshold)
        gyroPreviewReAimResidualEnterRatioInput = formatFloatSetting(preset.reAimResidualEnterRatio)
        gyroPreviewReAimResidualExitRatioInput = formatFloatSetting(preset.reAimResidualExitRatio)
        gyroPreviewReAimStillMsInput = formatFloatSetting(preset.reAimStillMs)
        gyroPreviewReAimPathFollowInput = formatFloatSetting(preset.reAimPathFollowPerSecond)
        gyroPreviewReAimBlendInInput = formatFloatSetting(preset.reAimBlendInPerSecond)
        gyroPreviewReAimBlendOutInput = formatFloatSetting(preset.reAimBlendOutPerSecond)
        gyroPreviewDeadbandInput = formatFloatSetting(preset.deadbandPx)
        gyroPreviewHorizontalGainInput = formatFloatSetting(preset.horizontalGain)
        gyroPreviewVerticalGainInput = formatFloatSetting(preset.verticalGain)
        gyroPreviewAimStabilizationEnabledInput = preset.aimStabilizationEnabled
        gyroPreviewAimRpsThresholdInput = formatGyroRps(preset.aimRpsThreshold)
        gyroPreviewAimStrengthMultiplierInput = formatFloatSetting(preset.aimStrengthMultiplier)
        gyroPreviewAimPathFollowInput = formatFloatSetting(preset.aimPathFollowPerSecond)
        gyroPreviewAimDeadbandInput = formatFloatSetting(preset.aimDeadbandPx)
        gyroPreviewAimOutputRateLimitInput = formatFloatSetting(preset.aimOutputRateLimitPxPerSecond)
        gyroPreviewAimOutputAccelerationLimitInput =
            formatFloatSetting(preset.aimOutputAccelerationLimitPxPerSecond2)
        gyroPreviewResidualReleaseRpsThresholdInput = formatGyroRps(preset.residualReleaseRpsThreshold)
        gyroPreviewZoomCompensationEnabledInput = preset.zoomCompensationEnabled
        gyroPreviewZoomCompensationStrengthInput = formatFloatSetting(preset.zoomCompensationStrength)
        gyroPreviewMaxCompensationZoomInput = formatFloatSetting(preset.maxCompensationZoom)
        gyroPreviewFrameSyncEnabledInput = preset.frameSyncEnabled
        gyroPreviewFrameTimestampOffsetInput = formatFloatSetting(preset.frameTimestampOffsetMs)
        gyroPreviewRollCompensationEnabledInput = preset.rollCompensationEnabled
        gyroPreviewRollStrengthInput = formatFloatSetting(preset.rollStrength)
        gyroPreviewMaxRollDegreesInput = formatFloatSetting(preset.maxRollDegrees)
        gyroPreviewInvertRollInput = preset.invertRoll
        gyroPreviewSettingsError = null
        Log.i(
            "CameraScreen",
            "gyro-preview-preset: label=${preset.label}, strength=${preset.strength}, " +
                "pathFollow=${preset.pathFollowPerSecond}, outputAlpha=${preset.outputLowPassAlpha}, " +
                "outputRateLimit=${preset.outputRateLimitPxPerSecond}, " +
                "crop=${preset.cropScale}, maxShift=${preset.maxShiftRatio}, deadband=${preset.deadbandPx}, " +
                "hGain=${preset.horizontalGain}, vGain=${preset.verticalGain}, " +
                "aim=${preset.aimStabilizationEnabled}, aimRps=${preset.aimRpsThreshold}, " +
                "aimStrength=${preset.aimStrengthMultiplier}, aimFollow=${preset.aimPathFollowPerSecond}, " +
                "aimDeadband=${preset.aimDeadbandPx}, aimRateLimit=${preset.aimOutputRateLimitPxPerSecond}, " +
                "zoomComp=${preset.zoomCompensationEnabled}, " +
                "zoomStrength=${preset.zoomCompensationStrength}, maxZoom=${preset.maxCompensationZoom}, " +
                "frameSync=${preset.frameSyncEnabled}, frameOffsetMs=${preset.frameTimestampOffsetMs}, " +
                "roll=${preset.rollCompensationEnabled}, " +
                "rollStrength=${preset.rollStrength}, maxRoll=${preset.maxRollDegrees}, invertRoll=${preset.invertRoll}",
        )
    }

    fun openGyroPreviewSettingsDialog() {
        fillGyroPreviewSettingInputs(currentGyroPreviewConfig())
        showGyroPreviewSettingsDialog = true
    }

    fun saveGyroPreviewSettings() {
        fun parseFloat(label: String, text: String, min: Float, max: Float): Float? {
            val value = text.trim().toFloatOrNull()
            if (value == null || value !in min..max) {
                gyroPreviewSettingsError =
                    "$label 请输入 ${formatFloatSetting(min)}-${formatFloatSetting(max)}"
                return null
            }
            return value
        }

        val strength = parseFloat(
            "强度",
            gyroPreviewStrengthInput,
            MIN_GYRO_PREVIEW_STRENGTH,
            MAX_GYRO_PREVIEW_STRENGTH,
        ) ?: return
        val pathFollow = parseFloat(
            "跟随速度",
            gyroPreviewPathFollowInput,
            MIN_GYRO_PREVIEW_PATH_FOLLOW,
            MAX_GYRO_PREVIEW_PATH_FOLLOW,
        ) ?: return
        val outputAlpha = parseFloat(
            "输出低通",
            gyroPreviewOutputAlphaInput,
            MIN_GYRO_PREVIEW_OUTPUT_ALPHA,
            MAX_GYRO_PREVIEW_OUTPUT_ALPHA,
        ) ?: return
        val outputRateLimit = parseFloat(
            "输出限速",
            gyroPreviewOutputRateLimitInput,
            MIN_GYRO_PREVIEW_OUTPUT_RATE_LIMIT,
            MAX_GYRO_PREVIEW_OUTPUT_RATE_LIMIT,
        ) ?: return
        val outputAccelerationLimit = parseFloat(
            "输出加速度",
            gyroPreviewOutputAccelerationLimitInput,
            MIN_GYRO_PREVIEW_OUTPUT_ACCELERATION_LIMIT,
            MAX_GYRO_PREVIEW_OUTPUT_ACCELERATION_LIMIT,
        ) ?: return
        val cropScale = parseFloat(
            "裁切倍数",
            gyroPreviewCropScaleInput,
            MIN_GYRO_PREVIEW_CROP_SCALE,
            MAX_GYRO_PREVIEW_CROP_SCALE,
        ) ?: return
        val maxShift = parseFloat(
            "最大平移",
            gyroPreviewMaxShiftInput,
            MIN_GYRO_PREVIEW_MAX_SHIFT,
            MAX_GYRO_PREVIEW_MAX_SHIFT,
        ) ?: return
        val softBoundaryStartRatio = parseFloat(
            "软边界开始比例",
            gyroPreviewSoftBoundaryStartRatioInput,
            MIN_GYRO_PREVIEW_SOFT_BOUNDARY_START_RATIO,
            MAX_GYRO_PREVIEW_SOFT_BOUNDARY_START_RATIO,
        ) ?: return
        val softBoundaryStrength = parseFloat(
            "软边界强度",
            gyroPreviewSoftBoundaryStrengthInput,
            MIN_GYRO_PREVIEW_SOFT_BOUNDARY_STRENGTH,
            MAX_GYRO_PREVIEW_SOFT_BOUNDARY_STRENGTH,
        ) ?: return
        val reAimRpsThreshold = if (gyroPreviewReAimEnabledInput) {
            parseFloat(
                "重瞄转速阈值",
                gyroPreviewReAimRpsThresholdInput,
                MIN_GYRO_PREVIEW_RE_AIM_RPS_THRESHOLD,
                MAX_GYRO_PREVIEW_RE_AIM_RPS_THRESHOLD,
            ) ?: return
        } else {
            gyroPreviewReAimRpsThresholdInput.trim().toFloatOrNull()
                ?: DEFAULT_GYRO_PREVIEW_CONFIG.reAimRpsThreshold
        }
        val reAimResidualEnterRatio = if (gyroPreviewReAimEnabledInput) {
            parseFloat(
                "重瞄进入残留",
                gyroPreviewReAimResidualEnterRatioInput,
                MIN_GYRO_PREVIEW_RE_AIM_RATIO,
                MAX_GYRO_PREVIEW_RE_AIM_RATIO,
            ) ?: return
        } else {
            gyroPreviewReAimResidualEnterRatioInput.trim().toFloatOrNull()
                ?: DEFAULT_GYRO_PREVIEW_CONFIG.reAimResidualEnterRatio
        }
        val reAimResidualExitRatioInputValue = if (gyroPreviewReAimEnabledInput) {
            parseFloat(
                "重瞄退出残留",
                gyroPreviewReAimResidualExitRatioInput,
                MIN_GYRO_PREVIEW_RE_AIM_RATIO,
                MAX_GYRO_PREVIEW_RE_AIM_RATIO,
            ) ?: return
        } else {
            gyroPreviewReAimResidualExitRatioInput.trim().toFloatOrNull()
                ?: DEFAULT_GYRO_PREVIEW_CONFIG.reAimResidualExitRatio
        }
        if (gyroPreviewReAimEnabledInput && reAimResidualExitRatioInputValue >= reAimResidualEnterRatio) {
            gyroPreviewSettingsError = "重瞄退出残留必须小于进入残留"
            return
        }
        val reAimResidualExitRatio = min(
            reAimResidualExitRatioInputValue.coerceIn(
                MIN_GYRO_PREVIEW_RE_AIM_RATIO,
                MAX_GYRO_PREVIEW_RE_AIM_RATIO,
            ),
            reAimResidualEnterRatio - 0.01f,
        ).coerceAtLeast(0f)
        val reAimStillMs = if (gyroPreviewReAimEnabledInput) {
            parseFloat(
                "重瞄停稳时间",
                gyroPreviewReAimStillMsInput,
                MIN_GYRO_PREVIEW_RE_AIM_STILL_MS,
                MAX_GYRO_PREVIEW_RE_AIM_STILL_MS,
            ) ?: return
        } else {
            gyroPreviewReAimStillMsInput.trim().toFloatOrNull() ?: DEFAULT_GYRO_PREVIEW_CONFIG.reAimStillMs
        }
        val reAimPathFollow = if (gyroPreviewReAimEnabledInput) {
            parseFloat(
                "重瞄跟随速度",
                gyroPreviewReAimPathFollowInput,
                MIN_GYRO_PREVIEW_RE_AIM_PATH_FOLLOW,
                MAX_GYRO_PREVIEW_RE_AIM_PATH_FOLLOW,
            ) ?: return
        } else {
            gyroPreviewReAimPathFollowInput.trim().toFloatOrNull()
                ?: DEFAULT_GYRO_PREVIEW_CONFIG.reAimPathFollowPerSecond
        }
        val reAimBlendIn = if (gyroPreviewReAimEnabledInput) {
            parseFloat(
                "重瞄混入速度",
                gyroPreviewReAimBlendInInput,
                MIN_GYRO_PREVIEW_RE_AIM_BLEND_RATE,
                MAX_GYRO_PREVIEW_RE_AIM_BLEND_RATE,
            ) ?: return
        } else {
            gyroPreviewReAimBlendInInput.trim().toFloatOrNull()
                ?: DEFAULT_GYRO_PREVIEW_CONFIG.reAimBlendInPerSecond
        }
        val reAimBlendOut = if (gyroPreviewReAimEnabledInput) {
            parseFloat(
                "重瞄混出速度",
                gyroPreviewReAimBlendOutInput,
                MIN_GYRO_PREVIEW_RE_AIM_BLEND_RATE,
                MAX_GYRO_PREVIEW_RE_AIM_BLEND_RATE,
            ) ?: return
        } else {
            gyroPreviewReAimBlendOutInput.trim().toFloatOrNull()
                ?: DEFAULT_GYRO_PREVIEW_CONFIG.reAimBlendOutPerSecond
        }
        val deadband = parseFloat(
            "像素死区",
            gyroPreviewDeadbandInput,
            MIN_GYRO_PREVIEW_DEADBAND_PX,
            MAX_GYRO_PREVIEW_DEADBAND_PX,
        ) ?: return
        val horizontalGain = parseFloat(
            "横向增益",
            gyroPreviewHorizontalGainInput,
            MIN_GYRO_PREVIEW_AXIS_GAIN,
            MAX_GYRO_PREVIEW_AXIS_GAIN,
        ) ?: return
        val verticalGain = parseFloat(
            "纵向增益",
            gyroPreviewVerticalGainInput,
            MIN_GYRO_PREVIEW_AXIS_GAIN,
            MAX_GYRO_PREVIEW_AXIS_GAIN,
        ) ?: return
        val aimRpsThreshold = parseFloat(
            "瞄准转速阈值",
            gyroPreviewAimRpsThresholdInput,
            MIN_GYRO_PREVIEW_AIM_RPS_THRESHOLD,
            MAX_GYRO_PREVIEW_AIM_RPS_THRESHOLD,
        ) ?: return
        val aimStrengthMultiplier = parseFloat(
            "瞄准强度倍数",
            gyroPreviewAimStrengthMultiplierInput,
            MIN_GYRO_PREVIEW_AIM_STRENGTH_MULTIPLIER,
            MAX_GYRO_PREVIEW_AIM_STRENGTH_MULTIPLIER,
        ) ?: return
        val aimPathFollow = parseFloat(
            "瞄准跟随速度",
            gyroPreviewAimPathFollowInput,
            MIN_GYRO_PREVIEW_AIM_PATH_FOLLOW,
            MAX_GYRO_PREVIEW_AIM_PATH_FOLLOW,
        ) ?: return
        val aimDeadband = parseFloat(
            "瞄准死区",
            gyroPreviewAimDeadbandInput,
            MIN_GYRO_PREVIEW_AIM_DEADBAND_PX,
            MAX_GYRO_PREVIEW_AIM_DEADBAND_PX,
        ) ?: return
        val aimOutputRateLimit = parseFloat(
            "瞄准限速",
            gyroPreviewAimOutputRateLimitInput,
            MIN_GYRO_PREVIEW_AIM_OUTPUT_RATE_LIMIT,
            MAX_GYRO_PREVIEW_AIM_OUTPUT_RATE_LIMIT,
        ) ?: return
        val aimOutputAccelerationLimit = parseFloat(
            "瞄准加速度",
            gyroPreviewAimOutputAccelerationLimitInput,
            MIN_GYRO_PREVIEW_AIM_OUTPUT_ACCELERATION_LIMIT,
            MAX_GYRO_PREVIEW_AIM_OUTPUT_ACCELERATION_LIMIT,
        ) ?: return
        val residualReleaseRpsThreshold = parseFloat(
            "残留释放转速",
            gyroPreviewResidualReleaseRpsThresholdInput,
            MIN_GYRO_PREVIEW_RESIDUAL_RELEASE_RPS_THRESHOLD,
            MAX_GYRO_PREVIEW_RESIDUAL_RELEASE_RPS_THRESHOLD,
        ) ?: return
        val zoomCompensationStrength = parseFloat(
            "倍率补偿强度",
            gyroPreviewZoomCompensationStrengthInput,
            MIN_GYRO_PREVIEW_ZOOM_COMPENSATION_STRENGTH,
            MAX_GYRO_PREVIEW_ZOOM_COMPENSATION_STRENGTH,
        ) ?: return
        val maxCompensationZoom = parseFloat(
            "最大补偿倍率",
            gyroPreviewMaxCompensationZoomInput,
            MIN_GYRO_PREVIEW_MAX_COMPENSATION_ZOOM,
            MAX_GYRO_PREVIEW_MAX_COMPENSATION_ZOOM,
        ) ?: return
        val frameTimestampOffsetMs = parseFloat(
            "帧时间偏移",
            gyroPreviewFrameTimestampOffsetInput,
            MIN_GYRO_PREVIEW_FRAME_TIMESTAMP_OFFSET_MS,
            MAX_GYRO_PREVIEW_FRAME_TIMESTAMP_OFFSET_MS,
        ) ?: return
        val rollStrength = parseFloat(
            "Roll强度",
            gyroPreviewRollStrengthInput,
            MIN_GYRO_PREVIEW_ROLL_STRENGTH,
            MAX_GYRO_PREVIEW_ROLL_STRENGTH,
        ) ?: return
        val maxRollDegrees = parseFloat(
            "Roll最大角度",
            gyroPreviewMaxRollDegreesInput,
            MIN_GYRO_PREVIEW_MAX_ROLL_DEGREES,
            MAX_GYRO_PREVIEW_MAX_ROLL_DEGREES,
        ) ?: return

        val config = GyroPreviewStabilizer.Config(
            strength = strength,
            pathFollowPerSecond = pathFollow,
            outputLowPassAlpha = outputAlpha,
            outputRateLimitPxPerSecond = outputRateLimit,
            outputAccelerationLimitPxPerSecond2 = outputAccelerationLimit,
            cropScale = cropScale,
            maxShiftRatio = maxShift,
            softBoundaryEnabled = gyroPreviewSoftBoundaryEnabledInput,
            softBoundaryStartRatio = softBoundaryStartRatio,
            softBoundaryStrength = softBoundaryStrength,
            reAimEnabled = gyroPreviewReAimEnabledInput,
            reAimRpsThreshold = reAimRpsThreshold,
            reAimResidualEnterRatio = reAimResidualEnterRatio,
            reAimResidualExitRatio = reAimResidualExitRatio,
            reAimStillMs = reAimStillMs,
            reAimPathFollowPerSecond = reAimPathFollow,
            reAimBlendInPerSecond = reAimBlendIn,
            reAimBlendOutPerSecond = reAimBlendOut,
            deadbandPx = deadband,
            horizontalGain = horizontalGain,
            verticalGain = verticalGain,
            aimStabilizationEnabled = gyroPreviewAimStabilizationEnabledInput,
            aimRpsThreshold = aimRpsThreshold,
            aimStrengthMultiplier = aimStrengthMultiplier,
            aimPathFollowPerSecond = aimPathFollow,
            aimDeadbandPx = aimDeadband,
            aimOutputRateLimitPxPerSecond = aimOutputRateLimit,
            aimOutputAccelerationLimitPxPerSecond2 = aimOutputAccelerationLimit,
            residualReleaseRpsThreshold = residualReleaseRpsThreshold,
            zoomCompensationEnabled = gyroPreviewZoomCompensationEnabledInput,
            zoomCompensationStrength = zoomCompensationStrength,
            maxCompensationZoom = maxCompensationZoom,
            frameSyncEnabled = gyroPreviewFrameSyncEnabledInput,
            frameTimestampOffsetMs = frameTimestampOffsetMs,
            rollCompensationEnabled = gyroPreviewRollCompensationEnabledInput,
            rollStrength = rollStrength,
            maxRollDegrees = maxRollDegrees,
            invertRoll = gyroPreviewInvertRollInput,
            swapPitchYawAxes = gyroPreviewSwapAxesInput,
            invertHorizontal = gyroPreviewInvertHorizontalInput,
            invertVertical = gyroPreviewInvertVerticalInput,
        )
        val glRenderChanged = gyroPreviewGlRenderEnabled != gyroPreviewGlRenderEnabledInput

        gyroPreviewStrength = config.strength
        gyroPreviewPathFollow = config.pathFollowPerSecond
        gyroPreviewOutputAlpha = config.outputLowPassAlpha
        gyroPreviewOutputRateLimit = config.outputRateLimitPxPerSecond
        gyroPreviewOutputAccelerationLimit = config.outputAccelerationLimitPxPerSecond2
        gyroPreviewCropScale = config.cropScale
        gyroPreviewMaxShift = config.maxShiftRatio
        gyroPreviewSoftBoundaryEnabled = config.softBoundaryEnabled
        gyroPreviewSoftBoundaryStartRatio = config.softBoundaryStartRatio
        gyroPreviewSoftBoundaryStrength = config.softBoundaryStrength
        gyroPreviewReAimEnabled = config.reAimEnabled
        gyroPreviewReAimRpsThreshold = config.reAimRpsThreshold
        gyroPreviewReAimResidualEnterRatio = config.reAimResidualEnterRatio
        gyroPreviewReAimResidualExitRatio = config.reAimResidualExitRatio
        gyroPreviewReAimStillMs = config.reAimStillMs
        gyroPreviewReAimPathFollow = config.reAimPathFollowPerSecond
        gyroPreviewReAimBlendIn = config.reAimBlendInPerSecond
        gyroPreviewReAimBlendOut = config.reAimBlendOutPerSecond
        gyroPreviewDeadbandPx = config.deadbandPx
        gyroPreviewHorizontalGain = config.horizontalGain
        gyroPreviewVerticalGain = config.verticalGain
        gyroPreviewAimStabilizationEnabled = config.aimStabilizationEnabled
        gyroPreviewAimRpsThreshold = config.aimRpsThreshold
        gyroPreviewAimStrengthMultiplier = config.aimStrengthMultiplier
        gyroPreviewAimPathFollow = config.aimPathFollowPerSecond
        gyroPreviewAimDeadbandPx = config.aimDeadbandPx
        gyroPreviewAimOutputRateLimit = config.aimOutputRateLimitPxPerSecond
        gyroPreviewAimOutputAccelerationLimit = config.aimOutputAccelerationLimitPxPerSecond2
        gyroPreviewResidualReleaseRpsThreshold = config.residualReleaseRpsThreshold
        gyroPreviewZoomCompensationEnabled = config.zoomCompensationEnabled
        gyroPreviewZoomCompensationStrength = config.zoomCompensationStrength
        gyroPreviewMaxCompensationZoom = config.maxCompensationZoom
        gyroPreviewFrameSyncEnabled = config.frameSyncEnabled
        gyroPreviewFrameTimestampOffsetMs = config.frameTimestampOffsetMs
        gyroPreviewGlRenderEnabled = gyroPreviewGlRenderEnabledInput
        gyroPreviewDiagnosticsEnabled = gyroPreviewDiagnosticsEnabledInput
        gyroStabilizationCalibrationFineSampleEnabled = gyroStabilizationCalibrationFineSampleInput
        gyroPreviewRollCompensationEnabled = config.rollCompensationEnabled
        gyroPreviewRollStrength = config.rollStrength
        gyroPreviewMaxRollDegrees = config.maxRollDegrees
        gyroPreviewInvertRoll = config.invertRoll
        gyroPreviewSwapAxes = config.swapPitchYawAxes
        gyroPreviewInvertHorizontal = config.invertHorizontal
        gyroPreviewInvertVertical = config.invertVertical

        cameraPrefs.edit()
            .putInt(KEY_GYRO_PREVIEW_CONFIG_VERSION, GYRO_PREVIEW_CONFIG_VERSION)
            .putFloat(KEY_GYRO_PREVIEW_STRENGTH, config.strength)
            .putFloat(KEY_GYRO_PREVIEW_PATH_FOLLOW, config.pathFollowPerSecond)
            .putFloat(KEY_GYRO_PREVIEW_OUTPUT_ALPHA, config.outputLowPassAlpha)
            .putFloat(KEY_GYRO_PREVIEW_OUTPUT_RATE_LIMIT, config.outputRateLimitPxPerSecond)
            .putFloat(KEY_GYRO_PREVIEW_OUTPUT_ACCELERATION_LIMIT, config.outputAccelerationLimitPxPerSecond2)
            .putFloat(KEY_GYRO_PREVIEW_CROP_SCALE, config.cropScale)
            .putFloat(KEY_GYRO_PREVIEW_MAX_SHIFT, config.maxShiftRatio)
            .putBoolean(KEY_GYRO_PREVIEW_SOFT_BOUNDARY_ENABLED, config.softBoundaryEnabled)
            .putFloat(KEY_GYRO_PREVIEW_SOFT_BOUNDARY_START_RATIO, config.softBoundaryStartRatio)
            .putFloat(KEY_GYRO_PREVIEW_SOFT_BOUNDARY_STRENGTH, config.softBoundaryStrength)
            .putBoolean(KEY_GYRO_PREVIEW_RE_AIM_ENABLED, config.reAimEnabled)
            .putFloat(KEY_GYRO_PREVIEW_RE_AIM_RPS_THRESHOLD, config.reAimRpsThreshold)
            .putFloat(KEY_GYRO_PREVIEW_RE_AIM_RESIDUAL_ENTER_RATIO, config.reAimResidualEnterRatio)
            .putFloat(KEY_GYRO_PREVIEW_RE_AIM_RESIDUAL_EXIT_RATIO, config.reAimResidualExitRatio)
            .putFloat(KEY_GYRO_PREVIEW_RE_AIM_STILL_MS, config.reAimStillMs)
            .putFloat(KEY_GYRO_PREVIEW_RE_AIM_PATH_FOLLOW, config.reAimPathFollowPerSecond)
            .putFloat(KEY_GYRO_PREVIEW_RE_AIM_BLEND_IN, config.reAimBlendInPerSecond)
            .putFloat(KEY_GYRO_PREVIEW_RE_AIM_BLEND_OUT, config.reAimBlendOutPerSecond)
            .putFloat(KEY_GYRO_PREVIEW_DEADBAND_PX, config.deadbandPx)
            .putFloat(KEY_GYRO_PREVIEW_HORIZONTAL_GAIN, config.horizontalGain)
            .putFloat(KEY_GYRO_PREVIEW_VERTICAL_GAIN, config.verticalGain)
            .putBoolean(KEY_GYRO_PREVIEW_AIM_STABILIZATION_ENABLED, config.aimStabilizationEnabled)
            .putFloat(KEY_GYRO_PREVIEW_AIM_RPS_THRESHOLD, config.aimRpsThreshold)
            .putFloat(KEY_GYRO_PREVIEW_AIM_STRENGTH_MULTIPLIER, config.aimStrengthMultiplier)
            .putFloat(KEY_GYRO_PREVIEW_AIM_PATH_FOLLOW, config.aimPathFollowPerSecond)
            .putFloat(KEY_GYRO_PREVIEW_AIM_DEADBAND_PX, config.aimDeadbandPx)
            .putFloat(KEY_GYRO_PREVIEW_AIM_OUTPUT_RATE_LIMIT, config.aimOutputRateLimitPxPerSecond)
            .putFloat(
                KEY_GYRO_PREVIEW_AIM_OUTPUT_ACCELERATION_LIMIT,
                config.aimOutputAccelerationLimitPxPerSecond2,
            )
            .putFloat(
                KEY_GYRO_PREVIEW_RESIDUAL_RELEASE_RPS_THRESHOLD,
                config.residualReleaseRpsThreshold,
            )
            .putBoolean(KEY_GYRO_PREVIEW_ZOOM_COMPENSATION_ENABLED, config.zoomCompensationEnabled)
            .putFloat(KEY_GYRO_PREVIEW_ZOOM_COMPENSATION_STRENGTH, config.zoomCompensationStrength)
            .putFloat(KEY_GYRO_PREVIEW_MAX_COMPENSATION_ZOOM, config.maxCompensationZoom)
            .putBoolean(KEY_GYRO_PREVIEW_FRAME_SYNC_ENABLED, config.frameSyncEnabled)
            .putFloat(KEY_GYRO_PREVIEW_FRAME_TIMESTAMP_OFFSET_MS, config.frameTimestampOffsetMs)
            .putBoolean(KEY_GYRO_PREVIEW_GL_RENDER_ENABLED, gyroPreviewGlRenderEnabledInput)
            .putBoolean(KEY_GYRO_PREVIEW_DIAGNOSTICS_ENABLED, gyroPreviewDiagnosticsEnabledInput)
            .putBoolean(KEY_GYRO_STABILIZATION_CALIBRATION_FINE_SAMPLE_ENABLED, gyroStabilizationCalibrationFineSampleInput)
            .putBoolean(KEY_GYRO_PREVIEW_ROLL_COMPENSATION_ENABLED, config.rollCompensationEnabled)
            .putFloat(KEY_GYRO_PREVIEW_ROLL_STRENGTH, config.rollStrength)
            .putFloat(KEY_GYRO_PREVIEW_MAX_ROLL_DEGREES, config.maxRollDegrees)
            .putBoolean(KEY_GYRO_PREVIEW_INVERT_ROLL, config.invertRoll)
            .putBoolean(KEY_GYRO_PREVIEW_SWAP_AXES, config.swapPitchYawAxes)
            .putBoolean(KEY_GYRO_PREVIEW_INVERT_HORIZONTAL, config.invertHorizontal)
            .putBoolean(KEY_GYRO_PREVIEW_INVERT_VERTICAL, config.invertVertical)
            .apply()

        gyroPreviewStabilizer.updateConfig(config)
        gyroStabilizationCalibrationFrame = null
        gyroStabilizationCalibrationHints.clear()
        if (gyroPreviewGlRenderEnabledInput) {
            detectionEnabled = false
            detectionBoxes = emptyList()
            detectionPerfText = ""
            showMeasurePrompt = false
            measureTrigger = false
            wasTargetInArea = false
            statMeasuring = false
            showCalPanel = false
            showDualCalPanel = false
            laserCompareEnabled = false
            showLaserComparePanel = false
            laserCompareText = "GL逐帧预览实验已开启，绿光识别已暂停"
        }
        if (glRenderChanged) {
            cameraController.closeCamera()
            isCameraReady = false
            surfaceAvailable = false
            previewSurfaceTextureRef = null
            textureViewRef = null
            glPreviewViewRef = null
        }
        gyroPreviewStabilizer.reset()
        gyroPreviewTransform = GyroPreviewStabilizer.Transform.Identity
        gyroPreviewLastMatrixUpdateMs = 0L
        gyroPreviewLastOverlayUpdateMs = 0L
        gyroPreviewLastHudUpdateMs = 0L
        gyroPreviewLastModelLogMs = 0L
        if (!gyroPreviewStabilizationEnabled) {
            gyroPreviewText = ""
        }
        showGyroPreviewSettingsDialog = false
        val renderText = if (gyroPreviewGlRenderEnabledInput) "GL逐帧预览" else "TextureView矩阵"
        val calibMode = stabilizationCalibrationSampleMode()
        statusText = "自研EIS参数已保存：${gyroPreviewAxisText()} ${if (config.frameSyncEnabled) "帧同步" else "陀螺实时"} $renderText 标定${calibMode.label}"
        Log.i("CameraScreen", "gyro-preview-config: $config")
    }

    fun resetGyroPreviewSettingInputsToDefault() {
        fillGyroPreviewSettingInputs(DEFAULT_GYRO_PREVIEW_CONFIG)
        gyroPreviewGlRenderEnabledInput = false
        gyroPreviewDiagnosticsEnabledInput = false
        gyroStabilizationCalibrationFineSampleInput = false
    }

    fun openYoloFilterDialog() {
        yoloTopDetectionEnabledInput = yoloTopDetectionEnabled
        yoloConfidenceThresholdInput = formatYoloConfidence(yoloTopConfidenceThreshold)
        yoloFilterError = null
        showYoloFilterDialog = true
    }

    fun saveYoloFilterSetting() {
        val threshold = yoloConfidenceThresholdInput.trim().toFloatOrNull()
        if (yoloTopDetectionEnabledInput &&
            (threshold == null ||
                threshold !in YoloPostprocessOptions.MIN_CONFIDENCE_THRESHOLD..YoloPostprocessOptions.MAX_CONFIDENCE_THRESHOLD)
        ) {
            yoloFilterError = "阈值请输入 0.00-1.00"
            return
        }
        val normalizedThreshold = (threshold ?: yoloTopConfidenceThreshold).coerceIn(
            YoloPostprocessOptions.MIN_CONFIDENCE_THRESHOLD,
            YoloPostprocessOptions.MAX_CONFIDENCE_THRESHOLD,
        )
        yoloTopDetectionEnabled = yoloTopDetectionEnabledInput
        yoloTopConfidenceThreshold = normalizedThreshold
        cameraPrefs.edit()
            .putBoolean(KEY_YOLO_TOP_DETECTION_ENABLED, yoloTopDetectionEnabledInput)
            .putFloat(KEY_YOLO_TOP_CONFIDENCE_THRESHOLD, normalizedThreshold)
            .apply()
        showYoloFilterDialog = false
        statusText = if (yoloTopDetectionEnabledInput) {
            "YOLO单框：阈值 ${formatYoloConfidence(normalizedThreshold)}"
        } else {
            "YOLO多框：原始阈值 ${formatYoloConfidence(YoloPostprocessOptions.ORIGINAL_CONFIDENCE_THRESHOLD)}"
        }
        Log.d(
            "CameraScreen",
            "yoloFilter: topOnly=$yoloTopDetectionEnabledInput threshold=$normalizedThreshold",
        )
    }

    fun resetGyroPreviewRenderTransformToBase() {
        val wrapper = wrapperRef ?: return
        val viewWidth = wrapper.width.toFloat()
        val viewHeight = wrapper.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val baseScale = textureBaseScale.coerceAtLeast(1f)
        val tx = textureBaseTx
        val ty = textureBaseTy
        if (gyroPreviewGlRenderEnabled) {
            textureViewRef?.setTransform(Matrix())
            glPreviewViewRef?.setPreviewTransform(
                scale = baseScale,
                translationX = tx,
                translationY = ty,
                rotationDegrees = 0f,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
            )
        } else {
            glPreviewViewRef?.resetPreviewTransform()
            val matrix = Matrix()
            if (baseScale != 1f || tx != 0f || ty != 0f) {
                matrix.setScale(baseScale, baseScale, viewWidth / 2f, viewHeight / 2f)
                matrix.postTranslate(tx, ty)
            }
            textureViewRef?.setTransform(matrix)
        }
        wrapperTx = tx
        wrapperTy = ty
        wrapperScale = baseScale
        wrapperRotation = 0f
    }

    fun resetGyroPreviewStabilization() {
        gyroPreviewStabilizer.reset()
        gyroPreviewTransform = GyroPreviewStabilizer.Transform.Identity
        gyroPreviewLastMatrixUpdateMs = 0L
        gyroPreviewLastOverlayUpdateMs = 0L
        gyroPreviewLastHudUpdateMs = 0L
        gyroPreviewLastModelLogMs = 0L
        if (!gyroPreviewStabilizationEnabled) {
            gyroPreviewText = ""
        }
        resetGyroPreviewRenderTransformToBase()
    }

    fun toggleGyroPreviewStabilization() {
        val next = !gyroPreviewStabilizationEnabled
        if (next && gyroscopeSensor == null) {
            statusText = "当前设备没有陀螺仪，无法开启自研EIS"
            Log.w("CameraScreen", "gyro-preview-stab: no TYPE_GYROSCOPE")
            return
        }
        gyroPreviewStabilizationEnabled = next
        cameraPrefs.edit()
            .putBoolean(KEY_GYRO_PREVIEW_STABILIZATION_ENABLED, next)
            .apply()
        resetGyroPreviewStabilization()
        statusText = if (next) {
            val syncText = if (gyroPreviewFrameSyncEnabled) "帧同步轨迹" else "陀螺实时轨迹"
            val renderText = if (gyroPreviewGlRenderEnabled) "GL逐帧预览" else "TextureView矩阵"
            "自研EIS已开启：$renderText + $syncText"
        } else {
            "自研EIS已关闭"
        }
        Log.i("CameraScreen", "gyro-preview-stab: enabled=$next")
    }

    fun toggleGyroStabilizationCalibration() {
        val next = !gyroStabilizationCalibrationEnabled
        if (next && gyroPreviewGlRenderEnabled) {
            statusText = "防抖标定暂只支持 TextureView 预览，先关闭 GL逐帧预览"
            return
        }
        val tv = textureViewRef
        if (next && (tv == null || !tv.isAvailable)) {
            statusText = "防抖标定：预览未就绪"
            return
        }
        gyroStabilizationCalibrationEnabled = next
        cameraPrefs.edit()
            .putBoolean(KEY_GYRO_STABILIZATION_CALIBRATION_ENABLED, next)
            .apply()
        gyroStabilizationCalibrationFrame = null
        gyroStabilizationCalibrationHints.clear()
        gyroStabilizationCalibrationText = if (next) "防抖标定: 等待预览采样" else ""
        statusText = if (next) {
            val mode = stabilizationCalibrationSampleMode()
            "防抖标定已开启：${mode.label} ${mode.sampleWidth}px"
        } else {
            "防抖标定已关闭"
        }
        Log.i("CameraScreen", "gyro-calib: enabled=$next")
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
                if (gyroPreviewGlRenderEnabled) {
                    throw IllegalStateException("GL逐帧预览实验不提供 TextureView 截图校准")
                }
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
        if (gyroPreviewGlRenderEnabled) {
            laserCompareEnabled = false
            showLaserComparePanel = false
            laserCompareText = "GL逐帧预览实验下不运行绿光识别"
            statusText = "GL逐帧预览实验已开启：绿光识别不可用"
            return
        }
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
        if (gyroPreviewGlRenderEnabled) {
            laserCompareEnabled = false
            laserCompareText = "GL逐帧预览实验下不运行绿光识别"
            statusText = "GL逐帧预览实验已开启：绿光识别不可用"
            return
        }
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

    fun activeLensDebugText(): String {
        val activeId = activePhysicalCameraId
        val activeLens = lensByCameraId(activeId)
        val activeText = if (activeId != null && activeLens != null) {
            String.format(Locale.US, "active=%s/%.0fmm", activeId, activeLens.eq35mm)
        } else if (activePhysicalLensReported) {
            "active=${activeId ?: "not_reported"}"
        } else {
            "active=pending"
        }
        val selected = selectedLens
        val selectedText = selected?.let {
            String.format(
                Locale.US,
                "selected=%s/%.0fmm",
                it.physicalId ?: it.cameraId,
                it.eq35mm,
            )
        } ?: "selected=none"
        return String.format(Locale.US, "%s %s zoom=%.2fx", activeText, selectedText, currentEquivRatio)
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

    fun isUiZoomActive(): Boolean = uiZoomOnlyEnabled && !isDualCalibMode

    fun opticalBaseForLens(lens: LensCandidate? = selectedLens): Float {
        if (lens == null) return 1f
        val wide = findWideLensInSameLogicalCamera(lens, lensCandidates) ?: return 1f
        if (lens.eq35mm <= 0f || wide.eq35mm <= 0f) return 1f

        // 当前物理镜头在 Camera2 1x 输出时已经带有自身光学底倍。
        // 光学底倍按镜头显示语义四舍五入，避免 4.86x 这类浮点值导致 UI 放大和用户认知的 5x 不一致。
        // 纯 UI 放大只补足 targetEquiv / roundedOpticalBase 的差值，例如 5x 物理镜头显示 7x 时，UI scale 为 7 / 5 = 1.4。
        return (lens.eq35mm / wide.eq35mm).roundToInt().coerceAtLeast(1).toFloat()
    }

    fun normalMinZoomFor(lens: LensCandidate?): Float {
        if (lens == null) return 1f
        val rawOpticalBase = opticalBaseForLens(lens)
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
            if (isUiZoomActive()) {
                anchor
            } else {
                centerFromAnchorAtZoom(anchor, zoom)
            }
        } else {
            null
        }
    }

    fun minCoverScaleForFocus(focusX: Float, focusY: Float, width: Float, height: Float): Float {
        val minLR = max(1f, min(focusX, width - focusX))
        val minTB = max(1f, min(focusY, height - focusY))
        return max(1f, max(width / (2f * minLR), height / (2f * minTB)))
    }

    fun applyTextureMatrixZoom(
        textureView: TextureView?,
        scale: Float,
        translationX: Float,
        translationY: Float,
        pivotX: Float,
        pivotY: Float,
        rotationDegrees: Float = 0f,
    ) {
        val tv = textureView ?: return
        val matrix = Matrix()
        if (scale != 1f || translationX != 0f || translationY != 0f || rotationDegrees != 0f) {
            // Matrix 坐标映射：
            // 1. 先以屏幕中心 pivot 做缩放：p1 = pivot + (p0 - pivot) * scale。
            // 2. 如开启 Roll 补偿，再围绕屏幕中心做小角度反向旋转。
            // 3. 再追加平移：p3 = p2 + translation。
            // 当需要把绿光/校准点 focus 移到屏幕中心时，
            // translation = (center - focus) * scale，可让 p3(focus) 接近 center。
            matrix.setScale(scale, scale, pivotX, pivotY)
            matrix.postRotate(rotationDegrees, pivotX, pivotY)
            matrix.postTranslate(translationX, translationY)
        }
        tv.setTransform(matrix)
    }

    fun resetTextureMatrixZoom(textureView: TextureView?) {
        textureView?.setTransform(Matrix())
    }

    fun resetPreviewRenderTransform() {
        resetTextureMatrixZoom(textureViewRef)
        glPreviewViewRef?.resetPreviewTransform()
    }

    fun mapPreviewPointToScreen(
        x: Float,
        y: Float,
        centerX: Float,
        centerY: Float,
        scale: Float,
        rotationDegrees: Float,
        translationX: Float,
        translationY: Float,
    ): Pair<Float, Float> {
        val scaledX = (x - centerX) * scale
        val scaledY = (y - centerY) * scale
        if (rotationDegrees == 0f) {
            return Pair(centerX + scaledX + translationX, centerY + scaledY + translationY)
        }
        val rad = Math.toRadians(rotationDegrees.toDouble())
        val cosValue = kotlin.math.cos(rad).toFloat()
        val sinValue = kotlin.math.sin(rad).toFloat()
        val rotatedX = scaledX * cosValue - scaledY * sinValue
        val rotatedY = scaledX * sinValue + scaledY * cosValue
        return Pair(centerX + rotatedX + translationX, centerY + rotatedY + translationY)
    }

    fun applyPreviewTextureTransform(
        stabilization: GyroPreviewStabilizer.Transform = gyroPreviewTransform,
        syncOverlay: Boolean = true,
    ) {
        val wrapper = wrapperRef ?: return
        val viewWidth = wrapper.width.toFloat()
        val viewHeight = wrapper.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val stabilizationAllowed = gyroPreviewStabilizationEnabled &&
            !showCalPanel &&
            !showDualCalPanel &&
            (!isDualCalibMode || anchorActive)
        val activeStabilization = if (stabilizationAllowed) {
            stabilization
        } else {
            GyroPreviewStabilizer.Transform.Identity
        }
        val baseScale = textureBaseScale.coerceAtLeast(1f)
        val stabilizationScale = activeStabilization.scale.coerceAtLeast(1f)
        val scale = baseScale * stabilizationScale
        val baseTranslationScale = scale / baseScale
        val tx = textureBaseTx * baseTranslationScale + activeStabilization.translationX
        val ty = textureBaseTy * baseTranslationScale + activeStabilization.translationY
        if (gyroPreviewGlRenderEnabled) {
            resetTextureMatrixZoom(textureViewRef)
            glPreviewViewRef?.setPreviewTransform(
                scale = scale,
                translationX = tx,
                translationY = ty,
                rotationDegrees = activeStabilization.rotationDegrees,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
            )
        } else {
            glPreviewViewRef?.resetPreviewTransform()
            applyTextureMatrixZoom(
                textureViewRef,
                scale,
                tx,
                ty,
                viewWidth / 2f,
                viewHeight / 2f,
                rotationDegrees = activeStabilization.rotationDegrees,
            )
        }
        if (syncOverlay) {
            wrapperTx = tx
            wrapperTy = ty
            wrapperScale = scale
            wrapperRotation = activeStabilization.rotationDegrees
        }
    }

    fun canApplyGyroPreviewStabilization(): Boolean {
        return gyroPreviewStabilizationEnabled &&
            !showCalPanel &&
            !showDualCalPanel &&
            (!isDualCalibMode || anchorActive)
    }

    fun uiCameraBaseZoomForLens(lens: LensCandidate? = selectedLens): Float {
        return normalMinZoomFor(lens).coerceAtLeast(1f)
    }

    fun uiZoomDisplayScale(): Float {
        return if (isUiZoomActive()) {
            val cameraBase = uiCameraBaseZoomForLens()
            (currentEquivRatio.coerceAtLeast(cameraBase) / cameraBase).coerceAtLeast(1f)
        } else {
            1f
        }
    }

    fun cameraZoomForMode(equivRatio: Float = currentEquivRatio): Float {
        return if (isDualCalibMode && isUsingDualTele) {
            1f
        } else if (isDualCalibMode) {
            equivRatio.coerceAtLeast(1f)
        } else if (uiZoomOnlyEnabled) {
            uiCameraBaseZoomForLens(selectedLens)
        } else {
            clampNormalZoom(equivRatio, selectedLens)
        }
    }

    fun applyCameraZoomForMode(equivRatio: Float = currentEquivRatio) {
        cameraController.setZoomRatio(cameraZoomForMode(equivRatio))
    }

    fun gyroPreviewDigitalZoomRatio(): Float {
        val opticalBase = opticalBaseForLens().coerceAtLeast(1f)
        val actualZoom = currentEquivRatio.coerceAtLeast(opticalBase)
        return (actualZoom / opticalBase).coerceAtLeast(1f)
    }

    fun gyroPreviewDisplayZoomRatio(): Float {
        val baseScale = textureBaseScale.coerceAtLeast(1f)
        val cropScale = gyroPreviewCropScale.coerceAtLeast(1f)
        val includedUiScale = if (uiZoomOnlyEnabled && !(isDualCalibMode && isUsingDualTele)) {
            uiZoomDisplayScale().coerceAtLeast(1f)
        } else {
            1f
        }
        val extraBaseScale = (baseScale / includedUiScale).coerceAtLeast(1f)
        return (extraBaseScale * cropScale).coerceAtLeast(1f)
    }

    fun gyroPreviewRenderScale(): Float {
        return (textureBaseScale.coerceAtLeast(1f) * gyroPreviewCropScale.coerceAtLeast(1f))
            .coerceAtLeast(1f)
    }

    fun gyroPreviewLensProjection(
        viewWidth: Float,
        viewHeight: Float,
        digitalZoomRatio: Float = 1f,
    ): GyroLensProjection {
        val lens = selectedLens ?: return GyroLensProjection()
        val focalMm = lens.focalLength.takeIf { it > 0f } ?: return GyroLensProjection()
        val sensorWidthMm = lens.sensorWidth.takeIf { it > 0f } ?: return GyroLensProjection()
        val sensorHeightMm = lens.sensorHeight.takeIf { it > 0f } ?: return GyroLensProjection()
        val displayDigitalRatio = digitalZoomRatio.coerceAtLeast(1f)
        val visibleSensorWidthMm = sensorWidthMm / displayDigitalRatio
        val visibleSensorHeightMm = sensorHeightMm / displayDigitalRatio
        val fovX = Math.toDegrees(
            2.0 * Math.atan((visibleSensorWidthMm / (2f * focalMm)).toDouble()),
        ).toFloat()
        val fovY = Math.toDegrees(
            2.0 * Math.atan((visibleSensorHeightMm / (2f * focalMm)).toDouble()),
        ).toFloat()
        return GyroLensProjection(
            focalPxX = viewWidth * focalMm / visibleSensorWidthMm,
            focalPxY = viewHeight * focalMm / visibleSensorHeightMm,
            fovXDegrees = fovX,
            fovYDegrees = fovY,
        )
    }

    fun gyroPreviewAxisFlag(x: Boolean, y: Boolean): String = when {
        x && y -> "XY"
        x -> "X"
        y -> "Y"
        else -> "-"
    }

    fun gyroPreviewLensFovText(transform: GyroPreviewStabilizer.Transform): String {
        val projection = gyroPreviewLensProjection(
            viewWidth = 1f,
            viewHeight = 1f,
            digitalZoomRatio = transform.digitalZoomRatio,
        )
        return if (projection.fovXDegrees > 0f && projection.fovYDegrees > 0f) {
            String.format(Locale.US, "%.1f/%.1f", projection.fovXDegrees, projection.fovYDegrees)
        } else if (transform.lensFocalPxX > 0f || transform.lensFocalPxY > 0f) {
            "unknown"
        } else {
            "-"
        }
    }

    fun gyroPreviewSummaryText(transform: GyroPreviewStabilizer.Transform): String {
        val syncMode = when {
            gyroPreviewGlRenderEnabled && transform.frameSyncEnabled -> "GL帧"
            gyroPreviewGlRenderEnabled -> "GL陀"
            transform.frameSyncEnabled -> "帧"
            else -> "陀"
        }
        return String.format(
            Locale.US,
            "自研EIS[%s]: dx=%.1f dy=%.1f rot=%.2f crop=%.2f z=%.1f(o%.1f*d%.1f*v%.2f) rps=%.3f aim=%.0f%% g=%.2f/%.2f lim=%.0f %s",
            syncMode,
            transform.translationX,
            transform.translationY,
            transform.rotationDegrees,
            transform.scale,
            transform.effectiveZoom,
            transform.opticalZoomBase,
            transform.digitalZoomRatio,
            transform.displayZoomRatio,
            transform.rps,
            transform.aimBlend * 100f,
            gyroPreviewHorizontalGain,
            gyroPreviewVerticalGain,
            transform.outputRateLimitPxPerSecond,
            gyroPreviewAxisText(),
        )
    }

    fun gyroPreviewDiagnosticsText(transform: GyroPreviewStabilizer.Transform): String {
        return String.format(
            Locale.US,
            "\n诊断: gx=%.3f gy=%.3f gz=%.3f pitchSrc=%.3f yawSrc=%.3f rollSrc=%.3f frame=%s samples=%d dt=%.1fms lag=%.1fms" +
                "\n模型: raw=%.1f/%.1f target=%.1f/%.1f out=%.1f/%.1f max=%.0f/%.0f step=%.1f lim=%s clamp=%s focal=%.0f/%.0f lens=%.0f/%.0f fov=%s display=%.2f render=%.2f exp=%.2fms skew=%.2fms off=%.2fms rel=%s res=%.0f%% still=%.0fms wind=%s",
            transform.gyroXRadPerSec,
            transform.gyroYRadPerSec,
            transform.gyroZRadPerSec,
            transform.sourcePitchRate,
            transform.sourceYawRate,
            transform.sourceRollRate,
            if (transform.frameSyncEnabled) "on" else "off",
            transform.frameSamples,
            transform.frameDeltaMs,
            transform.frameLagMs,
            transform.rawTranslationX,
            transform.rawTranslationY,
            transform.targetTranslationX,
            transform.targetTranslationY,
            transform.translationX,
            transform.translationY,
            transform.maxTranslationX,
            transform.maxTranslationY,
            transform.maxOutputStepPx,
            gyroPreviewAxisFlag(transform.outputLimitedX, transform.outputLimitedY),
            gyroPreviewAxisFlag(transform.translationClampedX, transform.translationClampedY),
            transform.modelFocalPxX,
            transform.modelFocalPxY,
            transform.lensFocalPxX,
            transform.lensFocalPxY,
            gyroPreviewLensFovText(transform),
            transform.displayZoomRatio,
            transform.renderScale,
            transform.exposureTimeMs,
            transform.rollingShutterSkewMs,
            transform.frameTargetOffsetMs,
            if (transform.residualReleaseActive) "on" else "off",
            transform.residualLevelRatio * 100f,
            transform.residualStillMs,
            gyroPreviewAxisFlag(transform.residualAntiWindupX, transform.residualAntiWindupY),
        )
    }

    fun gyroPreviewModelLogText(transform: GyroPreviewStabilizer.Transform): String {
        val lens = selectedLens
        return String.format(
            Locale.US,
            "gyro-preview-model: lens=%s/%s out=%.1f/%.1f raw=%.1f/%.1f target=%.1f/%.1f " +
                "max=%.0f/%.0f step=%.1f limited=%s clamped=%s modelFocal=%.0f/%.0f " +
                "lensFocal=%.0f/%.0f fov=%s exp=%.2fms rolling=%.2fms targetOffset=%.2fms " +
                "samples=%d dt=%.1fms lag=%.1fms zoom=%.2f(o%.2f*d%.2f*v%.2f) render=%.2f " +
                "release=%s residual=%.2f still=%.0fms windup=%s",
            lens?.cameraId ?: "unknown",
            lens?.physicalId ?: "logical",
            transform.translationX,
            transform.translationY,
            transform.rawTranslationX,
            transform.rawTranslationY,
            transform.targetTranslationX,
            transform.targetTranslationY,
            transform.maxTranslationX,
            transform.maxTranslationY,
            transform.maxOutputStepPx,
            gyroPreviewAxisFlag(transform.outputLimitedX, transform.outputLimitedY),
            gyroPreviewAxisFlag(transform.translationClampedX, transform.translationClampedY),
            transform.modelFocalPxX,
            transform.modelFocalPxY,
            transform.lensFocalPxX,
            transform.lensFocalPxY,
            gyroPreviewLensFovText(transform),
            transform.exposureTimeMs,
            transform.rollingShutterSkewMs,
            transform.frameTargetOffsetMs,
            transform.frameSamples,
            transform.frameDeltaMs,
            transform.frameLagMs,
            transform.effectiveZoom,
            transform.opticalZoomBase,
            transform.digitalZoomRatio,
            transform.displayZoomRatio,
            transform.renderScale,
            if (transform.residualReleaseActive) "on" else "off",
            transform.residualLevelRatio,
            transform.residualStillMs,
            gyroPreviewAxisFlag(transform.residualAntiWindupX, transform.residualAntiWindupY),
        )
    }

    fun gyroCalibrationAxisHint(rawEstimatePx: Float, gyroPredPx: Float): String {
        val minSignalPx = GYRO_STABILIZATION_CALIBRATION_HINT_MIN_SIGNAL_PX
        if (abs(rawEstimatePx) < minSignalPx || abs(gyroPredPx) < minSignalPx) return "弱"
        if (rawEstimatePx * gyroPredPx < 0f) return "反"
        val ratio = abs(gyroPredPx) / abs(rawEstimatePx).coerceAtLeast(1f)
        return when {
            ratio < 0.75f -> "少"
            ratio > 1.35f -> "多"
            else -> "准"
        }
    }

    fun gyroCalibrationDominantHint(
        samples: List<StabilizationCalibrationHintSample>,
        selector: (StabilizationCalibrationHintSample) -> String,
    ): String {
        if (samples.isEmpty()) return "等待"
        val order = listOf("反", "少", "多", "准", "弱")
        var bestHint = "弱"
        var bestCount = 0
        for (hint in order) {
            val count = samples.count { selector(it) == hint }
            if (count > bestCount) {
                bestHint = hint
                bestCount = count
            }
        }
        return "$bestHint$bestCount/${samples.size}"
    }

    fun gyroCalibrationWindowText(samples: List<StabilizationCalibrationHintSample>): String {
        return "1.2s: X=${gyroCalibrationDominantHint(samples) { it.xHint }} " +
            "Y=${gyroCalibrationDominantHint(samples) { it.yHint }}"
    }

    fun gyroCalibrationPanelText(
        actualDxPx: Float,
        actualDyPx: Float,
        rawEstimateX: Float,
        rawEstimateY: Float,
        gyroPredX: Float,
        gyroPredY: Float,
        compDeltaX: Float,
        compDeltaY: Float,
        score: Float,
        rps: Float,
        effectiveZoom: Float,
        xHint: String,
        yHint: String,
        windowText: String,
        sampleModeLabel: String,
        sampleWidth: Int,
        sampleHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): String {
        val stepX = viewWidth.toFloat() / sampleWidth.coerceAtLeast(1)
        val stepY = viewHeight.toFloat() / sampleHeight.coerceAtLeast(1)
        return String.format(
            Locale.US,
            "防抖标定[%s]: A=%.1f/%.1f R=%.1f/%.1f P=%.1f/%.1f\n" +
                "图=%dx%d view=%dx%d step=%.1f/%.1f score=%.2f\n" +
                "调参: X=%s Y=%s; %s C=%.1f/%.1f r=%.3f z=%.1f",
            sampleModeLabel,
            actualDxPx,
            actualDyPx,
            rawEstimateX,
            rawEstimateY,
            gyroPredX,
            gyroPredY,
            sampleWidth,
            sampleHeight,
            viewWidth,
            viewHeight,
            stepX,
            stepY,
            score,
            xHint,
            yHint,
            windowText,
            compDeltaX,
            compDeltaY,
            rps,
            effectiveZoom,
        )
    }

    fun handleGyroPreviewTransform(transform: GyroPreviewStabilizer.Transform, nowMs: Long) {
        gyroPreviewTransform = transform
        val shouldUpdateMatrix = nowMs - gyroPreviewLastMatrixUpdateMs >= GYRO_PREVIEW_MATRIX_UPDATE_INTERVAL_MS
        val shouldSyncOverlay = nowMs - gyroPreviewLastOverlayUpdateMs >= GYRO_PREVIEW_OVERLAY_UPDATE_INTERVAL_MS
        if (shouldUpdateMatrix) {
            applyPreviewTextureTransform(transform, syncOverlay = shouldSyncOverlay)
            gyroPreviewLastMatrixUpdateMs = nowMs
            if (shouldSyncOverlay) gyroPreviewLastOverlayUpdateMs = nowMs
        }
        if (nowMs - gyroPreviewLastHudUpdateMs >= GYRO_PREVIEW_HUD_UPDATE_INTERVAL_MS) {
            gyroPreviewText = buildString {
                append(gyroPreviewSummaryText(transform))
                if (gyroPreviewDiagnosticsEnabled) append(gyroPreviewDiagnosticsText(transform))
            }
            gyroPreviewLastHudUpdateMs = nowMs
        }
        if (gyroPreviewDiagnosticsEnabled && nowMs - gyroPreviewLastModelLogMs >= GYRO_PREVIEW_MODEL_LOG_INTERVAL_MS) {
            Log.i("CameraScreen", gyroPreviewModelLogText(transform))
            gyroPreviewLastModelLogMs = nowMs
        }
    }

    fun calibrationSampleZoom(equivRatio: Float = currentEquivRatio): Double {
        return if (isUiZoomActive()) {
            uiCameraBaseZoomForLens(selectedLens).toDouble()
        } else {
            equivRatio.coerceAtLeast(1f).toDouble()
        }
    }

    fun visualZoomDisplayRatio(): Float {
        val uiScale = textureBaseScale.coerceAtLeast(1f)
        val baseZoom = if (isUiZoomActive()) {
            uiCameraBaseZoomForLens(selectedLens)
        } else {
            currentEquivRatio.coerceAtLeast(1f)
        }
        return (baseZoom * uiScale).coerceAtLeast(1f)
    }

    fun visualZoomLabelSuffix(baseSuffix: String = ""): String {
        val hasUiScale = anchorActive && textureBaseScale > 1.01f
        return when {
            !hasUiScale -> baseSuffix
            baseSuffix.isBlank() -> "视觉"
            else -> "$baseSuffix 视觉"
        }
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
        applyCameraZoomForMode(targetZoom)
        statusText = if (lens?.category == LensCategory.WIDE) {
            if (isUiZoomActive()) "普通校准：广角镜头已切到 UI 5x" else "普通校准：广角镜头已请求 5x"
        } else {
            if (isUiZoomActive()) "普通校准：保持当前 UI ${"%.1f".format(targetZoom)}x" else "普通校准：保持当前 ${"%.1f".format(targetZoom)}x"
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
            applyCameraZoomForMode(currentEquivRatio)
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
        cameraController.onFrameTimingChanged = { timing ->
            mainHandler.post {
                if (!canApplyGyroPreviewStabilization() || !gyroPreviewFrameSyncEnabled) return@post
                val wrapper = wrapperRef ?: return@post
                if (wrapper.width <= 0 || wrapper.height <= 0) return@post
                val digitalZoomRatio = gyroPreviewDigitalZoomRatio()
                val displayZoomRatio = gyroPreviewDisplayZoomRatio()
                val renderScale = gyroPreviewRenderScale()
                val lensProjection = gyroPreviewLensProjection(
                    wrapper.width.toFloat(),
                    wrapper.height.toFloat(),
                    digitalZoomRatio,
                )
                val transform = gyroPreviewStabilizer.updateForFrame(
                    frameTimestampNs = timing.sensorTimestampNs,
                    exposureTimeNs = timing.exposureTimeNs,
                    rollingShutterSkewNs = timing.rollingShutterSkewNs,
                    nowElapsedRealtimeNs = android.os.SystemClock.elapsedRealtimeNanos(),
                    viewWidth = wrapper.width.toFloat(),
                    viewHeight = wrapper.height.toFloat(),
                    opticalZoomBase = opticalBaseForLens(),
                    digitalZoomRatio = digitalZoomRatio,
                    displayZoomRatio = displayZoomRatio,
                    renderScale = renderScale,
                    lensFocalPxX = lensProjection.focalPxX,
                    lensFocalPxY = lensProjection.focalPxY,
                )
                handleGyroPreviewTransform(transform, android.os.SystemClock.elapsedRealtime())
            }
        }
    }

    LaunchedEffect(
        gyroPreviewStrength,
        gyroPreviewPathFollow,
        gyroPreviewOutputAlpha,
        gyroPreviewOutputRateLimit,
        gyroPreviewCropScale,
        gyroPreviewMaxShift,
        gyroPreviewDeadbandPx,
        gyroPreviewHorizontalGain,
        gyroPreviewVerticalGain,
        gyroPreviewAimStabilizationEnabled,
        gyroPreviewAimRpsThreshold,
        gyroPreviewAimStrengthMultiplier,
        gyroPreviewAimPathFollow,
        gyroPreviewAimDeadbandPx,
        gyroPreviewAimOutputRateLimit,
        gyroPreviewZoomCompensationEnabled,
        gyroPreviewZoomCompensationStrength,
        gyroPreviewMaxCompensationZoom,
        gyroPreviewFrameSyncEnabled,
        gyroPreviewFrameTimestampOffsetMs,
        gyroPreviewRollCompensationEnabled,
        gyroPreviewRollStrength,
        gyroPreviewMaxRollDegrees,
        gyroPreviewInvertRoll,
        gyroPreviewSwapAxes,
        gyroPreviewInvertHorizontal,
        gyroPreviewInvertVertical,
    ) {
        gyroPreviewStabilizer.updateConfig(currentGyroPreviewConfig())
        resetGyroPreviewStabilization()
    }

    DisposableEffect(gyroPreviewStabilizationEnabled, gyroPreviewFrameSyncEnabled, gyroscopeSensor) {
        val sensor = gyroscopeSensor
        if (!gyroPreviewStabilizationEnabled || sensor == null) {
            if (gyroPreviewStabilizationEnabled && sensor == null) {
                gyroPreviewStabilizationEnabled = false
                cameraPrefs.edit()
                    .putBoolean(KEY_GYRO_PREVIEW_STABILIZATION_ENABLED, false)
                    .apply()
                gyroPreviewText = "自研EIS: 无陀螺仪"
                statusText = "当前设备没有陀螺仪，无法开启自研EIS"
            }
            resetGyroPreviewStabilization()
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type != Sensor.TYPE_GYROSCOPE || event.values.size < 3) return
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    gyroPreviewStabilizer.recordGyroSample(
                        timestampNs = event.timestamp,
                        gyroXRadPerSec = x,
                        gyroYRadPerSec = y,
                        gyroZRadPerSec = z,
                    )

                    if (canApplyGyroPreviewStabilization() && !gyroPreviewFrameSyncEnabled) {
                        val wrapper = wrapperRef
                        if (wrapper != null && wrapper.width > 0 && wrapper.height > 0) {
                            val digitalZoomRatio = gyroPreviewDigitalZoomRatio()
                            val displayZoomRatio = gyroPreviewDisplayZoomRatio()
                            val renderScale = gyroPreviewRenderScale()
                            val lensProjection = gyroPreviewLensProjection(
                                wrapper.width.toFloat(),
                                wrapper.height.toFloat(),
                                digitalZoomRatio,
                            )
                            val transform = gyroPreviewStabilizer.update(
                                timestampNs = event.timestamp,
                                gyroXRadPerSec = x,
                                gyroYRadPerSec = y,
                                gyroZRadPerSec = z,
                                viewWidth = wrapper.width.toFloat(),
                                viewHeight = wrapper.height.toFloat(),
                                opticalZoomBase = opticalBaseForLens(),
                                digitalZoomRatio = digitalZoomRatio,
                                displayZoomRatio = displayZoomRatio,
                                renderScale = renderScale,
                                lensFocalPxX = lensProjection.focalPxX,
                                lensFocalPxY = lensProjection.focalPxY,
                            )
                            handleGyroPreviewTransform(transform, android.os.SystemClock.elapsedRealtime())
                        }
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            resetGyroPreviewStabilization()
            gyroPreviewText = "自研EIS: 等待陀螺仪"
            val listenerRegistered = try {
                sensorManager.registerListener(
                    listener,
                    sensor,
                    GYRO_PREVIEW_STABILIZATION_SENSOR_DELAY_US,
                )
            } catch (e: SecurityException) {
                Log.e("CameraScreen", "gyro-preview listener start failed: sensor=${sensor.name}", e)
                gyroPreviewStabilizationEnabled = false
                cameraPrefs.edit()
                    .putBoolean(KEY_GYRO_PREVIEW_STABILIZATION_ENABLED, false)
                    .apply()
                resetGyroPreviewStabilization()
                gyroPreviewText = "自研EIS: 传感器权限不足"
                statusText = "自研EIS启动失败：缺少 HIGH_SAMPLING_RATE_SENSORS 权限"
                false
            } catch (e: RuntimeException) {
                Log.e("CameraScreen", "gyro-preview listener start failed: sensor=${sensor.name}", e)
                gyroPreviewStabilizationEnabled = false
                cameraPrefs.edit()
                    .putBoolean(KEY_GYRO_PREVIEW_STABILIZATION_ENABLED, false)
                    .apply()
                resetGyroPreviewStabilization()
                gyroPreviewText = "自研EIS: 启动失败"
                statusText = "自研EIS启动失败：${e.message ?: "传感器注册异常"}"
                false
            }
            Log.d(
                "CameraScreen",
                "gyro-preview listener ${if (listenerRegistered) "started" else "not-started"}: " +
                    "sensor=${sensor.name}, frameSync=$gyroPreviewFrameSyncEnabled",
            )
            onDispose {
                if (listenerRegistered) {
                    sensorManager.unregisterListener(listener)
                }
                if (gyroPreviewStabilizationEnabled) {
                    gyroPreviewStabilizer.reset()
                }
                Log.d("CameraScreen", "gyro-preview listener stopped")
            }
        }
    }

    LaunchedEffect(
        gyroStabilizationCalibrationEnabled,
        gyroPreviewGlRenderEnabled,
        gyroStabilizationCalibrationFineSampleEnabled,
        surfaceAvailable,
    ) {
        if (!gyroStabilizationCalibrationEnabled) return@LaunchedEffect
        if (gyroPreviewGlRenderEnabled) {
            gyroStabilizationCalibrationEnabled = false
            gyroStabilizationCalibrationFrame = null
            gyroStabilizationCalibrationHints.clear()
            gyroStabilizationCalibrationText = ""
            cameraPrefs.edit()
                .putBoolean(KEY_GYRO_STABILIZATION_CALIBRATION_ENABLED, false)
                .apply()
            statusText = "防抖标定暂只支持 TextureView 预览，已自动关闭"
            return@LaunchedEffect
        }

        gyroStabilizationCalibrationFrame = null
        gyroStabilizationCalibrationHints.clear()
        gyroStabilizationCalibrationText = "防抖标定: 等待预览采样"
        while (true) {
            val loopStartMs = android.os.SystemClock.elapsedRealtime()
            val textureView = textureViewRef
            if (textureView == null || !textureView.isAvailable) {
                gyroStabilizationCalibrationFrame = null
                gyroStabilizationCalibrationHints.clear()
                gyroStabilizationCalibrationText = "防抖标定: 预览未就绪"
                delay(GYRO_STABILIZATION_CALIBRATION_INTERVAL_MS)
                continue
            }

            val frame = captureStabilizationCalibrationFrame(
                textureView,
                gyroPreviewTransform,
                stabilizationCalibrationSampleMode(),
            )
            val previous = gyroStabilizationCalibrationFrame
            var matchCostMs: Long? = null
            var matchScore: Float? = null
            if (frame != null && previous != null) {
                val (result, costMs) = withContext(Dispatchers.Default) {
                    val matchStartMs = android.os.SystemClock.elapsedRealtime()
                    val matchResult = estimateStabilizationCalibrationShift(previous, frame)
                    matchResult to (android.os.SystemClock.elapsedRealtime() - matchStartMs)
                }
                matchCostMs = costMs
                if (result != null) {
                    matchScore = result.score
                    val compDeltaX = frame.compensationX - previous.compensationX
                    val compDeltaY = frame.compensationY - previous.compensationY
                    val rawEstimateX = result.actualDxPx - compDeltaX
                    val rawEstimateY = result.actualDyPx - compDeltaY
                    val gyroPredX = -compDeltaX
                    val gyroPredY = -compDeltaY
                    val intervalMs = frame.timestampMs - previous.timestampMs
                    val xHint = gyroCalibrationAxisHint(rawEstimateX, gyroPredX)
                    val yHint = gyroCalibrationAxisHint(rawEstimateY, gyroPredY)
                    val cutoffMs = frame.timestampMs - GYRO_STABILIZATION_CALIBRATION_HINT_WINDOW_MS
                    gyroStabilizationCalibrationHints.add(
                        StabilizationCalibrationHintSample(
                            timestampMs = frame.timestampMs,
                            xHint = xHint,
                            yHint = yHint,
                        ),
                    )
                    gyroStabilizationCalibrationHints.removeAll { it.timestampMs < cutoffMs }
                    val windowText = gyroCalibrationWindowText(gyroStabilizationCalibrationHints)
                    gyroStabilizationCalibrationText = gyroCalibrationPanelText(
                        result.actualDxPx,
                        result.actualDyPx,
                        rawEstimateX,
                        rawEstimateY,
                        gyroPredX,
                        gyroPredY,
                        compDeltaX,
                        compDeltaY,
                        result.score,
                        frame.rps,
                        frame.effectiveZoom,
                        xHint,
                        yHint,
                        windowText,
                        frame.sampleModeLabel,
                        frame.width,
                        frame.height,
                        frame.viewWidth,
                        frame.viewHeight,
                    )
                    val stepX = frame.viewWidth.toFloat() / frame.width.coerceAtLeast(1)
                    val stepY = frame.viewHeight.toFloat() / frame.height.coerceAtLeast(1)
                    Log.i(
                        "CameraScreen",
                        "gyro-calib: interval=${intervalMs}ms sample=(${result.sampleDx},${result.sampleDy}) " +
                            "actual=(${result.actualDxPx},${result.actualDyPx}) rawEst=($rawEstimateX,$rawEstimateY) " +
                            "gyroPred=($gyroPredX,$gyroPredY) compDelta=($compDeltaX,$compDeltaY) " +
                            "score=${result.score} rps=${frame.rps} zoom=${frame.effectiveZoom} rot=${frame.rotationDegrees} " +
                            "hint=($xHint,$yHint) window=$windowText mode=${frame.sampleModeLabel} " +
                            "bitmap=${frame.width}x${frame.height} view=${frame.viewWidth}x${frame.viewHeight} " +
                            "step=($stepX,$stepY)",
                    )
                } else {
                    gyroStabilizationCalibrationHints.clear()
                    gyroStabilizationCalibrationText = "防抖标定: 中心纹理不足或匹配失败"
                }
            } else if (frame == null) {
                gyroStabilizationCalibrationHints.clear()
                gyroStabilizationCalibrationText = "防抖标定: 截取预览失败"
            }
            frame?.let { currentFrame ->
                val loopCostMs = android.os.SystemClock.elapsedRealtime() - loopStartMs
                val intervalText = previous?.let { (currentFrame.timestampMs - it.timestampMs).toString() } ?: "first"
                Log.i(
                    "CameraScreen",
                    "gyro-calib-cost: interval=${intervalText}ms capture=${currentFrame.captureCostMs}ms " +
                        "luma=${currentFrame.lumaCostMs}ms frameBuild=${currentFrame.frameBuildCostMs}ms " +
                        "match=${matchCostMs ?: -1}ms loop=${loopCostMs}ms " +
                        "delay=${GYRO_STABILIZATION_CALIBRATION_INTERVAL_MS}ms score=${matchScore ?: -1f} " +
                        "mode=${currentFrame.sampleModeLabel} bitmap=${currentFrame.width}x${currentFrame.height} " +
                        "view=${currentFrame.viewWidth}x${currentFrame.viewHeight}",
                )
            }
            gyroStabilizationCalibrationFrame = frame
            delay(GYRO_STABILIZATION_CALIBRATION_INTERVAL_MS)
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
    DisposableEffect(lifecycleOwner, gyroPreviewGlRenderEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    glPreviewViewRef?.onPause()
                    hasPaused = true; cameraController.closeCamera(); isCameraReady = false
                }
                Lifecycle.Event.ON_RESUME -> {
                    glPreviewViewRef?.onResume()
                    if (hasPaused) {
                        hasPaused = false
                        val lens = selectedLens
                        val st = previewSurfaceTextureRef
                        if (lens != null && st != null && surfaceAvailable) {
                            cameraController.setSurfaceTexture(st)
                            val resumeZoom = if (isDualCalibMode && isUsingDualTele) {
                                1f
                            } else if (isDualCalibMode) {
                                currentEquivRatio.coerceAtLeast(1f)
                            } else {
                                cameraZoomForMode(clampNormalZoom(currentEquivRatio, lens))
                            }
                            cameraController.setZoomRatio(resumeZoom)
                            cameraController.openCamera(
                                lens,
                                Surface(st),
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
        applyCameraZoomForMode(currentEquivRatio)
        statusText = "选中: ${describeLens(lens)}，逻辑相机起始 ${"%.1f".format(currentEquivRatio)}x"
    }

    // 统一相机打开
    LaunchedEffect(
        selectedLens,
        surfaceAvailable,
        isDualCalibMode,
        isUsingDualTele,
        uiZoomOnlyEnabled,
        gyroPreviewGlRenderEnabled,
    ) {
        val lens = selectedLens ?: return@LaunchedEffect
        if (!surfaceAvailable) return@LaunchedEffect
        if (!gyroPreviewGlRenderEnabled) {
            val tv = textureViewRef ?: return@LaunchedEffect
            if (!tv.isAvailable) return@LaunchedEffect
        }
        val surfaceTexture = previewSurfaceTextureRef ?: return@LaunchedEffect
        val initialZoom = if (isDualCalibMode && isUsingDualTele) {
            1f
        } else if (isDualCalibMode) {
            currentEquivRatio.coerceAtLeast(1f)
        } else {
            cameraZoomForMode(clampNormalZoom(currentEquivRatio, lens))
        }
        cameraController.setZoomRatio(initialZoom)
        cameraController.closeCamera()
        cameraController.openCamera(
            lens,
            Surface(surfaceTexture),
            strictPhysical = isDualCalibMode && lens.physicalId != null,
        )
    }

    LaunchedEffect(uiZoomOnlyEnabled, selectedLens, lensCandidates, isDualCalibMode) {
        if (isUiZoomActive()) {
            val minEquivRatio = normalMinZoomFor(selectedLens)
            if (currentEquivRatio < minEquivRatio) {
                currentEquivRatio = minEquivRatio
            }
        }
    }

    LaunchedEffect(isCameraReady, isDualCalibMode, isUsingDualTele, currentEquivRatio, selectedLens, uiZoomOnlyEnabled) {
        if (!isCameraReady) return@LaunchedEffect
        val cameraZoom = cameraZoomForMode(currentEquivRatio)
        val opticalBase = opticalBaseForLens()
        val uiCameraBase = uiCameraBaseZoomForLens()
        val uiScale = uiZoomDisplayScale()
        val zoomMode = if (isUiZoomActive()) "UI_TEXTURE_MATRIX" else "CAMERA2"
        Log.i(
            "CameraScreen",
            String.format(
                Locale.US,
                "zoom-map: equiv=%.2fx, camera=%.2fx, uiScale=%.2fx, opticalBase=%.2fx, uiCameraBase=%.2fx, mode=%s, lens=%s",
                currentEquivRatio,
                cameraZoom,
                uiScale,
                opticalBase,
                uiCameraBase,
                zoomMode,
                selectedLens?.physicalId ?: selectedLens?.cameraId ?: "none",
            ),
        )
        cameraController.setZoomRatio(cameraZoom)
        if (isUiZoomActive()) {
            val message = String.format(
                Locale.US,
                "zoom: backend=UI_TEXTURE_MATRIX, ratio=%.2f, camera=%.2f, uiScale=%.2f, cameraBase=%.2f, opticalBase=%.2f",
                currentEquivRatio,
                cameraZoom,
                uiScale,
                uiCameraBase,
                opticalBase,
            )
            Log.i("CameraScreen", message)
            zoomBackendText = message
        }
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

    // 变换效果：默认倍率放大交给 Camera2；纯 UI 放大实验模式下由 TextureView Matrix 承担显示 zoom。
    LaunchedEffect(
        currentCenterPx,
        anchorCenter1x,
        anchorZoom,
        anchorActive,
        currentEquivRatio,
        isCameraReady,
        isDualCalibMode,
        isUsingDualTele,
        uiZoomOnlyEnabled,
        gyroPreviewStabilizationEnabled,
        gyroPreviewGlRenderEnabled,
        showCalPanel,
        showDualCalPanel,
    ) {
        val wrapper = wrapperRef ?: return@LaunchedEffect
        if (!isCameraReady) return@LaunchedEffect
        val point = if (isUiZoomActive()) {
            anchorCenter1x?.takeIf { anchorActive } ?: currentCenterPx
        } else if (isDualCalibMode) {
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
            val displayScale = uiZoomDisplayScale()

            if (anchorActive && point != null) {
                val focusX = (point.x / ImageUtils.OUT_W.toDouble() * w).toFloat()
                val focusY = (point.y / ImageUtils.OUT_H.toDouble() * h).toFloat()
                val coverScale = minCoverScaleForFocus(focusX, focusY, w, h)
                val scale = if (isUiZoomActive()) max(coverScale, displayScale) else coverScale

                // 1080x1920 归一化坐标先映射到实际预览容器，再用中心 pivot 变换。
                // 以中心为 pivot 时：screen = (focus - center) * scale + center + translation。
                // 令 screen = center，可得 translation = (center - focus) * scale。
                wrapper.pivotX = screenCX
                wrapper.pivotY = screenCY
                wrapper.translationX = 0f
                wrapper.translationY = 0f
                wrapper.scaleX = 1f
                wrapper.scaleY = 1f
                textureBaseScale = scale
                textureBaseTx = (screenCX - focusX) * scale
                textureBaseTy = (screenCY - focusY) * scale
                applyPreviewTextureTransform()
            } else if (isUiZoomActive() && displayScale > 1f) {
                wrapper.pivotX = screenCX
                wrapper.pivotY = screenCY
                wrapper.translationX = 0f
                wrapper.translationY = 0f
                wrapper.scaleX = 1f
                wrapper.scaleY = 1f
                textureBaseScale = displayScale
                textureBaseTx = 0f
                textureBaseTy = 0f
                applyPreviewTextureTransform()
            } else if (isDualCalibMode) {
                wrapper.pivotX = screenCX
                wrapper.pivotY = screenCY
                wrapper.translationX = 0f
                wrapper.translationY = 0f
                wrapper.scaleX = 1f
                wrapper.scaleY = 1f
                textureBaseScale = 1f
                textureBaseTx = 0f
                textureBaseTy = 0f
                applyPreviewTextureTransform()
                if (gyroPreviewStabilizationEnabled && !anchorActive) {
                    gyroPreviewText = "自研EIS: 双摄校准时暂停"
                }
            } else {
                wrapper.pivotX = screenCX
                wrapper.pivotY = screenCY
                wrapper.translationX = 0f
                wrapper.translationY = 0f
                wrapper.scaleX = 1f
                wrapper.scaleY = 1f
                textureBaseScale = 1f
                textureBaseTx = 0f
                textureBaseTy = 0f
                applyPreviewTextureTransform()
            }
        }
    }

    // 检测帧处理循环：支持 TextureView.getBitmap() 和 ImageReader YUV 两种数据源。

    LaunchedEffect(
        detectionEnabled,
        yoloCaptureMode,
        yoloFrameSource,
        yoloTopDetectionEnabled,
        yoloTopConfidenceThreshold,
    ) {
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
                        val postprocessOptions = if (yoloTopDetectionEnabled) {
                            YoloPostprocessOptions.topOnly(yoloTopConfidenceThreshold)
                        } else {
                            YoloPostprocessOptions.original()
                        }
                        val filterText = if (postprocessOptions.topConfidenceOnly) {
                            "单框>=${formatYoloConfidence(postprocessOptions.confidenceThreshold)}"
                        } else {
                            "多框>=${formatYoloConfidence(postprocessOptions.confidenceThreshold)}"
                        }
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
                                        val lensDebug = activeLensDebugText()
                                        sourceLabel = "PreviewBitmap ${captureMode.label} ${snapshot.width}x${snapshot.height} $lensDebug"
                                        Log.d("CameraScreen", "YOLO source: $sourceLabel")
                                        withContext(Dispatchers.Default) {
                                            detector.detect(snapshot, postprocessOptions)
                                        }
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
                                            val lensDebug = activeLensDebugText()
                                            sourceLabel = "ImageReaderYUV ${image.width}x${image.height} rot=$rotation $lensDebug"
                                            Log.d("CameraScreen", "YOLO source: $sourceLabel")
                                            withContext(Dispatchers.Default) {
                                                detector.detectFromYuv(image, rotation, postprocessOptions)
                                            }
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
                                detectionPerfText = "$sourceLabel | $filterText | pre=${result.timings.preprocessMs}ms inf=${result.timings.inferenceMs}ms post=${result.timings.postprocessMs}ms total=${totalMs}ms | ${result.detections.size}obj"
                                Log.d("CameraScreen", "detection done: objects=${result.detections.size}, total=${totalMs}ms")

                                val screenCX = viewW / 2f
                                val screenCY = viewH / 2f
                                val areaHalfW = areaWidthPx / 2f
                                val areaHalfH = areaHeightPx / 2f
                                val tx = wrapperTx; val ty = wrapperTy; val s = wrapperScale; val rot = wrapperRotation
                                val pts = checkPoints
                                val hasTargetInArea = mapped.any { (box, _) ->
                                    fun isPointInside(pctW: Float, pctH: Float): Boolean {
                                        val px = box.left + box.width() * pctW
                                        val py = box.top + box.height() * pctH
                                        val (cx, cy) = mapPreviewPointToScreen(
                                            x = px,
                                            y = py,
                                            centerX = screenCX,
                                            centerY = screenCY,
                                            scale = s,
                                            rotationDegrees = rot,
                                            translationX = tx,
                                            translationY = ty,
                                        )
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
            key(gyroPreviewGlRenderEnabled) {
                AndroidView(
                    factory = { ctx ->
                        val wrapper = FrameLayout(ctx)
                        if (gyroPreviewGlRenderEnabled) {
                            val glView = GlStabilizedPreviewView(ctx).apply {
                                onCameraSurfaceTextureAvailable = { st ->
                                    previewSurfaceTextureRef = st
                                    surfaceAvailable = true
                                    cameraController.setSurfaceTexture(st)
                                }
                                onCameraSurfaceTextureDestroyed = { st ->
                                    if (previewSurfaceTextureRef === st) previewSurfaceTextureRef = null
                                    surfaceAvailable = false
                                    cameraController.closeCamera()
                                    isCameraReady = false
                                }
                            }
                            wrapper.addView(
                                glView,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                ),
                            )
                            glPreviewViewRef = glView
                            textureViewRef = null
                        } else {
                            val tv = TextureView(ctx).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                        previewSurfaceTextureRef = st
                                        surfaceAvailable = true
                                        cameraController.setSurfaceTexture(st)
                                    }
                                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                        if (previewSurfaceTextureRef === st) previewSurfaceTextureRef = null
                                        surfaceAvailable = false
                                        cameraController.closeCamera()
                                        isCameraReady = false
                                        return true
                                    }
                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                }
                            }
                            // TextureView 填满容器；纯 UI 放大时通过 setTransform(Matrix) 做显示层变换。
                            wrapper.addView(
                                tv,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                ),
                            )
                            textureViewRef = tv
                            glPreviewViewRef = null
                        }
                        wrapperRef = wrapper
                        wrapper
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            CrosshairOverlay(modifier = Modifier.fillMaxSize())
            // 检测框跟随 wrapper 变换（与预览内容一致）
            DetectionOverlay(
                boxes = detectionBoxes,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    translationX = wrapperTx; translationY = wrapperTy
                    scaleX = wrapperScale; scaleY = wrapperScale
                    rotationZ = wrapperRotation
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
                        enabled = !gyroPreviewGlRenderEnabled && yoloDetector != null,
                        accent = Color(0xFFFF9800),
                        onClick = { detectionEnabled = !detectionEnabled },
                    )
                    TopActionButton(
                        text = if (isDualCalibMode) "双摄" else if (showCalPanel) "校准中" else "校准",
                        active = showCalPanel || showDualCalPanel || isDualCalibMode,
                        enabled = !gyroPreviewGlRenderEnabled,
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
                            enabled = !gyroPreviewGlRenderEnabled,
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
                            gyroPreviewStabilizationEnabled ||
                            gyroPreviewGlRenderEnabled ||
                            gyroStabilizationCalibrationEnabled ||
                            gyroPreviewSettingsChanged() ||
                            uiZoomOnlyEnabled ||
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
                            text = if (uiZoomOnlyEnabled) "纯UI放大 开" else "纯UI放大",
                            active = uiZoomOnlyEnabled,
                            enabled = !showCalPanel && !showDualCalPanel && !isDualCalibMode,
                            accent = Color(0xFF80CBC4),
                            onClick = {
                                toolsMenuExpanded = false
                                val next = !uiZoomOnlyEnabled
                                uiZoomOnlyEnabled = next
                                cameraPrefs.edit()
                                    .putBoolean(KEY_UI_ZOOM_ONLY_ENABLED, next)
                                    .apply()
                                if (next) {
                                    val minEquivRatio = normalMinZoomFor(selectedLens)
                                    if (currentEquivRatio < minEquivRatio) {
                                        currentEquivRatio = minEquivRatio
                                    }
                                }
                                detectionBoxes = emptyList()
                                showMeasurePrompt = false
                                wasTargetInArea = false
                                if (anchorActive) {
                                    updateCenterFromAnchorForZoom(currentEquivRatio)
                                }
                                applyCameraZoomForMode(currentEquivRatio)
                                statusText = if (next) {
                                    "纯UI放大：相机保持最低有效底倍，超出部分由 UI 矩阵放大"
                                } else {
                                    "纯UI放大已关闭：恢复 Camera2 数码放大"
                                }
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
                            enabled = !gyroPreviewGlRenderEnabled,
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
                            enabled = !gyroPreviewGlRenderEnabled,
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
                        ToolMenuItem(
                            text = if (yoloTopDetectionEnabled) {
                                "YOLO单框 >=${formatYoloConfidence(yoloTopConfidenceThreshold)}"
                            } else {
                                "YOLO多框"
                            },
                            active = yoloTopDetectionEnabled,
                            enabled = !gyroPreviewGlRenderEnabled,
                            accent = Color(0xFFFF9800),
                            onClick = {
                                toolsMenuExpanded = false
                                openYoloFilterDialog()
                            },
                        )
                        ToolMenuItem(
                            text = if (gyroPreviewStabilizationEnabled) "自研EIS 开" else "自研EIS",
                            active = gyroPreviewStabilizationEnabled,
                            accent = Color(0xFF00ACC1),
                            onClick = {
                                toolsMenuExpanded = false
                                toggleGyroPreviewStabilization()
                            },
                        )
                        ToolMenuItem(
                            text = "自研EIS参数 ${if (gyroPreviewGlRenderEnabled) "GL " else ""}${gyroPreviewAxisText()}",
                            active = gyroPreviewSettingsChanged(),
                            accent = Color(0xFF00ACC1),
                            onClick = {
                                toolsMenuExpanded = false
                                openGyroPreviewSettingsDialog()
                            },
                        )
                        ToolMenuItem(
                            text = if (gyroStabilizationCalibrationEnabled) "防抖标定 开" else "防抖标定",
                            active = gyroStabilizationCalibrationEnabled,
                            enabled = !gyroPreviewGlRenderEnabled,
                            accent = Color(0xFFFFD54F),
                            onClick = {
                                toolsMenuExpanded = false
                                toggleGyroStabilizationCalibration()
                            },
                        )
                        if (!isLaserCompareApp) {
                            ToolMenuItem(
                                text = if (laserCompareEnabled) "绿光识别中" else "绿光识别",
                                active = showLaserComparePanel || laserCompareEnabled,
                                enabled = !gyroPreviewGlRenderEnabled,
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
                val gyroPreviewHudText = if (gyroPreviewStabilizationEnabled) gyroPreviewText else ""
                val gyroCalibrationHudText = if (gyroStabilizationCalibrationEnabled) {
                    gyroStabilizationCalibrationText
                } else {
                    ""
                }
                val gyroHudText = listOf(gyroPreviewHudText, gyroCalibrationHudText)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                StatusHud(
                    statusText = statusText,
                    gyroText = gyroHudText,
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
                        val suffix = visualZoomLabelSuffix()
                        val suffixText = if (suffix.isBlank()) "" else " $suffix"
                        "锁定 (${a.x.toInt()}, ${a.y.toInt()}) · " +
                            "${"%.1f".format(visualZoomDisplayRatio())}x$suffixText"
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
                                calManager.collectSample(rawBytes, distance, calibrationSampleZoom())
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
                displayRatio = visualZoomDisplayRatio(),
                minRatio = if (isDualCalibMode) 1f else normalMinZoomFor(selectedLens),
                maxRatio = if (isDualCalibMode) {
                    dualTelephotoMag.toFloat()
                } else {
                    normalMaxZoomFor(selectedLens)
                },
                labelSuffix = visualZoomLabelSuffix(if (isDualCalibMode) {
                    if (isUsingDualTele) " (T)" else " (W)"
                } else {
                    ""
                }),
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
                        lastResultText = if (isUiZoomActive()) {
                            "UI校准倍率: ${"%.1f".format(calibrationZoom)}x，相机=${"%.1f".format(calibrationSampleZoom())}x"
                        } else {
                            "校准倍率: ${"%.1f".format(calibrationZoom)}x"
                        }
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

        if (showYoloFilterDialog) {
            AlertDialog(
                onDismissRequest = { showYoloFilterDialog = false },
                title = { Text("YOLO识别阈值") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "最高置信度单框",
                                modifier = Modifier.weight(1f),
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Switch(
                                checked = yoloTopDetectionEnabledInput,
                                onCheckedChange = {
                                    yoloTopDetectionEnabledInput = it
                                    yoloFilterError = null
                                },
                            )
                        }
                        OutlinedTextField(
                            value = yoloConfidenceThresholdInput,
                            onValueChange = {
                                yoloConfidenceThresholdInput = it
                                yoloFilterError = null
                            },
                            enabled = yoloTopDetectionEnabledInput,
                            label = { Text("单框阈值") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = if (yoloTopDetectionEnabledInput) {
                                "当前阈值 ${formatYoloConfidence(yoloConfidenceThresholdInput.trim().toFloatOrNull() ?: yoloTopConfidenceThreshold)}"
                            } else {
                                "原始多框阈值 ${formatYoloConfidence(YoloPostprocessOptions.ORIGINAL_CONFIDENCE_THRESHOLD)}"
                            },
                            color = Color.White.copy(alpha = 0.70f),
                            fontSize = 12.sp,
                        )
                        yoloFilterError?.let { error ->
                            Text(error, color = Color(0xFFFF5252), fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { saveYoloFilterSetting() }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { showYoloFilterDialog = false }) { Text("取消") }
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

        if (showGyroPreviewSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showGyroPreviewSettingsDialog = false },
                title = { Text("自研EIS参数") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "先用方向开关找最稳组合；如果开启后更抖，优先切换反向或互换轴，再调整强度。",
                            color = Color.White.copy(alpha = 0.76f),
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                        )
                        Text(
                            text = "参数预设",
                            color = Color.White.copy(alpha = 0.86f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GYRO_PREVIEW_PARAMETER_PRESETS.forEach { preset ->
                                Button(
                                    onClick = { applyGyroPreviewParameterPreset(preset) },
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White.copy(alpha = 0.12f),
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.height(34.dp),
                                ) {
                                    Text(
                                        text = preset.label,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        GyroSettingSwitchRow(
                            text = "X/Y互换",
                            checked = gyroPreviewSwapAxesInput,
                            onCheckedChange = { gyroPreviewSwapAxesInput = it; gyroPreviewSettingsError = null },
                        )
                        GyroSettingSwitchRow(
                            text = "横向反向",
                            checked = gyroPreviewInvertHorizontalInput,
                            onCheckedChange = { gyroPreviewInvertHorizontalInput = it; gyroPreviewSettingsError = null },
                        )
                        GyroSettingSwitchRow(
                            text = "纵向反向",
                            checked = gyroPreviewInvertVerticalInput,
                            onCheckedChange = { gyroPreviewInvertVerticalInput = it; gyroPreviewSettingsError = null },
                        )
                        GyroSettingSwitchRow(
                            text = "帧同步",
                            checked = gyroPreviewFrameSyncEnabledInput,
                            onCheckedChange = { gyroPreviewFrameSyncEnabledInput = it; gyroPreviewSettingsError = null },
                        )
                        GyroSettingSwitchRow(
                            text = "诊断日志",
                            checked = gyroPreviewDiagnosticsEnabledInput,
                            onCheckedChange = { gyroPreviewDiagnosticsEnabledInput = it; gyroPreviewSettingsError = null },
                        )
                        GyroSettingSwitchRow(
                            text = "GL逐帧预览实验",
                            checked = gyroPreviewGlRenderEnabledInput,
                            onCheckedChange = { gyroPreviewGlRenderEnabledInput = it; gyroPreviewSettingsError = null },
                        )
                        GyroSettingSwitchRow(
                            text = "防抖标定精细采样",
                            checked = gyroStabilizationCalibrationFineSampleInput,
                            onCheckedChange = {
                                gyroStabilizationCalibrationFineSampleInput = it
                                gyroPreviewSettingsError = null
                            },
                        )
                        GyroSettingSwitchRow(
                            text = "倍率补偿",
                            checked = gyroPreviewZoomCompensationEnabledInput,
                            onCheckedChange = {
                                gyroPreviewZoomCompensationEnabledInput = it
                                gyroPreviewSettingsError = null
                            },
                        )
                        GyroSettingSwitchRow(
                            text = "瞄准稳定",
                            checked = gyroPreviewAimStabilizationEnabledInput,
                            onCheckedChange = {
                                gyroPreviewAimStabilizationEnabledInput = it
                                gyroPreviewSettingsError = null
                            },
                        )
                        GyroSettingSwitchRow(
                            text = "软边界限幅",
                            checked = gyroPreviewSoftBoundaryEnabledInput,
                            onCheckedChange = {
                                gyroPreviewSoftBoundaryEnabledInput = it
                                gyroPreviewSettingsError = null
                            },
                        )
                        GyroSettingSwitchRow(
                            text = "重新瞄准释放",
                            checked = gyroPreviewReAimEnabledInput,
                            onCheckedChange = {
                                gyroPreviewReAimEnabledInput = it
                                gyroPreviewSettingsError = null
                            },
                        )
                        GyroSettingSwitchRow(
                            text = "Roll补偿",
                            checked = gyroPreviewRollCompensationEnabledInput,
                            onCheckedChange = {
                                gyroPreviewRollCompensationEnabledInput = it
                                gyroPreviewSettingsError = null
                            },
                        )
                        GyroSettingSwitchRow(
                            text = "Roll反向",
                            checked = gyroPreviewInvertRollInput,
                            enabled = gyroPreviewRollCompensationEnabledInput,
                            onCheckedChange = {
                                gyroPreviewInvertRollInput = it
                                gyroPreviewSettingsError = null
                            },
                        )

                        Text("基础", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GyroSettingTextField(
                                "强度",
                                gyroPreviewStrengthInput,
                                { gyroPreviewStrengthInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                            )
                            GyroSettingTextField(
                                "裁切",
                                gyroPreviewCropScaleInput,
                                { gyroPreviewCropScaleInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GyroSettingTextField(
                                "跟随",
                                gyroPreviewPathFollowInput,
                                { gyroPreviewPathFollowInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                            )
                            GyroSettingTextField(
                                "低通",
                                gyroPreviewOutputAlphaInput,
                                { gyroPreviewOutputAlphaInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GyroSettingTextField(
                                "限速",
                                gyroPreviewOutputRateLimitInput,
                                { gyroPreviewOutputRateLimitInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                            )
                            GyroSettingTextField(
                                "最大平移",
                                gyroPreviewMaxShiftInput,
                                { gyroPreviewMaxShiftInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                            )
                        }
                        GyroSettingTextField(
                            "输出加速度",
                            gyroPreviewOutputAccelerationLimitInput,
                            { gyroPreviewOutputAccelerationLimitInput = it; gyroPreviewSettingsError = null },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GyroSettingTextField(
                                "死区px",
                                gyroPreviewDeadbandInput,
                                { gyroPreviewDeadbandInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                            )
                            GyroSettingTextField(
                                "帧偏移ms",
                                gyroPreviewFrameTimestampOffsetInput,
                                { gyroPreviewFrameTimestampOffsetInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GyroSettingTextField(
                                "横向增益",
                                gyroPreviewHorizontalGainInput,
                                { gyroPreviewHorizontalGainInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                            )
                            GyroSettingTextField(
                                "纵向增益",
                                gyroPreviewVerticalGainInput,
                                { gyroPreviewVerticalGainInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GyroSettingTextField(
                                "软边界开始",
                                gyroPreviewSoftBoundaryStartRatioInput,
                                { gyroPreviewSoftBoundaryStartRatioInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewSoftBoundaryEnabledInput,
                            )
                            GyroSettingTextField(
                                "软边界强度",
                                gyroPreviewSoftBoundaryStrengthInput,
                                { gyroPreviewSoftBoundaryStrengthInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewSoftBoundaryEnabledInput,
                            )
                        }

                        Text("重新瞄准", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GyroSettingTextField(
                                "转速阈值",
                                gyroPreviewReAimRpsThresholdInput,
                                { gyroPreviewReAimRpsThresholdInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewReAimEnabledInput,
                            )
                            GyroSettingTextField(
                                "停稳ms",
                                gyroPreviewReAimStillMsInput,
                                { gyroPreviewReAimStillMsInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewReAimEnabledInput,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GyroSettingTextField(
                                "进入残留",
                                gyroPreviewReAimResidualEnterRatioInput,
                                { gyroPreviewReAimResidualEnterRatioInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewReAimEnabledInput,
                            )
                            GyroSettingTextField(
                                "退出残留",
                                gyroPreviewReAimResidualExitRatioInput,
                                { gyroPreviewReAimResidualExitRatioInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewReAimEnabledInput,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GyroSettingTextField(
                                "重瞄跟随",
                                gyroPreviewReAimPathFollowInput,
                                { gyroPreviewReAimPathFollowInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewReAimEnabledInput,
                            )
                            GyroSettingTextField(
                                "混入",
                                gyroPreviewReAimBlendInInput,
                                { gyroPreviewReAimBlendInInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewReAimEnabledInput,
                            )
                        }
                        GyroSettingTextField(
                            "混出",
                            gyroPreviewReAimBlendOutInput,
                            { gyroPreviewReAimBlendOutInput = it; gyroPreviewSettingsError = null },
                            enabled = gyroPreviewReAimEnabledInput,
                        )

                        Text("倍率/瞄准", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GyroSettingTextField(
                                "补偿强度",
                                gyroPreviewZoomCompensationStrengthInput,
                                { gyroPreviewZoomCompensationStrengthInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewZoomCompensationEnabledInput,
                            )
                            GyroSettingTextField(
                                "补偿上限",
                                gyroPreviewMaxCompensationZoomInput,
                                { gyroPreviewMaxCompensationZoomInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewZoomCompensationEnabledInput,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GyroSettingTextField(
                                "瞄准阈值",
                                gyroPreviewAimRpsThresholdInput,
                                { gyroPreviewAimRpsThresholdInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewAimStabilizationEnabledInput,
                            )
                            GyroSettingTextField(
                                "瞄准强度",
                                gyroPreviewAimStrengthMultiplierInput,
                                { gyroPreviewAimStrengthMultiplierInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewAimStabilizationEnabledInput,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GyroSettingTextField(
                                "瞄准跟随",
                                gyroPreviewAimPathFollowInput,
                                { gyroPreviewAimPathFollowInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewAimStabilizationEnabledInput,
                            )
                            GyroSettingTextField(
                                "瞄准死区",
                                gyroPreviewAimDeadbandInput,
                                { gyroPreviewAimDeadbandInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewAimStabilizationEnabledInput,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GyroSettingTextField(
                                "瞄准限速",
                                gyroPreviewAimOutputRateLimitInput,
                                { gyroPreviewAimOutputRateLimitInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewAimStabilizationEnabledInput,
                            )
                            GyroSettingTextField(
                                "瞄准加速度",
                                gyroPreviewAimOutputAccelerationLimitInput,
                                { gyroPreviewAimOutputAccelerationLimitInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewAimStabilizationEnabledInput,
                            )
                        }
                        GyroSettingTextField(
                            "残留释放转速",
                            gyroPreviewResidualReleaseRpsThresholdInput,
                            { gyroPreviewResidualReleaseRpsThresholdInput = it; gyroPreviewSettingsError = null },
                        )

                        Text("Roll", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GyroSettingTextField(
                                "Roll强度",
                                gyroPreviewRollStrengthInput,
                                { gyroPreviewRollStrengthInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewRollCompensationEnabledInput,
                            )
                            GyroSettingTextField(
                                "Roll最大角度",
                                gyroPreviewMaxRollDegreesInput,
                                { gyroPreviewMaxRollDegreesInput = it; gyroPreviewSettingsError = null },
                                Modifier.weight(1f),
                                enabled = gyroPreviewRollCompensationEnabledInput,
                            )
                        }

                        gyroPreviewSettingsError?.let { error ->
                            Text(error, color = Color(0xFFFF5252), fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { saveGyroPreviewSettings() }) { Text("保存") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { resetGyroPreviewSettingInputsToDefault() }) { Text("默认") }
                        TextButton(onClick = { showGyroPreviewSettingsDialog = false }) { Text("取消") }
                    }
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

private fun captureStabilizationCalibrationFrame(
    textureView: TextureView,
    transform: GyroPreviewStabilizer.Transform,
    sampleMode: StabilizationCalibrationSampleMode,
): StabilizationCalibrationFrame? {
    if (!textureView.isAvailable || textureView.width <= 0 || textureView.height <= 0) {
        return null
    }
    val viewWidth = textureView.width
    val viewHeight = textureView.height
    val sampleWidth = sampleMode.sampleWidth
    val sampleHeight = ((sampleWidth.toFloat() * viewHeight) / viewWidth)
        .roundToInt()
        .coerceIn(90, sampleMode.maxSampleHeight)
    val frameBuildStartMs = android.os.SystemClock.elapsedRealtime()
    val bitmap = try {
        textureView.getBitmap(sampleWidth, sampleHeight)
    } catch (e: RuntimeException) {
        Log.w("CameraScreen", "gyro-calib: capture failed: ${e.message}")
        null
    } ?: return null
    val bitmapCapturedMs = android.os.SystemClock.elapsedRealtime()

    return try {
        val lumaStartMs = android.os.SystemClock.elapsedRealtime()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val luma = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val red = pixel shr 16 and 0xFF
            val green = pixel shr 8 and 0xFF
            val blue = pixel and 0xFF
            luma[i] = (red * 77 + green * 150 + blue * 29) shr 8
        }
        val frameReadyMs = android.os.SystemClock.elapsedRealtime()
        StabilizationCalibrationFrame(
            timestampMs = frameReadyMs,
            width = bitmap.width,
            height = bitmap.height,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            sampleModeLabel = sampleMode.label,
            templateSize = sampleMode.templateSize,
            searchRadius = sampleMode.searchRadius,
            luma = luma,
            captureCostMs = bitmapCapturedMs - frameBuildStartMs,
            lumaCostMs = frameReadyMs - lumaStartMs,
            frameBuildCostMs = frameReadyMs - frameBuildStartMs,
            compensationX = transform.translationX,
            compensationY = transform.translationY,
            rotationDegrees = transform.rotationDegrees,
            rps = transform.rps,
            effectiveZoom = transform.effectiveZoom,
        )
    } finally {
        bitmap.recycle()
    }
}

private fun estimateStabilizationCalibrationShift(
    previous: StabilizationCalibrationFrame,
    current: StabilizationCalibrationFrame,
): StabilizationCalibrationResult? {
    if (previous.width != current.width || previous.height != current.height) return null
    val width = current.width
    val height = current.height
    val radius = current.searchRadius
    val templateSize = min(
        current.templateSize,
        min(width, height) - radius * 2 - 4,
    )
    if (templateSize < 24) return null

    val templateLeft = width / 2 - templateSize / 2
    val templateTop = height / 2 - templateSize / 2
    var bestSad = Long.MAX_VALUE
    var bestDx = 0
    var bestDy = 0
    var sampleCount = 0
    var sum = 0L
    var sumSquares = 0L

    for (dy in -radius..radius) {
        val currentTop = templateTop + dy
        if (currentTop < 0 || currentTop + templateSize >= height) continue
        for (dx in -radius..radius) {
            val currentLeft = templateLeft + dx
            if (currentLeft < 0 || currentLeft + templateSize >= width) continue

            var sad = 0L
            var localCount = 0
            var localSum = 0L
            var localSumSquares = 0L
            var y = 0
            while (y < templateSize) {
                val previousRow = (templateTop + y) * width + templateLeft
                val currentRow = (currentTop + y) * width + currentLeft
                var x = 0
                while (x < templateSize) {
                    val previousLuma = previous.luma[previousRow + x]
                    val currentLuma = current.luma[currentRow + x]
                    sad += abs(previousLuma - currentLuma).toLong()
                    localSum += previousLuma.toLong()
                    localSumSquares += previousLuma.toLong() * previousLuma.toLong()
                    localCount++
                    x += 2
                }
                y += 2
            }

            if (sad < bestSad) {
                bestSad = sad
                bestDx = dx
                bestDy = dy
                sampleCount = localCount
                sum = localSum
                sumSquares = localSumSquares
            }
        }
    }

    if (sampleCount <= 0 || bestSad == Long.MAX_VALUE) return null
    val mean = sum.toFloat() / sampleCount
    val variance = (sumSquares.toFloat() / sampleCount - mean * mean).coerceAtLeast(0f)
    val textureScore = (variance / 900f).coerceIn(0f, 1f)
    val normalizedSad = (bestSad.toFloat() / (sampleCount * 255f)).coerceIn(0f, 1f)
    val score = ((1f - normalizedSad) * textureScore).coerceIn(0f, 1f)
    if (score < GYRO_STABILIZATION_CALIBRATION_MIN_SCORE) return null

    val actualDxPx = bestDx * current.viewWidth.toFloat() / width
    val actualDyPx = bestDy * current.viewHeight.toFloat() / height
    return StabilizationCalibrationResult(
        actualDxPx = actualDxPx,
        actualDyPx = actualDyPx,
        score = score,
        sampleDx = bestDx,
        sampleDy = bestDy,
    )
}

private fun formatGyroRps(value: Float): String = String.format(Locale.US, "%.3f", value)

private fun formatYoloConfidence(value: Float): String = String.format(Locale.US, "%.2f", value)

private fun formatFloatSetting(value: Float): String = String.format(Locale.US, "%.2f", value)

@Composable
private fun GyroSettingSwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.42f),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun GyroSettingTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
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
    gyroText: String,
    modelText: String,
    statText: String,
    lensText: String,
    zoomText: String,
    anchorText: String,
    modifier: Modifier = Modifier,
) {
    val lines = listOf(statusText, lensText, gyroText, modelText, statText, zoomText, anchorText).filter { it.isNotBlank() }
    if (lines.isEmpty()) return

    Column(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        lines.take(6).forEachIndexed { index, text ->
            Text(
                text = text,
                color = when {
                    index == 0 -> Color.White.copy(alpha = 0.9f)
                    text.startsWith("画面镜头") -> Color(0xFFA5D6A7)
                    text.startsWith("自研EIS") || text.startsWith("防抖标定") -> Color(0xFF80DEEA)
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
