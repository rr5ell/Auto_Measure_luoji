package com.example.auto_measure_luoji.feature.detection.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.sp

@Composable
fun DetectionOverlay(
    boxes: List<Pair<RectF, String>>,
    modifier: Modifier = Modifier,
) {
    if (boxes.isEmpty()) return

    Canvas(modifier = modifier) {
        val boxPaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
            color = android.graphics.Color.RED
        }
        val bgPaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.FILL
            color = android.graphics.Color.argb(160, 255, 0, 0)
        }
        val textPaint = android.graphics.Paint().apply {
            textSize = 32f
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        for ((r, label) in boxes) {
            drawRect(
                color = Color.Red,
                topLeft = Offset(r.left, r.top),
                size = androidx.compose.ui.geometry.Size(r.width(), r.height()),
                style = Stroke(width = 4f),
            )

            val textW = textPaint.measureText(label)
            val textH = 36f
            val textTop = (r.top - textH - 4f).coerceAtLeast(0f)
            drawContext.canvas.nativeCanvas.drawRect(
                r.left, textTop, r.left + textW + 8f, textTop + textH + 4f, bgPaint
            )
            drawContext.canvas.nativeCanvas.drawText(
                label, r.left + 4f, textTop + textH - 4f, textPaint
            )
        }
    }
}
