package com.example.auto_measure_luoji.feature.camera.ui.backlightbalance

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.feature.camera.domain.backlightbalance.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BacklightBalanceDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun validatesDraftAndSavesParametersWithSwitch() {
        var saved: BacklightBalanceSettings? = null
        var savedEnabled = false
        compose.setContent {
            MaterialTheme { BacklightBalanceDialog(BacklightBalanceSettings(), false, true, "测试",
                { value, enabled -> saved = value; savedEnabled = enabled }, {}) }
        }
        compose.onNodeWithContentDescription("逆光平衡开关").performClick().assertIsOn()
        compose.onNodeWithText(BacklightParameter.TARGET.label).performScrollTo().performTextReplacement("NaN")
        compose.onNodeWithText("保存并应用").performClick()
        assertNull(saved)
        compose.onNodeWithText(BacklightParameter.TARGET.label).performScrollTo().performTextReplacement("100")
        compose.onNodeWithText(BacklightParameter.CONFIRM.label).performScrollTo().performTextReplacement("4")
        compose.onNodeWithText(BacklightParameter.SETTLE.label).performScrollTo().performTextReplacement("2.5")
        compose.onNodeWithText("保存并应用").performClick()
        compose.runOnIdle {
            assertEquals(BacklightBalanceSettings(targetLuma = 100f, confirmations = 4, settleSeconds = 2.5f), saved)
            assertTrue(savedEnabled)
        }
    }

    @Test fun resetStaysDraftAndCancelNeverSaves() {
        var dismissed = false
        var saved: BacklightBalanceSettings? = null
        compose.setContent {
            MaterialTheme { BacklightBalanceDialog(BacklightBalanceSettings(targetLuma = 90f), false, false, "相机未就绪",
                { value, _ -> saved = value }, { dismissed = true }) }
        }
        compose.onNodeWithContentDescription("逆光平衡开关").assertIsNotEnabled()
        compose.onNodeWithText("恢复默认参数").performScrollTo().performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("保存并应用").performClick()
        compose.runOnIdle { assertEquals(BacklightBalanceSettings(), saved); saved = null }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(dismissed); assertNull(saved) }
    }

    @Test fun preferencesRoundTripAndCorruptStorageFallsBack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("backlight_balance_test_only", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            assertEquals(BacklightBalanceSettings(), BacklightBalancePreferences.load(prefs))
            val custom = BacklightBalanceSettings(targetLuma = 100f, protectBelow = 70, confirmations = 4)
            BacklightBalancePreferences.save(prefs, custom)
            assertEquals(custom, BacklightBalancePreferences.load(prefs))
            prefs.edit().putString("backlight_balance_PROTECT", "200").commit()
            assertEquals(BacklightBalanceSettings(), BacklightBalancePreferences.load(prefs))
            prefs.edit().putBoolean("backlight_balance_TARGET", true).commit()
            assertEquals(BacklightBalanceSettings(), BacklightBalancePreferences.load(prefs))
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun largeFontPanelScrollsThroughAllFields() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                MaterialTheme { BacklightBalanceDialog(BacklightBalanceSettings(), false, true, "逆光平衡已关闭", { _, _ -> }, {}) }
            }
        }
        capture("backlight-panel-top.png")
        for (parameter in BacklightParameter.entries) {
            compose.onNodeWithText(parameter.label).performScrollTo().assertIsDisplayed()
            if (parameter == BacklightParameter.ZONE_COUNT) capture("backlight-panel-highlights.png")
        }
        compose.onNodeWithText("保存并应用").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        capture("backlight-panel-bottom.png")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
