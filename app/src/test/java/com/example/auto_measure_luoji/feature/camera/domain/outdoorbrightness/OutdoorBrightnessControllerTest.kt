package com.example.auto_measure_luoji.feature.camera.domain.outdoorbrightness

import org.junit.Assert.*
import org.junit.Test

class OutdoorBrightnessControllerTest {
    @Test fun restoresOriginalIncludingSystemDefaultAndAlreadyMaximum() {
        for (initial in listOf(-1f, 0f, 0.4f, 1f)) {
            var brightness = initial
            val controller = OutdoorBrightnessController({ brightness }, { brightness = it })
            assertTrue(controller.requestMaximum().isSuccess)
            assertEquals(1f, brightness, 0f)
            assertTrue(controller.requestMaximum().isSuccess)
            assertTrue(controller.restore().isSuccess)
            assertEquals(initial, brightness, 0f)
            assertFalse(controller.hasOverride)
            assertTrue(controller.restore().isSuccess)
        }
    }

    @Test fun unavailableWindowFailsWithoutClaimingOverride() {
        val controller = OutdoorBrightnessController({ error("没有窗口") }, { fail("不得写入") })
        assertEquals("没有窗口", controller.requestMaximum().exceptionOrNull()?.message)
        assertFalse(controller.hasOverride)
    }

    @Test fun invalidOriginalDoesNotWrite() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -2f, 2f)) {
            val controller = OutdoorBrightnessController({ invalid }, { fail("不得写入") })
            assertTrue(controller.requestMaximum().isFailure)
            assertFalse(controller.hasOverride)
        }
    }

    @Test fun rejectedRequestKeepsRecoveryAvailable() {
        val controller = OutdoorBrightnessController({ 0.4f }, {})
        assertEquals("窗口未接受最高亮度请求", controller.requestMaximum().exceptionOrNull()?.message)
        assertTrue(controller.hasOverride)
        assertTrue(controller.restore().isSuccess)
        assertFalse(controller.hasOverride)
    }

    @Test fun partiallyAppliedRequestCanBeRestored() {
        var brightness = -1f
        var failWrite = true
        val controller = OutdoorBrightnessController({ brightness }, {
            brightness = it
            if (failWrite) throw SecurityException("拒绝请求")
        })
        assertEquals("拒绝请求", controller.requestMaximum().exceptionOrNull()?.message)
        failWrite = false
        assertTrue(controller.restore().isSuccess)
        assertEquals(-1f, brightness, 0f)
    }

    @Test fun restoreFailureRetainsOriginalForRetry() {
        var brightness = 0.3f
        var rejectRestore = false
        val controller = OutdoorBrightnessController({ brightness }, {
            if (rejectRestore) error("恢复被拒绝")
            brightness = it
        })
        controller.requestMaximum()
        rejectRestore = true
        assertTrue(controller.restore().isFailure)
        assertTrue(controller.hasOverride)
        rejectRestore = false
        assertTrue(controller.restore().isSuccess)
        assertEquals(0.3f, brightness, 0f)
    }

    @Test fun externalBrightnessChangeIsNotOverwritten() {
        var brightness = -1f
        val controller = OutdoorBrightnessController({ brightness }, { brightness = it })
        controller.requestMaximum()
        brightness = 0.6f
        controller.restore()
        assertEquals(0.6f, brightness, 0f)
        assertFalse(controller.hasOverride)
    }
}
