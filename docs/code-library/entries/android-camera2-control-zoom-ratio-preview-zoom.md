# android-camera2-control-zoom-ratio-preview-zoom

## Metadata
- status: active
- tech: Android | Kotlin
- tags: camera2, zoom, control-zoom-ratio, scaler-crop-region, coordinate-map
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure\feature\camera\domain\Camera2Controller.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure\feature\camera\ui\CameraScreen.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure\feature\camera\util\ZoomController.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-07-31
- author: Codex Agent

## 2026-07-31 Update
- 普通校准长焦/非广角镜头的原生光学倍率策略临时回退：当前版本只保持用户当前倍率采集，不再自动改到 `currentLens.eq35mm / wideLens.eq35mm`。
- 普通模式滑条最小值恢复为 `1x`，不再按当前物理镜头原生光学倍率抬高。
- 普通模式 Camera2 zoom 下发恢复为直接使用 `currentEquivRatio`；双镜头长焦物理镜头仍保持 Camera2 `1x`。
- `CameraScreen:I` 仍输出 `zoom-map: equiv=..., camera=..., lens=...`，用于排查实际下发倍率。

## 适用场景
- 普通模式或双镜头广角模式需要让倍率滑块驱动真实 Camera2 数码 zoom，而不是只放大 UI。
- 需要保持 YOLO/绿光截图来自已经真实 zoom 后的预览内容。

## 禁止场景
- 禁止开启 EIS；EIS 会裁切画面并破坏坐标稳定性。
- 禁止在无校准点时使用 wrapper `scaleX/scaleY` 充当倍率放大，避免和 Camera2 zoom 叠加。
- 双镜头长焦物理镜头模式下不要再叠加长焦倍率，保持 Camera2 zoom 为 `1x`。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `currentEquivRatio` | `Float` | `1f` | UI/校准记录和普通模式 Camera2 请求使用的倍率 | 普通模式直接提交给 Camera2；双摄长焦时 Camera2 固定 `1x` |
| `WIDE_CALIBRATION_ZOOM` | `Float` | `5f` | 普通校准在广角镜头下的固定采样倍率 | 实际请求倍率不得超过当前镜头可用上限 |
| `normalMinRatio` | `Float` | `1f` | 普通模式滑条最小倍率 | 当前版本不再按非广角镜头原生倍率抬高 |
| `CONTROL_ZOOM_RATIO` | `Float` | 当前倍率 | API 30+ 优先使用的 Camera2 zoom | 必须落在 `CONTROL_ZOOM_RATIO_RANGE` 内 |
| `SCALER_CROP_REGION` | `Rect` | fallback crop | `CONTROL_ZOOM_RATIO` 不可用或请求失败时回退 | 基于 active array 中心裁切 |
| `wrapperScale` | `Float` | `1f` | UI 层覆盖 scale | 仅用于校准点居中需要覆盖边界时 |

## 复用方式
- `CameraScreen` 中监听 `currentEquivRatio`，调用 `Camera2Controller.setZoomRatio()`。
- `CameraScreen.prepareSingleCalibrationZoom()` 在普通校准开始前检查当前镜头；`LensCategory.WIDE` 自动请求 `5x`，并清除旧锚点居中状态。
- 长焦/非广角镜头普通校准保持当前倍率，不调用原生光学倍率换算。
- `Camera2Controller` 每次应用倍率后发布 `zoom: backend=..., ratio=...` 摘要；`CameraScreen` 将该摘要显示在状态区并转发 `CameraScreen:I`。
- `ZoomController.applyZoom()` 只委托 `Camera2Controller.setZoomRatio()`，避免旧代码复用时绕回中心 crop。
- `Camera2Controller.applyPreferredZoom()` 优先写入 `CaptureRequest.CONTROL_ZOOM_RATIO`，并将 `SCALER_CROP_REGION` 置为 full active array，避免二次裁切。
- `CONTROL_ZOOM_RATIO` 不可用时调用 `applyCropZoom()` 回退中心 crop。

## 注意事项
- Camera2 zoom 和 UI 居中变换是两个层次：倍率放大由 Camera2 负责，校准点居中可使用 UI 平移和最小覆盖 scale。
- 应用内 Log 面板需要包含 `Camera2Ctrl:I/D/W` 和 `CameraScreen:I`，否则真机上可能误以为没有 zoom 日志。
- `ZoomController` 中锚点居中的 crop 方法仅为历史实验逻辑；普通倍率不要调用这些方法替代 `setZoomRatio()`。
- Samsung S23 等设备在多流或逻辑多摄场景下可能有厂商 zoom 阈值，需用日志确认 `zoom backend`。
- 当前 YOLO 与绿光仍从 `TextureView.getBitmap()` 截图，因此会看到 Camera2 zoom 后的预览内容。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug`
- 已覆盖边界：普通模式滑块、普通广角校准 5x、双镜头广角滑块、双镜头长焦固定 `1x`、fallback crop。
- 未覆盖风险：不同厂商对 `CONTROL_ZOOM_RATIO` 和物理镜头组合的实际 HAL 行为差异。

## 给 AI 的指令
后续修改倍率逻辑时优先复用该链路。除非哥哥明确要求实验 UI zoom，否则普通倍率放大必须走 Camera2 `CONTROL_ZOOM_RATIO` 优先路径。
