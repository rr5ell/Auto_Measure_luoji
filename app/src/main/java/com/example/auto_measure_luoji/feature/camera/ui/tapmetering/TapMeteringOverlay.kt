package com.example.auto_measure_luoji.feature.camera.ui.tapmetering

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 只拦截有效单击；拖动/多指不提交测光对焦，黄色标记只表示受理，合焦结果由主面板显示。 */
@Composable
fun TapMeteringOverlay(epoch: Any, onTap: suspend (Offset) -> Boolean, modifier: Modifier = Modifier) {
    val callback by rememberUpdatedState(onTap)
    val scope = rememberCoroutineScope()
    var marker by remember(epoch) { mutableStateOf<Offset?>(null) }
    var pending by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(epoch) { onDispose { pending?.cancel() } }
    Canvas(modifier.semantics { contentDescription = "点击测光预览" }.pointerInput(epoch) {
        awaitEachGesture {
            val down = awaitFirstDown()
            var valid = true
            var pressed: Boolean
            var point = down.position
            do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (event.changes.size != 1 || change == null || change.isConsumed ||
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                    change.uptimeMillis - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis) valid = false
                if (change != null) point = change.position
                pressed = event.changes.any { it.pressed }
                if (!pressed && valid) change?.consume()
            } while (pressed)
            if (!valid) return@awaitEachGesture
            pending?.cancel()
            marker = null
            pending = scope.launch {
                if (callback(point)) {
                    marker = point
                    delay(1500)
                    marker = null
                }
            }
        }
    }) {
        marker?.let { drawCircle(Color(0xFFFFD54F), 20.dp.toPx(), it, style = Stroke(2.dp.toPx())) }
    }
}
