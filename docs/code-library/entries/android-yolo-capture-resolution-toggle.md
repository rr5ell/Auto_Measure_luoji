# android-yolo-capture-resolution-toggle

## Metadata
- status: active
- tech: Android | Kotlin
- tags: compose, yolo, textureview, capture-resolution, model-input
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\detection\domain\YoloDetector.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-07-29
- author: Codex Agent

## 适用场景
- 需要在真机上对比 YOLO 使用 1080P 和 4K TextureView 截图源时的识别效果、耗时和稳定性。
- 需要明确区分 Camera2 preview buffer、TextureView 截图源尺寸和 TFLite 模型输入尺寸。

## 禁止场景
- 禁止把 4K 设为默认检测图源，除非已经验证目标设备不会卡顿、掉帧或 OOM。
- 禁止硬编码模型输入为 `1280x1280` 来覆盖 TFLite tensor shape；模型输入尺寸必须从 `YoloDetector` 的 input tensor 读取。
- 禁止借该功能擅自修改 Camera2 防抖配置；EIS 默认 OFF，只有用户显式选择实验模式时才可开启。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `YoloCaptureMode.P1080` | enum | `1080x1920` | 默认 YOLO 截图源尺寸 | 内存压力较低 |
| `YoloCaptureMode.UHD4K` | enum | `2160x3840` | 4K YOLO 截图源尺寸 | 约 4 倍像素量，需关注耗时和内存 |
| `inputW/inputH` | `Int` | 模型 tensor shape | `YoloDetector` 最终模型输入尺寸 | 不得手写固定值覆盖 |

## 复用方式
- 顶部工具条复用 `TopActionButton`，文案为 `图1080P/图4K`。
- 检测循环根据 `yoloCaptureMode` 调用 `TextureView.getBitmap(width, height)`。
- `YoloDetector.detect(bitmap)` 继续读取模型 input tensor shape 并执行 letterbox。
- 日志同时查看 `YOLO图源: ...` 和 `YoloDetector Input: ... -> ...`。

## 注意事项
- `TextureView.getBitmap(width, height)` 是从预览显示内容截图并缩放到指定尺寸，不等同于直接改变 Camera2 previewSize。
- 如果当前模型 input tensor 是 `1280x1280`，则 1080P/4K 图源都会在 `YoloDetector` 内转换为 `1280x1280` 后推理。
- 4K 模式会增加 Bitmap 分配、像素读取和预处理耗时，连续检测时必须及时 recycle。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug`
- 已覆盖边界：1080P/4K 按钮切换、检测 HUD 显示图源、Bitmap recycle。
- 未覆盖风险：真机 4K 模式长时间运行的温升、掉帧和 OOM。

## 给 AI 的指令
后续调整 YOLO 输入链路时，先确认当前使用的是 TextureView 截图源还是 ImageReader/YUV 源；不要把截图源尺寸和模型 tensor 输入尺寸混为一谈。
