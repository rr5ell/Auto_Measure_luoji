# Auto_Measure_luoji 代码资产索引

本目录记录 Auto_Measure_luoji 项目内已经验证或正在沉淀的可复用逻辑。后续修改代码前，先读取本索引，优先复用 canonical code path。

## 项目信息

| 字段 | 内容 |
|---|---|
| project_id | `auto-measure-luoji` |
| 项目名称 | Auto_Measure_luoji |
| 项目根目录 | `E:\StudioProjects\Auto_Measure_luoji` |
| 应用类型 | Android Kotlin / Jetpack Compose 自动测量 App |
| 当前主要分支 | `feature/manual-shutter-aperture` |

## 可复用能力
- [android-camera2-tap-metering](entries/android-camera2-tap-metering.md)：点击 AE/AF 同区域、普通/GL 坐标反算、单次对焦触发、5秒恢复及真实曝光/AF 状态；三星模式3/4和探针已移除，禁止复用其历史实现。
- [android-camera-backlight-balance](entries/android-camera-backlight-balance.md)：五区截尾亮度统计、对数域综合测光、受限正负 EV 闭环、中心保护、14项可持久化判断参数面板、自动 HDR 隔离及局部预览映射。
- [android-camera2-center-metering](entries/android-camera2-center-metering.md)：Camera2 中心 AE 区域、变焦/宽高比换算、模板恢复、能力回退及持久化开关。
- [android-preview-glare-suppression](entries/android-preview-glare-suppression.md)：显示端受限白雾扣除、平坦区与高光保护、普通/GL 共享 Shader 和互斥设置。
- [android-preview-local-tone-retinex](entries/android-preview-local-tone-retinex.md)：保边局部色调映射、单尺度 Retinex、普通/GL 显示端共用邻域滤波及互斥开关。
- [android-preview-shadow-enhancement](entries/android-preview-shadow-enhancement.md)：预览端非线性暗部增益、AGSL/GL 共用曲线、校准暂停与原始取帧隔离。

- [android-camera-manual-image-adjustments](entries/android-camera-manual-image-adjustments.md)：手动 EV/快门、硬件光圈档位、固定 ISO、显示端对比度、自动曝光恢复与 HDR/点击测光互斥。

- [android-camera2-auto-hdr-exposure](entries/android-camera2-auto-hdr-exposure.md)：曝光阈值自动 HDR、四条主面板同步采样确认、双向停判、故障暂停保留自动开关、手动互斥及参数持久化。

- [android-camera2-exposure-display](entries/android-camera2-exposure-display.md)：实际曝光快照、逻辑/物理来源、旧会话过滤和实时主面板显示。

- [android-sensor-ambient-light](entries/android-sensor-ambient-light.md)：TYPE_LIGHT 实时照度、前台监听、500 ms 合并事件和主面板显示。
- [android-camera2-low-light-luminance](entries/android-camera2-low-light-luminance.md)：独立只读低光增强亮度范围诊断；不再接入主面板，不开启低光增强。

| reuse_id | 能力 | 标签 | canonical code path | 状态 |
|---|---|---|---|---|
| android-camera2-ois-toggle | Camera2 OIS 显式开关 | android, kotlin, camera2, ois, stabilization | `app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/Camera2Controller.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt` | active |
| android-camera2-eis-experimental-toggle | Camera2 EIS 实验模式开关 | android, kotlin, camera2, eis, video-stabilization, hardware-control | `app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/Camera2Controller.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt` | deprecated |
| android-gyro-preview-stabilization | 应用层陀螺仪预览防抖 | android, kotlin, camera2, gyroscope, textureview, opengl, stabilization, calibration | `app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/GyroPreviewStabilizer.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/GlStabilizedPreviewView.kt` | active |
| android-camera2-calibration-ev-compensation | 校准期间 Camera2 AE 曝光补偿 | android, kotlin, camera2, ae, exposure-compensation, calibration | `app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/Camera2Controller.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt` | active |
| android-system-speech-hotword-loop | 中文语音热词识别循环 | android, kotlin, speech-recognition, restart-loop, hotword-matching | `app/src/main/java/com/example/auto_measure_luoji/feature/voice/domain/ChineseVoiceHotwordRecognizer.kt` | active |
| android-voice-model-resumable-downloader | 第三方语音模型断点续传下载 | android, kotlin, http-range, model-download, speech-recognition | `app/src/main/java/com/example/auto_measure_luoji/feature/voice/domain/ThirdPartyVoiceModelDownloader.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/voice/domain/ChineseVoiceHotwordRecognizer.kt` | active |
| android-sherpa-sensevoice-vad-hotword-engine | Android Sherpa-ONNX SenseVoice + Silero VAD 热词识别 | android, kotlin, c++, jni, offline-asr, vad, hotword-matching | `app/src/main/java/com/example/auto_measure_luoji/feature/voice/domain/SherpaOnnxHotwordRecognizer.kt`; `app/src/main/cpp/sherpa_hotword_jni.cpp` | active |
| android-laser-detector-bytes-input | 绿光识别归一化图片 bytes 输入链路 | android, kotlin, image-processing, laser-detection, bytes-input, coordinate-map | `app/src/main/java/com/example/auto_measure_luoji/feature/calibration/detector/LaserDetector.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/calibration/domain/CalibrationManager.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt` | active |
| android-laser-detection-compare-panel | 绿光新旧算法对比 App 与面板 | android, kotlin, compose, laser-detection, debug-tool, build-type, gallery-output | `app/build.gradle.kts`; `app/src/laserCompare/res/values/strings.xml`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/calibration/util/ImageUtils.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/calibration/detector/LaserDetector.kt` | active |
| android-ble-distance-state-notification | BLE 测距数据事件通知与自动采集延迟 | android, kotlin, ble, compose-state, distance-measurement, calibration-delay | `app/src/main/java/com/example/auto_measure_luoji/feature/bluetooth/domain/BleConnectionManager.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/calibration/ui/CalibrationPanel.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/calibration/ui/DualCalibrationPanel.kt` | active |
| android-yolo-capture-resolution-toggle | YOLO TextureView 图源 1080P/4K 切换 | android, kotlin, compose, yolo, textureview, model-input | `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/detection/domain/YoloDetector.kt` | active |
| android-yolo-frame-source-toggle | YOLO 预览截图/YUV 数据源切换 | android, kotlin, camera2, imagereader, yuv, textureview, yolo, calibration, jni | `app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/Camera2Controller.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/calibration/util/ImageUtils.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/detection/domain/YoloDetector.kt`; `app/src/main/cpp/yuv_preprocess.cpp` | active |
| android-yolo-postprocess-threshold | YOLO 旗杆后处理阈值与单框策略 | android, kotlin, compose, yolo, nms, threshold, flag-detection | `app/src/main/java/com/example/auto_measure_luoji/feature/detection/domain/YoloDetector.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt` | active |
| android-yuv-native-preprocess-fast-path | YOLO YUV native 预处理快路径 | android, c++, jni, camera2, imagereader, yuv, yolo, image-processing, model-input | `app/src/main/cpp/yuv_preprocess.cpp`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/YuvPreprocessNative.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/detection/domain/YoloDetector.kt` | active |
| pgyer-apk-upload-powershell | 蒲公英 APK 上传脚本 | powershell, pgyer, apk, release | `scripts/upload_pgyer.ps1` | active |
| android-camera-top-action-two-row-layout | 相机页顶部按钮两行布局 | android, kotlin, compose, camera-ui, toolbar-layout | `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt` | superseded |
| android-camera-top-action-collapsible-menu | 相机页顶部按钮折叠菜单与广角切换 | android, kotlin, compose, camera-ui, toolbar-layout, lens-switch | `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt` | active |
| android-camera2-control-zoom-ratio-preview-zoom | Camera2 CONTROL_ZOOM_RATIO 倍率放大 | android, kotlin, camera2, zoom, control-zoom-ratio, coordinate-map | `app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/Camera2Controller.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/util/ZoomController.kt` | active |
| android-camera-ui-zoom-only-mode | 纯 UI 放大实验开关 | android, kotlin, compose, camera-ui, zoom, calibration, coordinate-map | `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt` | active |
| android-camera2-active-physical-lens-display | 当前画面活跃物理镜头焦距显示 | android, kotlin, camera2, logical-multi-camera, active-physical-id, lens-display | `app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/Camera2Controller.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/data/LensScanner.kt` | active |
| android-camera2-physical-preview-output-binding | 逻辑相机 Session 中绑定物理预览输出 | android, kotlin, camera2, logical-multi-camera, physical-output, fusion-avoidance | `app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/Camera2Controller.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt` | deprecated |

## HDR 与 LSC

- [android-preview-fps](entries/android-preview-fps.md)：普通预览与 GL/HDR 共用的新画面 FPS 统计，标准路径 `feature/camera/domain/previewfps/PreviewFrameRate.kt`。

- [android-gl-yuv-frame-detection](entries/android-gl-yuv-frame-detection.md)：普通 GL 的 YUV 异步识别、来源元数据、采样逆映射及 200 ms 结果过期策略；标准路径为 `app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/gldetection/GlDetectionCoordinates.kt`、`app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/gldetection/GlAsyncDetectionState.kt` 和 `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/GlStabilizedPreviewView.kt`。

| reuse_id | 能力 | 标准代码路径 | 状态 |
|---|---|---|---|
| android-camera2-hlg-preview | HLG10 逻辑相机预览与同帧 GL 显示 | `app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/hdr/HdrPreviewCapabilities.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/hdr/HlgPreviewGl.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/GlStabilizedPreviewView.kt` | active |
| android-camera2-lsc-toggle | LSC 能力检查、模式持久化及请求回退 | `app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/Camera2Controller.kt`; `app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt` | active |

## 参考资产

- 全局资产 `flutter-sherpa-sensevoice-vad-hotwords`：`E:\StudioProjects\Speech2Text\docs\code-library\INDEX.md`
- 参考文档：`E:\StudioProjects\Auto_Measure_luoji\HOTWORDS.md`
- VAD 参考文档：`E:\StudioProjects\Auto_Measure_luoji\关于VAD.md`

## 最后更新
2026-09-22，Codex Agent。按用户要求删除三星私有模式代码，点击测光增加独立区域 AF 触发与恢复；标准路径见 TapFocusRequest。107 项单测、构建、底层仪器测试通过，普通/GL 实际合焦、重触发和恢复回归通过；HDR 手动验证受锁屏/防误触阻挡未完成。

以下三星实验代码现已删除，仅保留验证历史：
2026-09-22，Codex Agent。新增原值/模式3/模式4单选面板与非法值拦截；107 项单测、构建、三星探针通过，UI 解锁后重跑通过，HDR 请求3/回传3和单选面板截图已验证。

以下为模式4开关验证历史：
2026-09-22，Codex Agent。三星模式4实验开关已接入更多菜单和共用相机请求路径，独立 SamsungMeteringRequest 管理恢复；107 项单测、构建和 2 项三星仪器测试通过，HDR 请求/回传与截图已人工检查，不启用 EIS。

以下为先前探针记录：
2026-09-22，Codex Agent。新增独立三星私有测光探针，SM_S9480 实测模式 0 -> 4 -> 0 回传成功，曝光均 9.991324 ms / ISO 72；无效果变化证据。构建通过、默认跳过通过，正式应用代码不变，不启用 EIS。

以下为历史验证记录，不代表当前恢复版本：

2026-09-22，Codex Agent。点击测光改为完整预览 AE 与受限点击微调，增加全局亮暗保护、迟滞和实际状态显示。120 项单测及构建通过；三星区域/曝光读数、模拟器普通/GL 点击流程通过，真机完整点击/HDR 仍待复测；额外 GL 同帧回归时序断言失败见错误报告。不启用 EIS。
