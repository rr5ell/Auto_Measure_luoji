#include <jni.h>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <algorithm>

// YUV_420_888 → RGB → 旋转 → Letterbox → 归一化 → FloatBuffer
// 所有预处理在 native 完成，Kotlin 层只传参数

struct LetterboxPlan {
    int srcW, srcH;
    int dstW, dstH;
    float scale;
    int padX, padY;
    int nw, nh;
};

static LetterboxPlan buildPlan(int srcW, int srcH, int dstW, int dstH) {
    LetterboxPlan plan;
    plan.srcW = srcW;
    plan.srcH = srcH;
    plan.dstW = dstW;
    plan.dstH = dstH;

    float rW = (float)dstW / (float)srcW;
    float rH = (float)dstH / (float)srcH;
    plan.scale = std::min(rW, rH);

    plan.nw = std::max(1, (int)roundf(srcW * plan.scale));
    plan.nh = std::max(1, (int)roundf(srcH * plan.scale));
    plan.padX = (dstW - plan.nw) / 2;
    plan.padY = (dstH - plan.nh) / 2;

    return plan;
}

// 从 YUV plane 采样
static inline uint8_t getY(const uint8_t* yPlane, int x, int y, int rowStride, int width) {
    return yPlane[y * rowStride + x];
}

static inline uint8_t getUV(const uint8_t* uvPlane, int x, int y, int rowStride, int pixelStride, int width) {
    int uvX = x / 2;
    int uvY = y / 2;
    return uvPlane[uvY * rowStride + uvX * pixelStride];
}

// YUV → RGB
static inline void yuvToRgb(uint8_t yVal, uint8_t uVal, uint8_t vVal,
                              float& r, float& g, float& b) {
    float y = (float)yVal;
    float u = (float)uVal - 128.0f;
    float v = (float)vVal - 128.0f;

    r = y + 1.402f * v;
    g = y - 0.344136f * u - 0.714136f * v;
    b = y + 1.772f * u;

    // clamp to [0, 255]
    r = std::max(0.0f, std::min(255.0f, r));
    g = std::max(0.0f, std::min(255.0f, g));
    b = std::max(0.0f, std::min(255.0f, b));
}

extern "C" {

// 主处理函数
JNIEXPORT jint JNICALL
Java_com_example_auto_1measure_feature_camera_domain_YuvPreprocessNative_process(
    JNIEnv* env,
    jclass /* clazz */,
    jobject yBuffer,
    jobject uBuffer,
    jobject vBuffer,
    jint yRowStride,
    jint uvRowStride,
    jint uvPixelStride,
    jint imageW,
    jint imageH,
    jint rotation,
    jint dstW,
    jint dstH,
    jobject outBuffer
) {
    // 获取 direct buffer 地址
    auto* yPlane = (uint8_t*)env->GetDirectBufferAddress(yBuffer);
    auto* uPlane = (uint8_t*)env->GetDirectBufferAddress(uBuffer);
    auto* vPlane = (uint8_t*)env->GetDirectBufferAddress(vBuffer);
    auto* out = (float*)env->GetDirectBufferAddress(outBuffer);

    if (!yPlane || !uPlane || !vPlane || !out) {
        return -1;
    }

    // 旋转后的源尺寸
    int rotatedW, rotatedH;
    if (rotation == 90 || rotation == 270) {
        rotatedW = imageH;
        rotatedH = imageW;
    } else {
        rotatedW = imageW;
        rotatedH = imageH;
    }

    // 构建 Letterbox 映射
    LetterboxPlan plan = buildPlan(rotatedW, rotatedH, dstW, dstH);

    const float inv255 = 1.0f / 255.0f;
    const float padVal = 114.0f * inv255;

    // 预计算映射表（避免内层循环重复计算）
    int* xMap = new int[plan.nw];
    int* yMap = new int[plan.nh];

    for (int x = 0; x < plan.nw; x++) {
        int sx = (x * rotatedW) / plan.nw;
        xMap[x] = std::min(sx, rotatedW - 1);
    }
    for (int y = 0; y < plan.nh; y++) {
        int sy = (y * rotatedH) / plan.nh;
        yMap[y] = std::min(sy, rotatedH - 1);
    }

    // 主循环：直接写入 FloatBuffer
    int outIdx = 0;

    for (int y = 0; y < dstH; y++) {
        int dy = y - plan.padY;
        if (dy < 0 || dy >= plan.nh) {
            // 填充区域
            for (int x = 0; x < dstW; x++) {
                out[outIdx++] = padVal;
                out[outIdx++] = padVal;
                out[outIdx++] = padVal;
            }
            continue;
        }

        int srcRotY = yMap[dy];

        for (int x = 0; x < dstW; x++) {
            int dx = x - plan.padX;
            if (dx < 0 || dx >= plan.nw) {
                out[outIdx++] = padVal;
                out[outIdx++] = padVal;
                out[outIdx++] = padVal;
                continue;
            }

            int srcRotX = xMap[dx];

            // 将旋转后的坐标映射回原始 YUV 坐标
            int origX, origY;
            switch (rotation) {
                case 90:
                    origX = srcRotY;
                    origY = imageH - 1 - srcRotX;
                    break;
                case 180:
                    origX = imageW - 1 - srcRotX;
                    origY = imageH - 1 - srcRotY;
                    break;
                case 270:
                    origX = imageW - 1 - srcRotY;
                    origY = srcRotX;
                    break;
                default: // 0
                    origX = srcRotX;
                    origY = srcRotY;
                    break;
            }

            // 边界检查
            origX = std::max(0, std::min(origX, imageW - 1));
            origY = std::max(0, std::min(origY, imageH - 1));

            // YUV 采样
            uint8_t yVal = getY(yPlane, origX, origY, yRowStride, imageW);
            uint8_t uVal = getUV(uPlane, origX, origY, uvRowStride, uvPixelStride, imageW);
            uint8_t vVal = getUV(vPlane, origX, origY, uvRowStride, uvPixelStride, imageW);

            // YUV → RGB → 归一化
            float r, g, b;
            yuvToRgb(yVal, uVal, vVal, r, g, b);

            out[outIdx++] = r * inv255;
            out[outIdx++] = g * inv255;
            out[outIdx++] = b * inv255;
        }
    }

    delete[] xMap;
    delete[] yMap;

    return 0;
}

} // extern "C"
