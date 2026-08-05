# android-camera2-calibration-ev-compensation

## Metadata
- status: active
- tech: Android | Kotlin
- tags: camera2, ae, exposure-compensation, calibration, hardware-control
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\Camera2Controller.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-07-31
- author: Codex Agent

## 适用场景
- 普通校准或双镜头校准采集绿光样本时，需要统一压低自动曝光，降低绿光过曝和扩散风险。
- 需要在 Camera2 请求层控制 EV，而不是通过 UI 亮度或截图后处理伪造曝光变化。

## 禁止场景
- 禁止假设所有设备的曝光补偿步进都是 `1.0 EV`。
- 禁止为了曝光稳定启用 EIS；EIS 会裁切画面并影响校准坐标。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `CALIBRATION_EXPOSURE_EV` | `Float` | `-2.0f` | 校准面板打开期间请求的目标 EV | 设备不支持完整范围时会被裁剪 |
| `pendingExposureCompensationEv` | `Float` | `0f` | Camera2Controller 缓存的目标 EV，跨 session 保留 | 面板关闭后必须恢复为 `0f` |
| `CONTROL_AE_COMPENSATION_STEP` | `Rational` | 设备返回 | 单个补偿档位对应的 EV 值 | 非法或缺失时按 `1 EV` 兜底 |
| `CONTROL_AE_COMPENSATION_RANGE` | `Range<Int>` | 设备返回 | 可写入的整数补偿档位范围 | 写入前必须裁剪 |
| `CONTROL_AE_EXPOSURE_COMPENSATION` | `Int` | 计算得到 | Camera2 请求实际写入的补偿档位 | `actualEV = steps * stepEV` |

## 复用方式
- `CameraScreen` 通过 `LaunchedEffect(isCameraReady, showCalPanel, showDualCalPanel)` 判断校准面板是否打开。
- 面板打开时调用 `Camera2Controller.setExposureCompensationEv(-2.0f)`；面板关闭时调用 `setExposureCompensationEv(0f)`。
- `Camera2Controller.startPreview()` 会重新应用 pending EV，保证普通/双镜头切换和 session 重建后不丢失校准曝光。

## 注意事项
- 日志 `exposure: requestedEV=..., actualEV=..., steps=..., step=..., range=...` 是真机确认设备实际 EV 的主要依据。
- 如果 `actualEV` 不是 `-2.0`，说明设备步进或范围不支持完整目标值，需要按日志判断是否接受裁剪结果。
- 本逻辑只控制 AE 曝光补偿；OIS/EIS 策略不变，EIS 必须保持关闭。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug`
- 测试命令：`.\gradlew.bat :app:assembleLaserCompare`
- 已覆盖边界：普通校准面板、双镜头校准面板、相机 session 重建后的 pending EV 应用。
- 未覆盖风险：不同厂商对物理镜头会话中 AE 曝光补偿的实际生效差异，需要真机看日志和画面亮度。

## 给 AI 的指令
修改校准曝光时优先复用本条目的 canonical code path。不得绕过 Camera2 请求层，也不得启用 EIS。
