package com.example.auto_measure_luoji.feature.detection.modelselection

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class YoloModelDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadingDisablesModelSelection() {
        compose.setContent { MaterialTheme {
            YoloModelDialog(YoloModelOption.V34, true, false, "V34 加载中...",
                { error("加载中不能切换") }, {}, {})
        } }
        compose.onNodeWithText("V29").assertIsNotEnabled()
        compose.onNodeWithText("重试").assertDoesNotExist()
    }

    @Test fun failedModelCanBeRetriedOrChanged() {
        var selected: YoloModelOption? = null
        var retries = 0
        compose.setContent { MaterialTheme {
            YoloModelDialog(YoloModelOption.V34, false, true, "V34 加载失败: 缺少模型",
                { selected = it }, { retries++ }, {})
        } }
        compose.onNodeWithText("V29").performClick()
        compose.runOnIdle { assertEquals(YoloModelOption.V29, selected) }
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
