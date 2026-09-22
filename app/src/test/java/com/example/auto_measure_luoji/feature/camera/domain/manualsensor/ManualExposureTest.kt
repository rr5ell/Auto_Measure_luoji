package com.example.auto_measure_luoji.feature.camera.domain.manualsensor

import org.junit.Assert.*
import org.junit.Test

class ManualExposureTest {
    private val caps = ManualSensorCapabilities(100_000, 1_000_000_000, 50, 3200, 1_000_000_000, listOf(1.4f), true)

    @Test fun logarithmicSliderCoversHardwareRangeAndRoundTrips() {
        assertEquals(caps.minTimeNs, caps.timeAt(-1f))
        assertEquals(caps.maxTimeNs, caps.timeAt(2f))
        assertEquals(10_000_000, caps.timeAt(0.5f))
        for (step in 0..100) {
            val progress = step / 100f
            assertEquals(progress, caps.progress(caps.timeAt(progress)), 0.00001f)
        }
        assertEquals(0f, caps.progress(0), 0f)
        assertEquals(1f, caps.progress(Long.MAX_VALUE), 0f)
        assertThrows(IllegalArgumentException::class.java) { caps.timeAt(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { caps.timeAt(Float.POSITIVE_INFINITY) }
    }

    @Test fun manualRequestRejectsOutOfRangeAndFixedApertureWrites() {
        assertFalse(caps.variableAperture)
        assertEquals(ManualExposure(10_000_000, 100), caps.validate(ManualExposure(10_000_000, 100)))
        for (invalid in listOf(ManualExposure(0, 100), ManualExposure(Long.MAX_VALUE, 100),
            ManualExposure(10_000_000, 0), ManualExposure(10_000_000, 3201), ManualExposure(10_000_000, 100, 1.4f))) {
            assertThrows(IllegalArgumentException::class.java) { caps.validate(invalid) }
        }
        val variable = caps.copy(apertures = listOf(1.4f, 2f))
        assertTrue(variable.variableAperture)
        variable.validate(ManualExposure(10_000_000, 100, 2f))
        assertThrows(IllegalArgumentException::class.java) { variable.validate(ManualExposure(10_000_000, 100, 2.8f)) }
        assertFalse(variable.copy(apertureWritable = false).variableAperture)
    }

    @Test fun longShutterExtendsFrameButStaysWithinHardwareLimit() {
        assertEquals(33_333_333, caps.frameDuration(ManualExposure(1_000_000, 100)))
        assertEquals(200_000_000, caps.frameDuration(ManualExposure(200_000_000, 100)))
        assertEquals(caps.maxFrameNs, caps.frameDuration(ManualExposure(caps.maxTimeNs, 100)))
        val fast = caps.copy(maxTimeNs = 10_000_000, maxFrameNs = 20_000_000)
        assertEquals(fast.maxFrameNs, fast.frameDuration(ManualExposure(1_000_000, 100)))
    }

    @Test fun missingInconsistentAndSingleValueRangesAreHandled() {
        for (invalid in listOf(ManualSensorCapabilities(), caps.copy(minTimeNs = 0), caps.copy(maxTimeNs = 1),
            caps.copy(minIso = 0), caps.copy(maxIso = 1), caps.copy(maxFrameNs = 1))) {
            assertFalse(invalid.supported)
            assertThrows(IllegalArgumentException::class.java) { invalid.validate(ManualExposure(100_000, 100)) }
        }
        val fixed = caps.copy(maxTimeNs = caps.minTimeNs)
        assertTrue(fixed.supported)
        assertEquals(caps.minTimeNs, fixed.timeAt(0.7f))
        assertEquals(0f, fixed.progress(caps.minTimeNs), 0f)
    }
}
