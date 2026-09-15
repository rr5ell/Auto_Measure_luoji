package com.example.auto_measure_luoji.feature.camera.domain.hdr

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.os.Build
import android.util.Log
import com.example.auto_measure_luoji.feature.camera.data.LensCandidate

object HdrPreviewCapabilities {
    /** 能力声明只用于准入，物理镜头与输出尺寸是否兼容仍由实际会话验证。 */
    fun checkCamera(context: Context, lens: LensCandidate): Result<String> {
        if (Build.VERSION.SDK_INT < 33) {
            return Result.failure(IllegalStateException("HDR 预览需要 Android 13 或以上版本"))
        }
        return checkCameraApi33(context, lens)
    }

    @androidx.annotation.RequiresApi(33)
    private fun checkCameraApi33(context: Context, lens: LensCandidate): Result<String> = runCatching {
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
        // 普通预览使用父逻辑相机 Session，能力检查必须与实际输出相机一致。
        val extraLatency = profiles!!.isExtraLatencyPresent(DynamicRangeProfiles.HLG10)
        val message = "camera=${lens.cameraId}, output=logical, profile=HLG10, extraLatency=$extraLatency"
        Log.i("HdrPreview", "hdr-capability: $message")
        message
    }
}
