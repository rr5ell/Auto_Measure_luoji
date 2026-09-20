package com.example.auto_measure_luoji.feature.camera.domain.ambientlight

import org.junit.Assert.*
import org.junit.Test

class AmbientLightReadingTest {
    @Test fun displaysLuxIncludingRealZero() {
        assertEquals("环境照度：123.5 lx", AmbientLightReading.fromLux(123.456f).panelText)
        assertEquals("环境照度：0.0 lx", AmbientLightReading.fromLux(0f).panelText)
        assertEquals(123.456f, AmbientLightReading.fromLux(123.456f).lux)
        assertEquals("环境照度：100000.0 lx", AmbientLightReading.fromLux(100000f).panelText)
    }

    @Test fun rejectsMissingAndInvalidValues() {
        for (lux in listOf(null, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val reading = AmbientLightReading.fromLux(lux)
            assertEquals(AmbientLightReading.Status.INVALID, reading.status)
            assertNull(reading.lux)
            assertEquals("环境照度：读数异常", reading.panelText)
        }
    }

    @Test fun nonReadingStatesAreDistinctAndNeverInventZero() {
        val states = AmbientLightReading.Status.values().filter { it != AmbientLightReading.Status.AVAILABLE }
        val messages = states.map { AmbientLightReading(it).panelText }
        assertEquals(states.size, messages.toSet().size)
        assertTrue(messages.none { it.contains("0.0") || it.contains("lx") })
        assertEquals("环境照度：读数异常", AmbientLightReading(AmbientLightReading.Status.AVAILABLE).panelText)
    }
}
