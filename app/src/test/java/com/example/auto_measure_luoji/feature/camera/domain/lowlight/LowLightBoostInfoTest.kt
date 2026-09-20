package com.example.auto_measure_luoji.feature.camera.domain.lowlight

import org.junit.Assert.*
import org.junit.Test

class LowLightBoostInfoTest {
    @Test fun preservesValuesAndIdentifiesOutputCamera() {
        val info = LowLightBoostInfo("7", true, true, 0.003f, 30f, true)
        assertEquals("低光增强范围[物理7]：\n0.003 ~ 30.0 lx", info.panelText)
        assertTrue(info.logText.contains("CONTROL_LOW_LIGHT_BOOST_INFO_LUMINANCE_RANGE=[0.003, 30.0]"))
        assertTrue(info.logText.contains("aeLowLightBoostSupported=true"))
        assertTrue(info.copy(physical = false, cameraId = "0").panelText.contains("逻辑0"))
    }

    @Test fun missingRangeIsNotInventedOrTreatedAsZero() {
        val info = LowLightBoostInfo("0", false, true, aeModeSupported = false)
        assertEquals("未提供", info.rangeText)
        assertTrue(info.logText.contains("LUMINANCE_RANGE=null"))
        assertTrue(info.logText.contains("aeLowLightBoostSupported=false"))
    }

    @Test fun oldApiAndReadFailureRemainDistinct() {
        assertEquals("需 Android 15+", LowLightBoostInfo("0", false, false).rangeText)
        assertEquals("读取失败", LowLightBoostInfo("0", false, true, readError = "test").rangeText)
    }

    @Test fun invalidAndZeroRangesAreHandled() {
        for ((lower, upper) in listOf(-1f to 2f, 5f to 2f, Float.NaN to 2f,
            0f to Float.POSITIVE_INFINITY, null to 2f)) {
            assertEquals("设备返回异常区间", LowLightBoostInfo("0", false, true, lower, upper).rangeText)
        }
        assertEquals("0.0 ~ 0.0 lx", LowLightBoostInfo("0", false, true, 0f, 0f).rangeText)
    }
}
