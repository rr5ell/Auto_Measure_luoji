package com.example.auto_measure_luoji.feature.camera.ui.glaresuppression

import org.junit.Assert.*
import org.junit.Test

class GlareSuppressionShaderTest {
    @Test fun flatAreasAndBlackWhiteStayUnchanged() {
        for (i in 0..100) {
            val c = i / 100f
            val veil = GlareSuppressionShader.veil(c, c, FloatArray(25) { c })
            assertEquals(0f, veil, 0f)
            assertEquals(c, GlareSuppressionShader.channel(c, veil), 0f)
        }
        val mixed = FloatArray(25) { if (it % 2 == 0) 0.3f else 1f }
        assertEquals(0f, GlareSuppressionShader.veil(0f, 0f, mixed), 0f)
        assertEquals(0f, GlareSuppressionShader.veil(1f, 1f, mixed), 0f)
    }

    @Test fun grayPedestalIsReducedAndExistingContrastIncreases() {
        val neighbors = FloatArray(25) { if (it % 2 == 0) 0.3f else 0.6f }
        val darkVeil = GlareSuppressionShader.veil(0.3f, 0.3f, neighbors)
        val lightVeil = GlareSuppressionShader.veil(0.6f, 0.6f, neighbors)
        assertEquals(0.105f, darkVeil, 0.00001f)
        val dark = GlareSuppressionShader.channel(0.3f, darkVeil)
        val light = GlareSuppressionShader.channel(0.6f, lightVeil)
        assertTrue(dark < 0.3f)
        assertTrue(light - dark > 0.3f)
    }

    @Test fun highlightsAndNearFlatNoiseReceiveLessSuppression() {
        val varied = FloatArray(25) { if (it % 2 == 0) 0.3f else 0.9f }
        val smallRange = FloatArray(25) { if (it % 2 == 0) 0.3f else 0.31f }
        assertEquals(0f, GlareSuppressionShader.veil(0.3f, 0.3f, smallRange), 0f)
        assertTrue(GlareSuppressionShader.veil(0.9f, 0.9f, varied) <
            GlareSuppressionShader.veil(0.6f, 0.6f, varied))
    }

    @Test fun subtractionIsBoundedAndDoesNotCrushColorChannels() {
        val varied = FloatArray(25) { if (it % 2 == 0) 0.6f else 1f }
        for (i in 0..100) {
            val channel = i / 100f
            val veil = GlareSuppressionShader.veil(0.65f, channel, varied)
            assertTrue(veil.isFinite() && veil in 0f..0.12f)
            assertTrue(veil <= channel * 0.5f)
            val output = GlareSuppressionShader.channel(channel, veil)
            assertTrue(output.isFinite() && output in 0f..1f)
            if (channel > 0f) assertTrue(output > 0f)
        }
    }

    @Test fun invalidStatisticsDisableSuppression() {
        assertEquals(0f, GlareSuppressionShader.veil(Float.NaN, 0.2f, FloatArray(25)), 0f)
        assertEquals(0f, GlareSuppressionShader.veil(0.2f, Float.POSITIVE_INFINITY, FloatArray(25)), 0f)
        assertEquals(0f, GlareSuppressionShader.veil(0.2f, 0.2f, FloatArray(25) { Float.NaN }), 0f)
        assertEquals(0.2f, GlareSuppressionShader.channel(0.2f, Float.NaN), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongNeighborhoodSizeIsRejected() {
        GlareSuppressionShader.veil(0.2f, 0.2f, FloatArray(24))
    }
}
