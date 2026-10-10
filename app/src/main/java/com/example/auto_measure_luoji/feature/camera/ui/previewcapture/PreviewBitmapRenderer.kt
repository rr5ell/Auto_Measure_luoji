package com.example.auto_measure_luoji.feature.camera.ui.previewcapture

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import androidx.annotation.RequiresApi

/** 单次离屏重放显示效果；不连接 Camera2，也不占用相机的 ImageReader。调用者回收返回图。 */
@RequiresApi(31)
internal object PreviewBitmapRenderer {
    fun render(source: Bitmap, effect: RenderEffect): Bitmap {
        val reader = ImageReader.newInstance(source.width, source.height, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val node = RenderNode("SavedPreview")
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            node.setPosition(0, 0, source.width, source.height)
            node.setRenderEffect(effect)
            val canvas = node.beginRecording()
            canvas.drawBitmap(source, 0f, 0f, null)
            node.endRecording()
            val status = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            check(status == HardwareRenderer.SYNC_OK || status == HardwareRenderer.SYNC_REDRAW_REQUESTED) {
                "预览效果离屏渲染失败：$status"
            }
            reader.acquireNextImage().use { image ->
                checkNotNull(image) { "未取得预览效果图像" }
                image.hardwareBuffer.use { buffer ->
                    checkNotNull(buffer) { "预览效果图像缺少硬件缓冲" }
                    val hardware = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, null)) { "无法读取预览效果图像" }
                    try {
                        return checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false)) { "无法复制预览效果图像" }
                    } finally { hardware.recycle() }
                }
            }
        } finally {
            renderer.destroy()
            node.discardDisplayList()
            reader.close()
        }
    }
}
