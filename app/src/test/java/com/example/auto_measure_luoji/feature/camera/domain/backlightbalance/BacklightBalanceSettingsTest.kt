package com.example.auto_measure_luoji.feature.camera.domain.backlightbalance

import com.example.auto_measure_luoji.feature.camera.domain.backlightbalance.BacklightExposureRange as ExposureCompensationRange
import org.junit.Assert.*
import org.junit.Test

class BacklightBalanceSettingsTest {
    private val defaults = BacklightBalanceSettings()
    private val range = ExposureCompensationRange(-12, 12, 1f / 3f)
    private fun sample(ms: Long, mean: Float = 60f, p20: Int = 120, p99: Int = 80, high: Float = 0f): HighlightStatistics {
        val zone = MeteringZone(mean, high, 100)
        return HighlightStatistics(ms * 1_000_000L, high, p99, p20, RegionalMetering(zone, zone, zone, zone, zone))
    }
    private fun feed(policy: HighlightExposurePolicy, sample: HighlightStatistics) = policy.update(sample, sample.sampledAtNs)

    @Test fun defaultsRoundTripWithoutChangingFractionalSteps() {
        assertEquals(defaults, BacklightBalanceSettings.parse(defaults.fields()))
        assertNull(defaults.validationError)
        assertEquals(1_500_000_000L, defaults.settleNs)
    }

    @Test fun fieldsRejectNonFiniteOutOfRangeAndFractionalIntegerValues() {
        for (parameter in BacklightParameter.entries) {
            for (invalid in listOf("", "NaN", "Infinity", "-Infinity", "text", (parameter.maximum + 1).toString(),
                (parameter.minimum - 1).toString())) {
                assertNull("$parameter=$invalid", BacklightBalanceSettings.parse(defaults.fields() + (parameter to invalid)))
            }
            if (parameter.integer) assertNull(BacklightBalanceSettings.parse(defaults.fields() +
                (parameter to (parameter.minimum + 0.5f).toString())))
        }
        assertNull(BacklightBalanceSettings.parse(emptyMap()))
    }

    @Test fun centerThresholdsRequireHysteresisAndOrderedDeepRecovery() {
        assertNotNull(defaults.copy(deepShadow = 65).validationError)
        assertNotNull(defaults.copy(protectBelow = 96).validationError)
        assertNotNull(defaults.copy(releaseAt = 63).validationError)
        assertNull(defaults.copy(deepShadow = 64).validationError)
        assertNull(BacklightBalanceSettings.parse(defaults.copy(deepShadow = 100).fields()))
        assertThrows(IllegalArgumentException::class.java) {
            HighlightExposurePolicy(range, 0f, defaults.copy(deadband = Float.NaN))
        }
    }

    @Test fun customTargetAndDeadbandChangeDecision() {
        val target = HighlightExposurePolicy(range, 0f, defaults.copy(targetLuma = 60f, confirmations = 1))
        assertNull(feed(target, sample(0)))
        assertEquals("保持曝光", target.decisionText)
        val normal = HighlightExposurePolicy(range, 0f, defaults.copy(confirmations = 1))
        assertNotNull(feed(normal, sample(0)))
        val wider = HighlightExposurePolicy(range, 0f, defaults.copy(deadband = 1.5f, confirmations = 1))
        assertNull(feed(wider, sample(0)))
    }

    @Test fun customConfirmationsAndSettlePeriodRequireFreshPostWaitSamples() {
        val policy = HighlightExposurePolicy(range, 0f, defaults.copy(confirmations = 4, settleSeconds = 2f))
        for (ms in listOf(0L, 500L, 1000L)) assertNull(feed(policy, sample(ms)))
        assertNull(feed(policy, sample(1000)))
        assertNotNull(feed(policy, sample(1500)))
        for (ms in 2000L..4500L step 500) assertNull(feed(policy, sample(ms)))
        assertNotNull(feed(policy, sample(5000)))
        // 等待期可改长，但旧数据的有效期仍为1.5秒。
        assertNull(policy.update(sample(6000), 8_000_000_000L))
    }

    @Test fun customBoundsAndHardwareQuantizationRemainBounded() {
        val settings = defaults.copy(stepEv = 0.4f, maxIncreaseEv = 0.8f, maxDecreaseEv = 0.4f, confirmations = 1)
        val policy = HighlightExposurePolicy(ExposureCompensationRange(-20, 20, 0.1f), 0.5f, settings)
        for (ms in 0L..10000L step 1500) feed(policy, sample(ms))
        assertEquals(0.8f, policy.correctionEv, 0.0001f)
        for (ms in 12000L..30000L step 1500) feed(policy, sample(ms, mean = 240f))
        assertEquals(-0.4f, policy.correctionEv, 0.0001f)
        val zero = HighlightExposurePolicy(range, 0f, defaults.copy(maxDecreaseEv = 0f, maxIncreaseEv = 0f, confirmations = 1))
        assertNull(feed(zero, sample(0)))
        assertNull(feed(zero, sample(2000, mean = 240f)))
    }

    @Test fun highFractionAndRegionCountUseCustomThresholds() {
        val low = MeteringZone(112f, 0f, 100)
        val high = MeteringZone(112f, 0.3f, 100)
        val zones = RegionalMetering(high, high, low, low, low)
        assertFalse(zones.broadClipping)
        assertTrue(zones.hasBroadClipping(defaults.copy(highlightZoneCount = 2)))
        assertFalse(zones.hasBroadClipping(defaults.copy(highlightZoneCount = 2, zoneHighlightPercent = 31f)))
        assertTrue(zones.hasBroadClipping(defaults.copy(weightedHighlightPercent = 8f)))
        val policy = HighlightExposurePolicy(range, 0f, defaults.copy(highlightZoneCount = 2, confirmations = 1))
        assertNotNull(feed(policy, sample(0, mean = 112f).copy(regions = zones)))
        assertEquals(-1f / 3f, policy.correctionEv, 0.0001f)
    }

    @Test fun customSubjectProtectionAndRecoveryMarginReachPolicy() {
        val settings = defaults.copy(deepShadow = 70, protectBelow = 80, releaseAt = 130, confirmations = 1)
        val policy = HighlightExposurePolicy(range, 0f, settings)
        assertNull(feed(policy, sample(0, mean = 240f, p20 = 75)))
        assertTrue(policy.subjectProtected)
        assertNull(feed(policy, sample(500, mean = 240f, p20 = 120)))
        assertNotNull(feed(policy, sample(1000, mean = 240f, p20 = 130)))
        assertNotNull(feed(policy, sample(2500, mean = 112f, p20 = 65, p99 = 255, high = 0.8f)))
        assertEquals(0f, policy.correctionEv, 0.0001f)
        fun recovery(margin: Int): Float? {
            val p = HighlightExposurePolicy(range, 0f, defaults.copy(confirmations = 1, recoveryMargin = margin))
            feed(p, sample(0, mean = 240f))
            return feed(p, sample(1500, p99 = 185))
        }
        assertNull(recovery(8))
        assertEquals(0f, recovery(0)!!, 0.0001f)
    }
}
