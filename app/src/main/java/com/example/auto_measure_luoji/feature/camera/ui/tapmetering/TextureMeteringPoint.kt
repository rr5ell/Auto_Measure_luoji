package com.example.auto_measure_luoji.feature.camera.ui.tapmetering

import android.graphics.Matrix
import android.view.TextureView
import com.example.auto_measure_luoji.feature.camera.domain.gldetection.GlDetectionCoordinates
import com.example.auto_measure_luoji.feature.camera.domain.gldetection.GlDetectionPoint

/** TextureView 的附加二维变换先求逆，再复用 OES 采样矩阵到原始缓冲区的映射。 */
fun TextureView.meteringPoint(x: Float, y: Float): GlDetectionPoint? {
    if (!isAvailable || width <= 0 || height <= 0) return null
    return runCatching {
        val inverse = Matrix()
        if (!getTransform(Matrix()).invert(inverse)) return null
        val point = floatArrayOf(x, y)
        // p_display=T*p_base，因此 p_base=T^-1*p_display；T 是实际 setTransform 矩阵。
        // 然后由共享映射执行屏幕 y 向下 -> GL y 向上 -> SurfaceTexture 采样矩阵 S。
        // S 已含缓冲区旋转/翻转/裁切，禁止再按 SENSOR_ORIENTATION 旋转一次。
        inverse.mapPoints(point)
        val sampling = FloatArray(16)
        (surfaceTexture ?: return null).getTransformMatrix(sampling)
        val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        GlDetectionCoordinates.screenToBuffer(sampling, identity, width, height, point[0], point[1])
    }.getOrNull()
}
