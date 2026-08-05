package com.example.auto_measure.feature.detection.domain

import org.tensorflow.lite.DataType
import org.tensorflow.lite.Tensor

object OutputReader {

    data class Float2D(
        val data: Array<FloatArray>,
        val n: Int,
        val c: Int,
        val layout: String,
    )

    fun toFloat2DAuto(outputObj: Any, outTensor: Tensor, outShape: IntArray, outType: DataType): Float2D {
        require(outShape.size == 3 && outShape[0] == 1) {
            "Only support rank3 output like [1,*,*], got=${outShape.contentToString()}"
        }

        val d1 = outShape[1]
        val d2 = outShape[2]
        val isCN = (d1 <= 32 && d2 > 32)
        val n = if (isCN) d2 else d1
        val c = if (isCN) d1 else d2
        val layout = if (isCN) "1,C,N" else "1,N,C"

        val out = Array(n) { FloatArray(c) }

        when (outType) {
            DataType.FLOAT32 -> {
                val arr = outputObj as Array<Array<FloatArray>>
                if (!isCN) {
                    for (i in 0 until n) {
                        val row = arr[0][i]
                        for (j in 0 until c) out[i][j] = row[j]
                    }
                } else {
                    for (j in 0 until c) {
                        val ch = arr[0][j]
                        for (i in 0 until n) out[i][j] = ch[i]
                    }
                }
            }
            DataType.UINT8, DataType.INT8 -> {
                val qp = outTensor.quantizationParams()
                val scale = qp.scale
                val zeroPoint = qp.zeroPoint
                val arr = outputObj as Array<Array<ByteArray>>

                fun deq(b: Byte): Float {
                    val v = b.toInt()
                    val u = if (outType == DataType.UINT8) (v and 0xFF) else v
                    return (u - zeroPoint) * scale
                }

                if (!isCN) {
                    for (i in 0 until n) {
                        val row = arr[0][i]
                        for (j in 0 until c) out[i][j] = deq(row[j])
                    }
                } else {
                    for (j in 0 until c) {
                        val ch = arr[0][j]
                        for (i in 0 until n) out[i][j] = deq(ch[i])
                    }
                }
            }
            else -> error("Unsupported outType=$outType")
        }

        return Float2D(out, n, c, layout)
    }
}
