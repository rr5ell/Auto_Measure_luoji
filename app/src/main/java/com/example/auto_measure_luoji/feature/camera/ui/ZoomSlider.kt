package com.example.auto_measure_luoji.feature.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 放大滑块 UI
 *
 * @param currentRatio    当前等效倍率
 * @param minRatio        最小倍率（光学底倍）
 * @param maxRatio        最大倍率
 * @param onRatioChanged  倍率变化回调
 */
@Composable
fun ZoomSlider(
    currentRatio: Float,
    minRatio: Float,
    maxRatio: Float,
    labelSuffix: String = "",
    onRatioChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeMinRatio = minRatio.coerceAtLeast(1f)
    val safeMaxRatio = maxRatio.coerceAtLeast(safeMinRatio)
    val sliderValue = currentRatio.coerceIn(safeMinRatio, safeMaxRatio)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 倍率显示
        Text(
            text = "%.1fx%s".format(sliderValue, labelSuffix),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 滑块
        Slider(
            value = sliderValue,
            onValueChange = { onRatioChanged(it.coerceIn(safeMinRatio, safeMaxRatio)) },
            valueRange = safeMinRatio..safeMaxRatio,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color(0xFF4CAF50),
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )

        // 范围标签
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "%.1fx".format(safeMinRatio),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
            Text(
                text = "%.1fx".format(safeMaxRatio),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }
    }
}
