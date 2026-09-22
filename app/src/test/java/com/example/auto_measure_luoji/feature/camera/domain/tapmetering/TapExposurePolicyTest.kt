package com.example.auto_measure_luoji.feature.camera.domain.tapmetering

import com.example.auto_measure_luoji.feature.camera.domain.backlightbalance.HighlightStatistics
import com.example.auto_measure_luoji.feature.camera.domain.backlightbalance.MeteringZone
import com.example.auto_measure_luoji.feature.camera.domain.centermetering.CenterMeteringRegion
import com.example.auto_measure_luoji.feature.camera.domain.imageadjustment.ExposureCompensationRange
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class TapExposurePolicyTest {
    private val point = TapMeteringPoint(0.7f, 0.3f)
    private val range = ExposureCompensationRange(-9, 9, 1f / 3f)
    private fun sample(ms: Long, local: Float = 30f, global: Float = 120f, bright: Float = 0f,
                       dark: Float = 0f) = HighlightStatistics(ms * 1_000_000, bright, 220, 80,
        tapPoint = point, tapZone = MeteringZone(local, 0f, 100),
        globalZone = MeteringZone(global, bright, 3000), darkFraction = dark)
    private fun feed(policy: TapExposurePolicy, ms: Long, local: Float = 30f, global: Float = 120f,
                     bright: Float = 0f, dark: Float = 0f) =
        policy.update(sample(ms, local, global, bright, dark), ms * 1_000_000)

    @Test fun waitsForTwoIndependentReadingsAfterSettling() {
        val policy = TapExposurePolicy(range, 0f, point, 0)
        assertNull(feed(policy, 0)); assertNull(feed(policy, 500))
        assertNull(feed(policy, 500)); assertNull(feed(policy, 600))
        assertEquals(1f / 3, feed(policy, 1000)!!, 0.0001f)
        assertNull(feed(policy, 1000)); assertNull(feed(policy, 1100)); assertNull(feed(policy, 1500))
        assertEquals(2f / 3, feed(policy, 2000)!!, 0.0001f)
    }

    @Test fun neverAccumulatesPastBothLimitsRelativeToManualBaseline() {
        for (baseline in listOf(-2f, 0f, 1f, 3f)) {
            val policy = TapExposurePolicy(range, baseline, point, 0)
            for (ms in 0L..30000L step 500) {
                feed(policy, ms)
                assertTrue(policy.correctionEv <= 1.0001f)
                assertTrue(baseline + policy.correctionEv <= 3.0001f)
            }
            for (ms in 30500L..60000L step 500) {
                feed(policy, ms, 240f, 150f)
                assertTrue(policy.correctionEv >= -1.0001f)
                assertTrue(baseline + policy.correctionEv >= -3.0001f)
            }
        }
    }

    @Test fun brightWindowCannotMakeAnAlreadyDarkRoomDarker() {
        val policy = TapExposurePolicy(range, 0f, point, 0)
        for (ms in 0L..20000L step 500) assertNull(feed(policy, ms, 250f, 70f))
        assertEquals(0f, policy.correctionEv, 0f)
        assertEquals("全画面暗部保护", policy.decisionText)
    }

    @Test fun broadDarkAreaIsProtectedEvenWhenMeanIsHigher() {
        val policy = TapExposurePolicy(range, 0f, point, 0)
        for (ms in 0L..6000L step 500) assertNull(feed(policy, ms, 250f, 120f, dark = 0.5f))
        assertEquals("全画面暗部保护", policy.decisionText)
    }

    @Test fun ordinaryBrightBackgroundDoesNotVetoDarkTarget() {
        val policy = TapExposurePolicy(range, 0f, point, 0)
        for (ms in 0L..6000L step 500) feed(policy, ms, local = 70f, global = 200f, bright = 0.3f)
        assertEquals(1f, policy.correctionEv, 0.0001f)
        assertEquals("已达微调上限", policy.decisionText)
    }

    @Test fun severeHighlightsLimitButDoNotVetoBrightening() {
        for ((global, bright) in listOf(150f to 0.5f, 210f to 0.1f)) {
            val policy = TapExposurePolicy(range, 0f, point, 0)
            for (ms in 0L..6000L step 500) feed(policy, ms, global = global, bright = bright)
            assertEquals(2f / 3, policy.correctionEv, 0.0001f)
            assertEquals("高光限幅", policy.decisionText)
        }
    }

    @Test fun protectionUnwindsExistingCorrectionInsteadOfHoldingIt() {
        val positive = TapExposurePolicy(range, 0f, point, 0)
        for (ms in 0L..4000L step 500) feed(positive, ms)
        assertTrue(positive.correctionEv > 0f)
        for (ms in 4500L..12000L step 500) feed(positive, ms, bright = 0.6f)
        assertEquals(2f / 3, positive.correctionEv, 0.0001f)
        val negative = TapExposurePolicy(range, 0f, point, 0)
        for (ms in 0L..4000L step 500) feed(negative, ms, 250f, 150f)
        assertTrue(negative.correctionEv < 0f)
        for (ms in 4500L..12000L step 500) feed(negative, ms, 250f, 60f)
        assertEquals(0f, negative.correctionEv, 0.0001f)
    }

    @Test fun invalidOldWrongTargetAndFutureSamplesCannotCompleteConfirmation() {
        val invalid = listOf(sample(2000).copy(tapPoint = TapMeteringPoint(0.2f, 0.3f)),
            sample(2000).copy(tapZone = MeteringZone(Float.NaN, 0f, 100)),
            sample(2000).copy(tapZone = MeteringZone(30f, 0f, 1)),
            sample(2000).copy(globalZone = null), sample(2000).copy(darkFraction = Float.NaN),
            sample(4001), sample(0), null)
        for (bad in invalid) {
            val policy = TapExposurePolicy(range, 0f, point, 0)
            feed(policy, 1500)
            assertNull(policy.update(bad, 2000_000_000))
            assertNull(feed(policy, 2500))
            assertNotNull(feed(policy, 3000))
        }
    }

    @Test fun changingDirectionOrPausingClearsConfirmations() {
        val policy = TapExposurePolicy(range, 0f, point, 0)
        feed(policy, 1500)
        assertNull(feed(policy, 2000, 240f, 150f))
        assertNull(feed(policy, 2500))
        policy.pause(2700_000_000)
        assertNull(feed(policy, 3000)); assertNull(feed(policy, 3500))
        assertNotNull(feed(policy, 4000))
    }

    @Test fun neutralSceneAndCoarseOrUnsupportedHardwareDoNotChangeEv() {
        for (hardware in listOf(range, ExposureCompensationRange(), ExposureCompensationRange(-4, 4, 0.5f))) {
            val policy = TapExposurePolicy(hardware, 0f, point, 0)
            for (ms in 0L..6000L step 500) assertNull(feed(policy, ms, 112f, 112f))
            assertEquals(0f, policy.correctionEv, 0f)
        }
        val coarse = TapExposurePolicy(ExposureCompensationRange(-4, 4, 0.5f), 0f, point, 0)
        for (ms in 0L..6000L step 500) assertNull(feed(coarse, ms))
    }

    @Test fun fineStepsAreQuantizedWithinThirdStop() {
        val policy = TapExposurePolicy(ExposureCompensationRange(-20, 20, 0.2f), 0f, point, 0)
        var previous = 0f
        for (ms in 0L..20000L step 500) feed(policy, ms)?.let {
            assertTrue(it - previous <= 1f / 3 + 0.0001f)
            previous = it
        }
        assertEquals(1f, policy.correctionEv, 0.0001f)
    }

    @Test fun tighteningHighlightLimitNeverExceedsOneStep() {
        val policy = TapExposurePolicy(ExposureCompensationRange(-20, 20, 0.1f), 0f, point, 0)
        for (ms in 0L..6000L step 500) feed(policy, ms)
        assertEquals(1f, policy.correctionEv, 0.0001f)
        var previous = policy.correctionEv
        for (ms in 6500L..12000L step 500) feed(policy, ms, bright = 0.6f)?.let {
            assertTrue(previous - it <= 1f / 3 + 0.0001f)
            assertTrue(it <= previous)
            previous = it
        }
        assertEquals(0.6f, policy.correctionEv, 0.0001f)
    }

    @Test fun gapResetsConfirmationAndProtectionHasHysteresis() {
        val policy = TapExposurePolicy(range, 0f, point, 0)
        feed(policy, 1500)
        assertNull(feed(policy, 4000))
        assertNotNull(feed(policy, 4500))
        val protected = TapExposurePolicy(range, 0f, point, 0)
        feed(protected, 1000, bright = 0.5f)
        for (ms in 1500L..5000L step 500) feed(protected, ms, bright = 0.4f)
        assertEquals(2f / 3, protected.correctionEv, 0.0001f)
        assertEquals("高光限幅", protected.decisionText)
        assertNull(feed(protected, 5500, bright = 0.3f))
        assertNotNull(feed(protected, 6000, bright = 0.3f))
        assertEquals(1f, protected.correctionEv, 0.0001f)
    }

    @Test fun sparseSamplingUsesUnrotatedTapCoordinatesAndPreservesStridesAndPosition() {
        val width = 64
        val height = 48
        val origin = 7
        val stride = 140
        val buffer = ByteBuffer.allocate(origin + stride * height)
        for (y in 0 until height) for (x in 0 until width)
            buffer.put(origin + y * stride + x * 2, (if (x < width / 2) 40 else 200).toByte())
        buffer.position(origin)
        val left = TapMeteringPoint(0.2f, 0.4f)
        for (rotation in listOf(0, 90, 180, 270)) {
            val stats = HighlightStatistics.sample(buffer, width, height, stride, 2, 0,
                rotation, left)!!
            assertEquals(40f, stats.tapZone!!.mean, 0f)
            assertEquals(120f, stats.globalZone!!.mean, 0.001f)
            assertEquals(0.5f, stats.darkFraction, 0f)
            assertEquals(left, stats.tapPoint)
            assertEquals(origin, buffer.position())
        }
        assertNull(HighlightStatistics.sample(buffer, width, height, 4, 2, 0))
    }

    @Test fun fullFrameRequestIsCenteredAndTapSamplingClipsAtEdges() {
        val bounds = CenterMeteringRegion.Bounds(0, 0, 4000, 3000)
        assertEquals(CenterMeteringRegion.Bounds(0, 375, 4000, 2625),
            CenterMeteringRegion.calculate(bounds, 1920, 1080, fraction = 1.0))
        assertNull(CenterMeteringRegion.calculate(bounds, 1920, 1080, fraction = Double.NaN))
        val buffer = ByteBuffer.wrap(ByteArray(64 * 48) { 112 })
        for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) {
            val stats = HighlightStatistics.sample(buffer, 64, 48, 64, 1, 0,
                tapPoint = TapMeteringPoint(x, y), tapDisplayScale = 2f)!!
            assertTrue(stats.tapZone!!.valid)
            assertEquals(112f, stats.tapZone.mean, 0f)
        }
    }
}
