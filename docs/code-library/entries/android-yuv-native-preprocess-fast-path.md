# android-yuv-native-preprocess-fast-path

## Metadata
- status: active
- tech: Android | Kotlin | C++ | JNI
- tags: camera2, imagereader, yuv, yolo, image-processing, model-input, performance
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\cpp\yuv_preprocess.cpp
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\YuvPreprocessNative.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\detection\domain\YoloDetector.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-08-28
- author: Codex Agent

## 适用场景
- Android Camera2 `ImageReader` 输出 `YUV_420_888`，需要直接转换成 YOLO / TFLite FLOAT32 RGB 输入。
- 需要同时完成 YUV 采样、旋转、Letterbox、padding 和归一化，并避免中间 Bitmap。
- 需要压低 `YOLO timing source=ImageReaderYUV ... pre=<ms>`，且模型输入仍为 FLOAT32 RGB。

## 禁止场景
- 不适用于 INT8 / UINT8 模型输入，除非同步修改 `YoloDetector` 输入 buffer 和 native 输出格式。
- 不要为了降低预处理耗时开启 Camera2 EIS；EIS 可能裁剪画面并破坏校准坐标一致性。
- 普通逻辑相机会话禁止重新启用 `OutputConfiguration.setPhysicalCameraId()`，除非哥哥明确要求进入物理输出诊断模式。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `imageW/imageH` | `Int` | Camera2 YUV 尺寸 | 原始 `ImageReader` 帧宽高 | 必须来自 `Image.width/height` |
| `rotation` | `Int` | 0/90/180/270 | 传感器到显示方向的旋转角 | 非标准角度按 0 处理 |
| `dstW/dstH` | `Int` | 模型 tensor 尺寸 | YOLO 输入宽高 | 当前只写 FLOAT32 RGB |
| `xMap/yMap` | native cache | thread-local | 旋转后坐标到源图采样索引 | 尺寸或旋转变化时重建 |
| `refillPadding` | `Boolean` | `true` | 是否重写不变的 Letterbox 灰边 | 连续 YUV 帧可为 `false` |

## 复用方式
- Kotlin 侧通过 `YuvPreprocessNative.processImage(image, rotation, inputW, inputH, inputBuffer)` 调用 native。
- `YoloDetector.detectFromYuv()` 只在 native 返回失败或 JNI 绑定异常时回退 `YuvFrameProcessor.fillInputFromYuv()`。
- 性能排查时比较 `YOLO timing source=ImageReaderYUV ... pre=<ms>`，不要只看总耗时。
- `YoloDetector` 用 `YuvNativePaddingKey` 跟踪输入 buffer、YUV 尺寸、旋转和模型输入尺寸；key 不变时 native 可跳过 padding 重写。

## 注意事项
- native 输出为 RGB float interleaved，范围 `[0, 1]`，padding 值为 `114 / 255`。
- YUV 到 RGB 使用 BT.601 full-range 定点整数近似，和旧浮点实现可能有极小数值差异。
- 90/270 度旋转时，Letterbox 源宽高需要先互换，再映射回原始 YUV 坐标。
- 大尺寸输入最多使用 4 个 native worker 按行并行处理；线程在 JNI 调用内创建并 join，不跨帧持有 `Image` plane 指针。
- YUV 帧消费后仍必须在调用方 `close()`，否则 `ImageReader.maxImages` 会堵塞相机管线。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:assembleDebug`
- 已覆盖边界：arm64-v8a、armeabi-v7a、x86、x86_64 native 编译通过。
- 未覆盖风险：真机 `pre=` 降幅受 SoC、YUV stride、模型输入尺寸和后台负载影响，需要安装后用 Logcat 复测。

## 给 AI 的指令
优先复用本条目的 native 快路径。除非哥哥明确要求改模型输入格式，否则不要重新引入 Bitmap 中转或逐像素 Kotlin fallback 作为主路径。
