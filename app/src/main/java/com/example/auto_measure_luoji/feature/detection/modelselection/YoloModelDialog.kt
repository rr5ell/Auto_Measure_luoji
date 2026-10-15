package com.example.auto_measure_luoji.feature.detection.modelselection

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun YoloModelDialog(
    selected: YoloModelOption,
    loading: Boolean,
    failed: Boolean,
    status: String,
    onSelect: (YoloModelOption) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("识别模型") },
        text = {
            Column(Modifier.selectableGroup()) {
                YoloModelOption.values().forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected == option, enabled = !loading,
                                role = Role.RadioButton, onClick = { onSelect(option) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == option, onClick = null, enabled = !loading)
                        Spacer(Modifier.width(8.dp))
                        Text(option.label)
                    }
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            if (failed && !loading) TextButton(onClick = onRetry) { Text("重试") }
        },
    )
}
