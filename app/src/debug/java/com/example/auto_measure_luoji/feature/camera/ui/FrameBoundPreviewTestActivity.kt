package com.example.auto_measure_luoji.feature.camera.ui

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** 真机 GL 回归测试宿主，仅进入 debug 构建，不打开相机或修改用户设置。 */
class FrameBoundPreviewTestActivity : Activity() {
    lateinit var preview: GlStabilizedPreviewView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = GlStabilizedPreviewView(this, hdrEnabled = intent.getBooleanExtra("hdr_preview", false))
        val container = FrameLayout(this)
        container.addView(preview, FrameLayout.LayoutParams(240, 426))
        setContentView(container)
    }

    override fun onPause() { preview.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); preview.onResume() }
}
