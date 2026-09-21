package com.example.auto_measure_luoji.feature.camera.ui.localtone

enum class PreviewToneMode(val shaderValue: Int, val label: String) {
    OFF(0, "原始预览"),
    LOCAL_TONE(1, "保边局部色调映射"),
    RETINEX(2, "Retinex"),
    GLARE_SUPPRESSION(3, "轻量眩光抑制"),
    BACKLIGHT_BALANCE(4, "逆光平衡");

    fun forCalibration(active: Boolean) = if (active) OFF else this
    fun toggled(selected: PreviewToneMode) = if (this == selected) OFF else selected

    companion object {
        const val PREFERENCE_KEY = "preview_local_tone_mode"
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: OFF
    }
}
