package com.example.auto_measure_luoji.feature.camera.domain.lowlight

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LowLightBoostInstrumentedTest {
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

}
