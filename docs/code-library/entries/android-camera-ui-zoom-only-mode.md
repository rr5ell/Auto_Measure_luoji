# android-camera-ui-zoom-only-mode

## Metadata
- status: active
- tech: Android | Kotlin | Jetpack Compose
- tags: camera-ui, zoom, calibration, coordinate-map, experiment
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-08-28
- author: Codex Agent

## 适用场景
- 需要对比“不使用 Camera2 数码 zoom，只放大 UI 预览”的实验效果。
- 需要观察逻辑多摄、active 物理镜头和显示层矩阵放大之间的差异。

## 禁止场景
- 默认主流程不得使用纯 UI 放大替代 Camera2 zoom；未打开 `更多 -> 纯UI放大` 时继续走 `CONTROL_ZOOM_RATIO` 优先路径。
- 需要验证真实相机裁切、YUV 输出和预览一致性时，必须关闭该开关。
- 禁止把 UI scale 当成真实 Camera2 倍率写入校准样本。
- 双镜头校准保持原 Camera2 zoom 和物理长焦切换逻辑，不套用纯 UI zoom。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `KEY_UI_ZOOM_ONLY_ENABLED` | `String` | `false` | `SharedPreferences` 持久化开关 | 仅由 `更多 -> 纯UI放大` 修改 |
| `currentEquivRatio` | `Float` | 当前滑块倍率 | UI 显示目标等效倍率 | 仍受 luoji 普通倍率下限和 `24x` 上限限制 |
| `uiCameraBaseZoomForLens()` | 函数 | `normalMinZoomFor(selectedLens)` | 计算纯 UI 模式下逻辑相机必须保持的真实 Camera2 底倍 | 不得固定为 `1f`，否则逻辑相机会退回广角 |
| `cameraZoomForMode()` | 函数 | `currentEquivRatio` 或最低有效底倍 | 计算 Camera2 请求倍率 | 纯 UI 普通模式返回 `uiCameraBaseZoomForLens()` |
| `uiZoomDisplayScale()` | 函数 | `1f` | 计算 TextureView Matrix 显示缩放 | 纯 UI 模式用 `currentEquivRatio / uiCameraBaseZoomForLens()`；双镜头模式保持 `1f` |
| `calibrationSampleZoom()` | 函数 | `currentEquivRatio` 或最低有效底倍 | 计算写入普通校准样本的真实相机倍率 | 纯 UI 普通模式记录真实 Camera2 底倍 |

## 复用方式
- 开关入口在 `CameraScreen` 的 `更多` 菜单中，默认关闭。
- 纯 UI 模式打开后，统一通过 `cameraZoomForMode()` 让 `Camera2Controller.setZoomRatio()` 收到当前逻辑镜头的最低有效底倍，避免 HAL 回到广角。
- 预览内容通过 `TextureView.setTransform(Matrix)` 做显示层矩阵缩放和平移，外层 `FrameLayout` 保持 `1x`。
- 检测框 overlay 和命中判断复用 `wrapperScale/wrapperTx/wrapperTy`，保证画面、overlay、命中区域坐标同层变换。
- 普通校准采集继续使用现有 bytes/YUV 输入链路，但标定计算倍率必须调用 `calibrationSampleZoom()`。

## 注意事项
- `TextureView.setTransform(Matrix)` 只表示预览显示层变换；`ImageReader YUV` 仍是相机原始输出。
- luoji 当前普通预览保持逻辑相机 Session，不使用 `OutputConfiguration.setPhysicalCameraId()` 绑定普通预览 Surface。
- 纯 UI 模式下 `zoom-map` 日志会输出 `mode=UI_TEXTURE_MATRIX` 和 `uiCameraBase`，HUD 会显示 `zoom: backend=UI_TEXTURE_MATRIX`、`cameraBase`。
- 电子防抖 EIS 默认保持 OFF；不要用 EIS 处理纯 UI 模式下的坐标差异。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 已覆盖边界：按钮默认关闭、持久化开关、普通模式 Camera2 保持最低有效底倍、普通校准记录真实相机倍率、双镜头校准不套用 UI zoom。
- 未覆盖风险：不同厂商 `TextureView.getBitmap()` 是否包含 matrix 变换需要真机观察确认。

## 给 AI 的指令
除非哥哥明确要求实验 UI zoom，否则不要把该模式作为默认倍率实现。修改校准或检测链路时，必须区分 UI scale 与真实 Camera2 zoom。
