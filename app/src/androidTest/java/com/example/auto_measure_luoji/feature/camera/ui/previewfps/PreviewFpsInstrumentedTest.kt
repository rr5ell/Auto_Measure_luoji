package com.example.auto_measure_luoji.feature.camera.ui.previewfps

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewFpsInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun previewFpsUpdatesWithoutYoloInTextureAndGlModes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_SCAN)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_CONNECT)
        }
        val prefs = context.getSharedPreferences("camera_screen_settings", Context.MODE_PRIVATE)
        val key = "gyro_preview_gl_render_enabled"
        val hadValue = prefs.contains(key)
        val oldValue = prefs.getBoolean(key, false)
        try {
            for (gl in listOf(false, true)) {
                check(prefs.edit().putBoolean(key, gl).commit())
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitForPositiveFps()
                    scenario.moveToState(Lifecycle.State.CREATED)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    waitForPositiveFps()
                }
            }
        } finally {
            val edit = prefs.edit()
            if (hadValue) edit.putBoolean(key, oldValue) else edit.remove(key)
            check(edit.commit())
        }
    }

    private fun waitForPositiveFps() {
        compose.waitUntil(45_000) {
            compose.onAllNodesWithText("预览 FPS：", substring = true).fetchSemanticsNodes().any { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text ->
                    (text.text.removePrefix("预览 FPS：").toDoubleOrNull() ?: 0.0) > 0.0
                }
            }
        }
    }
}
