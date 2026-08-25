# android-ble-distance-state-notification

## Metadata
- status: active
- tech: Android | Kotlin
- tags: ble, compose-state, distance-measurement, calibration
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\bluetooth\domain\BleConnectionManager.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\calibration\ui\CalibrationPanel.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\calibration\ui\DualCalibrationPanel.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-07-31
- author: Codex Agent

## 适用场景
- BLE 设备返回 `AT+Distance=` 数据后，需要驱动 Compose UI、普通校准或双镜头校准继续处理。
- 自动测距需要把最新距离作为事件消费，而不是只关心数值是否和上一次不同。

## 禁止场景
- 不要把距离 state 改回默认结构相等策略，否则连续两次相同距离数据可能不会触发 UI 和校准逻辑。
- 不要在校准自动测距中要求 `flag=1`；当前流程只要求 `line > 0`。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `lastDistance` | `DistanceData?` | `null` | 最新 BLE 测距包，使用 `neverEqualPolicy()` 保证每次赋值都通知 | 即使 `line/comp/height/flag` 全部相同也要触发 |
| `line` | `Double` | 设备返回 | 自动校准采集使用的距离值 | 必须大于 `0.0` |
| `flag` | `Int` | 设备返回 | 主界面测距展示仍可用来筛选正式结果 | 校准自动测距不依赖该值 |

## 复用方式
- BLE 解析层继续在 `BleConnectionManager` 中更新 `lastDistance` 并调用 `onDistanceReceived`。
- Compose 自动测距入口使用 `ignoredDistance` 忽略点击前已有的旧对象，再消费新的 `lastDistance`。
- 成功、超时、关闭面板三个路径都必须调用 `stopMeasure()`。

## 注意事项
- `lastDistance` 是事件状态，不是纯缓存字段；不要用默认 `mutableStateOf(null)`。
- 自动测距回填距离后，普通校准应立即进入绿光采集；绿光失败时保留距离供重试。
- 本条目只约束 BLE 距离数据流，不擅自切换 Camera2 EIS；EIS 默认 OFF，只有用户显式选择实验模式时才可开启。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug`
- 已覆盖边界：普通校准空距离自动测距；连续相同 `DistanceData` 需要重新通知 Compose。
- 未覆盖风险：真机 BLE 断连、设备长时间不返回距离包时的现场表现，需要继续实机验证超时提示。

## 给 AI 的指令
优先复用本条目的 canonical code path。修改 BLE 测距或校准自动输入时，保持 `lastDistance` 的事件通知语义和 `line > 0` 的校准提交条件。
