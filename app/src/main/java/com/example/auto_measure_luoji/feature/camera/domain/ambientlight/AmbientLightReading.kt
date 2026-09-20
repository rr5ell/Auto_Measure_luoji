package com.example.auto_measure_luoji.feature.camera.domain.ambientlight

import java.util.Locale

data class AmbientLightReading(val status: Status, val lux: Float? = null) {
    enum class Status { WAITING, AVAILABLE, UNSUPPORTED, FAILED, INVALID, PAUSED }

    val panelText: String
        get() = "环境照度：" + when (status) {
            Status.AVAILABLE -> if (lux != null && lux.isFinite() && lux >= 0f) {
                String.format(Locale.ROOT, "%.1f lx", lux)
            } else "读数异常"
            Status.WAITING -> "等待读数"
            Status.UNSUPPORTED -> "无光线传感器"
            Status.FAILED -> "监听失败"
            Status.INVALID -> "读数异常"
            Status.PAUSED -> "已暂停"
        }

    companion object {
        fun fromLux(lux: Float?): AmbientLightReading =
            if (lux != null && lux.isFinite() && lux >= 0f) AmbientLightReading(Status.AVAILABLE, lux)
            else AmbientLightReading(Status.INVALID)
    }
}
