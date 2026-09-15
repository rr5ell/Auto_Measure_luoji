package com.example.auto_measure_luoji.feature.camera.domain

import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 低成本预览运动估计器。
 *
 * 中心裁切亮度图使用亚像素 LK 双向跟踪及跨区域 RANSAC 验证。
 * 无纹理、时序断裂、前景主导等情况返回无效，不能把测量失败解释为静止。
 */
class PreviewMotionEstimator(
    private val sampleWidth: Int = DEFAULT_SAMPLE_WIDTH,
    private val aspectTolerance: Float = DEFAULT_ASPECT_TOLERANCE,
) {
    data class LumaFrame(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val sampleWidth: Int,
        val sampleHeight: Int,
        val scaleX: Float,
        val scaleY: Float,
        val luma: IntArray,
        val buildCostMs: Long,
    )

    data class Motion(
        val timestampNs: Long = 0L,
        val valid: Boolean = false,
        val hitSearchBoundary: Boolean = false,
        val rawDx: Float = 0f,
        val rawDy: Float = 0f,
        val score: Float = 0f,
        val trackedPoints: Int = 0,
        val inlierPoints: Int = 0,
        val coveredRegions: Int = 0,
        val reason: String = "initial",
    )

    private var previousSample: LumaFrame? = null
    private var previousTimestampNs = 0L

    @Synchronized
    fun reset() {
        previousSample = null
        previousTimestampNs = 0L
    }

    @Synchronized
    fun update(frame: LumaFrame, timestampNs: Long = 0L): Motion {
        if (timestampNs > 0L && previousTimestampNs > 0L && timestampNs <= previousTimestampNs) {
            return Motion(timestampNs = timestampNs, reason = "non_monotonic_timestamp")
        }
        val previous = previousSample
        val gapNs = timestampNs - previousTimestampNs
        previousSample = frame
        previousTimestampNs = timestampNs
        if (previous == null) {
            return Motion(timestampNs = timestampNs)
        }
        if (timestampNs > 0L && gapNs > 150_000_000L) {
            return Motion(timestampNs = timestampNs, reason = "frame_gap")
        }
        return SubpixelMotionTracker.estimate(previous, frame, timestampNs)
    }

    fun buildLumaFrame(
        image: Image,
        rotationDegrees: Int,
        width: Int = DEFAULT_OUTPUT_WIDTH,
        height: Int = DEFAULT_OUTPUT_HEIGHT,
    ): LumaFrame {
        check(SubpixelMotionTracker.available) { "OpenCV unavailable" }
        val startedMs = SystemClock.elapsedRealtime()
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val bytes = ByteArray(image.width * image.height)
        for (y in 0 until image.height) {
            if (plane.pixelStride == 1) {
                buffer.position(y * plane.rowStride)
                buffer.get(bytes, y * image.width, image.width)
            } else {
                for (x in 0 until image.width) {
                    bytes[y * image.width + x] = buffer.get(y * plane.rowStride + x * plane.pixelStride)
                }
            }
        }
        val gray = Mat(image.height, image.width, CvType.CV_8UC1)
        try {
            gray.put(0, 0, bytes)
            return sampleGray(gray, rotationDegrees, width, height, startedMs)
        } finally {
            gray.release()
        }
    }

    fun buildLumaFrame(bitmap: Bitmap): LumaFrame {
        check(SubpixelMotionTracker.available) { "OpenCV unavailable" }
        val startedMs = SystemClock.elapsedRealtime()
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return sampleGray(gray, 0, bitmap.width, bitmap.height, startedMs)
        } finally {
            gray.release()
            rgba.release()
        }
    }

    private fun sampleGray(gray: Mat, rotation: Int, width: Int, height: Int, startedMs: Long): LumaFrame {
        val oriented = Mat()
        val resized = Mat()
        var roi: Mat? = null
        try {
            when (((rotation % 360) + 360) % 360) {
                90 -> Core.rotate(gray, oriented, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(gray, oriented, Core.ROTATE_180)
                270 -> Core.rotate(gray, oriented, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> gray.copyTo(oriented)
            }
            val outputWidth = width.coerceAtLeast(1)
            val outputHeight = height.coerceAtLeast(1)
            val sw = min(sampleWidth, outputWidth).coerceAtLeast(1)
            val sh = max(1, (outputHeight * sw.toFloat() / outputWidth).roundToInt())
            val crop = centerCrop(oriented.cols(), oriented.rows(), outputWidth, outputHeight)
            val left = crop.left.roundToInt().coerceIn(0, oriented.cols() - 1)
            val top = crop.top.roundToInt().coerceIn(0, oriented.rows() - 1)
            roi = oriented.submat(org.opencv.core.Rect(left, top,
                crop.width.roundToInt().coerceIn(1, oriented.cols() - left),
                crop.height.roundToInt().coerceIn(1, oriented.rows() - top)))
            // 面积重采样在缩图前抑制混叠；直接抽取单像素会让细纹理随小位移跳变。
            Imgproc.resize(roi, resized, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val bytes = ByteArray(sw * sh)
            resized.get(0, 0, bytes)
            return LumaFrame(outputWidth, outputHeight, sw, sh, sw.toFloat() / outputWidth,
                sh.toFloat() / outputHeight, IntArray(bytes.size) { bytes[it].toInt() and 255 },
                SystemClock.elapsedRealtime() - startedMs)
        } finally {
            roi?.release()
            resized.release()
            oriented.release()
        }
    }


    private fun centerCrop(
        orientedWidth: Int,
        orientedHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): Crop {
        val targetAspect = outputWidth.toFloat() / outputHeight.toFloat()
        val orientedAspect = orientedWidth.toFloat() / orientedHeight.toFloat()
        var cropLeft = 0f
        var cropTop = 0f
        var cropWidth = orientedWidth.toFloat()
        var cropHeight = orientedHeight.toFloat()
        if (abs(orientedAspect - targetAspect) > aspectTolerance) {
            if (orientedAspect > targetAspect) {
                cropWidth = orientedHeight * targetAspect
                cropLeft = (orientedWidth - cropWidth) / 2f
            } else {
                cropHeight = orientedWidth / targetAspect
                cropTop = (orientedHeight - cropHeight) / 2f
            }
        }
        return Crop(cropLeft, cropTop, cropWidth, cropHeight)
    }

    private data class Crop(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    )

    companion object {
        const val DEFAULT_OUTPUT_WIDTH = 720
        const val DEFAULT_OUTPUT_HEIGHT = 1280
        const val DEFAULT_SAMPLE_WIDTH = 240
        const val DEFAULT_ASPECT_TOLERANCE = 0.02f
    }
}
