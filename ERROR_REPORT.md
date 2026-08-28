# Error Report

## [2026-08-28] 纯 UI 放大按钮同步报告

### 发现的问题
- `E:\StudioProjects\Auto_measure` 已有 `更多 -> 纯UI放大` 实验开关，luoji 项目缺少该入口，无法对比 Camera2 zoom 与显示层 UI zoom 的差异。
- 严重程度：低。属于实验入口缺失，不影响默认 Camera2 zoom 主流程。

### 解决方案
- 在 luoji 的 `CameraScreen` 中新增 `KEY_UI_ZOOM_ONLY_ENABLED`、`uiZoomOnlyEnabled` 状态和 `更多 -> 纯UI放大` 菜单按钮。
- 开启后普通模式下 `cameraZoomForMode()` 固定向 Camera2 下发 `1x`，`TextureView.setTransform(Matrix)` 承担显示层放大，并同步 overlay 变换参数。
- 普通校准样本通过 `calibrationSampleZoom()` 记录真实相机倍率；双镜头校准逻辑不套用纯 UI zoom。

### 预防措施
- 纯 UI 放大仅作为实验模式，默认保持关闭；验证真实相机裁切、YUV 输出或坐标校准时应关闭该开关。
- 后续修改倍率逻辑时，必须区分 `currentEquivRatio` 显示倍率、Camera2 请求倍率和 UI matrix scale。

## [2026-08-28] 蒲公英 0.8.28 发布记录

### 发现的问题
- YUV native 图片预处理优化后仍停留在 `0.8.27 (827)`，继续上传会和上一版 JNI 闪退修复包混淆。
- 严重程度：中。测试时难以确认安装包是否包含 `pre=` 降耗优化。

### 解决方案
- 上传前将 Android 构建版本提升到 `0.8.28 (828)`，并重新构建主 Debug APK。
- 蒲公英已确认显示 `Auto_Measure_luoji 0.8.28 (828)`，下载页为 `https://www.pgyer.com/automeasureluoji`。

### 预防措施
- 每次上传蒲公英前先检查 `versionName/versionCode` 是否已随功能变更递增。

## [2026-08-28] YUV 图片预处理耗时过高报告

### 发现的问题
- 附件日志显示 `ImageReaderYUV 1920x1080 rot=90` 输入到 `1280x1280` 模型时，`pre=` 稳定在 `433ms` 左右，总耗时约 `500ms`。
- 日志没有出现 native 失败回退告警，说明主要瓶颈不是 Kotlin fallback，而是 native 老实现仍在逐帧分配映射表、逐像素执行旋转分支、逐像素处理 padding 分支和浮点 YUV 转 RGB。
- 严重程度：中。YUV 实时识别帧率明显低于预览截图源，容易造成检测延迟和 UI 反馈滞后。

### 解决方案
- `yuv_preprocess.cpp` 新增线程本地 Letterbox 映射缓存，尺寸、旋转和模型输入不变时不再每帧重新分配 `xMap/yMap`。
- native 主循环按旋转角度拆分，避免每个像素执行 `switch(rotation)`。
- 左右和整行 padding 改成连续 `std::fill`，减少无效像素分支。
- YUV 到 RGB 使用 BT.601 full-range 定点整数近似，降低 `1280x1280` 逐像素浮点计算成本。
- 连续 YUV 检测时只在首次、输入 buffer 变化、尺寸/旋转变化或从预览截图源切回 YUV 时重填 padding，后续帧只写有效图像内容区。
- 大尺寸输入按输出行最多分 4 个 native worker 并行处理，降低单线程 CPU 转换耗时。

### 预防措施
- 后续优化 YUV 实时识别时，优先查看 `YOLO timing ... pre=`，不要把 GPU 推理耗时和 CPU 预处理耗时混在一起判断。
- 不要通过开启 Camera2 EIS 来掩盖 YUV 识别延迟，EIS 可能引入电子裁剪和坐标漂移。
- 若还需要继续降耗，优先评估模型输入尺寸、YUV 分析分辨率和帧率节流，而不是改变双摄校准或逻辑相机会话。

## [2026-08-28] YUV 分析流镜头来源排查报告

### 发现的问题
- 当前普通预览和 YUV 分析流挂在同一个 logical `CaptureRequest`，代码没有对任一普通输出使用 `OutputConfiguration.setPhysicalCameraId()`。
- `CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` 是结果级回传，现有日志没有把它和每次 YOLO 源帧放在同一行，现场难以判断 YUV 模式下 HAL 是否切到预期焦距镜头。
- 严重程度：中。若厂商 HAL 对预览 Surface 和 YUV Surface 使用不同处理链，可能出现预览画面和识别帧视场不一致。

### 解决方案
- 在 PreviewBitmap 和 ImageReaderYUV 两条 YOLO 源日志中加入 active 物理镜头、选中镜头与当前倍率。
- 保持普通预览为 logical Session，不重新启用普通输出物理绑定，避免改变现有逻辑相机行为。

### 预防措施
- 真机排查时对比 `YOLO source` 日志中的 `active=`、`selected=`、`zoom=`；若 active 焦距和预期不一致，优先调整逻辑 zoom 阈值或进入专门的物理绑定诊断模式，不直接改普通校准逻辑。

## [2026-08-28] 蒲公英 0.8.27 发布记录

### 发现的问题
- `0.8.26 (826)` 已上传过蒲公英，继续上传同版本会让平台和测试设备难以区分是否包含 YUV JNI 闪退修复。
- 严重程度：中。容易安装或回测到旧构建。

### 解决方案
- 上传前将 Android 构建版本提升到 `0.8.27 (827)`，并重新构建主 Debug 包。

### 预防措施
- 每次蒲公英发布包含行为修复时，先确认 `versionName/versionCode` 已递增。

## [2026-08-28] YUV 识别 JNI 包名绑定闪退报告

### 发现的问题
- luoji 项目 Kotlin 包名为 `com.example.auto_measure_luoji`，但 `yuv_preprocess.cpp` 仍导出旧项目 `com.example.auto_measure` 对应的 JNI 函数名。
- 切换 YOLO `YUV数据流` 后，`YuvPreprocessNative.process()` 首次执行时会找不到 native 符号，触发 `UnsatisfiedLinkError` 这类 `LinkageError`；原检测循环只捕获 `Exception`，因此会直接闪退。
- 同类问题也存在于 `sherpa_hotword_jni.cpp` 的第三方语音 native 函数导出名。
- 严重程度：高。用户一进入 YUV 实时识别路径就可能崩溃。

### 解决方案
- 将 `yuv_preprocess.cpp` 的导出名改为 `Java_com_example_auto_1measure_1luoji_feature_camera_domain_YuvPreprocessNative_process`。
- 将 `sherpa_hotword_jni.cpp` 的四个导出名同步改为 `auto_measure_luoji` 包名。
- `YoloDetector.detectFromYuv()` 捕获 native 绑定 `LinkageError`，native 不可用时回退到 `YuvFrameProcessor.fillInputFromYuv()`。

### 预防措施
- 复制或重命名 Android 项目包名后，必须同步检查所有 `external fun` 对应的 C++ 静态 JNI 导出名。
- YUV 实时识别不依赖 Camera2 EIS；不要为规避崩溃改动相机会话或防抖配置。

## [2026-08-28] 普通校准测距后立即采集报告

### 发现的问题
- 普通校准距离输入为空且 BLE 已连接时，点击“采集”会触发测距；收到有效距离后立即调用拍图和绿光识别。
- 严重程度：中。测距后激光点、相机曝光或 YUV/预览缓存可能尚未稳定，导致采集帧和测距动作时序过近。

### 解决方案
- BLE 自动测距成功后先回填距离并停止测距，再延迟 500ms 调用原有采图识别流程。
- 延迟等待和识别期间禁用“采集”按钮，避免重复触发同一次自动测距采集。

### 预防措施
- 后续修改校准自动测距时，保持“测距完成 -> 500ms 稳定延迟 -> 采图识别”的顺序；手动输入距离不强制引入测距延迟。

## [2026-08-26] 蒲公英上传版本号未提升报告

### 发现的问题
- YOLO 数据源切换功能已构建并上传，但 `app/build.gradle.kts` 仍停留在 `0.8.25 (825)`，蒲公英显示会与上一版重复。
- 严重程度：中。测试人员可能误判为未更新或安装到旧包。

### 解决方案
- 将 Android `versionName/versionCode` 提升为 `0.8.26 (826)` 后重新构建上传。

### 预防措施
- 每次上传蒲公英前先检查 `versionName/versionCode` 是否随功能变更递增。

## [2026-08-26] YOLO 数据源切换同步报告

### 发现的问题
- `E:\StudioProjects\Auto_measure` 已支持在预览截图与 YUV 数据流之间切换 YOLO 输入，luoji 当前仅支持 `TextureView.getBitmap()` 截图源。
- `Camera2Controller` 的分析 `ImageReader` 虽然已创建，但监听器会立即获取并关闭帧，UI 无法消费最新 YUV 帧。
- 普通校准和双镜头校准固定从预览截图采集，无法跟随 YOLO 数据源切换验证 YUV 坐标链路。
- 严重程度：中。影响真机对比 YUV 流与预览截图流的识别耗时、画面来源和坐标一致性验证。

### 解决方案
- 新增 `YoloFrameSource` 与 `KEY_YOLO_FRAME_SOURCE`，在 `更多 -> YOLO源` 中切换并持久化 `预览截图/YUV数据流`。
- `Camera2Controller` 改为只缓存最新 `ImageReader` YUV 帧，并提供 `acquireLatestAnalysisImage()`；相机关闭或重建 session 时释放旧帧。
- 实时 YOLO 检测按数据源分流：预览截图走 `detect(bitmap)`，YUV 数据流走 `detectFromYuv(image, rotation)`。
- 普通校准和双镜头校准改用 `captureLaserFrameForCalibration()`，YUV 采样通过 `ImageUtils.normalizeYuv420ForCalibration()` 转成原有 `1080x1920` PNG bytes 链路。

### 预防措施
- 后续修改 YOLO 数据源时，必须同时检查实时识别、普通校准、双镜头校准三条入口是否共用同一数据源策略。
- YUV 帧取走后必须 `close()`，缓存替换或相机关闭前也必须释放旧帧，避免 `ImageReader.maxImages` 堵塞。
- 普通预览继续保持父逻辑相机 Session；不得为了 YUV 数据源重新引入普通预览 `OutputConfiguration.setPhysicalCameraId()`。

## [2026-08-25] EIS 实验防抖同步报告

### 发现的问题
- `E:\StudioProjects\Auto_measure` 已有 EIS 实验模式，luoji 当前只保留 OIS，且每次请求固定写 `CONTROL_VIDEO_STABILIZATION_MODE_OFF`。
- 用户要求 luoji 与源项目保持一致，因此需要恢复 EIS OFF/ON/PREVIEW 三档选择能力。
- 严重程度：中。EIS ON/PREVIEW 可能触发电子裁剪，导致预览坐标与校准坐标发生漂移。

### 解决方案
- `Camera2Controller` 同步 `videoStabilizationModeChoices()`、`setVideoStabilizationMode()`、设备支持模式扫描和 capture result 日志。
- `CameraScreen` 在 `更多` 菜单新增 `防抖 EIS:...` 入口，弹窗内可切换 OIS 与 EIS 模式。
- 保留普通预览父逻辑相机 Session 和 active physical id 监听，不回退到普通物理输出绑定。

### 预防措施
- EIS 默认必须保持 OFF；只有用户在防抖弹窗中显式选择 ON/PREVIEW 时才写入请求。
- 校准、绿光识别或坐标对比时必须记录 EIS 当前模式，发现坐标漂移时优先切回 OFF。
- 后续相机防抖相关修改要同时检查 `Camera2Controller` 状态、UI 菜单入口和 capture result 日志。

## [2026-08-24] 普通预览逻辑相机恢复报告

### 发现的问题
- 普通预览新增 `bindPhysicalPreview` 后，虽然仍打开父逻辑 camera id，但预览 `Surface` 会通过 `OutputConfiguration.setPhysicalCameraId()` 绑定到物理镜头输出。
- 该路径会让普通预览画面接近“指定物理镜头流”，和当前要恢复的逻辑相机/HAL 自动镜头选择与融合验证目标不一致。
- 严重程度：中。普通预览容易和物理镜头调用方式混淆，也会影响 active physical id 对逻辑多摄行为的观察。

### 解决方案
- 删除 `Camera2Controller.openCamera()` 的 `bindPhysicalPreview` 参数和普通物理输出绑定状态。
- `Camera2Controller.createSession()` 只有在双摄校准 `strictPhysical=true` 且设备支持物理输出时才调用 `OutputConfiguration.setPhysicalCameraId()`。
- `CameraScreen` 普通打开相机只传 `strictPhysical = isDualCalibMode && lens.physicalId != null`，普通模式恢复纯逻辑 Session。
- 普通预览的 active array、zoom range、OIS 能力选择恢复使用父逻辑相机特征；双摄校准仍使用物理候选特征。

### 预防措施
- 后续普通预览禁止再新增 `bindPhysicalPreview` 或在普通入口用 `OutputConfiguration.setPhysicalCameraId()` 绑定预览 Surface。
- `setPhysicalCameraId()` 仅允许保留在双摄校准 `strictPhysical=true` 的物理 Session 路径中，不能影响普通预览。
- Camera2 相关修改仍禁止启用 EIS；如需稳定只能使用 OIS 或算法层物理补偿。

## [2026-08-07] 普通预览物理输出绑定报告

状态：已被 2026-08-24 “普通预览逻辑相机恢复报告”覆盖；本节仅保留历史实验记录。

### 发现的问题
- 普通预览虽然打开父逻辑相机，但预览输出仍可能由厂商 HAL 做逻辑多摄融合或自动切换，不能明确保证画面来自最大焦距物理镜头。
- `OutputConfiguration.setPhysicalCameraId()` 已在双摄校准物理路径使用，但普通预览尚未复用该能力绑定预览 Surface。
- 严重程度：中。普通模式难以绕开逻辑多摄融合，也不利于验证最大焦距镜头真实画面。

### 解决方案
- `Camera2Controller.openCamera()` 新增 `bindPhysicalPreview` 参数；普通预览传入 `true` 时，仍打开父逻辑相机 ID，但用 `setPhysicalCameraId()` 绑定预览 Surface 到当前选中物理候选。
- 默认普通候选仍是最大焦距镜头，因此普通预览默认绑定最大焦距物理输出；手动切换广角时绑定当前选中的广角物理输出。
- 物理预览输出绑定模式关闭非必要 YUV `ImageReader`，避免部分机型不支持物理预览流 + 逻辑 YUV 流组合。
- 普通物理预览输出配置失败时回退逻辑 Session；双摄校准 `strictPhysical=true` 仍保持原逻辑，物理 Session 失败直接报错。

### 预防措施
- 当前策略已废弃；后续普通预览必须保持父逻辑相机 Session，不得重新引入 `bindPhysicalPreview`。
- 双摄校准路径继续由 `strictPhysical=true` 控制，不能被普通预览 fallback 策略影响。
- Camera2 相关修改仍禁止启用 EIS；如需稳定只能使用 OIS 或算法层物理补偿。

## [2026-08-06] 当前画面镜头焦距显示格式报告

### 发现的问题
- 当前画面镜头状态行同时显示实际焦距和等效 35mm 焦距，不符合只用 35mm 等效焦距展示的需求。
- 严重程度：低。功能可用，但真机观察时读数不够直接。

### 解决方案
- 将状态行格式改为 `画面镜头: 35mm等效 xxmm (id=...)`。
- 保留 active physical id 映射逻辑和未上报提示，不改变相机打开方式、倍率逻辑或双摄校准逻辑。

### 预防措施
- 后续 UI 显示当前画面焦距时，优先展示 `LensCandidate.eq35mm`，不要把物理实际焦距放在主读数位置。
- Camera2 相关修改仍禁止启用 EIS；如需稳定只能使用 OIS 或算法层物理补偿。

## [2026-08-06] 当前画面物理镜头焦距不可见报告

### 发现的问题
- 普通预览使用逻辑摄像头后，界面只能看到选中的候选镜头和 zoom 后端，无法确认当前画面实际来自哪个物理焦距镜头。
- 只按 `selectedLens` 推断会误导真机验证，因为逻辑多摄可能由厂商 HAL 在不同倍率下切换或融合物理镜头。
- 严重程度：中。无法判断当前画面是否真的切到长焦，也不利于排查逻辑摄像头融合行为。

### 解决方案
- `Camera2Controller` 为 repeating request 增加 `CaptureCallback`，从 `TotalCaptureResult` 读取 `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`。
- `CameraScreen` 将 active physical id 映射到 `LensScanner` 扫描出的 `LensCandidate`，在状态区用 35mm 等效焦距显示镜头 ID。
- strict physical 模式直接回报绑定的物理镜头 ID；逻辑相机未上报时明确显示“未上报 activePhysicalId”，不伪装成已确认焦距。

### 预防措施
- 后续判断“当前画面用哪个焦距镜头”必须优先使用 capture result 的 active physical id，不得只看 `selectedLens`。
- 该显示只反映厂商 HAL 回报的 active physical id；如果设备仍做融合或不回传，需要结合真机画面和日志验证。
- Camera2 相关修改仍禁止启用 EIS；如需稳定只能使用 OIS 或算法层物理补偿。

## [2026-08-05] 普通预览 24x 上限与 0.1x 下限偏移报告

### 发现的问题
- 上一版普通预览最大倍率改为“不限”，但当前需求需要恢复固定最大 `24x`。
- 普通模式最低倍率仍等于最大焦距对应光学基准倍率，缺少额外 `0.1x` 偏移。
- 严重程度：中。倍率范围与当前真机验证预期不一致，会影响进入预览后的默认倍率和滑块可调范围。

### 解决方案
- 新增普通预览常量 `NORMAL_PREVIEW_MAX_ZOOM = 24f` 和 `NORMAL_MIN_ZOOM_OFFSET = 0.1f`。
- `normalMinZoomFor()` 返回光学基准倍率 + `0.1x`，并保证不超过 `24x`；`clampNormalZoom()` 恢复 `min..24x` 裁剪。
- `ZoomSlider.maxRatio` 恢复为固定 `Float`，普通模式传入 `24x`，不再显示 `不限`。
- `ZoomController` 历史入口同步使用 `24x` 上限和 `0.1x` 下限偏移。
- 双摄像头校准 `applyDualZoom()`、`strictPhysical` 物理镜头路径和 `1x..长焦倍率` 范围保持不变。

### 预防措施
- 后续普通模式最大倍率调整必须优先修改 `NORMAL_PREVIEW_MAX_ZOOM`，不要重新散落硬编码。
- 后续普通模式最低倍率调整必须通过 `NORMAL_MIN_ZOOM_OFFSET`，保证镜头切换、入场倍率、滑块和普通校准一致。
- Camera2 相关修改仍禁止启用 EIS；如需稳定只能使用 OIS 或算法层物理补偿。

## [2026-08-05] 普通预览最大倍率固定上限报告

状态：已被同日“普通预览 24x 上限与 0.1x 下限偏移报告”覆盖；本节只保留上一版“不限”需求的历史记录。

### 发现的问题
- 普通模式倍率最大值仍按父逻辑相机 `maxDigitalZoom` / `CONTROL_ZOOM_RATIO_RANGE` 做应用层限制，不能满足普通预览最大倍数“不限制”的要求。
- 普通广角校准 5x 也会被应用层最大可用倍率提前截断，状态文案仍提示“当前广角最大仅 x”。
- 严重程度：中。普通预览可用倍率被应用层提前卡住，真机上无法继续验证更高倍率 crop 效果。

### 解决方案
- `ZoomSlider.maxRatio` 改为可空；普通模式传入 `null`，右侧显示 `不限`，滑块上界随当前倍率动态扩展。
- `normalMinZoomFor()` / `clampNormalZoom()` 只保留最大焦距对应光学基准倍率下限，不再计算或裁剪普通最大倍率。
- `Camera2Controller` 的中心 crop fallback 不再按 `maxDigitalZoom` 截断请求倍率；`CONTROL_ZOOM_RATIO` 范围外请求直接走 crop 回退。
- 双摄像头校准 `applyDualZoom()`、`strictPhysical` 物理镜头路径和 `1x..长焦倍率` 范围保持不变。

### 预防措施
- 当前普通模式倍率逻辑应使用 `NORMAL_PREVIEW_MAX_ZOOM` / `normalMaxZoomFor()` 固定为 `24x`，不得继续沿用本节的“不限”策略。
- 当前普通模式仍需通过真机 HAL 行为和 `zoom: backend=...` 日志确认 `CONTROL_ZOOM_RATIO` 与 fallback crop 的实际效果。
- Camera2 相关修改仍禁止启用 EIS；如需稳定只能使用 OIS 或算法层物理补偿。

## [2026-08-05] 普通相机物理镜头与倍率下限报告

### 发现的问题
- 普通入口选中最大焦距候选时，只要候选带有 `physicalId`，`Camera2Controller` 就会创建物理镜头会话。
- 普通模式 `currentEquivRatio` 和倍率滑块最小值仍从 `1x` 开始，无法表达“已进入最大焦距对应光学倍率”的状态。
- 严重程度：中。普通预览和用户期望的逻辑多摄 zoom 行为不一致，且可能和后续逻辑相机自动镜头切换能力冲突。

### 解决方案
- `Camera2Controller` 只有在 `strictPhysical=true` 且存在物理 ID 时才创建物理会话；普通入口统一打开父逻辑相机。
- `LensCandidate` 额外记录父逻辑相机的 active array、max digital zoom、`CONTROL_ZOOM_RATIO_RANGE` 和 OIS 能力，普通入口使用逻辑相机能力下发请求。
- 普通模式按最大焦距候选相对同一逻辑相机广角候选的等效焦距比计算光学基准倍率，并把它作为进入倍率和滑块最小值。
- 双摄像头校准的 `applyDualZoom()`、`strictPhysical` 打开路径、广角 `5x` 和长焦物理 `1x` 采集逻辑保持不变。

### 预防措施
- 后续改普通相机入口时，不能再用 `physicalId != null` 直接决定是否创建物理会话，必须区分普通预览和双摄校准。
- 普通模式滑块最小值必须跟 `normalMinZoomFor()` 保持一致，不得写回固定 `1f`。
- Camera2 相关修改仍禁止启用 EIS；如需稳定只能使用 OIS 或算法层物理补偿。

## [2026-08-05] 副本 App 标识重复报告

### 发现的问题
- 复制后仍沿用源项目 `rootProject.name=Auto_measure`、`app_name=Auto_measure`、`applicationId=com.example.auto_measure` 和 `namespace=com.example.auto_measure`。
- Android Studio 加载项目和手机安装 App 时都会显示为 `Auto_measure`，并且主包会与原 App 包名冲突。
- 严重程度：高。副本无法和原 App 明确区分，也无法同时安装同名包。

### 解决方案
- 将项目名和桌面显示名改为 `Auto_Measure_luoji`。
- 将 `namespace`、`applicationId`、Kotlin 包声明和源码目录迁移到 `com.example.auto_measure_luoji`。
- 将 `laserCompare` 变体的显示名和包名同步到 `Auto_Measure_luoji 绿光对比` / `com.example.auto_measure_luoji.lasercompare`。

### 预防措施
- 后续复制 Android 项目后，必须立即区分 `rootProject.name`、`app_name`、`applicationId`、`namespace`、测试断言和源码包路径。
- 如果只想保留源码包路径不变，也至少必须更换 `applicationId` 和应用显示名，避免安装冲突。

## [2026-08-05] 项目复制风险报告

### 发现的问题
- 目标目录 `E:\StudioProjects\Auto_Measure_luoji` 原本是未纳入 Git 的 Android 模板项目，直接覆盖会难以恢复旧状态。
- 目标模板包名 `com.example.auto_measure_luoji` 与源项目原包名 `com.example.auto_measure` 并存时可能造成编译噪声或入口混乱。
- 源项目代码资产库中记录了 `E:\StudioProjects\Auto_measure` 的绝对路径，复制后如果不修正会误导后续复用定位。
- 严重程度：中。主要影响复制可回滚性、项目路径索引准确性和后续维护。

### 解决方案
- 在目标目录初始化 Git，并先提交复制前模板快照。
- 在 `feature/copy-auto-measure-code` 分支移除目标模板跟踪文件，再从源项目 Git `HEAD` 展开跟踪文件，避免旧模板源码残留。
- 将 `docs/code-library/` 内的项目路径更新为 `E:\StudioProjects\Auto_Measure_luoji`。

### 预防措施
- 后续复制 Android 项目前，先确认目标目录是否已有源码或未提交改动。
- 复制项目级代码资产库后，必须同步修正项目根目录、canonical code path 和当前分支记录。
- Camera2 相关源码复制后仍需保持 EIS 禁用；如需稳定，仅允许 OIS 或算法层物理补偿。

## [2026-08-03] 顶部按钮挤占导致识别入口不可见报告

### 发现的问题
- 相机页顶部同时固定显示 `广角/更多/OIS/语音/Log`，右侧固定按钮宽度过大时会压缩左侧横向滚动主操作区。
- `识别` 虽然仍在代码中渲染，但可能因为左侧可用宽度太小而需要横向滑动才能看到，用户会误以为按钮被隐藏。
- 严重程度：中。影响模型识别主入口的可发现性，尤其是小屏、字体较大或状态文案变长时。

### 解决方案
- 顶部固定区调整为 `识别`、`校准`、`更多`，并把 `识别` 放在首位。
- 将 `广角/广角中`、`OIS`、`语音`、`Log` 收纳进 `更多` 菜单，保留各自启用、禁用和高亮状态。
- `laserCompare` 包继续固定显示 `绿光对比`，避免绿光对比专包测试入口被菜单隐藏。

### 预防措施
- 后续新增顶部入口默认放入 `更多`，除非它是主流程高频操作。
- 顶栏固定按钮数量应控制在 3 个左右；必须优先保证 `识别` 和 `校准` 初始可见。

## [2026-08-03] 系统语音不可用时第三方包下载入口缺失报告

### 发现的问题
- 真机显示“语音识别不可用：系统没有可用识别服务，且第三方语音包未就绪”后，界面没有触发第三方语音包下载弹框。
- “第三方语音包”弹框虽然文字显示为第三方包，但确认按钮仍调用 `requestChineseLanguageModelDownload()`，实际会再次进入系统语言包下载链路。
- 严重程度：高。没有系统语音服务的设备无法进入第三方 SenseVoice/VAD 下载与识别路径。

### 解决方案
- 在 `ChineseVoiceHotwordRecognizer.start()` 中，当系统 `SpeechRecognizer.isRecognitionAvailable()` 为 false 且第三方模型未就绪时，直接发布 `fallbackActive=true` 的 `VoiceModelDownloadState`，打开第三方包下载入口。
- 在 `CameraScreen` 的下载弹框确认按钮中按 `fallbackActive` 分流：第三方包状态调用 `requestThirdPartyVoiceModelDownload()`，普通中文包状态才调用系统语言包下载。
- 第三方下载失败后，仅当系统语音服务可用时才调度系统识别重启，避免不可用设备进入无效循环。

### 预防措施
- UI 文案显示“第三方语音包”时，确认按钮必须调用第三方下载器，不得复用系统语言包下载接口。
- 系统识别服务不可用不能作为终止条件；只要第三方模型未就绪且下载入口可用，就应提示用户下载本地模型包。

## [2026-08-03] 第三方语音回调无限递归闪退报告

### 发现的问题
- 真机堆栈反复出现 `ChineseVoiceHotwordRecognizer.startThirdPartyRecognition$lambda...` 和 `ChineseVoiceHotwordRecognizer.kt:319`。
- 根因是 `SherpaOnnxHotwordRecognizer(context, paths).apply { ... }` 内部给 `onStatusChanged/onListeningChanged/onHotwordHit` 赋值时，lambda 中未限定外层对象，实际解析到 Sherpa 识别器自身回调字段，状态变化时会自调用直到栈溢出。
- 严重程度：高。点击语音后第三方识别一启动就会闪退。

### 解决方案
- 回调转发显式使用 `this@ChineseVoiceHotwordRecognizer`，确保 Sherpa 子识别器状态只转发给外层 UI 回调，不再调用自身。
- Android 版本更新为 `0.8.23 (823)`，便于和闪退版本区分。

### 预防措施
- 在 Kotlin `apply/also/run` 这类带 receiver 的作用域中，回调转发必须明确外层 receiver，避免同名属性解析到内部对象。
- 语音回调类新增或调整时，优先检查是否存在 `onX = { onX?.invoke(...) }` 这种自递归写法。

## [2026-08-03] 第三方语音引擎未接入与广角按钮不可见报告

### 发现的问题
- 第三方 SenseVoice/VAD 模型包虽然已经支持下载，但 `ChineseVoiceHotwordRecognizer` 下载完成后仍继续启动 Android 系统 `SpeechRecognizer`，没有使用第三方本地识别引擎。
- 下载器只检查 `silero_vad.onnx` 和 SenseVoice `.tar.bz2` 压缩包存在，没有解压并校验 `model.int8.onnx`、`tokens.txt`，导致“下载完成”和“可推理”状态混淆。
- 主界面 `广角/广角中` 按钮仍在左侧 `horizontalScroll` 区域，小屏或按钮较多时会被滚出初始视口。
- 严重程度：高。语音路径行为和界面提示不一致，用户无法验证第三方识别；广角入口不可见会影响镜头切换测试。

### 解决方案
- 新增 Sherpa-ONNX native 库、`sherpa_hotword_jni` bridge、`SherpaOnnxHotwordNative` 和 `SherpaOnnxHotwordRecognizer`，使用 `AudioRecord 16kHz PCM16 -> Silero VAD -> SenseVoice OfflineRecognizer -> 热词匹配`。
- `SpeechRecognizer` 改为懒加载；第三方模型就绪时优先启动 Sherpa 本地识别，未就绪时才回退系统识别。
- `ThirdPartyVoiceModelDownloader` 下载 SenseVoice 后自动解压 `.tar.bz2`，并以 `silero_vad.onnx`、`model.int8.onnx`、`tokens.txt` 作为真实就绪条件。
- 旧版本已下载 `.tar.bz2` 但未解压时，点击语音会先解压现有包，再启动第三方识别。
- 将 `广角/广角中` 移出横向滚动主操作区，固定放在 `更多` 前面。

### 预防措施
- 不得再把“第三方模型压缩包存在”描述为“已使用第三方识别”；必须以解压后的核心模型文件和 native 引擎启动状态判断。
- 语音问题排查同时过滤 `ChineseVoiceHotword|SherpaHotword`，确认是否出现 `第三方语音识别中（SenseVoice+VAD）`。
- 顶部关键入口不得放入 `horizontalScroll` 容器；后续新增低频按钮继续放入 `更多` 菜单。

## [2026-08-03] 语音支持检查失败与 SODA 离线错误报告

### 发现的问题
- 真机日志显示 `ChineseVoiceHotword: checkRecognitionSupport failed: error=14`，对应系统语音服务无法执行支持检查。
- 支持检查失败后旧逻辑继续以离线模式启动，随后 Google/Samsung 的 SODA 离线识别模块打印 `Failed to get language pack of required locale: error 12`。
- 日志没有再连续出现 `startListening called while listening is in progress`，说明上一版启动互斥已生效；当前问题重点变成支持检查失败后的离线路径选择。
- 严重程度：中。会造成日志刷屏和离线包错误提示，影响语音识别链路判断。

### 解决方案
- `checkRecognitionSupport` 返回错误时改为调用 fallback，直接进入系统默认中文识别，并持久化记录离线包不可用。
- 默认/在线识别 Intent 不再传 `EXTRA_PREFER_OFFLINE=false`、`EXTRA_LANGUAGE_PREFERENCE`、`EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE`，减少系统服务误走离线偏好。
- 系统默认识别如果仍返回语言类错误，不再关闭语音监听，而是保留第三方语音包入口并按 2 秒退避重试。
- `speechErrorName()` 新增 `ERROR_CANNOT_CHECK_SUPPORT` 和 `ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS`，便于日志判断。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 执行 `.\gradlew.bat :app:assembleLaserCompare`。
- 真机验证：过滤 `ChineseVoiceHotword|SodaSpeechRecognizer|RemoteSpeechRecognitionService`，确认首次支持检查失败后不再出现 `mode=离线` 的启动。

### 预防措施
- `checkRecognitionSupport` 失败不能等同于离线包可用，不能继续优先走离线识别。
- 默认/在线识别 Intent 只保留必要语言参数；离线偏好只在明确需要离线模式时设置。
- Google 语音服务内部仍可能初始化 SODA 并打印第三方服务日志，应以 `ChineseVoiceHotword` 自身日志判断 App 实际请求模式。

## [2026-08-03] 语音识别重复启动与离线包错误报告

### 发现的问题
- 真机日志出现 `SodaSpeechRecognizer: Failed to get language pack of required locale: error 12`，说明系统离线语音包不可用，和外网是否可访问不是同一个问题。
- 日志连续出现 `RemoteSpeechRecognitionService: #startListening called while listening is in progress for this caller`，说明 App 在上一轮系统识别还未完全释放时又启动了新一轮识别。
- `VoiceSearchConfigUtils: Unrecognized recognizer for [ja-JP...]` 来自 Google/Samsung 语音服务内部配置扫描，不是本 App 请求日语识别，也不是当前主要故障点。
- 严重程度：中。会导致语音服务 busy、取消当前识别流，影响热词命中稳定性。

### 解决方案
- `ChineseVoiceHotwordRecognizer` 新增 pending start 互斥，同一时间只保留一个待启动任务。
- 调用 `cancel()` 后增加 1.2 秒释放等待，避免系统服务麦克风关闭前再次 `startListening()`。
- 遇到 `ERROR_RECOGNIZER_BUSY` 时改为 1.5 秒退避重启。
- 系统离线中文包返回 `ERROR_LANGUAGE_NOT_SUPPORTED=12` 后写入 `SharedPreferences`，后续启动直接使用系统默认中文识别，不再反复优先撞离线包。
- 增加 `ChineseVoiceHotword` 日志：`startListening`、`onError`、fallback 原因和状态字段，便于 Logcat 排查。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 执行 `.\gradlew.bat :app:assembleLaserCompare`。
- 真机验证：过滤 `ChineseVoiceHotword|RemoteSpeechRecognitionService|SodaSpeechRecognizer`，确认 busy 日志不再连续刷屏。

### 预防措施
- 系统 `SpeechRecognizer.cancel()` 后不要立即重启，需要给系统识别服务释放麦克风和会话的时间。
- 离线语言包能力要作为设备能力缓存，不能每次启动都重复走失败路径。
- `VoiceSearchConfigUtils` 的第三方服务内部配置日志不能直接当成本 App 语言请求错误，需要结合 `ChineseVoiceHotword` 自身日志判断。

## [2026-08-03] 第三方语音包下载与断点续传报告

### 发现的问题
- 当前“下载中文语音包”实际调用的是 Android 系统 `SpeechRecognizer.triggerModelDownload()`，下载能力由设备内置语音服务决定。
- 真机返回 `ERROR_LANGUAGE_NOT_SUPPORTED=12` 时，含义是当前系统语音服务不支持下载该中文离线语言包；这和设备能否访问外网不是同一个问题。
- 项目之前没有 App 自己的语音模型下载链路，也没有 `.part` 临时文件或 HTTP `Range` 断点续传。
- 严重程度：中。会导致用户无法下载第三方语音包，且弱网/中断后需要重下大文件。

### 解决方案
- 新增 `ThirdPartyVoiceModelDownloader`，通过 HTTPS 下载 `silero_vad.onnx` 与 SenseVoice INT8 模型压缩包。
- 下载到应用私有目录 `files/sherpa_models`，先写入 `.part` 文件，完成后再 rename 为正式文件。
- 如果 `.part` 已存在，下一次下载自动携带 `Range: bytes=<已下载>-` 续传；服务器不接受续传时自动从头重下。
- 系统语言包返回 `ERROR_LANGUAGE_NOT_SUPPORTED=12` 时，改为切到系统默认中文识别，并在弹框中提供 `下载第三方包` 入口。
- `AndroidManifest.xml` 增加 `INTERNET` 权限。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 执行 `.\gradlew.bat :app:assembleLaserCompare`。
- 真机验证：下载中断后再次点击下载，应从 `.part` 已下载大小继续请求。

### 预防措施
- 不再把第三方模型下载依赖到系统 `SpeechRecognizer` 语言包安装接口。
- 大文件下载必须使用临时文件、完成后原子替换，并保留中断文件用于续传。
- 当前版本仍未接入 Sherpa-ONNX native 识别，第三方包下载完成后仍以系统默认中文识别继续监听；后续接入 native 识别前不要宣称已使用第三方模型推理。

## [2026-08-03] 更多按钮不可见修正报告

### 发现的问题
- `更多` 按钮被放在左侧横向滚动按钮组内，小屏幕或按钮文本较长时，初始可见区域只展示 `校准/识别/广角` 等靠前按钮。
- 用户需要横向滑动顶部按钮组才可能看到 `更多`，实际使用中会误判为没有入口。
- 严重程度：中。影响低频功能入口可达性，但不影响相机底层流程。

### 解决方案
- 将 `更多` 按钮和 `DropdownMenu` 移出左侧横向滚动区域，放入固定操作区。
- 保持 `图源/绿光识别/区域/判定点/统计` 的回调逻辑不变，只调整菜单锚点位置。
- Android 版本更新为 `0.8.18 (818)`，便于安装包区分。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 执行 `.\gradlew.bat :app:assembleLaserCompare`。

### 预防措施
- 关键折叠菜单入口必须固定可见，不能再放入横向滚动 Row 内。
- 后续新增低频按钮只放入 `更多` 菜单，避免再次挤占顶部固定操作区。

## [2026-08-03] 主界面按钮挤占与广角切换报告

### 发现的问题
- 主界面顶部已有 `校准/识别/图源/绿光/OIS/语音/Log/区域/判定点/统计` 多个入口，继续新增广角切换按钮会进一步挤占横向空间。
- 原两行工具栏虽然能缓解默认视口不可见问题，但会增加顶部占用高度，状态 HUD 和调试面板更容易被压缩。
- 严重程度：中。影响真机操作效率和小屏幕可见性，但不属于底层相机功能故障。

### 解决方案
- 顶部主操作保留 `校准/识别/广角/更多`，右侧继续固定 `OIS/语音/Log`。
- 将 `图源/绿光识别/区域/判定点/统计` 收纳到 `更多` 下拉菜单，复用原业务回调，不改变功能行为。
- `广角/广角中` 按钮在非校准状态下切换广角镜头；已在广角时再次点击切回默认镜头，并清理旧锚点与检测框。
- `laserCompare` 包继续固定显示 `绿光对比` 按钮，避免专用测试入口被隐藏。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 执行 `.\gradlew.bat :app:assembleLaserCompare`。

### 预防措施
- 后续新增顶部入口时优先放入 `更多` 菜单，只有高频主操作或硬件状态入口才固定显示。
- 镜头切换必须在校准流程中禁用，避免破坏普通校准和双镜头校准状态机。
- Camera2 EIS 继续保持关闭；本次只调整 UI 收纳和镜头选择入口。

## [2026-07-31] 普通校准原生光学倍率临时回退报告

### 发现的问题
- `0.8.13` 将普通校准长焦/非广角镜头改为相对广角的原生光学倍率采集，但当前阶段需要暂时不采用该逻辑。
- 普通模式滑条最小值也被抬到物理镜头原生光学倍率，和旧版普通模式操作习惯不一致。
- 严重程度：中。会影响现场对比旧版普通校准流程。

### 解决方案
- 普通校准保留广角镜头自动切到 `5x` 的规则。
- 长焦/非广角镜头普通校准恢复为保持当前 `currentEquivRatio` 采集，不再自动改到原生光学倍率。
- 普通模式滑条最小值恢复为 `1x`；Camera2 zoom 下发直接使用当前倍率。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 执行 `.\gradlew.bat :app:assembleLaserCompare`。

### 预防措施
- 后续重新启用“普通校准按原生光学倍率”前，必须先明确机型和倍率语义验证方案。
- 本次不改变双镜头校准的广角 5x / 长焦物理镜头 `1x` 采集规则。
- Camera2 EIS 继续保持关闭；本次只回退普通校准倍率策略。

## [2026-07-31] 校准曝光补偿 EV -2.0 报告

### 发现的问题
- 绿光校准采集依赖画面中的激光点亮度，但普通校准和双镜头校准没有统一固定 Camera2 曝光补偿。
- 如果设备自动曝光把画面拉亮，绿光区域可能更容易过曝或扩散，影响绿光质心稳定性。
- 严重程度：中。会影响现场校准样本一致性，但不同设备是否完整支持 `-2.0 EV` 需要实机日志确认。

### 解决方案
- `Camera2Controller` 新增 EV 曝光补偿接口，读取 `CONTROL_AE_COMPENSATION_STEP` 和 `CONTROL_AE_COMPENSATION_RANGE`，将目标 EV 换算为 `CONTROL_AE_EXPOSURE_COMPENSATION` 整数档位。
- 普通校准和双镜头校准面板打开期间设置 `-2.0 EV`，关闭后恢复 `0 EV`。
- 输出 `exposure: requestedEV/actualEV/steps/step/range` 日志；如果设备范围不足，会自动裁剪到支持范围。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 执行 `.\gradlew.bat :app:assembleLaserCompare`。

### 预防措施
- 后续修改校准曝光时必须在 Camera2 请求层下发，不要只改 UI 亮度或截图后处理。
- EV 目标值必须按设备步进换算并裁剪，不能假设所有设备的补偿步进都是 `1.0 EV`。
- Camera2 EIS 继续保持关闭；本次只修改 AE 曝光补偿，不启用电子防抖。

## [2026-07-31] 普通校准自动测距与失败重试报告

### 发现的问题
- 普通校准距离输入为空时只提示输入错误，没有像双镜头校准一样触发 BLE 测距、回填距离并继续采集。
- 普通校准在绿光检测完成前就更新 `lastCollectedDistance`，如果第 2 次采集识别失败，界面可能把当前距离当作重复距离，按钮显示“换位置”并阻断原地重试。
- 严重程度：中。会导致现场校准流程中断，尤其是在弱绿光、曝光变化或短时识别失败时更明显。

### 解决方案
- 普通校准面板新增自动测距流程：空距离且 BLE 已连接时发送 `measure()`，收到 `line > 0` 后回填输入框、停止测距并提交采集。
- 普通校准面板的开绿光逻辑改为跟随 BLE 连接状态，先打开面板后连接设备时也会发送 `laserOn()`。
- 增加 4 秒测距超时和面板销毁清理，超时或关闭时调用 `stopMeasure()`。
- 将 `lastCollectedDistance` 改为仅在绿光识别成功后更新；失败时返回“绿光识别失败，请调整瞄准后重试”提示，保留当前距离供继续重试。
- 将 BLE `lastDistance` 状态改为 `neverEqualPolicy()`，保证设备连续返回相同距离值时 Compose 仍会收到新事件。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 执行 `.\gradlew.bat :app:assembleLaserCompare`。

### 预防措施
- 校准采集的去重状态只能在样本真正写入后更新，不能在检测前预写。
- 自动测距流程必须忽略旧的距离对象，并在超时、成功、关闭面板三个路径里停止测距；BLE 距离 state 必须保留“同值也通知”的事件语义。
- Camera2 EIS 继续保持关闭；本次只修改校准输入流程和失败状态处理。

## [2026-07-31] 普通校准长焦原生倍率报告

### 发现的问题
- 普通校准只对广角镜头强制切到 `5x`，非广角镜头会沿用当前 `currentEquivRatio`。
- 当当前镜头是物理长焦时，原生光学倍率会被当成 `1x` 记录，或者把滑条倍率直接下发给 `CONTROL_ZOOM_RATIO` 后与物理倍率叠加。
- 严重程度：中。校准样本记录倍率不准确会影响后续 100m 坐标反算；在 S22 等机型上还可能出现观感倍率过大。

### 解决方案
- 新增 `opticalBaseForLens()`，按 `当前镜头 eq35mm / 广角 eq35mm` 计算相对广角的原生光学倍率，并与镜头选择显示保持整数倍率。
- 新增 `cameraZoomForLens()`，把等效倍率换算为当前镜头上的 Camera2 请求倍率。
- 普通校准时，长焦/非广角镜头使用原生光学倍率记录样本；Camera2 对该镜头只请求必要数码倍率，原生长焦校准时为 `1x`。
- 普通模式滑条在非广角镜头上使用原生光学倍率作为最小值，避免 UI 倍率低于当前物理镜头实际画面倍率。
- 新增 `zoom-map` 日志输出 `equiv/opticalBase/camera`，便于区分记录倍率和 Camera2 实际请求倍率。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 执行 `.\gradlew.bat :app:assembleLaserCompare`。

### 预防措施
- 后续修改普通校准倍率时，必须区分等效倍率、物理镜头原生光学倍率和 Camera2 请求倍率。
- Camera2 EIS 继续保持关闭；本次只调整倍率语义和校准样本记录。

## [2026-07-30] Legacy laser detector refinement contamination report

### Found Issue
- `detectLaserCenterFromBytesLegacy()` correctly used the old resize path, but the final large-region center refinement was shared with the current detector.
- As a result, `laserCompare` compared old resize plus new large-component center logic, not the complete previous detector behavior.
- Severity: medium. The comparison demo could under-report the coordinate difference caused by the new large green blob fix.

### Solution
- Kept `BRIGHTCORE_REFINE_AREA_MAX = 150` only for the current detector path.
- Legacy detector calls now always use `refineCenterByBrightcoreInRegion()` for the final center refinement.
- Added `centerMode` to debug reports so saved comparison output can show whether a result came from `large-component-mean` or `brightcore-refine`.

### Verification
- Run `.\gradlew.bat :app:compileDebugKotlin`.
- Run `.\gradlew.bat :app:assembleDebug`.
- Run `.\gradlew.bat :app:assembleLaserCompare`.

### Prevention
- New-vs-old detector comparisons must keep all behavior changes behind the same legacy/current switch, not only the resize function.
- Camera2 EIS remains disabled; this fix only changes image-processing comparison behavior.

## [2026-07-30] Large green laser blob center drift report

### Found Issue
- On `C:\Users\HS\Desktop\0730`, large green laser blobs such as `laser_compare_raw_1785394104113.png`, `laser_compare_raw_1785394120860.png`, and `dual_tele_raw_1785394113105.png` were detected above the visible green blob center.
- Root cause: `refineCenterByBrightcoreInRegion()` follows the brightest white/saturated core inside the region. For bloomed laser spots, that core can sit near the top of the green component, so the final coordinate is pulled upward by roughly 9-13 px.
- Severity: medium. The detector still found the correct laser component, but the reported center could be biased for large saturated blobs.

### Solution
- Added `BRIGHTCORE_REFINE_AREA_MAX = 150`.
- For candidate regions at or above that small-image area, `LaserDetector` now returns the connected component geometry center instead of bright-core refinement.
- Smaller laser spots keep the existing bright-core refinement path.

### Verification
- Ran `.\gradlew.bat :app:compileDebugKotlin`.
- Ran `.\gradlew.bat :app:assembleDebug`.
- Offline replay on `C:\Users\HS\Desktop\0730` shows the biased samples move from about `(592,1209)` / `(594,1208)` to about `(594,1217)` / `(594,1218)`, while small-spot samples stay unchanged.
- Device instrumentation was not run because `adb devices` returned no connected device.

### Prevention
- Do not tune color thresholds to solve this case; the component selection is correct. Only the final center refinement strategy needed a guard for large bloomed components.
- Camera2 EIS remains disabled; this fix is image-processing-only.

## [2026-07-30] Camera2 zoom 日志不可见报告

### 发现的问题
- 倍率后端只在 `Camera2Ctrl` tag 下以 `Log.d` 输出。
- 用户如果看应用内 Log 面板或系统 Logcat 时过滤级别、进程、缓冲区不匹配，可能看不到 `Camera2Ctrl` 和 `zoom`。
- 严重程度：低到中。相机 zoom 功能可用，但验证成本高。

### 解决方案
- `Camera2Controller` 每次应用倍率后发布统一的 `zoom: backend=..., ratio=...` 摘要。
- 摘要同时输出到 `Camera2Ctrl:I`、`Camera2Ctrl:D`，并回调给 `CameraScreen`。
- `CameraScreen` 将最近一次 zoom 摘要显示在主界面状态区，并用 `CameraScreen:I` 再打一遍日志。
- 应用内 Log 面板补充 `CameraScreen:I`、`Camera2Ctrl:I`、`Camera2Ctrl:W` 过滤项。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 真机验证：启动后状态区显示 `zoom: backend=CONTROL_ZOOM_RATIO, ratio=...`；打开 Log 面板可搜索 `zoom`。

### 预防措施
- 硬件状态验证日志不要只依赖一个 tag 或 D 级别；关键相机后端选择应同时有 UI 状态或 I 级摘要。

## [2026-07-30] 绿光对比按钮不可见报告

### 发现的问题
- 对比包沿用了主 App 第一行横向滚动工具栏中的 `绿光` 按钮。
- 用户在 `Auto_measure 绿光对比` App 中期望看到明确的 `绿光对比` 按钮，但现有入口名称不明确，且可能被第一行按钮挤到右侧。
- 严重程度：中。功能存在但发现成本高，影响新旧算法对比验证。

### 解决方案
- 通过包名判断当前是否为 `com.example.auto_measure_luoji.lasercompare`。
- 对比包在顶部第二行最左侧固定显示 `绿光对比/对比中` 按钮。
- 对比包隐藏第一行普通 `绿光` 按钮；主 App 保持原入口不变。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleLaserCompare`。
- 真机验证：启动 `Auto_measure 绿光对比` 后，顶部第二行能直接看到 `绿光对比` 按钮。

### 预防措施
- 对调试专用 build type 必须提供明确命名的固定入口，不能只复用主 App 的通用按钮文案。
- 顶部新增高优先级调试入口时，优先放在第二行左侧或固定区，避免被横向滚动隐藏。

## [2026-07-30] 绿光新旧算法对比 App 报告

### 发现的问题
- 绿光算法更新后，如果直接替换 `LaserDetector`，真机只能看到新算法结果，无法稳定复现旧算法坐标作为对照。
- 复制整个项目会带来 Gradle、相机、BLE、模型和文档重复维护风险。
- 如果新旧算法使用不同截图帧，对比结果会混入相机抖动、曝光和激光闪烁误差。

### 解决方案
- 新增 `laserCompare` 构建类型，使用 `applicationIdSuffix=.lasercompare` 和独立应用名，可与主 App 同时安装。
- 在 `LaserDetector` 中保留旧 resize 算法入口，正式校准继续默认使用新算法。
- 顶部 `绿光` 面板改为同一张 `1080x1920` PNG bytes 同时跑新旧算法，并显示坐标差值。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 执行 `.\gradlew.bat :app:assembleLaserCompare`。
- 真机验证：安装 `laserCompare` 包后应用名显示为 `Auto_measure 绿光对比`，点击 `绿光` 开关可保存新旧两组 marked 图。

### 预防措施
- 绿光算法实验优先通过 build type 拆独立包，避免复制项目。
- 新旧算法对比必须使用同一帧输入，禁止用两次截图结果直接比较坐标。

## [2026-07-30] Android laser coordinate drift from Python reference

### Found Issue
- Android `LaserDetector` and Python `1218.py` used similar laser thresholds but produced visibly different coordinates on the same 1080x1920 green laser frame.
- Root cause: Android used point bilinear interpolation for the detector `MAX_DIM` downsample, while Python/Pillow `Image.BILINEAR` uses a widened weighted footprint when downsampling. On saturated green/white laser cores, this changed the bright-core seed and moved the final refined coordinate.
- Severity: medium. Calibration samples could drift by roughly 7 px horizontally and 17 px vertically on the checked frame.

### Solution
- Replaced Android detector resize with Pillow-style separable bilinear weighted resampling.
- Did not change green thresholds, bright-core thresholds, Camera2 zoom, OIS, or EIS behavior.

### Verification
- Ran `.\gradlew.bat :app:compileDebugKotlin`.
- Verified offline with a Python Kotlin-like reproduction that the old Android resize matched `(598,1305)` and Pillow-style resize matches Python `(605,1322)` on `calibration20260729205230783.jpg`.

### Prevention
- Future Android/Python laser comparisons must first confirm identical RGB input SHA-1 and identical detector downsample output before tuning laser thresholds.
- Keep Camera2 EIS disabled; stabilization policy is unrelated to this resize correction.

## [2026-07-29] 广角普通校准 5x 倍率报告

### 发现的问题
- 普通校准开始时沿用用户当前倍率；如果当前镜头是广角镜头，可能以 1x 或其它倍率采集样本。
- `CalibrationManager` 会把采样时的 `currentEquivRatio` 写入标定参数，采样倍率不固定会影响后续 100m 坐标反算一致性。
- 旧锚点居中状态如果未清除，可能在校准采样时叠加 UI 平移或覆盖 scale。

### 解决方案
- 新增普通校准准备逻辑：当前镜头为 `LensCategory.WIDE` 时，开始普通校准前切到 `5x`。
- 如果当前广角镜头可用倍率上限小于 `5x`，使用最大可用倍率并提示，确保传入标定参数的倍率与请求倍率一致。
- 开始普通校准时清除 `anchorActive/currentCenterPx/anchorZoom`，让采样画面只受 Camera2 zoom 控制。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 真机验证：选中广角镜头后进入普通校准，日志出现 `zoom backend=CONTROL_ZOOM_RATIO, ratio=5.0` 或 fallback，校准面板显示 `校准倍率: 5.0x`。

### 预防措施
- 后续调整普通校准入口时，必须保留广角镜头固定校准倍率规则。
- 采样写入 `CalibrationManager.collectSample()` 的倍率必须与实际请求给 Camera2 的倍率保持一致。

## [2026-07-29] 倍率放大改为 Camera2 CONTROL_ZOOM_RATIO 报告

### 发现的问题
- 普通模式倍率滑块只改变 `currentEquivRatio`，随后通过 `FrameLayout` 的 `scaleX/scaleY` 做 UI 放大，没有提交到 Camera2。
- 双镜头模式会调用 `Camera2Controller.setZoomRatio()`，普通模式和双镜头模式的倍率语义不一致。
- 如果同时启用 Camera2 zoom 和 UI scale，可能出现双重放大，导致截图、检测框和校准坐标更难排查。

### 解决方案
- 将 `currentEquivRatio` 变化统一提交给 `Camera2Controller.setZoomRatio()`；双镜头长焦物理镜头仍保持 `1x`。
- `Camera2Controller.setZoomRatio()` 继续在 API 30+ 优先使用 `CONTROL_ZOOM_RATIO`，失败或不支持时回退 `SCALER_CROP_REGION`。
- 无校准点时 wrapper 固定 `scale=1`，倍率放大交给 Camera2；校准点居中只保留平移和必要的边界覆盖 scale。
- 历史 `ZoomController.applyZoom()` 同步改为委托 `setZoomRatio()`；锚点居中的 crop 方法仅保留为旧实验路径。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 真机验证：普通模式拖动倍率滑块时日志出现 `zoom backend=CONTROL_ZOOM_RATIO`；不支持时出现 fallback 到 `SCALER_CROP_REGION`。

### 预防措施
- 后续新增倍率控制必须区分 Camera2 数码 zoom 和 UI 居中变换，避免把倍率放大重新做成纯 UI scale。
- 禁止把 `ZoomController.applyZoom()` 回退成直接 `setCropRegion()` 的普通倍率实现；如确需锚点 crop，必须明确标注实验用途。
- Camera2 防抖策略不随本次修改变化，EIS 必须继续保持关闭。

## [2026-07-29] 蒲公英版本号显示报告

### 发现的问题
- 上传蒲公英后显示版本为 `1.0 (1)`，没有反映当前 `CHANGELOG` 最新版本 `0.8.6`。
- `app/build.gradle.kts` 仍使用默认 `versionName=1.0`、`versionCode=1`，导致测试包难以和功能提交对应。

### 解决方案
- 将 Android `defaultConfig.versionName` 更新为 `0.8.6`。
- 将 Android `defaultConfig.versionCode` 更新为 `806`。

### 验证步骤
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 检查 `app/build/outputs/apk/debug/output-metadata.json` 中版本号。
- 重新上传蒲公英后确认显示 `0.8.6 (806)`。

### 预防措施
- 每次准备上传蒲公英前先检查 `versionName/versionCode` 是否已跟随 `CHANGELOG` 更新。

## [2026-07-29] 顶部按钮挤占 UI 报告

### 发现的问题
- 顶部横向工具条同时承载 `校准/识别/图源/绿光/区域/判定点/统计`，新增按钮后默认视口只能看到前半部分。
- 右侧固定的 `OIS/语音/Log` 会继续压缩左侧工具条宽度，小屏上 `区域/判定点/统计` 更容易被挤到横向滚动区域外。

### 解决方案
- 顶部操作区拆成两行：第一行放高频主操作和固定入口，第二行放工具类按钮。
- 第二行保持横向滚动兜底，但在常见宽度下 `区域/判定点/统计` 能直接显示。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 真机验证：顶部默认可见 `校准/识别/图源/绿光/OIS/语音/Log`，第二行可见 `区域/判定点/统计`。

### 预防措施
- 后续新增顶部按钮优先评估放入第一行主操作还是第二行工具操作，不再把所有按钮塞进同一行。
- 长文案按钮应优先使用短标签，并通过状态 HUD 或弹框展示详细说明。

## [2026-07-29] YOLO 图源分辨率切换报告

### 发现的问题
- 当前 YOLO 检测使用 `TextureView.getBitmap()` 作为图源，但用户无法在真机上快速切换 1080P 与 4K 图源对比识别效果。
- 只看模型日志中的 `Input: WxH -> inputW x inputH`，不够直观地区分当前截图源尺寸和模型输入尺寸。
- 4K 图源会明显提高 Bitmap 内存占用，如果没有明确开关，容易误以为默认检测链路一直在处理 4K。

### 解决方案
- 在顶部工具条新增 `图1080P/图4K` 切换按钮。
- YOLO 检测循环按当前模式调用 `TextureView.getBitmap(width, height)`：1080P 为 `1080x1920`，4K 为 `2160x3840`。
- `YoloDetector.detect()` 继续读取模型 tensor shape 并执行 letterbox；模型为 `1280x1280` 时，图源会转换为 `1280x1280` 后送入模型。
- 检测状态 HUD 和日志输出当前图源尺寸，方便真机排查。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 真机验证：点击 `图1080P/图4K` 后开启“识别”，日志出现 `YOLO图源: 1080P=1080x1920` 或 `YOLO图源: 4K=2160x3840`，并在 `YoloDetector` 日志中确认最终模型输入尺寸。

### 预防措施
- 默认使用 1080P，只有用户显式切换才使用 4K。
- 4K 模式下如出现掉帧、卡顿或 OOM，应优先降低检测频率或切回 1080P。
- 该功能只改变 `TextureView` 截图源尺寸，不启用 EIS，也不改变 Camera2 预览 session。

## [2026-07-24] 绿光 bytes 与 Bitmap 对比识别报告

### 发现的问题
- 绿光识别切到 bytes 输入后，缺少一个真机快速对比入口，无法同时观察 bytes 解码识别和 Bitmap 直传识别的坐标差异。
- 新增 `detectLaserCenter(bytes: ByteArray)` 重载后，调用语义不如参考项目中的 `detectLaserCenterFromBytes(ByteArray)` 清晰，也不方便和哥哥说的 Uint8List bytes 对应。

### 解决方案
- 恢复明确的 `LaserDetector.detectLaserCenterFromBytes(ByteArray)` 入口，Android `ByteArray` 对应 Flutter/Dart 侧 `Uint8List` bytes。
- 顶部工具条新增 `绿光` 按钮，展开后显示 `识别开关`；开关触发一次当前预览帧对比识别，完成后自动关闭。
- 同一帧分别保存 bytes 组和 Bitmap 组 raw/marked 图片，marked 图片写入 `bytes`/`bitmap` 标签，便于相册中比对。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 真机验证：点击 `绿光`，打开 `识别开关` 后生成 `laser_uint8_bytes_*` 和 `laser_bitmap_*` 两组图片，并在面板中显示两条识别结果。

### 预防措施
- 绿光检测的 bytes 入口继续使用 `detectLaserCenterFromBytes(ByteArray)`，避免重载名称造成调用链路不清。
- 对比识别只做单次触发，避免连续保存图片导致相册和存储膨胀。

## [2026-07-24] 绿光识别 bytes 输入改造报告

### 发现的问题
- 普通校准与双镜头校准虽然会生成归一化图片用于保存，但绿光识别仍直接传入 `Bitmap`。
- `CalibrationManager.collectSample()` 内部再次执行图片归一化，导致调用边界不清晰，后续如果需要从文件、网络或 native 层传入图片 bytes 会继续耦合 `Bitmap`。

### 解决方案
- `LaserDetector` 新增 `detectLaserCenterFromBytes(ByteArray)` 入口，接收 PNG/JPEG 等 `BitmapFactory` 支持的完整图片编码 bytes。
- 普通校准与双镜头校准统一复用归一化后的 PNG bytes 进行保存和绿光识别。
- `CalibrationManager.collectSample()` 改为接收归一化图片 bytes，并只负责状态推进和样本记录。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 真机验证：普通校准和双镜头校准采集时仍能保存 raw/marked 图片，并正确识别绿光坐标。

### 预防措施
- 后续绿光识别调用优先传入归一化图片 bytes；仅兼容旧逻辑时使用 `Bitmap` 入口。
- bytes 必须是完整图片编码，不是裸 RGB/YUV 帧；裸帧接入需要单独定义宽高、stride、像素格式和坐标系。

## [2026-07-22] OIS 开关接入报告

### 发现的问题
- Camera2 预览请求原先固定关闭 OIS，顶部按钮区没有给用户临时开启光学防抖的入口。
- 不同镜头的 OIS 支持能力不同，直接写入 ON 可能在不支持的物理镜头上失败。
- 相机/校准流程禁止启用 EIS，新增 OIS 开关时必须确保 EIS 仍保持关闭。
- 首版把 `OIS` 按钮放在横向滚动工具条末尾，小屏或按钮较多时默认视口看不到。

### 解决方案
- `LensScanner` 读取 `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION`，在 `LensCandidate` 中记录 `oisSupported`。
- `Camera2Controller` 新增 `setOisEnabled()`，开启前检查当前镜头是否支持 OIS，失败时回调错误；所有请求仍固定设置 EIS 为 OFF。
- `CameraScreen` 顶部按钮区新增 `OIS/OIS开` 按钮，开启成功后填充青色，开启失败时显示 3 秒提示框。
- 将 `OIS/OIS开` 移到固定顶部区域，位于“语音”和“Log”左侧，不需要横向滑动即可看到。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 真机验证：当前镜头支持 OIS 时按钮开启后填充；不支持或请求失败时提示 3 秒后自动消失。
- 真机验证：进入相机页后顶部固定区域直接显示 `OIS` 按钮。

### 预防措施
- Camera2 相关修改继续禁止启用 EIS。
- OIS 只作为显式开关使用；校准精度验证时需要记录 OIS 开关状态。

## [2026-07-07] 语音识别固定时长与 VAD 循环报告

### 发现的问题
- 当前中文语音热词识别使用 Android 系统 `SpeechRecognizer`，单次识别会话的端点和超时由系统语音服务控制，表现上会像固定识别窗口。
- `关于VAD.md` 描述的是 Speech2Text 参考项目的 `record + Silero VAD + SenseVoice` PCM 流式切句流程，Auto_measure 当前尚未接入 `sherpa-onnx-jni`、VAD 模型和 SenseVoice 模型。
- 若直接把 Sherpa API 代码复制进当前项目，但不引入 native `.so` 和模型文件，会导致编译或运行期加载失败。
- 将 Silero VAD 的 250ms 静音端点参数直接设置给系统 `SpeechRecognizer` 后，部分系统语音服务会在用户还没说话时直接结束会话，导致上一版可识别的热词也识别不到。

### 解决方案
- 恢复上一版可用的系统 `SpeechRecognizer` intent，不再设置 `EXTRA_SEGMENTED_SESSION` 和 250ms 静音端点 extra。
- 保留结果/错误回调后的自动重启，维持按钮开启后的持续监听。
- 新增 30 秒 watchdog，对齐参考 VAD 的 `maxSpeechDuration=30.0s`，防止系统服务长时间不结束会话。
- 建立项目级 `docs/code-library`，登记当前语音热词循环实现和后续真 VAD 接入边界。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin`。
- 执行 `.\gradlew.bat :app:assembleDebug`。
- 真机验证：点击“语音”后不应在未说话时立刻显示“正在解析语音”；说“测距”“扫描”“扫描旗杆”时仍能提示命中类别。

### 预防措施
- 不再把参考项目的 Sherpa VAD 文档视为 Auto_measure 已落地实现；文档需明确当前引擎边界。
- 后续如需完整 Silero VAD，必须先引入 `sherpa-onnx-jni`、模型下载、模型校验和大文件存储清理策略。

## [2026-07-07] 中文语音热词识别接入报告

### 发现的问题
- 需要在现有 Android/Kotlin 项目中复用 Speech2Text 参考文档的热词思路，但不能直接迁移 Flutter/Sherpa-ONNX 代码。
- 首版只要求中文“测距”“扫描”“扫描旗杆”识别和命中类别提示，不应直接触发 BLE 测距或 YOLO 扫描业务动作。
- 离线语音识别依赖系统中文语言包，不同 Android 版本的下载进度能力不同。
- “语音”按钮放在横向滚动工具条里，真机上可能因为滚动区域宽度或位置导致入口不明显。
- 真机下载中文语音包时返回错误码 12，含义是 `ERROR_LANGUAGE_NOT_SUPPORTED`，当前系统语音服务不支持下载该中文离线语言包。

### 解决方案
- 新增 `feature/voice/domain/ChineseVoiceHotwordRecognizer.kt`，封装系统 `SpeechRecognizer`、持续重启监听和中文热词匹配。
- 顶部工具条新增“语音/语音中”按钮，负责录音权限申请、启动和停止持续识别。
- Android 13+ 先检查中文 on-device 语言包；缺失时弹出中文语音包下载对话框，Android 14+ 显示百分比进度，低版本显示系统下载中。
- 命中热词后只显示“语音命中：测距/扫描”，并同步更新状态 HUD，不改变现有测距和扫描流程。
- “语音”按钮移到日志按钮左侧的固定区域，不再跟随工具条横向滚动。
- 下载错误码 12 时不再停留在失败状态，改为提示 `ERROR_LANGUAGE_NOT_SUPPORTED=12` 并自动切换到系统默认中文识别继续监听。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin` 和 `.\gradlew.bat :app:assembleDebug`。
- 真机验证：点击“语音”后持续监听；首次缺少中文语言包时出现下载弹框；说“测距”“扫描”“扫描旗杆”时分别提示命中类别。
- 真机验证：中文语言包下载返回 12 时，弹框提示已切换为系统默认中文识别，按钮不再继续要求下载。

### 预防措施
- 语音热词命中和业务动作解耦，后续真正触发测距/扫描前必须单独确认防误触策略。
- 不把大体积语音模型打进 APK；如改用 Sherpa-ONNX，需要新增模型下载、校验和存储清理机制。
- 录音识别不触碰 Camera2 防抖配置，继续保持 EIS/OIS 关闭。

## [2026-07-02] 双镜头 CONTROL_ZOOM_RATIO 试验报告

### 发现的问题
- 当前双镜头广角数码变焦只使用 `SCALER_CROP_REGION` 中心裁切，用户需要验证 `CONTROL_ZOOM_RATIO` 在目标设备上的预览和坐标表现。

### 解决方案
- `Camera2Controller.setZoomRatio()` 在 Android 11/API 30+ 且倍率落在 `CONTROL_ZOOM_RATIO_RANGE` 内时，优先写入 `CaptureRequest.CONTROL_ZOOM_RATIO`。
- 使用 `CONTROL_ZOOM_RATIO` 时把 `SCALER_CROP_REGION` 置为 full active array，避免和逻辑 zoom 叠加二次裁切。
- 如果设备不支持、倍率超出范围或 repeating request 失败，自动回退中心 `SCALER_CROP_REGION`。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin` 和 `.\gradlew.bat :app:assembleDebug`。
- 真机验证：双镜头广角 5x 采集时日志出现 `backend=CONTROL_ZOOM_RATIO`，并确认绿光坐标与原 crop 方案是否存在偏移。

### 预防措施
- 双摄 zoom 方案切换必须保留 `SCALER_CROP_REGION` fallback，避免 API 30 以下或厂商实现异常导致无法采集。
- 继续保持 EIS/OIS 关闭，避免 zoom 方案变化以外的坐标漂移因素。

## [2026-07-02] 双镜头自动测距输入错误报告

### 发现的问题
- 双镜头 d1/d2 输入框为空时点击“输入 d1/d2”，被改成只提示错误，和预期的自动测距并填写距离不一致。

### 解决方案
- `DualCalibrationPanel` 接入 `BleConnectionManager`。
- 空输入且 BLE 已连接时调用 `measure()`；收到距离大于 0 的 `lastDistance` 后，填写并提交距离，不再要求 `flag=1`。
- 自动测距 4 秒无有效数据会调用 `stopMeasure()` 并提示超时；面板关闭时也会停止测距。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin` 和 `.\gradlew.bat :app:assembleDebug`。
- 真机验证：双镜头 d1/d2 空输入点击后设备开始测距，收到距离后自动切到广角 5x 采集步骤。

### 预防措施
- 区分普通校准和双镜头校准的距离输入语义；双镜头 d1/d2 允许空输入触发测距。
- 所有自动测距流程必须有超时和离开页面时的 `stopMeasure()` 收尾。

## [2026-07-01] 双镜头手动坐标入口错误报告

### 发现的问题
- 双镜头校准只能走 d1/d2 采集流程，已有广角/长焦校准坐标时无法直接录入。
- 长焦选择按钮直接开始校准采集，没有给用户选择“已有坐标”的机会。

### 解决方案
- 长焦扫描结果按钮改为“选择 Nx 长焦”，选择后弹出“是否已有双镜头 100m 坐标”。
- 有坐标时输入广角 X/Y 和长焦 X/Y 四个值，直接写入 `DualTargetPrediction` 并锁定双摄。
- 没有坐标时继续执行原来的双镜头 d1/d2 采集流程。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin` 和 `.\gradlew.bat :app:assembleDebug`。
- 真机验证：选择长焦后能进入坐标/重新校准选择；输入两组坐标后广角与长焦倍率切换使用各自中心点。

### 预防措施
- 新增校准入口时必须保留“已有数据”和“重新采集”两个分支。
- 双摄已有坐标模式不得依赖 `CalibrationParams`，只能使用直接输入的广角/长焦坐标。

## [2026-07-01] 双镜头校准行为错误报告

### 发现的问题
- 进入双镜头校准并锁定长焦后，没有主动发送打开绿光命令，用户需要手动打开绿光后才能采集。
- 校准距离输入为空时点击采集，会触发 BLE 测距命令，容易在误点时让设备开始测距。
- 双镜头应用目标距离后，UI 放大位置较固定，广角倍率变化时没有按 1x 锚点重新计算当前画面中心。

### 解决方案
- `activateDualCalibration()` 和双摄面板显示时，在 BLE 已连接状态下调用 `bleManager.laserOn()`。
- 普通校准距离输入改为必填；双镜头 d1/d2 保留空输入自动测距，非法或非正数只显示错误提示。
- 新增当前中心计算：`current = center + (anchor1x - center) * zoom`；双摄广角低于长焦阈值时按倍率重算中心，长焦阈值使用长焦预测坐标。

### 验证步骤
- 执行 `.\gradlew.bat :app:compileDebugKotlin` 和 `.\gradlew.bat :app:assembleDebug`。
- 真机验证：锁定双摄后绿光打开；空白距离点击输入不发测距；应用目标距离后拖动倍率，校准点始终回到屏幕中心。

### 预防措施
- 校准入口必须明确区分“输入距离”和“设备测距”，空输入不得隐式发送硬件命令。
- 双摄缩放必须同时验证相机真实 zoom、当前中心重算、UI 平移缩放三者的一致性。
- 后续相机/校准修改继续保持 EIS 关闭，避免坐标映射漂移。

## [2026-07-01] UI 错误报告

### 发现的问题
- 顶部按钮数量过多，窄屏下可能挤压、裁切或遮挡日志按钮。
- 模型状态、统计信息和锁定坐标都靠近顶部中心，多个状态同时出现时可能重叠。
- 校准面板、倍率滑块、蓝牙设备控制栏都固定在底部，校准流程中容易叠压。
- 普通校准面板“关闭”只是文本展示，没有触发 `onClose`。
- 双镜头扫描和判定点弹窗内容较多时，存在显示不全风险。

### 解决方案
- 顶部操作区改为横向滚动工具条，日志按钮固定在右侧。
- 新增状态 HUD，集中展示状态、性能、统计和锁定坐标，并限制最大行数。
- 校准面板打开时隐藏滑块和设备控制栏；面板本身可滚动。
- 普通校准面板“关闭”改为 `TextButton`。
- 双镜头扫描和判定点弹窗增加高度限制和滚动。

### 验证步骤
- 已执行 `.\gradlew.bat :app:compileDebugKotlin`，结果通过。
- 仍需真机检查顶部工具条横向滚动、底部面板高度、弹窗滚动和蓝牙面板展开状态。

### 预防措施
- 新增顶部操作按钮时必须放进可滚动工具条，不再直接追加到固定宽度 Row。
- 新增底部控件时需要检查与校准面板、滑块、蓝牙控制栏的互斥关系。
- 弹窗内容超过 3 个可交互项时必须限制高度并支持滚动。

## [2026-07-01] 错误报告

### 发现的问题
- 双镜头校准如果只使用 UI 放大，`TextureView.getBitmap()` 采集帧可能不是真实 5x 广角画面。
- 物理镜头 session 失败后静默回退逻辑镜头，会破坏“锁定广角+长焦”的校准前提。
- 部分厂商可能把后摄长焦标记为非后置，导致扫描候选不完整。

### 解决方案
- 双镜头模式使用 `SCALER_CROP_REGION` 执行真实广角 zoom，并缓存 zoom 到下一次 Camera2 session。
- 双镜头模式打开相机时启用严格物理绑定，物理 session 失败时直接报错。
- 镜头扫描加入疑似后摄长焦兜底，并继续用“等效焦距大于广角”过滤。

### 验证步骤
- 已执行 `.\gradlew.bat :app:compileDebugKotlin`，结果通过。
- 仍需真机验证广角 5x、长焦物理镜头切换、两轮采集和目标距离应用。

### 预防措施
- 后续修改双镜头校准时，禁止启用 EIS；OIS 也应保持关闭以避免像素坐标漂移。
- 双镜头采集必须确认真实相机 zoom 与 UI 显示倍率一致。
- 物理镜头失败不得静默回退，必须让用户知道当前设备无法锁定该镜头。
