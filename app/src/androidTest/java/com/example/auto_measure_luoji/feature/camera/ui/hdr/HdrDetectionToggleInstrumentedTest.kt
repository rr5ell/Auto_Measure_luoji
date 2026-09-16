package com.example.auto_measure_luoji.feature.camera.ui.hdr

import android.Manifest
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.MainActivity
import com.example.auto_measure_luoji.feature.camera.data.LensScanner
import com.example.auto_measure_luoji.feature.camera.domain.SelectLensUseCase
import com.example.auto_measure_luoji.feature.camera.domain.hdr.HdrPreviewCapabilities
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HdrDetectionToggleInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun hdrSwitchPreservesRecognitionIntentAndResumesInference() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        assumeTrue(HlgPreviewGl.checkSupport(display).isSuccess)
        val lens = SelectLensUseCase()(LensScanner(context).scan()) ?: error("没有后置镜头")
        assumeTrue(HdrPreviewCapabilities.checkCamera(context, lens).isSuccess)
        assumeTrue(HdrPreviewCapabilities.checkYuvAnalysis(context, lens).isSuccess)
        for (permission in arrayOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForRecognition("识别")
            compose.onNodeWithText("识别").performClick()
            waitForInference()

            compose.onNodeWithContentDescription("HDR 预览").performClick()
            waitForHdr()
            waitForRecognition("识别中")
            waitForInference()

            compose.onNodeWithText("更多").performClick()
            compose.onNodeWithText("YOLO源 ", substring = true).assertIsNotEnabled()
            compose.onNode(hasText("YOLO单框", substring = true) or hasText("YOLO多框"))
                .assertIsEnabled().performClick()
            compose.onNodeWithText("YOLO识别阈值").assertIsDisplayed()
            compose.onNodeWithText("保存").assertIsEnabled().performClick()
            waitForRecognition("识别中")
            waitForInference()

            compose.onNodeWithContentDescription("HDR 预览").performClick()
            waitForRecognition("识别中")
            compose.onNodeWithContentDescription("HDR 预览").assertIsOff()
            waitForInference()

            compose.onNodeWithText("识别中").performClick()
            compose.onNodeWithContentDescription("HDR 预览").performClick()
            waitForHdr()
            waitForRecognition("识别")
            compose.onNodeWithContentDescription("HDR 预览").performClick()
            waitForRecognition("识别")
            compose.onNodeWithContentDescription("HDR 预览").assertIsOff()
        }
    }

    private fun waitForRecognition(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().size == 1
        }
    }

    private fun waitForHdr() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("HDR：HLG10 | YOLO：SDR YUV")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("HDR 预览").assertIsOn()
    }

    private fun waitForInference() {
        // The switch clears the HUD; require a new completed model result, not only an enabled button.
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("inf=", substring = true) and hasText("obj", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
