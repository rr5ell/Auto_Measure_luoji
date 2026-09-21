# android-camera-top-action-collapsible-menu

## 元数据
- status: active
- tech: Android | Kotlin
- tags: compose, camera-ui, toolbar-layout, lens-switch, responsive-ui
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-09-21
- author: Codex Agent

## 适用场景
- 相机页顶部入口继续增加，需要减少按钮挤占和顶部高度。
- 需要保证 `识别`、`校准`、`唤醒` 这类主操作初始可见，其余入口放入 `更多`。

## 禁止场景
- 禁止把低频工具继续固定塞到顶部行，导致 `识别` 入口不可见。
- 禁止在普通校准或双镜头校准流程中允许手动切换镜头。
- `laserCompare` 包的 `绿光对比` 同样放入 `更多`，不额外占用顶部固定按钮。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `toolsMenuExpanded` | `Boolean` | `false` | `更多` 菜单展开状态 | 菜单项点击后必须关闭 |
| `识别/识别中` | `TopActionButton` | 固定显示 | YOLO 模型识别主入口 | 必须放在固定区首位 |
| `校准/校准中/双摄` | `TopActionButton` | 固定显示 | 普通校准和双镜头校准入口 | 不得被收进 `更多` |
| `唤醒/唤醒中` | `TopActionButton` | 固定显示 | 复用原语音开关及录音授权 | 不自动开启录音 |
| `更多` | `DropdownMenu` | 固定显示 | 收纳暗部增强、EV/对比度、自动 HDR、LSC 及其他工具 | 必须放在横向滚动 Row 外，保证初始可见 |
| `广角/广角中` | `ToolMenuItem` | 菜单显示 | 切换广角镜头；已在广角时切回默认镜头 | 校准流程中禁用 |
| `ToolMenuItem` | Composable | 菜单项组件 | 统一菜单项高亮和禁用态 | 文案保持短 |

## 复用方式
- 顶部第一行左侧可横向滚动区域只放 `识别/校准/唤醒`，且 `识别` 放在最前；右侧固定只放 `更多`。
- `更多` 中复用原功能回调与业务锁定条件，包含暗部增强、EV/对比度、自动 HDR（含手动 HDR）、LSC 和其他工具；语音开关已移出菜单。
- 广角切换调用 `findWideLens(lensCandidates)`；切换时重置 `currentEquivRatio=1f`，清理 `anchorCenter1x/currentCenterPx/detectionBoxes`。

## 注意事项
- 广角切换会重开 Camera2 session，不能在校准状态机中开放。
- 默认镜头使用 `defaultLens ?: selectLensUseCase(lensCandidates)`，避免切到广角后无法返回。
- 顶部 UI 文案必须短，状态细节放入 `statusText`、HUD 或日志。
- 顶栏只保留 `识别/校准/唤醒/更多` 四个入口；后续新增按钮默认放进 `更多`。
- 禁止启用 EIS；如需稳定，仅允许 OIS 或算法层物理补偿。本轮不更改既有防抖入口。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug`
- 测试命令：`.\gradlew.bat :app:assembleLaserCompare`
- 回归入口：CameraToolbarInstrumentedTest，检查四个顶部按钮、菜单及开关持久化；HDR 测试通过 `更多` 打开面板。
- 未覆盖风险：厂商极端字体缩放及语音设备差异需真机检查。

## 给 AI 的指令
后续新增相机页顶部入口时优先放入 `更多` 菜单。固定区优先保证 `识别` 和 `校准` 初始可见。
