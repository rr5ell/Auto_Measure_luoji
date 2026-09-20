# android-camera2-exposure-display

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、Camera2、Jetpack Compose
- 标签：camera2, exposure, diagnostics, lifecycle
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\exposure\ExposureReading.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\Camera2Controller.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
- 最后更新：2026-09-20
- 作者：Codex Agent

## 适用场景
- 对照环境照度显示相机的实际曝光时间；支持普通、GL/HDR 预览，不读取图像、不依赖 YOLO 或防抖。

## 禁止场景
- 不得把请求曝光、帧间隔或静态范围冒充实际曝光；不得仅凭曝光值认定环境照度或逆光。
- 严格物理输出不得用逻辑结果替代缺失的物理结果。
- 禁止启用 EIS；如需稳定，仅允许 OIS 或算法层物理补偿。本能力不修改 HDR、AE 或任何相机请求。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| SENSOR_EXPOSURE_TIME | Long? | 设备返回 | 原始单位 ns | null 为未提供，非正数异常 |
| 显示精度 | Int | 3 位小数 | ms = ns / 1,000,000.0 | 使用浮点除法，不丢失亚毫秒部分 |
| 刷新间隔 | Long | 500 ms | 只采样最新快照 | 不阻塞相机回调 |
| 过期时间 | Long | 2,000,000,000 ns | 接收时刻使用 elapsedRealtimeNanos | 过期或时间倒退不显示旧数值 |

## 复用方式
- Camera2Controller.latestExposureReading 只提供当前会话代次快照；closeCamera 清空。
- 使用 ExposureReading.panelText(nowNs) 统一格式和时效检查；日志 tag 为 CameraExposure。
- 严格物理模式在 API 28+ 使用 physicalCameraResults；其他模式报告逻辑相机结果。

## 测试与验证
- 构建：`gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon`。
- ExposureReadingTest 覆盖单位、亚毫秒、非法值、缺失、超时边界及来源；ExposureInstrumentedTest 验证曝光与照度同时可见及恢复。
- 未覆盖风险：厂商物理结果缺失，HDR 下元数据语义与内部多曝光实现差异；本工具只报告标准 API 的返回值，不推断内部曝光组合。
- 2026-09-20：Debug 应用、设备测试包和 32 项单元测试通过；ADB 无设备，未执行真机测试，未重跑全量 Lint。

## 给 AI 的指令
优先复用标准路径，保持只读，修改显示不能影响测量或相机帧时序。
