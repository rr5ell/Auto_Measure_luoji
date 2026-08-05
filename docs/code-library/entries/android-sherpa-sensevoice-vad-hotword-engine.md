# android-sherpa-sensevoice-vad-hotword-engine

## Metadata
- status: active
- tech: Android | Kotlin | C++
- tags: offline-asr, speech-recognition, vad, sensevoice, sherpa-onnx, jni, hotword-matching
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure\feature\voice\domain\ChineseVoiceHotwordRecognizer.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure\feature\voice\domain\SherpaOnnxHotwordRecognizer.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure\feature\voice\domain\SherpaOnnxHotwordNative.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\cpp\sherpa_hotword_jni.cpp
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\cpp\CMakeLists.txt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
  - E:\StudioProjects\Auto_Measure_luoji\DEV_GUIDE.md
  - E:\StudioProjects\Auto_Measure_luoji\关于VAD.md
- last_updated: 2026-08-03
- author: Codex Agent

## 适用场景
- Android Kotlin App 需要绕开系统 `SpeechRecognizer`，使用本地 SenseVoice + Silero VAD 做中文热词识别。
- 第三方模型已经下载并解压到应用私有目录，需要持续监听“测距”“扫描”“扫描旗杆”。
- 设备没有可用系统语音识别服务时，仍需要通过第三方语音包下载入口准备本地模型。

## 禁止场景
- 模型权重未就绪时禁止启动 Sherpa 本地识别；应提示下载或回退系统识别。
- 禁止把 SenseVoice / Silero VAD 模型权重打进 APK；仅 native 推理库随包携带。
- 禁止把系统语言包下载接口当成第三方模型下载入口。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `sampleRate` | Int | `16000` | AudioRecord、VAD、SenseVoice 输入采样率 | 必须单声道 PCM16 |
| `vad.windowSize` | Int | `512` | Silero VAD 每次输入窗口 | 录音循环按 512 samples 读取 |
| `vad.threshold` | Float | `0.45` | 非韩语 VAD 阈值 | 噪声环境需真机调整 |
| `vad.minSilenceDuration` | Float | `0.25` | 静音切句阈值，单位秒 | 过短会截断，过长会延迟 |
| `vad.minSpeechDuration` | Float | `0.25` | 最短有效语音，单位秒 | 太短会过滤短词 |
| `vad.maxSpeechDuration` | Float | `30.0` | 单段最长语音，单位秒 | 过长会占用识别线程 |
| `numThreads` | Int | `2` | SenseVoice / VAD 推理线程数 | 端侧优先稳定 |
| `use_itn` | Int | `0` | SenseVoice inverse text normalization | 保持热词文本形态稳定 |

## 复用方式
- `ChineseVoiceHotwordRecognizer.start()` 先检查 `ThirdPartyVoiceModelDownloader.isReady()`；就绪则启动 `SherpaOnnxHotwordRecognizer`，未就绪才回退系统 `SpeechRecognizer`。
- 如果系统 `SpeechRecognizer.isRecognitionAvailable()` 为 false 且模型未就绪，必须发布 `fallbackActive=true` 的下载状态，提示下载第三方包。
- `SherpaOnnxHotwordRecognizer` 负责 `AudioRecord -> FloatArray -> native VAD -> SenseVoice result`，热词命中仍在 Kotlin 层做字符串后处理。
- `sherpa_hotword_jni.cpp` 只保留最小 JNI：create、acceptWaveform、flush、destroy。
- 在 `apply` 中绑定 `SherpaOnnxHotwordRecognizer` 回调时，必须使用 `this@ChineseVoiceHotwordRecognizer` 显式转发到外层，禁止写成 `onStatusChanged = { onStatusChanged?.invoke(it) }` 这种自递归形式。

## 注意事项
- APK 会因为 `app/src/main/jniLibs` 中的 Sherpa-ONNX ABI 库显著变大；后续可按 ABI 拆包或改为动态下载 native 库。
- `System.loadLibrary` 需要先加载 `onnxruntime`、`sherpa-onnx-cxx-api`、`sherpa-onnx-c-api`，最后加载 `sherpa_hotword_jni`。
- 调试时过滤 `ChineseVoiceHotword|SherpaHotword`，确认状态出现 `第三方语音识别中（SenseVoice+VAD）`。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug`
- 测试命令：`.\gradlew.bat :app:assembleLaserCompare`
- 已覆盖边界：系统识别懒加载、第三方优先、模型解压就绪校验、停止释放、回调转发不自递归、四 ABI native 构建。
- 未覆盖风险：真机端首次加载 155MB SenseVoice 模型的耗时和内存峰值仍需在目标 Samsung 设备上验证。

## 给 AI 的指令
优先复用本条目的 canonical code path。除非哥哥明确要求换引擎，否则不要回退成只使用系统 `SpeechRecognizer` 的实现。
