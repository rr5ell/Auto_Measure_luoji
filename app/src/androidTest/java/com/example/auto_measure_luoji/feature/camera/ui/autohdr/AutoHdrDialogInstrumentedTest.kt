package com.example.auto_measure_luoji.feature.camera.ui.autohdr

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.feature.camera.domain.autohdr.AutoHdrPreferences
import com.example.auto_measure_luoji.feature.camera.domain.autohdr.AutoHdrSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoHdrDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun automaticModeLocksManualSwitchAndValidatesSettings() {
        var saved: AutoHdrSettings? = null
        compose.setContent {
            var automatic by remember { mutableStateOf(false) }
            var hdr by remember { mutableStateOf(false) }
            MaterialTheme {
                AutoHdrDialog(AutoHdrSettings(), automatic, hdr, true, "HDR：已关闭（手动）",
                    { automatic = it }, { hdr = it }, { saved = it }, {})
            }
        }
        compose.onNodeWithContentDescription("HDR 预览").assertIsEnabled().performClick().assertIsOn()
        compose.onNodeWithContentDescription("自动 HDR 开关").performClick()
        compose.onNodeWithContentDescription("HDR 预览").assertIsNotEnabled()
        compose.onNodeWithContentDescription("自动 HDR 开关").performClick()
        compose.onNodeWithContentDescription("HDR 预览").assertIsEnabled().assertIsOn()
        compose.onNodeWithText("开启：曝光小于（ms）").performTextReplacement("NaN")
        compose.onNodeWithText("保存参数").performClick()
        compose.onNodeWithText("曝光阈值须为正数且下限小于上限；停判须为 1~3600 秒整数").assertExists()
        assertNull(saved)
        compose.onNodeWithText("开启：曝光小于（ms）").performTextReplacement("0.5")
        compose.onNodeWithText("开启：曝光大于（ms）").performTextReplacement("30")
        compose.onNodeWithText("切换后停判（秒）").performScrollTo().performTextReplacement("12")
        compose.onNodeWithText("保存参数").performClick()
        assertEquals(AutoHdrSettings(0.5, 30.0, 12), saved)
    }

    @Test fun busySessionLocksManualEvenWhenAutomaticIsOff() {
        compose.setContent {
            MaterialTheme { AutoHdrDialog(AutoHdrSettings(), false, true, false, "HDR：已开启（手动）", {}, {}, {}, {}) }
        }
        compose.onNodeWithContentDescription("HDR 预览").assertIsOn().assertIsNotEnabled()
    }

    @Test fun faultPausePreservesAutomaticSwitchAndRetryRespectsReadiness() {
        var retried = false
        var ready by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                AutoHdrDialog(AutoHdrSettings(), true, false, true, "HDR：已关闭（自动，故障暂停）",
                    { fail("故障恢复不得修改自动模式") }, {}, {}, {},
                    onRetry = { retried = true }, canRetry = ready)
            }
        }
        compose.onNodeWithContentDescription("自动 HDR 开关").assertIsOn()
        compose.onNodeWithContentDescription("HDR 预览").assertIsNotEnabled()
        compose.onNodeWithText("重新判断").assertIsNotEnabled()
        compose.runOnIdle { ready = true }
        compose.onNodeWithText("重新判断").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(retried) }
        compose.onNodeWithContentDescription("自动 HDR 开关").assertIsOn()
    }

    @Test fun preferencesPersistAndInvalidStorageFallsBack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("auto_hdr_test_only", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            assertFalse(AutoHdrPreferences.loadEnabled(prefs))
            assertEquals(AutoHdrSettings(), AutoHdrPreferences.loadSettings(prefs))
            val custom = AutoHdrSettings(0.5, 30.0, 12)
            AutoHdrPreferences.saveEnabled(prefs, true)
            AutoHdrPreferences.saveSettings(prefs, custom)
            assertTrue(AutoHdrPreferences.loadEnabled(prefs))
            assertEquals(custom, AutoHdrPreferences.loadSettings(prefs))
            prefs.edit().putString("auto_hdr_lower_ms", "NaN").commit()
            assertEquals(AutoHdrSettings(), AutoHdrPreferences.loadSettings(prefs))
            prefs.edit().putString("auto_hdr_cooldown_seconds", "broken").commit()
            assertEquals(AutoHdrSettings(), AutoHdrPreferences.loadSettings(prefs))
        } finally { prefs.edit().clear().commit() }
    }
}
