# android-system-speech-hotword-loop

## Metadata

- status: active
- tech: Android, Kotlin
- tags: speech-recognition, hotword-matching, restart-loop
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\voice\domain\ChineseVoiceHotwordRecognizer.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\HOTWORDS.md
  - E:\StudioProjects\Auto_Measure_luoji\关于VAD.md
- last_updated: 2026-08-03
- author: Codex Agent

## 适用场景

- 在 Auto_Measure_luoji 中识别中文“测距”“扫描”“扫描旗杆”，并只提示命中类别。
- 当第三方 Sherpa-ONNX 模型未就绪或第三方启动失败时，使用 Android 系统语音服务做 fallback。

## 禁止场景

- 不适合要求完全离线、完全可控端点检测、固定模型输出一致性的场景。
- 不得直接把热词命中绑定到硬件测距或自动扫描动作，除非哥哥明确确认防误触策略。

## 核心参数

| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `CHINESE_LANGUAGE` | String | `zh-CN` | 系统语音识别目标语言 | 系统服务可能不支持 |
| `VAD_MAX_SEGMENT_MS` | Long | `30000` | 参考 VAD 的最长语音段 | 超时后重启会话 |
| `CANCEL_RELEASE_DELAY_MS` | Long | `1200` | `cancel()` 后等待系统语音服务释放会话 | 过短会触发系统 busy |
| `BUSY_RESTART_DELAY_MS` | Long | `1500` | 系统返回 busy 后的退避重启时间 | 过短会连续刷 `startListening called while listening is in progress` |
| `LANGUAGE_ERROR_RESTART_DELAY_MS` | Long | `2000` | 默认识别返回语言类错误后的退避时间 | 避免语言错误循环过快 |
| `HIT_DEBOUNCE_MS` | Long | `1800` | 同类热词防抖 | 过短会重复提示 |

## 复用方式

- 通过 `ChineseVoiceHotwordRecognizer.start()` / `stop()` 控制持续监听。
- 命中结果通过 `onHotwordHit` 回调给 UI。
- UI 只展示类别，不触发业务动作。

## 注意事项

- 不直接设置系统 `SpeechRecognizer` 的 250ms 静音端点，也不启用 segmented session；实测会破坏部分设备上的热词识别。
- 当前实现通过系统结果/错误回调重启下一轮，并用 30 秒 watchdog 防止长时间卡住；重启必须经过 pending start 互斥。
- `SpeechRecognizer.cancel()` 后必须等待 `CANCEL_RELEASE_DELAY_MS`，避免系统服务尚未释放时重复 `startListening()`。
- `checkRecognitionSupport` 返回 `ERROR_CANNOT_CHECK_SUPPORT=14` 时必须直接 fallback 到系统默认识别，禁止继续启动离线识别。
- ERROR_LANGUAGE_NOT_SUPPORTED=12 时会回退到系统默认中文识别，并持久化记录系统离线包不可用，后续启动不再反复优先触发离线包。
- ERROR_LANGUAGE_NOT_SUPPORTED=12 时允许继续下载第三方 SenseVoice/VAD 语音包；下载和解压由 `android-voice-model-resumable-downloader` 负责。
- 默认/在线识别 Intent 不传 `EXTRA_PREFER_OFFLINE=false` 和 `EXTRA_LANGUAGE_PREFERENCE`；这些参数只在明确离线识别时使用。
- 语音错误排查优先过滤 `ChineseVoiceHotword`，再结合系统 `RemoteSpeechRecognitionService` / `SodaSpeechRecognizer` 日志判断。
- Sherpa-ONNX SenseVoice + Silero VAD 主路径见 `android-sherpa-sensevoice-vad-hotword-engine`；本条目只作为系统 fallback。

## 测试与验证

- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug`
- 已覆盖边界：语言包不支持、权限缺失、未命中、会话重启、系统 busy 退避、离线包错误持久化。
- 未覆盖风险：不同厂商系统语音服务对 segmented session 和静音参数的支持差异。

## 给 AI 的指令

优先复用本条目的 canonical code path 作为系统 fallback。第三方模型就绪时不得强制回到只使用系统 `SpeechRecognizer`。
