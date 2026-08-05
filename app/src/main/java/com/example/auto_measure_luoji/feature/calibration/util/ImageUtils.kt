package com.example.auto_measure_luoji.feature.calibration.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageUtils {
    const val OUT_W = 1080
    const val OUT_H = 1920
    const val TARGET_ASPECT = 9.0 / 16.0

    fun decodeBitmap(file: File): Bitmap {
        return BitmapFactory.decodeFile(file.absolutePath)
            ?: error("图片解码失败: ${file.absolutePath}")
    }

    fun fixOrientation(file: File, src: Bitmap): Bitmap {
        val exif = ExifInterface(file)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    fun cropToAspect(src: Bitmap, aspectWOverH: Double): Bitmap {
        val srcAspect = src.width.toDouble() / src.height.toDouble()
        return when {
            srcAspect > aspectWOverH -> {
                val newW = (src.height * aspectWOverH).roundToInt()
                val x = ((src.width - newW) / 2f).roundToInt().coerceAtLeast(0)
                Bitmap.createBitmap(src, x, 0, newW.coerceAtMost(src.width - x), src.height)
            }
            srcAspect < aspectWOverH -> {
                val newH = (src.width / aspectWOverH).roundToInt()
                val y = ((src.height - newH) / 2f).roundToInt().coerceAtLeast(0)
                Bitmap.createBitmap(src, 0, y, src.width, newH.coerceAtMost(src.height - y))
            }
            else -> src
        }
    }

    fun resize(src: Bitmap, width: Int = OUT_W, height: Int = OUT_H): Bitmap {
        return Bitmap.createScaledBitmap(src, width, height, true)
    }

    fun loadAndNormalize(file: File): Bitmap {
        val decoded = decodeBitmap(file)
        val oriented = fixOrientation(file, decoded)
        val cropped = cropToAspect(oriented, TARGET_ASPECT)
        return resize(cropped, OUT_W, OUT_H)
    }

    fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    fun drawMarkOnBitmap(
        baseBitmap: Bitmap,
        centerX: Double,
        centerY: Double,
        pixelRatio: Float = 1f,
        labelPrefix: String? = null,
        markColor: Int = Color.RED,
    ): ByteArray {
        val mutable = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)

        val w = mutable.width.toFloat()
        val h = mutable.height.toFloat()

        val baseScale = max(1f, min(w, h) / 900f)
        val s = baseScale * if (pixelRatio <= 0f) 1f else pixelRatio

        val centerXF = centerX.toFloat()
        val centerYF = centerY.toFloat()

        val redStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = markColor
            style = Paint.Style.STROKE
            strokeWidth = 3f * s
        }
        canvas.drawCircle(centerXF, centerYF, 10f * s, redStroke)

        redStroke.strokeWidth = 2f * s
        canvas.drawLine(centerXF - 18f * s, centerYF, centerXF + 18f * s, centerYF, redStroke)
        canvas.drawLine(centerXF, centerYF - 18f * s, centerXF, centerYF + 18f * s, redStroke)

        val pointLabel = "(${centerX.roundToInt()},${centerY.roundToInt()})"
        val label = if (labelPrefix.isNullOrBlank()) pointLabel else "$labelPrefix $pointLabel"
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = markColor
            textSize = max(14f * s, 14f)
            isFakeBoldText = true
        }

        val textWidth = textPaint.measureText(label)
        val metrics = textPaint.fontMetrics
        val textHeight = metrics.bottom - metrics.top

        var tx = centerXF + 12f * s
        var ty = centerYF - textHeight - 10f * s
        tx = tx.coerceIn(2f, w - textWidth - 2f)
        ty = ty.coerceIn(2f - metrics.top, h - textHeight - metrics.bottom - 2f)

        val bgRect = RectF(
            tx - 6f * s,
            ty + metrics.top - 4f * s,
            tx + textWidth + 6f * s,
            ty + metrics.bottom + 4f * s,
        )

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((0.35f * 255).roundToInt(), 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(bgRect, 6f * s, 6f * s, bgPaint)
        canvas.drawText(label, tx, ty, textPaint)

        return bitmapToPngBytes(mutable)
    }

    fun createTempJpegFile(context: Context): File {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        return File(dir, "cap_${System.currentTimeMillis()}.jpg")
    }
}
