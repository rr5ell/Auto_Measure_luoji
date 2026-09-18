package com.example.auto_measure_luoji.feature.camera.domain.previewfps

/** Counts new preview images, not render requests or inference results. */
class PreviewFrameRate {
    private var windowStartNs: Long? = null
    private var frames = 0L
    private var lastTimestampNs = 0L

    @Synchronized
    fun reset(nowNs: Long) {
        windowStartNs = nowNs
        frames = 0L
        lastTimestampNs = 0L
    }

    @Synchronized
    fun onFrame(timestampNs: Long) {
        if (timestampNs <= 0L || timestampNs == lastTimestampNs) return
        lastTimestampNs = timestampNs
        frames++
    }

    @Synchronized
    fun sample(nowNs: Long): Double? {
        val start = windowStartNs
        if (start == null || nowNs < start) {
            reset(nowNs)
            return null
        }
        val elapsedNs = nowNs - start
        if (elapsedNs < 1_000_000_000L) return null
        // Use actual monotonic elapsed time so a delayed UI poll does not inflate FPS.
        val fps = frames * 1_000_000_000.0 / elapsedNs
        frames = 0L
        windowStartNs = nowNs
        return fps
    }
}
