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
import com.example.auto_measure_luoji.feature.camera.domain.manualsensor.ManualExposure
import com.example.auto_measure_luoji.feature.camera.domain.manualsensor.ManualSensorCapabilities
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
    manual: ManualExposure? = null,
    sensorCapabilities: ManualSensorCapabilities = ManualSensorCapabilities(),
    actualSensorText: String = "",
    onManualEnabledChange: (Boolean) -> Unit = {},
    onManualChange: (ManualExposure) -> Unit = {},
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
                    enabled = enabled && exposureRange.supported && manual == null,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "手动 EV" },
                )
                if (!exposureRange.supported) Text("当前镜头曝光补偿不可用")
                else Text(String.format(Locale.US, "%+.2f ~ %+.2f EV · 步进 %.2f",
                    exposureRange.minimum * exposureRange.stepEv, exposureRange.maximum * exposureRange.stepEv, exposureRange.stepEv))
                if (manual != null) Text("EV：手动曝光下不可用")
                Text(String.format(Locale.US, "预览对比度 %.2f", safe.contrast))
                Slider(
                    value = safe.contrast,
                    onValueChange = { onChange(safe.copy(contrast = it)) },
                    onValueChangeFinished = onChangeFinished,
                    valueRange = 0.5f..2f,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "预览对比度" },
                )
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("手动曝光", Modifier.weight(1f))
                    Switch(
                        checked = manual != null,
                        onCheckedChange = onManualEnabledChange,
                        enabled = enabled && sensorCapabilities.supported,
                        modifier = Modifier.semantics { contentDescription = "手动曝光开关" },
                    )
                }
                if (!sensorCapabilities.supported) Text("当前镜头不支持手动快门")
                if (manual != null) {
                    Text(String.format(Locale.US, "快门 %.3f ms · 固定 ISO %d", manual.timeNs / 1e6, manual.iso))
                    Slider(
                        value = sensorCapabilities.progress(manual.timeNs),
                        onValueChange = { onManualChange(manual.copy(timeNs = sensorCapabilities.timeAt(it))) },
                        valueRange = 0f..1f,
                        enabled = enabled && sensorCapabilities.supported && sensorCapabilities.minTimeNs < sensorCapabilities.maxTimeNs,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "手动快门" },
                    )
                    Text(String.format(Locale.US, "%.3f ~ %.3f ms", sensorCapabilities.minTimeNs / 1e6, sensorCapabilities.maxTimeNs / 1e6))
                    Text(if (manual.timeNs > 33_333_333L) "长快门：预览帧率将降低" else "",
                        modifier = Modifier.heightIn(min = 24.dp))
                } else Text("快门：自动")
                if (sensorCapabilities.variableAperture) {
                    val index = sensorCapabilities.apertures.indexOf(manual?.aperture).coerceAtLeast(0)
                    Text(if (manual == null) "光圈：自动" else String.format(Locale.US, "光圈 f/%.2f", sensorCapabilities.apertures[index]))
                    Slider(
                        value = index.toFloat(),
                        onValueChange = { value -> manual?.let { onManualChange(it.copy(aperture = sensorCapabilities.apertures[value.roundToInt()])) } },
                        valueRange = 0f..sensorCapabilities.apertures.lastIndex.toFloat(),
                        steps = sensorCapabilities.apertures.size - 2,
                        enabled = enabled && manual != null,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "手动光圈" },
                    )
                } else Text(if (sensorCapabilities.apertures.size == 1) "光圈：固定，不可调" else "光圈：设备未开放手动调节")
                if (actualSensorText.isNotBlank()) Text("实际回传\n$actualSensorText")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            TextButton(enabled = enabled, onClick = {
                if (manual != null) onManualEnabledChange(false)
                onChange(ImageAdjustments()); onChangeFinished()
            }) { Text("恢复默认") }
        },
    )
}
