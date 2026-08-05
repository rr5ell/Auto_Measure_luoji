package com.example.auto_measure.feature.detection.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * 屏幕中心识别区域
 *
 * @param areaWidthPx  区域宽度（px）
 * @param areaHeightPx 区域高度（px）
 * @param isTargetInside 是否有检测框中心在区域内（控制颜色）
 */
@Composable
fun RecognitionArea(
    areaWidthPx: Float = 300f,
    areaHeightPx: Float = 450f,
    isTargetInside: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val color = if (isTargetInside) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.5f)
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 10f), 0f)

    Canvas(modifier = modifier.fillMaxSize()) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val w = areaWidthPx
        val h = areaHeightPx
        val left = centerX - w / 2f
        val top = centerY - h / 2f

        drawRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(w, h),
            style = Stroke(width = 3f, pathEffect = dashEffect),
        )
    }
}

/**
 * 判断检测框中心是否在识别区域内
 *
 * @param detectionBox 检测框（已映射到预览视图坐标）
 * @param areaCenterX  区域中心 X
 * @param areaCenterY  区域中心 Y
 * @param areaHalfW    区域半宽
 * @param areaHalfH    区域半高
 */
fun isBoxCenterInArea(
    detectionBox: RectF,
    areaCenterX: Float,
    areaCenterY: Float,
    areaHalfW: Float,
    areaHalfH: Float,
): Boolean {
    val boxCenterX = (detectionBox.left + detectionBox.right) / 2f
    val boxCenterY = (detectionBox.top + detectionBox.bottom) / 2f
    return boxCenterX in (areaCenterX - areaHalfW)..(areaCenterX + areaHalfW) &&
            boxCenterY in (areaCenterY - areaHalfH)..(areaCenterY + areaHalfH)
}
