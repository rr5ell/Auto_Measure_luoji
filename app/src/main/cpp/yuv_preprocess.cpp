#include <jni.h>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <vector>
#include <thread>

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

struct MappingCache {
    int imageW = -1;
    int imageH = -1;
    int rotation = -1;
    int dstW = -1;
    int dstH = -1;
    int rotatedW = -1;
    int rotatedH = -1;
    LetterboxPlan plan{};
    std::vector<int> xMap;
    std::vector<int> yMap;
};

static thread_local MappingCache gMappingCache;

static inline int normalizeRotation(int rotation) {
    int value = rotation % 360;
    if (value < 0) {
        value += 360;
    }
    switch (value) {
        case 90:
        case 180:
        case 270:
            return value;
        default:
            return 0;
    }
}

static MappingCache& getMappingCache(
    int imageW,
    int imageH,
    int rotation,
    int dstW,
    int dstH
) {
    MappingCache& cache = gMappingCache;
    if (cache.imageW == imageW &&
        cache.imageH == imageH &&
        cache.rotation == rotation &&
        cache.dstW == dstW &&
        cache.dstH == dstH) {
        return cache;
    }

    cache.imageW = imageW;
    cache.imageH = imageH;
    cache.rotation = rotation;
    cache.dstW = dstW;
    cache.dstH = dstH;

    if (rotation == 90 || rotation == 270) {
        cache.rotatedW = imageH;
        cache.rotatedH = imageW;
    } else {
        cache.rotatedW = imageW;
        cache.rotatedH = imageH;
    }

    cache.plan = buildPlan(cache.rotatedW, cache.rotatedH, dstW, dstH);
    cache.xMap.assign(dstW, -1);
    cache.yMap.assign(dstH, -1);

    const int contentXStart = std::max(0, cache.plan.padX);
    const int contentXEnd = std::min(dstW, cache.plan.padX + cache.plan.nw);
    for (int x = contentXStart; x < contentXEnd; ++x) {
        const int dx = x - cache.plan.padX;
        const int sx = (dx * cache.rotatedW) / cache.plan.nw;
        cache.xMap[x] = std::min(sx, cache.rotatedW - 1);
    }

    const int contentYStart = std::max(0, cache.plan.padY);
    const int contentYEnd = std::min(dstH, cache.plan.padY + cache.plan.nh);
    for (int y = contentYStart; y < contentYEnd; ++y) {
        const int dy = y - cache.plan.padY;
        const int sy = (dy * cache.rotatedH) / cache.plan.nh;
        cache.yMap[y] = std::min(sy, cache.rotatedH - 1);
    }

    return cache;
}

static inline uint8_t clampByte(int value) {
    if (value < 0) {
        return 0;
    }
    if (value > 255) {
        return 255;
    }
    return static_cast<uint8_t>(value);
}

static inline void writePaddingPixels(float*& dst, int pixelCount, float padVal) {
    if (pixelCount <= 0) {
        return;
    }
    std::fill(dst, dst + pixelCount * 3, padVal);
    dst += pixelCount * 3;
}

static inline void writeYuvAsRgb(
    float*& dst,
    const uint8_t* yPlane,
    const uint8_t* uPlane,
    const uint8_t* vPlane,
    int yRowStride,
    int uvRowStride,
    int uvPixelStride,
    int origX,
    int origY,
    float inv255
) {
    const int yVal = yPlane[origY * yRowStride + origX];
    const int uvX = origX / 2;
    const int uvY = origY / 2;
    const int uvIndex = uvY * uvRowStride + uvX * uvPixelStride;
    const int u = static_cast<int>(uPlane[uvIndex]) - 128;
    const int v = static_cast<int>(vPlane[uvIndex]) - 128;

    // BT.601 full-range 近似。使用定点整数避免 1280x1280 逐像素浮点矩阵计算。
    const int r = yVal + (359 * v) / 256;
    const int g = yVal - (88 * u + 183 * v) / 256;
    const int b = yVal + (454 * u) / 256;

    *dst++ = static_cast<float>(clampByte(r)) * inv255;
    *dst++ = static_cast<float>(clampByte(g)) * inv255;
    *dst++ = static_cast<float>(clampByte(b)) * inv255;
}

static int chooseThreadCount(int dstH, int contentPixels) {
    if (contentPixels < 512 * 512) {
        return 1;
    }
    const unsigned int hardware = std::thread::hardware_concurrency();
    const int available = hardware == 0 ? 2 : static_cast<int>(hardware);
    const int byRows = std::max(1, dstH / 64);
    return std::max(1, std::min(std::min(available, byRows), 4));
}

static void processRows(
    int normalizedRotation,
    int rowStart,
    int rowEnd,
    int dstW,
    int imageW,
    int imageH,
    const uint8_t* yPlane,
    const uint8_t* uPlane,
    const uint8_t* vPlane,
    int yRowStride,
    int uvRowStride,
    int uvPixelStride,
    const int* xMap,
    const int* yMap,
    int contentXStart,
    int contentXEnd,
    bool fillPadding,
    float* out,
    float inv255,
    float padVal
) {
    const int rowStrideFloats = dstW * 3;
    switch (normalizedRotation) {
        case 90:
            for (int y = rowStart; y < rowEnd; ++y) {
                float* dst = out + static_cast<size_t>(y) * rowStrideFloats;
                const int srcRotY = yMap[y];
                if (srcRotY < 0) {
                    if (fillPadding) {
                        writePaddingPixels(dst, dstW, padVal);
                    }
                    continue;
                }
                if (fillPadding) {
                    writePaddingPixels(dst, contentXStart, padVal);
                } else {
                    dst += contentXStart * 3;
                }
                for (int x = contentXStart; x < contentXEnd; ++x) {
                    const int srcRotX = xMap[x];
                    const int origX = srcRotY;
                    const int origY = imageH - 1 - srcRotX;
                    writeYuvAsRgb(dst, yPlane, uPlane, vPlane, yRowStride,
                                  uvRowStride, uvPixelStride, origX, origY, inv255);
                }
                if (fillPadding) {
                    writePaddingPixels(dst, dstW - contentXEnd, padVal);
                }
            }
            break;
        case 180:
            for (int y = rowStart; y < rowEnd; ++y) {
                float* dst = out + static_cast<size_t>(y) * rowStrideFloats;
                const int srcRotY = yMap[y];
                if (srcRotY < 0) {
                    if (fillPadding) {
                        writePaddingPixels(dst, dstW, padVal);
                    }
                    continue;
                }
                if (fillPadding) {
                    writePaddingPixels(dst, contentXStart, padVal);
                } else {
                    dst += contentXStart * 3;
                }
                for (int x = contentXStart; x < contentXEnd; ++x) {
                    const int srcRotX = xMap[x];
                    const int origX = imageW - 1 - srcRotX;
                    const int origY = imageH - 1 - srcRotY;
                    writeYuvAsRgb(dst, yPlane, uPlane, vPlane, yRowStride,
                                  uvRowStride, uvPixelStride, origX, origY, inv255);
                }
                if (fillPadding) {
                    writePaddingPixels(dst, dstW - contentXEnd, padVal);
                }
            }
            break;
        case 270:
            for (int y = rowStart; y < rowEnd; ++y) {
                float* dst = out + static_cast<size_t>(y) * rowStrideFloats;
                const int srcRotY = yMap[y];
                if (srcRotY < 0) {
                    if (fillPadding) {
                        writePaddingPixels(dst, dstW, padVal);
                    }
                    continue;
                }
                if (fillPadding) {
                    writePaddingPixels(dst, contentXStart, padVal);
                } else {
                    dst += contentXStart * 3;
                }
                for (int x = contentXStart; x < contentXEnd; ++x) {
                    const int srcRotX = xMap[x];
                    const int origX = imageW - 1 - srcRotY;
                    const int origY = srcRotX;
                    writeYuvAsRgb(dst, yPlane, uPlane, vPlane, yRowStride,
                                  uvRowStride, uvPixelStride, origX, origY, inv255);
                }
                if (fillPadding) {
                    writePaddingPixels(dst, dstW - contentXEnd, padVal);
                }
            }
            break;
        default:
            for (int y = rowStart; y < rowEnd; ++y) {
                float* dst = out + static_cast<size_t>(y) * rowStrideFloats;
                const int srcRotY = yMap[y];
                if (srcRotY < 0) {
                    if (fillPadding) {
                        writePaddingPixels(dst, dstW, padVal);
                    }
                    continue;
                }
                if (fillPadding) {
                    writePaddingPixels(dst, contentXStart, padVal);
                } else {
                    dst += contentXStart * 3;
                }
                for (int x = contentXStart; x < contentXEnd; ++x) {
                    const int srcRotX = xMap[x];
                    writeYuvAsRgb(dst, yPlane, uPlane, vPlane, yRowStride,
                                  uvRowStride, uvPixelStride, srcRotX, srcRotY, inv255);
                }
                if (fillPadding) {
                    writePaddingPixels(dst, dstW - contentXEnd, padVal);
                }
            }
            break;
    }
}

extern "C" {

// 主处理函数
JNIEXPORT jint JNICALL
Java_com_example_auto_1measure_1luoji_feature_camera_domain_YuvPreprocessNative_process(
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
    jboolean refillPadding,
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

    const int normalizedRotation = normalizeRotation(rotation);
    MappingCache& cache = getMappingCache(imageW, imageH, normalizedRotation, dstW, dstH);
    const LetterboxPlan& plan = cache.plan;
    const int* xMap = cache.xMap.data();
    const int* yMap = cache.yMap.data();
    const float inv255 = 1.0f / 255.0f;
    const float padVal = 114.0f * inv255;
    const int contentXStart = std::max(0, plan.padX);
    const int contentXEnd = std::min(dstW, plan.padX + plan.nw);
    const int contentPixels = std::max(0, contentXEnd - contentXStart) * dstH;
    const bool fillPadding = refillPadding == JNI_TRUE;
    const int threadCount = chooseThreadCount(dstH, contentPixels);

    if (threadCount <= 1) {
        processRows(normalizedRotation, 0, dstH, dstW, imageW, imageH,
                    yPlane, uPlane, vPlane, yRowStride, uvRowStride, uvPixelStride,
                    xMap, yMap, contentXStart, contentXEnd, fillPadding,
                    out, inv255, padVal);
    } else {
        const int rowsPerThread = dstH / threadCount;
        std::vector<std::thread> workers;
        workers.reserve(threadCount - 1);
        for (int i = 1; i < threadCount; ++i) {
            const int rowStart = i * rowsPerThread;
            const int rowEnd = (i == threadCount - 1) ? dstH : (i + 1) * rowsPerThread;
            workers.emplace_back(processRows, normalizedRotation, rowStart, rowEnd,
                                 dstW, imageW, imageH, yPlane, uPlane, vPlane,
                                 yRowStride, uvRowStride, uvPixelStride,
                                 xMap, yMap, contentXStart, contentXEnd,
                                 fillPadding, out, inv255, padVal);
        }
        processRows(normalizedRotation, 0, rowsPerThread, dstW, imageW, imageH,
                    yPlane, uPlane, vPlane, yRowStride, uvRowStride, uvPixelStride,
                    xMap, yMap, contentXStart, contentXEnd, fillPadding,
                    out, inv255, padVal);
        for (std::thread& worker : workers) {
            worker.join();
        }
    }

    return 0;
}

} // extern "C"
