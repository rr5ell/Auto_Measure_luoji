package com.example.auto_measure_luoji.feature.camera.domain.backlightbalance

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class BacklightMeteringAlgorithmTest {
    private val range = BacklightExposureRange(-9, 9, 1f / 3f)
    private fun frame(ms: Long, y: Int = 60) = BacklightMeteringAlgorithm.FrameInput(
        ByteBuffer.wrap(ByteArray(64 * 48) { y.toByte() }), 64, 48, 64, 1, ms * 1_000_000L)

    @Test fun rawFrameToEvHasInitialWaitConfirmationsAndStructuredOutput() {
        val algorithm = BacklightMeteringAlgorithm(range)
        for (ms in 0L..2000L step 500) assertNull(algorithm.process(frame(ms), ms * 1_000_000L).requestedEv)
        val result = algorithm.process(frame(2500), 2_500_000_000L)
        assertEquals(BacklightMeteringAlgorithm.Status.UPDATE_EXPOSURE, result.status)
        assertEquals(1f / 3f, result.requestedEv!!, 0.0001f)
        assertEquals(1, result.requestedIndex)
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
        assertEquals(2, algorithm.process(frame(500), 500_000_000L).requestedIndex)
        val reset = algorithm.reset(600_000_000L)
        assertEquals(1, reset.requestedIndex)
        assertEquals(1f / 3f, reset.requestedEv!!, 0.0001f)
        assertEquals(0f, reset.correctionEv, 0f)
        assertNull(algorithm.process(frame(1000), 1_000_000_000L).requestedEv)
        algorithm.pause(1_500_000_000L)
        assertNull(algorithm.process(frame(1500), 1_500_000_000L).requestedEv)
        assertEquals(2, algorithm.process(frame(2000), 2_000_000_000L).requestedIndex)
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
