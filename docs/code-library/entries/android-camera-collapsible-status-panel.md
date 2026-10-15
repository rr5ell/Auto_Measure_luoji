# android-camera-collapsible-status-panel

2026-10-15（Codex Agent）：新增可选`modelNameText`参数，在收起/展开时均显示当前模型名称或加载状态，默认空串保持其他调用兼容；推理明细仍放在详情区。增加模型切换后名称实时更新测试。

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、Jetpack Compose、Material3
- 标签：camera, ui, status-panel, accessibility
- 标准代码路径：E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\statuspanel\CollapsibleStatusPanel.kt
- 接入路径：E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt（StatusHud）
- 最后更新：2026-09-23
- 作者：Codex Agent

## 适用场景
- 实时诊断信息较多但需要让出预览区域；通过Composable详情插槽复用现有内容。

## 核心参数
| 参数 | 类型 | 说明 |
|---|---|---|
| fpsText | String | 常驻实时帧率 |
| statusText | String | 常驻当前状态，最多两行 |
| modifier | Modifier | 宿主控制宽度与外边距 |
| details | ColumnScope可组合函数 | 展开后显示的最新详情，不应持有业务生命周期 |

## 复用方式与约束
- 默认收起；rememberSaveable恢复页面展开状态，不承诺跨冷启动持久化。
- 详情最大高度为min(240dp,屏幕高度35%)，支持滚动，按钮位于滚动区之外，触控区48dp。
- 禁止在详情插槽内创建相机、测光或推理协程；折叠不得停采样，恢复不得复用陈旧读数。
- 当前项目不折叠顶部按钮及蓝牙控制。禁止启用EIS；稳定需求只允许OIS或算法层物理补偿。

## 测试与验证
- CollapsibleStatusPanelTest覆盖默认收起、实时更新、双向切换、页面恢复、窄屏大字体和末行可达。
- CameraToolbarInstrumentedTest等原相机测试通过expandStatusPanel辅助函数先展开状态。
- 本轮139项项目单测、6项模拟器回归通过，Debug应用/测试APK及laserCompare Kotlin编译通过；实际主页面收起/展开、窄屏大字体截图已检查。真机GL/HDR与业务长期稳定性未在本轮复测。

## 给 AI 的指令
优先复用本组件；保留显示与业务逻辑分离，不把折叠状态作为算法或相机会话重启条件。
