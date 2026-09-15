# android-camera2-eis-experimental-toggle

## 元数据
- 状态：deprecated（2026-09-15）；替代条目：`android-gyro-preview-stabilization`。
- 当前规则：禁止启用系统 EIS；只允许 OIS 或应用层算法补偿。控制器拒绝 ON/PREVIEW。

## 历史元数据
- status: deprecated
- tech: Android | Kotlin
- tags: camera2, eis, video-stabilization, hardware-control, calibration-risk
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\Camera2Controller.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-08-25
- author: Codex Agent

## 历史适用场景（已停用）
- 需要与 `E:\StudioProjects\Auto_measure` 保持一致，提供 Camera2 EIS OFF/ON/PREVIEW 实验模式。
- 需要在真机上验证厂商 EIS 对画面稳定、电子裁剪和坐标漂移的影响。

## 禁止场景
- 禁止默认开启 EIS；进入相机后默认模式必须是 OFF。
- 禁止在校准流程中静默切换 EIS，必须由用户显式选择。
- 禁止把 EIS 当作 OIS 的替代品；两者开关状态必须可区分。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `pendingVideoStabilizationMode` | `Int` | `CONTROL_VIDEO_STABILIZATION_MODE_OFF` | 用户请求的 EIS 模式 | 仅允许设备支持列表中的模式 |
| `availableVideoStabilizationModes` | `IntArray` | `OFF` | 当前相机上报的可用 EIS 模式 | 不支持 ON/PREVIEW 时 UI 禁用对应选项 |
| `VIDEO_STABILIZATION_MODE_PREVIEW` | `Int` | `2` | Camera2 PREVIEW stabilization 模式值 | 与源项目保持一致 |
| `onVideoStabilizationStateChanged` | `(Int, String) -> Unit` | `null` | 向 UI 发布 EIS 模式和支持列表 | UI 同步 `eisMode` 与状态文本 |

## 复用方式
- `Camera2Controller.openCamera()` 读取 `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES` 并发布支持列表。
- `Camera2Controller.setVideoStabilizationMode(mode)` 校验支持模式后写入 `CONTROL_VIDEO_STABILIZATION_MODE` 并重提 repeating request。
- `CameraScreen` 在 `更多` 菜单显示 `防抖 EIS:<mode>`，弹窗中用 `videoStabilizationModeChoices()` 渲染 OFF/ON/PREVIEW。
- 所有 repeating request callback 必须同时保留 active physical id 监听和 EIS/OIS result 日志。

## 注意事项
- EIS ON/PREVIEW 可能产生电子裁剪，导致 TextureView 像素坐标、绿光质心和测量校准参数发生漂移。
- 普通预览仍保持父逻辑相机 Session；本条目不恢复普通预览 `setPhysicalCameraId()` 绑定。
- OIS 仍由 `setOisEnabled()` 控制，EIS 和 OIS 状态必须分别记录。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug :app:assembleLaserCompare`
- 已覆盖边界：不支持模式禁用/回退 OFF、EIS 切换 repeating request、capture result 日志、active physical id callback 保留。
- 未覆盖风险：不同厂商对 ON/PREVIEW 的裁剪幅度、回传 result 和坐标漂移差异，需要真机测量验证。

## 给 AI 的指令
禁止恢复本条目的 ON/PREVIEW 实验入口。系统 EIS 固定 OFF；自研补偿请复用 `android-gyro-preview-stabilization`。
