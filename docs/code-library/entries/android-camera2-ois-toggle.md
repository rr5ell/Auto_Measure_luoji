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
- last_updated: 2026-07-22
- author: Codex Agent

## 适用场景

- 需要在 Camera2 预览中由用户显式开启或关闭光学防抖 OIS。
- 需要根据当前物理/逻辑镜头能力判断 OIS 是否可用。

## 禁止场景

- 禁止借 OIS 开关启用 EIS。Camera2 请求必须继续写 `CONTROL_VIDEO_STABILIZATION_MODE_OFF`。
- 校准坐标漂移尚未实机验证时，不要默认开启 OIS。

## 核心参数

| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `oisSupported` | Boolean | 扫描获得 | 当前镜头是否支持 `LENS_OPTICAL_STABILIZATION_MODE_ON` | 不支持时不能开启 |
| `oisRequested` | Boolean | `false` | 用户是否请求开启 OIS | 相机重开后沿用请求，但不支持镜头会自动关闭 |
| `CONTROL_VIDEO_STABILIZATION_MODE` | Int | `OFF` | EIS 状态 | 必须保持 OFF |
| `LENS_OPTICAL_STABILIZATION_MODE` | Int | `OFF/ON` | OIS 状态 | 仅用户显式开启且镜头支持时设 ON |

## 复用方式

- 镜头扫描时从 `LensScanner.buildCandidate()` 写入 `LensCandidate.oisSupported`。
- UI 调用 `Camera2Controller.setOisEnabled(true/false)`。
- `CameraScreen` 中的 `OIS/OIS开` 按钮放在顶部固定区域，避免横向工具条滚动后不可见。
- 失败提示通过 `onOisEnableFailed` 回调给 Compose 层展示 3 秒。

## 注意事项

- 不同物理镜头 OIS 能力可能不同，不能只用逻辑相机能力判断。
- OIS 可能改变取景稳定方式，涉及校准精度时需要记录开关状态并实机验证。
- EIS 会裁切画面并影响坐标映射，任何情况下都不能开启。

## 测试与验证

- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug`
- 已覆盖边界：当前镜头不支持 OIS、相机未就绪、请求异常回退。
- 未覆盖风险：不同厂商对物理镜头 OIS 请求的实际生效差异。

## 给 AI 的指令

优先复用本条目的 canonical code path。后续改相机防抖时必须保持 EIS 关闭，只允许在用户显式要求下处理 OIS。
