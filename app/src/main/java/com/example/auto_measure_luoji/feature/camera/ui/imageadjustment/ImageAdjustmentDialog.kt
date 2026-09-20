package com.example.auto_measure_luoji.feature.camera.ui.imageadjustment

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.auto_measure_luoji.feature.camera.domain.imageadjustment.ExposureCompensationRange
import com.example.auto_measure_luoji.feature.camera.domain.imageadjustment.ImageAdjustments
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ImageAdjustmentDialog(
    settings: ImageAdjustments,
    exposureRange: ExposureCompensationRange,
    enabled: Boolean,
    status: String?,
    onChange: (ImageAdjustments) -> Unit,
    onChangeFinished: () -> Unit,
    onDismiss: () -> Unit,
) {
    val safe = settings.sanitized()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("EV / 对比度") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                status?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                Text(String.format(Locale.US, "EV %+.2f", exposureRange.evFor(safe.ev)))
                Slider(
                    value = exposureRange.indexFor(safe.ev).toFloat(),
                    onValueChange = { onChange(safe.copy(ev = it.roundToInt() * exposureRange.stepEv)) },
                    onValueChangeFinished = onChangeFinished,
                    valueRange = if (exposureRange.supported) exposureRange.minimum.toFloat()..exposureRange.maximum.toFloat() else -1f..1f,
                    steps = if (exposureRange.supported) (exposureRange.maximum.toLong() - exposureRange.minimum - 1).coerceIn(0, 1000).toInt() else 0,
                    enabled = enabled && exposureRange.supported,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "手动 EV" },
                )
                if (!exposureRange.supported) Text("当前镜头曝光补偿不可用")
                else Text(String.format(Locale.US, "%+.2f ~ %+.2f EV · 步进 %.2f",
                    exposureRange.minimum * exposureRange.stepEv, exposureRange.maximum * exposureRange.stepEv, exposureRange.stepEv))
                Text(String.format(Locale.US, "预览对比度 %.2f", safe.contrast))
                Slider(
                    value = safe.contrast,
                    onValueChange = { onChange(safe.copy(contrast = it)) },
                    onValueChangeFinished = onChangeFinished,
                    valueRange = 0.5f..2f,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "预览对比度" },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            TextButton(enabled = enabled, onClick = { onChange(ImageAdjustments()); onChangeFinished() }) { Text("恢复默认") }
        },
    )
}
