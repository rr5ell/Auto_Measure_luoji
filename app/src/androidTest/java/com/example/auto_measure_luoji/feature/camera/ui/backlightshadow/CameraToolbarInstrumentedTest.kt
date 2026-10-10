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
import com.example.auto_measure_luoji.feature.camera.ui.statuspanel.expandStatusPanel
import com.example.auto_measure_luoji.feature.camera.domain.autohdr.AutoHdrPreferences
import com.example.auto_measure_luoji.feature.camera.domain.backlightbalance.BacklightBalancePreferences
import com.example.auto_measure_luoji.feature.camera.ui.localtone.PreviewToneMode
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraToolbarInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun topHasFiveActionsAndMoreKeepsSettingsAndShadowPreference() {
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
        val originalBalance = BacklightBalancePreferences.load(prefs)
        val keys = listOf(ShadowEnhancement.PREFERENCE_KEY, "tap_exposure_metering_enabled",
            "gyro_preview_gl_render_enabled", "gyro_preview_stabilization_enabled")
        val original = keys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        AutoHdrPreferences.saveEnabled(prefs, false)
        prefs.edit().putString(PreviewToneMode.PREFERENCE_KEY, PreviewToneMode.OFF.name)
            .apply { keys.forEach { putBoolean(it, false) } }.commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitForCamera()
                compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,
                    androidx.compose.ui.semantics.Role.Button) and hasAnyAncestor(hasContentDescription("相机顶部操作栏")))
                    .assertCountEquals(5)
                compose.onNodeWithContentDescription("保存当前预览").assertIsDisplayed()
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
                compose.onNodeWithText("轻量眩光抑制").performScrollTo().assertIsOff().performClick()
                scenario.recreate()
                waitForCamera()
                compose.onNodeWithText("更多").performClick()
                compose.onNodeWithText("轻量眩光抑制").performScrollTo().assertIsOn()
                compose.onNodeWithText("Retinex").performScrollTo().assertIsOff()
                compose.onNodeWithText("轻量眩光抑制").performScrollTo().performClick()
                compose.onNodeWithText("更多").performClick()
                compose.onNodeWithText("轻量眩光抑制").performScrollTo().assertIsOff().performClick()
                compose.onNodeWithText("更多").performClick()
                compose.onNodeWithText("逆光平衡").performScrollTo().performClick()
                compose.onNodeWithContentDescription("逆光平衡开关").assertIsOff().performClick()
                compose.onNodeWithText("综合亮度目标（Y）").performScrollTo().performTextReplacement("100")
                compose.onNodeWithText("保存并应用").performClick()
                compose.runOnIdle { assertEquals(100f, BacklightBalancePreferences.load(prefs).targetLuma, 0f) }
                val autoAdvance = compose.mainClock.autoAdvance
                compose.mainClock.autoAdvance = false
                try {
                    compose.waitUntil(15_000) {
                        // Camera2/TextureView使用真实时钟，曝光协程的delay由Compose测试时钟驱动。
                        // 每轮同时留出真实帧更新时间并显式跨过500ms轮询，不能只等待语义树。
                        Thread.sleep(250)
                        compose.mainClock.advanceTimeBy(500)
                        compose.onAllNodes(hasText("来源：TextureView", substring = true)).fetchSemanticsNodes().isNotEmpty()
                    }
                } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                    throw AssertionError("TextureView测光未接通：" + compose.onRoot().printToString(), error)
                } finally {
                    compose.mainClock.autoAdvance = autoAdvance
                }
                AutoHdrPreferences.saveEnabled(prefs, true)
                scenario.recreate()
                waitForCamera()
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasText("逆光平衡暂停判断", substring = true)).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("逆光平衡状态").assertExists()
                compose.onNodeWithText("更多").performClick()
                compose.onNodeWithText("逆光平衡").performScrollTo().performClick()
                compose.onNodeWithContentDescription("逆光平衡开关").assertIsOn()
                compose.onNodeWithText("综合亮度目标（Y）").performScrollTo().assertTextContains("100.0")
                compose.onNodeWithText("取消").performClick()
                compose.onNodeWithText("更多").performClick()
                compose.onNodeWithText("轻量眩光抑制").performScrollTo().assertIsOff()
                compose.onNodeWithText("逆光暗部增强").performScrollTo()
                compose.onNodeWithText("逆光暗部增强").performClick()
                compose.onNodeWithText("更多").performClick()
                compose.onNodeWithText("轻量眩光抑制").performScrollTo().assertIsOff()
                compose.onNodeWithText("Retinex").performScrollTo()
                compose.onNodeWithText("Retinex").assertIsOff()
                compose.onNodeWithText("逆光暗部增强").assertIsOn().performClick()
                compose.onNodeWithText("更多").performClick()
                compose.onNodeWithText("EV / 对比度").performScrollTo().performClick()
                compose.onNodeWithContentDescription("手动 EV").assertIsDisplayed()
            }
        } finally {
            BacklightBalancePreferences.save(prefs, originalBalance)
            AutoHdrPreferences.saveEnabled(prefs, originalAuto)
            prefs.edit().putString(PreviewToneMode.PREFERENCE_KEY, originalTone).commit()
            prefs.edit().apply { original.forEach { (key, value) ->
                if (value == null) remove(key) else putBoolean(key, value)
            } }.commit()
        }
    }

    private fun waitForCamera() {
        compose.expandStatusPanel()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("识别") and isEnabled()).fetchSemanticsNodes().size == 1
        }
    }
}
