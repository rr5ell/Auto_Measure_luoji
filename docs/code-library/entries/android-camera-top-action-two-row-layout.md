# android-camera-top-action-two-row-layout

## Metadata
- status: superseded
- tech: Android | Kotlin
- tags: compose, camera-ui, toolbar-layout, responsive-ui
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure\feature\camera\ui\CameraScreen.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-08-03
- author: Codex Agent
- superseded_by: android-camera-top-action-collapsible-menu

## 2026-08-03 Update
- 顶部两行按钮布局已被 `android-camera-top-action-collapsible-menu` 替代。
- 旧规则保留为历史记录；后续新增主界面入口时优先使用 `更多` 折叠菜单，而不是继续增加顶部行数。

## 适用场景
- 相机页顶部按钮数量增加后，需要避免常用按钮被横向滚动容器挤到默认视口外。
- 需要同时保留高频主操作、硬件开关、日志入口和低频工具按钮。

## 禁止场景
- 禁止把所有新增按钮继续塞入第一行，导致 `OIS/语音/Log` 或工具按钮不可见。
- 禁止使用过长按钮文案；状态细节应放到 HUD、弹框或日志中。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| 第一行按钮 | Compose Row | `校准/识别/图源/绿光/OIS/语音/Log` | 高频入口和固定入口 | 左侧主操作保留横向滚动兜底 |
| 第二行按钮 | Compose Row | `区域/判定点/统计` | 低频工具入口 | 保持横向滚动兜底 |
| `TopActionButton` | Composable | `height=36.dp` | 顶部按钮基础组件 | 文案应尽量短 |

## 复用方式
- 第一行左侧使用 `weight(1f)` 的横向滚动 Row 放主操作，右侧固定放 `OIS/语音/Log`。
- 第二行使用全宽横向滚动 Row 放工具类按钮。
- 新增按钮前先判断属于主操作还是工具操作，再放入对应行。

## 注意事项
- 两行布局会增加顶部占用高度；打开校准面板、绿光对比面板时需关注状态 HUD 是否被遮挡。
- 如果后续按钮继续增加，应优先考虑弹出菜单或分组面板，不应无限增加顶部行数。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug`
- 已覆盖边界：新增 `图1080P/图4K` 与 `绿光` 后，`区域/判定点/统计` 拆到第二行。
- 未覆盖风险：极窄屏幕下第一行主操作仍可能需要横向滑动。

## 给 AI 的指令
后续修改相机页顶部按钮时优先复用该布局分组。除非哥哥明确要求重做 UI，否则只做最小必要移动，不改业务回调。
