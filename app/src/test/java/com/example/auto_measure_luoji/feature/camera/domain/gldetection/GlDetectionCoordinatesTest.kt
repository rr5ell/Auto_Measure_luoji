package com.example.auto_measure_luoji.feature.camera.domain.gldetection

import org.junit.Assert.*
import org.junit.Test

class GlDetectionCoordinatesTest {
    private fun identity() = FloatArray(16).apply { this[0]=1f; this[5]=1f; this[10]=1f; this[15]=1f }
    private fun sampling() = identity().apply { this[5]=-1f; this[13]=1f }
    private fun mapper(rotation: Int = 0, sample: FloatArray = sampling(), display: FloatArray = identity()) =
        GlDetectionCoordinates(sample, display, 320, 240, GlDetectionTicket(1, 100, 640, 480, rotation))

    private fun assertPoint(x: Float, y: Float, point: GlDetectionPoint) {
        assertEquals(x, point.x, 0.001f)
        assertEquals(y, point.y, 0.001f)
    }

    @Test fun inversePreprocessRotationUsesContinuousPixelEdges() {
        val inputs = listOf(0 to (64f to 96f), 90 to (384f to 64f),
            180 to (576f to 384f), 270 to (96f to 576f))
        for ((rotation, input) in inputs) assertPoint(32f, 48f, mapper(rotation).point(input.first, input.second))
        assertPoint(0f, 0f, mapper().point(0f, 0f))
        assertPoint(320f, 240f, mapper().point(640f, 480f))
    }

    @Test fun samplingCropAndMirrorAreInvertedExactlyOnce() {
        val cropped = sampling().apply { this[0]=0.8f; this[5]=-0.8f; this[12]=0.1f; this[13]=0.9f }
        assertPoint(80f, 120f, mapper(sample=cropped).point(192f, 240f))
        val mirrored = sampling().apply { this[0]=-1f; this[12]=1f }
        assertPoint(288f, 48f, mapper(sample=mirrored).point(64f, 96f))
    }

    @Test fun rollUsesPixelAspectAndSameFrameDisplayTranslation() {
        // 像素空间缩放 2 倍、顺时针 90 度，再平移 (10,-8)。
        // 原点 (180,120) 相对中心 (160,120) 为 (20,0)，输出应为 (170,152)。
        val d = identity().apply {
            this[0]=0f; this[5]=0f
            this[1]=-2f*320/240; this[4]=2f*240/320
            this[12]=2f*10/320; this[13]=2f*8/240
        }
        val map = mapper(display=d)
        assertPoint(170f, 152f, map.point(360f, 240f))
        val quad = map.box(GlDetectionBox(320f, 200f, 400f, 280f, "目标"))
        assertPoint(170f, 152f, quad.point(0.5f, 0.5f))
        assertPoint(210f, 112f, quad.point(0f, 0f))
    }

    @Test fun matricesAreCopiedAndInvalidInputsRejected() {
        val s = sampling()
        val map = mapper(sample=s)
        s[0] = 4f
        assertPoint(32f, 48f, map.point(64f, 96f))
        assertThrows(IllegalArgumentException::class.java) { mapper(sample=FloatArray(16)) }
        assertThrows(IllegalArgumentException::class.java) { mapper(rotation=45) }
        assertThrows(IllegalArgumentException::class.java) { map.point(Float.NaN, 1f) }
        assertThrows(IllegalArgumentException::class.java) { map.box(GlDetectionBox(5f, 0f, 1f, 1f, "")) }
    }
}
