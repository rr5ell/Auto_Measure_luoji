package com.example.auto_measure_luoji.feature.camera.ui.previewcapture

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PreviewSaveCameraTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun toolbarSavesPreviewBesideWakeButton() = saveFromCamera(false)

    @Test fun toolbarSavesGlCameraPreview() = saveFromCamera(true)

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
                // 顶部入口无需先打开菜单；等首帧实际显示，避免把软件GPU冷启动计入保存超时。
                compose.waitUntil(30_000) {
                    compose.onAllNodes(SemanticsMatcher("预览已有画面") { node ->
                        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any {
                            it.text.startsWith("预览 FPS：") && (it.text.substringAfter('：').toFloatOrNull() ?: 0f) > 0f
                        } == true
                    }).fetchSemanticsNodes().isNotEmpty()
                }
                val save = compose.onNodeWithContentDescription("保存当前预览")
                save.assertIsDisplayed().assertIsEnabled()
                val wakeBounds = compose.onNodeWithText("唤醒").fetchSemanticsNode().boundsInRoot
                val saveBounds = save.fetchSemanticsNode().boundsInRoot
                val moreBounds = compose.onNodeWithText("更多").fetchSemanticsNode().boundsInRoot
                assertTrue("保存必须在唤醒右侧", saveBounds.left >= wakeBounds.right)
                assertTrue("保存不能遮挡更多", saveBounds.right <= moreBounds.left)
                save.performClick()
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
