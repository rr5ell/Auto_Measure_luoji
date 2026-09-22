package com.example.auto_measure_luoji.feature.camera.domain.tapmetering

import com.example.auto_measure_luoji.feature.camera.domain.centermetering.CenterMeteringRegion
import com.example.auto_measure_luoji.feature.camera.domain.gldetection.*
import org.junit.Assert.*
import org.junit.Test

class TapMeteringTest {
    private fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    @Test fun inverseMatchesDetectionForwardAcrossRotationMirrorCropAndDisplayMotion() {
        val matrices = listOf(identity(), identity().apply { this[5] = -1f; this[13] = 1f },
            identity().apply { this[0] = 0f; this[1] = 0.98f; this[4] = -0.98f; this[5] = 0f; this[12] = 0.99f; this[13] = 0.01f },
            identity().apply { this[0] = -0.8f; this[5] = 0.8f; this[12] = 0.9f; this[13] = 0.1f })
        val display = identity().apply { this[0] = 1.5f; this[1] = 0.2f; this[4] = -0.2f; this[5] = 1.5f; this[12] = 0.04f; this[13] = -0.1f }
        for (sampling in matrices) for (rotation in listOf(0, 90, 180, 270)) {
            val ticket = GlDetectionTicket(1, 1, 640, 480, rotation)
            val qx = 0.45f; val qy = 0.55f
            val (x, y) = when (rotation) {
                90 -> (1f - qy) * 480 to qx * 640
                180 -> (1f - qx) * 640 to (1f - qy) * 480
                270 -> qy * 480 to (1f - qx) * 640
                else -> qx * 640 to qy * 480
            }
            val screen = GlDetectionCoordinates(sampling, display, 1080, 1920, ticket).point(x, y)
            val q = GlDetectionCoordinates.screenToBuffer(sampling, display, 1080, 1920, screen.x, screen.y)!!
            assertEquals(qx, q.x, 1e-5f); assertEquals(qy, q.y, 1e-5f)
        }
    }

    @Test fun blackBordersInvalidMatricesAndOutOfViewTapsAreRejected() {
        val s = identity(); val d = identity().apply { this[12] = 0.5f }
        assertNull(GlDetectionCoordinates.screenToBuffer(s, d, 100, 100, 1f, 50f))
        for (bad in listOf(-1f, 101f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(GlDetectionCoordinates.screenToBuffer(s, s, 100, 100, bad, 50f))
        }
        assertNull(GlDetectionCoordinates.screenToBuffer(s, FloatArray(16), 100, 100, 50f, 50f))
        assertNull(GlDetectionCoordinates.screenToBuffer(FloatArray(16), s, 100, 100, 50f, 50f))
        assertNull(GlDetectionCoordinates.screenToBuffer(s, s, 0, 100, 0f, 0f))
        assertNull(GlDetectionCoordinates.screenToBuffer(s, s.copyOf(3), 100, 100, 50f, 50f))
    }

    @Test fun tappedRegionStaysInsideVisibleCropAndUiZoomDoesNotMoveTarget() {
        val crop = CenterMeteringRegion.Bounds(100, 200, 4100, 3200)
        val normal = CenterMeteringRegion.calculate(crop, 1920, 1080, 1f, 0.7f, 0.6f)!!
        val zoomed = CenterMeteringRegion.calculate(crop, 1920, 1080, 2f, 0.7f, 0.6f)!!
        assertEquals(normal.left + normal.right, zoomed.left + zoomed.right)
        assertTrue(zoomed.right - zoomed.left < normal.right - normal.left)
        val topLeft = CenterMeteringRegion.calculate(crop, 1920, 1080, 1f, 0f, 0f)!!
        val bottomRight = CenterMeteringRegion.calculate(crop, 1920, 1080, 1f, 1f, 1f)!!
        assertEquals(100, topLeft.left); assertEquals(575, topLeft.top)
        assertEquals(4100, bottomRight.right); assertEquals(2825, bottomRight.bottom)
        assertNull(CenterMeteringRegion.calculate(crop, 1920, 1080, 1f, Float.NaN, 0.5f))
        assertNull(CenterMeteringRegion.calculate(crop, 1920, 1080, 1f, 1.01f, 0.5f))
    }

    @Test fun tapPointRequiresFiniteNormalizedCoordinates() {
        assertTrue(TapMeteringPoint(0f, 1f).valid)
        assertFalse(TapMeteringPoint(Float.NaN, 0f).valid)
        assertFalse(TapMeteringPoint(0f, Float.POSITIVE_INFINITY).valid)
        assertFalse(TapMeteringPoint(-0.1f, 0.5f).valid)
    }
}
