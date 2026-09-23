package com.example.auto_measure_luoji.feature.camera.ui.backlightbalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextureMeteringSourceSizeTest {
    @Test fun scalesPortraitLandscapeAndSquareTo640() {
        assertEquals(360 to 640, TextureMeteringSource.captureSize(1080, 1920))
        assertEquals(640 to 360, TextureMeteringSource.captureSize(1920, 1080))
        assertEquals(640 to 640, TextureMeteringSource.captureSize(1280, 1280))
        assertEquals(480 to 640, TextureMeteringSource.captureSize(3000, 4000))
    }

    @Test fun smallImagesAreNotUpscaledAndThinImagesStayNonempty() {
        assertEquals(240 to 426, TextureMeteringSource.captureSize(240, 426))
        assertEquals(360 to 640, TextureMeteringSource.captureSize(360, 640))
        assertEquals(1 to 640, TextureMeteringSource.captureSize(1, 10000))
        assertEquals(640 to 1, TextureMeteringSource.captureSize(10000, 1))
    }

    @Test fun rejectsUnavailableDimensions() {
        assertThrows(IllegalArgumentException::class.java) { TextureMeteringSource.captureSize(0, 1920) }
        assertThrows(IllegalArgumentException::class.java) { TextureMeteringSource.captureSize(1080, -1) }
    }
}
