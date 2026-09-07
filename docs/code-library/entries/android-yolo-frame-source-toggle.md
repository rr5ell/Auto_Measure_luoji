# android-yolo-frame-source-toggle

## Metadata
- status: active
- tech: Android | Kotlin
- tags: camera2, imagereader, yuv, textureview, yolo, calibration, frame-source
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\Camera2Controller.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\calibration\util\ImageUtils.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\detection\domain\YoloDetector.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\YuvPreprocessNative.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\YuvFrameProcessor.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\cpp\yuv_preprocess.cpp
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-08-28
- author: Codex Agent

## 2026-08-28 Active Lens Diagnostics Update
- `CameraScreen` 的 PreviewBitmap 和 ImageReaderYUV 检测日志会输出 `active=<physicalId>/<eq35mm>mm selected=<id>/<eq35mm>mm zoom=<ratio>x`。
- 该日志只能证明逻辑相机当前 capture result 上报的 active 物理镜头，不能证明每个 Surface 都有独立的物理来源上报。
- 普通预览仍禁止重新启用 `OutputConfiguration.setPhysicalCameraId()`；如需强制同源，应先做单独诊断模式，避免改变普通逻辑相机链路。

## 2026-08-28 YUV Native Preprocess Perf Update
- `yuv_preprocess.cpp` 是 YUV 数据源实时识别的优先预处理路径，当前实现复用线程本地 Letterbox 映射缓存，旋转分支已移出逐像素循环。
- 连续 YUV 检测时，`YoloDetector` 会让 native 复用已初始化的 Letterbox padding；从预览截图源切回 YUV 时会重新填充，避免污染。
- 1920x1080 YUV 输入到 1280x1280 模型时，性能判断以 `YOLO timing ... pre=` 为准；`inf=` 只代表 LiteRT/GMS 推理耗时。
- 详细复用规则见 `android-yuv-native-preprocess-fast-path`。

## 2026-08-28 Update
- luoji 包名下的 `YuvPreprocessNative.process()` 必须导出为 `Java_com_example_auto_1measure_1luoji_feature_camera_domain_YuvPreprocessNative_process`；旧 `auto_measure` 导出名会导致 YUV 模式运行时 `UnsatisfiedLinkError`。
- `YoloDetector.detectFromYuv()` 优先走 native 预处理，捕获 `LinkageError` 后回退 `YuvFrameProcessor.fillInputFromYuv()`，避免 JNI 绑定异常直接闪退。

## 适用场景
- 需要在同一个 Camera2 逻辑相机会话中切换 YOLO 输入来源：`TextureView.getBitmap()` 预览截图或 `ImageReader` 的 `YUV_420_888` 数据流。
- 需要让普通校准和双镜头校准复用同一采帧入口，并按当前 YOLO 数据源保存 `cal_yuv/cal_preview` 或 `dual_*_yuv/dual_*_preview` 图片。

## 禁止场景
- 禁止为了 YUV 数据源重新启用普通预览 `OutputConfiguration.setPhysicalCameraId()`；普通预览必须保持父逻辑相机 Session。
- 禁止在双摄 `strictPhysical=true` 物理会话里假定 YUV `ImageReader` 一定可用；当前物理绑定路径不创建分析流。
- Camera2 EIS 默认保持 OFF；用户显式选择 ON/PREVIEW 时必须记录坐标漂移风险。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `YoloFrameSource.PREVIEW_BITMAP` | enum | default | 使用 `TextureView.getBitmap(width,height)` 截图做 YOLO 输入 | 依赖 `TextureView.isAvailable` |
| `YoloFrameSource.IMAGE_READER_YUV` | enum | optional | 使用 `ImageReader.acquireLatestImage()` 的 YUV 帧做 YOLO 输入 | `analysisImageReader == null` 时必须提示不可用 |
| `KEY_YOLO_FRAME_SOURCE` | SharedPreferences key | `PREVIEW_BITMAP` | 持久化用户选择的数据源 | 切换后清空旧检测框 |
| `latestAnalysisImage` | `Image?` | `null` | 相机线程只缓存最新 YUV 帧 | 取走后置空，关闭相机前必须 close |

## 复用方式
- `Camera2Controller` 负责创建普通逻辑 Session 的 YUV `ImageReader`，监听器只保留最新一帧，通过 `acquireLatestAnalysisImage()` 交给 UI 消费。
- `CameraScreen` 的实时检测循环按 `YoloFrameSource` 分流：预览截图调用 `YoloDetector.detect(bitmap, postprocessOptions)`，YUV 数据流调用 `YoloDetector.detectFromYuv(image, rotation, postprocessOptions)`。
- 校准采集统一调用 `captureLaserFrameForCalibration()`，YUV 校准帧通过 `ImageUtils.normalizeYuv420ForCalibration()` 转为 `1080x1920` PNG bytes 后进入原有绿光检测链路。

## 注意事项
- YUV 帧消费后必须 `close()`，否则 Camera2 管线会被 `maxImages` 堵住。
- YUV 检测框映射使用旋转后的有效尺寸：`rotation` 为 90/270 时宽高互换。
- 复制或重命名 App 包名后，必须同步检查 `YuvPreprocessNative` 的 C++ JNI 导出名，或保留 Kotlin fallback。
- 预览截图源的 1080P/4K 是截图尺寸，不是模型 tensor 输入尺寸；模型输入仍由 `YoloDetector` 读取 TFLite tensor shape。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 已覆盖边界：预览截图默认源、YUV 源不可用提示、取走最新帧后置空、校准失败时 bitmap 回收。
- 未覆盖风险：不同厂商逻辑多摄 HAL 对 YUV 分析流和预览流的融合/切镜语义可能不同，仍需真机确认画面来源和坐标漂移。

## 给 AI 的指令
优先复用本条目的 canonical code path。除非哥哥明确要求重构，否则只做最小必要适配，不重新设计 YOLO 数据源、校准采帧或 Camera2 普通逻辑 Session。
