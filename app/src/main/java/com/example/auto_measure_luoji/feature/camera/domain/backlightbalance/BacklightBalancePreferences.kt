package com.example.auto_measure_luoji.feature.camera.domain.backlightbalance

import android.content.SharedPreferences

object BacklightBalancePreferences {
    private const val PREFIX = "backlight_balance_"

    fun load(prefs: SharedPreferences): BacklightBalanceSettings = runCatching {
        val defaults = BacklightBalanceSettings().fields()
        BacklightBalanceSettings.parse(BacklightParameter.entries.associateWith {
            prefs.getString(PREFIX + it.name, defaults.getValue(it)) ?: ""
        }) ?: BacklightBalanceSettings()
    }.getOrDefault(BacklightBalanceSettings())

    fun save(prefs: SharedPreferences, settings: BacklightBalanceSettings) {
        require(settings.validationError == null)
        val editor = prefs.edit()
        settings.fields().forEach { (parameter, value) -> editor.putString(PREFIX + parameter.name, value) }
        editor.apply()
    }
}
