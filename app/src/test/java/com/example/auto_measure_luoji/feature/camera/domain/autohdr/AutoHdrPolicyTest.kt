package com.example.auto_measure_luoji.feature.camera.domain.autohdr

import com.example.auto_measure_luoji.feature.camera.domain.exposure.ExposureReading
import org.junit.Assert.*
import org.junit.Test

class AutoHdrPolicyTest {
    private val defaults = AutoHdrSettings()
    private fun frame(exposureNs: Long?, nowNs: Long) = ExposureReading(exposureNs, nowNs, "0", false)

    @Test fun thresholdsAreStrictAndEqualityMeansHdrOff() {
        val policy = AutoHdrPolicy()
        for (exposure in listOf(299_999L, 25_000_001L)) {
            assertEquals(true, policy.decide(true, false, false, frame(exposure, 0L), defaults, 0L))
            assertNull(policy.decide(true, false, true, frame(exposure, 0L), defaults, 0L))
        }
        for (exposure in listOf(300_000L, 1_000_000L, 25_000_000L)) {
            assertEquals(false, policy.decide(true, false, true, frame(exposure, 0L), defaults, 0L))
            assertNull(policy.decide(true, false, false, frame(exposure, 0L), defaults, 0L))
        }
    }

    @Test fun successfulOnAndOffEachBlockDecisionsForTenSeconds() {
        val policy = AutoHdrPolicy()
        val switchedAt = 1_000_000_000L
        policy.transitionCompleted(switchedAt, defaults)
        assertEquals(10, policy.remainingSeconds(switchedAt))
        for (now in listOf(switchedAt, switchedAt + 500_000_000L, switchedAt + 9_999_999_999L)) {
            assertNull(policy.decide(true, false, true, frame(1_000_000, now), defaults, now))
        }
        val closedAt = switchedAt + 10_000_000_000L
        assertEquals(false, policy.decide(true, false, true, frame(1_000_000, closedAt), defaults, closedAt))
        policy.transitionCompleted(closedAt, defaults)
        assertEquals(10, policy.remainingSeconds(closedAt))
        assertNull(policy.decide(true, false, false, frame(100_000, closedAt + 1L), defaults, closedAt + 1L))
        val next = closedAt + 10_000_000_000L
        assertEquals(true, policy.decide(true, false, false, frame(100_000, next), defaults, next))
        assertEquals(0, policy.remainingSeconds(next))
    }

    @Test fun cooldownUsesFreshExposureNotAQueuedDecision() {
        val policy = AutoHdrPolicy()
        policy.transitionCompleted(0L, defaults)
        assertNull(policy.decide(true, false, false, frame(100_000, 9_000_000_000L), defaults, 9_000_000_000L))
        assertNull(policy.decide(true, false, false, frame(1_000_000, 10_000_000_000L), defaults, 10_000_000_000L))
        assertEquals(true, policy.decide(true, false, false, frame(30_000_000, 11_000_000_000L), defaults, 11_000_000_000L))
    }

    @Test fun disabledBlockedOrMissingMetadataNeverSwitches() {
        val policy = AutoHdrPolicy()
        assertNull(policy.decide(false, false, false, frame(100_000, 0L), defaults, 0L))
        assertNull(policy.decide(true, true, false, frame(100_000, 0L), defaults, 0L))
        assertNull(policy.decide(true, false, true, null, defaults, 0L))
        for (value in listOf(null, 0L, -1L)) {
            assertNull(policy.decide(true, false, true, frame(value, 0L), defaults, 0L))
        }
    }

    @Test fun staleOrFutureFramesNeverSwitch() {
        val policy = AutoHdrPolicy()
        assertNull(policy.decide(true, false, false, frame(100_000, 0L), defaults, 2_000_000_001L))
        assertNull(policy.decide(true, false, false, frame(100_000, 1L), defaults, 0L))
    }

    @Test fun customSettingsAndActiveCooldownRemainIndependent() {
        val policy = AutoHdrPolicy()
        val custom = AutoHdrSettings(1.0, 10.0, 3)
        assertEquals(true, policy.decide(true, false, false, frame(500_000, 0L), custom, 0L))
        policy.transitionCompleted(0L, custom)
        assertNull(policy.decide(true, false, false, frame(100_000, 2_999_999_999L), defaults, 2_999_999_999L))
        assertEquals(true, policy.decide(true, false, false, frame(100_000, 3_000_000_000L), defaults, 3_000_000_000L))
    }

    @Test fun parserRejectsInvalidThresholdsAndCooldown() {
        assertEquals(defaults, AutoHdrSettings.parse(" 0.3 ", "25", "10"))
        for (lower in listOf("", "NaN", "Infinity", "-1", "0", "25", "26")) {
            assertNull(AutoHdrSettings.parse(lower, "25", "10"))
        }
        for (upper in listOf("", "NaN", "Infinity", "0.1")) assertNull(AutoHdrSettings.parse("0.3", upper, "10"))
        for (cooldown in listOf("", "0", "-1", "1.5", "3601")) assertNull(AutoHdrSettings.parse("0.3", "25", cooldown))
        assertNull(AutoHdrPolicy().decide(true, false, false, frame(100_000, 0), AutoHdrSettings(Double.NaN), 0))
    }
}
