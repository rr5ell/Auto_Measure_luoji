# android-gyro-preview-stabilization

## 元数据
- 状态：active
- 技术栈：Android | Kotlin | Camera2 | Jetpack Compose
- 标签：camera, gyroscope, stabilization, textureview, opengl, coordinate-map
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\GyroPreviewStabilizer.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\GlStabilizedPreviewView.kt
- 相关文档：
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- 最后更新：2026-09-04
- 作者：Codex Agent

## 适用场景
- 需要在不改变 Camera2 逻辑相机会话的前提下，用陀螺仪对预览显示层做轻量防抖。
- 需要对比 TextureView 矩阵和 GL OES 逐帧渲染的防抖效果。
- 需要通过相邻预览帧亮度匹配辅助判断防抖方向、增益和帧时间偏移。
- 需要在纯 UI 放大、校准居中或防抖裁切后，把显示层倍率纳入自研 EIS 的有效倍率补偿模型。

## 禁止场景
- 禁止把本条目当作系统 Camera2 EIS 使用；不得默认写入 `CONTROL_VIDEO_STABILIZATION_MODE_ON/PREVIEW`。
- 禁止在普通预览中使用 `OutputConfiguration.setPhysicalCameraId()` 绑定预览 Surface；该 API 只保留给双摄校准 `strictPhysical=true`。
- 禁止在 GL 逐帧预览实验开启时继续运行 YOLO、绿光识别或 TextureView 截图校准。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `strength` | Float | `0.75` | 基础补偿强度 | `0.0..1.5` |
| `cropScale` | Float | `1.16` | 防抖预留裁切倍率 | `1.0..1.25` |
| `maxShiftRatio` | Float | `0.075` | 最大平移占视图比例 | `0.0..0.10` |
| `frameSyncEnabled` | Boolean | `true` | 是否使用 Camera2 帧时间驱动 | 关闭后由陀螺仪事件实时驱动 |
| `frameTimestampOffsetMs` | Float | `0.0` | 帧时间补偿偏移 | `-80..80ms` |
| `horizontalGain` / `verticalGain` | Float | `2.7 / 0.35` | 横纵轴增益 | `0.0..4.0` |
| `rollCompensationEnabled` | Boolean | `false` | 是否补偿 Roll 旋转 | 默认关闭，避免过度旋转影响判断 |
| `displayZoomRatio` | Float | `1.0` | Camera2 之外的显示层倍率，参与有效倍率建模 | 调用方传入，必须大于等于 `1.0` |
| `renderScale` | Float | `cropScale` | 最终渲染缩放，用于计算可用裁切边界 | 不能小于 `cropScale` |
| `residualReleaseEnabled` | Boolean | `true` | 静止瞄准且残留较大时加快释放补偿残留 | 禁止替代正常跟随参数调试 |
| `residualAntiWindupEnabled` | Boolean | `true` | 平移达到边界时回灌平滑状态，避免积分残留继续堆积 | 只作用于自研 EIS 模型 |

## 复用方式
- 普通预览继续打开父逻辑相机；只通过 `CameraScreen.applyPreviewTextureTransform()` 合成 UI 居中矩阵和自研 EIS 矩阵。
- Camera2 帧同步复用 `Camera2Controller.onFrameTimingChanged`，读取 `SENSOR_TIMESTAMP`、`SENSOR_EXPOSURE_TIME` 和 `SENSOR_ROLLING_SHUTTER_SKEW`。
- `CameraScreen` 调用 `gyroPreviewDisplayZoomRatio()` 和 `gyroPreviewRenderScale()`，再传给 `GyroPreviewStabilizer.update()` / `updateForFrame()`；有效倍率必须按 `光学 * 数码 * 显示层` 计算。
- 调参优先使用 `更多 -> 自研EIS参数` 和 `更多 -> 防抖标定`，不要直接改默认参数。

## 注意事项
- Android 12+ 高频传感器采样需要 `android.permission.HIGH_SAMPLING_RATE_SENSORS`。
- `GyroPreviewStabilizer` 输出单位是预览像素，不是 1080x1920 校准坐标。
- 自研 EIS 会改变显示层视觉位置；普通/双摄校准面板期间暂停补偿，减少坐标采集漂移。
- TextureView 矩阵缩放必须使用基础显示缩放叠加 EIS 裁切缩放；不能用 `max(baseScale, cropScale)` 吞掉高倍率下的防抖裁切。
- `gyro-preview-model` 日志中的 `zoom=o*d*v` 用于调试补偿模型，不得反写到 Camera2 请求倍率或校准样本倍率。
- 系统 OIS 仍由 `setOisEnabled()` 显式控制，系统 Camera2 EIS 仍默认 OFF。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:assembleDebug --no-daemon`
- 已覆盖边界：无陀螺仪自动拒绝开启、传感器注册异常回退、GL 模式禁用识别/截图校准、普通预览逻辑 Session 保持不变、显示层倍率参与补偿、残留释放和 anti-windup 诊断字段编译通过。
- 未覆盖风险：不同机型陀螺仪坐标轴、rolling shutter 时间戳和 HAL active physical id 行为需要真机调参验证。

## 给 AI 的指令
优先复用本条目的 canonical code path。除非哥哥明确要求重构，否则只做最小必要适配，不重新设计同类防抖或相机 Session 逻辑。
