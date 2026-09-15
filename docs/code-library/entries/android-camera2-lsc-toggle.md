# android-camera2-lsc-toggle

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、Camera2、Jetpack Compose
- 标签：camera2, lsc, shading, hardware-control, settings
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\Camera2Controller.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
- 相关文档：E:\StudioProjects\Auto_Measure_luoji\docs\eis-hdr-lsc-sync.md
- 最后更新：2026-09-15
- 作者：Codex Agent

## 适用场景
- 预览中显式选择镜头阴影校正模式，并保留上次选择。

## 禁止场景
- 禁止启用系统 EIS；仅允许 OIS 或算法层物理补偿。
- 禁止因为 LSC 设置而改动缩放、曝光或焦点模式。
- 禁止将 SENSOR_INFO_LENS_SHADING_APPLIED 的固有校正能力当成用户可关闭的开关。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| pendingShadingMode | Int | -1（AUTO） | 模式 | AUTO/OFF/FAST/HIGH_QUALITY |
| camera_shading_mode | 偏好键 | AUTO | 保存选择 | 能力读取前允许暂存 |
| SHADING_AVAILABLE_MODES | IntArray | 空 | 设备支持模式 | 空数组仅允许 AUTO |

## 复用方式
- 调用 `setShadingMode()`，由现有 repeating request 提交；失败恢复先前请求。
- 通过 `getSelectableShadingModes()` 禁用不支持选项。
- 初始化时先暂存偏好，`updateShadingCharacteristics()` 读取实际逻辑相机能力后校验。

## 注意事项
- 切换镜头后不支持的模式回退 AUTO。LSC 不改变坐标几何，但可能改变暗角亮度，需真机观察识别效果。
- CaptureResult 日志保留请求与回执模式，UI 不把请求成功等同于已验证的画质收益。

## 测试与验证
- `CameraShadingInstrumentedTest` 覆盖打开相机前恢复偏好、无效模式保持原值及系统 EIS 拒绝。
- 不同设备 OFF/FAST/QUALITY 的效果和支持情况需要真机验证。

## 给 AI 的指令
优先复用控制器和现有顶部模式入口，不把 LSC 绑定到低延迟、HDR、曝光或 OIS 开关。
