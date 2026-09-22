package com.example.auto_measure_luoji.feature.camera.domain.backlightbalance

import com.example.auto_measure_luoji.feature.camera.domain.imageadjustment.ExposureCompensationRange
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class HighlightExposurePolicyTest {
    private val range = ExposureCompensationRange(-9, 9, 1f / 3f)
    private fun regions(mean: Float, bright: Float = 0f) = MeteringZone(mean, bright, 100).let {
        RegionalMetering(it, it, it, it, it)
    }
    private fun sample(ms: Long, bright: Float = 0.1f) = HighlightStatistics(ms * 1_000_000L, bright,
        if (bright == 0f) 80 else 250, 120,
        regions(when (bright) { 0f -> 60f; 0.01f -> 112f; else -> 200f }, bright))
    private fun update(p: HighlightExposurePolicy, ms: Long, bright: Float = 0.1f) = p.update(sample(ms, bright), ms * 1_000_000L)

    @Test fun requiresThreeDistinctSamplesAndWaitsForAeToSettle() {
        val p = HighlightExposurePolicy(range, 0f)
        assertNull(update(p, 0))
        assertNull(update(p, 0))
        assertNull(update(p, 500))
        assertEquals(-1f / 3f, update(p, 1000)!!, 0.0001f)
        assertNull(update(p, 1500))
        assertNull(update(p, 2000))
        assertNull(update(p, 2500))
        assertNull(update(p, 3000))
        assertEquals(-2f / 3f, update(p, 3500)!!, 0.0001f)
    }

    @Test fun deadbandInterruptsConfirmationAndSmallHighlightsAreIgnored() {
        val p = HighlightExposurePolicy(range, 0f)
        update(p, 0); update(p, 500)
        assertNull(update(p, 1000, 0.01f))
        assertNull(update(p, 1500)); assertNull(update(p, 2000))
        assertNotNull(update(p, 2500))
        for (ms in 4000L..10000L step 500) assertNull(update(p, ms, 0.01f))
        assertEquals(-1f / 3f, p.correctionEv, 0.0001f)
    }

    @Test fun boundedTwoSidedCorrectionUsesManualBaseline() {
        val p = HighlightExposurePolicy(range, 1f)
        for (ms in 0L..40000L step 500) update(p, ms)
        assertEquals(-2f / 3f, p.correctionEv, 0.0001f)
        assertEquals(1f / 3f, p.ev, 0.0001f)
        for (ms in 41000L..90000L step 500) update(p, ms, 0f)
        assertEquals(1f, p.correctionEv, 0.0001f)
        assertEquals(2f, p.ev, 0.0001f)
    }

    @Test fun invalidStaleFutureAndPausedSamplesDoNotTrigger() {
        val p = HighlightExposurePolicy(range, 0f)
        update(p, 0); update(p, 500)
        assertNull(p.update(sample(1000), 3_000_000_000L))
        assertNull(p.update(sample(5000), 4_000_000_000L))
        assertNull(update(p, 6000, Float.NaN))
        assertNull(update(p, 6500)); assertNull(update(p, 7000))
        p.pause(7_000_000_000L)
        assertNull(update(p, 7500)); assertNull(update(p, 8000))
        assertNull(update(p, 8500)); assertNull(update(p, 9000))
        assertNotNull(update(p, 9500))
    }

    @Test fun hardwareRangeAndCoarseStepsAreRespected() {
        val p = HighlightExposurePolicy(ExposureCompensationRange(-1, 1, 1f), 0f)
        for (ms in 0L..40000L step 500) update(p, ms)
        assertEquals("一档已经大于 2/3 EV 时不允许强行降曝", 0f, p.ev, 0f)
        val unsupported = HighlightExposurePolicy(ExposureCompensationRange(), 0f)
        for (ms in 0L..10000L step 500) assertNull(update(unsupported, ms))
    }

    @Test fun highlightsJustBelowThresholdDoNotCauseExposureOscillation() {
        val p = HighlightExposurePolicy(range, 0f)
        update(p, 0); update(p, 500); update(p, 1000)
        for (ms in 2500L..20000L step 500) {
            assertNull(p.update(HighlightStatistics(ms * 1_000_000L, 0f, 228, 120, regions(60f)), ms * 1_000_000L))
        }
        assertEquals(-1f / 3f, p.ev, 0.0001f)
        assertNull(update(p, 20500, 0f)); assertNull(update(p, 21000, 0f))
        assertEquals(0f, update(p, 21500, 0f)!!, 0.0001f)
    }

    @Test fun yPlaneSamplingHonorsStridePositionAndDoesNotModifyBuffer() {
        val buffer = ByteBuffer.allocate(30)
        buffer.position(2)
        for (y in 0..2) for (x in 0..3) buffer.put(2 + y * 9 + x * 2, (if (x == 0) 240 else 50).toByte())
        val snapshot = HighlightStatistics.sample(buffer.asReadOnlyBuffer(), 4, 3, 9, 2, 123)!!
        assertEquals(0.25f, snapshot.brightFraction, 0f)
        assertEquals(240, snapshot.percentile99)
        assertEquals(50, snapshot.centerDarkLuma)
        assertEquals(123, snapshot.sampledAtNs)
        assertEquals(2, buffer.position())
        assertNull(HighlightStatistics.sample(buffer, 10, 3, 9, 2, 0))
        assertNull(HighlightStatistics.sample(buffer, 4, 100, 9, 2, 0))
        assertNull(HighlightStatistics.sample(buffer, 0, 3, 9, 2, 0))
    }

    @Test fun darkCenterPreventsSkyFromDrivingExposureDown() {
        val p = HighlightExposurePolicy(range, 0f)
        for (ms in 0L..10000L step 500) {
            assertNull(p.update(HighlightStatistics(ms * 1_000_000L, 0.7f, 255, 35, regions(112f, 0.7f)), ms * 1_000_000L))
        }
        assertTrue(p.subjectProtected)
        assertEquals(0f, p.correctionEv, 0f)
    }

    @Test fun newlyDarkCenterRestoresBaselineDespiteBrightSkyAndHysteresisHolds() {
        val p = HighlightExposurePolicy(range, 0f)
        update(p, 0); update(p, 500); update(p, 1000)
        fun center(ms: Long, y: Int) = p.update(HighlightStatistics(ms * 1_000_000L, 0.6f, 255, y,
            regions(if (y >= 96) 200f else 112f, 0.6f)), ms * 1_000_000L)
        assertNull(center(2500, 30)); assertNull(center(3000, 30))
        assertEquals(0f, center(3500, 30)!!, 0f)
        for (ms in 5000L..10000L step 500) assertNull(center(ms, 80))
        assertTrue(p.subjectProtected)
        assertNull(center(10500, 96)); assertNull(center(11000, 96))
        assertEquals(-1f / 3f, center(11500, 96)!!, 0.0001f)
    }

    @Test fun centerPercentileIsNotDilutedByBrightBackgroundAndTinyFramesAreValid() {
        val width = 100
        val buffer = ByteBuffer.wrap(ByteArray(width * width) { 250.toByte() })
        for (y in 35 until 65) for (x in 35 until 65) buffer.put(y * width + x, 30.toByte())
        val stats = HighlightStatistics.sample(buffer, width, width, width, 1, 0)!!
        assertEquals(30, stats.centerDarkLuma)
        assertEquals(250, stats.percentile99)
        assertTrue(stats.brightFraction > 0.8f)
        for (size in 1..3) {
            val tiny = ByteBuffer.wrap(ByteArray(size * size) { 25 })
            assertEquals(25, HighlightStatistics.sample(tiny, size, size, size, 1, 0)!!.centerDarkLuma)
        }
    }

    @Test fun unsignedLumaThresholdAndLargeSparseFrameAreHandled() {
        val buffer = ByteBuffer.wrap(ByteArray(1920 * 1080) { 235.toByte() })
        assertEquals(1f, HighlightStatistics.sample(buffer, 1920, 1080, 1920, 1, 0)!!.brightFraction, 0f)
        assertEquals(0f, HighlightStatistics.sample(ByteBuffer.wrap(byteArrayOf(234.toByte())), 1, 1, 1, 1, 0)!!.brightFraction, 0f)
    }
}
