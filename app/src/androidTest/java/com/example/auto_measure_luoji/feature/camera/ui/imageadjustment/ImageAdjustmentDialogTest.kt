package com.example.auto_measure_luoji.feature.camera.ui.imageadjustment

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.auto_measure_luoji.feature.camera.domain.imageadjustment.ExposureCompensationRange
import com.example.auto_measure_luoji.feature.camera.domain.imageadjustment.ImageAdjustments
import com.example.auto_measure_luoji.feature.camera.domain.manualsensor.ManualExposure
import com.example.auto_measure_luoji.feature.camera.domain.manualsensor.ManualSensorCapabilities
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImageAdjustmentDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun manualShutterDisablesEvKeepsContrastAndResetsToAutomatic() {
        var manual by mutableStateOf<ManualExposure?>(ManualExposure(10_000_000, 120))
        val caps = ManualSensorCapabilities(100_000, 1_000_000_000, 50, 3200, 1_000_000_000, listOf(1.4f), true)
        compose.setContent {
            MaterialTheme {
                ImageAdjustmentDialog(ImageAdjustments(), ExposureCompensationRange(-20, 20, 0.1f), true, null,
                    {}, {}, {}, manual = manual, sensorCapabilities = caps,
                    actualSensorText = "曝光时间：10.000 ms\nISO：120\n光圈：f/1.4",
                    onManualEnabledChange = { if (!it) manual = null }, onManualChange = { manual = it })
            }
        }
        compose.onNodeWithContentDescription("手动 EV").assertIsNotEnabled()
        compose.onNodeWithContentDescription("预览对比度").assertIsEnabled()
        compose.onNodeWithContentDescription("手动快门").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
        compose.runOnIdle { assertEquals(100_000_000L, manual!!.timeNs); assertEquals(120, manual!!.iso) }
        compose.onNodeWithText("光圈：固定，不可调").assertExists()
        compose.onNodeWithContentDescription("手动光圈").assertDoesNotExist()
        compose.onNodeWithText("恢复默认").performClick()
        compose.runOnIdle { assertNull(manual) }
        compose.onNodeWithContentDescription("手动 EV").assertIsEnabled()
    }

    @Test fun apertureUsesOnlyAdvertisedStops() {
        var manual by mutableStateOf<ManualExposure?>(ManualExposure(10_000_000, 100, 1.4f))
        val caps = ManualSensorCapabilities(100_000, 1_000_000_000, 50, 3200, 1_000_000_000, listOf(1.4f, 2f, 2.8f), true)
        compose.setContent {
            MaterialTheme {
                ImageAdjustmentDialog(ImageAdjustments(), ExposureCompensationRange(), true, null, {}, {}, {},
                    manual = manual, sensorCapabilities = caps, onManualChange = { manual = it })
            }
        }
        compose.onNodeWithContentDescription("手动光圈").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.runOnIdle { assertEquals(2.8f, manual!!.aperture!!, 0f); assertEquals(100, manual!!.iso) }
    }

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
