package com.example.auto_measure_luoji.feature.camera.ui.previewcapture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.feature.calibration.util.GallerySaver
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PreviewGalleryInstrumentedTest {
    @Test fun savesReadablePngWithDimensionsAndPublishesToAlbum() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(90, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            val uri = GallerySaver.savePreview(context, bitmap, "preview_test_${UUID.randomUUID()}.png")
            try {
                context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING,
                    MediaStore.Images.Media.RELATIVE_PATH), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                    assertEquals("Pictures/AutoMeasure/", it.getString(1))
                }
                val restored = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }!!
                try {
                    assertEquals(90, restored.width)
                    assertEquals(160, restored.height)
                    assertEquals(Color.RED, restored.getPixel(45, 80))
                } finally { restored.recycle() }
                assertFalse("保存工具不能回收调用者的图像", bitmap.isRecycled)
            } finally { context.contentResolver.delete(uri, null, null) }
        } finally { bitmap.recycle() }
    }

    @Test fun encodingFailureRemovesInsertedMediaRecord() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "preview_failure_${UUID.randomUUID()}.png"
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { recycle() }
        assertTrue(runCatching { GallerySaver.savePreview(context, bitmap, name) }.isFailure)
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(name), null)!!.use { assertEquals("失败后遗留了相册记录", 0, it.count) }
    }
}
