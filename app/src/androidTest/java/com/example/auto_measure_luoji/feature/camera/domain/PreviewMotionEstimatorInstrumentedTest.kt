package com.example.auto_measure_luoji.feature.camera.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Random

@RunWith(AndroidJUnit4::class)
class PreviewMotionEstimatorInstrumentedTest {
    private fun texture(): Mat {
        assertTrue(SubpixelMotionTracker.available)
        val mat = Mat(1280, 720, CvType.CV_8UC1)
        val random = Random(24680)
        mat.put(0, 0, ByteArray(720 * 1280) { random.nextInt(256).toByte() })
        Imgproc.GaussianBlur(mat, mat, Size(0.0, 0.0), 1.5)
        return mat
    }

    private fun frame(source: Mat, dx: Double, dy: Double): PreviewMotionEstimator.LumaFrame {
        val affine = Mat(2, 3, CvType.CV_64F)
        val shifted = Mat()
        val small = Mat()
        try {
            affine.put(0, 0, 1.0, 0.0, dx, 0.0, 1.0, dy)
            Imgproc.warpAffine(source, shifted, affine, source.size(), Imgproc.INTER_LINEAR, Core.BORDER_REFLECT_101)
            Imgproc.resize(shifted, small, Size(240.0, 427.0), 0.0, 0.0, Imgproc.INTER_AREA)
            val bytes = ByteArray(240 * 427)
            small.get(0, 0, bytes)
            return PreviewMotionEstimator.LumaFrame(720, 1280, 240, 427, 240f / 720, 427f / 1280,
                IntArray(bytes.size) { bytes[it].toInt() and 255 }, 0L)
        } finally {
            affine.release()
            shifted.release()
            small.release()
        }
    }

    @Test fun knownSmallTranslationsAreMeasuredWithoutSevenPixelQuantization() {
        val texture = texture()
        try {
            for (shift in listOf(0.5, 1.0, 2.0, 3.0, 3.5, 3.8, 5.0, -2.0)) {
                val estimator = PreviewMotionEstimator()
                estimator.update(frame(texture, 0.0, 0.0), 1_000_000_000L)
                val result = estimator.update(frame(texture, shift, -shift * 0.6), 1_033_333_333L)
                assertTrue("$shift: $result", result.valid)
                assertEquals("X $shift", shift, result.rawDx.toDouble(), 0.45)
                assertEquals("Y $shift", -shift * 0.6, result.rawDy.toDouble(), 0.45)
            }
        } finally { texture.release() }
    }

    @Test fun noTextureAndTimestampFailuresAreNotReportedAsValidStillness() {
        val flat = PreviewMotionEstimator.LumaFrame(720, 1280, 240, 427, 1f / 3, 427f / 1280,
            IntArray(240 * 427) { 100 }, 0)
        val estimator = PreviewMotionEstimator()
        assertFalse(estimator.update(flat, 1_000_000_000L).valid)
        assertFalse(estimator.update(flat, 1_033_333_333L).valid)
        assertEquals("non_monotonic_timestamp", estimator.update(flat, 1_033_333_333L).reason)
        assertEquals("frame_gap", estimator.update(flat, 1_500_000_000L).reason)
    }
}
