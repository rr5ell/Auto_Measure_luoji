# android-camera2-tap-metering

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、Camera2、Compose、OpenGL ES
- 标签：camera2, metering, coordinate-map, exposure
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\tapmetering\TapMeteringPoint.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\tapmetering\TapExposurePolicy.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\gldetection\GlDetectionCoordinates.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\centermetering\CenterMeteringRequest.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\tapmetering\TextureMeteringPoint.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\tapmetering\TapMeteringOverlay.kt
- 接入：CameraScreen.kt、Camera2Controller.kt、GlStabilizedPreviewView.kt。
- 最后更新：2026-09-22
- 作者：Codex Agent

## 适用场景
- 用户点击预览暗部，在全画面 AE 上进行有全局保护的受限微调；不直接让小区域决定整幅快门与 ISO。

## 禁止场景
- 禁止启用 EIS；如需稳定，仅允许 OIS 或算法层物理补偿。
- 不用于自动跟踪对象、恢复过曝纹理或承诺原厂 HDR 效果；不改 AF、光圈或显示滤镜。

## 核心参数
| 参数 | 默认值 | 边界 |
|---|---|---|
| tap_exposure_metering_enabled | false | 只持久化开关，不存目标坐标 |
| HAL AE 区域 | 完整可见预览，1000 权重 | 等待点击与不同点击点保持同一矩形，不因纯显示放大而缩小 |
| 分析点击窗口 | 预览宽高 30%，再除纯 UI 显示倍率 | 复用中心区域计算；仅统计原始 SDR Y，不提交此小窗口给 HAL |
| 微调范围/步幅 | 手动 EV 基线 ±2/3，每步最多 1/3 | 向下量化，粗于 1/3 EV 的硬件保持基线；非快门/ISO 硬限幅 |
| 确认/稳定 | 三条、至少 500 ms / 1 秒 | 独立新样本、错误目标/旧会话不使用，超过 1.5 秒间断重新确认 |
| 点击/全局权重 | 60% / 40%，对数误差目标 112、死区 ±0.35 | 去掉两端各 5% 的 SDR Y 均值，非物理曝光公式 |
| 高光保护 | 高亮比例 >=20% 或全局均值 >=180 | 比例 <12% 且均值 <165 才退出 |
| 暗部保护 | 全局均值 <=96 或暗像素比例 >=45% | 均值 >=112 且暗比例 <=30% 才退出；暗像素 Y<=48 |
| 点击标记 | 1500 ms | 只表示点击受理，不代表 AE 已收敛 |
| GL 坐标请求 | 300 ms 超时、已显示帧不超过 1500 ms | 旧会话/无效矩阵/黑边拒绝 |

## 复用方式
- screenToBuffer 为现有检测坐标正向映射的逆向，输入采样矩阵 S、显示矩阵 D 与屏幕像素，输出原始缓冲区归一化坐标；不要再次按传感器角度旋转。
- TextureView 先读取 getTransform 并求逆，再使用同一 SurfaceTexture 采样矩阵约定；GL 在 GL 线程使用 displayedSampling/displayedTransform，不能取下一帧矩阵。
- 点击模式临时设置 ON/解锁、完整预览测光；点击点只供 HighlightStatistics 的稀疏统计和 TapExposurePolicy 使用。关闭或暂停恢复此前模式/锁定及区域，撤销微调回到手动/校准 EV。
- CameraScreen 同一协程拥有 EV 控制权，五区反馈与点击微调互斥。没有分析帧（部分 HDR 组合）、样本失效或 EV 不支持时不退回旧小区域 AE。
- 切镜头、变焦、预览重建、前后台和校准变化清空目标；测量期间拒绝新点击。自动 HDR 和五区 EV 暂停但不清除偏好。

## 测试与验证
- 命令：gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileLaserCompareKotlin --offline --no-daemon。
- 单测：TapMeteringTest、TapExposurePolicyTest、ExposureReadingTest、RegionalMeteringTest。
- 设备：CenterMeteringInstrumentedTest、TapMeteringUiTest，及既有 GL/显示回归。
- 2026-09-22：107 项单测、Debug/测试 APK 与 laserCompare Kotlin 编译通过；7 项模拟器回归通过，普通与窄屏大字体状态已检查。三星安装成功后 ADB 断开，未完成最终真机回归，HDR 场景未验证。
- 2026-09-22 限幅修订：120 项单测与构建通过；三星实际区域/曝光读数通过，普通/GL 点击与区域请求模拟器用例通过，手动检查微调不超过 2/3 EV。真机断连，点击修订及 HDR 统计断言未在真机复测；额外 GL 同帧回归有时序断言失败，详见 ERROR_REPORT，不宣称整体回归全通过。
- 未覆盖风险：实际手机 AE 的目标权重、镜头切换与厂商畸变、HDR 内部多帧合成、连续运动后的测光目标偏离。

## 给 AI 的指令
复用矩阵与模板回退，不另外手算一套屏幕旋转；曝光快照必须读真实 CaptureResult，禁止由请求值/静态光圈冒充。微调固定以手动 EV 为基线，不能同时运行五区自动补偿。禁止重新把单个点击小区域直接交给 HAL，也不能把区域权重 1000 改小冒充曝光限幅。
