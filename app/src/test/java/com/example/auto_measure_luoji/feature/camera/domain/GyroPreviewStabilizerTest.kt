package com.example.auto_measure_luoji.feature.camera.domain

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroPreviewStabilizerTest {

    @Test
    fun repeatedFrameTimestampDoesNotResetComputedTransform() {
        val stabilizer = testStabilizer()
        val t0 = 1_000_000_000L
        val t1 = t0 + 33_333_333L

        stabilizer.recordGyroSample(t0, gyroXRadPerSec = 0f, gyroYRadPerSec = 0.25f, gyroZRadPerSec = 0f)
        stabilizer.updateForFrame(
            frameTimestampNs = t0,
            exposureTimeNs = 0L,
            nowElapsedRealtimeNs = t0,
            viewWidth = 1080f,
            viewHeight = 1920f,
            opticalZoomBase = 1f,
            digitalZoomRatio = 8f,
        )

        stabilizer.recordGyroSample(t1, gyroXRadPerSec = 0f, gyroYRadPerSec = 0.25f, gyroZRadPerSec = 0f)
        val moved = stabilizer.updateForFrame(
            frameTimestampNs = t1,
            exposureTimeNs = 0L,
            nowElapsedRealtimeNs = t1,
            viewWidth = 1080f,
            viewHeight = 1920f,
            opticalZoomBase = 1f,
            digitalZoomRatio = 8f,
        )

        assertTrue(abs(moved.translationX) > 0.01f)

        val repeated = stabilizer.updateForFrame(
            frameTimestampNs = t1,
            exposureTimeNs = 0L,
            nowElapsedRealtimeNs = t1,
            viewWidth = 1080f,
            viewHeight = 1920f,
            opticalZoomBase = 1f,
            digitalZoomRatio = 8f,
        )

        assertEquals(moved.translationX, repeated.translationX, 0f)
        assertEquals(moved.translationY, repeated.translationY, 0f)
    }

    @Test
    fun longFrameGapStillIntegratesUsableGyroWindow() {
        val stabilizer = testStabilizer()
        val t0 = 2_000_000_000L
        val t1 = t0 + 200_000_000L

        stabilizer.recordGyroSample(t0, gyroXRadPerSec = 0f, gyroYRadPerSec = 0.10f, gyroZRadPerSec = 0f)
        stabilizer.updateForFrame(
            frameTimestampNs = t0,
            exposureTimeNs = 0L,
            nowElapsedRealtimeNs = t0,
            viewWidth = 1080f,
            viewHeight = 1920f,
            opticalZoomBase = 1f,
            digitalZoomRatio = 8f,
        )

        for (offsetNs in listOf(50_000_000L, 100_000_000L, 150_000_000L, 200_000_000L)) {
            stabilizer.recordGyroSample(
                timestampNs = t0 + offsetNs,
                gyroXRadPerSec = 0f,
                gyroYRadPerSec = 0.10f,
                gyroZRadPerSec = 0f,
            )
        }

        val transform = stabilizer.updateForFrame(
            frameTimestampNs = t1,
            exposureTimeNs = 0L,
            nowElapsedRealtimeNs = t1,
            viewWidth = 1080f,
            viewHeight = 1920f,
            opticalZoomBase = 1f,
            digitalZoomRatio = 8f,
        )

        assertEquals(200f, transform.frameDeltaMs, 0.5f)
        assertTrue(transform.frameSamples > 0)
        assertTrue(abs(transform.translationX) > 0.01f)
    }

    private fun testStabilizer(): GyroPreviewStabilizer {
        return GyroPreviewStabilizer(
            GyroPreviewStabilizer.Config(
                outputLowPassAlpha = 1f,
                aimOutputLowPassAlpha = 1f,
                frameTimestampOffsetMs = 0f,
                softBoundaryEnabled = false,
                residualReleaseEnabled = false,
            ),
        )
    }

    @Test
    fun staleFramesCannotRewindOutputAndResetStartsANewSequence() {
        val stabilizer = testStabilizer()
        val start = 4_000_000_000L
        fun frame(timestamp: Long): GyroPreviewStabilizer.Transform {
            return stabilizer.updateForFrame(
                frameTimestampNs = timestamp,
                exposureTimeNs = 0L,
                nowElapsedRealtimeNs = timestamp,
                viewWidth = 1080f,
                viewHeight = 1920f,
                opticalZoomBase = 1f,
                digitalZoomRatio = 8f,
            )
        }
        stabilizer.recordGyroSample(start, 0f, 0.25f, 0f)
        frame(start)
        val next = start + 33_333_333L
        stabilizer.recordGyroSample(next, 0f, 0.25f, 0f)
        val moved = frame(next)
        assertTrue(abs(moved.translationX) > 0.01f)
        val stale = frame(start + 10_000_000L)
        assertEquals(moved.translationX, stale.translationX, 0f)
        assertEquals(moved.translationY, stale.translationY, 0f)
        stabilizer.reset()
        stabilizer.recordGyroSample(start, 0f, 0f, 0f)
        val restarted = frame(start)
        assertEquals(0f, restarted.translationX, 0f)
        assertEquals(0f, restarted.translationY, 0f)
    }
}
