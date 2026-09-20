# Auto_Measure_luoji

Android 自动测量工具应用。

相机状态区显示实时“预览 FPS”，每秒更新，支持普通 TextureView 和 GL/HDR。只统计新画面的更新次数，不是相机请求帧率或 YOLO 推理速度；停帧显示 0，尚未就绪显示 `--`。

当前 Android 构建版本：`0.8.34 (834)`。

本版已知问题：HDR/SDR 切换过程中可能出现 `CameraDevice was already closed` 并暂停自动 HDR；本次测试分发未包含相机会话竞争修复。

顶部“自动 HDR”打开统一控制面板，包含自动开关、手动 HDR 和曝光阈值/停判时长。自动模式默认关闭；启用后，实际曝光 `< 0.3 ms` 或 `> 25 ms` 为开启条件，`0.3~25 ms`（含边界）为关闭条件。自动判断与主面板共用每 500 ms 更新的曝光读数，须连续 4 条不同帧的数据都满足对应条件才切换，中途不满足就重新计数；重复帧不累计，不能跨镜头、会话、参数修改或业务暂停拼接样本。每次会话成功切换后停判 10 秒，期间不缓存条件，到期从随后更新的第 1 条重新收集 4 条有效曝光。阈值和时长可修改保存，自动开关也会记忆；关闭自动模式保留当前 HDR 状态，之后才可手动切换。主面板显示 HDR 开关、切换中、自动/手动、停判倒计时及“开启/关闭确认 n/4”。不同新帧的曝光数值可以相同，不要求数字改变。

自动 HDR 不在后台、校准、测量、统计、绿光对比、防抖标定或会话切换期间改变预览；曝光缺失或过期时保持现状。设备拒绝 HDR 或发生相机/GL 错误时保留自动开关及其偏好，主面板显示“自动，故障暂停”和原因，不自动反复重试。相机恢复就绪且业务空闲后，可在自动 HDR 面板点击“重新判断”，重新累计 4 条，剩余停判时间不变。故障时仍保留原有 SDR 恢复路径，这不属于曝光条件触发的正常开关；校准中禁止故障回退 SDR。参数修改不缩短已经开始的停判窗口，新时长在下一次切换成功后使用；详见 [自动 HDR 条目](docs/code-library/entries/android-camera2-auto-hdr-exposure.md)。

环境照度下方显示“曝光时间：xx.xxx ms”，读取当前相机 `CaptureResult.SENSOR_EXPOSURE_TIME` 的实际返回值，每 500 ms 刷新并写入 `CameraExposure` 日志。该值不是设置值或帧间隔，不依赖 YOLO 或防抖；无返回、异常或超过 2 秒没有新帧会明确提示。普通、GL/HDR 预览共用采集回调，严格物理校准只读取对应物理结果，不冒用逻辑相机值。

主面板在 FPS 下方显示“环境照度：xx.x lx”，来自 `Sensor.TYPE_LIGHT`，替代原来的静态低光增强范围。数值保留一位小数，变化时最多每秒更新两次，同时写入 `AmbientLight` 日志；无传感器、等待读数和监听失败分别显示。退到后台停止监听，回到前台重新等待新读数。该值是手机光线传感器测得的环境照度，不是后置相机目标亮度；不会自动切换 HDR 或修改相机请求。详见 [环境照度条目](docs/code-library/entries/android-sensor-ambient-light.md)。

## 本次更新（2026-09-15）

已同步 Auto_measure `2e14622` 的自研 EIS 逻辑与默认参数。顶部提供 HDR 开关和 LSC 模式按钮；详细参数、兼容条件与验证步骤见 [同步说明](docs/eis-hdr-lsc-sync.md)。

GL 可开启 YOLO 识别，运行时自动使用 YUV 图源，不改写原有图源偏好。HDR 在设备允许 HLG10 + SDR 双路输出时也可识别：HLG10 用于预览，标准 YUV 用于模型。预览与推理解耦，先执行模型再注册 GL 结果，来源帧未被 GL 渲染不得阻断模型；最新异步框最多保留 200 ms，不包含目标运动跟踪。GL/HDR 现支持普通校准、绿光识别及能力允许时的双摄校准，按需读取不含检测框的预览帧。详见 [HDR 校准](docs/hdr-calibration.md) 和 [GL 异步识别](docs/gl-yuv-detection.md)。

切换 HDR 保留原来的识别开关状态；会话重建期间暂停推理并清除旧框和测距触发，会话就绪后自动继续。原来关闭识别时不会自行开启。

HDR 下可通过“更多 -> YOLO单框/YOLO多框”调整阈值和单框策略；YOLO 仍固定使用 YUV。校准与绿光识别单独读取 GL/HDR 预览帧，不使用旁路 SDR YUV。校准期间禁止切换 HDR，异常不自动降级；系统 EIS 固定关闭。

## 技术栈

- **语言**: Kotlin
- **UI 框架**: Jetpack Compose
- **最低 SDK**: Android 7.0 (API 24)
- **目标 SDK**: Android 16 (API 36)
- **构建工具**: Gradle (Kotlin DSL)
- **相机**: Camera2 API + TextureView
- **蓝牙**: Android BLE API（BluetoothLeScanner / BluetoothGatt）
- **AI 推理**: TensorFlow Lite 2.16.1 + GPU Delegate
- **GPU 后端**: LOCAL_GPU → GMS_GPU (Play services) → CPU (XNNPACK) 三级降级
- **语音推理**: Sherpa-ONNX SenseVoice + Silero VAD（第三方包就绪后本地识别）

## 项目结构

```
app/src/main/java/com/example/auto_measure_luoji/
├── feature/                # 功能模块（每个功能独立文件夹）
│   ├── camera/            # 相机预览与镜头控制
│   │   ├── ui/           #   CameraScreen, ZoomSlider, CrosshairOverlay
│   │   ├── data/         #   LensCandidate, LensScanner
│   │   └── domain/       #   Camera2Controller, SelectLensUseCase
│   ├── calibration/       # 校准与绿光识别
│   │   ├── ui/           #   CalibrationPanel, DualCalibrationPanel
│   │   ├── domain/       #   CalibrationManager, DualCalibrationManager
│   │   ├── model/        #   LaserPoint, LaserResult, CalibrationParams, Sample
│   │   ├── detector/     #   LaserDetector
│   │   └── util/         #   ImageUtils, GallerySaver
│   ├── bluetooth/         # BLE 蓝牙通信
│   │   ├── ui/           #   BluetoothPanel, DeviceControls
│   │   ├── data/         #   BleDevice, BleCommand
│   │   └── domain/       #   BleConnectionManager
│   ├── detection/         # YOLO 目标检测
│   │   ├── ui/           #   DetectionOverlay
│   │   ├── data/         #   AssetUtils
│   │   ├── domain/       #   YoloDetector, OutputReader, Nms
│   │   └── model/        #   DetectionResult
│   └── common/            # 共享组件
│       └── ui/           #   LogViewer
└── ui/theme/              # 主题配置
```

## 功能列表

### 相机镜头控制 (v0.2.0)
- 后置镜头扫描与分类（TELE / WIDE / ULTRA_WIDE）
- 自动锁定最大长焦镜头（eq35mm 最大）
- 普通预览打开最大长焦候选所属父逻辑相机，保持纯逻辑相机 Session，不再用 `OutputConfiguration.setPhysicalCameraId()` 绑定预览 Surface；实际活跃物理镜头由厂商 HAL 按逻辑多摄策略回传
- 顶部固定区提供 `HDR`、`LSC` 和 `更多`，滚动区保留 `识别`、`校准`，`识别` 放在首位，避免被其他按钮挤到不可见位置
- `广角/广角中` 收纳在 `更多` 菜单中，可在主界面切换到广角镜头；已在广角时再次点击会切回默认镜头，校准流程中禁用
- 进入普通预览后直接放大到最大焦距对应的光学基准倍率 + `0.1x`，倍率滑块最低只能回到该上浮后的基准倍率，最高固定为 `24x`
- 倍率滑块使用父逻辑相机 Camera2 `CONTROL_ZOOM_RATIO` 优先链路执行数码放大；请求超出设备 `CONTROL_ZOOM_RATIO_RANGE` 时回退 `SCALER_CROP_REGION`
- `更多 -> 纯UI放大` 提供实验开关，开启后普通模式下 Camera2 保持当前逻辑相机镜头的最低有效底倍，预览显示层通过 `TextureView.setTransform(Matrix)` 补足额外放大；双镜头校准不受该开关影响
- 校准锁定坐标后，倍率文本显示视觉总倍率：普通模式为 `currentEquivRatio * wrapperScale`，纯 UI 模式为 `uiCameraBaseZoomForLens() * wrapperScale`；该值只用于 UI 显示，不写入 Camera2 或校准样本
- 主界面状态区会用 35mm 等效焦距显示当前画面实际活跃物理镜头；如果厂商 HAL 未回传 `activePhysicalId`，会显示未上报
- 主界面状态区会显示最近一次 `zoom: backend=...`，应用内 Log 面板也会显示 `Camera2Ctrl` / `CameraScreen` 的 zoom 日志
- 历史 `ZoomController.applyZoom()` 已委托到同一 Camera2 zoom 链路，避免普通倍率重新变成纯 crop 或 UI 放大
- 当前镜头为广角镜头时，普通校准开始会请求 5x 采集；请求超出 `CONTROL_ZOOM_RATIO_RANGE` 时由中心 crop 回退处理
- 当前镜头为长焦/非广角镜头时，普通校准保持当前逻辑相机倍率采集，且不得低于最大焦距对应的光学基准倍率 + `0.1x`
- 普通校准和双镜头校准采集期间会把 Camera2 AE 曝光补偿切到 `-2.0 EV`，退出校准面板后恢复 `0 EV`
- 双镜头广角 zoom 在 API 30+ 优先使用 CONTROL_ZOOM_RATIO，低版本或失败时回退 SCALER_CROP_REGION
- `更多` 菜单提供 OIS 开关；系统 EIS 固定 OFF，控制器禁止 ON/PREVIEW 请求
- `更多 -> 自研EIS` 提供应用层陀螺仪预览防抖：普通预览仍使用逻辑相机 Session，只在 TextureView/GL 显示层叠加缩放、平移和 Roll 矩阵，不改变双摄校准物理绑定逻辑
- 自研 EIS 有效倍率按 `光学倍率 * Camera2 数码倍率 * 显示层倍率` 建模，纯 UI 放大、校准居中和防抖裁切会进入补偿计算；长焦静止瞄准时包含输出加速度限幅、软边界限幅、重新瞄准释放、大残留释放和 anti-windup 限幅回灌
- `更多 -> 自研EIS参数` 可调整强度、跟随、裁切、方向反转、X/Y 互换、帧同步、倍率补偿、软边界、重新瞄准、瞄准稳定、Roll 补偿、诊断日志和 GL 逐帧预览实验；HUD 会显示 `display/render/release/residual/still/windup` 便于真机调参
- `更多 -> 防抖标定` 会按相邻预览帧的亮度匹配估计实际位移，并和自研 EIS 补偿量对比，输出“反/少/多/准/弱”调参提示
- 禁止启用系统电子防抖 (EIS)，仅使用 OIS 或应用层补偿；校准采集期间暂停应用层补偿

### 语音识别
- 第三方语音包就绪后优先使用 Sherpa-ONNX SenseVoice + Silero VAD 本地识别，不再依赖系统语音服务。
- 如果系统 `SpeechRecognizer` 不可用且第三方语音包未就绪，会直接弹出第三方语音包下载入口；下载支持断点续传。

### 校准与绿光识别 (v0.3.0 ~ v0.4.0)
- 绿光识别算法（LaserDetector）
- 绿光识别实际输入为归一化后的图片 bytes：预览帧先缩放到 `1080x1920`，再编码为 PNG bytes 传入检测器
- `更多` 菜单中的 `绿光识别` 可展开新旧算法对比面板；`识别开关` 会用同一帧分别输出新算法和旧算法结果、坐标差值和两组 marked 图；旧算法保留旧 resize 与旧 bright-core refine，不包含大绿光区域几何中心修正
- 两点校准：a/d+b 反投影模型，预测任意距离下的 1x 坐标
- 锚点居中：以预测坐标为中心放大预览
- 校准面板：距离输入、采集、应用目标距离；距离输入为空且 BLE 已连接时会自动测距、回填距离，并在 500ms 稳定延迟后采集图片与识别绿光；绿光识别失败会提示并允许重试

### 绿光算法对比 App
- 普通 Debug 包：`.\gradlew.bat :app:assembleDebug`，包名 `com.example.auto_measure_luoji`
- 绿光对比包：`.\gradlew.bat :app:assembleLaserCompare`，包名 `com.example.auto_measure_luoji.lasercompare`
- 对比包应用名为 `Auto_Measure_luoji 绿光对比`，可与主 App 同时安装，用于真机验证新旧绿光算法坐标差
- 对比包顶部固定显示 `绿光对比` 按钮；主包通过 `更多 -> 绿光识别` 打开普通绿光对比入口

### 双镜头校准 (v0.7.0)
- 点击“校准”可选择普通校准或双镜头校准，普通校准保留原流程
- 双镜头校准会扫描广角和大于广角的长焦候选，并允许选择一个长焦锁定
- 锁定双镜头后，如果 BLE 已连接，会立即发送打开绿光命令
- 选择长焦后可选择“已有双镜头坐标”，直接输入广角/长焦两组 100m 坐标；没有坐标时继续走 d1/d2 双镜头采集
- 双镜头 d1/d2 输入为空且 BLE 已连接时，点击输入按钮会自动测距并填入距离
- 第一轮和第二轮均依次采集广角 5x、长焦物理镜头原生 1x 的绿光坐标
- 广角低于长焦阈值时通过真实相机 zoom 采集，不只做 UI 放大
- 广角标定会反算 1x 下的 100m 坐标，低于长焦阈值的倍率都使用该坐标重新居中
- 达到长焦阈值后切换到长焦物理镜头，并使用长焦自己的 100m 坐标

### BLE 蓝牙通信 (v0.5.0)
- 扫描、连接、断开 SWR005 "Mate X" 激光测距仪
- AT 命令协议：测距、绿光开关、电池查询
- 测距按钮（按下-松开模式）、绿光开关按钮
- 距离数据实时显示

### 中文语音热词识别 (v0.8.0)
- `更多` 菜单提供“语音”入口，点击后申请录音权限并持续识别
- 当前只处理中文“测距”“扫描”“扫描旗杆”三个热词
- 命中后只提示所属类别：`测距` 或 `扫描`，暂不触发真实测距或扫描动作
- 第三方语音包就绪时优先使用 Sherpa-ONNX SenseVoice + Silero VAD 本地识别；未就绪时才回退 Android 系统 `SpeechRecognizer`
- Android 13+ 系统识别会检查中文离线语言包；如果系统返回中文语言包下载错误 12，会允许下载第三方 SenseVoice/VAD 语音包
- 第三方语音包下载使用 App 自己的 HTTPS 下载器，保存到应用私有目录，支持 `.part` 临时文件和 HTTP Range 断点续传；下载后会解压出 `model.int8.onnx`、`tokens.txt` 和 `silero_vad.onnx`
- App 已随包携带 Sherpa-ONNX Android native 库，APK 体积会明显增加；模型权重仍按需下载，不打进 APK
- 设备确认系统离线中文包不支持后会记住该状态，后续直接走系统默认中文识别，避免反复触发系统离线包错误
- 如果系统不支持 `checkRecognitionSupport`（错误 14），会直接降级到系统默认中文识别，不再先启动离线识别
- 识别状态机包含启动互斥、取消后释放等待和 busy 退避，避免重复 `startListening`
- 语音错误可在 Logcat 过滤 `ChineseVoiceHotword|SherpaHotword` 查看，包括系统 fallback、Sherpa 初始化、SenseVoice 识别结果和 busy 退避
- 识别会话结束、未命中或超时后会自动重启，维持按钮开启后的持续监听
- Sherpa 本地识别使用 `AudioRecord 16kHz PCM16 -> Silero VAD -> SenseVoice OfflineRecognizer -> 热词匹配`；热词仍只做命中提示，不直接触发业务动作
- `HOTWORDS.md` 和 `DEV_GUIDE.md` 记录热词来源与 Speech2Text 参考实现

### YOLO 目标检测 (v0.6.0 ~ v0.6.2)
- TFLite 实时推理（GPU 三级降级：LOCAL_GPU → GMS_GPU → CPU）
- 检测开关按钮（"识别" / "识别中"）
- `更多` 菜单中的 `图源` 可切换 YOLO 检测前的 `TextureView` 截图源尺寸：默认 `1080x1920`，4K 为 `2160x3840`，随后按模型输入 tensor 转换到模型尺寸（如 `1280x1280`）
- `更多` 菜单中的 `YOLO源` 可在 `预览截图` 与 `YUV数据流` 间切换；YUV 模式使用 Camera2 `ImageReader` 的 `YUV_420_888` 最新帧，实时识别走 `detectFromYuv()`，native 预处理不可用时会回退 Kotlin 预处理；检测日志会输出当前 active 物理镜头、选中镜头和倍率，便于排查预览/YUV 来源是否一致
- `更多 -> YOLO单框` 可设置旗杆识别后处理：默认只保留最高置信度目标且阈值为 `0.25`，也可切回原始多框阈值 `0.10`
- 每 10ms 帧捕获 → Letterbox 预处理 → 模型推理 → NMS → 检测框绘制
- 支持 FLOAT32 / UINT8 / INT8 模型输出
- 单类别 "flag" 检测（可替换模型文件扩展）
- 30 秒检测成功率统计按钮（命中率统计）
- AT 命令防抖：轮询模式 + 2s 冷却，防止连续发送

### 日志查看器 (v0.5.0)
- 全屏日志查看（支持文本选择）
- 全选复制功能
- 多 TAG 日志聚合

## 开发规范

详见 [CLAUDE.md](CLAUDE.md)，核心要点：

- 每个功能独立文件夹，feature-based 分层
- 代码提交前必须通过 5 步审查流程
- 新功能必须在分支开发，确认后合并
- 每次修改同步更新文档和 CHANGELOG

## 构建与运行

```bash
# 调试版
./gradlew assembleDebug

# 安装到设备
./gradlew installDebug
```

## 变更日志

详见 [CHANGELOG.md](CHANGELOG.md)
