package com.example.auto_measure.feature.common.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// TAG 颜色映射
private val TAG_COLORS = mapOf(
    "CameraScreen" to Color(0xFF4FC3F7),  // 浅蓝
    "CalMgr"       to Color(0xFF81C784),  // 浅绿
    "CalPanel"     to Color(0xFFAED581),  // 黄绿
    "BleConn"      to Color(0xFFFFB74D),  // 橙色
    "YoloDetector" to Color(0xFFCE93D8),  // 紫色
    "Camera2Ctrl"  to Color(0xFF90CAF9),  // 淡蓝
)

// 关键词高亮颜色
private val HIGHLIGHT_KEYWORDS = mapOf(
    "detect:"       to Color(0xFFFF5722),
    "det="          to Color(0xFFFF5722),
    "obj"           to Color(0xFFFF5722),
    "total="        to Color(0xFFFF9800),
    "inf="          to Color(0xFFFF9800),
    "pre="          to Color(0xFFFF9800),
    "测距"          to Color(0xFF4CAF50),
    "distance"      to Color(0xFF4CAF50),
    "Distance"      to Color(0xFF4CAF50),
    "AT+Measure"    to Color(0xFF4CAF50),
    "AT+Stop"       to Color(0xFFFF5722),
    "连接"          to Color(0xFF2196F3),
    "断开"          to Color(0xFFFF5722),
    "扫描"          to Color(0xFFFFC107),
    "error"         to Color(0xFFFF5252),
    "Error"         to Color(0xFFFF5252),
    "异常"          to Color(0xFFFF5252),
    "失败"          to Color(0xFFFF5252),
    "成功"          to Color(0xFF4CAF50),
    "检测成功"      to Color(0xFF4CAF50),
    "模型加载"      to Color(0xFFCE93D8),
    "Backend="      to Color(0xFFCE93D8),
    "GPU"           to Color(0xFFCE93D8),
    "laser"         to Color(0xFF76FF03),
    "Laser"         to Color(0xFF76FF03),
    "绿光"          to Color(0xFF76FF03),
)

@Composable
fun LogViewerOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    var rawLogs by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        rawLogs = withContext(Dispatchers.IO) { readLogs() }
        isLoading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("日志", color = Color.White, fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        clearLogs()
                        rawLogs = ""
                    }) {
                        Text("清空", color = Color(0xFFFF9800), fontSize = 14.sp)
                    }
                    TextButton(onClick = { copyToClipboard(context, rawLogs) }) {
                        Text("全选复制", color = Color(0xFF4CAF50), fontSize = 14.sp)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 日志内容
            if (isLoading) {
                Text("加载中...", color = Color.Gray, fontSize = 13.sp)
            } else {
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF121212), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = formatLogs(rawLogs),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LogButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .background(Color.White.copy(alpha = 0.15f), CircleShape)
            .padding(1.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
            Text("Log", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
        }
    }
}

/**
 * 解析日志行格式（threadtime）：
 * 06-11 10:09:58.134  1234  5678 D CameraScreen: message...
 */
private data class LogEntry(
    val timestamp: String,
    val tag: String,
    val level: String,
    val message: String,
)

private fun parseLogLine(line: String): LogEntry? {
    val trimmed = line.trim()
    if (trimmed.length < 30) return null

    // threadtime 格式: "MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG: message"
    val regex = Regex("""(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+\d+\s+\d+\s+([VDIWEF])\s+(\w+):\s*(.*)""")
    val match = regex.matchEntire(trimmed) ?: return null

    val timestamp = match.groupValues[1]
    val level = match.groupValues[2]
    val tag = match.groupValues[3]
    val message = match.groupValues[4]

    return LogEntry(timestamp, tag, level, message)
}

/**
 * 将原始日志格式化为带颜色的 AnnotatedString
 */
private fun formatLogs(raw: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = raw.lines()
        for (line in lines) {
            val entry = parseLogLine(line)
            if (entry != null) {
                // 时间戳 - 灰色
                withStyle(SpanStyle(color = Color(0xFF666666), fontSize = 10.sp)) {
                    append(entry.timestamp)
                }
                append("  ")

                // TAG - 对应颜色
                val tagColor = TAG_COLORS[entry.tag] ?: Color(0xFF999999)
                withStyle(SpanStyle(color = tagColor, fontWeight = FontWeight.Bold)) {
                    append(entry.tag)
                }
                append("  ")

                // 消息内容 - 根据关键词高亮
                appendHighlightedMessage(entry.message)
                append("\n")
            } else if (line.trim().isNotEmpty()) {
                // 无法解析的行，灰色显示
                withStyle(SpanStyle(color = Color(0xFF666666))) {
                    append(line)
                }
                append("\n")
            }
        }
    }
}

/**
 * 对消息内容进行关键词高亮
 */
private fun AnnotatedString.Builder.appendHighlightedMessage(message: String) {
    // 找到所有匹配的关键词及其位置
    data class Highlight(val start: Int, val end: Int, val color: Color)

    val highlights = mutableListOf<Highlight>()
    for ((keyword, color) in HIGHLIGHT_KEYWORDS) {
        var idx = message.indexOf(keyword, ignoreCase = false)
        while (idx >= 0) {
            highlights.add(Highlight(idx, idx + keyword.length, color))
            idx = message.indexOf(keyword, idx + keyword.length)
        }
    }

    // 按位置排序，合并重叠
    highlights.sortBy { it.start }

    if (highlights.isEmpty()) {
        // 没有关键词，检查是否是错误日志
        val isError = message.contains("E ", ignoreCase = false) ||
                message.contains("Error", ignoreCase = true) ||
                message.contains("异常") ||
                message.contains("失败")
        val color = if (isError) Color(0xFFFF5252) else Color(0xFFBBBBBB)
        withStyle(SpanStyle(color = color)) {
            append(message)
        }
        return
    }

    // 按高亮区间分段输出
    var lastEnd = 0
    for (h in highlights) {
        if (h.start > lastEnd) {
            // 高亮前的普通文本
            withStyle(SpanStyle(color = Color(0xFFBBBBBB))) {
                append(message.substring(lastEnd, h.start))
            }
        }
        // 高亮文本 - 加粗
        withStyle(SpanStyle(color = h.color, fontWeight = FontWeight.Bold)) {
            append(message.substring(h.start, h.end))
        }
        lastEnd = h.end
    }
    // 剩余普通文本
    if (lastEnd < message.length) {
        withStyle(SpanStyle(color = Color(0xFFBBBBBB))) {
            append(message.substring(lastEnd))
        }
    }
}

private fun readLogs(): String {
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-s",
            "CameraScreen:D", "CalMgr:D", "CalPanel:D", "BleConn:D",
            "YoloDetector:D", "Camera2Ctrl:D",
            "CameraScreen:I", "Camera2Ctrl:I", "Camera2Ctrl:W",
            "CameraScreen:E", "YoloDetector:E"))
        val output = process.inputStream.bufferedReader().readText()
        process.destroy()
        val lines = output.lines()
        val start = maxOf(0, lines.size - 10000)
        lines.subList(start, lines.size).joinToString("\n")
    } catch (e: Exception) {
        "读取日志失败: ${e.message}"
    }
}

private fun clearLogs() {
    try {
        Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
    } catch (_: Exception) {}
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("logs", text))
}
