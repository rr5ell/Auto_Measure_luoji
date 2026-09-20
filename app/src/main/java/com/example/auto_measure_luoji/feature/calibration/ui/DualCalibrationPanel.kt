package com.example.auto_measure_luoji.feature.calibration.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.auto_measure_luoji.feature.bluetooth.domain.BleConnectionManager
import com.example.auto_measure_luoji.feature.calibration.domain.DualCalibrationManager
import kotlinx.coroutines.delay

@Composable
fun DualCalibrationPanel(
    manager: DualCalibrationManager,
    bleManager: BleConnectionManager,
    telephotoMag: Int,
    isProcessing: Boolean,
    lastResultText: String,
    onInputDistance: (Double) -> Unit,
    onCollect: () -> Unit,
    onApplyTarget: (Double) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var distanceInput by remember { mutableStateOf("") }
    var targetInput by remember { mutableStateOf("100") }
    var distanceError by remember { mutableStateOf<String?>(null) }
    var targetError by remember { mutableStateOf<String?>(null) }
    var isMeasuringDistance by remember { mutableStateOf(false) }
    var ignoredDistance by remember { mutableStateOf(bleManager.lastDistance) }
    val isBleConnected = bleManager.state == BleConnectionManager.State.CONNECTED
    val lastDistance = bleManager.lastDistance

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
            isMeasuringDistance = false
            bleManager.stopMeasure()
            onInputDistance(data.line)
            distanceInput = ""
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
            .background(Color(0xCC1A1A1A))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "双镜头校准",
                color = Color(0xFFE0E0E0),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onClose, enabled = !isProcessing, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text("关闭", color = Color(0xFF9E9E9E), fontSize = 13.sp)
            }
        }

        Text(
            text = manager.getStatusText(telephotoMag),
            color = Color(0xFF4CAF50),
            fontSize = 12.sp,
        )
        Text(
            text = manager.getSampleText(),
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp,
        )

        if (manager.state == DualCalibrationManager.State.WAIT_DISTANCE ||
            manager.state == DualCalibrationManager.State.IDLE
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
                    label = { Text("距离 d (m)", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = Color(0xFFE0E0E0)),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE0E0E0),
                        unfocusedTextColor = Color(0xFFE0E0E0),
                        focusedBorderColor = Color(0xFF4CAF50),
                        unfocusedBorderColor = Color(0xFF9E9E9E).copy(alpha = 0.4f),
                        focusedLabelColor = Color(0xFF4CAF50),
                        unfocusedLabelColor = Color(0xFF9E9E9E),
                        cursorColor = Color(0xFF4CAF50),
                    ),
                )
                Button(
                    onClick = {
                        val distance = distanceInput.toDoubleOrNull()
                        if (distance != null && distance > 0.0) {
                            onInputDistance(distance)
                            distanceInput = ""
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
                    enabled = !isProcessing && !isMeasuringDistance,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = if (isMeasuringDistance) "测距中..." else manager.getActionText(telephotoMag),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (distanceError != null) {
                    Text(distanceError!!, color = Color(0xFFFF9800), fontSize = 11.sp)
                }
            }
        }

        if (manager.state == DualCalibrationManager.State.WAIT_WIDE ||
            manager.state == DualCalibrationManager.State.WAIT_TELE
        ) {
            Button(
                onClick = onCollect,
                enabled = !isProcessing,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = if (isProcessing) "检测中..." else manager.getActionText(telephotoMag),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        if (manager.state == DualCalibrationManager.State.CALIBRATED) {
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
                    label = { Text("目标距离 D (m)", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = Color(0xFFE0E0E0)),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE0E0E0),
                        unfocusedTextColor = Color(0xFFE0E0E0),
                        focusedBorderColor = Color(0xFF2196F3),
                        unfocusedBorderColor = Color(0xFF9E9E9E).copy(alpha = 0.4f),
                        focusedLabelColor = Color(0xFF2196F3),
                        unfocusedLabelColor = Color(0xFF9E9E9E),
                        cursorColor = Color(0xFF2196F3),
                    ),
                )
                Button(
                    onClick = {
                        val distance = targetInput.toDoubleOrNull()
                        if (distance != null && distance > 0.0) {
                            onApplyTarget(distance)
                        } else {
                            targetError = "请输入大于 0 的目标距离"
                        }
                    },
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
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

        if (lastResultText.isNotEmpty()) {
            Text(
                text = lastResultText,
                color = Color(0xFF9E9E9E),
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }
    }
}
