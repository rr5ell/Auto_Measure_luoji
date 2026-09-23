package com.example.auto_measure_luoji.feature.camera.ui.statuspanel

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick

internal fun ComposeTestRule.expandStatusPanel() {
    waitUntil(30_000) {
        onAllNodesWithContentDescription("展开状态详情").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithContentDescription("收起状态详情").fetchSemanticsNodes().isNotEmpty()
    }
    if (onAllNodesWithContentDescription("展开状态详情").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithContentDescription("展开状态详情").performClick()
    }
}
