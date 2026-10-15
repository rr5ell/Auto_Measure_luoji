package com.example.auto_measure_luoji.feature.detection.modelselection

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class YoloModelCameraTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun ordinaryPreviewSwitchesAndRemembersModel() = switchModels(false)
    @Test fun glPreviewSwitchesAndRemembersModel() = switchModels(true)
    @Test fun ordinaryPreviewSwitchesToV35() = switchModels(false, YoloModelOption.V35)
    @Test fun glPreviewSwitchesToV35() = switchModels(true, YoloModelOption.V35)

    private fun switchModels(gl: Boolean, target: YoloModelOption = YoloModelOption.V29) {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        val prefs = context.getSharedPreferences("camera_screen_settings", Context.MODE_PRIVATE)
        val modelKey = YoloModelOption.PREFERENCE_KEY
        val oldModel = prefs.getString(modelKey, null)
        val keys = listOf("gyro_preview_gl_render_enabled", "gyro_preview_stabilization_enabled")
        val original = keys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        prefs.edit().putString(modelKey, YoloModelOption.V34.assetPath)
            .putBoolean(keys[0], gl).putBoolean(keys[1], false).commit()
        fun waitReady() {
            compose.waitUntil(60_000) {
                compose.onAllNodes(hasText("识别") and isEnabled()).fetchSemanticsNodes().size == 1
            }
        }
        fun openModels(label: String) {
            compose.onNodeWithText("更多").performClick()
            compose.onNodeWithText("识别模型 $label").performScrollTo().performClick()
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitReady()
                compose.onNodeWithContentDescription("当前识别模型").assertTextEquals("模型: V34").assertIsDisplayed()
                compose.onNodeWithText("识别").performClick()
                openModels("V34")
                compose.onNodeWithText(target.label).performClick()
                compose.waitUntil(60_000) {
                    compose.onAllNodes(hasText("${target.label} 已加载", substring = true)).fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(target.assetPath, prefs.getString(modelKey, null))
                compose.onNodeWithText("关闭").performClick()
                compose.onNodeWithContentDescription("当前识别模型").assertTextEquals("模型: ${target.label}").assertIsDisplayed()
                compose.onNodeWithText("识别").assertIsEnabled()
                compose.onNodeWithText("识别中").assertDoesNotExist()
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                waitReady()
                openModels(target.label)
                compose.onNodeWithText(target.label).assertIsSelected()
                compose.onNodeWithText("V34").performClick()
                compose.waitUntil(60_000) {
                    compose.onAllNodes(hasText("V34 已加载", substring = true)).fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(YoloModelOption.V34.assetPath, prefs.getString(modelKey, null))
            }
        } finally {
            prefs.edit().apply {
                if (oldModel == null) remove(modelKey) else putString(modelKey, oldModel)
                original.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) }
            }.commit()
        }
    }
}
