package com.example.auto_measure_luoji.feature.camera.ui.autohdr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.auto_measure_luoji.feature.camera.domain.autohdr.AutoHdrSettings

@Composable
fun AutoHdrDialog(
    settings: AutoHdrSettings,
    automatic: Boolean,
    hdrEnabled: Boolean,
    canChangeHdr: Boolean,
    status: String,
    onAutomaticChanged: (Boolean) -> Unit,
    onHdrChanged: (Boolean) -> Unit,
    onSave: (AutoHdrSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var lower by remember { mutableStateOf(settings.lowerMs.toString()) }
    var upper by remember { mutableStateOf(settings.upperMs.toString()) }
    var cooldown by remember { mutableStateOf(settings.cooldownSeconds.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自动 HDR") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("自动 HDR", Modifier.weight(1f))
                    Switch(checked = automatic, onCheckedChange = onAutomaticChanged,
                        modifier = Modifier.semantics { contentDescription = "自动 HDR 开关" })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("手动 HDR", Modifier.weight(1f))
                    Switch(checked = hdrEnabled, onCheckedChange = onHdrChanged,
                        enabled = !automatic && canChangeHdr,
                        modifier = Modifier.semantics { contentDescription = "HDR 预览" })
                }
                Text(status)
                OutlinedTextField(value = lower, onValueChange = { lower = it; error = null },
                    label = { Text("开启：曝光小于（ms）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = upper, onValueChange = { upper = it; error = null },
                    label = { Text("开启：曝光大于（ms）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = cooldown, onValueChange = { cooldown = it; error = null },
                    label = { Text("切换后停判（秒）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = AutoHdrSettings.parse(lower, upper, cooldown)
                if (parsed == null) error = "曝光阈值须为正数且下限小于上限；停判须为 1~3600 秒整数"
                else onSave(parsed)
            }) { Text("保存参数") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "关闭自动 HDR 面板" }) { Text("关闭") }
        },
    )
}
