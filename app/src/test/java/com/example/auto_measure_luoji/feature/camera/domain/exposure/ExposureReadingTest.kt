package com.example.auto_measure_luoji.feature.camera.domain.exposure

import org.junit.Assert.*
import org.junit.Test

class ExposureReadingTest {
    @Test fun actualIsoAndApertureHandleMissingInvalidAndStaleValues() {
        val sample = reading(1_000_000).copy(iso = 320, aperture = 1.8f)
        assertEquals("ISO：320\n光圈：f/1.8", sample.cameraParametersText(1000))
        assertEquals("ISO：未提供\n光圈：未提供", reading(null).cameraParametersText(1000))
        assertEquals("ISO：读数异常\n光圈：读数异常", sample.copy(iso = 0, aperture = Float.NaN).cameraParametersText(1000))
        assertEquals("ISO：等待新帧\n光圈：等待新帧", sample.cameraParametersText(999))
        assertEquals("ISO：等待新帧\n光圈：等待新帧", sample.cameraParametersText(2_000_001_001L))
        assertTrue(sample.logText.contains("SENSOR_SENSITIVITY=320 LENS_APERTURE=1.8"))
    }
    private fun reading(ns: Long?) = ExposureReading(ns, 1_000L, "0", false)

    @Test fun convertsNanosecondsWithoutIntegerTruncation() {
        assertEquals("曝光时间：0.125 ms", reading(125_000).panelText(1_000))
        assertEquals("曝光时间：33.333 ms", reading(33_333_333).panelText(1_000))
        assertEquals("曝光时间：1000.000 ms", reading(1_000_000_000).panelText(1_000))
        assertTrue(reading(125_000).logText.contains("SENSOR_EXPOSURE_TIME=125000 ns"))
    }

    @Test fun missingAndInvalidValuesDoNotBecomeZero() {
        assertEquals("曝光时间：未提供", reading(null).panelText(1_000))
        for (ns in listOf(0L, -1L)) assertEquals("曝光时间：读数异常", reading(ns).panelText(1_000))
    }

    @Test fun staleAndFutureSamplesAreNotShownAsLive() {
        assertEquals("曝光时间：1.000 ms", reading(1_000_000).panelText(2_000_001_000L))
        assertEquals("曝光时间：等待新帧", reading(1_000_000).panelText(2_000_001_001L))
        assertEquals("曝光时间：等待新帧", reading(1_000_000).panelText(999L))
        assertTrue(reading(1L).copy(cameraId = "7", physical = true).logText.contains("camera=7 output=physical"))
    }
}
