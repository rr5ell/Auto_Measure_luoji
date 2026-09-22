package com.example.auto_measure_luoji.feature.camera.domain.backlightbalance

import java.nio.ByteBuffer
import com.example.auto_measure_luoji.feature.camera.domain.centermetering.CenterMeteringRegion
import com.example.auto_measure_luoji.feature.camera.domain.tapmetering.TapMeteringPoint

/** YUV 分析帧的稀疏统计，不转换 RGB、不修改缓冲区位置、不占用模型工作线程。 */
data class HighlightStatistics(val sampledAtNs: Long, val brightFraction: Float, val percentile99: Int,
                               val centerDarkLuma: Int, val regions: RegionalMetering? = null,
                               val tapPoint: TapMeteringPoint? = null, val tapZone: MeteringZone? = null,
                               val globalZone: MeteringZone? = null, val darkFraction: Float = 0f) {
    companion object {
        const val INTERVAL_NS = 500_000_000L

        fun sample(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
                   nowNs: Long, rotationDegrees: Int = 0, tapPoint: TapMeteringPoint? = null,
                   tapDisplayScale: Float = 1f): HighlightStatistics? {
            if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return null
            val rotation = (rotationDegrees % 360 + 360) % 360
            if (rotation % 90 != 0) return null
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
            val regionHistograms = Array(5) { IntArray(256) }
            val regionCounts = IntArray(5)
            // 点击点已经反算为原始缓冲区坐标，不再旋转；复用相同 30% 窗口和边缘平移。
            val tapBounds = tapPoint?.takeIf { it.valid }?.let {
                CenterMeteringRegion.calculate(CenterMeteringRegion.Bounds(0, 0, width, height),
                    width, height, tapDisplayScale, it.x, it.y)
            }
            val tapHistogram = IntArray(256)
            var tapCount = 0
            for (gy in 0 until rows) for (gx in 0 until columns) {
                val x = ((2L * gx + 1) * width / (2L * columns)).toInt()
                val y = ((2L * gy + 1) * height / (2L * rows)).toInt()
                val index = (origin + y.toLong() * rowStride + x.toLong() * pixelStride).toInt()
                val value = buffer.get(index).toInt() and 255
                histogram[value]++
                val region = MeteringRegion.at((x + 0.5f) / width, (y + 0.5f) / height, rotation).ordinal
                regionHistograms[region][value]++
                regionCounts[region]++
                if (tapBounds != null && x in tapBounds.left until tapBounds.right &&
                    y in tapBounds.top until tapBounds.bottom) {
                    tapHistogram[value]++
                    tapCount++
                }
                if (value >= 235) bright++
            }
            val count = columns * rows
            fun percentile(values: IntArray, size: Int, percent: Int): Int {
                val rank = (size * percent + 99) / 100
                var accumulated = 0
                return values.indices.first { accumulated += values[it]; accumulated >= rank }
            }
            // 用直方图累计秩裁去两端 5%，即使阈值落在同一亮度桶，也只减去相应数量。
            fun zone(values: IntArray, size: Int): MeteringZone {
                if (size == 0) return MeteringZone(0f, 0f, 0)
                val trim = size / 20
                var rank = 0
                var sum = 0L
                var high = 0
                values.forEachIndexed { value, number ->
                    val kept = (minOf(rank + number, size - trim) - maxOf(rank, trim)).coerceAtLeast(0)
                    sum += value.toLong() * kept
                    rank += number
                    if (value >= 235) high += number
                }
                return MeteringZone(sum.toFloat() / (size - 2 * trim), high.toFloat() / size, size)
            }
            fun zone(index: Int) = zone(regionHistograms[index], regionCounts[index])
            val regions = RegionalMetering(zone(0), zone(1), zone(2), zone(3), zone(4)).takeIf { it.valid }
            val centerCount = regionCounts[MeteringRegion.CENTER.ordinal]
            return HighlightStatistics(nowNs, bright.toFloat() / count,
                percentile(histogram, count, 99), if (centerCount > 0)
                    percentile(regionHistograms[MeteringRegion.CENTER.ordinal], centerCount, 20)
                else percentile(histogram, count, 20), regions, tapPoint,
                if (tapBounds != null) zone(tapHistogram, tapCount) else null,
                zone(histogram, count), histogram.take(49).sum().toFloat() / count)
        }
    }
}
