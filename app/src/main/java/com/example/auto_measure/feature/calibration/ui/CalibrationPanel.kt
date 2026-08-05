package com.example.auto_measure.feature.calibration.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.auto_measure.feature.bluetooth.domain.BleConnectionManager
import com.example.auto_measure.feature.calibration.domain.CalibrationManager
import kotlinx.coroutines.delay

private val AccentGreen = Color(0xFF4CAF50)
private val AccentBlue = Color(0xFF2196F3)
private val SurfaceDark = Color(0xCC1A1A1A)
private val TextPrimary = Color(0xFFE0E0E0)
private val TextSecondary = Color(0xFF9E9E9E)

@Composable
fun CalibrationPanel(
    calManager: CalibrationManager,
    bleManager: BleConnectionManager,
    onCollect: (distanceMeters: Double, onResult: (Boolean) -> Unit) -> Unit,
    onApplyTarget: (targetDistance: Double) -> Unit,
    onClose: () -> Unit,
    isProcessing: Boolean,
    lastResultText: String,
    modifier: Modifier = Modifier,
) {
    var distanceInput by remember { mutableStateOf("") }
    var targetInput by remember { mutableStateOf("100") }
    var lastCollectedDistance by remember { mutableStateOf(0.0) }
    var distanceError by remember { mutableStateOf<String?>(null) }
    var targetError by remember { mutableStateOf<String?>(null) }
    var isMeasuringDistance by remember { mutableStateOf(false) }
    var ignoredDistance by remember { mutableStateOf(bleManager.lastDistance) }
    val isBleConnected = bleManager.state == BleConnectionManager.State.CONNECTED
    val lastDistance = bleManager.lastDistance

    LaunchedEffect(isBleConnected) {
        if (isBleConnected) bleManager.laserOn()
    }

    fun collectDistance(distanceMeters: Double) {
        onCollect(distanceMeters) { success ->
            if (success) {
                lastCollectedDistance = distanceMeters
                distanceInput = ""
            } else {
                distanceError = "绿光识别失败，请调整瞄准后重试"
            }
        }
    }

    LaunchedEffect(isMeasuringDistance, lastDistance) {
        val data = lastDistance
        if (!isMeasuringDistance || data == null || data === ignoredDistance) return@LaunchedEffect
        if (data.line > 0.0) {
            val formatted = if (data.line < 200.0) {
                "%.2f".format(data.line)
            } else {
                "%.0f".format(data.line)
            }
            distanceInput = formatted
            distanceError = null
            isMeasuringDistance = false
            bleManager.stopMeasure()
            collectDistance(data.line)
        }
    }

    LaunchedEffect(isMeasuringDistance) {
        if (!isMeasuringDistance) return@LaunchedEffect
        delay(4000)
        if (isMeasuringDistance) {
            isMeasuringDistance = false
            bleManager.stopMeasure()
            distanceError = "测距超时，请重试或手动输入距离"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isMeasuringDistance) {
                bleManager.stopMeasure()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceDark)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (calManager.state) {
                    CalibrationManager.State.SAMPLE_1 -> "校准 (1/2)"
                    CalibrationManager.State.SAMPLE_2 -> "校准 (2/2)"
                    CalibrationManager.State.CALIBRATED -> "校准完成"
                    else -> "校准"
                },
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(
                onClick = onClose,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("关闭", color = TextSecondary, fontSize = 13.sp)
            }
        }

        // 状态提示
        Text(
            text = calManager.getStatusText(),
            color = AccentGreen,
            fontSize = 12.sp,
        )

        // 采集阶段
        if (calManager.state == CalibrationManager.State.SAMPLE_1 ||
            calManager.state == CalibrationManager.State.SAMPLE_2
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = distanceInput,
                    onValueChange = {
                        distanceInput = it
                        distanceError = null
                    },
                    label = { Text("距离 (m)", fontSize = 11.sp) },
                    placeholder = { Text("请输入大于 0 的距离", fontSize = 12.sp, color = TextSecondary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentGreen,
                        unfocusedBorderColor = TextSecondary.copy(alpha = 0.4f),
                        focusedLabelColor = AccentGreen,
                        unfocusedLabelColor = TextSecondary,
                        cursorColor = AccentGreen,
                    ),
                )

                val isDuplicate = calManager.state == CalibrationManager.State.SAMPLE_2 &&
                    distanceInput.toDoubleOrNull()?.let { it == lastCollectedDistance } == true

                Button(
                    onClick = {
                        val d = distanceInput.toDoubleOrNull()
                        if (d != null && d > 0) {
                            if (isDuplicate) return@Button
                            collectDistance(d)
                        } else if (distanceInput.isBlank() && isBleConnected) {
                            ignoredDistance = bleManager.lastDistance
                            distanceError = null
                            isMeasuringDistance = true
                            bleManager.measure()
                        } else {
                            distanceError = if (isBleConnected) {
                                "请输入大于 0 的距离"
                            } else {
                                "请输入距离，或连接设备后自动测距"
                            }
                        }
                    },
                    enabled = !isProcessing && !isDuplicate && !isMeasuringDistance,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDuplicate) Color(0xFFFF9800) else AccentGreen,
                        disabledContainerColor = TextSecondary.copy(alpha = 0.2f),
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = when {
                            isProcessing -> "检测中..."
                            isMeasuringDistance -> "测距中..."
                            isDuplicate -> "换位置"
                            else -> "采集"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (distanceError != null) {
                    Text(distanceError!!, color = Color(0xFFFF9800), fontSize = 11.sp)
                }
            }
        }

        // 已标定 → 目标距离
        if (calManager.state == CalibrationManager.State.CALIBRATED) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = targetInput,
                    onValueChange = {
                        targetInput = it
                        targetError = null
                    },
                    label = { Text("目标距离 (m)", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = TextSecondary.copy(alpha = 0.4f),
                        focusedLabelColor = AccentBlue,
                        unfocusedLabelColor = TextSecondary,
                        cursorColor = AccentBlue,
                    ),
                )
                Button(
                    onClick = {
                        val d = targetInput.toDoubleOrNull()
                        if (d != null && d > 0) {
                            onApplyTarget(d)
                        } else {
                            targetError = "请输入大于 0 的目标距离"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text("应用", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                if (targetError != null) {
                    Text(targetError!!, color = Color(0xFFFF9800), fontSize = 11.sp)
                }
            }
        }

        // 结果提示
        if (lastResultText.isNotEmpty()) {
            Text(
                text = lastResultText,
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }
    }
}
