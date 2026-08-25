# Auto_Measure_luoji 复用规则

## 强制规则

- 修改相机、校准、检测、语音等核心流程前，先查阅 `docs/code-library/INDEX.md`。
- 优先增量修改已有类和函数，避免重新设计同类流程。
- Android Camera2 EIS 默认必须保持 OFF；只有用户明确要求或在防抖弹窗中显式选择 ON/PREVIEW 时才允许写入 `CONTROL_VIDEO_STABILIZATION_MODE`，并需实机验证坐标漂移。
- 大体积模型、缓存、构建产物、密钥和真实用户隐私数据不得登记到代码资产库。
- 绿光识别校准链路优先传入归一化图片 bytes：预览帧先缩放到 `1080x1920`，再编码为 PNG/JPEG 等完整图片格式，禁止把无宽高/stride 元数据的裸帧直接传入 bytes 入口。
- 绿光新旧算法对比识别只做调试验证入口，默认单次触发并保存两组图片，禁止改成无节流连续保存；对比必须使用同一帧输入。
- 绿光算法实验需要单独安装包时，优先新增 Android build type 并设置 `applicationIdSuffix`，禁止复制整套项目。
- `laserCompare` 对比包必须固定显示明确的 `绿光对比` 按钮，不能只复用主 App 的普通 `绿光` 文案。
- YOLO TextureView 图源切换只改变 `getBitmap(width, height)` 的截图尺寸；模型输入尺寸必须继续以 TFLite input tensor 为准，不得硬编码覆盖模型 shape。
- 相机页顶部按钮新增入口时，优先保留高频主操作和硬件状态入口固定可见，低频工具放入 `更多` 折叠菜单；不要再增加顶部行数。
- `广角/广角中` 必须和 `更多/OIS/语音/Log` 一起固定在横向滚动区外，不能再放入主操作 `horizontalScroll`。
- `更多` 是低频工具的关键入口，必须固定可见，禁止放进 `horizontalScroll` 横向滚动按钮组内。
- `laserCompare` 对比包必须保留固定可见的 `绿光对比` 按钮，不能只藏在 `更多` 菜单里。
- 镜头切换按钮必须在普通/双镜头校准流程中禁用，切换镜头时需要清理旧锚点和检测框。
- 倍率放大优先使用 Camera2 `CONTROL_ZOOM_RATIO`；UI wrapper 只用于校准点居中和平移覆盖，不得重新承担普通倍率放大。
- 普通预览必须打开父逻辑相机 ID，并保持纯逻辑相机 Session；禁止通过 `bindPhysicalPreview` 或 `OutputConfiguration.setPhysicalCameraId()` 绑定预览 Surface。只有双摄校准 `strictPhysical=true` 时才允许使用物理输出绑定，并且物理绑定失败应视为致命错误。
- 普通模式倍率滑块最小值必须是最大焦距候选相对同一逻辑相机广角的光学基准倍率 + `0.1x`，禁止固定回 `1x` 或原始光学基准倍率。
- 普通模式倍率滑块最大值固定为 `24x`；不得按 `maxDigitalZoom` 或 `CONTROL_ZOOM_RATIO_RANGE` 改写该上限。
- Camera2 zoom 后端选择必须通过 `zoom: backend=...` 摘要暴露到 `Camera2Ctrl`、`CameraScreen` 和主界面状态区，方便真机验证。
- 当前画面物理焦距必须优先读取 `CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` 并映射到 `LensCandidate`；禁止只用 `selectedLens` 推断逻辑相机实际画面来源。
- `ZoomController.applyZoom()` 只能委托 `Camera2Controller.setZoomRatio()`；锚点 crop 方法属于历史实验路径，不能作为普通倍率默认实现。
- 普通校准开始时，若当前镜头为 `LensCategory.WIDE`，必须先将 Camera2 倍率请求到 `5x` 再采集；请求超出设备 `CONTROL_ZOOM_RATIO_RANGE` 时允许中心 crop 回退。
- 普通校准在长焦/非广角镜头下保持当前逻辑相机倍率采集，不得低于普通模式光学基准倍率 + `0.1x`。
- 普通校准距离输入为空且 BLE 已连接时，应触发自动测距；收到 `line > 0` 后回填距离并采集，绿光识别失败不得更新 `lastCollectedDistance`，必须允许同一距离重试。
- BLE 测距数据对 UI 和校准流程是事件语义，`lastDistance` 必须使用同值也通知的 Compose state 策略，避免连续两次相同距离值被结构相等过滤。
- 普通校准和双镜头校准采集期间必须通过 Camera2 `CONTROL_AE_EXPOSURE_COMPENSATION` 设置 `-2.0 EV`；退出校准面板后恢复 `0 EV`，且必须按设备 `CONTROL_AE_COMPENSATION_STEP/RANGE` 换算和裁剪。

## 语音识别规则

- 当前中文热词入口只提示命中类别，不直接触发 BLE 测距或 YOLO 扫描。
- 第三方语音模型就绪时必须优先使用 Sherpa-ONNX SenseVoice + Silero VAD 本地识别；只有模型未就绪或第三方启动失败时才允许回退系统 `SpeechRecognizer`。
- 第三方语音模型下载必须复用 `ThirdPartyVoiceModelDownloader`，保留 `.part` 文件和 HTTP `Range` 断点续传；下载后必须解压并校验 `silero_vad.onnx`、`model.int8.onnx`、`tokens.txt`。
- 系统 `SpeechRecognizer` 的分段、静音和离线语言包行为受设备语音服务影响；不得把系统语言包下载当成第三方模型下载入口。
- 热词匹配必须先做空白和标点清理，再判断“扫描旗杆/扫描/测距”。
