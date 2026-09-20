package com.example.auto_measure_luoji.feature.camera.domain.autohdr

import android.content.SharedPreferences

object AutoHdrPreferences {
    fun loadSettings(prefs: SharedPreferences): AutoHdrSettings = runCatching {
        AutoHdrSettings.parse(prefs.getString("auto_hdr_lower_ms", "0.3") ?: "",
            prefs.getString("auto_hdr_upper_ms", "25.0") ?: "",
            prefs.getInt("auto_hdr_cooldown_seconds", 10).toString()) ?: AutoHdrSettings()
    }.getOrDefault(AutoHdrSettings())

    fun loadEnabled(prefs: SharedPreferences): Boolean =
        runCatching { prefs.getBoolean("auto_hdr_enabled", false) }.getOrDefault(false)

    fun saveEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean("auto_hdr_enabled", enabled).apply()
    }

    fun saveSettings(prefs: SharedPreferences, settings: AutoHdrSettings) {
        require(settings.isValid)
        prefs.edit().putString("auto_hdr_lower_ms", settings.lowerMs.toString())
            .putString("auto_hdr_upper_ms", settings.upperMs.toString())
            .putInt("auto_hdr_cooldown_seconds", settings.cooldownSeconds).apply()
    }
}
