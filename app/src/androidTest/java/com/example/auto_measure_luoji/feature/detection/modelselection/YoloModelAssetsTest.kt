package com.example.auto_measure_luoji.feature.detection.modelselection

import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.feature.detection.domain.YoloDetector
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class YoloModelAssetsTest {
    @Test fun allBundledModelsLoadAndInferWithExistingParser() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        try {
            YoloModelOption.values().forEach { model ->
                val bytes = context.assets.open(model.assetPath).use { it.readBytes() }
                val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                buffer.put(bytes).rewind()
                Interpreter(buffer).use { interpreter ->
                    assertEquals(1, interpreter.inputTensorCount)
                    assertEquals(1, interpreter.outputTensorCount)
                    val input = interpreter.getInputTensor(0)
                    val output = interpreter.getOutputTensor(0)
                    val inputShape = input.shape()
                    val outputShape = output.shape()
                    Log.i("YoloModelAssetsTest", "${model.label}: input=${inputShape.contentToString()} output=${outputShape.contentToString()}")
                    assertEquals(DataType.FLOAT32, input.dataType())
                    assertEquals(DataType.FLOAT32, output.dataType())
                    assertEquals(4, inputShape.size)
                    assertEquals(1, inputShape[0])
                    assertEquals(3, inputShape[3])
                    assertEquals(3, outputShape.size)
                    assertEquals(1, outputShape[0])
                    // 单类旗杆输出必须匹配现有 cx/cy/w/h/conf 解析，避免空检出掩盖格式不兼容。
                    assertTrue((outputShape[1] == 5 && outputShape[2] > 32) ||
                        (outputShape[2] == 5 && outputShape[1] > 32))
                }
                YoloDetector(context, model.assetPath, "classes.txt", useGpu = false).use { detector ->
                    val result = detector.detect(bitmap)
                    assertTrue(result.detections.all { it.score.isFinite() })
                }
            }
        } finally { bitmap.recycle() }
    }
}
