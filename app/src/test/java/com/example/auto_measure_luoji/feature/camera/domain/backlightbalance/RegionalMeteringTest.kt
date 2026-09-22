package com.example.auto_measure_luoji.feature.camera.domain.backlightbalance

import com.example.auto_measure_luoji.feature.camera.domain.imageadjustment.ExposureCompensationRange
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

class RegionalMeteringTest {
    private fun zone(mean: Float, high: Float = 0f) = MeteringZone(mean, high, 100)
    private fun uniform(mean: Float) = zone(mean).let { RegionalMetering(it, it, it, it, it) }
    private val range = ExposureCompensationRange(-9, 9, 1f / 3f)
    private fun feed(policy: HighlightExposurePolicy, ms: Long, regions: RegionalMetering, dark: Int = 100): Float? =
        policy.update(HighlightStatistics(ms * 1_000_000L, regions.weightedHighlights, 200, dark, regions), ms * 1_000_000L)

    @Test fun directionsRotateWithPortraitSensorAndDoNotDoubleCountCorners() {
        assertEquals(MeteringRegion.TOP, MeteringRegion.at(0.5f, 0.1f, 0))
        assertEquals(MeteringRegion.RIGHT, MeteringRegion.at(0.5f, 0.1f, 90))
        assertEquals(MeteringRegion.BOTTOM, MeteringRegion.at(0.5f, 0.1f, 180))
        assertEquals(MeteringRegion.LEFT, MeteringRegion.at(0.5f, 0.1f, 270))
        assertEquals(MeteringRegion.TOP, MeteringRegion.at(0.1f, 0.5f, 90))
        for (rotation in listOf(0, 90, 180, 270)) assertEquals(MeteringRegion.CENTER, MeteringRegion.at(0.5f, 0.5f, rotation))
        assertEquals(MeteringRegion.TOP, MeteringRegion.at(0.1f, 0.1f, 0))
        assertEquals(MeteringRegion.RIGHT, MeteringRegion.at(0.1f, 0.1f, 90))
    }

    @Test fun fiveZonesCoverSourceAndSensorRotationPermutesOnlyEdgeLabels() {
        val width = 64
        val height = 48
        val buffer = ByteBuffer.wrap(ByteArray(width * height) { i ->
            val x = i % width
            val y = i / width
            (when { y < 16 -> 30; y >= 32 -> 210; x < 21 -> 70; x >= 43 -> 160; else -> 110 }).toByte()
        })
        val original = HighlightStatistics.sample(buffer, width, height, width, 1, 0, 0)!!.regions!!
        val rotated = HighlightStatistics.sample(buffer, width, height, width, 1, 0, 90)!!.regions!!
        assertEquals(width * height, original.top.count + original.bottom.count + original.left.count + original.right.count + original.center.count)
        assertEquals(original.top, rotated.right)
        assertEquals(original.right, rotated.bottom)
        assertEquals(original.bottom, rotated.left)
        assertEquals(original.left, rotated.top)
        assertEquals(original.center, rotated.center)
        assertEquals(original.weightedLuma, rotated.weightedLuma, 0.001f)
        assertNull(HighlightStatistics.sample(buffer, width, height, width, 1, 0, 45))
    }

    @Test fun isolatedOutliersAreTrimmedButHighPixelFractionIsRetained() {
        val buffer = ByteBuffer.wrap(ByteArray(64 * 48) { 112 })
        buffer.put(0, 0); buffer.put(32, 255.toByte())
        val stats = HighlightStatistics.sample(buffer, 64, 48, 64, 1, 0)!!
        val regions = stats.regions!!
        for (zone in listOf(regions.top, regions.bottom, regions.left, regions.right, regions.center)) {
            assertEquals(112f, zone.mean, 0.001f)
        }
        assertTrue(stats.brightFraction > 0f)
        assertTrue(regions.top.brightFraction > 0f)
    }

    @Test fun weightingUsesCenterAndEveryEdgeInLogSpace() {
        val regions = RegionalMetering(zone(240f), zone(60f), zone(80f), zone(100f), zone(50f))
        val expected = exp(0.4 * ln(50.0) + 0.15 * (ln(240.0) + ln(60.0) + ln(80.0) + ln(100.0)))
        assertEquals(expected.toFloat(), regions.weightedLuma, 0.0001f)
        assertEquals(112f, uniform(112f).weightedLuma, 0.001f)
        val lowerEdge = regions.copy(right = zone(20f))
        assertTrue(lowerEdge.weightedLuma < regions.weightedLuma)
    }

    @Test fun darkSidesDrivePositiveExposureAndSingleBrightSkyDoesNotDominate() {
        val regions = RegionalMetering(zone(250f, 1f), zone(55f), zone(55f), zone(55f), zone(35f))
        assertFalse(regions.broadClipping)
        val policy = HighlightExposurePolicy(range, 0f)
        assertNull(feed(policy, 0, regions, 30)); assertNull(feed(policy, 500, regions, 30))
        assertEquals(1f / 3f, feed(policy, 1000, regions, 30)!!, 0.0001f)
        for (ms in 1500L..20000L step 500) feed(policy, ms, regions, 30)
        assertEquals(1f, policy.correctionEv, 0.0001f)
        assertEquals("已达提亮上限", policy.decisionText)
    }

    @Test fun onlyChangingTwoSidesCanChangeDecisionAtSameCenterBrightness() {
        val policy = HighlightExposurePolicy(range, 0f)
        for (ms in 0L..5000L step 500) assertNull(feed(policy, ms, uniform(112f)))
        val sidesDark = uniform(112f).copy(left = zone(40f), right = zone(40f))
        assertNull(feed(policy, 5500, sidesDark)); assertNull(feed(policy, 6000, sidesDark))
        assertNotNull(feed(policy, 6500, sidesDark))
        assertTrue(policy.correctionEv > 0f)
    }

    @Test fun broadClippingAndDarkSubjectReportsContrastLimitInsteadOfRaisingBlindly() {
        val regions = RegionalMetering(zone(250f, 1f), zone(20f), zone(250f, 1f), zone(250f, 1f), zone(20f))
        val policy = HighlightExposurePolicy(range, 0f)
        for (ms in 0L..10000L step 500) assertNull(feed(policy, ms, regions, 20))
        assertEquals("反差超限", policy.decisionText)
        assertEquals(0f, policy.correctionEv, 0f)
    }

    @Test fun missingInvalidRegionsAndOldSamplesCannotFinishConfirmation() {
        val policy = HighlightExposurePolicy(range, 0f)
        feed(policy, 0, uniform(60f)); feed(policy, 500, uniform(60f))
        assertNull(policy.update(HighlightStatistics(1_000_000_000L, 0f, 80, 50), 1_000_000_000L))
        assertNull(feed(policy, 1500, uniform(60f).copy(left = zone(Float.NaN))))
        assertNull(feed(policy, 2000, uniform(60f).copy(top = zone(60f).copy(count = 0))))
        assertNull(feed(policy, 2500, uniform(60f))); assertNull(feed(policy, 2500, uniform(60f)))
        assertNull(feed(policy, 3000, uniform(60f)))
        assertNotNull(feed(policy, 3500, uniform(60f)))
    }

    @Test fun feedbackConvergesWithoutAdjacentStepOscillationInSimulatedLinearAndGammaScenes() {
        for (gamma in listOf(1f, 2.2f)) for (base in listOf(50f, 90f, 150f, 200f)) {
            val policy = HighlightExposurePolicy(range, 0f)
            var changesAfterSettling = 0
            for (ms in 0L..60000L step 500) {
                val y = (base * 2f.pow(policy.correctionEv / gamma)).coerceIn(1f, 230f)
                val change = feed(policy, ms, uniform(y), y.toInt())
                if (ms > 30000 && change != null) changesAfterSettling++
            }
            assertEquals("场景应收敛或停在边界：gamma=$gamma base=$base", 0, changesAfterSettling)
            assertTrue(policy.correctionEv >= -0.667f && policy.correctionEv <= 1.001f)
        }
    }
}
