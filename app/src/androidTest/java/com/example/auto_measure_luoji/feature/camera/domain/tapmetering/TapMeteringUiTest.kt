package com.example.auto_measure_luoji.feature.camera.domain.tapmetering

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.MainActivity
import com.example.auto_measure_luoji.feature.camera.ui.statuspanel.expandStatusPanel
import com.example.auto_measure_luoji.feature.camera.domain.autohdr.AutoHdrPreferences
import com.example.auto_measure_luoji.feature.camera.domain.centermetering.CenterMeteringRegion
import com.example.auto_measure_luoji.feature.camera.ui.localtone.PreviewToneMode
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TapMeteringUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun waitStatus(text: String) = compose.waitUntil(30_000) {
        compose.onAllNodes(hasContentDescription("测光状态") and hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitFocus(active: Boolean) = compose.waitUntil(10_000) {
        val states = if (active) listOf("对焦：已触发，等待结果", "对焦：搜索中", "对焦：已合焦（点击）", "对焦：未合焦（可重新点击）")
            else listOf("对焦：自动", "对焦：超时，已恢复自动")
        states.any { compose.onAllNodes(hasContentDescription("点击对焦状态") and hasText(it)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun tapWorksInTextureAndGlPersistsSwitchButResetsPointAndRestoresCenter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_SCAN)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_CONNECT)
        }
        val prefs = context.getSharedPreferences("camera_screen_settings", Context.MODE_PRIVATE)
        val keys = listOf(TapMeteringPoint.PREFERENCE_KEY, CenterMeteringRegion.PREFERENCE_KEY,
            "gyro_preview_gl_render_enabled", "gyro_preview_stabilization_enabled")
        val original = keys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        val oldTone = prefs.getString(PreviewToneMode.PREFERENCE_KEY, null)
        val oldAuto = AutoHdrPreferences.loadEnabled(prefs)
        AutoHdrPreferences.saveEnabled(prefs, false)
        try {
            for (gl in listOf(false, true)) {
                prefs.edit().putBoolean(TapMeteringPoint.PREFERENCE_KEY, true)
                    .putBoolean(CenterMeteringRegion.PREFERENCE_KEY, true)
                    .putBoolean("gyro_preview_gl_render_enabled", gl)
                    .putBoolean("gyro_preview_stabilization_enabled", false)
                    .putString(PreviewToneMode.PREFERENCE_KEY, PreviewToneMode.BACKLIGHT_BALANCE.name).commit()
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    compose.expandStatusPanel()
                    compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("测光状态") and
                        hasText("测光：等待相机").not()).fetchSemanticsNodes().isNotEmpty() }
                    assumeTrue(compose.onAllNodesWithText("测光：默认（镜头不支持点击区域）").fetchSemanticsNodes().isEmpty())
                    waitStatus("测光：等待点击")
                    compose.onNodeWithContentDescription("ISO 和光圈").assertTextContains("ISO：", substring = true)
                    compose.onNodeWithContentDescription("逆光平衡状态").assertTextEquals("逆光平衡：点击测光暂停自动 EV")
                    compose.onNodeWithContentDescription("点击测光预览").performTouchInput {
                        swipe(Offset(width * 0.55f, height * 0.6f), Offset(width * 0.8f, height * 0.7f), 300)
                    }
                    waitStatus("测光：等待点击")
                    compose.onNodeWithContentDescription("点击测光预览").performTouchInput {
                        click(Offset(width * 0.65f, height * 0.60f))
                    }
                    waitStatus("测光：点击区域（已请求）")
                    waitFocus(true)
                    waitFocus(false)
                    // 同一个点再次点击也必须重新触发，不能被 Compose 相等坐标去重。
                    compose.onNodeWithContentDescription("点击测光预览").performTouchInput {
                        click(Offset(width * 0.65f, height * 0.60f))
                    }
                    waitFocus(true)
                    scenario.recreate()
                    waitStatus("测光：等待点击")
                    waitFocus(false)
                    compose.onNodeWithText("更多").performClick()
                    compose.onNodeWithText("点击测光与对焦").performScrollTo().assertIsOn().performClick()
                    waitStatus("测光：中心 30%（已请求）")
                    waitFocus(false)
                    assertFalse(prefs.getBoolean(TapMeteringPoint.PREFERENCE_KEY, true))
                    compose.onNodeWithContentDescription("点击测光预览").assertDoesNotExist()
                }
            }
        } finally {
            AutoHdrPreferences.saveEnabled(prefs, oldAuto)
            prefs.edit().putString(PreviewToneMode.PREFERENCE_KEY, oldTone).apply {
                original.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) }
            }.commit()
        }
    }
}
