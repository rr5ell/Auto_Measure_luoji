# Auto_Measure_luoji 技术栈

## Android

| 技术 | 当前值 | 版本来源 |
|---|---|---|
| Kotlin | `2.0.21` | `gradle/libs.versions.toml` |
| Android Gradle Plugin | `8.12.3` | `gradle/libs.versions.toml` |
| compileSdk | `36` | `app/build.gradle.kts` |
| minSdk | `24` | `app/build.gradle.kts` |
| targetSdk | `36` | `app/build.gradle.kts` |
| Jetpack Compose BOM | `2024.09.00` | `gradle/libs.versions.toml` |
| UI | Jetpack Compose Material3 | `app/build.gradle.kts` |
| Camera | Android Camera2 API + TextureView/GL 预览 | `feature/camera/domain/Camera2Controller.kt`; `feature/camera/ui/CameraScreen.kt`; `feature/camera/ui/GlStabilizedPreviewView.kt` |
| 应用层预览防抖 | Android SensorManager gyroscope + TextureView matrix / OpenGL OES texture | `feature/camera/domain/GyroPreviewStabilizer.kt`; `feature/camera/ui/CameraScreen.kt`; `feature/camera/ui/GlStabilizedPreviewView.kt` |
| HDR 预览 | Android 13+ HLG10 + OpenGL ES 3 / RGB10_A2 | `feature/camera/domain/hdr/HdrPreviewCapabilities.kt`; `feature/camera/ui/hdr/HlgPreviewGl.kt` |
| 低光增强能力诊断 | Android 15+ / API 35，只读亮度范围 | `feature/camera/domain/lowlight/LowLightBoostCharacteristics.kt`；compileSdk 来源 `app/build.gradle.kts` |
| 环境照度 | Android SensorManager / TYPE_LIGHT，无新增依赖；前台监听 | `feature/camera/domain/ambientlight/AmbientLightMonitor.kt`；SDK 版本来源 `app/build.gradle.kts` |
| 视觉运动估计 | OpenCV `4.12.0`，LK/RANSAC | `app/build.gradle.kts`; `feature/camera/domain/PreviewMotionEstimator.kt` |
| BLE | Android BLE API | `feature/bluetooth/domain/BleConnectionManager.kt` |
| YOLO 推理 | LiteRT 1.4.2 + GMS TFLite fallback；创建/推理/释放使用 YoloInference 专用线程 | `app/build.gradle.kts`; `feature/camera/ui/CameraScreen.kt` |
| 语音热词 | Sherpa-ONNX SenseVoice + Silero VAD 优先，Android `SpeechRecognizer` fallback | `feature/voice/domain/ChineseVoiceHotwordRecognizer.kt`; `feature/voice/domain/SherpaOnnxHotwordRecognizer.kt` |
| 语音模型下载 | HTTPS + HTTP Range 断点续传 + `.tar.bz2` 解压 | `feature/voice/domain/ThirdPartyVoiceModelDownloader.kt`; `AndroidManifest.xml` |
| 压缩包解压 | Apache Commons Compress 1.28.0 | `app/build.gradle.kts` |
| 构建变体 | `debug`, `laserCompare`, `release` | `app/build.gradle.kts` |

## Native

| 技术 | 当前值 | 版本来源 |
|---|---|---|
| CMake | `3.22.1` | `app/build.gradle.kts` |
| Native 模块 | `yuv_preprocess`, `sherpa_hotword_jni` | `app/src/main/cpp/CMakeLists.txt` |
| Sherpa-ONNX native | `1.13.4` Android ABI libraries | `app/src/main/jniLibs`; `app/src/main/cpp/sherpa_onnx/c-api.h` |

## 注意事项

- 五区测光核心：`feature/camera/domain/backlightbalance/BacklightMeteringAlgorithm.kt`为独立Kotlin/JVM单文件，仅使用标准库及ByteBuffer，无Android/Compose依赖；编译器版本仍取上表2.0.21，独立验证目标字节码为JVM 1.8。相机接入和偏好存储保留在Android侧，源码输入输出契约与说明见`docs/integration/backlight-metering.md`。

- 预览增强：Android 13+ RuntimeShader/AGSL（无新增依赖），GL/HLG10 使用现有 OpenGL ES Shader；暗部曲线位于 feature/camera/ui/backlightshadow，局部色调映射/Retinex 共用邻域位于 feature/camera/ui/localtone，SDK 来源 app/build.gradle.kts。

- 当前项目随包携带 Sherpa-ONNX native 推理库，但不把 SenseVoice / Silero VAD 模型权重打进 APK；模型仍由 `ThirdPartyVoiceModelDownloader` 下载到应用私有目录。
- YOLO 可在 `TextureView.getBitmap()` 预览截图源与 Camera2 `ImageReader` 的 `YUV_420_888` 数据源之间切换；YUV 实时识别复用 native `yuv_preprocess` 预处理链路。
- YOLO 后处理通过 `YoloPostprocessOptions` 统一传入置信度阈值和单框/多框策略，PreviewBitmap 与 YUV 数据源不得各自维护重复阈值常量。
- 禁止启用 Camera2 系统 EIS；仅允许 OIS 或应用层物理补偿，所有请求固定 `CONTROL_VIDEO_STABILIZATION_MODE_OFF`。
- 自研 EIS 是应用层显示矩阵防抖，依赖 `HIGH_SAMPLING_RATE_SENSORS`、陀螺仪采样和 `CameraFrameTiming`；补偿模型按 `光学倍率 * Camera2 数码倍率 * 显示层倍率` 计算，普通预览不绑定物理输出 Surface。
- Camera2 zoom 后端以 `zoom: backend=...` 形式输出到 Logcat 和主界面状态区。
