package com.example.auto_measure_luoji.feature.camera.domain.backlightbalance

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ArgbMeteringTest {
    private fun gray(value: Int) = 0xff000000.toInt() or (value shl 16) or (value shl 8) or value

    @Test fun blackWhiteAndGrayUseLimitedRangeWithoutChangingInputs() {
        for ((rgb, y) in listOf(0 to 16, 51 to 60, 112 to 112, 255 to 235)) {
            val pixels = IntArray(64 * 48) { gray(rgb) }
            val original = pixels.copyOf()
            val result = HighlightStatistics.sampleArgb(pixels, 64, 48, 123L)!!
            assertArrayEquals(original, pixels)
            assertEquals(123L, result.sampledAtNs)
            assertEquals(y, result.percentile99)
            assertEquals(y, result.centerDarkLuma)
            assertEquals(y.toFloat(), result.regions!!.weightedLuma, 0.001f)
            assertEquals(if (rgb == 255) 1f else 0f, result.brightFraction, 0f)
        }
    }

    @Test fun primaryColorsAndMixedFrameMatchExplicitYInputAndRotation() {
        val colors = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt())
        val expected = intArrayOf(81, 145, 41)
        val pixels = IntArray(64 * 48) { colors[(it / 64) % 3] }
        val y = ByteArray(pixels.size) { expected[(it / 64) % 3].toByte() }
        for (rotation in listOf(0, 90, 180, 270)) {
            assertEquals(HighlightStatistics.sample(ByteBuffer.wrap(y), 64, 48, 64, 1, 456, rotation),
                HighlightStatistics.sampleArgb(pixels, 64, 48, 456, rotation))
        }
    }

    @Test fun malformedAndTransparentCapturesCannotDriveExposure() {
        assertNull(HighlightStatistics.sampleArgb(IntArray(0), 0, 0, 0))
        assertNull(HighlightStatistics.sampleArgb(IntArray(4), Int.MAX_VALUE, Int.MAX_VALUE, 0))
        assertNull(HighlightStatistics.sampleArgb(IntArray(4), 2, 3, 0))
        assertNull(HighlightStatistics.sampleArgb(IntArray(64 * 48), 64, 48, 0))
        val pixels = IntArray(64 * 48) { gray(60) }
        pixels[0] = 0x7fffffff
        assertNull(HighlightStatistics.sampleArgb(pixels, 64, 48, 0))
        assertNull(HighlightStatistics.sampleArgb(IntArray(64 * 48) { gray(60) }, 64, 48, 0, 45))
    }

    @Test fun grayRampIsMonotonicAndAllFiveRegionsArePreserved() {
        var previous = 0
        for (level in 0..255) {
            val result = HighlightStatistics.sampleArgb(IntArray(20 * 20) { gray(level) }, 20, 20, 0)!!
            assertTrue(result.regions!!.valid)
            assertTrue(result.percentile99 >= previous)
            assertTrue(result.percentile99 in 16..235)
            previous = result.percentile99
        }
    }
}
