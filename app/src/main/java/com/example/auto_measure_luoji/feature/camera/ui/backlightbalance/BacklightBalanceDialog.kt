package com.example.auto_measure_luoji.feature.camera.ui.backlightbalance

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
import com.example.auto_measure_luoji.feature.camera.domain.backlightbalance.BacklightBalanceSettings
import com.example.auto_measure_luoji.feature.camera.domain.backlightbalance.BacklightParameter

@Composable
fun BacklightBalanceDialog(
    settings: BacklightBalanceSettings,
    enabled: Boolean,
    canEnable: Boolean,
    status: String,
    onSave: (BacklightBalanceSettings, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var fields by remember(settings) { mutableStateOf(settings.fields()) }
    var draftEnabled by remember { mutableStateOf(enabled) }
    var showErrors by remember { mutableStateOf(false) }
    val parsed = BacklightBalanceSettings.parse(fields)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("逆光平衡") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("逆光平衡", Modifier.weight(1f))
                    Switch(checked = draftEnabled, onCheckedChange = { draftEnabled = it },
                        enabled = canEnable || draftEnabled,
                        modifier = Modifier.semantics { contentDescription = "逆光平衡开关" })
                }
                Text(status)
                TextButton(onClick = { fields = BacklightBalanceSettings().fields(); showErrors = false }) {
                    Text("恢复默认参数")
                }
                BacklightParameter.entries.groupBy { it.group }.forEach { (group, parameters) ->
                    Text(group, style = MaterialTheme.typography.titleSmall)
                    parameters.forEach { parameter ->
                        val value = fields.getValue(parameter)
                        val valid = value.trim().toFloatOrNull()?.let(parameter::accepts) == true
                        OutlinedTextField(
                            value = value,
                            onValueChange = { fields = fields + (parameter to it) },
                            label = { Text(parameter.label) },
                            supportingText = { Text(parameter.rangeLabel) },
                            isError = showErrors && !valid,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType =
                                if (parameter.integer) KeyboardType.Number else KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (showErrors && parsed == null) {
                    Text("参数超出范围，或中心 P20 未满足：恢复 ≤ 保护 < 解除",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = !draftEnabled || canEnable, onClick = {
                    showErrors = true
                    if (parsed != null) onSave(parsed, draftEnabled)
                }) { Text("保存并应用") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
