# android-camera-top-action-collapsible-menu

## Metadata
- status: active
- tech: Android | Kotlin
- tags: compose, camera-ui, toolbar-layout, lens-switch, responsive-ui
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-08-03
- author: Codex Agent

## 适用场景
- 相机页顶部入口继续增加，需要减少按钮挤占和顶部高度。
- 需要保证 `识别` 和 `校准` 这类主操作初始可见，同时保留广角、OIS、语音、Log、图源、绿光、区域、判定点、统计等功能。

## 禁止场景
- 禁止把低频工具继续固定塞到顶部行，导致 `识别` 入口不可见。
- 禁止在普通校准或双镜头校准流程中允许手动切换镜头。
- `laserCompare` 包的 `绿光对比` 入口不能只放在 `更多` 菜单，必须固定可见。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `toolsMenuExpanded` | `Boolean` | `false` | `更多` 菜单展开状态 | 菜单项点击后必须关闭 |
| `识别/识别中` | `TopActionButton` | 固定显示 | YOLO 模型识别主入口 | 必须放在固定区首位 |
| `校准/校准中/双摄` | `TopActionButton` | 固定显示 | 普通校准和双镜头校准入口 | 不得被收进 `更多` |
| `更多` | `DropdownMenu` | 固定显示 | 收纳广角、OIS、语音、Log、图源、绿光识别、区域、判定点、统计 | 必须放在横向滚动 Row 外，保证初始可见 |
| `广角/广角中` | `ToolMenuItem` | 菜单显示 | 切换广角镜头；已在广角时切回默认镜头 | 校准流程中禁用 |
| `ToolMenuItem` | Composable | 菜单项组件 | 统一菜单项高亮和禁用态 | 文案保持短 |

## 复用方式
- 顶部第一行左侧可横向滚动区域只放 `识别/校准` 等主操作，且 `识别` 放在最前；右侧固定只放 `更多`。
- `更多` 中复用原功能回调：切换广角、切换 OIS、启动语音识别、打开 Log、切换 YOLO 图源、打开绿光识别面板、打开区域/判定点弹窗、启动统计。
- 广角切换调用 `findWideLens(lensCandidates)`；切换时重置 `currentEquivRatio=1f`，清理 `anchorCenter1x/currentCenterPx/detectionBoxes`。

## 注意事项
- 广角切换会重开 Camera2 session，不能在校准状态机中开放。
- 默认镜头使用 `defaultLens ?: selectLensUseCase(lensCandidates)`，避免切到广角后无法返回。
- 顶部 UI 文案必须短，状态细节放入 `statusText`、HUD 或日志。
- 顶栏固定按钮数量控制在 3 个左右；后续新增按钮默认放进 `更多`。
- Camera2 EIS 默认 OFF；`更多` 菜单可包含显式 EIS 实验入口，本条目只约束 UI 收纳和镜头选择入口。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug`
- 测试命令：`.\gradlew.bat :app:assembleLaserCompare`
- 已覆盖边界：主包低频工具收纳、`识别/校准/更多` 固定可见、`laserCompare` 固定对比入口、校准中禁用广角切换。
- 未覆盖风险：极窄屏幕上 `laserCompare` 包额外固定 `绿光对比` 时仍需真机检查。

## 给 AI 的指令
后续新增相机页顶部入口时优先放入 `更多` 菜单。固定区优先保证 `识别` 和 `校准` 初始可见。
