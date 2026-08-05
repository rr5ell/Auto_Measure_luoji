# Changelog

所有重要变更都会记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

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
- Added `laserCompare` build type with package `com.example.auto_measure.lasercompare` and app name `Auto_measure 绿光对比` so the comparison build can be installed beside the main app.
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
