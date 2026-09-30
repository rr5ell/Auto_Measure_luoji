package com.example.auto_measure_luoji.feature.camera.ui.outdoorbrightness

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.auto_measure_luoji.feature.camera.domain.outdoorbrightness.OutdoorBrightnessController

class OutdoorBrightnessUiState(private val controller: OutdoorBrightnessController) {
    var visible by mutableStateOf(false)
        private set
    var hasOverride by mutableStateOf(false)
        private set
    var result by mutableStateOf(OutdoorBrightnessController.HBM_FAILURE)
        private set

    fun open(): String {
        visible = true
        return result
    }

    fun dismiss() { visible = false }

    fun requestMaximum(): String {
        result = controller.requestMaximum().fold(
            onSuccess = { OutdoorBrightnessController.MAXIMUM_REQUESTED },
            onFailure = { "窗口高亮请求失败：${it.message ?: it.javaClass.simpleName}" },
        )
        hasOverride = controller.hasOverride
        // 关闭弹窗，回到真正设置亮度的Activity窗口。
        if (result == OutdoorBrightnessController.MAXIMUM_REQUESTED) visible = false
        return result
    }

    fun restore(): String {
        result = controller.restore().fold(
            onSuccess = { "窗口亮度已释放；HBM 状态未确认" },
            onFailure = { "窗口亮度恢复失败：${it.message ?: it.javaClass.simpleName}" },
        )
        hasOverride = controller.hasOverride
        return result
    }
}

@Composable
fun rememberOutdoorBrightnessControl(onStatus: (String) -> Unit): OutdoorBrightnessUiState {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val report by rememberUpdatedState(onStatus)
    val state = remember(context) {
        val activity = context.activityOrNull()
        OutdoorBrightnessUiState(OutdoorBrightnessController(
            readBrightness = { checkNotNull(activity?.window) { "当前页面没有可用的Activity窗口" }.attributes.screenBrightness },
            writeBrightness = { value ->
                val window = checkNotNull(activity?.window) { "当前页面没有可用的Activity窗口" }
                val attributes = window.attributes
                attributes.screenBrightness = value
                window.attributes = attributes
            },
        ))
    }
    DisposableEffect(owner, state) {
        fun release() {
            if (state.hasOverride) {
                val result = state.restore()
                Log.i("OutdoorBrightness", result)
                report(result)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) release()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); release() }
    }
    return state
}

@Composable
fun OutdoorBrightnessDialog(state: OutdoorBrightnessUiState, onStatus: (String) -> Unit) {
    if (!state.visible) return
    AlertDialog(
        onDismissRequest = state::dismiss,
        title = { Text("HBM / 户外高亮") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(OutdoorBrightnessController.HBM_FAILURE, color = MaterialTheme.colorScheme.error)
                Text(OutdoorBrightnessController.HBM_DETAIL)
                if (state.result != OutdoorBrightnessController.HBM_FAILURE) Text(state.result)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = if (state.hasOverride) state.restore() else state.requestMaximum()
                Log.i("OutdoorBrightness", result)
                onStatus(result)
            }) { Text(if (state.hasOverride) "恢复窗口亮度" else "请求窗口最高亮度") }
        },
        dismissButton = { TextButton(onClick = state::dismiss) { Text("关闭") } },
    )
}

private fun Context.activityOrNull(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
    return current as? Activity
}
