# 自研 EIS、HDR 与 LSC 同步说明

## 同步来源

- 来源：`E:\StudioProjects\Auto_measure`，分支 `feature/hdr-live-preview`。
- 来源提交：`2e14622`，2026-09-14 的 HLG10 实时预览版本。
- 目标：`E:\StudioProjects\Auto_Measure_luoji`，分支 `feature/sync-eis-hdr-lsc`。
- 日期：2026-09-15；作者：Codex Agent。
- 不复制源项目的录制工具、蓝牙测距改动、日志和构建产物。

## 界面入口

- 相机页顶部：`HDR` 开关；`LSC` 按钮显示当前模式，点击选择自动、关闭、快速或高质量。
- `更多 -> 自研EIS`：应用层陀螺仪防抖开关。
- `更多 -> 自研EIS参数`：帧同步、输出低通、瞄准自适应低通、视觉静止门控、补偿输出旁路及原有参数。
- HDR 默认关闭且不跨重启保存；LSC 默认 AUTO，用户选项保存到 SharedPreferences。
- 参数配置版本从 19 升级为 28，首次升级按源项目迁移相关参数。之后保存的参数继续保留。

## 默认参数

| 参数 | 默认值 |
|---|---|
| 基础强度 / 跟随速度 | 0.75 / 5.0 |
| 基础输出低通 / 速度上限 | 0.85 / 720 px/s |
| 裁切倍率 / 最大平移比例 | 1.18 / 0.08 |
| 横向 / 纵向增益 | 1.30 / 1.25 |
| 瞄准转速阈值 / 强度倍率 | 0.04 rps / 2.0 |
| 瞄准跟随 / 输出低通 | 0.8 / 0.70 |
| 瞄准自适应低通 / 最小系数 | 关闭 / 0.62 |
| 软边界 / 起始比例 / 强度 | 开启 / 0.80 / 1.00 |
| 重新瞄准阈值 / 进入与退出残留比例 | 0.012 rps / 0.85 与 0.25 |
| 重新瞄准等待 / 跟随 | 250 ms / 8.0 |
| 重新瞄准混入 / 混出 | 3.0 / 3.0 |
| 残留释放 / 停稳阈值 | 关闭 / 0.015 rps |
| 帧同步 / 时间偏移 | 开启 / +5 ms |
| 视觉静止门控 / 输出旁路 / Roll | 关闭 / 关闭 / 关闭 |

完整默认值以 `GyroPreviewStabilizer.Config` 为准；预设按钮表示不同调参方案，不等同于“恢复默认”。

## 接入约束

- 禁止启用 Camera2 系统 EIS；只允许 OIS 或应用层算法补偿。控制器拒绝 ON/PREVIEW。
- 普通 SDR/HDR 预览均使用父逻辑相机 Session，禁止绑定物理预览 Surface；物理绑定仅保留给双摄校准。
- HDR 复用 HLG10、BT.2020 HLG EGL 表面、RGB10_A2 缓存及源项目 GL 同帧队列。HDR 不等于多曝光融合。
- HDR 开启前检查 Android 13+、相机 10-bit/HLG10 能力、屏幕 HLG 能力和 GPU 扩展；失败恢复 SDR 及开启 HDR 前的 GL 选择。
- HDR/GL 期间禁用 YOLO、绿光及截图校准；HDR 关闭 YUV 分析输出。
- TextureView 与 GL 都按 SENSOR_TIMESTAMP 匹配矩阵；GL 同时缓存图像，TextureView 只在匹配可见纹理时提交。
- 可选视觉门控复用 OpenCV 4.12.0 的 LK/RANSAC 实现，使用独立分析线程和严格同时间戳反馈；无纹理、分析失败不能判成静止。
- LSC 能力来自实际打开的逻辑相机。不支持的模式禁用；切换失败恢复上一请求，不把传感器固有校正误报为可关闭。
- 官方 API 参考：[HDR 输出](https://developer.android.com/media/camera/camera2/hdr-video-capture)、[SHADING_MODE](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#SHADING_MODE)。

## 验证

- 构建：`gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon`。
- 设备回归：`gradlew.bat :app:connectedDebugAndroidTest --offline --no-daemon`。
- 静态检查：`gradlew.bat :app:lintDebug --offline --no-daemon`、`git diff --check`。
- 实测环境：JBR、Gradle 8.13、AGP 8.12.3；Android API 36 模拟器，1080x2400/420 dpi 与 720x1280/320 dpi。
- 最终 Debug APK 和测试 APK 均已生成；6 项 JVM 测试通过，0 失败。
- 安装最终 APK 后执行 `adb -s emulator-5580 shell am instrument -w -r com.example.auto_measure_luoji.test/androidx.test.runner.AndroidJUnitRunner`：6 项通过、1 项跳过。跳过原因为模拟器屏幕未开放 HLG，不能将此结果视为真实 HDR 测试通过。
- 已覆盖同帧绑定、GL 像素非空、竖屏旋转、视觉估计、LSC 参数恢复及 HDR 不支持分支；手动验证顶部按钮、LSC 四种选项、重启持久化及 HDR 拒绝后保持 SDR。
- 360dp 小屏截图发现亮色预览影响按钮文字对比度，已增加顶部半透明底色并复测。
- `GyroPreviewStabilizer.kt` 忽略包名和换行差异后与源项目完全一致；`git diff --check` 通过。
- 全量 Lint 未通过：8 项错误、61 项警告、1 项提示。剩余错误位于本轮未改动文件：BLE 权限检查 3 项、语音录音权限检查 2 项、MainActivity 的 Fragment 版本要求 2 项、Manifest 的 ChromeOS 相机特性声明 1 项。本轮相机相关的新增 API 版本边界、DataSpace 参数注解和 Compose 初始化错误已修正；没有添加全局抑制或基线掩盖原有问题。
- 真机待验证：长焦抖动收益、实际 LSC 画质、HLG10 色彩与亮度、长时间运行及镜头切换；模拟器不代表真实 HDR 支持。
