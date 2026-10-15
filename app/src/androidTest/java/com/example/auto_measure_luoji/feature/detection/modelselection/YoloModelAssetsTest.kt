package com.example.auto_measure_luoji.feature.detection.modelselection

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.feature.detection.domain.YoloDetector
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloModelAssetsTest {
    @Test fun bothBundledModelsLoadAndInferWithExistingParser() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        try {
            YoloModelOption.values().forEach { model ->
                YoloDetector(context, model.assetPath, "classes.txt", useGpu = false).use { detector ->
                    val result = detector.detect(bitmap)
                    assertTrue(result.detections.all { it.score.isFinite() })
                }
            }
        } finally { bitmap.recycle() }
    }
}
