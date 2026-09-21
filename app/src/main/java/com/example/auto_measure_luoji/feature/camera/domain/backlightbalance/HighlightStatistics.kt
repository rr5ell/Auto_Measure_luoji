package com.example.auto_measure_luoji.feature.camera.domain.backlightbalance

import java.nio.ByteBuffer

/** YUV 分析帧的稀疏统计，不转换 RGB、不修改缓冲区位置、不占用模型工作线程。 */
data class HighlightStatistics(val sampledAtNs: Long, val brightFraction: Float, val percentile99: Int,
                               val centerDarkLuma: Int) {
    companion object {
        const val INTERVAL_NS = 500_000_000L

        fun sample(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
                   nowNs: Long): HighlightStatistics? {
            if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return null
            val origin = buffer.position().toLong()
            val last = origin + (height - 1L) * rowStride + (width - 1L) * pixelStride
            if (last >= buffer.limit() || (width - 1L) * pixelStride >= rowStride) return null
            // 网格不超过 64x48，取每格中心，索引遵循 Y 平面的行/像素步长。
            // Y>=235 是 8-bit SDR 分析流的保守高亮代理：有限范围接近白端，
            // 全范围约为 92%。它不是传感器 RAW 饱和率，也不是 HLG 的物理亮度。
            val columns = minOf(width, 64)
            val rows = minOf(height, 48)
            var bright = 0
            val histogram = IntArray(256)
            val centerHistogram = IntArray(256)
            var centerCount = 0
            // 在原始分析帧网格中取中心宽高各 30%，小图向外取整至少覆盖一个点。
            // P20 是中心区域亮度排序的第 20 百分位，比均值更能反映暗处，
            // 但不等同于旗杆分割；很细/偏离中心的目标仍可能被背景占据。
            val left = (columns * 0.35).toInt()
            val right = kotlin.math.ceil(columns * 0.65).toInt()
            val top = (rows * 0.35).toInt()
            val bottom = kotlin.math.ceil(rows * 0.65).toInt()
            for (gy in 0 until rows) for (gx in 0 until columns) {
                val x = ((2L * gx + 1) * width / (2L * columns)).toInt()
                val y = ((2L * gy + 1) * height / (2L * rows)).toInt()
                val index = (origin + y.toLong() * rowStride + x.toLong() * pixelStride).toInt()
                val value = buffer.get(index).toInt() and 255
                histogram[value]++
                if (gx in left until right && gy in top until bottom) {
                    centerHistogram[value]++
                    centerCount++
                }
                if (value >= 235) bright++
            }
            val count = columns * rows
            fun percentile(values: IntArray, size: Int, percent: Int): Int {
                val rank = (size * percent + 99) / 100
                var accumulated = 0
                return values.indices.first { accumulated += values[it]; accumulated >= rank }
            }
            return HighlightStatistics(nowNs, bright.toFloat() / count,
                percentile(histogram, count, 99), percentile(centerHistogram, centerCount, 20))
        }
    }
}
