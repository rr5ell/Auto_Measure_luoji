package com.example.auto_measure_luoji.feature.camera.domain

import android.media.Image
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YUV_420_888 帧处理器
 *
 * 直接从 YUV plane 采样，旋转/letterbox/归一化后写入 TFLite 输入 Buffer。
 * 不生成中间 Bitmap，不走 getPixels()。
 */
object YuvFrameProcessor {

    /**
     * Letterbox 映射计划（不绑定固定尺寸）
     */
    data class LetterboxPlan(
        val srcW: Int,
        val srcH: Int,
        val dstW: Int,
        val dstH: Int,
        val scale: Float,
        val padX: Int,
        val padY: Int,
        val nw: Int,
        val nh: Int,
        val xMap: IntArray,
        val yMap: IntArray,
    ) {
        /** 将模型输出坐标映射回原图坐标 */
        fun mapToOriginal(x: Float, y: Float, origW: Int, origH: Int): Pair<Float, Float> {
            val ox = ((x - padX) / scale).coerceIn(0f, origW.toFloat())
            val oy = ((y - padY) / scale).coerceIn(0f, origH.toFloat())
            return ox to oy
        }
    }

    private var cachedPlan: LetterboxPlan? = null

    /**
     * 构建或获取缓存的 Letterbox 映射计划
     */
    fun getOrBuildPlan(srcW: Int, srcH: Int, dstW: Int, dstH: Int): LetterboxPlan {
        val cached = cachedPlan
        if (cached != null && cached.srcW == srcW && cached.srcH == srcH &&
            cached.dstW == dstW && cached.dstH == dstH) {
            return cached
        }

        val r = min(dstW.toFloat() / srcW.toFloat(), dstH.toFloat() / srcH.toFloat())
        val nw = max(1, (srcW * r).roundToInt())
        val nh = max(1, (srcH * r).roundToInt())
        val padX = (dstW - nw) / 2
        val padY = (dstH - nh) / 2

        val xMap = IntArray(nw) { x -> ((x * srcW) / nw).coerceAtMost(srcW - 1) }
        val yMap = IntArray(nh) { y -> ((y * srcH) / nh).coerceAtMost(srcH - 1) }

        val plan = LetterboxPlan(srcW, srcH, dstW, dstH, r, padX, padY, nw, nh, xMap, yMap)
        cachedPlan = plan
        return plan
    }

    /**
     * 从 YUV_420_888 Image 直接填充 TFLite FLOAT32 输入 Buffer
     *
     * @param image        YUV_420_888 格式的 Image
     * @param rotation     传感器旋转角度 (0, 90, 180, 270)
     * @param dstW         模型输入宽度
     * @param dstH         模型输入高度
     * @param outBuffer    TFLite 输入 ByteBuffer (FLOAT32, 已 rewind)
     */
    fun fillInputFromYuv(
        image: Image,
        rotation: Int,
        dstW: Int,
        dstH: Int,
        outBuffer: ByteBuffer,
    ) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        // 旋转后的源尺寸
        val rotatedW: Int
        val rotatedH: Int
        if (rotation == 90 || rotation == 270) {
            rotatedW = image.height
            rotatedH = image.width
        } else {
            rotatedW = image.width
            rotatedH = image.height
        }

        val plan = getOrBuildPlan(rotatedW, rotatedH, dstW, dstH)
        val inv255 = 1f / 255f
        val padVal = 114f * inv255

        outBuffer.rewind()
        val floatBuffer = outBuffer.asFloatBuffer()

        for (y in 0 until dstH) {
            val dy = y - plan.padY
            if (dy < 0 || dy >= plan.nh) {
                // 填充区域
                for (x in 0 until dstW) {
                    floatBuffer.put(padVal); floatBuffer.put(padVal); floatBuffer.put(padVal)
                }
                continue
            }

            val srcY = plan.yMap[dy]

            for (x in 0 until dstW) {
                val dx = x - plan.padX
                if (dx < 0 || dx >= plan.nw) {
                    floatBuffer.put(padVal); floatBuffer.put(padVal); floatBuffer.put(padVal)
                    continue
                }

                val srcX = plan.xMap[dx]

                // 将旋转后的坐标映射回原始 YUV 坐标
                val origX: Int
                val origY: Int
                when (rotation) {
                    90 -> { origX = srcY; origY = image.height - 1 - srcX }
                    180 -> { origX = image.width - 1 - srcX; origY = image.height - 1 - srcY }
                    270 -> { origX = image.width - 1 - srcY; origY = srcX }
                    else -> { origX = srcX; origY = srcY }
                }

                // YUV → RGB
                val yVal =getY(yBuffer, origX, origY, yRowStride, image.width)
                val uVal = getUV(uBuffer, origX, origY, uvRowStride, uvPixelStride, image.width)
                val vVal = getUV(vBuffer, origX, origY, uvRowStride, uvPixelStride, image.width)

                val r = yVal + 1.402f * (vVal - 128)
                val g = yVal - 0.344136f * (uVal - 128) - 0.714136f * (vVal - 128)
                val b = yVal + 1.772f * (uVal - 128)

                // 归一化到 [0, 1]
                floatBuffer.put((r.coerceIn(0f, 255f)) * inv255)
                floatBuffer.put((g.coerceIn(0f, 255f)) * inv255)
                floatBuffer.put((b.coerceIn(0f, 255f)) * inv255)
            }
        }

        outBuffer.rewind()
    }

    private fun getY(buffer: ByteBuffer, x: Int, y: Int, rowStride: Int, width: Int): Float {
        val idx = y * rowStride + x
        return (buffer.get(idx).toInt() and 0xFF).toFloat()
    }

    private fun getUV(buffer: ByteBuffer, x: Int, y: Int, rowStride: Int, pixelStride: Int, width: Int): Float {
        val uvX = x / 2
        val uvY = y / 2
        val idx = uvY * rowStride + uvX * pixelStride
        return (buffer.get(idx).toInt() and 0xFF).toFloat()
    }

    /**
     * 获取 ImageReader 中最新的检测帧
     *
     * @return 最新的 Image，调用方用完后必须 close()
     */
    fun acquireLatestForDetection(reader: android.media.ImageReader): Image? {
        return try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        }
    }
}
