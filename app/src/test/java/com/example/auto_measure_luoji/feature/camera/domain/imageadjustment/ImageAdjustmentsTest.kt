package com.example.auto_measure_luoji.feature.camera.domain.imageadjustment

import com.example.auto_measure_luoji.feature.camera.ui.imageadjustment.PreviewContrast
import org.junit.Assert.*
import org.junit.Test

class ImageAdjustmentsTest {
    @Test fun defaultsAreNeutral() {
        assertEquals(ImageAdjustments(0f, 1f), ImageAdjustments().sanitized())
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f), PreviewContrast.colorMatrix(1f), 0f)
    }

    @Test fun invalidStoredValuesFallBackAndContrastIsBounded() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(ImageAdjustments(), ImageAdjustments(invalid, invalid).sanitized())
            assertEquals(1f, PreviewContrast.normalize(invalid), 0f)
        }
        assertEquals(0.5f, ImageAdjustments(0f, -4f).sanitized().contrast, 0f)
        assertEquals(2f, ImageAdjustments(0f, 8f).sanitized().contrast, 0f)
    }

    @Test fun cameraStepsAreQuantizedAndClamped() {
        val range = ExposureCompensationRange(-20, 20, 0.1f)
        assertTrue(range.supported)
        assertEquals(13, range.indexFor(1.26f))
        assertEquals(-13, range.indexFor(-1.26f))
        assertEquals(2f, range.evFor(3f), 0.0001f)
        assertEquals(-2f, range.evFor(-3f), 0.0001f)
        assertEquals(0, range.indexFor(Float.NaN))
        assertEquals(20, range.indexFor(Float.MAX_VALUE))
    }

    @Test fun fractionalStepAndDifferentLensAreRespected() {
        val range = ExposureCompensationRange(-6, 6, 1f / 3f)
        assertEquals(2, range.indexFor(0.7f))
        assertEquals(2f / 3f, range.evFor(0.7f), 0.0001f)
        assertEquals(1f, ExposureCompensationRange(-2, 2, 0.5f).evFor(1.7f), 0f)
    }

    @Test fun unsupportedOrInvalidCharacteristicsDisableExposure() {
        for (range in listOf(ExposureCompensationRange(), ExposureCompensationRange(0, 0, 1f),
            ExposureCompensationRange(-2, 2, 0f), ExposureCompensationRange(-2, 2, Float.NaN),
            ExposureCompensationRange(2, -2, 1f), ExposureCompensationRange(1, 2, 1f))) {
            assertFalse(range.supported)
            assertEquals(0f, range.evFor(1f), 0f)
        }
    }

    @Test fun calibrationTemporarilyOverridesAndRestoresManualSettings() {
        val manual = ImageAdjustments(1.3f, 1.4f)
        assertEquals(ImageAdjustments(-2f, 1f), manual.forCalibration(true))
        assertEquals(manual, manual.forCalibration(false))
    }

    @Test fun contrastKeepsMidpointAndAlphaAndMatchesNormalizedShader() {
        for (k in listOf(0.5f, 1f, 1.5f, 2f)) {
            val matrix = PreviewContrast.colorMatrix(k)
            assertEquals(127.5f, matrix[0] * 127.5f + matrix[4], 0.0001f)
            assertEquals(1f, matrix[18], 0f)
            assertEquals(0f, matrix[19], 0f)
            for (pixel in listOf(0f, 64f, 128f, 255f)) {
                val expected = ((pixel / 255f - 0.5f) * k + 0.5f).coerceIn(0f, 1f)
                val actual = (matrix[0] * pixel + matrix[4]).coerceIn(0f, 255f) / 255f
                assertEquals(expected, actual, 0.00001f)
            }
        }
    }
}
