package com.example.auto_measure_luoji.feature.camera.domain

import android.hardware.camera2.CaptureRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraShadingInstrumentedTest {
    @Test
    fun persistedShadingCanBeRestoredBeforeCameraCharacteristicsArrive() {
        val controller = Camera2Controller(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(controller.setShadingMode(CaptureRequest.SHADING_MODE_FAST).isSuccess)
        assertEquals(CaptureRequest.SHADING_MODE_FAST, controller.getShadingMode())
        assertTrue(controller.setShadingMode(999).isFailure)
        assertEquals(CaptureRequest.SHADING_MODE_FAST, controller.getShadingMode())
        assertTrue(controller.setShadingMode(Camera2Controller.SHADING_MODE_AUTO).isSuccess)
        assertEquals(Camera2Controller.SHADING_MODE_AUTO, controller.getShadingMode())
        assertTrue(controller.setVideoStabilizationMode(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON).isFailure)
        assertEquals(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF, controller.getVideoStabilizationMode())
    }
}
