package com.example.auto_measure_luoji.feature.camera.domain.lowlight

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.MainActivity
import com.example.auto_measure_luoji.feature.camera.data.LensScanner
import com.example.auto_measure_luoji.feature.camera.domain.SelectLensUseCase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LowLightBoostInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun logActualLogicalAndPhysicalCameraRangesWithoutOpeningCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        assertTrue(manager.cameraIdList.isNotEmpty())
        Log.i("LowLightBoostTest", "设备=${Build.MODEL} SDK=${Build.VERSION.SDK_INT}")
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            verifyRange(chars, id, false)
            if (Build.VERSION.SDK_INT >= 28) {
                for (physical in chars.physicalCameraIds.sorted()) {
                    verifyRange(manager.getCameraCharacteristics(physical), physical, true)
                }
            }
        }
    }

    private fun verifyRange(chars: CameraCharacteristics, id: String, physical: Boolean) {
        val info = LowLightBoostCharacteristics.read(chars, id, physical)
        assertNull(info.logText, info.readError)
        if (Build.VERSION.SDK_INT >= 35) {
            val raw = chars.get(CameraCharacteristics.CONTROL_LOW_LIGHT_BOOST_INFO_LUMINANCE_RANGE)
            assertEquals(raw?.lower, info.lowerLux)
            assertEquals(raw?.upper, info.upperLux)
        } else assertFalse(info.apiAvailable)
    }

    @Test fun panelShowsCurrentLogicalCameraRangeAfterResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_SCAN)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_CONNECT)
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val lens = SelectLensUseCase()(LensScanner(context).scan()) ?: error("没有后置镜头")
        val expected = LowLightBoostCharacteristics.read(manager.getCameraCharacteristics(lens.cameraId), lens.cameraId, false).panelText
        fun verifyPanel() {
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("低光增强亮度范围").assertTextEquals(expected).assertIsDisplayed()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            verifyPanel()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            verifyPanel()
        }
    }
}
