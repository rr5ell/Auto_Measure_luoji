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
