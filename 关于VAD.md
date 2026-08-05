# 关于 VAD

> **VAD（Voice Activity Detection）** = 语音活动检测，判断「哪里开始说话、哪里结束」。  
> 本项目使用 **Silero VAD**（`silero_vad.onnx`，约 2 MB），通过 Sherpa-ONNX 的 `VoiceActivityDetector` 接入。  
> 存储路径：`{应用文档目录}/sherpa_models/silero_vad.onnx`

---

## 什么是 VAD

VAD 是语音识别前的「**切句器**」：从连续录音中切出有效语段，再交给 ASR 模型识别。

```
[静音] 你好 [停顿] 今天天气不错 [停顿] 谢谢 [静音]
         ↓ VAD 切句
语段 1: "你好"  →  语段 2: "今天天气不错"  →  语段 3: "谢谢"
```

没有 VAD，离线识别器不知道一句话何时结束，容易出现断句混乱、误识别噪音。

---

## 为什么要加 VAD

| 原因 | 说明 |
|------|------|
| 离线模型需先切句 | SenseVoice 用 `OfflineRecognizer`，必须整段送入，VAD 负责找句界 |
| 过滤静音和噪音 | 减少无效音频进入识别器 |
| 适配长按录音 | 一次录音可自动切多句；松手时 `vad.flush()` 处理剩余片段 |
| 嘈杂环境更稳 | VAD 先切句再识别，减少无效片段 |

**VAD 与 ASR 分工不同：** VAD 找句界，SenseVoice 把语音转成文字。

---

## 识别流程

参考 Speech2Text 项目使用 **SenseVoice + VAD** 单一路线：

| 组件 | 作用 |
|------|------|
| Silero VAD | 从 PCM 流中切出语段 |
| SenseVoice | 对每个语段离线解码成文字 |

**模型下载：** VAD 只需下载一次（~2 MB），与 SenseVoice 共用，首次启动一并拉取。

### Auto_measure 当前适配说明

Auto_measure 当前 Android/Kotlin 版本已经接入 Sherpa-ONNX native 库、Silero VAD 和 SenseVoice 本地识别链路。

当前实现：

- 第三方模型就绪时，语音按钮优先使用 `AudioRecord 16kHz PCM16 -> Silero VAD -> SenseVoice OfflineRecognizer -> 热词匹配`。
- 第三方模型下载到 `{应用私有目录}/sherpa_models`，SenseVoice `.tar.bz2` 下载后会解压出 `model.int8.onnx` 和 `tokens.txt`。
- 第三方模型未就绪或启动失败时，才回退 Android 系统 `SpeechRecognizer`。
- 不再把 Silero VAD 的 250 ms 端点参数套给系统 `SpeechRecognizer`；该参数只属于 Sherpa VAD 链路。

---

## 韩语 VAD 参数

韩语停顿更细，项目使用更宽松的参数（`_rebuildVad()` in `home_screen.dart`）：

| 参数 | 非韩语 | 韩语 |
|------|--------|------|
| `minSilenceDuration` | 0.25 s | **0.5 s** |
| `minSpeechDuration` | 0.25 s | **0.4 s** |
| `threshold` | 0.45 | **0.40** |
| `maxSpeechDuration` | 30.0 s | 30.0 s |
| `sampleRate` | 16000 | 16000 |
| `numThreads` | 2 | 2 |

切换识别语言为韩语时，若 VAD 已存在且上次非韩语，会重建 VAD 实例。

---

## 录音流程

```
长按录音（150 ms）→ PCM 流 → vad.acceptWaveform()（512 样本窗口）
                              → 切出语段 → ASR 解码 → 热词匹配 → 追加文字
松手             → vad.flush() → 处理剩余片段 → 保存历史
```

录音配置（`RecordConfig`）：

- 编码：PCM 16-bit
- 采样率：16000 Hz
- 声道：单声道
- 开启自动增益（`autoGain`）与噪声抑制（`noiseSuppress`）

---

## 相关文档

- [项目概述.md](./项目概述.md) — 识别架构与流程
- [知识点.md](./知识点.md) — sherpa_onnx 说明
- [热词增加.md](./热词增加.md) — 识别后的热词后处理
