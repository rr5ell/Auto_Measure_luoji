# HDR 校准与绿光采集

更新时间：2026-09-20。作者：Codex Agent。

## 使用范围
- 先开启 HDR，再从“校准”进入普通或双镜头校准；校准过程中 HDR 开关锁定，不能混采 SDR/HDR 样本。
- “更多 -> 绿光识别”以及绿光对比版本的对应入口支持 GL/HDR。
- HDR 显示仍使用 GL，但校准不再依赖 TextureView，也不会为采集而临时切回 SDR。
- 双摄要求逻辑相机和实际物理镜头都声明 HLG10；预览尺寸取双方交集，最终是否接受该组合仍由 HAL 决定。拒绝时保留 HDR 选择、暂停校准并明确提示，不退回逻辑镜头冒充物理镜头。

## 取帧与坐标
- `GlStabilizedPreviewView.capturePreviewBitmap()` 等待请求之后的新显示帧，读取不含 YOLO 框的输出缓存，1500 ms 超时；不返回旧缓存冒充新样本。
- 校准先等待当前相机会话的 `latestSensorTimestampNs`，拒绝更早的 GL 缓存；切镜头期间暂停采集，图片编码前后复查镜头及视图身份，采集中禁用退出。
- HLG10/BT.2020 数据在独立着色器中解码到线性域，转 BT.709、固定亮度压缩后编码 sRGB，输出 1080x1920 PNG 供已有绿光算法处理。不是 10 位文件导出，也不宣称与屏幕亮度完全一致。
- 正常预览保留 10 位 HLG，新增回读只在采集时发生；RGBA 到 Bitmap 的翻转和转换在后台完成。单次 GPU 回读仍可能产生短暂开销，不用于逐帧推理。
- 输出 FBO 已含采样和当前显示变换，只做 GL 左下原点到 Bitmap 左上原点翻转。校准面板开启时沿用现有动态补偿暂停逻辑；系统 EIS 始终 OFF。
- 色调/色域变化会影响绿光阈值响应，需要重新采集 HDR 样本，不应直接沿用 SDR 的精度结论。

## 保留限制
- 双摄物理校准会话不创建逻辑 SDR YUV，避免混用视角，因此该阶段 YOLO 暂停，退出双摄后恢复。
- YOLO 的图源切换、1080P/4K 预览截图推理仍未接入 GL，继续用独立 YUV 推理。
- “防抖标定”和视觉静止门控仍依赖旧的精确时间戳链路，本次没有只解除禁用；它们不是普通/双摄激光校准。
- 本次不更改防抖默认开关、绿光阈值、距离模型，不上传蒲公英。

## 验证
- 构建：`gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon`。
- `PreviewCaptureInstrumentedTest`：新帧准入、旧帧超时后重试、方向、颜色、平移矩阵和归一化尺寸。
- `HdrPreviewInstrumentedTest`：HLG10 取帧、预览继续、物理镜头切换，不支持硬件时显式跳过。
- `HdrDetectionToggleInstrumentedTest`：HDR 下普通校准入口、开关锁定、退出恢复、绿光入口及 YOLO 回归。
- 真正的两距离绿光标定与测量精度需要现场目标、距离与激光设备；自动化取帧测试不能代替精度验收。

### 本次结果
- Debug APK、测试 APK 构建成功，22 项 JVM 测试通过；已安装到连接的 Samsung SM-S9380。
- 最终真机 5 项仪器测试全部通过、无跳过。物理镜头 5（广角 5x）-> 7（长焦 1x）-> 5 全程 HLG10，HLG 色彩标记及 10 位输出保持，1080x1920 取帧成功并完成绿光算法调用。
- HDR 普通校准入口、校准期间开关锁定、关闭面板后恢复、绿光入口及 YOLO 回归通过。已检查实际相机页截图，校准页面预览约 30 FPS；未输入虚构距离或生成校准参数。
- 首次最终回归受手机息屏和 USB 断连中断，重新唤醒连接后完整重跑通过。
- 模拟器未通过：恢复快照时出现系统 EGL_BAD_CONFIG 崩溃；冷启动软件 GPU 后合成取帧测试仍在 GL 队列屏障超时。该结果作为未解决的环境/兼容风险保留，不算测试通过；相同 EGL 合成测试在手机通过。
- 全量 Lint 仍为原有 8 项错误、56 项警告、1 项提示。错误位于 BLE 权限、语音录音权限、MainActivity 的 Fragment 依赖版本、Manifest 的 ChromeOS 声明；本次未增加 Lint 错误，也未用基线或抑制隐藏问题。
- `git diff --check` 通过；本轮未上传蒲公英、未合并主干。

## 依据
- [Camera2 HDR 输出配置](https://developer.android.com/media/camera/camera2/hdr-video-capture)。
- [物理输出配置](https://developer.android.com/reference/android/hardware/camera2/params/OutputConfiguration)。
- [HLG 传递函数](https://registry.khronos.org/DataFormat/specs/1.4/dataformat.1.4.html)。
