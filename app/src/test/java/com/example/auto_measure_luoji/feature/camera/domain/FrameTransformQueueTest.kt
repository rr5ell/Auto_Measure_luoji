package com.example.auto_measure_luoji.feature.camera.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameTransformQueueTest {
    @Test fun futureAndPreviousTransformsNeverMatchCurrentTexture() {
        val queue = FrameTransformQueue<String>()
        queue.offer(100, "previous")
        queue.offer(300, "future")
        assertNull(queue.exact(200))
        queue.offer(200, "current")
        assertEquals("current", queue.exact(200))
        assertEquals("current", queue.exact(200))
        assertEquals("future", queue.exact(300))
    }

    @Test fun outOfOrderAndOverflowStayBoundedWithoutNearestFallback() {
        val queue = FrameTransformQueue<Int>(2)
        queue.offer(300, 3)
        queue.offer(100, 1)
        queue.offer(200, 2)
        assertNull(queue.exact(100))
        assertEquals(2, queue.exact(200))
        queue.clear()
        assertNull(queue.exact(300))
        queue.offer(0, 0)
        assertNull(queue.exact(0))
    }
}
