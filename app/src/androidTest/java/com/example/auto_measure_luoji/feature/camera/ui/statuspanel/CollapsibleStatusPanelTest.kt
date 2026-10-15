package com.example.auto_measure_luoji.feature.camera.ui.statuspanel

import android.graphics.Bitmap
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollapsibleStatusPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modelNameRemainsVisibleWhenCollapsedAndUpdates() {
        val model = mutableStateOf("模型: V34")
        compose.setContent { MaterialTheme {
            CollapsibleStatusPanel("30 FPS", "相机就绪", Modifier.width(180.dp), modelNameText = model.value) {
                Text("推理详情")
            }
        } }
        compose.onNodeWithContentDescription("当前识别模型").assertTextEquals("模型: V34").assertIsDisplayed()
        compose.onNodeWithText("推理详情").assertDoesNotExist()
        compose.runOnIdle { model.value = "模型: V29 加载中..." }
        compose.onNodeWithContentDescription("当前识别模型").assertTextEquals("模型: V29 加载中...")
        compose.runOnIdle { model.value = "模型: V29" }
        compose.onNodeWithContentDescription("当前识别模型").assertTextEquals("模型: V29").assertIsDisplayed()
        capture("status-panel-model.png")
    }

    @Test fun startsCompactAndExpandsWithLatestData() {
        val reading = mutableStateOf("合112")
        compose.setContent { MaterialTheme {
            CollapsibleStatusPanel("30 FPS", "识别中", Modifier.width(180.dp)) { Text(reading.value) }
        } }
        compose.onNodeWithText("30 FPS").assertIsDisplayed()
        compose.onNodeWithText("识别中").assertIsDisplayed()
        compose.onNodeWithText("合112").assertDoesNotExist()
        capture("status-panel-collapsed.png")
        compose.runOnIdle { reading.value = "合150" }
        compose.onNodeWithContentDescription("展开状态详情").performClick()
        compose.onNodeWithText("合150").assertIsDisplayed()
        capture("status-panel-expanded.png")
        compose.onNodeWithContentDescription("收起状态详情").performClick()
        compose.onNodeWithText("合150").assertDoesNotExist()
        compose.onNodeWithText("识别中").assertIsDisplayed()
    }

    @Test fun restoresExpandedStateWithoutSavingStaleReadings() {
        val restoration = StateRestorationTester(compose)
        val reading = mutableStateOf("ISO 100")
        restoration.setContent { MaterialTheme {
            CollapsibleStatusPanel("30 FPS", "相机就绪", Modifier.width(180.dp)) { Text(reading.value) }
        } }
        compose.onNodeWithContentDescription("展开状态详情").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { reading.value = "ISO 200" }
        compose.onNodeWithContentDescription("收起状态详情").assertIsDisplayed()
        compose.onNodeWithText("ISO 200").assertIsDisplayed()
        compose.onNodeWithContentDescription("收起状态详情").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("展开状态详情").assertIsDisplayed()
        compose.onNodeWithText("ISO 200").assertDoesNotExist()
    }

    @Test fun narrowLargeFontDetailsScrollAndToggleStaysVisible() {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                CollapsibleStatusPanel("预览 30.0 FPS", "等待连续确认 2/3：分区降曝", Modifier.width(150.dp).testTag("panel")) {
                    repeat(30) { Text("数据$it：曝光、五区亮度及当前状态") }
                }
            }
        } }
        val compact = compose.onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithContentDescription("展开状态详情").performClick()
        val expanded = compose.onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot.height
        assertTrue(expanded > compact)
        compose.onNodeWithText("数据29：曝光、五区亮度及当前状态").performScrollTo().assertIsDisplayed()
        capture("status-panel-narrow.png")
        compose.onNodeWithContentDescription("收起状态详情").assertIsDisplayed().performClick()
        assertTrue(compose.onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot.height < expanded)
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
