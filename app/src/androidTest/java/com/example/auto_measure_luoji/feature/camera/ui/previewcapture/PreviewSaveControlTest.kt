package com.example.auto_measure_luoji.feature.camera.ui.previewcapture

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PreviewSaveControlTest {
    @get:Rule val compose = createComposeRule()

    @Test fun repeatedRequestsAreIgnoredAndFailureAllowsRetry() {
        val pending = CompletableDeferred<SavedPreviewFrame>()
        var calls = 0
        lateinit var control: PreviewSaveControl
        compose.setContent {
            var status by remember { mutableStateOf("") }
            control = rememberPreviewSaveControl(onStatus = { status = it }) {
                calls++
                pending.await()
            }
            Text(status)
        }
        compose.runOnIdle { control.save(); control.save() }
        compose.waitUntil { calls == 1 }
        compose.runOnIdle {
            assertTrue(control.busy)
            pending.completeExceptionally(IllegalStateException("测试相机未就绪"))
        }
        compose.onNodeWithText("保存失败：测试相机未就绪").assertExists()
        compose.runOnIdle { assertFalse(control.busy); control.save() }
        compose.waitUntil { calls == 2 }
        compose.runOnIdle { assertFalse(control.busy) }
    }
}
