package com.example.auto_measure_luoji.feature.camera.ui.statuspanel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 只折叠显示内容，不拥有相机/采样协程；上游数据持续更新，展开时读取最新值。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollapsibleStatusPanel(
    fpsText: String,
    statusText: String,
    modifier: Modifier = Modifier,
    modelNameText: String = "",
    details: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val action = if (expanded) "收起状态详情" else "展开状态详情"
    val detailHeight = minOf(240.dp, (LocalConfiguration.current.screenHeightDp * 0.35f).dp)
    Column(
        modifier = modifier.clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(fpsText, color = Color(0xFFA5D6A7), fontSize = 11.sp, lineHeight = 13.sp,
                fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(action) } }, state = rememberTooltipState()) {
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(48.dp).semantics {
                    stateDescription = if (expanded) "已展开" else "已收起"
                }) {
                    Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = action, tint = Color.White)
                }
            }
        }
        if (modelNameText.isNotBlank()) Text(modelNameText, color = Color(0xFFFFCC80),
            fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = "当前识别模型" })
        if (statusText.isNotBlank()) Text(statusText, color = Color.White.copy(alpha = 0.9f),
            fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (expanded) {
            Column(Modifier.fillMaxWidth().heightIn(max = detailHeight).verticalScroll(scroll)
                .padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp), content = details)
        }
    }
}
