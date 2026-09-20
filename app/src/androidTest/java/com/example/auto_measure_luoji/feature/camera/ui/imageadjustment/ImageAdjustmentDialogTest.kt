package com.example.auto_measure_luoji.feature.camera.ui.imageadjustment

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.auto_measure_luoji.feature.camera.domain.imageadjustment.ExposureCompensationRange
import com.example.auto_measure_luoji.feature.camera.domain.imageadjustment.ImageAdjustments
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImageAdjustmentDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun controlsUpdateAndResetWithoutClosing() {
        var settings by mutableStateOf(ImageAdjustments())
        var saved = false
        var closed = false
        compose.setContent {
            MaterialTheme {
                ImageAdjustmentDialog(settings, ExposureCompensationRange(-20, 20, 0.1f), true, null,
                    { settings = it }, { saved = true }, { closed = true })
            }
        }
        compose.onNodeWithContentDescription("手动 EV").performSemanticsAction(SemanticsActions.SetProgress) { it(10f) }
        compose.onNodeWithContentDescription("预览对比度").performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        compose.runOnIdle { assertEquals(ImageAdjustments(1f, 1.5f), settings) }
        compose.onNodeWithText("恢复默认").performClick()
        compose.runOnIdle { assertEquals(ImageAdjustments(), settings); assertTrue(saved); assertFalse(closed) }
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun calibrationOrBusyStateLocksBothControls() {
        compose.setContent {
            MaterialTheme {
                ImageAdjustmentDialog(ImageAdjustments(), ExposureCompensationRange(-20, 20, 0.1f), false,
                    "校准中：EV -2.00，对比度 1.00", {}, {}, {})
            }
        }
        compose.onNodeWithContentDescription("手动 EV").assertIsNotEnabled()
        compose.onNodeWithContentDescription("预览对比度").assertIsNotEnabled()
        compose.onNodeWithText("恢复默认").assertIsNotEnabled()
        compose.onNodeWithText("完成").assertIsEnabled()
    }

    @Test fun unsupportedExposureDoesNotDisableContrast() {
        compose.setContent {
            MaterialTheme { ImageAdjustmentDialog(ImageAdjustments(), ExposureCompensationRange(), true, null, {}, {}, {}) }
        }
        compose.onNodeWithContentDescription("手动 EV").assertIsNotEnabled()
        compose.onNodeWithContentDescription("预览对比度").assertIsEnabled()
        compose.onNodeWithText("当前镜头曝光补偿不可用").assertExists()
    }
}
