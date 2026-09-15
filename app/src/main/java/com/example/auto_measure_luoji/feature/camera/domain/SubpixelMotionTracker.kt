package com.example.auto_measure_luoji.feature.camera.domain

import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.hypot

/** 亚像素 LK 双向跟踪；只有跨区域一致的背景运动才作为有效测量。 */
internal object SubpixelMotionTracker {
    val available: Boolean by lazy {
        try {
            OpenCVLoader.initLocal()
        } catch (_: LinkageError) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    fun estimate(
        previous: PreviewMotionEstimator.LumaFrame,
        current: PreviewMotionEstimator.LumaFrame,
        timestampNs: Long,
    ): PreviewMotionEstimator.Motion {
        fun invalid(reason: String) = PreviewMotionEstimator.Motion(timestampNs = timestampNs, reason = reason)
        if (!available) return invalid("opencv_unavailable")
        if (previous.sampleWidth != current.sampleWidth || previous.sampleHeight != current.sampleHeight ||
            previous.sourceWidth != current.sourceWidth || previous.sourceHeight != current.sourceHeight ||
            current.sampleWidth < 48 || current.sampleHeight < 48 ||
            previous.luma.size != current.luma.size ||
            current.luma.size != current.sampleWidth * current.sampleHeight ||
            !current.scaleX.isFinite() || !current.scaleY.isFinite() ||
            current.scaleX <= 0f || current.scaleY <= 0f ||
            previous.scaleX != current.scaleX || previous.scaleY != current.scaleY
        ) return invalid("geometry_changed")

        val owned = mutableListOf<Mat>()
        fun <T : Mat> keep(mat: T): T = mat.also { owned.add(it) }
        try {
            val a = keep(Mat(current.sampleHeight, current.sampleWidth, CvType.CV_8UC1))
            val b = keep(Mat(current.sampleHeight, current.sampleWidth, CvType.CV_8UC1))
            a.put(0, 0, ByteArray(previous.luma.size) { previous.luma[it].toByte() })
            b.put(0, 0, ByteArray(current.luma.size) { current.luma[it].toByte() })
            val mask = keep(Mat.zeros(a.size(), CvType.CV_8UC1))
            val interior = keep(mask.submat(12, a.rows() - 12, 12, a.cols() - 12))
            interior.setTo(org.opencv.core.Scalar(255.0))
            val corners = keep(MatOfPoint())
            Imgproc.goodFeaturesToTrack(a, corners, 180, 0.015, 8.0, mask)
            val initial = corners.toArray()
            if (initial.size < 12) return invalid("insufficient_texture")
            val p0 = keep(MatOfPoint2f(*initial))
            val p1 = keep(MatOfPoint2f())
            val back = keep(MatOfPoint2f())
            val status = keep(MatOfByte())
            val backStatus = keep(MatOfByte())
            val error = keep(MatOfFloat())
            val backError = keep(MatOfFloat())
            val criteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 30, 0.01)
            Video.calcOpticalFlowPyrLK(a, b, p0, p1, status, error, Size(21.0, 21.0), 3, criteria)
            Video.calcOpticalFlowPyrLK(b, a, p1, back, backStatus, backError, Size(21.0, 21.0), 3, criteria)
            val next = p1.toArray()
            val returned = back.toArray()
            val ok = status.toArray()
            val backOk = backStatus.toArray()
            val errors = error.toArray()
            val from = mutableListOf<Point>()
            val to = mutableListOf<Point>()
            for (i in initial.indices) {
                val q = next[i]
                if (ok[i].toInt() == 0 || backOk[i].toInt() == 0 || !errors[i].isFinite() || errors[i] > 25f ||
                    !q.x.isFinite() || !q.y.isFinite() ||
                    !returned[i].x.isFinite() || !returned[i].y.isFinite() ||
                    q.x < 8 || q.y < 8 || q.x >= a.cols() - 8 || q.y >= a.rows() - 8 ||
                    hypot(initial[i].x - returned[i].x, initial[i].y - returned[i].y) > 0.7
                ) continue
                from.add(initial[i])
                to.add(q)
            }
            if (from.size < 12) return invalid("tracking_failed")
            val inliers = keep(Mat())
            val affine = keep(Calib3d.estimateAffinePartial2D(
                keep(MatOfPoint2f(*from.toTypedArray())),
                keep(MatOfPoint2f(*to.toTypedArray())),
                inliers, Calib3d.RANSAC, 1.25, 1000, 0.99, 10,
            ))
            if (affine.empty()) return invalid("model_failed")
            val flags = ByteArray(from.size)
            inliers.get(0, 0, flags)
            val regions = mutableSetOf<Int>()
            var count = 0
            for (i in from.indices) {
                if (flags[i].toInt() == 0) continue
                count++
                val gx = (from[i].x * 3 / a.cols()).toInt().coerceIn(0, 2)
                val gy = (from[i].y * 3 / a.rows()).toInt().coerceIn(0, 2)
                regions.add(gy * 3 + gx)
            }
            // A 将上一帧采样坐标映射到本帧。中心位移为 A*c-c，
            // 再除以 sample/source 比例，还原到诊断图像像素，不能取整。
            // 双向误差和 RANSAC 阈值均使用采样像素；输出及静止阈值使用 source 像素。
            val values = DoubleArray(6)
            affine.get(0, 0, values)
            val cx = (a.cols() - 1) * 0.5
            val cy = (a.rows() - 1) * 0.5
            val dx = ((values[0] - 1) * cx + values[1] * cy + values[2]) / current.scaleX
            val dy = (values[3] * cx + (values[4] - 1) * cy + values[5]) / current.scaleY
            val ratio = count.toFloat() / from.size
            val scale = hypot(values[0], values[3])
            val valid = count >= 12 && ratio >= 0.60f && regions.size >= 4 &&
                dx.isFinite() && dy.isFinite() && abs(scale - 1) < 0.06
            return PreviewMotionEstimator.Motion(
                timestampNs = timestampNs, valid = valid,
                rawDx = dx.toFloat(), rawDy = dy.toFloat(), score = ratio,
                trackedPoints = from.size, inlierPoints = count, coveredRegions = regions.size,
                reason = if (valid) "ok" else "inconsistent_motion",
            )
        } catch (_: RuntimeException) {
            return invalid("opencv_error")
        } finally {
            owned.asReversed().forEach { it.release() }
        }
    }
}
