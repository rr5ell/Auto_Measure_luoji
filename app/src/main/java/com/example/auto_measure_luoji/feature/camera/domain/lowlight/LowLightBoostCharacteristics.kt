package com.example.auto_measure_luoji.feature.camera.domain.lowlight

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log

object LowLightBoostCharacteristics {
    /** 仅查询 characteristics；绝不提交 AE、HDR 或 EIS 请求。可选字段缺失不阻断预览。 */
    fun read(chars: CameraCharacteristics, cameraId: String, physical: Boolean): LowLightBoostInfo {
        val info = if (Build.VERSION.SDK_INT < 35) {
            LowLightBoostInfo(cameraId, physical, apiAvailable = false)
        } else {
            try {
                val range = chars.get(CameraCharacteristics.CONTROL_LOW_LIGHT_BOOST_INFO_LUMINANCE_RANGE)
                val aeSupported = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
                    ?.contains(CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY)
                LowLightBoostInfo(cameraId, physical, true, range?.lower, range?.upper, aeSupported)
            } catch (error: RuntimeException) {
                LowLightBoostInfo(cameraId, physical, true,
                    readError = "${error.javaClass.simpleName}: ${error.message}")
            }
        }
        Log.i("Camera2Ctrl", info.logText)
        return info
    }
}
