# android-yolo-postprocess-threshold

## 元数据
- 状态：active
- 技术栈：Android | Kotlin | Jetpack Compose | YOLO
- 标签：yolo, flag-detection, threshold, nms, compose-settings
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\detection\domain\YoloDetector.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
- 相关文档：
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- 最后更新：2026-09-07
- 作者：Codex Agent

## 适用场景
- 需要在真机上调试旗杆识别误检/漏检，切换单框最高置信度策略和原始多框策略。
- 需要让 PreviewBitmap 与 ImageReader YUV 两条实时检测链路使用同一套 YOLO 后处理阈值。

## 禁止场景
- 禁止在 `YoloDetector` 内重新写死全局置信度阈值；必须通过 `YoloPostprocessOptions` 传入。
- 禁止把单框策略用于需要同时观察多个候选目标的离线评估，除非哥哥明确要求只看最高置信度。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `YoloPostprocessOptions.confidenceThreshold` | Float | `0.25` | 单框模式置信度阈值 | `0.0..1.0` |
| `YoloPostprocessOptions.topConfidenceOnly` | Boolean | `true` | 是否只保留最高置信度检测框 | `false` 时使用原始多框 |
| `ORIGINAL_CONFIDENCE_THRESHOLD` | Float | `0.10` | 原始多框阈值 | 用于兼容旧行为 |
| `TOP_ONLY_MAX_DETECTIONS` | Int | `1` | 单框模式最大检测数量 | 固定为最高置信度目标 |
| `ORIGINAL_MAX_DETECTIONS` | Int | `100` | 原始多框最大检测数量 | 交给 NMS 继续过滤 |
| `KEY_YOLO_TOP_DETECTION_ENABLED` | SharedPreferences key | `true` | 持久化单框策略开关 | 切换后检测协程重启 |
| `KEY_YOLO_TOP_CONFIDENCE_THRESHOLD` | SharedPreferences key | `0.25` | 持久化单框阈值 | 保存时限制在 `0.0..1.0` |

## 复用方式
- `CameraScreen` 的实时检测循环根据 SharedPreferences 构建 `YoloPostprocessOptions`。
- PreviewBitmap 路径调用 `YoloDetector.detect(bitmap, postprocessOptions)`。
- ImageReader YUV 路径调用 `YoloDetector.detectFromYuv(image, rotation, postprocessOptions)`。
- `YoloDetector.parseAndNms()` 只接收归一化后的后处理参数，再交给 `Nms.classAwareNms()`。

## 注意事项
- 检测状态文本会显示 `单框>=阈值` 或 `多框>=阈值`，现场验证先确认该文本。
- 单框模式仍先按阈值筛候选，再执行 NMS，最后只保留最高置信度结果。
- YUV native 预处理和 Kotlin fallback 不处理阈值，阈值只作用在模型输出解析之后。

## 测试与验证
- 测试命令：`git diff --check`
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin --no-daemon`
- 测试命令：`.\gradlew.bat :app:assembleDebug --no-daemon`
- 已覆盖边界：PreviewBitmap/YUV 调用点统一传参、阈值输入范围校验、关闭单框时回到原始多框阈值。
- 未覆盖风险：阈值对不同距离、光照和旗杆尺寸的实际召回率需要真机采样验证。

## 给 AI 的指令
优先复用 `YoloPostprocessOptions` 和 `更多 -> YOLO单框` 设置入口。除非哥哥明确要求重做检测策略，否则不要在其它位置新增重复阈值常量。
