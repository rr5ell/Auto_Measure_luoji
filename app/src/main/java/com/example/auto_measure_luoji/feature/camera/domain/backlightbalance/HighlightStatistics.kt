package com.example.auto_measure_luoji.feature.camera.domain.backlightbalance

import java.nio.ByteBuffer

/** YUV 分析帧的稀疏统计，不转换 RGB、不修改缓冲区位置、不占用模型工作线程。 */
data class HighlightStatistics(val sampledAtNs: Long, val brightFraction: Float, val percentile99: Int) {
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
            for (gy in 0 until rows) for (gx in 0 until columns) {
                val x = ((2L * gx + 1) * width / (2L * columns)).toInt()
                val y = ((2L * gy + 1) * height / (2L * rows)).toInt()
                val index = (origin + y.toLong() * rowStride + x.toLong() * pixelStride).toInt()
                val value = buffer.get(index).toInt() and 255
                histogram[value]++
                if (value >= 235) bright++
            }
            val count = columns * rows
            val rank = (count * 99 + 99) / 100
            var accumulated = 0
            val percentile = histogram.indices.first { accumulated += histogram[it]; accumulated >= rank }
            return HighlightStatistics(nowNs, bright.toFloat() / count, percentile)
        }
    }
}
