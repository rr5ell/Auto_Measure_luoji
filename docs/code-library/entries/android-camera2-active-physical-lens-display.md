# android-camera2-active-physical-lens-display

## Metadata
- status: active
- tech: Android | Kotlin
- tags: camera2, logical-multi-camera, active-physical-id, lens-display, hardware-debug
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\Camera2Controller.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\data\LensScanner.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-08-06
- author: Codex Agent

## 适用场景
- 需要在逻辑多摄预览中确认当前画面实际活跃的物理镜头 ID。
- 需要把 active physical id 映射成真实焦距和等效 35mm 焦距，辅助判断是否切到长焦或仍在融合/广角路径。

## 禁止场景
- 禁止只用 `selectedLens` 判断当前画面物理来源；逻辑相机实际活跃镜头由厂商 HAL 决定。
- 禁止把“未上报 activePhysicalId”显示成已确认焦距。
- 禁止为实现该显示启用 EIS；EIS 会裁切画面并影响坐标映射。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` | `CaptureResult.Key<String>` | 设备返回 | 当前 capture result 的活跃物理镜头 ID | 仅 API 28+；设备可不回传 |
| `onActivePhysicalCameraChanged` | `(String?) -> Unit` | `null` | `Camera2Controller` 向 UI 通知 active physical id 变化 | `null` 表示未上报，不等于选中镜头 |
| `activePhysicalCameraId` | `String?` | `null` | UI 当前记录的活跃物理镜头 ID | 必须通过 `LensCandidate.physicalId` 映射 |
| `activePhysicalLensReported` | `Boolean` | `false` | 区分等待回传和已回传空值 | 防止启动初期误报 |

## 复用方式
- `Camera2Controller` 的 repeating request 必须传入统一 `CaptureCallback`，在 `onCaptureCompleted()` 中读取 active physical id。
- strict physical 模式可直接回报当前绑定的 `physicalId`；逻辑模式优先读取 `CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`。
- `CameraScreen` 通过 `LensScanner` 扫描得到的 `LensCandidate` 映射焦距，显示格式为实际焦距、等效 35mm 焦距和 id。

## 注意事项
- active physical id 是厂商 HAL 回报的当前主活跃物理镜头，不一定能完整描述多帧融合或厂商私有处理。
- 如果设备不回传该字段，UI 必须显示“未上报 activePhysicalId”，再结合画面和 zoom 日志实机判断。
- 所有 `setRepeatingRequest()` 调用点都要保留同一个 capture callback，否则切 zoom、OIS、曝光补偿后会丢失 active id 监听。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug :app:assembleLaserCompare`
- 已覆盖边界：逻辑相机 active id 回调、strict physical 回报绑定物理 ID、设备未上报时 UI 提示、zoom/OIS/EV repeating request 保持 callback。
- 未覆盖风险：不同厂商对 active physical id 的回报频率和融合语义差异。

## 给 AI 的指令
后续修改当前画面镜头显示时，优先复用 `Camera2Controller` capture callback + `LensCandidate` 映射链路。不要用 `selectedLens` 代替 active physical id 判断逻辑相机实际画面来源。
