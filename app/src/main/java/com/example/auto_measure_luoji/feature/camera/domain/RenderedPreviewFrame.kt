package com.example.auto_measure_luoji.feature.camera.domain

/** GL 实际绘制的帧及两个独立像素观测；elapsed 时间不是物理屏幕 present 时间。 */
data class RenderedPreviewFrame(
    val sourceTimestampNs: Long,
    val transformTimestampNs: Long,
    val drawSequence: Long,
    val drawElapsedNs: Long,
    val viewWidth: Int,
    val viewHeight: Int,
    val readbackCostMs: Float,
    val unmatchedDraws: Long,
    val repeatedDraws: Long,
    val droppedTextureFrames: Long,
    val diagnosticDrops: Long,
    val transform: GyroPreviewStabilizer.Transform,
    val raw: PreviewMotionEstimator.LumaFrame,
    val output: PreviewMotionEstimator.LumaFrame,
)
