# Auto_Measure_luoji 代码资产索引

本目录记录 Auto_Measure_luoji 项目内已经验证或正在沉淀的可复用逻辑。后续修改代码前，先读取本索引，优先复用 canonical code path。

## 项目信息

| 字段 | 内容 |
|---|---|
| project_id | `auto-measure-luoji` |
| 项目名称 | Auto_Measure_luoji |
| 项目根目录 | `E:\StudioProjects\Auto_Measure_luoji` |
| 应用类型 | Android Kotlin / Jetpack Compose 自动测量 App |
| 当前主要分支 | `release/pgyer-0-8-32` |

## 可复用能力

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

2026-09-17，Codex Agent。构建版本递增为 0.8.32 (832)，复用现有蒲公英上传脚本；沿用已验证的 HDR 阈值入口修复，不改变核心算法。
