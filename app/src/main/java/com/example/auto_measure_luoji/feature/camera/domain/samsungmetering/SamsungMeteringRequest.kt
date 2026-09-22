package com.example.auto_measure_luoji.feature.camera.domain.samsungmetering

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult

/** 仅试验私有字段的可写性；回传 3/4 不代表启用了原相机的完整测光算法。 */
class SamsungMeteringRequest {
    private var requestKey: CaptureRequest.Key<Int>? = null
    private var resultKey: CaptureResult.Key<Int>? = null
    private var original: Int? = null
    private var lastRequest: CaptureRequest? = null
    private var rejectedMode: Int? = null
    private var prefix = ""
    @Volatile var available = false
        private set
    @Volatile var status = "三星测光：等待相机"
        private set

    @Synchronized
    fun rememberTemplate(builder: CaptureRequest.Builder, manufacturer: String) {
        requestKey = null
        resultKey = null
        original = null
        lastRequest = null
        rejectedMode = null
        available = false
        status = "三星测光：不可用"
        if (!manufacturer.equals("samsung", ignoreCase = true)) return
        try {
            val key = CaptureRequest.Key(KEY, Int::class.javaObjectType)
            val value = builder.get(key) ?: return
            requestKey = key
            resultKey = CaptureResult.Key(KEY, Int::class.javaObjectType)
            original = value
            available = true
            status = "三星测光：关闭（原值 $value）"
        } catch (_: IllegalArgumentException) {
            // 不猜测原值；未公开或类型不匹配时不进行私有字段写入。
        }
    }

    @Synchronized
    fun submit(builder: CaptureRequest.Builder, mode: Int?, suspended: Boolean,
               send: (CaptureRequest) -> Unit) {
        require(mode == null || mode == 3 || mode == 4) { "仅允许原值、模式3或模式4" }
        val key = requestKey
        val saved = original
        if (!available || key == null || saved == null) {
            send(builder.build())
            return
        }
        if (mode != rejectedMode) rejectedMode = null
        val rejected = mode != null && mode == rejectedMode
        val active = mode != null && !suspended && !rejected
        val target = if (active) mode!! else saved
        prefix = when {
            rejected -> "三星测光：请求失败，已恢复"
            mode == null -> "三星测光：关闭"
            suspended -> "三星测光：暂停"
            else -> "三星测光：开启"
        }
        try {
            builder.set(key, target)
            val request = builder.build()
            send(request)
            lastRequest = request
            status = "$prefix（请求 $target，等待回传）"
        } catch (error: Exception) {
            // 只对私有模式开启失败进行一次恢复重试，不误把普通请求错误归给此功能。
            if (!active) throw error
            rejectedMode = mode
            builder.set(key, saved)
            try {
                val restored = builder.build()
                send(restored)
                lastRequest = restored
                prefix = "三星测光：请求失败，已恢复"
                status = "$prefix（请求 $saved，等待回传）"
            } catch (restoreError: Exception) {
                lastRequest = null
                status = "三星测光：恢复失败"
                restoreError.addSuppressed(error)
                throw restoreError
            }
        }
    }

    @Synchronized
    fun onResult(request: CaptureRequest, result: CaptureResult) {
        if (lastRequest != request) return
        val key = resultKey ?: return
        val requested = requestKey?.let { request.get(it) } ?: return
        val actual = try { result.get(key) } catch (_: IllegalArgumentException) { null }
        status = "$prefix（请求 $requested，回传 ${actual ?: "未提供"}）"
    }

    companion object {
        const val KEY = "samsung.android.control.meteringMode"
    }
}
