# android-camera2-low-light-luminance

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、Camera2、Jetpack Compose
- 标签：camera2, low-light, luminance, diagnostics, capabilities
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\lowlight\LowLightBoostCharacteristics.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\lowlight\LowLightBoostInfo.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\Camera2Controller.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
- 最后更新：2026-09-20
- 作者：Codex Agent

## 适用场景
- 查看当前相机会话的低光增强工作亮度范围，在日志中排查逻辑相机与物理镜头的能力差异。

## 禁止场景
- 禁止将静态范围当成实时照度、HDR 支持或低光增强运行状态。
- 禁止用官方示例值替代设备返回；禁止读取普通逻辑预览候选物理镜头后冒充会话能力。
- 禁止启用 EIS；如需稳定，仅允许 OIS 或算法层物理补偿。本条目也不启用低光增强、不设置 AE。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| CONTROL_LOW_LIGHT_BOOST_INFO_LUMINANCE_RANGE | Range<Float>? | 设备返回 | 工作亮度区间，单位 lx | API 35+；可为 null |
| lowerLux | Float? | null | 最低可靠工作亮度 | 非有限数、负数明确报异常 |
| upperLux | Float? | null | 启用该 AE 模式后的触发亮度上界 | 小于等于此值的场景可触发增强；不是自动启用开关 |
| aeModeSupported | Boolean? | null | 可用 AE 模式是否含低光增强模式 | null 表示未提供模式列表 |

## 复用方式
- `LowLightBoostCharacteristics.read(chars, cameraId, physical)` 只读并输出 Camera2Ctrl 日志。
- `Camera2Controller` 只在打开会话时调用，关闭时清空；主面板通过现有回调模式显示最新快照，不逐帧查询。
- `LowLightBoostInfo.panelText` 保留原始 Float 精度、相机 ID 和单位，缺失/旧 API/读取失败单独显示。

## 注意事项
- 普通预览读取逻辑相机，严格物理校准读取绑定的物理镜头，与控制器实际使用的 characteristics 保持一致。
- 支持低光 AE 模式不表示允许与 HDR/高帧率同时开启，本次没有启用或测试这类组合。
- 依据：[Android 官方参数说明](https://developer.android.com/reference/kotlin/android/hardware/camera2/CameraCharacteristics#CONTROL_LOW_LIGHT_BOOST_INFO_LUMINANCE_RANGE:kotlin.Any)。

## 测试与验证
- 构建：`gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon`。
- `LowLightBoostInfoTest`：字段精度、逻辑/物理来源、null、旧 API、读取失败、NaN、无穷大、负值、倒置区间、零区间。
- `LowLightBoostInstrumentedTest`：枚举真实逻辑/物理镜头并对照 API 原始返回；验证主面板与前后台恢复。
- 未覆盖风险：各厂商声明的亮度区间是否对应实际画质效果；只读结果不构成低光增强效果验证。

### 真机返回记录
- 日期：2026-09-20；型号：SM-S9480；Android 16 / API 36。
- 应用和设备测试包构建通过，26 项单元测试与设备元数据读取测试通过。
- 测试包已安装，因手机锁屏，主面板显示及前后台恢复设备测试暂未执行；本轮未重跑全量 Lint。

| 来源 | 相机 ID | 亮度范围原始值 | 低光 AE 模式支持 |
|---|---|---|---|
| 公开相机 | 0、1、2、3 | null | false |
| 相机 0 关联物理镜头 | 2、5、6、7 | null | false |

- 各项读取均无异常。当前设备没有可报告的实际区间，主面板显示“未提供”。
- 仅记录当前固件对本应用暴露的标准能力，不代表原厂相机私有功能，也不以官方示例或其他机型范围补值。

## 给 AI 的指令
优先复用上述标准路径。只读能力显示，不改曝光、HDR 或防抖配置。
