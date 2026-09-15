package com.example.auto_measure_luoji.feature.camera.domain.gldetection

import org.junit.Assert.*
import org.junit.Test

class GlAsyncDetectionStateTest {
    private fun result(timestamp: Long = 1_000_000_000L, elapsed: Long = 10_000_000_000L,
        boxes: List<GlDetectionBox> = listOf(GlDetectionBox(0f, 0f, 1f, 1f, "目标"))) =
        GlAsyncDetectionState.Result(GlDetectionTicket(timestamp, timestamp, 640, 480, 90), elapsed, boxes)

    @Test fun previewCanAdvanceWithoutNewResultButBoxExpiresAt200ms() {
        val state = GlAsyncDetectionState()
        val r = result()
        assertTrue(state.offer(r, r.ticket.timestampNs, r.capturedElapsedNs))
        assertNotNull(state.current(r.ticket.timestampNs + 199_000_000L, r.capturedElapsedNs + 199_000_000L))
        assertNull(state.current(r.ticket.timestampNs + 200_000_000L, r.capturedElapsedNs + 199_000_000L))
    }

    @Test fun stoppedCameraStillExpiresByMonotonicClock() {
        val state = GlAsyncDetectionState()
        val r = result()
        assertTrue(state.offer(r, r.ticket.timestampNs, r.capturedElapsedNs))
        assertNull(state.current(r.ticket.timestampNs, r.capturedElapsedNs + 200_000_000L))
    }

    @Test fun lateFutureAndOutOfOrderResultsAreRejected() {
        val state = GlAsyncDetectionState()
        val r = result()
        assertFalse(state.offer(r, r.ticket.timestampNs - 1L, r.capturedElapsedNs))
        state.clear()
        assertFalse(state.offer(r, r.ticket.timestampNs, r.capturedElapsedNs + 250_000_000L))
        state.clear()
        assertTrue(state.offer(r, r.ticket.timestampNs, r.capturedElapsedNs))
        assertFalse(state.offer(result(timestamp=r.ticket.timestampNs-1), r.ticket.timestampNs, r.capturedElapsedNs))
        assertEquals(r, state.current(r.ticket.timestampNs, r.capturedElapsedNs))
    }

    @Test fun emptyResultReplacesOldBoxAndResetInvalidatesEverything() {
        val state = GlAsyncDetectionState()
        val r = result()
        assertTrue(state.offer(r, r.ticket.timestampNs, r.capturedElapsedNs))
        val empty = result(timestamp=r.ticket.timestampNs+1, boxes=emptyList())
        assertTrue(state.offer(empty, empty.ticket.timestampNs, empty.capturedElapsedNs))
        assertTrue(state.current(empty.ticket.timestampNs, empty.capturedElapsedNs)!!.boxes.isEmpty())
        state.clear()
        assertNull(state.current(empty.ticket.timestampNs, empty.capturedElapsedNs))
    }
}
