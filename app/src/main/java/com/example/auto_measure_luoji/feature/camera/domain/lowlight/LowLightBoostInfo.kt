package com.example.auto_measure_luoji.feature.camera.domain.lowlight

/** 静态能力，不是实时照度，也不代表低光增强已开启。保留 Float 原始精度。 */
data class LowLightBoostInfo(
    val cameraId: String,
    val physical: Boolean,
    val apiAvailable: Boolean,
    val lowerLux: Float? = null,
    val upperLux: Float? = null,
    val aeModeSupported: Boolean? = null,
    val readError: String? = null,
) {
    val rangeText: String
        get() = when {
            !apiAvailable -> "需 Android 15+"
            readError != null -> "读取失败"
            lowerLux == null && upperLux == null -> "未提供"
            lowerLux == null || upperLux == null || !lowerLux.isFinite() || !upperLux.isFinite() ||
                lowerLux < 0f || upperLux < lowerLux -> "设备返回异常区间"
            else -> "$lowerLux ~ $upperLux lx"
        }

    val panelText: String
        get() = "低光增强范围[${if (physical) "物理" else "逻辑"}$cameraId]：\n$rangeText"

    val logText: String
        get() = "low-light-boost: camera=$cameraId output=${if (physical) "physical" else "logical"} " +
            "CONTROL_LOW_LIGHT_BOOST_INFO_LUMINANCE_RANGE=" +
            (if (lowerLux == null && upperLux == null) "null" else "[$lowerLux, $upperLux]") +
            " unit=lx apiAvailable=$apiAvailable aeLowLightBoostSupported=$aeModeSupported " +
            "status=$rangeText error=${readError ?: "none"}"
}
