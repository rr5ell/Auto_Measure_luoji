package com.example.auto_measure_luoji.feature.calibration.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream
import java.io.File

object GallerySaver {

    fun savePng(context: Context, bytes: ByteArray, filename: String): Uri =
        writePng(context, filename, "LaserNative") { it.write(bytes) }

    /** 在 IO 线程直接编码，避免整张 PNG 再复制成 ByteArray；调用者负责回收 bitmap。 */
    fun savePreview(context: Context, bitmap: Bitmap, filename: String): Uri =
        writePng(context, filename, "AutoMeasure") {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("PNG 编码失败")
        }

    private fun writePng(context: Context, filename: String, album: String, write: (OutputStream) -> Unit): Uri {
        require(filename.isNotBlank() && '/' !in filename && '\\' !in filename) { "图片文件名无效" }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + album
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), album)
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建相册目录")
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(directory, filename).absolutePath)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("保存到相册失败：无法创建 MediaStore 记录")

        try {
            resolver.openOutputStream(uri)?.use(write)
                ?: throw IOException("保存到相册失败：无法写入输出流")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completeValues = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                if (resolver.update(uri, completeValues, null, null) <= 0) throw IOException("无法发布相册图片")
            }
            return uri
        } catch (error: Throwable) {
            // 写入/发布失败不留下空图或永久 pending 记录；保留原始失败原因。
            try { resolver.delete(uri, null, null) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }
}
