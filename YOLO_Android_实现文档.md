# YOLO Android 实现文档

## 项目概述

这是一个基于 Android 的 YOLO 实时目标检测应用，使用 TensorFlow Lite 进行模型推理，支持 CPU 和 GPU 两种后端，通过 CameraX 实现实时摄像头画面分析。

---

## 1. 模型调用流程

### 1.1 模型文件加载

**文件位置**: `AssetUtils.kt`

```kotlin
// 加载 tflite 模型文件到内存映射
fun loadMappedFile(context: Context, filePath: String): MappedByteBuffer {
    val fileDescriptor = context.assets.openFd(filePath)
    val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
    val fileChannel = inputStream.channel
    val startOffset = fileDescriptor.startOffset
    val declaredLength = fileDescriptor.declaredLength
    return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
}
```

**关键点**:
- 模型文件放在 `app/src/main/assets/` 目录下
- 使用 `MappedByteBuffer` 内存映射方式加载，避免将整个模型读入内存
- 支持的模型格式: `.tflite` (float32 和 float16)

### 1.2 模型初始化

**文件位置**: `YoloTfliteDetector.kt` (第137-153行)

```kotlin
init {
    val model = AssetUtils.loadMappedFile(context, modelAssetPath)

    // GPU -> CPU 降级策略
    runner = if (useGpu) {
        tryCreateLocalGpuRunner(model)
            ?: tryCreateGmsGpuRunner(model)
            ?: createCpuRunner(model)
    } else {
        createCpuRunner(model)
    }
}
```

**调用方式**:
```kotlin
detector = YoloTfliteDetector(
    context = applicationContext,
    modelAssetPath = "bestV34_float32.tflite",  // 模型文件名
    labelsAssetPath = "classes.txt",              // 标签文件名
    useGpu = true,                                // 是否使用GPU
    numThreads = 4                                // CPU线程数
)
```

---

## 2. 图片处理流程

### 2.1 摄像头图像获取

**文件位置**: `CameraActivity.kt` (第201-239行)

```kotlin
// CameraX 配置
imageAnalysis = ImageAnalysis.Builder()
    .setTargetAspectRatio(AspectRatio.RATIO_16_9)
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)  // 丢弃旧帧
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)  // RGBA格式
    .build()
```

**关键配置**:
- `STRATEGY_KEEP_ONLY_LATEST`: 保证只处理最新帧，避免队列堆积
- `OUTPUT_IMAGE_FORMAT_RGBA_8888`: 直接输出RGBA格式，转Bitmap更快

### 2.2 ImageProxy 转 Bitmap

**文件位置**: `CameraActivity.kt` (第334-341行)

```kotlin
private fun imageProxyToBitmapRgba(imageProxy: ImageProxy): Bitmap? {
    val plane = imageProxy.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    buffer.rewind()
    val bmp = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
    bmp.copyPixelsFromBuffer(buffer)
    return bmp
}
```

### 2.3 图像旋转处理

**文件位置**: `BitmapUtils.kt`

```kotlin
fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(angle)
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}
```

**使用场景**: 摄像头图像通常需要旋转才能正向显示

---

## 3. 图像预处理 (Letterbox)

### 3.1 Letterbox 原理

**文件位置**: `YoloTfliteDetector.kt` (第349-380行)

Letterbox 是 YOLO 的标准预处理方式，保持图像宽高比的同时缩放到模型输入尺寸。

```kotlin
private fun getOrBuildPlan(srcW: Int, srcH: Int, dstW: Int, dstH: Int): LbPlan {
    // 计算缩放比例 (取最小值保持宽高比)
    val r = min(dstW.toFloat() / srcW.toFloat(), dstH.toFloat() / srcH.toFloat())
    val nw = max(1, (srcW * r).roundToInt())  // 缩放后的宽度
    val nh = max(1, (srcH * r).roundToInt())  // 缩放后的高度

    // 计算填充偏移 (居中放置)
    val padX = (dstW - nw) / 2
    val padY = (dstH - nh) / 2

    // 创建像素映射表 (用于快速采样)
    val xMap = IntArray(nw) { x -> (x * srcW) / nw }
    val yMap = IntArray(nh) { y -> (y * srcH) / nh }
}
```

### 3.2 像素填充 (FLOAT32)

**文件位置**: `YoloTfliteDetector.kt` (第405-466行)

```kotlin
private fun fillInputFloatFast(srcPixels: IntArray, plan: LbPlan, dstW: Int, dstH: Int, outBuffer: ByteBuffer) {
    val inv255 = 1f / 255f
    val padVal = 114f * inv255  // YOLO标准填充值

    for (y in 0 until dstH) {
        val dy = y - padY
        if (dy < 0 || dy >= nh) {
            // 填充区域: 使用灰色 (114/255)
            for (x in 0 until dstW) {
                fa[di++] = padVal; fa[di++] = padVal; fa[di++] = padVal
            }
            continue
        }

        val sy = yMap[dy]
        val rowBase = sy * srcW

        for (x in 0 until dstW) {
            val dx = x - padX
            if (dx < 0 || dx >= nw) {
                fa[di++] = padVal; fa[di++] = padVal; fa[di++] = padVal
                continue
            }

            val sx = xMap[dx]
            val p = srcPixels[rowBase + sx]

            // 提取RGB并归一化到 [0, 1]
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()

            // RGB顺序 (useBgr=false)
            fa[di++] = r * inv255
            fa[di++] = g * inv255
            fa[di++] = b * inv255
        }
    }
}
```

**处理要点**:
- 填充值: 114 (YOLO标准灰色)
- 归一化: 像素值 / 255.0
- 通道顺序: RGB (可通过 `useBgr` 参数切换为 BGR)

---

## 4. 模型推理

### 4.1 输入输出张量

**文件位置**: `YoloTfliteDetector.kt` (第248-305行)

```kotlin
fun detect(bitmap: Bitmap): DetectResult {
    // 1. 获取输入张量信息
    val inputTensor = r.getInputTensor(0)
    val inShape = inputTensor.shape() // [1, H, W, 3]
    val inputH = inShape[1]
    val inputW = inShape[2]

    // 2. 预处理图像
    val plan = getOrBuildPlan(bitmap.width, bitmap.height, inputW, inputH)
    val srcPixels = getSrcPixelsCached(bitmap)
    val inputBuffer = ensureInputBuffer(inType, inputW, inputH)
    fillInputFloatFast(srcPixels, plan, inputW, inputH, inputBuffer)

    // 3. 创建输出缓冲区
    val outTensor = r.getOutputTensor(0)
    val outShape = outTensor.shape()  // [1, N, C] 或 [1, C, N]
    val outputObj = createOutputBuffer(outTensor)

    // 4. 执行推理
    r.run(inputBuffer, outputObj)

    // 5. 解析输出
    val detections = parseAndNms(outputObj, outTensor, outShape, outType, plan.meta, bitmap.width, bitmap.height)
}
```

### 4.2 输出缓冲区创建

**文件位置**: `YoloTfliteDetector.kt` (第670-679行)

```kotlin
private fun createOutputBuffer(outTensor: Tensor): Any {
    val shape = outTensor.shape() // [1,*,*]
    val d1 = shape[1]
    val d2 = shape[2]
    return when (outTensor.dataType()) {
        DataType.FLOAT32 -> Array(1) { Array(d1) { FloatArray(d2) } }
        DataType.UINT8, DataType.INT8 -> Array(1) { Array(d1) { ByteArray(d2) } }
        else -> throw IllegalStateException("Unsupported output type")
    }
}
```

---

## 5. 输出数据处理

### 5.1 输出格式识别

**文件位置**: `OutputReader.kt` (第28-91行)

```kotlin
fun toFloat2DAuto(outputObj: Any, outTensor: Tensor, outShape: IntArray, outType: DataType): Float2D {
    val d1 = outShape[1]
    val d2 = outShape[2]

    // 自动识别布局: [1,N,C] 或 [1,C,N]
    // 经验: N 通常很大(8400/25200)，C 通常很小(5/6/85)
    val isCN = (d1 <= 32 && d2 > 32) // [1,C,N]
    val n = if (isCN) d2 else d1
    val c = if (isCN) d1 else d2

    // 转置处理
    if (isCN) {
        // [1,C,N] -> [N,C]
        for (j in 0 until c) {
            val ch = arr[0][j]
            for (i in 0 until n) out[i][j] = ch[i]
        }
    } else {
        // [1,N,C] 直接复制
        for (i in 0 until n) {
            val row = arr[0][i]
            for (j in 0 until c) out[i][j] = row[j]
        }
    }
}
```

### 5.2 检测结果解析

**文件位置**: `YoloTfliteDetector.kt` (第523-638行)

支持三种输出格式:

#### 格式1: c == 6 (已解码)
```
[x1, y1, x2, y2, score, class_id]
```

#### 格式2: c == 5 (单类别)
```
[cx, cy, w, h, confidence]
```
- 如果坐标值 <= 1.5，认为是归一化坐标，需要乘以输入尺寸

#### 格式3: c > 6 (多类别)
```
[cx, cy, w, h, objectness, class1_prob, class2_prob, ...]
```
- 最终置信度 = objectness × max(class_probs)

**坐标转换**:
```kotlin
// 中心点转角点
val x1 = cx - w / 2f
val y1 = cy - h / 2f
val x2 = cx + w / 2f
val y2 = cy + h / 2f

// Letterbox逆变换: 从模型输入坐标映射回原图坐标
fun mapBoxToOriginal(boxOnInput: RectF, origW: Int, origH: Int): RectF {
    val x1 = ((boxOnInput.left - padX) / scale).coerceIn(0f, origW.toFloat())
    val y1 = ((boxOnInput.top - padY) / scale).coerceIn(0f, origH.toFloat())
    val x2 = ((boxOnInput.right - padX) / scale).coerceIn(0f, origW.toFloat())
    val y2 = ((boxOnInput.bottom - padY) / scale).coerceIn(0f, origH.toFloat())
    return RectF(x1, y1, x2, y2)
}
```

### 5.3 NMS (非极大值抑制)

**文件位置**: `Nms.kt`

```kotlin
fun classAwareNms(dets: List<Detection>, iouThreshold: Float, maxOut: Int): List<Detection> {
    // 按类别分组处理
    val byCls = dets.groupBy { it.classId }
    val out = ArrayList<Detection>()

    for ((_, list) in byCls) {
        // 按置信度降序排序
        val sorted = list.sortedByDescending { it.score }.toMutableList()
        val picked = ArrayList<Detection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            picked.add(best)

            // 移除与best IoU过高的框
            val it = sorted.iterator()
            while (it.hasNext()) {
                val cur = it.next()
                if (iou(best.box, cur.box) >= iouThreshold) it.remove()
            }
        }
        out.addAll(picked)
    }

    return out.sortedByDescending { it.score }.take(maxOut)
}
```

**参数**:
- `iouThreshold = 0.45f`
- `maxDetections = 100`

---

## 6. UI 显示

### 6.1 检测框映射到预览视图

**文件位置**: `CameraActivity.kt` (第343-365行)

```kotlin
private fun mapBoxesToPreview(imageProxy: ImageProxy, dets: List<Detection>): List<Pair<RectF, String>> {
    val viewW = previewView.width.toFloat()
    val viewH = previewView.height.toFloat()

    // 计算源图像尺寸 (考虑旋转)
    val rot = imageProxy.imageInfo.rotationDegrees
    val srcW = if (rot == 90 || rot == 270) imageProxy.height else imageProxy.width
    val srcH = if (rot == 90 || rot == 270) imageProxy.width else imageProxy.height

    // 计算缩放和偏移 (center-crop方式)
    val scale = max(viewW / srcW.toFloat(), viewH / srcH.toFloat())
    val dx = (viewW - srcW * scale) / 2f
    val dy = (viewH - srcH * scale) / 2f

    // 映射每个检测框
    return dets.map { d ->
        val r = RectF(
            d.box.left * scale + dx,
            d.box.top * scale + dy,
            d.box.right * scale + dx,
            d.box.bottom * scale + dy
        )
        r to "${d.label} ${"%.2f".format(d.score)}"
    }
}
```

### 6.2 OverlayView 绘制

**文件位置**: `OverlayView.kt`

```kotlin
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
        color = Color.RED
    }

    private val textPaint = Paint().apply {
        style = Paint.Style.FILL
        textSize = 36f
        isAntiAlias = true
        color = Color.RED
    }

    private var items: List<Pair<RectF, String>> = emptyList()

    fun setBoxes(newItems: List<Pair<RectF, String>>) {
        items = newItems
        invalidate()  // 触发重绘
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for ((r, label) in items) {
            canvas.drawRect(r, boxPaint)  // 绘制边框
            canvas.drawText(label, r.left, (r.top - 8).coerceAtLeast(40f), textPaint)  // 绘制标签
        }
    }
}
```

### 6.3 布局文件

**文件位置**: `activity_camera.xml`

```xml
<!-- 预览视图 -->
<androidx.camera.view.PreviewView
    android:id="@+id/previewView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />

<!-- 覆盖层 (绘制检测框) -->
<com.example.yolov14.OverlayView
    android:id="@+id/overlayView"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:layout_constraintTop_toTopOf="@id/previewView"
    app:layout_constraintBottom_toBottomOf="@id/previewView"
    app:layout_constraintStart_toStartOf="@id/previewView"
    app:layout_constraintEnd_toEndOf="@id/previewView" />
```

**关键点**:
- `OverlayView` 必须与 `PreviewView` 大小完全一致
- 使用 `ConstraintLayout` 约束确保对齐

---

## 7. GPU 调用与兼容性

### 7.1 GPU 后端选择策略

**文件位置**: `YoloTfliteDetector.kt` (第49-53行, 161-229行)

```kotlin
private enum class Backend {
    LOCAL_GPU,   // 旧做法：CompatibilityList + GpuDelegate（APK 内）
    GMS_GPU,     // A 方案：Play services runtime + GpuDelegateFactory
    CPU          // CPU/XNNPACK
}
```

**降级顺序**: `LOCAL_GPU -> GMS_GPU -> CPU`

### 7.2 本地 GPU (LOCAL_GPU)

```kotlin
private fun tryCreateLocalGpuRunner(model: ByteBuffer): Runner? {
    val compatList = CompatibilityList()
    if (!compatList.isDelegateSupportedOnThisDevice) {
        return null  // 设备不支持
    }

    val options = Interpreter.Options()
    val delegateOptions = compatList.bestOptionsForThisDevice
    gpuDelegate = GpuDelegate(delegateOptions)
    options.addDelegate(gpuDelegate)

    return LocalRunner(Interpreter(model, options))
}
```

**依赖**:
```gradle
implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
```

### 7.3 Google Play Services GPU (GMS_GPU)

```kotlin
private fun tryCreateGmsGpuRunner(model: ByteBuffer): Runner? {
    // 检查GMS GPU是否可用
    val available = Tasks.await(
        TfLiteGpu.isGpuDelegateAvailable(context.applicationContext),
        8, TimeUnit.SECONDS
    )
    if (!available) return null

    // 初始化Play services TFLite
    val initOpts = TfLiteInitializationOptions.builder()
        .setEnableGpuDelegateSupport(true)
        .build()
    Tasks.await(
        TfLite.initialize(context.applicationContext, initOpts),
        20, TimeUnit.SECONDS
    )

    // 使用InterpreterApi + GpuDelegateFactory
    val options = InterpreterApi.Options()
        .setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY)
        .addDelegateFactory(GpuDelegateFactory())

    return ApiRunner(InterpreterApi.create(model, options))
}
```

**依赖**:
```gradle
implementation("com.google.android.gms:play-services-tflite-java:16.4.0") {
    exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
}
implementation("com.google.android.gms:play-services-tflite-gpu:16.4.0") {
    exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
}
```

### 7.4 CPU 后端

```kotlin
private fun createCpuRunner(model: ByteBuffer): Runner {
    val options = Interpreter.Options().apply { setNumThreads(numThreads) }
    return LocalRunner(Interpreter(model, options))
}
```

**依赖**:
```gradle
implementation("org.tensorflow:tensorflow-lite:2.16.1")
```

### 7.5 Runner 接口抽象

```kotlin
private interface Runner : Closeable {
    fun getInputTensor(i: Int): Tensor
    fun getOutputTensor(i: Int): Tensor
    fun getOutputTensorCount(): Int
    fun run(input: Any, output: Any)
}

private class LocalRunner(private val itp: Interpreter) : Runner { ... }
private class ApiRunner(private val itp: InterpreterApi) : Runner { ... }
```

---

## 8. 完整数据流

```
摄像头帧 (ImageProxy)
    ↓
RGBA Bitmap
    ↓
旋转校正 (rotateBitmap)
    ↓
获取原图像素 (getPixels)
    ↓
Letterbox 预处理
  - 计算缩放比例和填充
  - 像素采样和归一化
  - 填充到输入缓冲区
    ↓
模型推理 (Interpreter.run)
    ↓
输出解析
  - 自动识别布局 [1,N,C] 或 [1,C,N]
  - 提取边界框、置信度、类别
  - 坐标逆变换 (Letterbox -> 原图)
    ↓
NMS 过滤
    ↓
映射到预览视图坐标
    ↓
OverlayView 绘制
```

---

## 9. 关键配置项

| 配置项 | 值 | 说明 |
|--------|-----|------|
| confThreshold | 0.25f | 置信度阈值 |
| iouThreshold | 0.45f | NMS IoU阈值 |
| maxDetections | 100 | 最大检测数 |
| numThreads | 4 | CPU线程数 |
| padPixel | 114 | Letterbox填充值 |
| inv255 | 1/255 | 归一化系数 |

---

## 10. 依赖版本

```gradle
// TensorFlow Lite
implementation("org.tensorflow:tensorflow-lite:2.16.1")
implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")

// Google Play Services TFLite
implementation("com.google.android.gms:play-services-tflite-java:16.4.0")
implementation("com.google.android.gms:play-services-tflite-gpu:16.4.0")

// CameraX
val cameraxVersion = "1.5.3"
implementation("androidx.camera:camera-core:$cameraxVersion")
implementation("androidx.camera:camera-camera2:$cameraxVersion")
implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
implementation("androidx.camera:camera-view:$cameraxVersion")
```

---

## 11. AndroidManifest 配置

**文件位置**: `AndroidManifest.xml`

```xml
<manifest>
    <!-- 相机权限 -->
    <uses-permission android:name="android.permission.CAMERA" />

    <!-- 声明相机硬件不是必须 (没有相机也能安装) -->
    <uses-feature android:name="android.hardware.camera" android:required="false" />

    <application>
        <!-- 主界面 -->
        <activity android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.AppCompat.Light.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- 摄像头实时检测界面 -->
        <activity android:name=".CameraActivity"
            android:exported="false"
            android:screenOrientation="sensorPortrait" />
    </application>
</manifest>
```

**关键配置**:
- `android.hardware.camera` required=false: 允许在无相机设备上安装
- `CameraActivity` 使用 `sensorPortrait`: 竖屏显示，允许180度旋转

---

## 12. 标签文件格式

**文件位置**: `app/src/main/assets/classes.txt`

```
flag
```

**格式说明**:
- 每行一个类别名称
- 行号（从0开始）对应模型输出的 class_id
- 本项目只有1个类别: "flag"

---

## 13. 两种运行模式

### 13.1 图片检测模式 (MainActivity)

**流程**:
1. 用户通过 Photo Picker 选择图片
2. 点击"开始识别"按钮
3. 在子线程执行检测
4. 显示结果图片和检测信息

```kotlin
// 选择图片
pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

// 执行检测
btnRun.setOnClickListener {
    runDetection(selectedBitmap)
}
```

### 13.2 实时摄像头模式 (CameraActivity)

**流程**:
1. 从 MainActivity 跳转，传递 GPU/模型/标签参数
2. CameraX 持续获取帧
3. 每帧独立检测，使用 AtomicBoolean 防止堆积
4. 实时绘制检测框

```kotlin
// 跳转到摄像头界面
val intent = Intent(this, CameraActivity::class.java).apply {
    putExtra(CameraActivity.EXTRA_USE_GPU, useGpu)
    putExtra(CameraActivity.EXTRA_MODEL, "bestV29_float32.tflite")
    putExtra(CameraActivity.EXTRA_LABELS, "classes.txt")
}
startActivity(intent)
```

---

## 14. 线程模型

```
┌─────────────────────────────────────────────────────────────┐
│  Main Thread (UI)                                           │
│  - 更新 TextView / ImageView                                │
│  - 调用 overlayView.setBoxes()                              │
│  - 调用 overlayView.invalidate()                            │
└─────────────────────────────────────────────────────────────┘
        ↑ runOnUiThread
        │
┌─────────────────────────────────────────────────────────────┐
│  Camera Executor (SingleThreadExecutor)                     │
│  - ImageAnalysis.Analyzer 回调                              │
│  - imageProxyToBitmapRgba()                                 │
│  - rotateBitmap()                                           │
│  - det.detect() (模型推理)                                  │
│  - mapBoxesToPreview()                                      │
└─────────────────────────────────────────────────────────────┘
        │
        ↓
┌─────────────────────────────────────────────────────────────┐
│  TFLite Executor (SingleThreadExecutor)                     │
│  - 模型初始化                                               │
│  - detector.close()                                         │
└─────────────────────────────────────────────────────────────┘
```

**关键点**:
- GPU delegate 必须在同一线程初始化和运行
- 使用 `AtomicBoolean isProcessing` 防止帧堆积
- `STRATEGY_KEEP_ONLY_LATEST` 丢弃未处理的旧帧

---

## 15. 数字变焦功能

**文件位置**: `CameraActivity.kt` (第242-295行)

```kotlin
private val TARGET_MAX_ZOOM = 14f  // 目标最大倍率

private fun setupZoomUi() {
    // SeekBar: 0..1300 -> 1.0..14.0
    seekZoom.max = 1300

    seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            desiredZoom = 1f + (progress / 1300f) * (TARGET_MAX_ZOOM - 1f)

            if (desiredZoom <= hwMaxZoom) {
                // 在硬件支持范围内: 直接设置相机变焦
                cam.cameraControl.setZoomRatio(desiredZoom)
                applyDigitalScale(1f)
            } else {
                // 超出硬件范围: 相机拉到最大 + UI数字放大
                cam.cameraControl.setZoomRatio(hwMaxZoom)
                val extra = desiredZoom / hwMaxZoom
                applyDigitalScale(extra)
            }
        }
    })
}

private fun applyDigitalScale(scale: Float) {
    // 同时放大 PreviewView 和 OverlayView
    previewView.scaleX = scale
    previewView.scaleY = scale
    overlayView.scaleX = scale
    overlayView.scaleY = scale
}
```

**实现要点**:
- 硬件变焦 + 数字变焦组合，实现最高14倍放大
- PreviewView 和 OverlayView 必须同步缩放

---

## 16. 30秒统计功能

**文件位置**: `CameraActivity.kt` (第158-198行)

```kotlin
private fun startStat7s() {
    statTotal.set(0)
    statHit.set(0)
    statEndMs = SystemClock.elapsedRealtime() + 30000L  // 30秒
    statMeasuring.set(true)

    // 定时更新UI
    val r = object : Runnable {
        override fun run() {
            val leftMs = statEndMs - SystemClock.elapsedRealtime()
            if (leftMs <= 0L) {
                finishStat7s()
                return
            }
            tvStat7s.text = "统计中: ${(leftMs / 1000f)}s  命中=$h/$t"
            uiHandler.postDelayed(this, 200L)
        }
    }
}

private fun finishStat7s() {
    val rate = if (t <= 0) 0f else (h.toFloat() / t.toFloat()) * 100f
    tvStat7s.text = "成功率: ${"%.1f".format(rate)}%  命中=$h/$t"
}
```

**统计逻辑**:
- 每次 `detect()` 调用算一次 total
- 如果检测到目标 (detections.isNotEmpty()) 算一次 hit
- 成功率 = hit / total × 100%

---

## 17. 资源释放

**文件位置**: `CameraActivity.kt` (第367-376行)

```kotlin
override fun onDestroy() {
    super.onDestroy()
    cameraProvider?.unbindAll()           // 释放相机
    cameraExecutor.shutdown()             // 关闭相机线程池
    tfliteExecutor.execute { detector?.close() }  // 释放模型
    tfliteExecutor.shutdown()             // 关闭TFLite线程池
    statTicker?.let { uiHandler.removeCallbacks(it) }  // 移除定时器
}
```

**YoloTfliteDetector.close()**:
```kotlin
override fun close() {
    runner?.close()           // 释放Interpreter
    gpuDelegate?.close()      // 释放GPU delegate
    cachedInputBuffer = null  // 释放缓冲区
    cachedSrcPixels = null
    cachedFloatInput = null
    cachedPlan = null
}
```

---

## 18. 完整文件清单

| 文件 | 职责 |
|------|------|
| `YoloTfliteDetector.kt` | 核心检测器：模型加载、预处理、推理、后处理 |
| `CameraActivity.kt` | 摄像头实时检测界面 |
| `MainActivity.kt` | 主界面：图片选择、GPU切换、跳转摄像头 |
| `OutputReader.kt` | 模型输出解析，自动识别布局 |
| `OverlayView.kt` | 自定义View，绘制检测框和标签 |
| `Nms.kt` | 非极大值抑制算法 |
| `Letterbox.kt` | Letterbox预处理（备用，实际使用内联版本） |
| `AssetUtils.kt` | 资源文件加载工具 |
| `BitmapUtils.kt` | Bitmap旋转工具 |
| `StageTimer.kt` | 性能计时工具 |
| `activity_main.xml` | 主界面布局 |
| `activity_camera.xml` | 摄像头界面布局 |
| `AndroidManifest.xml` | 应用配置 |
| `classes.txt` | 类别标签文件 |
| `bestV29_float32.tflite` | YOLO模型文件 |

---

## 19. 实现要点总结

1. **模型加载**: 使用 `MappedByteBuffer` 内存映射，避免大文件OOM
2. **GPU兼容**: 三级降级策略 (LOCAL_GPU -> GMS_GPU -> CPU)
3. **图像预处理**: Letterbox保持宽高比，像素归一化到 [0,1]
4. **输出解析**: 自动识别 [1,N,C] 和 [1,C,N] 两种布局
5. **坐标映射**: Letterbox逆变换 + 预览视图映射
6. **性能优化**: 像素缓存、缓冲区复用、单线程执行器
7. **UI显示**: OverlayView覆盖在PreviewView上，实时绘制检测框
8. **线程安全**: AtomicBoolean防帧堆积，GPU操作单线程
9. **变焦**: 硬件变焦+数字变焦组合，最高14倍
10. **统计**: 30秒成功率统计功能
