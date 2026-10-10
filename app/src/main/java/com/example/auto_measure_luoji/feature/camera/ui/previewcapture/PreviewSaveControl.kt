package com.example.auto_measure_luoji.feature.camera.ui.previewcapture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.auto_measure_luoji.feature.calibration.util.GallerySaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal data class SavedPreviewFrame(val bitmap: Bitmap, val hdrConverted: Boolean)

internal class PreviewSaveControl(val busy: Boolean, val save: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreviewSaveButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = if (busy) "正在保存预览" else "保存当前预览"
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        Button(onClick = onClick, enabled = enabled && !busy,
            modifier = Modifier.height(36.dp).width(48.dp),
            shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.12f),
                disabledContainerColor = Color.White.copy(alpha = 0.06f))) {
            Icon(painter = painterResource(android.R.drawable.ic_menu_save), contentDescription = label,
                tint = Color.White.copy(alpha = if (enabled && !busy) 1f else 0.38f), modifier = Modifier.size(22.dp))
        }
    }
}

/** 仅点击时申请旧系统存储权限；不改变相机曝光、GL/HDR 或防抖设置。 */
@Composable
internal fun rememberPreviewSaveControl(
    onStatus: (String) -> Unit,
    capture: suspend () -> SavedPreviewFrame,
): PreviewSaveControl {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentCapture by rememberUpdatedState(capture)
    val currentStatus by rememberUpdatedState(onStatus)
    var busy by remember { mutableStateOf(false) }
    fun report(message: String) {
        currentStatus(message)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    val start: () -> Unit = {
        // 权限弹窗期间Activity可能重建，授权回调也必须重新进入忙碌状态。
        busy = true
        // 取TextureView与Toast均要求真实主线程，不依赖Compose调度器的恢复线程。
        scope.launch(Dispatchers.Main.immediate) {
            try {
                currentStatus("正在保存当前预览…")
                val frame = currentCapture()
                try {
                    val name = "preview_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}_${UUID.randomUUID().toString().take(8)}.png"
                    val uri = withContext(Dispatchers.IO) { GallerySaver.savePreview(context, frame.bitmap, name) }
                    Log.i("PreviewSave", "saved=$uri size=${frame.bitmap.width}x${frame.bitmap.height} hdrConverted=${frame.hdrConverted}")
                    report("已保存到相册 Pictures/AutoMeasure" + if (frame.hdrConverted) "（HDR 已转 SDR）" else "")
                } finally { frame.bitmap.recycle() }
            } catch (timeout: TimeoutCancellationException) {
                report("保存失败：等待预览画面超时，请重试")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("PreviewSave", "保存预览失败", error)
                report("保存失败：${error.message ?: error.javaClass.simpleName}")
            } finally { busy = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else {
            busy = false
            report("保存失败：未授予相册写入权限")
        }
    }
    return PreviewSaveControl(busy) {
        if (!busy) {
            busy = true
            if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else start()
        }
    }
}
