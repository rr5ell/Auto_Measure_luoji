package com.example.auto_measure_luoji.feature.camera.ui.previewcapture

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PreviewSaveCameraTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun moreMenuSavesPreviewWithoutChangingTopToolbar() = saveFromCamera(false)

    @Test fun moreMenuSavesGlCameraPreview() = saveFromCamera(true)

    private fun saveFromCamera(gl: Boolean) {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        val prefs = context.getSharedPreferences("camera_screen_settings", Context.MODE_PRIVATE)
        val keys = listOf("gyro_preview_gl_render_enabled", "gyro_preview_stabilization_enabled")
        val original = keys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        prefs.edit().apply { keys.forEach { putBoolean(it, false) } }.commit()
        prefs.edit().putBoolean("gyro_preview_gl_render_enabled", gl).commit()
        fun savedIds(): Set<Long> {
            return context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
                arrayOf("Pictures/AutoMeasure/"), null)!!.use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
            }
        }
        val before = savedIds()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(30_000) {
                    compose.onAllNodes(hasText("识别") and isEnabled()).fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithText("保存当前预览").assertDoesNotExist()
                compose.onNodeWithText("更多").performClick()
                compose.onNodeWithText("保存当前预览").performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(15_000) { savedIds().size > before.size }
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasText("已保存到相册 Pictures/AutoMeasure", substring = true))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(1, savedIds().size - before.size)
            }
        } finally {
            (savedIds() - before).forEach {
                context.contentResolver.delete(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it), null, null)
            }
            prefs.edit().apply { original.forEach { (key, value) ->
                if (value == null) remove(key) else putBoolean(key, value)
            } }.commit()
        }
    }
}
