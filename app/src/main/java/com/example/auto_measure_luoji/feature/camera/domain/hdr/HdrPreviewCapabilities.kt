package com.example.auto_measure_luoji.feature.camera.domain.hdr

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.os.Build
import android.util.Log
import com.example.auto_measure_luoji.feature.camera.data.LensCandidate

object HdrPreviewCapabilities {
    fun checkYuvAnalysis(context: Context, lens: LensCandidate): Result<Unit> {
        if (Build.VERSION.SDK_INT < 33) {
            return Result.failure(IllegalStateException("HDR 识别需要 Android 13 或以上版本"))
        }
        return checkYuvAnalysisApi33(context, lens)
    }

    @androidx.annotation.RequiresApi(33)
    private fun checkYuvAnalysisApi33(context: Context, lens: LensCandidate): Result<Unit> = runCatching {
        checkCamera(context, lens).getOrThrow()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val profiles = manager.getCameraCharacteristics(lens.cameraId)
            .get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            ?: error("相机未提供动态范围配置")
        val requested = setOf(DynamicRangeProfiles.HLG10, DynamicRangeProfiles.STANDARD)
        check(profiles.supportedProfiles.containsAll(requested)) { "相机未开放 HDR 与 SDR 双路输出" }
        val constraints = requested.associateWith { profiles.getProfileCaptureRequestConstraints(it) }
        check(canShareDynamicRangeProfiles(requested, profiles.supportedProfiles, constraints)) {
            "当前相机不允许 HDR 预览与 YUV 模型识别同时运行"
        }
        Log.i("HdrPreview", "hdr-yuv: camera=${lens.cameraId} constraints=$constraints supported=true")
    }

    /** 能力声明只用于准入，物理镜头与输出尺寸是否兼容仍由实际会话验证。 */
    fun checkCamera(context: Context, lens: LensCandidate, strictPhysical: Boolean = false): Result<String> {
        if (Build.VERSION.SDK_INT < 33) {
            return Result.failure(IllegalStateException("HDR 预览需要 Android 13 或以上版本"))
        }
        return checkCameraApi33(context, lens, strictPhysical)
    }

    @androidx.annotation.RequiresApi(33)
    private fun checkCameraApi33(context: Context, lens: LensCandidate, strictPhysical: Boolean): Result<String> = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val logical = manager.getCameraCharacteristics(lens.cameraId)
        val capabilities = logical.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        check(capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT) == true) {
            "当前相机未开放 10-bit HDR 输出"
        }
        val profiles = logical.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
        check(profiles?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true) {
            "当前相机不支持 HLG10 预览"
        }
        if (strictPhysical && lens.physicalId != null) {
            check(lens.physicalId in logical.physicalCameraIds) { "校准镜头不属于当前逻辑相机" }
            val physical = manager.getCameraCharacteristics(lens.physicalId)
            val physicalProfiles = physical.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            check(physicalProfiles?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true) {
                "物理镜头 ${lens.physicalId} 未开放 HLG10，无法在 HDR 下进行双摄校准"
            }
        }
        // 普通预览使用父逻辑相机 Session，能力检查必须与实际输出相机一致。
        val extraLatency = profiles!!.isExtraLatencyPresent(DynamicRangeProfiles.HLG10)
        val message = "camera=${lens.cameraId}, output=${if (strictPhysical) lens.physicalId else "logical"}, profile=HLG10, extraLatency=$extraLatency"
        Log.i("HdrPreview", "hdr-capability: $message")
        message
    }
}

/** 空约束表示无限制，同 profile 总能共存；不同 profile 必须双向满足约束。 */
internal fun canShareDynamicRangeProfiles(requested: Set<Long>, supported: Set<Long>,
    constraints: Map<Long, Set<Long>>): Boolean =
    requested.isNotEmpty() && supported.containsAll(requested) && requested.all { profile ->
        val allowed = constraints[profile] ?: return@all false
        allowed.isEmpty() || requested.all { it == profile || it in allowed }
    }
