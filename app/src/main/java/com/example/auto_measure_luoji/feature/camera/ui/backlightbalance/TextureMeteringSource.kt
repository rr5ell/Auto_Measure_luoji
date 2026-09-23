package com.example.auto_measure_luoji.feature.camera.ui.backlightbalance

import android.os.Looper
import android.util.Log
import android.view.TextureView
import com.example.auto_measure_luoji.feature.camera.domain.backlightbalance.BacklightBalanceSettings
import com.example.auto_measure_luoji.feature.camera.domain.backlightbalance.HighlightStatistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 普通预览测光适配器；所有公开方法在主线程调用，算法计算转移到Default线程。
 * onFrame只记录TextureView新画面的身份与接收时间，不逐帧截图；调用方每500ms调用sample。
 * 截图最长边640像素，不放大小图；统计仍最多64x48点，不含控件/检测框及View的RenderEffect。
 * 同一画面沿用原时间戳，冻结超过1.5秒返回null。切换视图/销毁会使在途计算失效。
 * 不适用于GLSurfaceView/HLG；该路径由宿主明确保留YUV来源，不静默拿旧TextureView截图。
 */
class TextureMeteringSource {
    companion object {
        /** 等比例缩图：s=min(1,640/max(w,h))；四舍五入至像素，窄边至少1像素。 */
        internal fun captureSize(width: Int, height: Int): Pair<Int, Int> {
            require(width > 0 && height > 0)
            val ratio = minOf(1.0, 640.0 / maxOf(width, height))
            return (width * ratio).roundToInt().coerceAtLeast(1) to
                (height * ratio).roundToInt().coerceAtLeast(1)
        }
    }

    private var view: TextureView? = null
    private var surfaceTimestamp: Long? = null
    private var receivedAtNs = Long.MIN_VALUE
    private var generation = 0L
    private var cachedTimestamp: Long? = null
    private var cached: HighlightStatistics? = null
    private var lastFailure: String? = null

    fun onFrame(source: TextureView, timestampNs: Long, nowNs: Long) {
        checkMainThread()
        if (view !== source) {
            clear()
            view = source
        }
        if (surfaceTimestamp == timestampNs) return
        surfaceTimestamp = timestampNs
        receivedAtNs = nowNs
    }

    fun invalidate(source: TextureView) {
        checkMainThread()
        if (view === source) clear()
    }

    /** minFrameNs是本次曝光循环开始时刻，拒绝上次相机会话/模式遗留的帧。 */
    suspend fun sample(source: TextureView?, nowNs: Long, minFrameNs: Long): HighlightStatistics? {
        checkMainThread()
        fun reject(reason: String): HighlightStatistics? {
            if (lastFailure != reason) {
                Log.d("BacklightBalance", "TextureView取样等待：$reason now=$nowNs frame=$receivedAtNs start=$minFrameNs")
                lastFailure = reason
            }
            return null
        }
        if (source == null || source !== view) return reject("视图未收到帧")
        if (!source.isAvailable || source.width <= 0 || source.height <= 0) return reject("视图不可用")
        if (receivedAtNs < minFrameNs) return reject("帧早于本次曝光循环")
        if (nowNs < receivedAtNs || nowNs - receivedAtNs > BacklightBalanceSettings.MAX_SAMPLE_AGE_NS)
            return reject("帧时间无效或过期")
        val timestamp = surfaceTimestamp ?: return null
        if (timestamp == cachedTimestamp) return cached
        val epoch = generation
        val sampledAt = receivedAtNs
        val (width, height) = captureSize(source.width, source.height)
        val pixels = try {
            val bitmap = source.getBitmap(width, height) ?: return reject("截图为空")
            try {
                IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
            } finally {
                bitmap.recycle()
            }
        } catch (error: RuntimeException) {
            Log.w("BacklightBalance", "TextureView测光取图失败", error)
            return null
        }
        val result = withContext(Dispatchers.Default) {
            HighlightStatistics.sampleArgb(pixels, width, height, sampledAt)
        }
        if (epoch != generation || view !== source) return null
        if (result == null) return reject("截图含透明像素或布局无效")
        lastFailure = null
        cachedTimestamp = timestamp
        cached = result
        return result
    }

    private fun clear() {
        generation++
        view = null
        surfaceTimestamp = null
        receivedAtNs = Long.MIN_VALUE
        cachedTimestamp = null
        cached = null
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())
}
