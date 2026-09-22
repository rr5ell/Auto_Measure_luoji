package com.example.auto_measure_luoji.feature.camera.domain.centermetering

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.MainActivity
import com.example.auto_measure_luoji.feature.camera.domain.autohdr.AutoHdrPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CenterMeteringUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun togglePersistsAndDefaultMeteringReturns() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_SCAN)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_CONNECT)
        }
        val prefs = context.getSharedPreferences("camera_screen_settings", Context.MODE_PRIVATE)
        val keys = listOf(CenterMeteringRegion.PREFERENCE_KEY, "tap_exposure_metering_enabled",
            "gyro_preview_gl_render_enabled", "gyro_preview_stabilization_enabled")
        val old = keys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        val oldAuto = AutoHdrPreferences.loadEnabled(prefs)
        AutoHdrPreferences.saveEnabled(prefs, false)
        prefs.edit().apply { keys.forEach { putBoolean(it, false) } }.commit()
        fun waitForStatus(text: String) {
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasContentDescription("测光状态") and hasText(text))
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitForStatus("测光：默认")
                compose.onNodeWithText("更多").performClick()
                val unavailable = compose.onAllNodesWithText("中心区域测光（不可用）").fetchSemanticsNodes().isNotEmpty()
                if (unavailable) {
                    compose.onNodeWithText("中心区域测光（不可用）").performScrollTo().assertIsOff().assertIsNotEnabled()
                } else {
                    compose.onNodeWithText("中心区域测光").performScrollTo().assertIsOff().performClick()
                    waitForStatus("测光：中心 30%（已请求）")
                    assertTrue(prefs.getBoolean(CenterMeteringRegion.PREFERENCE_KEY, false))
                    scenario.recreate()
                    waitForStatus("测光：中心 30%（已请求）")
                    compose.onNodeWithText("更多").performClick()
                    compose.onNodeWithText("中心区域测光").performScrollTo().assertIsOn().performClick()
                    waitForStatus("测光：默认")
                    assertFalse(prefs.getBoolean(CenterMeteringRegion.PREFERENCE_KEY, true))
                    compose.onNodeWithText("更多").performClick()
                    compose.onNodeWithText("中心区域测光").performScrollTo().assertIsOff()
                }
            }
        } finally {
            AutoHdrPreferences.saveEnabled(prefs, oldAuto)
            prefs.edit().apply { old.forEach { (key, value) ->
                if (value == null) remove(key) else putBoolean(key, value)
            } }.commit()
        }
    }
}
