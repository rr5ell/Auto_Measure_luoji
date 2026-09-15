package com.example.auto_measure_luoji.feature.camera.domain.gldetection

/**
 * 仅保留最新异步结果。相机时间戳用于拒绝未来帧/过期帧；单调时钟用于相机停止后的过期清理。
 * 时间单位统一为 ns。此策略不预测目标运动，显示变换重投影也不能代替光流或目标跟踪。
 */
class GlAsyncDetectionState {
    data class Result(
        val ticket: GlDetectionTicket,
        val capturedElapsedNs: Long,
        val boxes: List<GlDetectionBox>,
    ) {
        val expiresElapsedNs: Long get() = capturedElapsedNs + MAX_AGE_NS
    }

    private var latest: Result? = null
    private var newestTimestampNs = 0L

    fun offer(result: Result, previewTimestampNs: Long, nowNs: Long): Boolean {
        if (result.ticket.timestampNs <= newestTimestampNs) return false
        newestTimestampNs = result.ticket.timestampNs
        latest = result.takeIf { valid(it, previewTimestampNs, nowNs) }
        return latest != null
    }

    fun current(previewTimestampNs: Long, nowNs: Long): Result? {
        val result = latest ?: return null
        if (!valid(result, previewTimestampNs, nowNs)) {
            latest = null
            return null
        }
        return result
    }

    private fun valid(result: Result, previewTimestampNs: Long, nowNs: Long): Boolean =
        previewTimestampNs >= result.ticket.timestampNs &&
            previewTimestampNs - result.ticket.timestampNs < MAX_AGE_NS &&
            nowNs >= result.capturedElapsedNs && nowNs - result.capturedElapsedNs < MAX_AGE_NS

    fun clear() { latest = null; newestTimestampNs = 0L }

    companion object { const val MAX_AGE_NS = 200_000_000L }
}
