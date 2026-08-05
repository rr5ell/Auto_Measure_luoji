# Auto_measure 开发问题排查文档

记录开发过程中遇到的所有问题、解决方案和遗留限制。按功能模块分类。

---

## 一、相机预览与镜头控制

### 1.1 TextureView 变换导致黑屏（反复出现）
**问题**：对 TextureView 调用 `setTransform(Matrix)`、`rotation`、`scaleX/scaleY` 会导致预览黑屏。
**尝试过**：
- `TextureView.setTransform(matrix)` → 黑屏
- `TextureView.rotation = 90f` → 黑屏
- `TextureView.scaleX/scaleY` → 黑屏
**原因**：TextureView 的 SurfaceTexture 绑定到 GPU 纹理，任何 View 层级的变换都会破坏纹理绑定。
**最终方案**：TextureView 完全不变换，外层 FrameLayout 容器承担所有变换（scaleX/scaleY/translationX/translationY）。
**逻辑**：TextureView 只负责显示原始画面，FrameLayout 作为普通 ViewGroup 可以自由变换。

### 1.2 镜头选择策略变更
**初版**：选择第二大 TELE 镜头（eq35mm 第二大）。
**问题**：用户反馈不合理，应该选最大长焦。
**修改**：改为 `candidates.maxByOrNull { it.eq35mm }`，锁定最大长焦。
**教训**：需求确认要在编码前完成，避免返工。

### 1.3 变焦方案从 Crop Region 改为 UI Scale
**初版**：使用 `SCALER_CROP_REGION` 做数码变焦。
**问题**：
- Crop region 有 sensor 边界限制，超出后被 clamp
- 锚点居中时 crop 被 clamp 导致黑边
- 需要复杂的 UI 平移补偿逻辑
**最终方案**：删除 `ZoomController`，改为纯 UI 放大（`wrapper.scaleX/scaleY`）。
**逻辑**：Crop region 是传感器级裁切，受硬件限制。UI scale 是纯视觉缩放，无边界限制。

### 1.4 锚点居中算法
**问题**：以预测坐标为中心放大预览，需要精确的平移计算。
**算法**：
```
pivotX = screenCX（屏幕中心）
pivotY = screenCY
translationX = (screenCX - anchor.x) * scale
translationY = (screenCY - anchor.y) * scale
```
**关键点**：pivot 必须设为屏幕中心，translation 将锚点移向中心。

### 1.5 TextureView.getBitmap() 性能问题
**问题**：`TextureView.getBitmap()` 比 CameraX 的 `ImageProxy` 慢约 30ms。
**原因**：`getBitmap()` 需要 GPU → CPU 回读（readback），CameraX 的 ImageProxy 直接从内存读取。
**尝试优化**：用 ImageReader 替代（见 1.6），失败。
**当前状态**：无法在不改架构的前提下优化。检测间隔 300ms，总检测时间 ~70ms，仍可实时。

### 1.6 ImageReader 方案失败（两次尝试）
**第一次尝试**：ImageReader + YUV_420_888 格式。
**问题**：YUV 转 RGB 复杂，且方向处理困难。
**第二次尝试**：ImageReader + JPEG 格式。
**问题**：
1. 预览卡顿 — ImageReader 加入相机输出管线后抢帧
2. 检测框位置错乱 — JPEG 方向是相机原生方向（横屏），TextureView 内部已处理旋转（竖屏）
**教训**：不要轻易修改相机输出管线，TextureView 的内部旋转处理是隐式的。

---

## 二、YOLO 目标检测

### 2.1 检测结果始终为 0（det=0）— 多轮调试
**现象**：模型推理正常（GPU 后端，~65ms），但 det=0。
**调试过程**：
1. 添加原始输出 dump → 发现所有 confidence 为 0.0000
2. 添加 maxConf 统计 → 发现最大置信度只有 0.004
3. 降低阈值到 0.1 → maxConf 到 0.197，仍然 det=0
4. 继续观察 → 偶尔 maxConf 到 0.197，刚好超过 0.1 阈值，det=1
**根因**：模型对目标的响应较弱（训练数据或场景差异）。
**最终方案**：阈值从 0.25 降到 0.1。

### 2.2 检测图片方向错误 — CameraX vs Camera2 差异
**现象**：同一个模型，yolov14（CameraX）能检测，Auto_measure（Camera2）检测不到。
**调试过程**：
1. 添加 `Input: WxH` 日志 → 发现输入是 1920x1080（横屏）
2. 添加 `sensorOrient=90` 日志 → 发现旋转了 90°
3. 分析：TextureView.getBitmap() 返回 1080x1920（竖屏），旋转 90° 后变 1920x1080（横屏）
4. 对比 yolov14：CameraX ImageProxy 是 1920x1080（横屏），旋转 90° 后变 1080x1920（竖屏）
**根因**：CameraX 和 Camera2 的帧方向处理机制不同。
- CameraX：原始帧横屏 → 旋转 → 竖屏
- Camera2 + TextureView：getBitmap() 已经是竖屏 → 不需要旋转
**最终方案**：去掉旋转，直接用 getBitmap() 原始方向。

### 2.3 Bitmap 缩小优化导致崩溃
**问题**：尝试缩小 bitmap 加速预处理，`ArrayIndexOutOfBoundsException: length=518400; index=518400`。
**原因**：缩小 1080x1920 → 540x960，像素数组 518400。但 Letterbox 的 xMap/yMap 仍按原尺寸索引，导致越界。
**教训**：预处理管线（Letterbox）已经处理了缩放，不要在输入端额外缩放。

### 2.4 GPU 后端优先级调整
**初版**：LOCAL_GPU → GMS_GPU → CPU
**调整后**：GMS_GPU → LOCAL_GPU → CPU
**原因**：GMS_GPU（Play services）使用系统级 TFLite 运行时，可能有更好的硬件优化和兼容性。

### 2.5 协程频繁取消重建（LeftCompositionCancellationException）
**问题**：点击"识别"后，协程不断被取消重建，检测无法持续。
**日志**：`LeftCompositionCancellationException: The coroutine scope left the composition`
**原因**：`LaunchedEffect(detectionEnabled, isCameraReady)` 中 `isCameraReady` 作为 key，相机回调频繁改变它（ON_PAUSE/ON_RESUME），每次变化都重启协程。
**调试过程**：
1. 初版：`LaunchedEffect(detectionEnabled, isCameraReady)` → 协程频繁重启
2. 尝试：在循环内检查 isCameraReady，但 key 仍有它 → 仍然重启
3. 最终：去掉 isCameraReady 作为 key，只保留 detectionEnabled
**最终方案**：
```kotlin
LaunchedEffect(detectionEnabled) {  // 只用 detectionEnabled 作为 key
    while (detectionEnabled) {
        if (!isCameraReady) { delay(300); continue }  // 内部检查
        // ... 检测逻辑
    }
}
```

---

## 三、BLE 蓝牙通信

### 3.1 BLE 状态不更新 UI
**问题**：蓝牙连接成功后，UI 没有显示已连接状态，也没有测距/绿光按钮。
**原因**：`state` 是普通 `var`，Compose 无法观察其变化。
**解决方案**：改用 `mutableStateOf` 包装：
```kotlin
var state: State by mutableStateOf(State.IDLE)
var connectedDevice: BleDevice? by mutableStateOf(null)
```

### 3.2 setState 命名冲突
**问题**：`setState()` 方法名与 `mutableStateOf` 生成的 setter 冲突，编译报错。
**解决方案**：重命名为 `updateState()`。

### 3.3 UUID 未导入
**问题**：`Unresolved reference: UUID`
**解决方案**：添加 `import java.util.UUID`。

### 3.4 BLE 连续数据覆盖手动输入
**问题**：BLE 设备每 300ms 发送一次距离数据，覆盖了用户手动输入的距离值。
**解决方案**：添加 `distanceInput.isBlank()` 守卫，只在输入为空时才接受 BLE 数据。

### 3.5 第二次采集不自动测距
**问题**：第二次点击采集时，不会自动触发 BLE 测距。
**原因**：第一次采集后 `distanceInput` 没有被清空，BLE 回调判断 `distanceInput.isBlank()` 为 false。
**调试过程**：
1. 用户报告"第二次点击采集不会测距"
2. 添加日志发现 `distanceInput` 仍有旧值
3. 分析：第一次 BLE 自动采集后，距离填入了 `distanceInput`，但没有清空
**解决方案**：采集成功后清空 `distanceInput`，采集失败时保留（允许重试）。

### 3.6 重复距离导致 PredictionCalculator 崩溃
**问题**：两次采集的距离相同（都是 4.2m），`require(firstDistance != secondDistance)` 抛异常。
**日志**：`采集异常: Failed requirement`
**解决方案**：添加 `lastCollectedDistance` 检查，重复距离时按钮显示"换位置"，禁止采集。

---

## 四、校准流程

### 4.1 检测失败卡在第一次采集
**问题**：绿光检测失败后，`distanceInput` 被清空，用户需要重新测距。
**原因**：`onCollect` 回调在检测失败时也清空了输入。
**解决方案**：改为 `(distance, onResult: (Boolean) -> Unit)` 回调模式：
- 检测成功：`onResult(true)` → 清空输入，进入下一步
- 检测失败：`onResult(false)` → 保留输入，允许重试

### 4.2 校准面板与 BLE 联动
**需求**：
1. 打开校准面板 → 自动打开绿光
2. 点击采集 → 距离必须显式输入，空白或非正数只提示错误
3. 应用后不关闭绿光（可在主界面关闭）
**实现**：
```kotlin
LaunchedEffect(Unit) {
    if (isBleConnected) bleManager.laserOn()
}
```
**注意**：旧版本曾允许“空白距离自动测距”，但该行为容易在误点时向设备发送 `AT+Measure`。当前版本已取消隐式测距，设备测距只保留在主界面设备控制栏和识别触发流程中。
双镜头校准的 d1/d2 输入是例外：该按钮的语义就是输入当前校准距离，空输入且 BLE 已连接时会自动测距并提交。

### 4.3 双镜头采集仅 UI 放大导致坐标偏差
**问题**：双镜头校准需要采集广角 5x 画面，如果只放大外层 `FrameLayout`，`TextureView.getBitmap()` 可能仍取到未裁切的底层预览帧。
**影响范围**：广角 5x 样本会被当成 5x 参数参与反投影，导致 100m 的 1x 坐标被错误除以倍率。
**解决方案**：
- 双镜头模式下使用 `Camera2Controller.setZoomRatio()` 写入真实 `SCALER_CROP_REGION`。
- `Camera2Controller` 增加 `pendingZoomRatio`，切换物理镜头后新 session 会继承目标 zoom。
- 双镜头模式下禁用外层 UI 倍率缩放，避免 crop zoom 与 UI scale 叠加。

### 4.4 物理长焦 session 回退为逻辑镜头
**问题**：原有 Camera2 物理 session 创建失败时会 fallback 到逻辑 session；双镜头校准要求锁定广角和长焦，静默回退会破坏坐标一致性。
**影响范围**：采集到的长焦样本可能来自逻辑镜头或厂商自动切换镜头，而不是用户选择的物理长焦。
**解决方案**：
- `Camera2Controller.openCamera()` 增加 `strictPhysical` 参数。
- 双镜头模式传入 `strictPhysical = true`，物理 session 失败时直接报告错误，不再静默回退。
- 严格物理模式只绑定预览 Surface，避免 ImageReader 逻辑输出干扰物理 session。

### 4.5 厂商异常镜头标记导致扫描不到长焦
**问题**：部分设备会把后摄长焦标记为非后置 facing，单纯过滤 `LENS_FACING_BACK` 会漏掉候选。
**影响范围**：双镜头校准入口提示未找到长焦。
**解决方案**：
- `LensScanner` 保留“非后置但物理焦距大于 4mm”的疑似后摄长焦候选。
- 双镜头选择时仍要求候选等效焦距大于广角，降低误选普通前摄的概率。

### 4.6 双镜头校准进入后绿光未打开
**问题**：锁定广角和长焦后只打开校准面板，没有向 BLE 设备发送绿光打开命令。
**影响范围**：用户进入双镜头校准后可能直接采集，导致绿光检测失败。
**解决方案**：
- `activateDualCalibration()` 在 BLE 已连接时立即调用 `bleManager.laserOn()`。
- 增加双摄面板显示状态监听，面板重新打开且 BLE 已连接时再次发送绿光打开命令。

### 4.7 双镜头应用后放大中心固定
**问题**：目标距离应用后，广角倍率变化时 UI 没有按参考项目逻辑从 1x 锚点重新计算当前中心，表现为放大位置固定或不跟随倍率。
**解决方案**：
- 保存 `anchorCenter1x` 和 `currentCenterPx`。
- 广角倍率小于长焦阈值时，使用 `current = center + (anchor1x - center) * zoom` 重算当前中心。
- 达到长焦阈值时使用长焦预测坐标，不再把广角 1x 坐标套到长焦画面。
- UI 变换先把 1080x1920 坐标映射到实际预览容器，再按 `translation = (center - focus) * scale` 平移到屏幕中心。

### 4.8 双镜头已有坐标无法直接录入
**问题**：双镜头校准入口选择长焦后会直接开始 d1/d2 采集，没有让用户复用已有广角/长焦 100m 坐标。
**影响范围**：已有校准坐标的用户必须重复采集，且无法跳过现场绿光采集流程。
**解决方案**：
- 长焦扫描结果只负责选择长焦，不直接开始采集。
- 选择长焦后弹出“已有双镜头坐标/没有，开始校准”。
- 已有坐标时录入广角 X/Y 和长焦 X/Y，直接生成 `DualTargetPrediction`。
- 没有坐标时仍走原 `DualCalibrationManager` 的 d1/d2 双镜头采集逻辑。

### 4.9 双镜头空距离输入未触发测距
**问题**：双镜头 d1/d2 输入框为空时点击输入按钮，只显示错误提示，没有触发设备测距并提交距离。
**原因**：之前把“空输入不触发硬件命令”的普通校准约束错误套到了双镜头 d1/d2 输入流程。
**解决方案**：
- `DualCalibrationPanel` 增加 `BleConnectionManager` 参数。
- 空输入且 BLE 已连接时调用 `bleManager.measure()`。
- 通过 `bleManager.lastDistance` 监听新距离数据，收到距离大于 0 后调用 `onInputDistance(data.line)`，不再要求 `flag=1`。
- 自动测距超时或面板销毁时调用 `bleManager.stopMeasure()`。

### 4.10 双镜头 CONTROL_ZOOM_RATIO 试验
**问题**：`SCALER_CROP_REGION` 是中心裁切，用户需要验证 `CONTROL_ZOOM_RATIO` 是否在目标设备上更接近系统倍率表现。
**风险**：`CONTROL_ZOOM_RATIO` 依赖 Android 11/API 30，并且厂商可能在逻辑 zoom 中引入融合、畸变校正或自动镜头策略，影响绿光像素坐标一致性。
**解决方案**：
- `Camera2Controller.setZoomRatio()` 在 API 30+ 且倍率位于 `CONTROL_ZOOM_RATIO_RANGE` 内时优先写入 `CONTROL_ZOOM_RATIO`。
- 使用 `CONTROL_ZOOM_RATIO` 时将 `SCALER_CROP_REGION` 置为完整 active array，避免二次裁切。
- 如果设备不支持、倍率超出 range 或 request 失败，自动回退中心 `SCALER_CROP_REGION`。
- 真机测试时对比同一距离下广角 5x 的绿光坐标偏移，再决定是否保留该方案。

---

## 五、UI 组件

### 5.0 顶部/底部控件遮挡
**问题**：主界面同时存在顶部操作按钮、蓝牙面板、状态文本、底部滑块、设备控制栏和校准面板。窄屏或多状态同时显示时，容易出现按钮裁切、状态文字重叠和底部控件叠压。
**原因**：
- 顶部按钮直接放在固定 Row 中，按钮数量增长后没有滚动或换行策略。
- 模型状态、统计结果和锁定坐标使用多个独立 `TopCenter` 文本。
- 校准面板、滑块和设备控制栏都固定在 BottomCenter。
**解决方案**：
- 顶部操作区改为横向滚动工具条，日志按钮固定在右侧。
- 状态信息合并为左上角 HUD，并限制行数和省略溢出文本。
- 校准面板打开时隐藏滑块和设备控制栏，避免底部叠压。
- 校准面板和内容较多的弹窗增加高度限制与滚动。

### 5.1 IntrinsicSize.Max + LazyColumn 崩溃
**问题**：`java.lang.IllegalStateException: Asking for intrinsic measurements of SubcomposeLayout layouts is not supported`
**原因**：`Modifier.width(IntrinsicSize.Max)` 与 `LazyColumn` 不兼容。
**解决方案**：改用固定宽度 `Modifier.width(180.dp)`。

### 5.2 clip 扩展函数错误
**问题**：自定义的 `Modifier.clip(RoundedCornerShape)` 扩展不生效，编译报错。
**解决方案**：使用标准 `import androidx.compose.ui.draw.clip`。

### 5.3 UI 重写
**原因**：原生 Material3 组件样式太丑，不符合暗色主题需求。
**重写范围**：
- BluetoothPanel（胶囊按钮 + 状态指示灯）
- DeviceControls（80x80 圆角按钮）
- CalibrationPanel（暗色主题 + AccentGreen/AccentBlue）
- 整体布局调整

---

## 六、语音识别

### 6.1 中文语言包缺失
**问题**：系统 `SpeechRecognizer` 的离线中文识别依赖设备语音服务和中文 on-device 语言包，首次使用可能无法直接识别。
**解决方案**：
- Android 13+ 调用 `checkRecognitionSupport()` 检查中文语言包状态。
- 缺失时弹出“中文语音包”对话框，由用户触发 `triggerModelDownload()`。
- Android 14+ 使用 `ModelDownloadListener` 展示百分比进度；较低版本显示系统下载中。
- 如果下载返回错误码 12（`ERROR_LANGUAGE_NOT_SUPPORTED`），说明当前系统语音服务不支持下载该中文离线语言包，自动降级为系统默认中文识别继续监听。

### 6.2 持续识别中断
**问题**：`SpeechRecognizer` 每次识别会话会在结果、超时或 no-match 后结束，不是天然无限流。
**解决方案**：
- `ChineseVoiceHotwordRecognizer` 在 `onResults()`、`ERROR_NO_MATCH`、`ERROR_SPEECH_TIMEOUT` 等状态后延迟重启监听。
- 命中结果做 1.8 秒防抖，避免 partial 和 final result 重复弹出同一类别。

### 6.3 热词误触发
**问题**：语音热词如果直接触发测距或扫描，短词误识别会导致硬件动作误触发。
**解决方案**：
- 当前版本只提示命中类别，不执行真实测距或扫描动作。
- 首版只匹配“测距”“扫描”“扫描旗杆”三个中文目标词，后续扩展必须同步更新 `HOTWORDS.md` 并真机验证。

---

## 七、Git 分支管理

### 7.1 分支策略
```
master ← 稳定主干
├── develop ← 开发集成分支
│   └── feature/xxx ← 功能分支
```

### 7.2 曾犯的错误
1. **直接在 master 上开发** → 应该在 feature 分支
2. **忘记合并到 develop** → master 和 develop 不同步
3. **忘记更新文档和 CHANGELOG** → 文档滞后于代码

### 7.3 提交规范
```
feat:     新功能
fix:      修复 Bug
refactor: 重构
docs:     文档更新
perf:     性能优化
revert:   回退
debug:    调试日志
```

---

## 七、无法解决的限制

| 限制 | 原因 | 尝试过的方案 | 当前状态 |
|------|------|-------------|----------|
| TextureView.getBitmap() 慢 ~30ms | GPU → CPU 回读是固有开销 | ImageReader（失败，预览卡顿+坐标错乱） | 接受，300ms 间隔可实时 |
| 模型检测置信度较低（max ~0.2） | 模型训练数据或场景差异 | 降低阈值到 0.1 | 可检测，但偶尔误检 |
| ImageReader 加入管线后预览卡顿 | 相机输出管线被分流 | 换用 JPEG/YUV 格式（都失败） | 放弃 ImageReader 方案 |
| 不同设备 GPU 后端兼容性 | 硬件差异 | 三级降级策略 | GMS_GPU → LOCAL_GPU → CPU |

---

## 八、问题解决流程

```
问题发生
    ↓
查看 logcat 日志（过滤对应 TAG）
    ↓
定位问题类型
    ├── 编译错误 → 检查导入、命名冲突、类型不匹配
    ├── 运行时崩溃 → 检查空指针、数组越界、状态不一致
    ├── 功能异常 → 检查数据流、回调逻辑、状态管理
    └── 性能问题 → 检查主线程阻塞、重复计算、缓存策略
    ↓
参考本文档查找类似问题
    ↓
修复并验证
    ↓
更新本文档
```

---

## 九、关键 TAG 列表

| TAG | 模块 | 用途 |
|-----|------|------|
| CameraScreen | 相机预览 | 帧处理、检测循环、坐标映射 |
| Camera2Ctrl | 相机控制 | 预览尺寸、相机打开/关闭 |
| CalMgr | 校准管理 | 样本采集、标定参数 |
| CalPanel | 校准面板 | 距离输入、BLE 自动采集 |
| BleConn | BLE 连接 | 扫描、连接、AT 命令 |
| YoloDetector | YOLO 检测 | 模型加载、推理、输出解析 |
