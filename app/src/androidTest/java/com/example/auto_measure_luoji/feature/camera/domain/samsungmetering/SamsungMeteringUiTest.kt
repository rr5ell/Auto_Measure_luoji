package com.example.auto_measure_luoji.feature.camera.domain.samsungmetering

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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SamsungMeteringUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun waitStatus(text: String) = compose.waitUntil(30_000) {
        compose.onAllNodes(hasContentDescription("三星测光状态") and hasText(text, substring = true))
            .fetchSemanticsNodes().isNotEmpty()
    }

    private fun selectMode(label: String, previous: String) {
        compose.onNodeWithText("更多").performClick()
        compose.onNodeWithText("三星测光（实验）").performScrollTo().performClick()
        compose.onNodeWithText(previous).assertIsSelected()
        compose.onNodeWithText(label).performClick()
    }

    @Test fun toggleRestoresTemplateAndDoesNotPersistInTextureAndGl() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("samsungMeteringProbe") == "true")
        assumeTrue(Build.MANUFACTURER.equals("samsung", ignoreCase = true))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_SCAN)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_CONNECT)
        }
        val prefs = context.getSharedPreferences("camera_screen_settings", Context.MODE_PRIVATE)
        val keys = listOf("gyro_preview_gl_render_enabled", "gyro_preview_stabilization_enabled")
        val original = keys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        val oldAuto = AutoHdrPreferences.loadEnabled(prefs)
        AutoHdrPreferences.saveEnabled(prefs, false)
        try {
            for (gl in listOf(false, true)) {
                prefs.edit().putBoolean(keys[0], gl).putBoolean(keys[1], false).commit()
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitStatus("关闭（请求 0，回传 0）")
                    selectMode("模式3", "关闭（恢复原值）")
                    waitStatus("开启（请求 3，回传 3）")
                    selectMode("模式4", "模式3")
                    waitStatus("开启（请求 4，回传 4）")
                    selectMode("关闭（恢复原值）", "模式4")
                    waitStatus("关闭（请求 0，回传 0）")
                    selectMode("模式3", "关闭（恢复原值）")
                    waitStatus("开启（请求 3，回传 3）")
                    scenario.recreate()
                    waitStatus("关闭（请求 0，回传 0）")
                }
            }
        } finally {
            AutoHdrPreferences.saveEnabled(prefs, oldAuto)
            prefs.edit().apply {
                original.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) }
            }.commit()
        }
    }
}
