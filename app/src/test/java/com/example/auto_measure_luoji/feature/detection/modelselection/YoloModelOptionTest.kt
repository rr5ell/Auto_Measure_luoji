package com.example.auto_measure_luoji.feature.detection.modelselection

import org.junit.Assert.*
import org.junit.Test

class YoloModelOptionTest {
    @Test fun defaultsAndUnknownPathsUseV34() {
        assertEquals(YoloModelOption.V34, YoloModelOption.fromAssetPath(null))
        assertEquals(YoloModelOption.V34, YoloModelOption.fromAssetPath("/tmp/model.tflite"))
    }

    @Test fun supportedModelsRoundTrip() {
        YoloModelOption.values().forEach {
            assertEquals(it, YoloModelOption.fromAssetPath(it.assetPath))
        }
        assertEquals(2, YoloModelOption.values().map { it.assetPath }.distinct().size)
    }
}
