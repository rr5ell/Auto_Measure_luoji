# android-sensor-ambient-light

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、Jetpack Compose
- 标签：sensor, ambient-light, lux, lifecycle, diagnostics
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\ambientlight\AmbientLightMonitor.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\ambientlight\AmbientLightReading.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
- 最后更新：2026-09-20
- 作者：Codex Agent

## 适用场景
- 用硬件光线传感器显示实时环境照度，无须读取相机像素；兼容普通、GL 和 HDR 预览。

## 禁止场景
- 不得把环境照度等同于后置镜头目标亮度、逆光判定或相机静态亮度范围。
- 不得把缺失、等待或失败写成 0 lx，不得把最大量程写成当前读数。
- 禁止启用 EIS；如需稳定，仅允许 OIS 或算法层物理补偿。本功能也不得隐式修改 HDR、AE 或低光增强模式。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| Sensor.TYPE_LIGHT | Int | Android 标准类型 | values[0] 为 lux | 硬件可缺失 |
| lux | Float? | null | 原始照度 | 有限非负值，0 合法 |
| 合并窗口 | Long | 500 ms | 保留最新事件，降低界面和日志刷新频率 | 不丢弃窗口内最终读数 |
| 显示精度 | Int | 1 位小数 | Locale.ROOT 固定小数点 | 不声称传感器具有对应测量精度 |

## 复用方式
- 主线程创建 `AmbientLightMonitor(sensorManager, onReading)`，RESUME 时 start，PAUSE 或释放时 stop；重复调用幂等。
- 复用 `AmbientLightReading.fromLux` 校验读数；主面板使用 panelText，日志保留原始 lux 与状态。
- 后台注销监听并取消延迟回调；恢复时重新等待读数。监听生命周期不能绑定 HDR 或镜头选择。

## 注意事项
- 无新增权限或依赖；SensorManager 使用 SENSOR_DELAY_NORMAL，并在主线程分发回调。
- 光照不变时允许没有新事件，不设置无依据的超时失效；暂停后不得继续展示旧读数为实时值。
- Logcat tag 为 AmbientLight，启动时记录传感器名、量程、分辨率；不读取或记录图像。

## 测试与验证
- 构建命令：`gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon`。
- `AmbientLightReadingTest` 覆盖正常格式、大读数、零值、缺失、负值、NaN、无穷大、状态区分。
- `AmbientLightInstrumentedTest` 覆盖缺失服务、重复启停、实际读数、停止回调与主面板前后台恢复。
- 未覆盖风险：各厂商传感器实际精度、遮挡和朝向的影响；需要真实设备验收。
- 2026-09-20：Debug 应用、设备测试包编译与 29 项单元测试通过；ADB 无设备，未执行真机测试或获取本机 lux，未重跑全量 Lint。

## 给 AI 的指令
优先复用标准路径。只读照度显示，不改相机和模型管线，不引入自动 HDR 策略。
