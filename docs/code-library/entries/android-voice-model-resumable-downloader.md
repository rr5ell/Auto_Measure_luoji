# android-voice-model-resumable-downloader

## Metadata
- status: active
- tech: Android | Kotlin
- tags: speech-recognition, model-download, http-range, resumable-download
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure\feature\voice\domain\ThirdPartyVoiceModelDownloader.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure\feature\voice\domain\ChineseVoiceHotwordRecognizer.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\DEV_GUIDE.md
  - E:\StudioProjects\Auto_Measure_luoji\关于VAD.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-08-03
- author: Codex Agent

## 适用场景
- 系统 `SpeechRecognizer.triggerModelDownload()` 返回不支持下载时，需要 App 自己下载第三方语音模型文件。
- 系统 `SpeechRecognizer.isRecognitionAvailable()` 返回 false，且第三方模型未就绪时，需要直接提示第三方包下载，而不是只报“语音识别不可用”。
- 下载大体积模型文件时，需要弱网中断后继续下载，而不是从头重下。

## 禁止场景
- 禁止把“第三方压缩包已下载”描述为“第三方模型已可用”；必须解压并校验 `silero_vad.onnx`、`model.int8.onnx`、`tokens.txt`。
- 禁止把密钥、私有下载地址、Cookie 或账号信息写入本条目或源码。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `MODELS_DIR_NAME` | String | `sherpa_models` | 应用私有目录下的模型根目录 | 不需要外部存储权限 |
| `PART_SUFFIX` | String | `.part` | 未完成下载的临时文件后缀 | 完成后 rename 为正式文件 |
| `Range` | HTTP Header | `bytes=<已下载>-` | 断点续传请求 | 服务器返回 200 时从头重下 |
| `VAD_FILE_NAME` | String | `silero_vad.onnx` | Silero VAD 文件 | 必须存在才允许 Sherpa 启动 |
| `SENSE_VOICE_ARCHIVE_NAME` | String | `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2` | SenseVoice INT8 模型压缩包 | 下载后必须解压 |
| `SENSE_VOICE_MODEL_FILE_NAME` | String | `model.int8.onnx` | SenseVoice 推理模型 | 必须位于解压目录 |
| `SENSE_VOICE_TOKENS_FILE_NAME` | String | `tokens.txt` | SenseVoice tokens | 必须位于解压目录 |

## 复用方式
- 通过 `ThirdPartyVoiceModelDownloader.downloadAll()` 下载 VAD 与 SenseVoice 文件，并解压 `.tar.bz2`。
- UI 只接收 `ThirdPartyVoiceModelProgress`，不要直接操作 `.part` 文件。
- `ChineseVoiceHotwordRecognizer` 启动时优先检查 `isReady()`；模型就绪后直接启动 Sherpa 本地识别，未就绪才回退系统识别。
- `VoiceModelDownloadState.fallbackActive=true` 表示当前弹框是第三方包入口，确认按钮必须调用 `requestThirdPartyVoiceModelDownload()`，不能调用系统 `requestChineseLanguageModelDownload()`。
- 旧版本已下载 `.tar.bz2` 但未解压时，调用 `prepareDownloadedPackageIfPossible()` 只解压现有包，不静默触发大文件下载。

## 注意事项
- `AndroidManifest.xml` 必须声明 `android.permission.INTERNET`。
- 下载目录使用 `context.filesDir/sherpa_models`，属于应用私有目录，卸载 App 会清理。
- 弱网中断后保留 `.part`，再次点击下载会续传；服务器不支持 Range 时会自动重下。
- 解压时必须做 canonical path 校验，避免 tar 包路径穿越。
- 系统语音服务不可用时，不要调度系统识别重启；只能提示第三方包下载或等待第三方包下载完成后启动 Sherpa 本地识别。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug`
- 测试命令：`.\gradlew.bat :app:assembleLaserCompare`
- 已覆盖边界：系统语音服务不可用、系统语言包不支持、第三方下载进度、断点续传文件保留、服务器不接受 Range 时从头重下、SenseVoice 解压和核心文件就绪校验。
- 未覆盖风险：真机弱网续传、GitHub Release 镜像可达性、解压耗时和存储空间不足。

## 给 AI 的指令
优先复用本条目的 `ThirdPartyVoiceModelDownloader`。第三方语音模型只有在解压后的核心文件都存在时才视为可用。
