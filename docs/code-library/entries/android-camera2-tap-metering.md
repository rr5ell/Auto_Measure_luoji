# android-camera2-tap-metering

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、Camera2、Compose、OpenGL ES
- 标签：camera2, metering, coordinate-map, exposure
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\tapmetering\TapMeteringPoint.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\gldetection\GlDetectionCoordinates.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\centermetering\CenterMeteringRequest.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\tapmetering\TextureMeteringPoint.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\tapmetering\TapMeteringOverlay.kt
- 接入：CameraScreen.kt、Camera2Controller.kt、GlStabilizedPreviewView.kt。
- 最后更新：2026-09-22
- 作者：Codex Agent

## 适用场景
- 用户点击预览暗部，交给设备 AE 选择快门与 ISO，而不是给不同区域设置不同曝光。

## 禁止场景
- 禁止启用 EIS；如需稳定，仅允许 OIS 或算法层物理补偿。
- 不用于自动跟踪对象、恢复过曝纹理或承诺原厂 HDR 效果；不改 AF、光圈或显示滤镜。

## 核心参数
| 参数 | 默认值 | 边界 |
|---|---|---|
| tap_exposure_metering_enabled | false | 只持久化开关，不存目标坐标 |
| AE 区域宽高 | 可见预览 30%，纯 UI 放大再除显示倍率 | 复用中心区域计算，区域限制在有效预览裁切内 |
| 区域权重 | 1000 | 不保证设备忽略其他区域 |
| 点击标记 | 1500 ms | 只表示点击受理，不代表 AE 已收敛 |
| GL 坐标请求 | 300 ms 超时、已显示帧不超过 1500 ms | 旧会话/无效矩阵/黑边拒绝 |

## 复用方式
- screenToBuffer 为现有检测坐标正向映射的逆向，输入采样矩阵 S、显示矩阵 D 与屏幕像素，输出原始缓冲区归一化坐标；不要再次按传感器角度旋转。
- TextureView 先读取 getTransform 并求逆，再使用同一 SurfaceTexture 采样矩阵约定；GL 在 GL 线程使用 displayedSampling/displayedTransform，不能取下一帧矩阵。
- 点击 AE 临时设置 ON/解锁，关闭或暂停恢复此前模式/锁定及区域；区域拒绝复用中心测光恢复逻辑。
- 切镜头、变焦、预览重建、前后台和校准变化清空目标；测量期间拒绝新点击。自动 HDR 和五区 EV 暂停但不清除偏好。

## 测试与验证
- 命令：gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileLaserCompareKotlin --offline --no-daemon。
- 单测：TapMeteringTest、ExposureReadingTest。
- 设备：CenterMeteringInstrumentedTest、TapMeteringUiTest，及既有 GL/显示回归。
- 2026-09-22：107 项单测、Debug/测试 APK 与 laserCompare Kotlin 编译通过；7 项模拟器回归通过，普通与窄屏大字体状态已检查。三星安装成功后 ADB 断开，未完成最终真机回归，HDR 场景未验证。
- 未覆盖风险：实际手机 AE 的目标权重、镜头切换与厂商畸变、HDR 内部多帧合成、连续运动后的测光目标偏离。

## 给 AI 的指令
复用矩阵与模板回退，不另外手算一套屏幕旋转；曝光快照必须读真实 CaptureResult，禁止由请求值/静态光圈冒充。模式保持手动 EV，不能同时运行五区自动补偿。

## 版本恢复记录
- 2026-09-22，Codex Agent：按用户要求恢复 `94fbea4`，当前 app 源码/测试与该提交一致。
- 后续限幅实现（`ecb1133`、`c5386b0` 中的 TapExposurePolicy）状态：superseded，已由本条目的原生 AE 区域实现替代；历史验证记录保留在 CHANGELOG 与 ERROR_REPORT，不能视为当前版本验证。
- 不重新引入固定 112 亮度目标、点击 EV 累加和“已达微调上限”提示；原生 AE 仍可能有较大亮暗变化，不能承诺厂商内部 HDR 行为。
- 本轮 107 项单测、Debug 应用/测试 APK 与 laserCompare Kotlin 编译通过，app 目录与 `94fbea4` 无差异；当前无 ADB 设备，未安装及执行真机回归。

## 三星私有测光探针
- 2026-09-22，Codex Agent。标准测试路径：`E:\StudioProjects\Auto_Measure_luoji\app\src\androidTest\java\com\example\auto_measure_luoji\feature\camera\domain\samsungmetering\SamsungMeteringProbeTest.kt`。
- 用途：验证 `samsung.android.control.meteringMode` 是否可请求及读回，并恢复模板原值；不是生产设置开关，不推断私有枚举 4 的含义。
- 参数：`samsungMeteringProbe=true` 显式运行；另需 `allowUnlistedVendorKey=true` 才允许直接构造未列举的公开 Key。无参数默认跳过，原值无法读取时拒绝写入。
- 禁止启用 EIS；如需稳定，仅允许 OIS 或算法层物理补偿。本测试不启用任何防抖功能，不使用反射或 Root，不更改正式应用偏好。
- 测试条件：公开镜头 0，1920x1080 YUV 单输出；复用 CenterMeteringRequest，缓冲区归一化点 (0.65, 0.6)，区域 (2040, 1415, 1224, 689)，权重 1000、EV 0，固定 AF。各阶段跳过前 3 秒，再采集 12 帧。

| SM_S9480 阶段 | 请求/回传 | 曝光时间 | ISO | 光圈 | AE 状态 |
|---|---|---|---|---|---|
| 基线 | 0 / 0 | 9.991324 ms | 72 | f/1.4 | 2 |
| 模式 4 | 4 / 4 | 9.991324 ms | 72 | f/1.4 | 2 |
| 恢复 | 0 / 0 | 9.991324 ms | 72 | f/1.4 | 2 |

- 请求/结果枚举列表均不包含此字段：保守路径预期停止；直接公开 Key 路径真机测试通过。camera-service watch 同样记录请求/结果的 0 -> 4 -> 0，测试后已停止监控并释放相机。
- 构建：`gradlew.bat :app:assembleDebugAndroidTest :app:testDebugUnitTest --offline --console=plain --max-workers=4` 通过，单测任务复用缓存。另验证无启用参数时按预期跳过。
- 未覆盖风险：仅一台设备一个场景，未验证正式 GL/HDR 会话，也不是此前用户手动点击的精确目标；未观察到 ISO/快门变化，不代表模式 4 永远无效或已触发原相机处理。
- 复测命令（仅连接并解锁测试设备、确认可临时占用相机后运行）：

```powershell
& 'D:\Android\Sdk\platform-tools\adb.exe' shell am instrument -w -r -e samsungMeteringProbe true -e allowUnlistedVendorKey true -e class com.example.auto_measure_luoji.feature.camera.domain.samsungmetering.SamsungMeteringProbeTest com.example.auto_measure_luoji.test/androidx.test.runner.AndroidJUnitRunner
& 'D:\Android\Sdk\platform-tools\adb.exe' logcat -d -s SamsungMeterProbe:I '*:S'
```

## 三星私有测光实验开关
- 2026-09-22，Codex Agent。新增标准路径：`E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\samsungmetering\SamsungMeteringRequest.kt`，由 Camera2Controller 所有重复请求入口共用。
- “更多 -> 三星测光（实验）”提供关闭、模式3、模式4单选，默认关闭，仅页面内 remember、不写偏好；重新创建页面或重启关闭。选择独立于点击 AE 区域，不自动调整 EV、ISO、快门、AF、测光窗口或防抖。
- 仅三星且能读取当前模板 Int 原值时可用，不依赖 availableCaptureRequestKeys 枚举；失败不开启，不猜测原值。接口只允许 null（恢复原值）、3、4，非法值在写入前拒绝；关闭/校准/严格物理输出/后台暂停恢复当前模板原值，新会话重新读取。
- 同步提交异常恢复并重试原值一次，后续保持回退直到用户关闭或改选其他模式；最终恢复失败向上报告。真实结果仅接收当前会话当前请求，主面板显示请求/回传或未提供，不用请求值冒充实际值。
- 单选 UI 标准路径：`E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\samsungmetering\SamsungMeteringDialog.kt`，复用项目 Material3 单选组和 48 dp 最小触控高度。
- 禁止启用 EIS；如需稳定，仅允许 OIS 或算法层物理补偿。本机私有值 3/4 的意义及画质收益未确认，不能移植为跨厂商通用 API。
- 验证：107 项 JVM 单测、Debug APK/测试 APK 和 laserCompare 编译通过；SamsungMeteringProbeTest 新增生产封装暂停/恢复/模拟拒绝断言及实际回传，SamsungMeteringUiTest 覆盖普通/GL 开关和页面重建默认关闭，三星 2 项测试通过。手动 HDR 下回传 4、预览及状态截图通过。
- 未覆盖：不同厂商/更多镜头组合、真实 HAL 异步故障、长期运行及曝光画质改善。仪器测试通过 `samsungMeteringProbe=true` 显式启用，UI 回归仅用于模板原值 0 的已验证三星。
- 模式3扩展验证（2026-09-22，Codex Agent）：107 项单测及构建通过，探针通过原值 -> 3 -> 4 -> 原值，并覆盖两模式暂停/拒绝回退与非法值不提交；模式3的 12 帧为回传3、9.991324 ms、ISO 25、f/1.4。UI 初次因锁屏失败，解锁后普通/GL 模式选择与重建复位测试通过；手动 HDR 回传3通过，面板截图检查无重叠。不据此推断模式3的实际画质效果。
