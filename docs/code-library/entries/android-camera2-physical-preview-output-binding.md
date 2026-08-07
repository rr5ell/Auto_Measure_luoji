# android-camera2-physical-preview-output-binding

## Metadata
- status: active
- tech: Android | Kotlin
- tags: camera2, logical-multi-camera, physical-output, set-physical-camera-id, fusion-avoidance
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\Camera2Controller.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-08-07
- author: Codex Agent

## 适用场景
- 普通预览需要继续打开父逻辑相机 ID，但预览画面要尽量固定来自当前选中的物理镜头。
- 需要用默认最大焦距候选作为普通预览输出，减少逻辑多摄融合或自动切镜对画面的影响。

## 禁止场景
- 禁止将普通预览改成直接打开物理 camera id；必须通过父逻辑 camera id + `OutputConfiguration.setPhysicalCameraId()` 绑定输出。
- 禁止让普通预览物理输出绑定失败影响双摄校准；双摄校准仍由 `strictPhysical=true` 控制失败策略。
- 禁止为实现物理输出绑定启用 EIS。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `bindPhysicalPreview` | `Boolean` | `false` | 普通预览是否绑定预览 Surface 到当前 `physicalId` | 仅普通模式传 `true`；API < 28 或无 `physicalId` 时自动退化 |
| `strictPhysical` | `Boolean` | `false` | 双摄校准物理 Session 严格模式 | 保持原逻辑；失败即报错 |
| `currentPhysicalPreviewBound` | `Boolean` | `false` | 当前 Session 是否使用物理预览输出 | 影响 active id 回报、active array、zoom range 和 OIS 能力选择 |
| `currentPhysicalOutputId` | `String?` | `null` | 当前绑定到预览 Surface 的物理镜头 ID | 普通绑定失败回退时必须清空 |

## 复用方式
- `CameraScreen` 普通打开相机时传 `bindPhysicalPreview = !isDualCalibMode && lens.physicalId != null`。
- `Camera2Controller` 在 `bindPhysicalPreview` 或 `strictPhysical` 有效时，使用 `OutputConfiguration(surface).setPhysicalCameraId(lens.physicalId)` 配置预览输出。
- 普通物理预览输出配置失败时调用 `fallbackToLogicalSession()` 回退逻辑 Session；双摄 strict physical 失败仍调用 `onError`。
- 物理输出绑定模式关闭非必要 YUV `ImageReader`，降低物理流 + 逻辑流组合配置失败概率。

## 注意事项
- 普通模式默认选中最大焦距候选，因此默认绑定最大焦距物理镜头；用户切换到广角候选时绑定当前广角物理输出。
- `CONTROL_ZOOM_RATIO`、crop、OIS 能力在物理预览输出绑定模式下按当前物理候选特征处理。
- 当前画面显示仍需结合 `android-camera2-active-physical-lens-display`，用 active physical id 和 35mm 等效焦距确认真机效果。
- 该方案能绕开一部分逻辑多摄融合，但厂商 HAL 行为仍需真机验证。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug :app:assembleLaserCompare`
- 已覆盖边界：普通预览物理输出绑定、普通绑定失败逻辑回退、双摄 strict physical 失败策略不变、EIS 继续关闭。
- 未覆盖风险：不同厂商支持的物理输出 Surface 尺寸和组合不同。

## 给 AI 的指令
后续修改普通预览物理镜头输出时，优先复用 `bindPhysicalPreview` 路径。不要把它和双摄 `strictPhysical` 混用，也不要直接打开物理 camera id。
