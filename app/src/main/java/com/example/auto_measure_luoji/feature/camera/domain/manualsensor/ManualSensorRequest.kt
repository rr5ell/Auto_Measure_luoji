package com.example.auto_measure_luoji.feature.camera.domain.manualsensor

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest

/** 每个会话独立保存自动曝光模板；选择不持久化，关闭会话即失效。 */
class ManualSensorRequest {
    @Volatile var capabilities = ManualSensorCapabilities()
        private set
    @Volatile var selection: ManualExposure? = null
        private set
    private var baseline: CaptureRequest? = null

    fun configure(chars: CameraCharacteristics) {
        val keys = chars.availableCaptureRequestKeys.orEmpty()
        val manual = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val exposure = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val frame = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 0
        val apertures = (chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf())
            .filter { it.isFinite() && it > 0f }.distinct().sorted()
        capabilities = if (manual && exposure != null && iso != null &&
            listOf(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.SENSOR_EXPOSURE_TIME,
                CaptureRequest.SENSOR_SENSITIVITY, CaptureRequest.SENSOR_FRAME_DURATION).all { it in keys })
            ManualSensorCapabilities(exposure.lower, minOf(exposure.upper, frame), iso.lower, iso.upper, frame,
                apertures, CaptureRequest.LENS_APERTURE in keys)
        else ManualSensorCapabilities(apertures = apertures)
    }

    fun select(builder: CaptureRequest.Builder, value: ManualExposure?) {
        value?.let { capabilities.validate(it) }
        if (value == null) restore(builder)
        selection = value
    }

    fun apply(builder: CaptureRequest.Builder) {
        val value = selection ?: return
        if (baseline == null) baseline = builder.build()
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, value.timeNs)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, value.iso)
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, capabilities.frameDuration(value))
        if (capabilities.variableAperture) builder.set(CaptureRequest.LENS_APERTURE,
            value.aperture ?: baseline?.get(CaptureRequest.LENS_APERTURE))
    }

    private fun restore(builder: CaptureRequest.Builder) {
        val saved = baseline ?: return
        builder.set(CaptureRequest.CONTROL_AE_MODE, saved.get(CaptureRequest.CONTROL_AE_MODE))
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, saved.get(CaptureRequest.SENSOR_EXPOSURE_TIME))
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, saved.get(CaptureRequest.SENSOR_SENSITIVITY))
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, saved.get(CaptureRequest.SENSOR_FRAME_DURATION))
        if (capabilities.variableAperture) builder.set(CaptureRequest.LENS_APERTURE, saved.get(CaptureRequest.LENS_APERTURE))
        baseline = null
    }
}
