# android-camera2-ois-toggle

## Metadata

- status: active
- tech: Android, Kotlin
- tags: camera2, ois, stabilization, hardware-control
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\data\LensCandidate.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\data\LensScanner.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\Camera2Controller.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-08-25
- author: Codex Agent

## 适用场景

- 需要在 Camera2 预览中由用户显式开启或关闭光学防抖 OIS。
- 需要根据当前物理/逻辑镜头能力判断 OIS 是否可用。

## 禁止场景

- 禁止借 OIS 开关隐式启用 EIS；EIS 只能通过 `防抖` 弹窗的显式 ON/PREVIEW 选项切换，默认保持 OFF。
- 校准坐标漂移尚未实机验证时，不要默认开启 OIS。

## 核心参数

| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `oisSupported` | Boolean | 扫描获得 | 当前镜头是否支持 `LENS_OPTICAL_STABILIZATION_MODE_ON` | 不支持时不能开启 |
| `oisRequested` | Boolean | `false` | 用户是否请求开启 OIS | 相机重开后沿用请求，但不支持镜头会自动关闭 |
| `CONTROL_VIDEO_STABILIZATION_MODE` | Int | `OFF` | EIS 状态 | 默认 OFF；仅用户显式选择 ON/PREVIEW 时写入 |
| `LENS_OPTICAL_STABILIZATION_MODE` | Int | `OFF/ON` | OIS 状态 | 仅用户显式开启且镜头支持时设 ON |

## 复用方式

- 镜头扫描时从 `LensScanner.buildCandidate()` 写入 `LensCandidate.oisSupported`。
- UI 调用 `Camera2Controller.setOisEnabled(true/false)`。
- `CameraScreen` 中的 `OIS/OIS开` 菜单项放在 `更多` 菜单；EIS 实验模式通过同一个 `防抖` 弹窗切换。
- 失败提示通过 `onOisEnableFailed` 回调给 Compose 层展示 3 秒。

## 注意事项

- 不同物理镜头 OIS 能力可能不同，不能只用逻辑相机能力判断。
- OIS 可能改变取景稳定方式，涉及校准精度时需要记录开关状态并实机验证。
- EIS 会裁切画面并影响坐标映射；开启 ON/PREVIEW 后，校准和绿光识别必须记录当前 EIS 模式并实机验证。

## 测试与验证

- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug :app:assembleLaserCompare`
- 已覆盖边界：当前镜头不支持 OIS、相机未就绪、请求异常回退。
- 未覆盖风险：不同厂商对物理镜头 OIS 请求的实际生效差异。

## 给 AI 的指令

优先复用本条目的 canonical code path。后续改相机防抖时，OIS 和 EIS 必须保持显式用户控制；EIS 默认 OFF，只能由用户选择实验模式开启。
