package com.example.auto_measure_luoji.feature.camera.domain.previewfps

import org.junit.Assert.*
import org.junit.Test

class PreviewFrameRateTest {
    @Test fun countsThirtyDistinctImages() {
        val counter = PreviewFrameRate()
        counter.reset(0L)
        repeat(30) { counter.onFrame(it + 1L) }
        assertEquals(30.0, counter.sample(1_000_000_000L)!!, 0.001)
    }

    @Test fun ignoresInvalidAndRepeatedImagesAcrossWindows() {
        val counter = PreviewFrameRate()
        counter.reset(0L)
        counter.onFrame(0L)
        counter.onFrame(-1L)
        repeat(20) { counter.onFrame(10L) }
        assertEquals(1.0, counter.sample(1_000_000_000L)!!, 0.001)
        counter.onFrame(10L)
        assertEquals(0.0, counter.sample(2_000_000_000L)!!, 0.001)
    }

    @Test fun usesElapsedTimeAndKeepsPartialWindow() {
        val counter = PreviewFrameRate()
        counter.reset(0L)
        repeat(60) { counter.onFrame(it + 1L) }
        assertNull(counter.sample(500_000_000L))
        assertEquals(30.0, counter.sample(2_000_000_000L)!!, 0.001)
        assertEquals(0.0, counter.sample(3_000_000_000L)!!, 0.001)
    }

    @Test fun resetDiscardsPreviousSessionAndAcceptsRestartedTimestamps() {
        val counter = PreviewFrameRate()
        counter.reset(0L)
        counter.onFrame(100L)
        counter.reset(2_000_000_000L)
        counter.onFrame(1L)
        assertEquals(1.0, counter.sample(3_000_000_000L)!!, 0.001)
    }

    @Test fun uninitializedOrBackwardClockStartsNewWindow() {
        val counter = PreviewFrameRate()
        assertNull(counter.sample(2_000_000_000L))
        counter.onFrame(10L)
        assertNull(counter.sample(1_000_000_000L))
        assertEquals(0.0, counter.sample(2_000_000_000L)!!, 0.001)
    }
}
