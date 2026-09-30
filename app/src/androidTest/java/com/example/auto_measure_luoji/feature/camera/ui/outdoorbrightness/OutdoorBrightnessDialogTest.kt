package com.example.auto_measure_luoji.feature.camera.ui.outdoorbrightness

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.auto_measure_luoji.feature.camera.domain.outdoorbrightness.OutdoorBrightnessController
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OutdoorBrightnessDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsHbmFailureAndOnlyExplicitFallbackChangesBrightness() {
        var brightness = -1f
        val state = OutdoorBrightnessUiState(OutdoorBrightnessController({ brightness }, { brightness = it }))
        state.open()
        compose.setContent { MaterialTheme { OutdoorBrightnessDialog(state) {} } }
        compose.onNodeWithText(OutdoorBrightnessController.HBM_FAILURE).assertIsDisplayed()
        compose.runOnIdle { assertEquals(-1f, brightness, 0f) }
        compose.onNodeWithText("请求窗口最高亮度").performClick()
        compose.runOnIdle {
            assertEquals(1f, brightness, 0f)
            assertFalse(state.visible)
            assertEquals(OutdoorBrightnessController.MAXIMUM_REQUESTED, state.result)
            state.open()
        }
        compose.onNodeWithText("恢复窗口亮度").performClick()
        compose.runOnIdle { assertEquals(-1f, brightness, 0f); assertFalse(state.hasOverride) }
    }

    @Test fun unavailableWindowReportsFailureWithoutSuccessfulState() {
        val state = OutdoorBrightnessUiState(OutdoorBrightnessController({ error("没有窗口") }, {}))
        state.open()
        compose.setContent { MaterialTheme { OutdoorBrightnessDialog(state) {} } }
        compose.onNodeWithText("请求窗口最高亮度").performClick()
        compose.onNodeWithText("窗口高亮请求失败：没有窗口").assertIsDisplayed()
        compose.runOnIdle { assertTrue(state.visible); assertFalse(state.hasOverride) }
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { assertFalse(state.visible) }
    }
}
