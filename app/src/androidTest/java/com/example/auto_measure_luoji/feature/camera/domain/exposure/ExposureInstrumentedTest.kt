package com.example.auto_measure_luoji.feature.camera.domain.exposure

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.MainActivity
import com.example.auto_measure_luoji.feature.camera.ui.statuspanel.expandStatusPanel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExposureInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun actualExposureIsVisibleAfterResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_SCAN)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_CONNECT)
        }
        fun verifyPanel() {
            compose.expandStatusPanel()
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasContentDescription("曝光时间") and hasText(" ms", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("曝光时间").assertIsDisplayed()
            compose.onNodeWithContentDescription("环境照度").assertIsDisplayed()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            verifyPanel()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            verifyPanel()
        }
    }
}
