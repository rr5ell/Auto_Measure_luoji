package com.example.auto_measure_luoji.feature.camera.ui.backlightshadow

import org.junit.Assert.*
import org.junit.Test

class ShadowEnhancementTest {
    @Test fun grayCurvePreservesEndpointsAndIsMonotonic() {
        for (hlg in listOf(false, true)) {
            var previous = 0f
            for (i in 0..1000) {
                val input = i / 1000f
                val output = input * ShadowEnhancement.gain(input, input, input, hlg)
                assertTrue(output in 0f..1.00001f)
                assertTrue(output >= previous)
                previous = output
                if (i == 0) assertEquals(0f, output, 0f)
            }
            assertEquals(1f, previous, 0.00001f)
        }
    }

    @Test fun liftsShadowsButPreservesHighlights() {
        assertEquals(0.36384f, 0.2f * ShadowEnhancement.gain(0.2f, 0.2f, 0.2f), 0.00001f)
        assertEquals(0.80256f, 0.8f * ShadowEnhancement.gain(0.8f, 0.8f, 0.8f), 0.00001f)
    }

    @Test fun saturatedColorsStayInRangeWithoutChangingChannelRatios() {
        for (hlg in listOf(false, true)) {
            for (r in 0..10) for (g in 0..10) for (b in 0..10) {
                val channels = floatArrayOf(r / 10f, g / 10f, b / 10f)
                val gain = ShadowEnhancement.gain(channels[0], channels[1], channels[2], hlg)
                assertTrue(gain >= 1f)
                assertTrue(channels.all { it * gain in 0f..1.00001f })
            }
        }
        assertEquals(1f, ShadowEnhancement.gain(1f, 0f, 0f), 0f)
    }

    @Test fun invalidValuesDoNotProduceInvalidGain() {
        assertEquals(1f, ShadowEnhancement.gain(Float.NaN, 0f, 0f), 0f)
        assertEquals(1f, ShadowEnhancement.gain(0f, Float.POSITIVE_INFINITY, 0f), 0f)
        assertTrue(ShadowEnhancement.gain(-1f, 2f, 0f).isFinite())
    }
}
