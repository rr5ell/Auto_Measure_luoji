# Speech2Text 开发指南

> Auto_measure 说明：本文件是 Speech2Text 参考项目的语音识别实现说明。Auto_measure 当前 Android/Kotlin 版本已接入 Sherpa-ONNX native 库；第三方模型就绪时优先使用 SenseVoice + Silero VAD 本地识别，未就绪时才回退系统 `SpeechRecognizer`。

本文档面向接手本项目的开发者，内容以当前代码实现为准。它不是 Sherpa-ONNX 通用教程，而是本仓库的开发交接说明。

## 1. 项目定位

Speech2Text 是一个 Flutter 离线语音识别应用，当前主流程为：

```text
长按录音 -> 16 kHz PCM16 音频流 -> Silero VAD 分段 -> SenseVoice OfflineRecognizer 识别 -> 文本清洗 -> 热词匹配 -> 展示/保存历史
```

关键结论：

- 识别引擎：`sherpa_onnx` + SenseVoice INT8。
- VAD：Silero VAD，通过 Sherpa-ONNX `VoiceActivityDetector` 接入。
- 识别方式：不是逐字实时 ASR，而是录音流实时进入 VAD，VAD 切出语音段后再离线解码。
- 热词方式：应用层字符串后处理，不使用 Sherpa-ONNX hotwords 文件。
- 模型：首次运行按需下载到应用文档目录，不打进 APK。

## 2. 关键文件

| 文件 | 作用 |
| --- | --- |
| `lib/home_screen.dart` | 主界面、录音、VAD、识别器生命周期、热词匹配、历史写入 |
| `lib/model_manager.dart` | SenseVoice / VAD 下载、解压、路径检查 |
| `lib/app_strings.dart` | 中/英/日/韩 UI 文案 |
| `lib/history_manager.dart` | 本地识别历史读写 |
| `docs/HOTWORDS.md` | 当前热词表说明 |
| `docs/项目概述.md` | 项目结构和整体流程 |
| `scripts/upload_pgyer.ps1` | 蒲公英 APK 上传脚本 |
| `android/settings.gradle` | Android Gradle Plugin 版本 |
| `android/gradle.properties` | Gradle / Kotlin 构建参数 |

## 3. 本地环境

当前项目依赖见 `pubspec.yaml`：

```yaml
dependencies:
  sherpa_onnx: ^1.13.0
  record: ^6.1.2
  path_provider: ^2.1.0
  permission_handler: ^12.0.3
  archive: ^3.6.0
  path: ^1.9.0
  dio: ^5.7.0
```

Android 构建配置：

```text
Gradle Wrapper: gradle-8.3-bin.zip
Android Gradle Plugin: 8.2.2
Kotlin Gradle Plugin: 1.9.24
compileSdk: 35
minSdk: 24
```

建议环境变量：

```powershell
[Environment]::SetEnvironmentVariable("GRADLE_USER_HOME", "E:\Android\.gradle", "User")
[Environment]::SetEnvironmentVariable("PUB_CACHE", "E:\Flutter\PubCache", "User")
```

如果 IDE 已经打开，设置环境变量后需要重启 VS Code / Android Studio。

## 4. 模型管理

模型由 `SherpaModelManager` 管理，入口在 `lib/model_manager.dart`。

当前使用资源：

| 资源 | 用途 | 大小 |
| --- | --- | --- |
| SenseVoice INT8 | 中/英/日/韩/粤语离线 ASR | 约 155 MB |
| Silero VAD | 语音活动检测 | 约 2 MB |

下载地址在 `model_manager.dart` 中维护。模型下载后存放到应用文档目录下的 `sherpa_models`，核心文件包括：

```text
sherpa_models/
|-- silero_vad.onnx
`-- sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/
    |-- model.int8.onnx
    `-- tokens.txt
```

开发时不要把模型文件提交到 Git，也不要放进 `assets`。

## 5. 识别器实现

当前识别器在 `lib/home_screen.dart` 的 `_prepareEngine()` 中创建。实际代码结构如下：

```dart
_recognizer = sherpa.OfflineRecognizer(
  sherpa.OfflineRecognizerConfig(
    model: sherpa.OfflineModelConfig(
      senseVoice: sherpa.OfflineSenseVoiceModelConfig(
        model: await _modelManager.getSenseVoiceModelPath(),
        language: _selectedLangCode,
        useInverseTextNormalization: false,
      ),
      tokens: await _modelManager.getSenseVoiceTokensPath(),
      numThreads: 2,
      debug: true,
    ),
  ),
);
```

注意：

- 当前使用 `OfflineRecognizer`，不是 `createOfflineRecognizer()`。
- 当前使用 `OfflineSenseVoiceModelConfig`，不是 `paraformer` 配置。
- `useInverseTextNormalization` 当前为 `false`。不要随意改成 `true`，否则识别文本形态可能变化，热词匹配会受影响。
- `numThreads` 当前为 2，优先保证端侧稳定性。

## 6. VAD 实现

VAD 在 `_rebuildVad()` 中创建：

```dart
final vadConfig = sherpa.VadModelConfig(
  sileroVad: sherpa.SileroVadModelConfig(
    model: vadModelPath,
    minSilenceDuration: isKorean ? 0.5 : 0.25,
    minSpeechDuration: isKorean ? 0.4 : 0.25,
    maxSpeechDuration: 30.0,
    threshold: isKorean ? 0.40 : 0.45,
  ),
  sampleRate: 16000,
  numThreads: 2,
  debug: true,
);
```

韩语参数比其他语言更宽松，目的是降低误切句概率。调整 VAD 参数时要实机测试，尤其关注：

- 开头是否被截断。
- 短词是否被过滤。
- 背景噪声是否触发误识别。
- 长句是否能在 30 秒内正常切段。

## 7. 录音实现

当前录音使用 `record` 插件的流式 PCM16：

```dart
const config = RecordConfig(
  encoder: AudioEncoder.pcm16bits,
  sampleRate: 16000,
  numChannels: 1,
  autoGain: true,
  noiseSuppress: true,
);

final stream = await _audioRecorder.startStream(config);
```

不要改成 `AudioEncoder.wav` + `start()`。当前流程依赖 `startStream()` 持续接收 PCM16 字节，再转换为 Float32 后送入 VAD。

音频处理流程：

```text
Uint8List PCM16 bytes -> Int16List -> Float32List(-1.0 到 1.0) -> 512 样本窗口 -> VAD
```

当 VAD 检测到完整语音段后，调用 `_processSegment(segment.samples)` 进行识别。

## 8. 识别段处理

识别段处理在 `_processSegment()`：

```dart
final stream = _recognizer!.createStream();
stream.acceptWaveform(samples: audioSamples, sampleRate: 16000);
_recognizer!.decode(stream);
final result = _recognizer!.getResult(stream);
stream.free();

final text = _formatKeywordRecognitionOutput(
  _applyTextFilter(result.text.trim()),
);
```

注意：

- 每个 VAD 段创建一个识别 stream。
- 识别完成后必须 `stream.free()`。
- 识别结果先清洗，再做热词匹配。
- 非空结果会追加到 `_recognizedText`，停止录音后写入历史。

## 9. 热词机制

热词在 `lib/home_screen.dart` 顶部维护，并按功能拆分：

```text
_chineseScanKeywords
_chineseDistanceKeywords
_koreanDistanceKeywords
_koreanPinCatcherKeywords
_englishMeasureKeywords
_englishPinCatcherKeywords
_japaneseDistanceKeywords
_japanesePinCatcherKeywords
```

当前标准输出值必须保持一致：

| 语言 | 功能 | 输出 |
| --- | --- | --- |
| 中文 | 扫描旗杆 | `ok_扫描旗杆` |
| 中文 | 测距 | `ok_测距` |
| 韩语 | 测距 | `ok_거리측정` |
| 韩语 | Pin Catcher | `ok_핀캐쳐` |
| 英语 | Measure | `ok_measure` |
| 英语 | Pin Catcher | `ok_pin catcher` |
| 日语 | 測定 | `ok_測定` |
| 日语 | Pin Catcher | `ok_ピンキャッチャー` |

不要使用 `ok_distance`、`ok_scan`、`ok_pin_catcher` 这类示例值，它们不是当前项目协议。

## 10. 热词匹配规则

匹配函数为 `_formatKeywordRecognitionOutput()`。

规则：

- 识别文本为空时直接返回。
- 中文：先匹配扫描，再匹配测距。
- 韩语：先匹配测距，再匹配 Pin Catcher。
- 英语：先转小写，先匹配 measure，再匹配 pin catcher。
- 日语：先匹配測定，再匹配 Pin Catcher。
- 命中规则为 `contains` 子串匹配。
- 未命中时返回清洗后的 ASR 原文。

新增热词时注意顺序：

- 长词应放在短词前面，降低短词抢先命中概率。
- 单字词如 `测`、`距`、`주`、`개` 误触风险高，新增前必须实测。
- 同一触发词不要出现在两个功能表中。
- 更新代码后同步更新 `docs/HOTWORDS.md`。

## 11. 文本清洗

文本清洗函数为 `_applyTextFilter()`。

当前行为：

- 去除中英文常见标点。
- 韩语识别语言下，只保留韩文音节/字母和空格。
- 韩语多空格会压缩为一个空格。

不要按 UI 语言判断清洗逻辑，应按 `_selectedLangCode` 判断。当前代码已经按识别语言处理。

## 12. 新增热词流程

1. 在 `lib/home_screen.dart` 找到对应语言和功能的 Map。
2. 添加识别变体，格式为：

```dart
'识别出来的文本': '标准输出',
```

3. 长词放在短词前。
4. 同步更新 `docs/HOTWORDS.md`。
5. 运行检查：

```powershell
flutter analyze --no-pub
flutter test --no-pub
```

6. 如果涉及 Android 构建，运行：

```powershell
$env:GRADLE_USER_HOME="E:\Android\.gradle"
$env:PUB_CACHE="E:\Flutter\PubCache"
cd E:\StudioProjects\Speech2Text\android
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

## 13. 构建与发布

Debug 构建：

```powershell
flutter build apk --debug
```

Release 构建：

```powershell
flutter build apk --release
```

Debug APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

上传蒲公英：

```powershell
.\scripts\upload_pgyer.ps1 `
  -ApkPath "E:\StudioProjects\Auto_Measure_luoji\app\build\outputs\apk\debug\app-debug.apk" `
  -UpdateDescription "0.8.29 纯UI放大逻辑相机底倍修复"
```

脚本默认读取用户环境变量 `PGYER_API_KEY`。不要把 API Key 写进脚本、README 或提交记录。

## 14. 常见问题

### Android 构建报 `JdkImageTransform` 或 `jlink.exe`

原因通常是 Java 21 + 旧 Android Gradle Plugin 组合触发已知问题。当前项目已升级到 AGP `8.2.2`。如仍报错，先确认：

```text
android/settings.gradle
android/gradle.properties
GRADLE_USER_HOME
```

### Kotlin 报 `this and base files have different roots`

通常是 Pub Cache 在 C 盘、项目在 E 盘导致 Kotlin 增量编译计算相对路径失败。当前项目已设置：

```properties
kotlin.incremental=false
```

建议同时设置：

```powershell
[Environment]::SetEnvironmentVariable("PUB_CACHE", "E:\Flutter\PubCache", "User")
```

### 蒲公英上传 `AccessDenied` 或 403

当前脚本已兼容蒲公英 `getCOSToken` 的 `data.params` 返回结构。如果仍失败：

- 确认 `PGYER_API_KEY` 是当前账号的 Key。
- 确认账号已实名认证。
- 确认 APK 路径存在。
- 确认网络没有拦截 COS 上传地址。

### 热词命中错误

优先检查：

- 短词是否在长词前面。
- 同一词是否出现在多个功能 Map。
- 韩语清洗后是否改变了空格。
- 英文是否需要小写变体；当前英文会统一转小写。

## 15. 交接 Checklist

开发者接手前应确认：

- `flutter pub get` 成功。
- `flutter analyze --no-pub` 无问题。
- `flutter test --no-pub` 通过。
- Android Debug 或 Release 至少构建一次成功。
- 设备首次运行能下载 SenseVoice 和 VAD 模型。
- 四种识别语言切换正常。
- 热词命中输出与本指南第 9 节一致。
- 新增热词时同步更新 `docs/HOTWORDS.md`。

## 16. 参考文档

- [项目概述](./项目概述.md)
- [热词配置文档](./HOTWORDS.md)
- [关于 VAD](./关于VAD.md)
- [模型训练与语音识别说明](./语音识别_模型训练.md)
- [商用与模型许可说明](./关于是否可以商用&怎么用.md)

---

**文档版本**: v2.0
**适用项目版本**: `speech_to_text_sherpa` 2.0.1+1
**更新日期**: 2026-07-07
