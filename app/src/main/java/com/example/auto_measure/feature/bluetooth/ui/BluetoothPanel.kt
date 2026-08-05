package com.example.auto_measure.feature.bluetooth.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.auto_measure.feature.bluetooth.data.BleDevice
import com.example.auto_measure.feature.bluetooth.domain.BleConnectionManager

private val AccentGreen = Color(0xFF4CAF50)
private val AccentBlue = Color(0xFF2196F3)
private val AccentRed = Color(0xFFFF5722)
private val SurfaceDark = Color(0xCC1A1A1A)
private val TextPrimary = Color(0xFFE0E0E0)
private val TextSecondary = Color(0xFF9E9E9E)

@Composable
fun BluetoothPanel(
    manager: BleConnectionManager,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val state = manager.state
    val connectedDevice = manager.connectedDevice
    var scanResults by remember { mutableStateOf(listOf<BleDevice>()) }

    LaunchedEffect(Unit) {
        manager.onDeviceFound = { device ->
            scanResults = scanResults + device
        }
    }

    // 展开时自动扫描
    LaunchedEffect(expanded) {
        if (expanded && state == BleConnectionManager.State.IDLE && connectedDevice == null) {
            scanResults = emptyList()
            manager.startScan()
        }
    }

    Column(modifier = modifier.widthIn(min = 132.dp, max = 190.dp)) {
        // 收起状态：一行胶囊按钮
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceDark)
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 状态指示灯
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when (state) {
                            BleConnectionManager.State.CONNECTED -> AccentGreen
                            BleConnectionManager.State.SCANNING, BleConnectionManager.State.CONNECTING -> Color(0xFFFFC107)
                            else -> TextSecondary
                        }
                    )
            )
            Text(
                text = when {
                    state == BleConnectionManager.State.CONNECTED && connectedDevice != null -> connectedDevice!!.name
                    state == BleConnectionManager.State.SCANNING -> "扫描中..."
                    state == BleConnectionManager.State.CONNECTING -> "连接中..."
                    else -> "蓝牙"
                },
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (expanded) "▲" else "▼",
                color = TextSecondary,
                fontSize = 10.sp,
            )
        }

        // 展开状态
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .padding(12.dp),
            ) {
                if (state == BleConnectionManager.State.CONNECTED && connectedDevice != null) {
                    // 已连接
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(connectedDevice!!.name, color = AccentGreen, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(connectedDevice!!.address, color = TextSecondary, fontSize = 11.sp)
                        }
                        Text(
                            text = "断开",
                            color = AccentRed,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { manager.disconnect(); expanded = false }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                } else {
                    // 未连接
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (state == BleConnectionManager.State.SCANNING) "正在扫描..." else "附近设备",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (state == BleConnectionManager.State.SCANNING) {
                                Text(
                                    text = "停止",
                                    color = AccentRed,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { manager.stopScan() }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            } else {
                                Text(
                                    text = "刷新",
                                    color = AccentBlue,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { scanResults = emptyList(); manager.startScan() }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (scanResults.isEmpty()) {
                        Text(
                            text = if (state == BleConnectionManager.State.SCANNING) "搜索中..." else "未发现设备",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
                            items(scanResults) { device ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { manager.connect(device) }
                                        .padding(horizontal = 8.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(device.name, color = TextPrimary, fontSize = 13.sp)
                                        Text(device.address, color = TextSecondary, fontSize = 10.sp)
                                    }
                                    Text("${device.rssi}", color = TextSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
