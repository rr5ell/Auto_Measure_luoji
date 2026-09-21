package com.example.auto_measure_luoji.feature.camera.ui.backlightshadow

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
import com.example.auto_measure_luoji.feature.camera.ui.localtone.PreviewToneMode
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraToolbarInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun topHasOnlyFourActionsAndMoreKeepsSettingsAndShadowPreference() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (permission in arrayOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        val prefs = context.getSharedPreferences("camera_screen_settings", Context.MODE_PRIVATE)
        val originalAuto = AutoHdrPreferences.loadEnabled(prefs)
        val originalTone = prefs.getString(PreviewToneMode.PREFERENCE_KEY, null)
        val keys = listOf(ShadowEnhancement.PREFERENCE_KEY, "gyro_preview_gl_render_enabled", "gyro_preview_stabilization_enabled")
        val original = keys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        AutoHdrPreferences.saveEnabled(prefs, false)
        prefs.edit().putString(PreviewToneMode.PREFERENCE_KEY, PreviewToneMode.OFF.name)
            .apply { keys.forEach { putBoolean(it, false) } }.commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitForCamera()
                compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,
                    androidx.compose.ui.semantics.Role.Button) and hasAnyAncestor(hasContentDescription("相机顶部操作栏")))
                    .assertCountEquals(4)
                for (text in listOf("识别", "校准", "唤醒", "更多")) compose.onNodeWithText(text).assertIsDisplayed()
                compose.onNodeWithText("EV / 对比度").assertDoesNotExist()
                compose.onNodeWithText("自动 HDR").assertDoesNotExist()
                compose.onNodeWithText("更多").performClick()
                compose.onNodeWithText("逆光暗部增强").assertIsOff().assertIsEnabled().performClick()
                compose.runOnIdle { assertTrue(prefs.getBoolean(ShadowEnhancement.PREFERENCE_KEY, false)) }
                scenario.recreate()
                waitForCamera()
                compose.onNodeWithText("更多").performClick()
                compose.onNodeWithText("逆光暗部增强").assertIsOn()
                compose.onNodeWithText("EV / 对比度").assertIsDisplayed()
                compose.onNodeWithText("自动 HDR").assertIsDisplayed()
                compose.onNodeWithText("LSC ", substring = true).assertIsDisplayed()
                compose.onNodeWithText("逆光暗部增强").performClick()
                compose.runOnIdle { assertFalse(prefs.getBoolean(ShadowEnhancement.PREFERENCE_KEY, true)) }
                compose.onNodeWithText("更多").performClick()
                compose.onNodeWithText("保边局部色调映射").assertIsOff().performClick()
                compose.onNodeWithText("更多").performClick()
                compose.onNodeWithText("保边局部色调映射").assertIsOn()
                compose.onNodeWithText("逆光暗部增强").assertIsOff()
                compose.onNodeWithText("Retinex").assertIsOff().performClick()
                scenario.recreate()
                waitForCamera()
                compose.onNodeWithText("更多").performClick()
                compose.onNodeWithText("Retinex").assertIsOn()
                compose.onNodeWithText("保边局部色调映射").assertIsOff()
                compose.onNodeWithText("逆光暗部增强").performClick()
                compose.onNodeWithText("更多").performClick()
                compose.onNodeWithText("Retinex").assertIsOff()
                compose.onNodeWithText("逆光暗部增强").assertIsOn().performClick()
                compose.onNodeWithText("更多").performClick()
                compose.onNodeWithText("EV / 对比度").performClick()
                compose.onNodeWithContentDescription("手动 EV").assertIsDisplayed()
            }
        } finally {
            AutoHdrPreferences.saveEnabled(prefs, originalAuto)
            prefs.edit().putString(PreviewToneMode.PREFERENCE_KEY, originalTone).commit()
            prefs.edit().apply { original.forEach { (key, value) ->
                if (value == null) remove(key) else putBoolean(key, value)
            } }.commit()
        }
    }

    private fun waitForCamera() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("识别") and isEnabled()).fetchSemanticsNodes().size == 1
        }
    }
}
