# Changelog

所有重要变更都会记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [exposure-time-2026-09-20] - 2026-09-20

作者：Codex Agent。

### 新增
- 主面板保留环境照度，增加实际曝光时间（ms，三位小数），每 500 ms 更新；`CameraExposure` 日志同时记录原始纳秒值和逻辑/物理来源。
- 使用当前会话 CaptureResult，不依赖 YOLO 或防抖；严格物理输出只使用绑定镜头的结果，缺失时明确提示。
- 会话代次过滤旧快照，关闭清空；超过 2 秒无新帧显示等待新帧。仅增加只读诊断，不改变曝光、HDR 或防抖设置。
- 增加纳秒换算、缺失/非法值、过期边界单元测试，以及主面板前后台恢复设备测试。

### 验证
- Debug 应用与设备测试包构建成功，32 项单元测试通过，差异空白检查通过。
- ADB 未检测到手机，未安装新包或执行真机界面、遮挡对比测试；本轮未重跑全量 Lint。

## [ambient-light-2026-09-20] - 2026-09-20

作者：Codex Agent。

### 变更
- 主界面 FPS 下方将静态 `LUMINANCE_RANGE` 替换为 `Sensor.TYPE_LIGHT` 实时环境照度，显示一位小数与 `lx`，同步输出 `AmbientLight` 日志。
- 独立前台传感器监听，500 ms 合并最新事件；退到后台或退出页面注销监听、清除待发布读数，恢复时不沿用旧值。
- 无传感器、等待数据、监听失败和非法读数明确区分；真实零照度正常显示，不以零值代替缺失值。
- 移除相机会话的静态范围回调，保留独立元数据诊断工具；不改变 HDR、YOLO、AE 或防抖配置，不启用 EIS。
- 新增照度格式单元测试与设备监听、停止回调、主面板恢复测试。

### 验证
- Debug 应用与设备测试包构建通过，29 项单元测试全部通过，差异空白检查通过。
- ADB 未检测到设备，未安装或执行新增真机测试，未获取本机实时 lux；本轮未重跑全量 Lint。

## [low-light-luminance-2026-09-20] - 2026-09-20

作者：Codex Agent。

### 新增
- 主面板 FPS 下增加低光增强亮度区间，读取 Camera2 `CONTROL_LOW_LIGHT_BOOST_INFO_LUMINANCE_RANGE`，保留原始浮点上下限与 `lx` 单位。
- 日志记录完整参数名、当前会话相机 ID、逻辑/物理来源和低光 AE 模式支持状态；可选字段缺失、旧系统、异常返回分别处理，关闭时清空旧值。
- 仅查询静态能力，不开启低光增强，不修改 AE、HDR、OIS 或系统 EIS 请求；增加格式边界及设备元数据/主面板回归。

### 验证
- Debug 应用与测试包构建通过，26 项单元测试通过。
- SM-S9480 / Android 16（API 36）实测：公开相机 ID `0、1、2、3`，相机 `0` 关联物理镜头 `2、5、6、7` 均返回范围 `null`，低光增强 AE 模式支持状态均为 `false`；主面板应显示“未提供”，不存在可报告的实际区间。
- 设备元数据读取测试通过；只代表当前系统向本应用开放的 Camera2 能力，不推断原厂相机的私有夜景能力。
- 测试包已安装；手机仍锁屏，主面板真机显示及前后台恢复测试暂未执行，本轮未重跑全量 Lint。

## [0.8.33] - 2026-09-20

作者：Codex Agent。

### 发布
- 构建版本递增为 `0.8.33 (833)`，用于蒲公英测试分发。
- 包含预览 FPS、GL/HDR 普通校准、物理双摄校准及绿光识别；校准期间保持 HDR，过滤旧镜头缓存。
- 本次仅调整发布版本，不修改模型、阈值、相机参数或系统 EIS 配置。双摄物理校准阶段 YOLO 暂停，真实两距离标定精度仍需现场验证。
- Debug APK 构建、版本核对与签名验证通过；单元测试任务复用 22 项通过结果，本轮未重跑真机或全量 Lint。
- 蒲公英解析完成，确认版本 `Auto_Measure_luoji 0.8.33 (833)`；[下载页面](https://www.pgyer.com/automeasureluoji)。
- APK SHA-256：`B80282C239AB69E225E9FFCAE8B457E7DE6E6D2B1629D6C0130D539A4F444630`。

## [hdr-calibration-2026-09-20] - 2026-09-20

作者：Codex Agent。

### 新增与修复
- 接通 GL/HDR 普通校准与绿光识别，直接读取新的预览帧，不含检测框。HLG10 通过独立颜色转换生成算法用 sRGB 图片，不借用 SDR 分析流，不改变 HDR 预览。
- 双摄校准检查物理镜头 HLG10 能力并绑定 HDR 输出，使用逻辑/物理镜头共有尺寸；不支持时明确报错，不降级 SDR 或冒用逻辑镜头。
- 根因：旧校准依赖 TextureView 截图，HDR 控制器一律拒绝物理校准；错误处理还会自动退出 HDR。改为专用按需取帧、能力准入和校准期间禁止降级，保存 GL 参数不再无条件关闭校准/识别。
- 保留系统 EIS OFF 和校准期间动态补偿暂停；新增取帧方向、超时、矩阵、HLG10 与物理切镜头回归。精度验收仍需真实两距离激光样本。
- 当前会话时间戳过滤旧镜头缓存，采集中锁定退出，禁止校准时切换 GL 渲染路径。
- 验证：22 项 JVM 测试、最终真机 5 项仪器测试通过，已安装 Debug 包；HDR 广角 5x/长焦切换与 1080x1920 取帧通过。模拟器 GL 屏障超时未解决；全量 Lint 仍有原有 8 项错误。详细结果见 `docs/hdr-calibration.md`。

## [preview-fps-2026-09-18] - 2026-09-18

作者：Codex Agent。

### 新增
- 状态区增加“预览 FPS”，每秒更新一位小数，普通 TextureView 与 GL/HDR 分别按实际新画面计数。
- 重复 GL 绘制、旧画面和 YOLO 推理不计入 FPS；采用单调时钟实际时间差，界面轮询延迟不会夸大读数。停帧显示 0，切换镜头/模式或相机未就绪时重置。
- 新增计数单元测试，扩展 GL 仪器测试验证重复绘制不增加计数；不增加像素回读，不修改相机帧率、模型或系统 EIS 设置。
- 验证：Debug APK 与测试包构建成功，22 项 JVM 测试通过，模拟器 FPS 页面回归通过，覆盖普通预览、GL 和前后台恢复；已检查界面截图。无真机连接，HDR 实机验证待补充，本次未上传蒲公英或重跑全量 Lint。
- GL 综合回归第三次完整通过；前两次分别在预热和检测框有效期检查失败，保留模拟器时序波动记录，未放宽正式结果有效期。

## [0.8.32] - 2026-09-17

作者：Codex Agent。

### 发布
- 构建版本递增为 `0.8.32 (832)`，用于蒲公英测试分发，避免与上一版 `0.8.31 (831)` 混淆。
- 包含 GL 预览与后台 YOLO 解耦、HDR 的 SDR YUV 模型输入、切换保留识别状态和 HDR 阈值设置入口修复。
- 本次仅调整发布版本，不改变模型、阈值、相机参数或系统 EIS 配置。
- Debug APK 构建成功，单元测试任务通过（复用已有 17 项通过结果）；本次未重跑真机或全量 Lint。
- 蒲公英解析成功，平台确认 `Auto_Measure_luoji 0.8.32 (832)`；[下载页面](https://www.pgyer.com/automeasureluoji)。
- APK SHA-256：`D464E3FADBF3957E10804BFCB9C12780298E5489AEFCE91FE7F22692C4E84C87`。

## [hdr-yolo-settings-2026-09-16] - 2026-09-16

作者：Codex Agent。

### 修复
- 根因：HDR 的 YUV 模型识别已接通，但“YOLO单框/YOLO多框”菜单仍沿用 `!hdrPreviewEnabled` 禁用条件。
- 移除阈值入口的旧限制，继续复用原有阈值校验、保存与模型参数传递；不修改用户阈值或默认值。
- GL/HDR 的图源切换、截图校准与绿光识别仍禁用，避免开放尚未适配的功能。
- 扩展 HDR 页面回归，覆盖阈值入口、保存后继续识别及图源禁用边界。
- 验证：17 项 JVM 测试通过，Debug 包与测试包构建成功；SM-S9480 上扩展后的 HDR 页面仪器测试通过，已保留数据安装修复包。本轮未重跑全量 Lint。

## [hdr-detection-state-2026-09-16] - 2026-09-16

作者：Codex Agent。

### 修复
- 真机复现根因：HDR 双路已经正常出帧，但切换函数仍把识别开关置为关闭，导致模型停止。现保留识别意图，会话就绪后自动恢复；原来关闭识别时不自动开启。
- 切换时清理旧检测框、性能文本、有效期及测距触发，不把旧结果带入新会话。
- 真机还捕获到推理结束后读取已关闭 Image 元数据的异常；改为推理前快照时间戳和尺寸，避免跨挂起点读取失效图像。未改模型、阈值或系统 EIS。

### 验证
- 17 项 JVM 测试通过，Debug 包与测试包构建成功，保留应用数据更新到 SM-S9480。
- 分组执行 5 项真机测试通过：HDR 页面双向切换、HLG10/SDR YUV 模型与异步框、无屏幕能力拒绝、普通 GL 慢推理连续预览及像素回归。
- 首次混合运行在普通 GL 测试退出时停顿，终止后分组重跑通过；原因未确认，不计为完整混合套件通过。用户现场确认可以识别，长期负载与定位精度不在本次结论内；本轮未重跑全量 Lint。

## [async-gl-preview-2026-09-15] - 2026-09-15

### HDR 识别接入
- 作者：Codex Agent。根因是 HDR 主动关闭 YUV、禁用识别按钮和推理循环，并拒绝 GL 检测框注册。
- 能力允许时，在同一逻辑会话中配置 HLG10 预览与 STANDARD YUV 分析输出，沿用原 YOLO 后台推理和异步框。
- 双向检查动态范围约束；不支持双路时保留 HDR 预览并禁用识别，实际会话失败仍报错恢复 SDR。系统 EIS 固定 OFF，未改模型与阈值。
- 新增混合动态范围约束单元测试，扩展 HDR 真机测试覆盖 YUV 模型调用与异步框。
- 验证：17 项 JVM 测试通过、两个 Debug APK 构建成功，应用已更新到测试手机；手机锁屏，HDR 双路实际出帧与旗杆检出仍未验证。

### 后续修复：推理输入不依赖 GL
- 作者：Codex Agent。移除推理前的 GL 同时间戳准入条件，先调用 YOLO，再注册异步显示结果；SurfaceTexture 跳帧不再导致 YUV 输入被跳过。
- 注册结果时传递取出 YUV 的单调时间，不给慢推理结果续期；模型完成日志与 GL 显示状态分开记录。
- 真机已有日志确认 GL 下 GPU 模型实际被调用，但该段结果均为 0 个目标；这与输入被跳过是两个问题，现场旗杆识别效果仍需复核。
- 验证：14 项 JVM 测试及 2 项模拟器仪器测试通过，修复包已更新到测试手机；手机锁屏，未确认现场旗杆正样本检出。

作者：Codex Agent。

### 修复
- 根因：旧 GL 显示只接受完成推理的帧，导致预览跟随识别速度刷新，即使关闭 EIS 仍卡顿。
- GL 预览恢复独立处理最新相机帧；YOLO 仍在专用线程串行推理，不固定推理纹理、不积压模型任务。
- 来源帧仅保留轻量元数据；最新异步框最多保留 200 ms，空结果、模式切换及过期均清理旧框。
- 框独立合成到屏幕，当前显示变换重投影不等于目标运动跟踪；过期判定也同步约束测距提示与触发。
- 新增慢推理期间预览持续前进、时钟过期、乱序结果与空框替换回归，详情见 [GL 异步识别](docs/gl-yuv-detection.md)。
- 验证：14 项 JVM 测试及 2 项模拟器仪器测试通过；Debug 包构建成功。全量 Lint 保留原有 8 项错误，本轮无真机连接。

## [gl-yuv-detection-2026-09-15] - 2026-09-15

此节记录最初严格同帧方案，已由上述异步更新取代其显示策略。

作者：Codex Agent。

### 变更
- 修复根因：GL 模式主动卸载模型且禁用识别按钮，原截图识别链路也无法从 GL 取图。现保持模型可用，并临时强制使用现有 YUV native 预处理链路。
- 在推理前固定同时间戳 OES 图像与矩阵；推理完成后将检测框、标签和对应图像写入同一 FBO。等待期间保持上一对输出，不把旧框叠到新图像上。
- 坐标使用原始缓冲区归一化、YUV 逆旋转、SurfaceTexture 采样逆矩阵和实际 GL 显示矩阵，保留 Roll 下四边形与框内判定点语义。
- 模型创建、推理和释放改为同一专用线程；超时、模式切换、尺寸变化和过期结果均释放固定帧。
- HDR 识别、GL 绿光及截图校准限制不变；图像更新速度受推理吞吐限制。验证见 [GL 同帧识别](docs/gl-yuv-detection.md)。

### 验证
- 10 项 JVM 测试通过，2 项真机回归通过；已验证框与标签像素、YUV 模型闭环、图源恢复及前后台切换。
- 全量 Lint 仍有原有 8 项错误，本轮无新增错误；清理新增文件末尾空行。真实旗杆定位精度待现场验证。

## [eis-hdr-lsc-sync-2026-09-15] - 2026-09-15

作者：Codex Agent。

### 变更
- 同步 Auto_measure `2e14622` 的自研 EIS，包含瞄准低通、微步进平滑、重新瞄准边界确认及时间戳连续性修复；配置版本升级为 28，相关默认值随升级迁移。
- 接入同帧 GL 图像/矩阵队列，以及默认关闭的 OpenCV 视觉静止门控；保留当前项目的逻辑相机、倍率与校准坐标模型。
- 相机页顶部新增 HDR 开关和 LSC 模式按钮。HDR 使用 HLG10/10-bit GL，失败恢复 SDR；LSC 支持 AUTO/OFF/FAST/QUALITY、能力检查和持久化。
- 修复根因：旧预览用固定时间节流提交矩阵，图像与补偿可能错帧；现在按相机时间戳配对，Compose 重组不能绕过配对。
- HDR 重建预览增加会话代次与 Surface 身份检查，避免旧回调关闭新会话。
- 按当前硬件约束，系统 EIS 固定 OFF；普通 HDR 预览同样禁止物理 Surface 绑定。

### 验证
- Debug APK 与测试 APK 已生成，6 项 JVM 测试通过；最终包设备回归 6 项通过、1 项因模拟器无 HLG 显示而跳过，360dp 小屏按钮及 LSC 持久化已验证。
- 全量 Lint 仍有未改动模块的 8 项错误；具体分类与验证记录见 [同步说明](docs/eis-hdr-lsc-sync.md)。
- 真机 HDR 显示、LSC 画质与长焦稳定收益待设备验证。

## [eis-yolo-filter-sync-2026-09-07] - 2026-09-07

### 变更
- 从 `E:\StudioProjects\Auto_measure` 同步最新自研 EIS 稳定器逻辑，加入输出加速度限幅、软边界限幅、重新瞄准释放、瞄准输出加速度和残留释放转速阈值等可调参数。
- `更多 -> 自研EIS参数` 新增对应设置项，并将自研 EIS 参数配置版本升级到 `19`，旧配置会自动写入本轮默认值。
- YOLO 旗杆识别新增后处理配置：默认只保留最高置信度目标，单框阈值默认 `0.25`；可在 `更多 -> YOLO单框` 切回原始多框阈值 `0.10`。
- 实时检测状态文本新增当前 YOLO 后处理模式和阈值，便于现场确认识别配置。

### 保持
- 普通预览仍保持父逻辑相机 Session，不使用 `OutputConfiguration.setPhysicalCameraId()` 绑定预览 Surface；双摄校准 `strictPhysical=true` 物理输出路径不变。
- 系统 Camera2 EIS 默认仍为 OFF，只能通过 `防抖` 弹窗显式切换 ON/PREVIEW；本轮同步的是应用层自研 EIS。

### 验证
- 已通过 `git diff --check`。
- 已通过 `.\gradlew.bat :app:compileDebugKotlin --no-daemon`。
- 已通过 `.\gradlew.bat :app:assembleDebug --no-daemon`。

## [0.8.31] - 2026-09-04

### 修复
- Android 构建版本同步为 `versionName=0.8.31`、`versionCode=831`，用于上传包含最新自研 EIS 长焦稳定逻辑的新包。
- 蒲公英已上传并确认显示 `Auto_Measure_luoji 0.8.31 (831)`，下载页为 `https://www.pgyer.com/automeasureluoji`。

## [gyro-preview-stabilization-sync-2026-09-04] - 2026-09-04

### 变更
- 同步 `E:\StudioProjects\Auto_measure` 最新自研 EIS 模型，加入长焦大残留释放和 anti-windup 限幅回灌，降低高倍率静止瞄准后补偿量卡在边界的概率。
- 自研 EIS 有效倍率模型从 `光学倍率 * Camera2 数码倍率` 扩展为 `光学倍率 * Camera2 数码倍率 * 显示层倍率`，纯 UI 放大、校准居中和防抖裁切会纳入补偿强度计算。
- 预览显示矩阵改为基础显示缩放叠加自研 EIS 裁切缩放，避免 `max(baseScale, stabilizationScale)` 在校准居中或 UI 放大时吞掉防抖预留裁切。
- HUD、诊断文本和 `gyro-preview-model` 日志新增 `display/render/release/residual/still/windup`，preset 日志输出完整调参项，方便真机复盘。
- 自研 EIS 参数配置版本升级到 `6`，沿用参考项目回退后的默认长焦参数。

### 保持
- 普通预览仍保持父逻辑相机 Session，不使用 `OutputConfiguration.setPhysicalCameraId()` 绑定预览 Surface；双摄校准 `strictPhysical=true` 物理输出路径不变。
- 系统 Camera2 EIS 默认仍为 OFF，只能通过 `防抖` 弹窗显式切换 ON/PREVIEW。

### 验证
- 已通过 `.\gradlew.bat :app:assembleDebug --no-daemon`。

## [camera-gyro-preview-stabilization-2026-09-03] - 2026-09-03

### Added
- 按 `E:\StudioProjects\Auto_measure` 集成应用层自研 EIS：新增 `GyroPreviewStabilizer`、`GlStabilizedPreviewView` 和高采样率陀螺仪权限。
- `更多` 菜单新增 `自研EIS`、`自研EIS参数`、`防抖标定` 入口；参数支持方向反转、X/Y 互换、帧同步、倍率补偿、瞄准稳定、Roll 补偿、诊断日志和 GL 逐帧预览实验。
- Camera2 capture result 新增 `CameraFrameTiming` 回调，将 `SENSOR_TIMESTAMP`、曝光时间、rolling shutter skew 提供给自研 EIS 帧同步路径。

### Changed
- 默认预览仍保持逻辑相机 Session；普通预览不使用 `OutputConfiguration.setPhysicalCameraId()`，双摄校准 `strictPhysical=true` 路径不变。
- 自研 EIS 只叠加显示层矩阵，不静默开启系统 Camera2 EIS；系统 EIS 仍只由 `防抖` 弹窗显式切换。
- GL 逐帧预览实验开启后自动停用 YOLO、绿光识别和截图校准入口，避免预览源与识别源不一致。

### Verified
- 已通过 `.\gradlew.bat :app:assembleDebug --no-daemon`。

## [0.8.30] - 2026-08-28

### Fixed
- Android 构建版本同步为 `versionName=0.8.30`、`versionCode=830`，用于上传包含校准后视觉倍率显示修正的新包。
- 蒲公英已上传并确认显示 `Auto_Measure_luoji 0.8.30 (830)`，下载页为 `https://www.pgyer.com/automeasureluoji`。

## [camera-visual-zoom-display-2026-08-28] - 2026-08-28

### Changed
- 校准锁定坐标后，状态栏锁定倍率和底部倍率标题改为显示视觉总倍率，包含用于坐标居中的 UI scale。
- `ZoomSlider` 新增 `displayRatio` 参数，滑块控制值仍使用真实目标倍率，避免把视觉显示倍率反写到 Camera2 或校准计算。

## [0.8.29] - 2026-08-28

### Fixed
- Android 构建版本同步为 `versionName=0.8.29`、`versionCode=829`，用于上传包含纯 UI 放大逻辑相机底倍修复的新包。
- 蒲公英已上传并确认显示 `Auto_Measure_luoji 0.8.29 (829)`，下载页为 `https://www.pgyer.com/automeasureluoji`。

### Added
- 新增项目内蒲公英上传脚本 `scripts/upload_pgyer.ps1`，默认读取环境变量 `PGYER_API_KEY`，避免继续依赖其它项目脚本。

## [camera-ui-zoom-only-button-2026-08-28] - 2026-08-28

### Fixed
- 修正 luoji 逻辑相机下纯 UI 放大把 Camera2 请求倍率固定成 `1x` 的问题；开启后改为保持当前镜头的最低有效底倍，避免点击放大时画面退回广角。
- 纯 UI 放大的 HUD 日志新增 `cameraBase`，普通校准样本记录实际 Camera2 底倍，不再误写为 `1x`。

### Added
- 按 `E:\StudioProjects\Auto_measure` 同步 `更多 -> 纯UI放大` 实验按钮，默认关闭并持久化到 `camera_screen_settings`。
- 开启后普通模式下 Camera2 保持当前逻辑相机镜头的最低有效底倍，预览通过 `TextureView.setTransform(Matrix)` 做底倍以上的显示层放大，overlay 使用同一组 scale/translation。
- 普通校准在纯 UI 模式下记录真实 Camera2 底倍；双镜头校准保持原 Camera2 zoom 和物理长焦切换逻辑。

## [0.8.28] - 2026-08-28

### Fixed
- Android 构建版本同步为 `versionName=0.8.28`、`versionCode=828`，用于上传包含两轮 YUV native 图片预处理优化的新包。

## [yuv-native-preprocess-parallel-2026-08-28] - 2026-08-28

### Changed
- YUV native 预处理新增 `refillPadding` 状态控制，连续 YUV 检测时复用不变的 Letterbox 灰边，只重写有效图像内容区。
- `yuv_preprocess.cpp` 按输出行分块并行处理，较大模型输入最多使用 4 个 native worker，降低单核逐像素转换瓶颈。
- 从预览截图源切回 YUV 源、输入 buffer 重建、尺寸或旋转变化时会强制重新填充 padding，避免复用脏输入。

## [yuv-native-preprocess-perf-2026-08-28] - 2026-08-28

### Changed
- 优化 `yuv_preprocess.cpp` 的 YOLO YUV native 预处理路径，复用 Letterbox 映射缓存，去掉每帧 `new/delete`。
- 将旋转分支移出逐像素循环，左右 padding 改为整段填充，降低 1920x1080 YUV 到 1280x1280 FloatBuffer 的 CPU 成本。
- 将 YUV 到 RGB 的逐像素浮点矩阵计算改为 BT.601 full-range 定点整数近似，减少实时 YUV 模式下 `pre=` 耗时。

## [yuv-active-lens-diagnostics-2026-08-28] - 2026-08-28

### Added
- YOLO 预览截图和 YUV 数据流检测日志新增 active 物理镜头、选中镜头与当前倍率，便于排查逻辑多摄下预览流和 YUV 分析流是否表现为同一焦距画面。

## [0.8.27] - 2026-08-28

### Fixed
- Android 构建版本同步为 `versionName=0.8.27`、`versionCode=827`，用于上传包含 YUV JNI 绑定闪退修复和普通校准自动采集延迟修复的新包。

## [yuv-jni-package-binding-2026-08-28] - 2026-08-28

### Fixed
- 修正 luoji 包名下 `YuvPreprocessNative.process()` 的 C++ JNI 导出名，避免切换 YOLO `YUV数据流` 后因找不到 native 方法直接闪退。
- `detectFromYuv()` 增加 native 预处理失败回退到 Kotlin YUV 预处理的保护，后续 JNI 绑定异常会显示检测异常而不是直接崩溃。
- 同步修正 `SherpaOnnxHotwordNative` 的 C++ JNI 导出名，避免第三方语音 native 入口在 luoji 包名下出现同类绑定崩溃。

## [calibration-auto-capture-delay-2026-08-28] - 2026-08-28

### Fixed
- 普通校准在 BLE 自动测距成功后，不再立即采集图片和执行绿光识别；先延迟 500ms 等待画面稳定，再复用原有采帧识别链路。
- 500ms 等待和识别期间禁用“采集”按钮，避免同一次测距触发重复采集。

## [0.8.26] - 2026-08-26

### Fixed
- Android 构建版本同步为 `versionName=0.8.26`、`versionCode=826`，避免蒲公英与上一版 `0.8.25 (825)` 显示重复。

## [camera-yolo-frame-source-toggle-2026-08-26] - 2026-08-26

### Added
- 按 `E:\StudioProjects\Auto_measure` 同步 YOLO 数据源切换，`更多 -> YOLO源` 可在 `预览截图` 和 `YUV数据流` 间切换并持久化选择。
- 普通逻辑相机会话的 `ImageReader` 不再立即丢弃帧，而是只缓存最新一帧供 YOLO/YUV 模式消费；实时识别的 YUV 路径调用 `YoloDetector.detectFromYuv()`。
- 普通校准和双镜头校准复用统一采帧函数，按当前数据源保存 `cal_yuv/cal_preview` 与 `dual_*_yuv/dual_*_preview` 图片。
- `ImageUtils` 补齐 YUV420 转 bitmap、旋转和 `1080x1920` 校准归一化工具。

### Risk
- `strictPhysical=true` 的双摄物理绑定会话当前不创建分析 `ImageReader`，YUV 数据源在该路径下会提示不可用；普通预览仍保持父逻辑相机 Session，不恢复普通预览物理绑定。

## [camera-eis-experimental-toggle-2026-08-25] - 2026-08-25

### Added
- 按 `E:\StudioProjects\Auto_measure` 同步 Camera2 EIS 实验模式，支持 OFF/ON/PREVIEW 三档 `CONTROL_VIDEO_STABILIZATION_MODE`。
- `更多` 菜单新增 `防抖 EIS:...` 入口，弹窗内可切换 OIS ON/OFF 和 EIS 模式，并显示设备支持模式与回传状态。
- `Camera2Controller` 新增 EIS 支持模式扫描、模式切换、capture result 日志，保留 active physical id 监听和普通逻辑相机 Session 逻辑。

### Risk
- EIS ON/PREVIEW 可能触发电子裁剪，真机校准时需要记录 EIS 模式并验证坐标漂移。

## [camera-logical-preview-restore-2026-08-24] - 2026-08-24

### Changed
- 普通预览移除 `bindPhysicalPreview` 与 `OutputConfiguration.setPhysicalCameraId()` 物理输出绑定，恢复为父逻辑相机 Session。
- 普通预览继续保留最大长焦候选、光学基准倍率 + `0.1x` 下限和 `24x` 上限；当前画面镜头显示改由逻辑相机 capture result 的 active physical id 回传决定。
- 双摄校准 `strictPhysical=true` 路径保持原物理 Session 绑定和失败即报错策略，不改变双摄校准逻辑。

## [camera-physical-preview-output-2026-08-07] - 2026-08-07

> 已被 `camera-logical-preview-restore-2026-08-24` 覆盖；当前普通预览保持父逻辑相机 Session，不再使用普通物理输出绑定。

### Changed
- 普通预览仍打开父逻辑相机 ID，但预览 `Surface` 通过 `OutputConfiguration.setPhysicalCameraId()` 绑定到当前选中物理候选；默认选中候选为最大焦距镜头。
- 物理预览输出绑定模式关闭非必要 YUV `ImageReader`，降低物理流 + 逻辑流组合导致 Session 配置失败的概率。
- 普通物理预览输出配置失败时自动回退普通逻辑 Session；双摄校准 `strictPhysical=true` 路径仍保持失败即报错，不做静默回退。
- 当前画面镜头状态行在物理输出绑定模式下直接显示绑定的 physical id 对应 35mm 等效焦距。

## [camera-active-lens-eq35-display-2026-08-06] - 2026-08-06

### Changed
- 当前画面镜头状态行改为只用 `35mm等效 xxmm` 显示焦距，并保留 active physical id。

## [camera-active-physical-lens-2026-08-06] - 2026-08-06

### Added
- `Camera2Controller` repeating request 改为带 `CaptureCallback`，监听 `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`。
- 主界面 `StatusHud` 新增当前画面镜头行，用 35mm 等效焦距显示 active physical id 对应的物理镜头。
- strict physical 物理镜头模式下直接回报绑定的物理镜头 ID；逻辑相机未回传时显示“未上报 activePhysicalId”。

## [camera-normal-zoom-24-2026-08-05] - 2026-08-05

### Changed
- 普通预览倍率最大值从“不限”改回固定 `24x`，普通 `ZoomSlider` 右侧显示 `24.0x`。
- 普通模式最低倍率改为最大焦距对应光学基准倍率 + `0.1x`，镜头切换、入场倍率、滑块拖动和普通校准保持同一限制。
- `ZoomController` 历史入口同步恢复 `24x` 上限，避免后续复用时绕过普通倍率范围。
- 双摄像头校准仍保留 `1x..长焦倍率` 范围、`strictPhysical=true` 物理镜头绑定和原有广角/长焦切换逻辑。

## [camera-unbounded-normal-zoom-2026-08-05] - 2026-08-05

> 已被 `camera-normal-zoom-24-2026-08-05` 覆盖；当前普通预览最大倍率固定为 `24x`。

### Changed
- 普通预览倍率最大值取消应用层固定上限；`ZoomSlider` 在普通模式显示 `不限`，并随当前倍率动态扩展可拖动范围。
- `Camera2Controller` 的 `SCALER_CROP_REGION` fallback 不再按 `maxDigitalZoom` 截断请求倍率，普通最低倍率仍由最大焦距对应的光学基准倍率限制。
- 双摄像头校准仍保留 `1x..长焦倍率` 范围、`strictPhysical=true` 物理镜头绑定和原有广角/长焦切换逻辑。

## [camera-logic-zoom-2026-08-05] - 2026-08-05

### Changed
- 普通相机入口不再因选中物理长焦候选而创建物理镜头会话；默认使用候选所属父逻辑相机打开预览。
- 普通模式进入后会把 Camera2 zoom 直接设为最大焦距候选相对同一逻辑相机广角的光学基准倍率。
- 普通模式倍率滑块最小值改为该光学基准倍率，不能再滑回 `1x`。
- 双摄像头校准路径保持 `strictPhysical=true` 的物理镜头绑定和原有广角/长焦切换逻辑不变。

## [copy-rename-2026-08-05] - 2026-08-05

### Changed
- 将副本项目名、应用显示名、`namespace`、`applicationId` 和 Kotlin 包路径改为 `Auto_Measure_luoji / com.example.auto_measure_luoji`。
- `laserCompare` 构建变体包名同步为 `com.example.auto_measure_luoji.lasercompare`，应用名同步为 `Auto_Measure_luoji 绿光对比`。

## [copy-2026-08-05] - 2026-08-05

### Added
- 将 `E:\StudioProjects\Auto_measure` 当前 Git `HEAD` 的跟踪源码、资源、Gradle 配置和项目文档复制到 `E:\StudioProjects\Auto_Measure_luoji`。

### Changed
- 目标目录初始化 Git 仓库，保留复制前模板快照，并在 `feature/copy-auto-measure-code` 分支保存本次复制。
- 更新 `docs/code-library/` 中的项目根目录与 canonical path，指向当前副本。

## [0.8.25] - 2026-08-03

### Changed
- 调整相机页顶部工具栏：固定区保留 `识别`、`校准` 和 `更多`，`识别` 放在首位，避免被右侧按钮挤到不可见。
- 将 `广角/广角中`、`OIS`、`语音`、`Log` 收纳到 `更多` 菜单，保留原功能和高亮状态；`laserCompare` 包仍固定显示 `绿光对比`。
- Android 构建版本同步为 `versionName=0.8.25`、`versionCode=825`。

## [0.8.24] - 2026-08-03

### Fixed
- 修复系统 `SpeechRecognizer` 不可用且第三方语音包未就绪时，只显示“语音识别不可用”但不弹出第三方语音包下载入口的问题。
- 修复“第三方语音包”弹框的确认按钮仍调用系统中文语言包下载接口的问题；现在 `fallbackActive=true` 时会调用第三方包断点续传下载。
- 第三方语音包下载失败后，如果系统识别服务也不可用，不再继续调度系统识别重启。

### Changed
- Android 构建版本同步为 `versionName=0.8.24`、`versionCode=824`。

## [0.8.23] - 2026-08-03

### Fixed
- 修复第三方语音识别启动后闪退：`SherpaOnnxHotwordRecognizer` 回调绑定时误调用自身回调，导致 `ChineseVoiceHotwordRecognizer.kt:319` 无限递归。

### Changed
- Android 构建版本同步为 `versionName=0.8.23`、`versionCode=823`。

## [0.8.22] - 2026-08-03

### Added
- 接入 Sherpa-ONNX 第三方语音识别链路：第三方模型就绪后使用 `AudioRecord 16kHz PCM16 -> Silero VAD -> SenseVoice OfflineRecognizer -> 热词匹配`，不再继续调用系统 `SpeechRecognizer`。
- 随包加入 Sherpa-ONNX Android native 库和 `sherpa_hotword_jni` bridge；模型权重仍按需下载到应用私有目录，不打进 APK。
- 第三方 SenseVoice `.tar.bz2` 下载完成后自动解压，实际就绪条件改为 `silero_vad.onnx`、`model.int8.onnx`、`tokens.txt` 都存在。
- 兼容旧版本已下载压缩包但未解压的升级场景：点击语音时会先解压现有包，再启动第三方识别。

### Fixed
- 修复第三方语音包下载完成后仍提示并继续使用系统默认识别的问题。
- 修复顶部按钮拥挤时 `广角/广角中` 仍在横向滚动区内、可能被挤到不可见的问题；现在 `广角/更多/OIS/语音/Log` 固定可见。

### Changed
- 第三方语音日志新增 `SherpaHotword`，用于查看 SenseVoice 识别结果和本地引擎错误。
- Android 构建版本同步为 `versionName=0.8.22`、`versionCode=822`。

## [0.8.21] - 2026-08-03

### Fixed
- `checkRecognitionSupport` 返回 `ERROR_CANNOT_CHECK_SUPPORT=14` 时，直接降级到系统默认中文识别，不再先启动离线识别触发 SODA 语言包 `error 12`。
- 系统默认识别如果仍返回语言类错误，不再停止语音按钮，而是保留第三方语音包入口并按 2 秒退避继续重试。

### Changed
- 默认/在线识别 Intent 不再传 `EXTRA_PREFER_OFFLINE=false`、`EXTRA_LANGUAGE_PREFERENCE` 和 `EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE`，只在明确离线模式时传离线偏好。
- Android 构建版本同步为 `versionName=0.8.21`、`versionCode=821`。

## [0.8.20] - 2026-08-03

### Fixed
- 修复系统语音服务在取消/降级后尚未释放时，App 过快重启导致连续 `startListening called while listening is in progress` 的问题。
- 系统离线中文包确认返回 `ERROR_LANGUAGE_NOT_SUPPORTED=12` 后会持久化记录，后续启动直接使用系统默认中文识别，避免反复触发离线 SODA 语言包错误。

### Changed
- 语音识别循环增加 pending start 互斥、取消后 1.2 秒释放等待、`ERROR_RECOGNIZER_BUSY` 1.5 秒退避。
- `ChineseVoiceHotword` 日志增加 `startListening`、`onError` 和 fallback 原因，方便真机过滤语音错误。
- Android 构建版本同步为 `versionName=0.8.20`、`versionCode=820`。

## [0.8.19] - 2026-08-03

### Added
- 新增第三方语音包下载器，下载 SenseVoice INT8 与 Silero VAD 模型文件，使用 `.part` 临时文件和 HTTP `Range` 请求支持断点续传。
- `AndroidManifest` 新增 `INTERNET` 权限，用于 App 自己下载第三方语音包。

### Changed
- 当系统中文离线语言包返回 `ERROR_LANGUAGE_NOT_SUPPORTED=12` 时，不再只显示“不支持下载”；会切换到系统默认中文识别，并允许继续下载第三方语音包。
- 语音包弹框在第三方下载场景下显示 `第三方语音包` 与 `下载第三方包`，避免和系统语言包下载混淆。
- Android 构建版本同步为 `versionName=0.8.19`、`versionCode=819`。

## [0.8.18] - 2026-08-03

### Fixed
- 主界面 `更多` 按钮从左侧可横向滚动按钮组移到固定操作区，避免小屏或按钮文本较长时被滚动区域挤到不可见位置。

### Changed
- Android 构建版本同步为 `versionName=0.8.18`、`versionCode=818`。

## [0.8.17] - 2026-08-03

### Added
- 主界面顶部新增 `广角/广角中` 按钮，非校准状态下可切换到广角镜头；已在广角镜头时再次点击会切回默认镜头。
- 新增 `更多` 折叠菜单，收纳 `图源`、`绿光识别`、`区域`、`判定点`、`统计` 等低频工具入口。

### Changed
- 主界面顶部工具区从两行按钮改为一行主操作加折叠菜单，减少按钮挤占和顶部遮挡。
- `laserCompare` 包仍保留固定可见的 `绿光对比` 入口，避免新旧绿光算法测试入口被菜单隐藏。
- Android 构建版本同步为 `versionName=0.8.17`、`versionCode=817`。

## [0.8.16] - 2026-07-31

### Changed
- 普通校准的长焦/非广角镜头倍率逻辑临时回退到旧行为：只保持当前倍率采集，不再自动切到相对广角的原生光学倍率。
- 普通模式倍率滑条最小值恢复为 `1x`，不再按当前物理镜头原生光学倍率抬高最小值。
- 普通模式 Camera2 zoom 下发恢复为直接使用当前倍率；双镜头长焦物理镜头仍保持 Camera2 `1x`。
- Android 构建版本同步为 `versionName=0.8.16`、`versionCode=816`。

## [0.8.15] - 2026-07-31

### Changed
- 普通校准和双镜头校准面板打开期间，Camera2 请求会把 AE 曝光补偿切到 `-2.0 EV`；面板关闭后恢复 `0 EV`。
- `Camera2Controller` 新增 `setExposureCompensationEv()`，按设备 `CONTROL_AE_COMPENSATION_STEP` 换算整数档位，并裁剪到 `CONTROL_AE_COMPENSATION_RANGE`。
- 新增 `exposure: requestedEV/actualEV/steps/step/range` 日志，方便真机确认设备是否完整支持 `-2.0 EV`。
- Android 构建版本同步为 `versionName=0.8.15`、`versionCode=815`。

## [0.8.14] - 2026-07-31

### Fixed
- 普通校准距离输入为空且 BLE 已连接时，点击“采集”会发送测距命令，收到 `line > 0` 的距离后自动回填并进入采集流程。
- 普通校准面板会在 BLE 连接状态变为已连接时发送打开绿光命令，覆盖先打开面板再连接设备的边界场景。
- 普通校准自动测距增加 4 秒超时保护，超时或面板关闭时会发送停止测距命令，避免设备持续测距。
- 普通校准绿光识别失败时不再提前记录 `lastCollectedDistance`，失败后显示“绿光识别失败，请调整瞄准后重试”，并允许使用同一距离继续重试。
- BLE `lastDistance` 改为每次距离包赋值都通知 Compose，避免连续两次相同距离数据无法触发自动回填。
- Android 构建版本同步为 `versionName=0.8.14`、`versionCode=814`。

## [0.8.13] - 2026-07-31

### Fixed
- 普通校准在当前镜头为长焦/非广角镜头时，改为使用该镜头相对广角的原生光学倍率进行采集和样本记录。
- Camera2 请求倍率会从等效倍率换算为当前镜头上的实际请求倍率，避免物理长焦原生倍率与 `CONTROL_ZOOM_RATIO` 再次叠加。
- 普通模式滑条在非广角镜头上会以原生光学倍率作为最小值，避免 UI 显示 `1x` 但实际已经是长焦原生画面。
- `CameraScreen` 新增 `zoom-map` 日志，输出 `equiv/opticalBase/camera` 三个倍率，方便排查 S22 等机型上的倍率语义。
- Android 构建版本同步为 `versionName=0.8.13`、`versionCode=813`。

## [0.8.12] - 2026-07-30

### Changed
- `LaserDetector` legacy entry points now preserve the old bright-core refinement path; they no longer apply the large green connected-component center fix.
- New/current laser detection still uses Pillow-style resize and the large green component geometry center guard for bloomed laser spots.
- Debug reports now include `centerMode`, making `laserCompare` output distinguish `large-component-mean` from `brightcore-refine`.
- Android build version synchronized to `versionName=0.8.12`, `versionCode=812`.

## [0.8.11] - 2026-07-30

### Fixed
- `LaserDetector` now skips bright-core refinement for large green connected components and uses the component center instead, avoiding the white/saturated core pulling large laser blobs upward from the visible green center.
- `Camera2Controller` 的倍率后端日志改为同时输出 `Camera2Ctrl:I` 和 `Camera2Ctrl:D`，避免只看 D 级日志时误以为没有 zoom 日志。
- `CameraScreen` 接收并转发最近一次 zoom 后端到 `CameraScreen:I`，主界面状态区也显示 `zoom: backend=...`。
- 应用内 Log 面板新增 `CameraScreen:I`、`Camera2Ctrl:I`、`Camera2Ctrl:W` 过滤项，便于直接查看 `CONTROL_ZOOM_RATIO` 或 fallback。
- Android 构建版本同步为 `versionName=0.8.11`、`versionCode=811`。

## [0.8.10] - 2026-07-30

### Fixed
- `laserCompare` 对比包新增固定可见的 `绿光对比` 按钮，放在顶部第二行最左侧，避免原 `绿光` 入口在第一行横向滚动栏中不明显或被挤出。
- 主 App 继续保留第一行 `绿光` 入口；对比包隐藏该普通入口，避免两个按钮语义混淆。
- Android 构建版本同步为 `versionName=0.8.10`、`versionCode=810`。

## [0.8.9] - 2026-07-30

### Changed
- `LaserDetector` internal `MAX_DIM` resize now uses Pillow `Image.BILINEAR` style separable weighted resampling so Android laser coordinates align with Python `1218.py` offline calibration output.
- Kept laser thresholds, bright-core refine thresholds, and Camera2 stabilization behavior unchanged; this change only adjusts detector pixel preprocessing.
- Added legacy laser detector entry points that preserve the previous Android resize path, allowing new-vs-old coordinate comparison on the same frame.
- Added `laserCompare` build type with package `com.example.auto_measure_luoji.lasercompare` and app name `Auto_measure 绿光对比` so the comparison build can be installed beside the main app.
- Updated the top `绿光` panel to compare new algorithm vs old resize algorithm, saving `laser_new_marked_*` and `laser_old_marked_*` images plus coordinate delta text.
- Android build version synchronized to `versionName=0.8.9`, `versionCode=809`.

## [0.8.8] - 2026-07-29

### Changed
- 普通校准开始时，如果当前镜头分类为 `WIDE`，自动将 Camera2 倍率切到 5x 后再采集绿光样本。
- 广角设备可用倍率不足 5x 时，使用该镜头最大可用倍率并在状态栏提示，避免标定参数记录与实际画面倍率不一致。
- 普通校准开始会清除旧锚点居中状态，防止历史 UI 变换影响采样画面。
- Android 构建版本同步为 `versionName=0.8.8`、`versionCode=808`。

## [0.8.7] - 2026-07-29

### Changed
- 普通模式倍率滑块改为提交到 Camera2 `CONTROL_ZOOM_RATIO` 优先链路，不再只依赖 UI wrapper scale 放大。
- 无校准点时取消 wrapper 倍率缩放，避免 Camera2 数码 zoom 与 UI scale 叠加造成双重放大。
- 校准点居中仍保留必要的 UI 平移和边界覆盖 scale；请求的倍率放大由 Camera2 zoom 负责。
- 历史 `ZoomController.applyZoom()` 改为委托 `Camera2Controller.setZoomRatio()`，避免后续复用时重新走旧的中心 crop 放大。
- Android 构建版本同步为 `versionName=0.8.7`、`versionCode=807`。

## [0.8.6] - 2026-07-29

### Changed
- 顶部按钮区改为两行布局：第一行保留 `校准/识别/图源/绿光` 与固定 `OIS/语音/Log`，第二行放置 `区域/判定点/统计`。
- 减少新增 `图1080P/图4K`、`绿光` 按钮后对 `区域/判定点/统计` 的挤占，避免常用工具默认不可见。
- Android 构建版本同步为 `versionName=0.8.6`、`versionCode=806`，便于蒲公英区分当前包。

## [0.8.5] - 2026-07-29

### Added
- 顶部工具条新增 `图1080P/图4K` 按钮，用于切换 YOLO 模型识别前的 `TextureView` 截图源尺寸。
- YOLO 检测默认使用 `1080x1920` 图源；切到 4K 后使用 `2160x3840` 图源，再由 `YoloDetector` 按模型 tensor 转换到模型输入尺寸（当前模型预期为 `1280x1280` 时即转为 `1280x1280`）。
- 检测日志和状态 HUD 增加 YOLO 图源尺寸输出，便于对比 `Camera2 previewSize`、截图源尺寸和模型输入尺寸。

### Notes
- 4K 图源会显著增加 `TextureView.getBitmap()`、预处理和内存压力，默认仍保持 1080P。

## [0.8.4] - 2026-07-24

### Added
- 顶部工具条新增 `绿光` 调试按钮，点击后展开绿光识别对比面板。
- 对比面板新增 `识别开关`，开启后单次采集当前预览帧，并分别执行 Uint8List/ByteArray bytes 识别和 Bitmap 识别。
- 对比识别会输出两组图片：`laser_uint8_bytes_raw/marked_*.png` 与 `laser_bitmap_raw/marked_*.png`，标记图分别带 `bytes` 和 `bitmap` 标签。

### Changed
- 恢复明确的 bytes 识别入口命名：校准链路调用 `LaserDetector.detectLaserCenterFromBytes(ByteArray)`，Bitmap 识别继续调用 `LaserDetector.detectLaserCenter(Bitmap)`。

## [0.8.3] - 2026-07-24

### Changed
- 绿光识别输入链路改为 bytes：普通校准和双镜头校准都会先把预览帧归一化为 `1080x1920`，再编码为 PNG bytes 传入 `LaserDetector`。
- `CalibrationManager.collectSample()` 的采集输入从 `Bitmap` 调整为归一化图片 bytes，避免校准管理器内部重复处理图片尺寸。
- `LaserDetector` 保留旧 `Bitmap` 入口兼容现有代码，但新增 bytes 主入口作为校准流程的实际调用方式。

## [0.8.2] - 2026-07-22

### Added
- 顶部按钮区新增 `OIS` 开关，开启成功后按钮以青色填充显示，关闭后恢复普通状态。
- 相机扫描结果记录当前镜头是否支持 `LENS_OPTICAL_STABILIZATION_MODE_ON`，避免对不支持 OIS 的镜头发送无效请求。
- OIS 开启失败或当前镜头不支持时，界面显示 3 秒错误提示。

### Changed
- Camera2 请求继续强制关闭 EIS；OIS 默认关闭，只在用户显式开启后写入 `LENS_OPTICAL_STABILIZATION_MODE_ON`。
- `OIS` 按钮从横向滚动工具条末尾移动到固定顶部区域，放在“语音”和“Log”左侧，避免默认视口看不到。

## [0.8.1] - 2026-07-07

### Changed
- 中文语音识别恢复为上一版可用的系统 `SpeechRecognizer` intent，避免 250ms 静音端点和 segmented session 导致未说话就结束识别。
- 增加 30 秒识别 watchdog：系统语音服务长时间不返回结果时会取消当前会话并进入下一轮监听。
- 补充项目级 `docs/code-library` 代码资产库，登记当前中文语音热词循环实现和 Sherpa/Silero VAD 边界。

### Notes
- 这版仍然使用系统 `SpeechRecognizer`，不是完整 Silero VAD + SenseVoice。Silero VAD 的 250ms 端点参数不能直接套进系统语音服务。

## [0.8.0] - 2026-07-07

### Added
- 新增中文语音热词识别按钮：点击“语音”后申请录音权限并持续监听。
- 语音热词当前仅识别中文“测距”“扫描”“扫描旗杆”，命中后提示“语音命中：测距/扫描”。
- 接入系统 `SpeechRecognizer` 中文语言包检查和下载入口，支持下载提示、弹框进度和下载完成后继续识别。
- 新增 `HOTWORDS.md` 与 `DEV_GUIDE.md` 作为语音热词和 Speech2Text 参考文档。

### Fixed
- 将“语音”按钮从横向滚动工具条移到日志按钮左侧的固定位置，避免真机上看不到入口。
- 中文语音包下载返回错误码 12（`ERROR_LANGUAGE_NOT_SUPPORTED`）时，自动降级为系统默认中文识别继续监听，并显示可读错误说明。

## [0.7.5] - 2026-07-02

### Changed
- 双镜头广角数码变焦试验切换为 API 30+ 优先使用 `CONTROL_ZOOM_RATIO`；设备不支持、倍率超出范围或请求失败时自动回退 `SCALER_CROP_REGION`。
- `Camera2Controller` 在新 session 创建时继续复用缓存倍率，保证广角 5x 切回时不会丢失 zoom。

## [0.7.4] - 2026-07-02

### Fixed
- 修正双镜头校准 d1/d2 输入逻辑：距离输入为空且 BLE 已连接时，点击“输入 d1/d2”会自动触发设备测距，收到有效距离后填写并提交到双镜头采集流程。
- 双镜头自动测距提交距离时不再要求 `flag=1`，只要求距离值大于 0。

## [0.7.3] - 2026-07-01

### Added
- 双镜头校准选择长焦后新增“已有双镜头坐标/重新校准”分支；已有坐标时可输入广角 100m 坐标和长焦 100m 坐标，直接锁定双摄并应用中心点。

### Changed
- 双镜头扫描结果中的长焦按钮改为先选择长焦，再进入校准数据选择，避免把“锁定镜头”和“开始采集”绑定在一个按钮上。

## [0.7.2] - 2026-07-01

### Changed
- 双镜头目标距离应用后，参考 `laser_native_kotlin_app` 的置中逻辑：广角按 1x 锚点随倍率重新计算当前中心，长焦使用长焦预测坐标，再将校准点移动到屏幕中心。
- 普通校准距离输入改为显式必填；双镜头 d1/d2 输入在 BLE 已连接时仍支持空输入自动测距。

### Fixed
- 修复进入双镜头校准后没有主动发送打开绿光命令的问题。
- 修复双镜头应用目标距离后 UI 放大位置过固定、拖动倍率时中心点没有随倍率重算的问题。

## [0.7.1] - 2026-07-01

### Changed
- 优化主相机页 UI：顶部操作区改为横向滚动工具条，避免小屏按钮挤压和日志按钮被遮挡。
- 将模型状态、检测性能、统计结果和锁定坐标合并到左上状态 HUD，避免多个顶部提示互相覆盖。
- 校准面板和双镜头校准面板改为可滚动底部面板，输入框和主按钮分行全宽显示。
- 校准面板打开时隐藏底部倍率滑块和蓝牙设备控制栏，避免底部控件叠压。
- 双镜头目标距离应用成功后自动收起面板，恢复倍率滑块；顶部“双摄”按钮可重新打开面板。
- 双镜头扫描弹窗、判定点弹窗限制高度并支持滚动，判定模式按钮改为等宽分段样式。

### Fixed
- 修复普通校准面板“关闭”文本不可点击的问题。

## [0.7.0] - 2026-07-01

### Added
- 新增双镜头校准流程：点击“校准”后可选择普通校准或双镜头校准。
- 新增双镜头扫描与长焦选择：以广角镜头为基准，列出所有大于广角的长焦候选并锁定组合。
- 新增 `DualCalibrationManager`：分别采集广角 5x 与长焦原生 1x 的两轮样本，计算两套标定参数和两个 100m 坐标。
- 新增 `DualCalibrationPanel`：支持输入 d1/d2、依次采集广角/长焦、应用目标距离 D。
- `Camera2Controller` 新增真实 `SCALER_CROP_REGION` zoom 缓存，切换物理镜头后能保留下一次需要的广角 5x。

### Changed
- 双镜头模式下，低于长焦阈值使用广角物理镜头和 crop zoom；达到长焦阈值后切换到选中的长焦物理镜头。
- 双镜头模式下严格要求物理镜头绑定，不再在物理 session 失败时静默回退为逻辑镜头。
- 镜头扫描增加疑似后摄长焦兜底，兼容部分厂商把长焦标记为非后置的情况。

### Fixed
- 避免双镜头采集时仅 UI 放大、取帧仍为未变焦画面的问题。
- 修正 README 中 OIS/EIS 描述，使其与当前 Camera2 请求保持一致。

## [0.6.3] - 2026-06-18

### Added
- 识别区域调节按钮（"区域"）：可自定义识别区域的宽度和高度（dp），UI 框和实际判定同步生效

## [0.6.2] - 2026-06-17

### Added
- 30 秒检测成功率统计按钮（与 yolov14 一致，统计命中次数/总次数）
- ZoomController 工具类：SCALER_CROP_REGION 缩放控制（含锚点居中、9:16 裁切）
- Camera2Controller.setCropRegion() 方法

### Changed
- 模型升级：bestV29 → bestV34
- 检测帧间隔从 300ms 降低到 10ms，提高检测吞吐量（目标 300+ 次/30s）
- 统计 UI 改为 Column 布局，同时显示性能信息和统计结果

### Fixed
- AT 命令防抖：改为轮询模式 + 2s 冷却，防止目标在区域内时连续发送 AT 命令

## [0.6.1] - 2026-06-12

### Added
- 校准快捷入口：已有校准数据时，点击"校准"可选择直接输入100m坐标，跳过采集流程

## [0.6.0] - 2026-06-10 (feature/detection)

### Added
- YOLO 实时目标检测功能（TFLite 推理引擎）
- GPU 三级降级策略：LOCAL_GPU (CompatibilityList) → GMS_GPU (Play services) → CPU (XNNPACK)
- 检测开关按钮（顶部栏"识别" / "识别中"）
- 实时帧处理循环（每 300ms 从 TextureView 捕获帧 → 推理 → 坐标映射 → 绘制检测框）
- Letterbox 预处理（保持宽高比缩放，114 填充值，归一化到 [0,1]）
- 输出解析器（自动识别 [1,N,C] / [1,C,N] 布局，支持 FLOAT32/UINT8/INT8）
- 类别感知 NMS（非极大值抑制）
- Compose Canvas 检测框叠加层（DetectionOverlay）
- 模型文件：bestV29_float32.tflite + classes.txt（单类别 "flag"）

### 新增文件
- `feature/detection/data/AssetUtils.kt` — 模型文件加载（MappedByteBuffer）
- `feature/detection/model/DetectionResult.kt` — Detection / Timings / DetectResult 数据类
- `feature/detection/domain/OutputReader.kt` — TFLite 输出张量解析
- `feature/detection/domain/Nms.kt` — 非极大值抑制
- `feature/detection/domain/YoloDetector.kt` — 核心检测器（GPU/CPU 自动降级）
- `feature/detection/ui/DetectionOverlay.kt` — Compose 检测框绘制

### Changed
- `app/build.gradle.kts` 添加 TFLite + GMS GPU 依赖，`noCompress += "tflite"`
- `CameraScreen.kt` 集成检测功能（开关按钮 + 帧处理循环 + 检测框叠加）

## [0.5.0] - 2026-06-10 (feature/bluetooth)

### Added
- BLE 蓝牙功能：扫描、连接、断开 SWR005 "Mate X" 激光测距仪
- 蓝牙面板 UI（可收起/展开）：扫描列表、设备信息、断开按钮
- 设备控制栏：测距按钮（按下-松开模式，最短 0.5s）、绿光开关按钮
- 距离数据解析与显示（AT+Distance 响应）
- AT 命令协议支持（测距、绿光、电池、设备信息）
- BLE 权限请求（Android 12+ BLUETOOTH_SCAN/CONNECT，旧版 ACCESS_FINE_LOCATION）

## [0.1.0] - 2026-06-09

### Added
- 项目初始化（Android Jetpack Compose 模板）
- 开发行为规范（CLAUDE.md）
- 项目说明文档（README.md）
- 变更日志（CHANGELOG.md）
- Git 仓库初始化及首次提交
- 完善 .gitignore，排除 .idea/、local.properties、构建产物等

## [0.2.0] - 2026-06-09 (feature/camera-lens-control)

### Added
- 镜头扫描模块（LensScanner）：扫描后置镜头，读取焦距/传感器/zoom 参数，计算 eq35mm 并分类
- 镜头选择模块（SelectLensUseCase）：按策略选择最佳镜头（第二大 TELE → 第一大 → WIDE → 兜底）
- 相机控制器（Camera2Controller）：打开相机、创建物理/逻辑会话、预览、禁止 EIS
- 放大控制器（ZoomController）：光学底倍计算、硬件裁切放大、软件虚拟放大 fallback
- 相机预览 UI（CameraScreen + ZoomSlider）：TextureView 预览 + 放大滑块控制
- 相机权限请求（MainActivity）
- LensCandidate 数据模型（含镜头分类枚举）
- ZoomState 状态模型

## [0.4.0] - 2026-06-10 (feature/calibration)

### Changed
- 镜头选择改为锁定最大长焦（eq35mm 最大），不再选第二大 TELE
- 放大方式从 SCALER_CROP_REGION 数码放大改为纯 UI 放大（wrapper scale）
- 锚点居中改为纯 UI 变换（translation + scale），无 sensor 边界限制
- 删除 ZoomController（不再需要 crop region 逻辑）
- Camera2Controller 移除 setCropRegion 方法

## [0.3.1] - 2026-06-10 (feature/calibration)

### Fixed
- 锚点居中：crop region 按 9:16 比例裁切（匹配输出画面），解决画面拉伸变形
- 锚点居中：添加 UI 平移补偿，当 crop 被 sensor 边界 clamp 后自动计算偏移量并用 wrapper translationX/Y 补偿，确保锚点出现在屏幕中心
- 锚点居中：移除 UI scale 叠加，只用 crop region 处理 zoom，避免 zoom 翻倍（之前 crop 2x + UI 2x = 4x）

### Changed
- ZoomController 新增 `applyZoomCenteredOnAnchor916` 方法（9:16 比例裁切）
- ZoomController 新增 `calculateAnchorScreenOffset` 方法（计算锚点屏幕偏移量）
- AnchorZoomResult 数据类封装 ZoomState + cropRect

## [0.3.0] - 2026-06-09 (feature/calibration)

### Changed
- 预览变换方案从 TextureView.setTransform(Matrix) 改为 view 属性（scaleX/scaleY/translationX/translationY/rotation），与参考项目一致，解决黑屏问题
- 锚点居中使用 minCoverScaleForFocus 算法，确保无黑边

### Added
- 校准功能（左上角"校准"按钮）
- 绿光识别算法（LaserDetector，直接复制参考项目）
- 校准状态机（CalibrationManager）：IDLE → SAMPLE_1 → SAMPLE_2 → CALIBRATED
- 坐标预测（PredictionCalculator）：a/d+b 反投影模型，预测 100m 下 1x 坐标
- 校准面板 UI（CalibrationPanel）：距离输入、采集按钮、结果展示
- 预览锚点变换：以预测坐标为中心放大预览，无黑边
- 数据模型：LaserPoint, LaserResult, CalibrationParams, Sample
- 工具类：ImageUtils（图片归一化）、GallerySaver（相册保存）
- exifinterface 依赖
