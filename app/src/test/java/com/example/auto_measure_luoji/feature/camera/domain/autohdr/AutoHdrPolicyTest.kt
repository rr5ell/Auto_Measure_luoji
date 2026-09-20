package com.example.auto_measure_luoji.feature.camera.domain.autohdr

import com.example.auto_measure_luoji.feature.camera.domain.exposure.ExposureReading
import org.junit.Assert.*
import org.junit.Test

class AutoHdrPolicyTest {
    private val defaults = AutoHdrSettings()
    private fun frame(exposureNs: Long?, nowNs: Long) = ExposureReading(exposureNs, nowNs, "0", false)

    private fun assertBatch(policy: AutoHdrPolicy, hdr: Boolean, exposureNs: Long,
                            startNs: Long, settings: AutoHdrSettings = defaults): Long {
        for (index in 0..3) {
            val now = startNs + index * 250_000_000L
            val decision = policy.decide(true, false, hdr, frame(exposureNs, now), settings, now)
            if (index < 3) assertNull("第 ${index + 1} 条不能切换", decision)
            else assertEquals(!hdr, decision)
        }
        return startNs + 750_000_000L
    }

    private fun firstThree(policy: AutoHdrPolicy) {
        for (index in 0..2) {
            val now = index * 250_000_000L
            assertNull(policy.decide(true, false, false, frame(100_000, now), defaults, now))
        }
    }

    @Test fun thresholdsAreStrictAndEqualityMeansHdrOff() {
        for (exposure in listOf(299_999L, 25_000_001L)) {
            assertBatch(AutoHdrPolicy(), false, exposure, 0L)
            assertNull(AutoHdrPolicy().decide(true, false, true, frame(exposure, 0L), defaults, 0L))
        }
        for (exposure in listOf(300_000L, 1_000_000L, 25_000_000L)) {
            assertBatch(AutoHdrPolicy(), true, exposure, 0L)
            assertNull(AutoHdrPolicy().decide(true, false, false, frame(exposure, 0L), defaults, 0L))
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
        val closedAt = assertBatch(policy, true, 1_000_000, switchedAt + 10_000_000_000L)
        policy.transitionCompleted(closedAt, defaults)
        assertEquals(10, policy.remainingSeconds(closedAt))
        assertNull(policy.decide(true, false, false, frame(100_000, closedAt + 1L), defaults, closedAt + 1L))
        val next = closedAt + 10_000_000_000L
        assertBatch(policy, false, 100_000, next)
        assertEquals(0, policy.remainingSeconds(next))
    }

    @Test fun cooldownUsesFreshExposureNotAQueuedDecision() {
        val policy = AutoHdrPolicy()
        policy.transitionCompleted(0L, defaults)
        assertNull(policy.decide(true, false, false, frame(100_000, 9_000_000_000L), defaults, 9_000_000_000L))
        assertNull(policy.decide(true, false, false, frame(1_000_000, 10_000_000_000L), defaults, 10_000_000_000L))
        assertBatch(policy, false, 30_000_000, 11_000_000_000L)
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
        assertBatch(policy, false, 500_000, 0L, custom)
        policy.transitionCompleted(1_000_000_000L, custom)
        assertNull(policy.decide(true, false, false, frame(100_000, 3_999_999_999L), defaults, 3_999_999_999L))
        assertBatch(policy, false, 100_000, 4_000_000_000L)
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

    @Test fun duplicateFramesCannotFillConfirmationWindow() {
        val policy = AutoHdrPolicy()
        val same = frame(100_000, 0L)
        repeat(8) { assertNull(policy.decide(true, false, false, same, defaults, it * 10_000_000L)) }
        for (index in 1..3) {
            val now = index * 250_000_000L
            val decision = policy.decide(true, false, false, frame(100_000, now), defaults, now)
            if (index < 3) assertNull(decision) else assertEquals(true, decision)
        }
        assertNull(policy.decide(true, false, false, frame(100_000, 750_000_000L), defaults, 800_000_000L))
    }

    @Test fun oneNormalExposureBreaksTheOpeningSequence() {
        val policy = AutoHdrPolicy()
        firstThree(policy)
        assertNull(policy.decide(true, false, false, frame(1_000_000, 750_000_000L), defaults, 750_000_000L))
        assertBatch(policy, false, 100_000, 1_000_000_000L)
    }

    @Test fun oneExtremeExposureBreaksTheClosingSequence() {
        val policy = AutoHdrPolicy()
        for (index in 0..2) {
            val now = index * 250_000_000L
            assertNull(policy.decide(true, false, true, frame(1_000_000, now), defaults, now))
        }
        assertNull(policy.decide(true, false, true, frame(30_000_000, 750_000_000L), defaults, 750_000_000L))
        assertBatch(policy, true, 1_000_000, 1_000_000_000L)
    }

    @Test fun eitherExtremeQualifiesAsOpeningCondition() {
        val policy = AutoHdrPolicy()
        for ((index, exposure) in listOf(100_000L, 30_000_000L, 100_000L, 30_000_000L).withIndex()) {
            val now = index * 250_000_000L
            val decision = policy.decide(true, false, false, frame(exposure, now), defaults, now)
            if (index < 3) assertNull(decision) else assertEquals(true, decision)
        }
    }

    @Test fun interruptionsDiscardPartialConfirmation() {
        for (interruption in 0..4) {
            val policy = AutoHdrPolicy()
            firstThree(policy)
            val now = 750_000_000L
            val reading = when (interruption) {
                2 -> null
                3 -> frame(null, now)
                4 -> frame(-1, now)
                else -> frame(100_000, now)
            }
            assertNull(policy.decide(interruption != 0, interruption == 1, false, reading, defaults, now))
            assertBatch(policy, false, 100_000, 1_000_000_000L)
        }
    }

    @Test fun changedParametersAndHdrStateStartNewConfirmation() {
        val policy = AutoHdrPolicy()
        firstThree(policy)
        assertBatch(policy, false, 100_000, 750_000_000L, AutoHdrSettings(0.5, 30.0, 10))
        assertBatch(policy, true, 1_000_000, 2_000_000_000L)
    }

    @Test fun cameraPhysicalOutputAndSessionChangesCannotMixSamples() {
        for (source in listOf(frame(100_000, 0).copy(cameraId = "7"),
            frame(100_000, 0).copy(physical = true), frame(100_000, 0).copy(sessionGeneration = 1L))) {
            val policy = AutoHdrPolicy()
            firstThree(policy)
            for (index in 0..3) {
                val now = 750_000_000L + index * 250_000_000L
                val decision = policy.decide(true, false, false, source.copy(receivedAtNs = now), defaults, now)
                if (index < 3) assertNull(decision) else assertEquals(true, decision)
            }
        }
    }

    @Test fun asynchronousRecheckCannotAccumulateAndRejectsChangedExposure() {
        val policy = AutoHdrPolicy()
        firstThree(policy)
        assertFalse(policy.canApplyConfirmedDecision(true, false, frame(100_000, 500_000_000L), defaults, 500_000_000L))
        val completedAt = assertBatch(policy, false, 100_000, 750_000_000L)
        repeat(4) {
            assertTrue(policy.canApplyConfirmedDecision(true, false, frame(100_000, completedAt), defaults, completedAt))
        }
        assertFalse(policy.canApplyConfirmedDecision(true, false, frame(1_000_000, 2_000_000_000L), defaults, 2_000_000_000L))
        assertBatch(policy, false, 100_000, 2_250_000_000L)
    }

    @Test fun staleReorderedAndCooldownFramesCannotCarryConfirmation() {
        val policy = AutoHdrPolicy()
        firstThree(policy)
        assertNull(policy.decide(true, false, false, frame(100_000, 250_000_000L), defaults, 750_000_000L))
        assertNull(policy.decide(true, false, false, frame(100_000, 500_000_000L), defaults, 800_000_000L))
        assertBatch(policy, false, 100_000, 1_000_000_000L)
        assertNull(policy.decide(true, false, false, frame(100_000, 1_750_000_000L), defaults, 4_000_000_000L))
        assertBatch(policy, false, 100_000, 4_250_000_000L)
        policy.transitionCompleted(5_000_000_000L, defaults)
        assertNull(policy.decide(true, false, true, frame(1_000_000, 14_900_000_000L), defaults, 15_000_000_000L))
        assertBatch(policy, true, 1_000_000, 15_250_000_000L)
    }

    @Test fun explicitResetInvalidatesAsyncApprovalWithoutChangingCooldown() {
        val policy = AutoHdrPolicy()
        val completedAt = assertBatch(policy, false, 100_000, 0L)
        policy.resetConfirmation()
        assertFalse(policy.canApplyConfirmedDecision(true, false, frame(100_000, completedAt), defaults, completedAt))
        policy.transitionCompleted(1_000_000_000L, defaults)
        policy.resetConfirmation()
        assertEquals(10, policy.remainingSeconds(1_000_000_000L))
    }

    @Test fun cooldownMustBeFollowedByFourNewDisplayedReadings() {
        for (hdr in listOf(false, true)) {
            val policy = AutoHdrPolicy()
            val exposure = if (hdr) 1_000_000L else 100_000L
            val direction = if (hdr) "关闭" else "开启"
            policy.transitionCompleted(0L, defaults)
            for (index in 0..19) {
                val now = index * 500_000_000L
                assertNull(policy.decide(true, false, hdr, frame(exposure, now), defaults, now))
                assertNull(policy.confirmationText)
            }
            // 到期时即使数据仍新鲜，停判期间收到的曝光也不得算作第一条。
            assertNull(policy.decide(true, false, hdr, frame(exposure, 9_999_999_999L), defaults, 10_000_000_000L))
            assertNull(policy.confirmationText)
            for (index in 0..3) {
                val now = 10_500_000_000L + index * 500_000_000L
                val decision = policy.decide(true, false, hdr, frame(exposure, now), defaults, now)
                assertEquals("${direction}确认 ${index + 1}/4", policy.confirmationText)
                if (index < 3) assertNull(decision) else assertEquals(!hdr, decision)
            }
        }
    }

    @Test fun faultPauseDiscardsSamplesAndRecoveryRequiresFourNewReadings() {
        for (hdr in listOf(false, true)) {
            val policy = AutoHdrPolicy()
            val exposure = if (hdr) 1_000_000L else 100_000L
            for (index in 0..2) {
                val now = index * 500_000_000L
                assertNull(policy.decide(true, false, hdr, frame(exposure, now), defaults, now))
            }
            for (index in 3..10) {
                val now = index * 500_000_000L
                assertNull(policy.decide(true, true, hdr, frame(exposure, now), defaults, now))
                assertNull(policy.confirmationText)
            }
            for (index in 0..3) {
                val now = 5_500_000_000L + index * 500_000_000L
                val decision = policy.decide(true, false, hdr, frame(exposure, now), defaults, now)
                if (index < 3) assertNull(decision) else assertEquals(!hdr, decision)
            }
        }
    }

    @Test fun fourDisplayedReadingsShowProgressForBothDirections() {
        for (hdr in listOf(false, true)) {
            val policy = AutoHdrPolicy()
            val exposure = if (hdr) 1_000_000L else 100_000L
            val direction = if (hdr) "关闭" else "开启"
            assertNull(policy.confirmationText)
            for (index in 0..3) {
                val now = index * 500_000_000L
                val reading = frame(exposure, now)
                val decision = policy.decide(true, false, hdr, reading, defaults, now)
                assertEquals("$direction" + "确认 ${index + 1}/4", policy.confirmationText)
                if (index < 3) assertNull(decision) else assertEquals(!hdr, decision)
                // 在下一次主面板更新之前再次读取同一帧，不得增加进度。
                assertNull(policy.decide(true, false, hdr, reading, defaults, now + 250_000_000L))
                assertEquals("$direction" + "确认 ${index + 1}/4", policy.confirmationText)
            }
            assertTrue(policy.canApplyConfirmedDecision(!hdr, hdr, frame(exposure, 1_500_000_000L), defaults, 1_500_000_000L))
        }
    }

    @Test fun neitherOpeningNorClosingCanBeApprovedWithOnlyThreeReadings() {
        for (hdr in listOf(false, true)) {
            val policy = AutoHdrPolicy()
            val exposure = if (hdr) 1_000_000L else 100_000L
            for (index in 0..2) {
                val now = index * 500_000_000L
                assertNull(policy.decide(true, false, hdr, frame(exposure, now), defaults, now))
            }
            assertFalse(policy.canApplyConfirmedDecision(!hdr, hdr, frame(exposure, 1_000_000_000L), defaults, 1_000_000_000L))
            assertNull(policy.confirmationText)
        }
    }

    @Test fun visibleConfirmationClearsWhenConditionBreaksOrAutomaticStops() {
        val policy = AutoHdrPolicy()
        policy.decide(true, false, false, frame(100_000L, 0), defaults, 0)
        assertEquals("开启确认 1/4", policy.confirmationText)
        policy.decide(true, false, false, frame(1_000_000L, 500_000_000L), defaults, 500_000_000L)
        assertNull(policy.confirmationText)
        policy.decide(true, false, false, frame(100_000L, 1_000_000_000L), defaults, 1_000_000_000L)
        assertEquals("开启确认 1/4", policy.confirmationText)
        policy.decide(false, false, false, frame(100_000L, 1_500_000_000L), defaults, 1_500_000_000L)
        assertNull(policy.confirmationText)
    }
}
