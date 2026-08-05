package com.example.auto_measure_luoji.feature.bluetooth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.auto_measure_luoji.feature.bluetooth.domain.BleConnectionManager
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.delay

private val AccentGreen = Color(0xFF4CAF50)
private val AccentRed = Color(0xFFFF5722)
private val SurfaceDark = Color(0xCC1A1A1A)
private val TextPrimary = Color(0xFFE0E0E0)
private val TextSecondary = Color(0xFF9E9E9E)

@Composable
fun DeviceControls(
    manager: BleConnectionManager,
    modifier: Modifier = Modifier,
) {
    var isMeasuring by remember { mutableStateOf(false) }
    var laserOn by remember { mutableStateOf(false) }
    var measureStartTime by remember { mutableLongStateOf(0L) }
    val lastDistance by remember { derivedStateOf { manager.lastDistance } }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 距离显示
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (lastDistance != null) {
                val d = lastDistance!!
                val text = if (d.line < 200) String.format("%.2f", d.line) else String.format("%.0f", d.line)
                val distColor = if (d.flag == 1) AccentGreen else TextSecondary
                Text(text = text, color = distColor, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(text = "m", color = TextSecondary, fontSize = 14.sp)
            } else {
                Text(text = "--", color = TextSecondary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(text = "m", color = TextSecondary, fontSize = 14.sp)
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // 测距按钮
        Box(
            modifier = Modifier
                .size(width = 80.dp, height = 80.dp)
                .background(
                    if (isMeasuring) AccentRed else AccentGreen,
                    RoundedCornerShape(16.dp),
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isMeasuring = true
                            measureStartTime = System.currentTimeMillis()
                            manager.measure()
                            tryAwaitRelease()
                            val elapsed = System.currentTimeMillis() - measureStartTime
                            if (elapsed < 500) delay(500 - elapsed)
                            manager.stopMeasure()
                            isMeasuring = false
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isMeasuring) "测量中" else "测距",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 绿光按钮
        Box(
            modifier = Modifier
                .size(width = 80.dp, height = 80.dp)
                .background(
                    if (laserOn) AccentGreen.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.08f),
                    RoundedCornerShape(16.dp),
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (laserOn) { manager.laserOff(); laserOn = false }
                            else { manager.laserOn(); laserOn = true }
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (laserOn) "ON" else "绿光",
                color = if (laserOn) AccentGreen else TextSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
