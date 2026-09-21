package com.example.auto_measure_luoji.feature.camera.ui.localtone

import org.junit.Assert.*
import org.junit.Test

class LocalToneShaderTest {
    @Test fun modesToggleExclusivelyAndCalibrationDoesNotOverwriteChoice() {
        assertEquals(PreviewToneMode.OFF, PreviewToneMode.fromPreference("unknown"))
        assertEquals(PreviewToneMode.OFF, PreviewToneMode.fromPreference(null))
        for (mode in PreviewToneMode.entries) {
            assertEquals(mode, PreviewToneMode.fromPreference(mode.name))
            assertEquals(PreviewToneMode.OFF, mode.forCalibration(true))
            assertEquals(mode, mode.forCalibration(false))
            assertEquals(PreviewToneMode.OFF, mode.toggled(mode))
        }
        assertEquals(PreviewToneMode.RETINEX, PreviewToneMode.LOCAL_TONE.toggled(PreviewToneMode.RETINEX))
    }

    @Test fun uniformShadowsBrightenWithoutChangingBlackOrWhite() {
        for (mode in listOf(PreviewToneMode.LOCAL_TONE, PreviewToneMode.RETINEX)) {
            assertTrue(LocalToneShader.gain(mode, 0.2f, FloatArray(25) { 0.2f }) > 1.5f)
            assertEquals(1f, LocalToneShader.gain(mode, 1f, FloatArray(25) { 1f }), 0.00001f)
            assertEquals(0f, 0f * LocalToneShader.gain(mode, 0f, FloatArray(25)), 0f)
        }
    }

    @Test fun neighborsAffectGainAndBilateralProtectsStrongEdges() {
        val dark = FloatArray(25) { 0.2f }
        val edge = FloatArray(25) { if (it % 5 > 2) 0.8f else 0.2f }
        val localDark = LocalToneShader.gain(PreviewToneMode.LOCAL_TONE, 0.2f, dark)
        val localEdge = LocalToneShader.gain(PreviewToneMode.LOCAL_TONE, 0.2f, edge)
        val retinexDark = LocalToneShader.gain(PreviewToneMode.RETINEX, 0.2f, dark)
        val retinexEdge = LocalToneShader.gain(PreviewToneMode.RETINEX, 0.2f, edge)
        assertTrue(retinexDark - retinexEdge > 0.2f)
        assertEquals(localDark, localEdge, 0.01f)
        val midNeighbors = FloatArray(25) { 0.3f }.apply { this[12] = 0.2f }
        assertTrue(LocalToneShader.gain(PreviewToneMode.LOCAL_TONE, 0.2f, midNeighbors) < localDark)
    }

    @Test fun gainIsFiniteBoundedAndRespectsColorHeadroom() {
        for (mode in PreviewToneMode.entries) for (i in 0..100) {
            val v = i / 100f
            val gain = LocalToneShader.gain(mode, v, FloatArray(25) { v })
            val minimum = if (mode == PreviewToneMode.BACKLIGHT_BALANCE) 0.85f else 1f
            assertTrue(gain.isFinite() && gain in minimum..4f)
            assertTrue(v * gain <= 1.00001f)
        }
        assertEquals(1f, LocalToneShader.gain(PreviewToneMode.RETINEX, 0.1f, FloatArray(25) { 0.1f }, 1f), 0f)
        assertEquals(1f, LocalToneShader.gain(PreviewToneMode.RETINEX, Float.NaN, FloatArray(25)), 0f)
        assertEquals(1f, LocalToneShader.gain(PreviewToneMode.RETINEX, 0.2f, FloatArray(25) { Float.NaN }), 0f)
        assertEquals(4f, LocalToneShader.radius(0, 0), 0f)
        assertEquals(32f, LocalToneShader.radius(4000, 8000), 0f)
    }

    @Test fun balanceLiftsShadowsCompressesHighlightsAndProtectsNearBlack() {
        fun gain(y: Float, peak: Float = y, neighbors: FloatArray = FloatArray(25) { y }) =
            LocalToneShader.gain(PreviewToneMode.BACKLIGHT_BALANCE, y, neighbors, peak)
        assertEquals(1f, gain(0.005f), 0.0001f)
        assertTrue("深暗部不能被原噪声门槛完全压住", gain(0.02f) > 2f)
        assertTrue(gain(0.2f) > 1.4f)
        assertTrue(gain(0.8f) < 1f)
        assertEquals(0.85f, gain(1f), 0.0001f)
        assertTrue(gain(0.2f, 0.99f) < 1f)
        assertEquals(gain(0.2f), gain(0.2f, neighbors = FloatArray(25) { if (it % 5 > 2) 0.8f else 0.2f }), 0.01f)
        var previous = 0f
        for (i in 0..1000) {
            val y = i / 1000f
            val output = y * gain(y)
            assertTrue("灰阶不能出现亮度反转", output >= previous - 0.00001f)
            previous = output
        }
    }
}
