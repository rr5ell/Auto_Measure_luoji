package com.example.auto_measure_luoji.feature.camera.domain

import android.media.Image
import java.nio.ByteBuffer

/**
 * JNI 接口：YUV 预处理 native 实现
 *
 * 所有像素处理在 C++ 完成，Kotlin 层只传参数
 */
object YuvPreprocessNative {

    init {
        System.loadLibrary("yuv_preprocess")
    }

    /**
     * YUV_420_888 → RGB → 旋转 → Letterbox → 归一化 → FloatBuffer
     *
     * @param yPlane        Y 平面 direct ByteBuffer
     * @param uPlane        U 平面 direct ByteBuffer
     * @param vPlane        V 平面 direct ByteBuffer
     * @param yRowStride    Y 平面行步长
     * @param uvRowStride   UV 平面行步长
     * @param uvPixelStride UV 平面像素步长
     * @param imageW        原始图像宽度
     * @param imageH        原始图像高度
     * @param rotation      旋转角度 (0, 90, 180, 270)
     * @param dstW          模型输入宽度
     * @param dstH          模型输入高度
     * @param outBuffer     TFLite 输入 ByteBuffer (direct, FLOAT32)
     * @return 0 成功，-1 失败
     */
    @JvmStatic
    external fun process(
        yPlane: ByteBuffer,
        uPlane: ByteBuffer,
        vPlane: ByteBuffer,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        imageW: Int,
        imageH: Int,
        rotation: Int,
        dstW: Int,
        dstH: Int,
        refillPadding: Boolean,
        outBuffer: ByteBuffer,
    ): Int

    /**
     * 从 Image 获取 YUV planes 并调用 native 处理
     *
     * @param image     YUV_420_888 格式的 Image
     * @param rotation  传感器旋转角度
     * @param dstW      模型输入宽度
     * @param dstH      模型输入高度
     * @param refillPadding 是否重新填充不变的 Letterbox padding 区域
     * @param outBuffer TFLite 输入 ByteBuffer (direct, FLOAT32)
     * @return 0 成功，-1 失败
     */
    fun processImage(
        image: Image,
        rotation: Int,
        dstW: Int,
        dstH: Int,
        outBuffer: ByteBuffer,
        refillPadding: Boolean = true,
    ): Int {
        val yPlane = image.planes[0].buffer
        val uPlane = image.planes[1].buffer
        val vPlane = image.planes[2].buffer

        val yRowStride = image.planes[0].rowStride
        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride

        return process(
            yPlane, uPlane, vPlane,
            yRowStride, uvRowStride, uvPixelStride,
            image.width, image.height,
            rotation,
            dstW, dstH,
            refillPadding,
            outBuffer,
        )
    }
}
