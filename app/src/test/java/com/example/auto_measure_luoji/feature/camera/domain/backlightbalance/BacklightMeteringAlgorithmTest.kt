package com.example.auto_measure_luoji.feature.camera.domain.backlightbalance

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class BacklightMeteringAlgorithmTest {
    private val range = BacklightExposureRange(-9, 9, 1f / 3f)
    private fun frame(ms: Long, y: Int = 60) = BacklightMeteringAlgorithm.FrameInput(
        ByteBuffer.wrap(ByteArray(64 * 48) { y.toByte() }), 64, 48, 64, 1, ms * 1_000_000L)

    @Test fun decisionsDistinguishConfirmedDirectionHoldAndRestore() {
        for ((y, decision) in listOf(60 to BacklightMeteringAlgorithm.ExposureDecision.INCREASE_EV,
            200 to BacklightMeteringAlgorithm.ExposureDecision.DECREASE_EV)) {
            val algorithm = BacklightMeteringAlgorithm(range)
            val initial = algorithm.process(frame(0, y), 0)
            assertEquals(BacklightMeteringAlgorithm.ExposureDecision.KEEP_EV, initial.decision)
            assertEquals("等待曝光稳定", initial.reason)
            for (ms in 500L..2000L step 500) {
                val waiting = algorithm.process(frame(ms, y), ms * 1_000_000L)
                assertEquals(BacklightMeteringAlgorithm.ExposureDecision.KEEP_EV, waiting.decision)
                assertNull(waiting.requestedEv)
                if (ms == 1500L) assertTrue(waiting.reason.contains("1/3"))
                if (ms == 2000L) assertTrue(waiting.reason.contains("2/3"))
            }
            val update = algorithm.process(frame(2500, y), 2_500_000_000L)
            assertEquals(decision, update.decision)
            assertEquals(BacklightMeteringAlgorithm.Status.UPDATE_EXPOSURE, update.status)
            val restore = algorithm.process(null, 3_000_000_000L)
            assertEquals(BacklightMeteringAlgorithm.ExposureDecision.RESTORE_BASELINE, restore.decision)
            assertEquals(0, restore.requestedIndex)
            assertEquals(0f, restore.requestedEv!!, 0f)
            assertTrue(restore.reason.contains("没有输入图像"))
            assertEquals(BacklightMeteringAlgorithm.ExposureDecision.RESTORE_BASELINE, algorithm.reset(3_500_000_000L).decision)
        }
    }

    @Test fun invalidInputsExplainWhyNoDecisionIsAvailable() {
        fun check(input: BacklightMeteringAlgorithm.FrameInput?, now: Long, expected: String) {
            val output = BacklightMeteringAlgorithm(range).process(input, now)
            assertEquals(BacklightMeteringAlgorithm.Status.INVALID_SAMPLE, output.status)
            assertEquals(BacklightMeteringAlgorithm.ExposureDecision.NO_DECISION, output.decision)
            assertNull(output.requestedIndex)
            assertTrue(output.reason, output.reason.contains(expected))
        }
        check(null, 0, "没有输入图像")
        check(frame(500), 0, "晚于决策时间")
        check(frame(0), 1_500_000_001L, "超过1.5秒")
        check(frame(0).copy(rowStrideBytes = 1), 0, "步长")
        check(frame(0).copy(width = 1, height = 1), 0, "五区统计不完整")
        val algorithm = BacklightMeteringAlgorithm(range)
        assertTrue(algorithm.evaluate(null, 0).reason.contains("宿主取图日志"))
        val snapshot = HighlightStatistics.sample(frame(0).yPlane, 64, 48, 64, 1, 0)!!
        assertTrue(algorithm.evaluate(snapshot.copy(brightFraction = Float.NaN), 0).reason.contains("越界"))
        val unsupported = BacklightMeteringAlgorithm(BacklightExposureRange()).process(frame(0), 0)
        assertEquals(BacklightMeteringAlgorithm.ExposureDecision.NO_DECISION, unsupported.decision)
        assertEquals("相机不支持曝光补偿", unsupported.reason)
    }

    @Test fun duplicateOutOfOrderAndFastSamplesHaveDifferentReasons() {
        val algorithm = BacklightMeteringAlgorithm(range)
        algorithm.process(frame(500), 500_000_000L)
        for ((ms, expected) in listOf(500L to "重复", 400L to "乱序", 700L to "不足500ms")) {
            val output = algorithm.process(frame(ms), 800_000_000L)
            assertEquals(BacklightMeteringAlgorithm.Status.SKIPPED, output.status)
            assertEquals(BacklightMeteringAlgorithm.ExposureDecision.NO_DECISION, output.decision)
            assertNull(output.requestedEv)
            assertTrue(output.reason, output.reason.contains(expected))
        }
    }

    @Test fun targetBrightnessAndLimitsAreValidHoldDecisions() {
        for ((y, settings, reason) in listOf(
            Triple(112, BacklightBalanceSettings(), "保持曝光"),
            Triple(60, BacklightBalanceSettings(maxIncreaseEv = 0f), "已达提亮上限"),
            Triple(200, BacklightBalanceSettings(maxDecreaseEv = 0f), "已达降曝下限"))) {
            val algorithm = BacklightMeteringAlgorithm(range, settings = settings)
            algorithm.process(frame(0, y), 0)
            val output = algorithm.process(frame(1500, y), 1_500_000_000L)
            assertEquals(BacklightMeteringAlgorithm.Status.HOLD, output.status)
            assertEquals(BacklightMeteringAlgorithm.ExposureDecision.KEEP_EV, output.decision)
            assertNull(output.requestedEv)
            assertEquals(reason, output.reason)
        }
    }

    @Test fun directionUsesPreviousEvNotTheSignOfTheRequestedEv() {
        val increasing = BacklightMeteringAlgorithm(range, manualEv = -1f,
            settings = BacklightBalanceSettings(confirmations = 1))
        increasing.process(frame(0), 0)
        val negativeRequest = increasing.process(frame(1500), 1_500_000_000L)
        assertTrue(negativeRequest.requestedEv!! < 0f)
        assertEquals(BacklightMeteringAlgorithm.ExposureDecision.INCREASE_EV, negativeRequest.decision)
        val decreasing = BacklightMeteringAlgorithm(range, manualEv = 2f / 3f,
            settings = BacklightBalanceSettings(confirmations = 1))
        decreasing.process(frame(0, 200), 0)
        val zeroRequest = decreasing.process(frame(1500, 200), 1_500_000_000L)
        assertEquals(0f, zeroRequest.requestedEv!!, 0f)
        assertEquals(0, zeroRequest.requestedIndex)
        assertEquals(BacklightMeteringAlgorithm.ExposureDecision.DECREASE_EV, zeroRequest.decision)
    }

    @Test fun rawFrameToEvHasInitialWaitConfirmationsAndStructuredOutput() {
        val algorithm = BacklightMeteringAlgorithm(range)
        for (ms in 0L..2000L step 500) assertNull(algorithm.process(frame(ms), ms * 1_000_000L).requestedEv)
        val result = algorithm.process(frame(2500), 2_500_000_000L)
        assertEquals(BacklightMeteringAlgorithm.Status.UPDATE_EXPOSURE, result.status)
        assertEquals(2f / 3f, result.requestedEv!!, 0.0001f)
        assertEquals(2, result.requestedIndex)
        assertEquals(result.currentEv, result.correctionEv, 0f)
        assertEquals(60f, result.statistics!!.regions!!.weightedLuma, 0.001f)
        assertEquals(60, result.statistics.centerDarkLuma)
        assertEquals(60, result.statistics.percentile99)
        assertEquals(0f, result.statistics.brightFraction, 0f)
        assertTrue(result.subjectProtected)
    }

    @Test fun fastRepeatedAndOutOfOrderFramesDoNotIncreaseConfirmationCount() {
        val algorithm = BacklightMeteringAlgorithm(range, settings = BacklightBalanceSettings(settleSeconds = 0.5f))
        algorithm.process(frame(0), 0)
        for (ms in 1L..499L step 17) {
            assertEquals(BacklightMeteringAlgorithm.Status.SKIPPED, algorithm.process(frame(ms), ms * 1_000_000L).status)
        }
        assertNull(algorithm.process(frame(500), 500_000_000L).requestedEv)
        assertEquals(BacklightMeteringAlgorithm.Status.SKIPPED, algorithm.process(frame(500), 500_000_000L).status)
        assertEquals(BacklightMeteringAlgorithm.Status.SKIPPED, algorithm.process(frame(400), 600_000_000L).status)
        assertNull(algorithm.process(frame(1000), 1_000_000_000L).requestedEv)
        assertNotNull(algorithm.process(frame(1500), 1_500_000_000L).requestedEv)
    }

    @Test fun missingStaleFutureAndMalformedInputRestoreBaselineOnlyOnce() {
        for (invalid in listOf("missing", "stale", "future", "layout", "tiny")) {
            val algorithm = BacklightMeteringAlgorithm(range,
                settings = BacklightBalanceSettings(confirmations = 1, settleSeconds = 0.5f))
            algorithm.process(frame(0), 0)
            assertNotNull(algorithm.process(frame(500), 500_000_000L).requestedEv)
            val input = when (invalid) {
                "missing" -> null
                "stale" -> frame(500)
                "future" -> frame(4000)
                "layout" -> frame(2500).copy(rowStrideBytes = 1)
                else -> BacklightMeteringAlgorithm.FrameInput(ByteBuffer.wrap(byteArrayOf(60)), 1, 1, 1, 1, 2_500_000_000L)
            }
            val result = algorithm.process(input, 2_500_000_000L)
            assertEquals(invalid, BacklightMeteringAlgorithm.Status.RESTORE_BASELINE, result.status)
            assertEquals(0f, result.requestedEv!!, 0f)
            assertEquals(0, result.requestedIndex)
            assertNull(result.statistics)
            val missing = algorithm.process(null, 2_600_000_000L)
            assertEquals(BacklightMeteringAlgorithm.Status.INVALID_SAMPLE, missing.status)
            assertNull(missing.requestedEv)
        }
    }

    @Test fun processAndDetachedAnalysisProduceIdenticalDecisions() {
        val direct = BacklightMeteringAlgorithm(range, 1f)
        val detached = BacklightMeteringAlgorithm(range, 1f)
        for (ms in 0L..30000L step 500) {
            val input = frame(ms, if (ms < 15000) 200 else 60)
            val snapshot = HighlightStatistics.sample(input.yPlane, input.width, input.height,
                input.rowStrideBytes, input.pixelStrideBytes, input.sampledAtNs, input.rotationDegrees)
            assertEquals(direct.process(input, input.sampledAtNs), detached.evaluate(snapshot, input.sampledAtNs))
        }
    }

    @Test fun sourceLayoutReadOnlyDirectBufferAndRotationRemainSupported() {
        val buffer = ByteBuffer.allocateDirect(2 + 48 * 131)
        buffer.position(2)
        for (y in 0 until 48) for (x in 0 until 64) buffer.put(2 + y * 131 + x * 2, 70)
        val readOnly = buffer.asReadOnlyBuffer()
        val algorithm = BacklightMeteringAlgorithm(range)
        val result = algorithm.process(BacklightMeteringAlgorithm.FrameInput(readOnly, 64, 48, 131, 2, 0, -90), 0)
        assertEquals(2, readOnly.position())
        assertEquals(buffer.limit(), readOnly.limit())
        assertEquals(70f, result.statistics!!.regions!!.weightedLuma, 0.001f)
        assertNull(result.requestedEv)
    }

    @Test fun unsupportedHardwareNeverRequestsEvAndInvalidConfigFailsFast() {
        for (invalid in listOf(BacklightExposureRange(), BacklightExposureRange(-9, 9, Float.NaN),
            BacklightExposureRange(1, 9, 0.3f), BacklightExposureRange(-9, 9, -1f))) {
            val algorithm = BacklightMeteringAlgorithm(invalid)
            val result = algorithm.process(frame(0), 0)
            assertEquals(BacklightMeteringAlgorithm.Status.EXPOSURE_UNSUPPORTED, result.status)
            assertNull(result.requestedEv)
            assertEquals(0f, result.currentEv, 0f)
            assertNull(algorithm.reset(0).requestedIndex)
        }
        assertThrows(IllegalArgumentException::class.java) { BacklightMeteringAlgorithm(range, Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) {
            BacklightMeteringAlgorithm(range, settings = BacklightBalanceSettings(protectBelow = 200))
        }
    }

    @Test fun resetReturnsQuantizedManualBaselineAndPauseClearsConfirmation() {
        val algorithm = BacklightMeteringAlgorithm(range, manualEv = 0.4f,
            settings = BacklightBalanceSettings(confirmations = 1, settleSeconds = 0.5f))
        algorithm.process(frame(0), 0)
        assertEquals(3, algorithm.process(frame(500), 500_000_000L).requestedIndex)
        val reset = algorithm.reset(600_000_000L)
        assertEquals(1, reset.requestedIndex)
        assertEquals(1f / 3f, reset.requestedEv!!, 0.0001f)
        assertEquals(0f, reset.correctionEv, 0f)
        assertNull(algorithm.process(frame(1000), 1_000_000_000L).requestedEv)
        algorithm.pause(1_500_000_000L)
        assertNull(algorithm.process(frame(1500), 1_500_000_000L).requestedEv)
        assertEquals(3, algorithm.process(frame(2000), 2_000_000_000L).requestedIndex)
    }

    @Test fun freshFacadeMatchesExistingPolicyForRegularSamples() {
        val algorithm = BacklightMeteringAlgorithm(range)
        val policy = HighlightExposurePolicy(range, 0f)
        policy.pause(0)
        for (ms in 0L..30000L step 500) {
            val f = frame(ms, if (ms < 15000) 200 else 60)
            val snapshot = HighlightStatistics.sample(f.yPlane, 64, 48, 64, 1, f.sampledAtNs)!!
            assertEquals(policy.update(snapshot, f.sampledAtNs), algorithm.evaluate(snapshot, f.sampledAtNs).requestedEv)
        }
    }
}
