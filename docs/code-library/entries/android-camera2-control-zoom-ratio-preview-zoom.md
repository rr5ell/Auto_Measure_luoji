# android-camera2-control-zoom-ratio-preview-zoom

## Metadata
- status: active
- tech: Android | Kotlin
- tags: camera2, zoom, control-zoom-ratio, scaler-crop-region, coordinate-map
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\Camera2Controller.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\util\ZoomController.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-08-07
- author: Codex Agent

## 2026-08-05 Update
- 普通预览不直接打开物理 camera id；普通模式通过父逻辑相机 Session + `bindPhysicalPreview=true` 绑定当前物理候选的预览输出，双摄校准仍由 `strictPhysical=true` 控制。
- `LensCandidate` 同时保留当前候选镜头特征和父逻辑相机 zoom/OIS 特征；普通物理预览输出绑定时使用当前候选 active array / zoom range，普通最大倍率固定为 `24x`。
- 普通模式进入后把 `currentEquivRatio` 设置为最大焦距候选相对同一逻辑相机广角候选的光学基准倍率 + `0.1x`。
- 普通模式滑条最小值由 `normalMinZoomFor()` 计算，最低只能回到光学基准倍率 + `0.1x`，最大值固定 `24x`；双摄滑条仍从 `1x` 到长焦倍率。
- 双摄校准的广角 `5x`、长焦物理镜头 `1x` 和 `applyDualZoom()` 逻辑保持不变。

## 2026-07-31 Update
- 普通校准长焦/非广角镜头的原生光学倍率策略临时回退：当前版本只保持用户当前倍率采集，不再自动改到 `currentLens.eq35mm / wideLens.eq35mm`。
- 普通模式滑条最小值恢复为 `1x`，不再按当前物理镜头原生光学倍率抬高。
- 普通模式 Camera2 zoom 下发恢复为直接使用 `currentEquivRatio`；双镜头长焦物理镜头仍保持 Camera2 `1x`。
- `CameraScreen:I` 仍输出 `zoom-map: equiv=..., camera=..., lens=...`，用于排查实际下发倍率。

## 适用场景
- 普通模式需要用逻辑多摄的 `CONTROL_ZOOM_RATIO` 进入最大焦距对应光学基准倍率 + `0.1x`，并限制滑块不能低于该倍率、不能高于 `24x`。
- 普通模式或双镜头广角模式需要让倍率滑块驱动真实 Camera2 数码 zoom，而不是只放大 UI。
- 需要保持 YOLO/绿光截图来自已经真实 zoom 后的预览内容。

## 禁止场景
- 禁止开启 EIS；EIS 会裁切画面并破坏坐标稳定性。
- 禁止在无校准点时使用 wrapper `scaleX/scaleY` 充当倍率放大，避免和 Camera2 zoom 叠加。
- 普通预览禁止直接打开物理 camera id；如需固定物理输出，必须使用 `bindPhysicalPreview=true` 在父逻辑 Session 中绑定预览 Surface。
- 普通模式滑条最小值禁止写死为 `1f` 或原始光学基准倍率，必须保留 `0.1x` 偏移。
- 普通模式最大倍率必须固定为 `24x`；禁止改成“不限”，也禁止按 `logicalMaxDigitalZoom`、`maxDigitalZoom` 或 `CONTROL_ZOOM_RATIO_RANGE.upper` 改写。
- 双镜头长焦物理镜头模式下不要再叠加长焦倍率，保持 Camera2 zoom 为 `1x`。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `currentEquivRatio` | `Float` | 光学基准倍率 + `0.1x` | UI/校准记录和普通模式 Camera2 请求使用的倍率 | 普通模式范围为 `normalMinZoomFor()..24x`；双摄长焦时 Camera2 固定 `1x` |
| `WIDE_CALIBRATION_ZOOM` | `Float` | `5f` | 普通校准在广角镜头下的固定采样倍率 | 低于普通 `24x` 上限；超出设备 `CONTROL_ZOOM_RATIO_RANGE` 时允许 crop fallback |
| `NORMAL_PREVIEW_MAX_ZOOM` | `Float` | `24f` | 普通模式滑条最大倍率 | 只限制普通模式；双摄模式继续使用长焦倍率 |
| `NORMAL_MIN_ZOOM_OFFSET` | `Float` | `0.1f` | 普通模式最低倍率相对光学基准的上浮量 | 通过 `normalMinZoomFor()` 统一生效 |
| `normalMinZoomFor()` | local function | 光学基准倍率 + `0.1x` | 普通模式滑条最小倍率和入场倍率 | 基于同一逻辑相机下最大焦距候选与广角候选的等效焦距比，最终不超过 `24x` |
| `normalMaxZoomFor()` | local function | `24f` | 普通模式滑条最大倍率 | 必须不小于 `normalMinZoomFor()` |
| `logicalZoomRatioRange` | `Range<Float>` | 设备返回 | 父逻辑相机 `CONTROL_ZOOM_RATIO` 可用范围 | 仅决定是否走 `CONTROL_ZOOM_RATIO` 优先路径，不改写普通 `24x` 上限 |
| `CONTROL_ZOOM_RATIO` | `Float` | 当前倍率 | API 30+ 优先使用的 Camera2 zoom | 落在 `CONTROL_ZOOM_RATIO_RANGE` 内时使用；范围外回退 crop |
| `SCALER_CROP_REGION` | `Rect` | fallback crop | `CONTROL_ZOOM_RATIO` 不可用或请求失败时回退 | 基于 active array 中心裁切 |
| `wrapperScale` | `Float` | `1f` | UI 层覆盖 scale | 仅用于校准点居中需要覆盖边界时 |

## 复用方式
- `CameraScreen` 中监听 `currentEquivRatio`，普通模式用 `normalMinZoomFor()`/`normalMaxZoomFor()`/`clampNormalZoom()` 限制到“光学基准 + 0.1x .. 24x”，再调用 `Camera2Controller.setZoomRatio()`。
- `CameraScreen` 普通入口设置 `strictPhysical=false` 且 `bindPhysicalPreview=true`，在父逻辑相机 Session 中绑定当前物理候选预览输出；双摄入口继续传 `strictPhysical=true`。
- `CameraScreen.prepareSingleCalibrationZoom()` 在普通校准开始前检查当前镜头；`LensCategory.WIDE` 自动请求 `5x`，并清除旧锚点居中状态。
- 长焦/非广角镜头普通校准保持当前逻辑相机倍率，且不得低于光学基准倍率 + `0.1x`。
- `Camera2Controller` 每次应用倍率后发布 `zoom: backend=..., ratio=...` 摘要；`CameraScreen` 将该摘要显示在状态区并转发 `CameraScreen:I`。
- `ZoomController.applyZoom()` 只委托 `Camera2Controller.setZoomRatio()`，并同步使用光学基准 + `0.1x` 下限和 `24x` 上限，避免旧代码复用时绕回 `1x` 或绕过最大倍率。
- `Camera2Controller.applyPreferredZoom()` 优先写入 `CaptureRequest.CONTROL_ZOOM_RATIO`，并将 `SCALER_CROP_REGION` 置为 full active array，避免二次裁切。
- `CONTROL_ZOOM_RATIO` 不可用时调用 `applyCropZoom()` 回退中心 crop。

## 注意事项
- Camera2 zoom 和 UI 居中变换是两个层次：倍率放大由 Camera2 负责，校准点居中可使用 UI 平移和最小覆盖 scale。
- 普通物理预览输出绑定模式和双摄 strictPhysical 都使用当前物理候选 zoom 参数；纯逻辑 fallback 使用父逻辑相机参数，新增调用点必须显式判断模式。
- 普通模式最大倍率固定 `24x`；实际后端仍可能因设备 HAL 对 `CONTROL_ZOOM_RATIO` 的范围限制而切到 fallback crop。
- 应用内 Log 面板需要包含 `Camera2Ctrl:I/D/W` 和 `CameraScreen:I`，否则真机上可能误以为没有 zoom 日志。
- `ZoomController` 中锚点居中的 crop 方法仅为历史实验逻辑；普通倍率不要调用这些方法替代 `setZoomRatio()`。
- Samsung S23 等设备在多流或逻辑多摄场景下可能有厂商 zoom 阈值，需用日志确认 `zoom backend`。
- 当前 YOLO 与绿光仍从 `TextureView.getBitmap()` 截图，因此会看到 Camera2 zoom 后的预览内容。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug :app:assembleLaserCompare`
- 已覆盖边界：普通模式逻辑相机打开、普通模式光学基准倍率 + `0.1x` 下限、普通模式 `24x` 上限、普通广角校准 5x、双镜头广角滑块、双镜头长焦固定 `1x`、fallback crop。
- 未覆盖风险：不同厂商对 `CONTROL_ZOOM_RATIO` 和物理镜头组合的实际 HAL 行为差异。

## 给 AI 的指令
后续修改倍率逻辑时优先复用该链路。除非哥哥明确要求实验 UI zoom，否则普通倍率放大必须走 Camera2 `CONTROL_ZOOM_RATIO` 优先路径。
