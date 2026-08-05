# 双镜头校准完整说明

本文档说明当前项目中的双镜头校准功能，包括用户流程、核心代码、标定模型、Camera2 物理镜头绑定、OIS 处理、历史问题点和验证清单。

## 目标

双镜头校准用于在同一目标距离下分别采集广角镜头和长焦镜头的激光点位置，建立两套独立标定参数：

- 广角标定：使用广角物理镜头，在约 5x 数码变焦下采集两次。
- 长焦标定：使用选中的物理长焦镜头，在长焦原生 1x 下采集两次。界面上按相对广角的有效倍率显示，例如 10x。
- 目标距离应用：输入目标距离 D 后，分别预测广角和长焦下的激光中心，并默认切回广角 1x 显示预测中心。

这个功能的核心难点是：Android 厂商通常把多个物理摄像头挂在一个逻辑多摄设备下，CameraX 不一定能直接稳定选择某个物理镜头。因此双摄校准模式会在预览层切换到 Camera2，并通过 `OutputConfiguration.setPhysicalCameraId()` 强制绑定物理镜头。

## 相关文件

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/com/example/laser_native_kotlin_app/MainActivity.kt` | 双摄入口、流程状态机、采集、缩放、OIS、UI 刷新 |
| `app/src/main/java/com/example/laser_native_kotlin_app/camera/DualCameraManager.kt` | 扫描后置镜头、识别广角/长焦、记录当前使用镜头 |
| `app/src/main/java/com/example/laser_native_kotlin_app/camera/Camera2PhysicalBinder.kt` | Camera2 物理镜头绑定、预览 session、OIS OFF、zoom 缓存 |
| `app/src/main/java/com/example/laser_native_kotlin_app/model/CameraInfo.kt` | 镜头信息模型、35mm 等效焦距计算 |
| `app/src/main/java/com/example/laser_native_kotlin_app/model/Sample.kt` | 单次采样数据：距离、激光点、采集倍率 |
| `app/src/main/java/com/example/laser_native_kotlin_app/model/CalibrationParams.kt` | 标定参数：`ax/bx/ay/by/calibrationZoom` |
| `app/src/main/java/com/example/laser_native_kotlin_app/model/PredictionCalculator.kt` | 两点标定参数计算和目标距离坐标预测 |
| `app/src/main/java/com/example/laser_native_kotlin_app/detector/LaserDetector.kt` | 激光点识别 |
| `app/src/main/java/com/example/laser_native_kotlin_app/util/ImageUtils.kt` | 图片归一化、裁剪、缩放、标记 |
| `app/src/main/java/com/example/laser_native_kotlin_app/util/GallerySaver.kt` | 导出 raw/marked PNG |

## 用户操作流程

1. 点击“双摄校准”按钮。
2. 应用扫描后置镜头，弹出“双摄像头扫描结果”。
3. 如果只有一个长焦，点击“选择 Nx 长焦”；如果有多个长焦，先选择一个长焦倍率。
4. 选择是否已有双镜头 100m 坐标：
   - 有：输入广角 100m 坐标和长焦 100m 坐标，直接锁定双摄并应用中心点。
   - 没有：进入原双镜头校准采集流程。
5. 进入双摄模式后，应用强制关闭 OIS，并默认绑定广角物理镜头。
   - 如果 BLE 已连接，会立刻发送打开绿光命令。
6. 点击“双摄采集：输入 d1”，输入第一次距离 d1；如果输入框为空且 BLE 已连接，会自动测距并提交 d1。
7. 应用自动切到广角 5x，点击“采集广角(5x)”。
8. 广角采集成功后，应用自动切到长焦物理镜头，点击“采集长焦(10x)”。
9. 第一次广角+长焦采集完成后，输入 d2；同样支持空输入自动测距并提交。
10. 应用再次切到广角 5x，重复广角和长焦采集。
11. 两轮采集完成后，应用计算广角标定参数和长焦标定参数。
12. 输入目标距离 D。
13. 应用计算目标距离下的预测坐标，默认切回广角 1x 显示预测中心。

说明：部分 UI 文案写的是“长焦10x”，实际切换阈值由 `selectedTelephoto.opticalMagnification` 决定，代码里通过 `telephotoMag` 动态读取。

## 状态变量

双摄相关状态集中在 `MainActivity.kt`：

```kotlin
private var dualCameraManager: DualCameraManager? = null
private var physicalBinder: Camera2PhysicalBinder? = null
private var isDualCalibMode = false
private val telephotoMag: Int
    get() = dualCameraManager?.selectedTelephoto?.opticalMagnification ?: 10

private val wideSamples = mutableListOf<Sample>()
private val teleSamples = mutableListOf<Sample>()
private var wideCalibration: CalibrationParams? = null
private var teleCalibration: CalibrationParams? = null
private var dualCalibStep = 0
```

`dualCalibStep` 当前实际使用的状态：

| 值 | 含义 |
| --- | --- |
| `0` | 空闲，等待输入 d1 或 d2 |
| `1` | 已输入距离，等待采集广角 5x |
| `2` | 广角已采集，等待采集长焦 |

代码注释里保留了 `3/4` 的描述，但当前流程实际通过 `wideSamples.size` 区分 d1/d2，不再使用 `3/4`。

## 镜头扫描和选择

入口是 `MainActivity.enterDualCalibMode()`：

```kotlin
private fun enterDualCalibMode() {
    val mgr = DualCameraManager()
    mgr.onLog = { appendLog(it) }
    val cameras = mgr.scanCameras(this)
    dualCameraManager = mgr

    val teleMags = mgr.getAvailableTelephotoMagnifications()
    ...
}
```

`DualCameraManager.scanCameras()` 会：

1. 遍历 `CameraManager.cameraIdList`。
2. 过滤后置摄像头；对部分厂商异常标记的长焦，使用“非后置但焦距大于 4mm”作为疑似后摄长焦候选。
3. 如果逻辑摄像头包含 `physicalCameraIds`，逐个读取物理镜头的 `CameraCharacteristics`。
4. 计算每个镜头的物理焦距、传感器尺寸、35mm 等效焦距、zoom range。
5. 用 35mm 等效焦距分类：
   - `< 20mm`：超广角
   - `20mm..38mm`：广角/主摄
   - `>= 45mm`：长焦
6. 以广角等效焦距为基准，计算每个镜头的光学倍率：

```kotlin
cam.opticalMagnification = (camEq35mm / baseEq35mm).roundToInt()
```

镜头信息由 `CameraInfo` 表示。35mm 等效焦距计算公式：

```kotlin
eq35mm = focalLength * 43.266 / sensorDiagonalMm
```

## 进入双摄模式

选择长焦后调用 `activateDualCameraMode()`：

```kotlin
private fun activateDualCameraMode() {
    val mgr = dualCameraManager ?: return
    if (!mgr.isDualCameraReady()) {
        toast("未找到广角+长焦组合")
        return
    }

    isDualCalibMode = true
    forceOisOffForDualCalibration()

    bindWideAngleCamera()
    refreshUi()
}
```

进入双摄后会立刻：

- 设置 `isDualCalibMode = true`。
- 暂存并关闭 OIS。
- 解绑 CameraX。
- 使用 Camera2 绑定广角物理镜头。
- 更新 UI：倍率显示为 `x倍率(W/T)`，双摄按钮变为锁定状态，zoom 滑块范围变为 `1x..telephotoMag`。

## Camera2 物理镜头绑定

双摄模式不再依赖 CameraX 的 `ImageCapture` 或普通 CameraX zoom 绑定，而是通过 `Camera2PhysicalBinder` 绑定物理镜头。

广角绑定：

```kotlin
private fun bindWideAngleCamera() {
    val mgr = dualCameraManager ?: return
    val wide = mgr.wideAngleCamera ?: return

    cameraProvider?.unbindAll()
    val logicalId = findLogicalCameraIdForPhysical(wide.cameraId) ?: return

    physicalBinder?.close()
    val binder = Camera2PhysicalBinder(this)
    physicalBinder = binder

    bindWhenSurfaceReady {
        val surface = getPreviewSurface() ?: return@bindWhenSurfaceReady
        binder.bindPhysicalCamera(
            logicalCameraId = logicalId,
            physicalCameraId = wide.cameraId,
            surface = surface,
            onConfigured = { ... },
            onFailed = { ... },
        )
    }
}
```

长焦绑定：

```kotlin
private fun bindTelephotoCamera() {
    val mgr = dualCameraManager ?: return
    val tele = mgr.selectedTelephoto ?: return

    cameraProvider?.unbindAll()
    val logicalId = findLogicalCameraIdForPhysical(tele.cameraId) ?: return

    physicalBinder?.close()
    val binder = Camera2PhysicalBinder(this)
    physicalBinder = binder
    mgr.isUsingWideAngleSetter(false)

    bindWhenSurfaceReady {
        val surface = getPreviewSurface() ?: return@bindWhenSurfaceReady
        binder.bindPhysicalCamera(
            logicalCameraId = logicalId,
            physicalCameraId = tele.cameraId,
            surface = surface,
            onConfigured = { ... },
            onFailed = { ... },
        )
    }
}
```

这里有几个关键点：

- `cameraProvider?.unbindAll()`：CameraX 和 Camera2 不能同时控制同一个相机资源。
- `physicalBinder?.close()`：切换物理镜头前先关闭旧 session，减少 Camera2 配置失败。
- `bindWhenSurfaceReady()`：CameraX unbind 后 `PreviewView` 底层 `SurfaceView` 可能被销毁，需要等 Surface 重建。
- `if (physicalBinder !== binder) return@runOnUiThread`：过滤过期异步回调，避免旧 session 的失败 Toast 影响新 session。

`Camera2PhysicalBinder.bindPhysicalCamera()` 的核心是：

```kotlin
val outputConfig = OutputConfiguration(surface)
outputConfig.setPhysicalCameraId(physicalCameraId)

val sessionConfig = SessionConfiguration(
    SessionConfiguration.SESSION_REGULAR,
    listOf(outputConfig),
    context.mainExecutor,
    object : CameraCaptureSession.StateCallback() { ... }
)

device.createCaptureSession(sessionConfig)
```

预览 request 默认参数：

```kotlin
builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, requestedZoomRatio)
builder.set(
    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
)
```

## OIS 处理

双摄校准时必须关闭 OIS，因为 OIS 会让光轴和像素坐标发生微小动态变化，影响两次采样的一致性。

进入双摄：

```kotlin
private fun forceOisOffForDualCalibration() {
    if (oisEnabledBeforeDualCalib == null) {
        oisEnabledBeforeDualCalib = oisEnabled
    }
    oisEnabled = false
    refreshOisControlState()
}
```

退出或重置：

```kotlin
private fun restoreOisAfterDualCalibration() {
    val previous = oisEnabledBeforeDualCalib ?: return
    oisEnabledBeforeDualCalib = null
    oisEnabled = previous && oisSupported
    refreshOisControlState()
}
```

在 CameraX 绑定里也会根据 `isOisActiveForCurrentMode()` 写入 OIS request。双摄模式下该函数始终返回 `false`：

```kotlin
private fun isOisActiveForCurrentMode(): Boolean {
    return oisEnabled && !isDualCalibMode
}
```

Camera2 物理预览也在 `Camera2PhysicalBinder.applyCalibrationPreviewDefaults()` 中固定写入 `LENS_OPTICAL_STABILIZATION_MODE_OFF`。

## 采集流程

输入距离入口是 `onDualCalibInputDistance()`。

第一次进入会清空旧样本和旧标定：

```kotlin
wideSamples.clear()
teleSamples.clear()
wideCalibration = null
teleCalibration = null
calibrationParams = null
anchorCenter1x = null
anchorActive = false
```

输入 d1 或 d2 后，应用切到广角 5x：

```kotlin
pendingDistance = d
setCameraZoom(5.0, keepAnchor = false)
dualCalibStep = 1
```

采集入口是 `onDualCalibDetect()`。根据 `dualCalibStep` 分支：

```kotlin
when (dualCalibStep) {
    1 -> {
        val sample = Sample(distanceMeters = distance, point = point, zoom = camZoom)
        wideSamples += sample
        setCameraZoom(telephotoMag.toDouble(), keepAnchor = false)
        dualCalibStep = 2
    }

    2 -> {
        val sample = Sample(distanceMeters = distance, point = point, zoom = 1.0)
        teleSamples += sample
        ...
    }
}
```

广角样本记录采集时的 `camZoom`，通常接近 5x。长焦样本记录 `zoom = 1.0`，因为此时是物理长焦镜头的原生画面，显示上等效为广角的 `telephotoMag`。

## 图像采集和导出

普通单摄模式使用 CameraX `ImageCapture`。双摄模式下 CameraX 已经被 `unbindAll()`，继续调用 `ImageCapture.takePicture()` 会报 `Not bound to a valid Camera`，所以双摄采集从当前预览 Surface 截图。

入口：

```kotlin
private suspend fun detectAndExportTwoImages(prefix: String): LaserResult? {
    val normalized = captureNormalizedBitmap(tempFile)
    GallerySaver.savePng(this, rawBytes, "${prefix}_raw_$ts.png")
    val result = LaserDetector.detectLaserCenter(normalized, roiRatio)
    GallerySaver.savePng(this, markedBytes, "${prefix}_marked_$ts.png")
    return result
}
```

双摄模式使用 PixelCopy：

```kotlin
private suspend fun captureNormalizedBitmap(tempFile: File): Bitmap {
    if (isDualCalibMode && physicalBinder?.isOpen() == true) {
        return capturePreviewBitmapNormalized()
    }

    takePictureToFile(tempFile)
    return ImageUtils.loadAndNormalize(tempFile)
}
```

```kotlin
PixelCopy.request(
    surfaceView,
    snapshot,
    { result ->
        if (result == PixelCopy.SUCCESS) {
            cont.resume(Unit)
        } else {
            cont.resumeWithException(IllegalStateException("PixelCopy 失败: $result"))
        }
    },
    Handler(Looper.getMainLooper())
)
```

PixelCopy 得到的预览图会走相同的归一化流程：

```kotlin
ImageUtils.resize(
    ImageUtils.cropToAspect(snapshot, ImageUtils.TARGET_ASPECT)
)
```

因此双摄和单摄最终都在统一的输出坐标系里计算，当前模型默认输出尺寸是 `1080x1920`。

## 标定模型

标定模型在 `PredictionCalculator` 中。

对任意一个镜头，采两组样本：

- 距离 `d1`，激光点 `p1 = (x1, y1)`
- 距离 `d2`，激光点 `p2 = (x2, y2)`

以输出图像中心为原点：

```kotlin
cx = OUT_W / 2
cy = OUT_H / 2
dx1 = x1 - cx
dx2 = x2 - cx
dy1 = y1 - cy
dy2 = y2 - cy
```

假设偏移和距离满足：

```text
dx(d) = ax / d + bx
dy(d) = ay / d + by
```

由两点解出：

```kotlin
ax = (dx1 - dx2) * d1 * d2 / (d2 - d1)
bx = dx1 - ax / d1
ay = (dy1 - dy2) * d1 * d2 / (d2 - d1)
by = dy1 - ay / d1
```

保存为：

```kotlin
data class CalibrationParams(
    val ax: Double,
    val bx: Double,
    val ay: Double,
    val by: Double,
    val calibrationZoom: Double,
)
```

预测目标距离 D：

```kotlin
dxCal = params.ax / targetDistance + params.bx
dyCal = params.ay / targetDistance + params.by

x = cx + dxCal / params.calibrationZoom
y = cy + dyCal / params.calibrationZoom
```

广角标定：

```kotlin
val wideZoom = (ws1.zoom + ws2.zoom) / 2
wideCalibration = PredictionCalculator.buildCalibration(..., currentZoom = wideZoom)
```

长焦标定：

```kotlin
teleCalibration = PredictionCalculator.buildCalibration(..., currentZoom = 1.0)
```

为什么长焦是 `1.0`：因为 Camera2 已经绑定到物理长焦镜头，当前画面是该物理镜头原生 1x。它相对于广角可能是 10x，但在长焦自己的坐标模型里不再除以 10。

## 应用目标距离

双摄模式下 `applyTargetDistance(distance)` 会分别预测广角和长焦：

```kotlin
val widePredicted = PredictionCalculator.predict1xCoordinateAtDistance(wideCalibration!!, distance)

if (teleCalibration != null) {
    val telePredicted = PredictionCalculator.predict1xCoordinateAtDistance(teleCalibration!!, distance)
}
```

默认使用广角 1x 作为 anchor：

```kotlin
anchorCenter1x = widePredicted
anchorActive = true
setCameraZoom(1.0, keepAnchor = false)
currentCenterPx = anchorCenter1x
applyTransform()
```

当用户在广角上拖动 zoom 时，`updateCenterFromAnchorForZoom()` 会根据 1x anchor 重新计算当前倍率下的中心：

```kotlin
currentCenterPx = LaserPoint(
    x = cx + dx1 * zoom,
    y = cy + dy1 * zoom,
)
```

当前中心重算后，UI 变换再把 1080x1920 坐标映射到实际预览容器，并使用：

```kotlin
translationX = (screenCenterX - focusX) * scale
translationY = (screenCenterY - focusY) * scale
```

这样低于长焦阈值时，广角会随倍率重新居中；达到长焦阈值后，使用长焦自己的预测坐标居中，不把广角 1x 坐标套到长焦画面。

如果用户选择“已有双镜头坐标”，不会构建 `CalibrationParams`，而是直接把两组 100m 坐标保存到 `DualTargetPrediction`：

```kotlin
dualTargetPrediction = DualTargetPrediction(
    widePoint1x = widePoint,
    telePoint = telePoint,
)
```

此时广角倍率仍按 `widePoint1x` 重新计算当前中心；切到长焦阈值后直接使用 `telePoint`。

当用户切到长焦倍率时，如果已经有 `teleCalibration`，则使用长焦预测坐标：

```kotlin
if (teleCalibration != null) {
    val telePredicted = PredictionCalculator.predict1xCoordinateAtDistance(teleCalibration!!, targetDistance)
    currentCenterPx = telePredicted
    anchorActive = false
    applyTransform()
}
```

## 双摄缩放和镜头切换

双摄模式下 `setCameraZoom(value)` 里的 `value` 表示“相对于广角 1x 的有效倍率”。

规则：

- `1.0 <= value < telephotoMag`：使用广角物理镜头；Android 11/API 30+ 且设备 range 支持时优先通过 `CONTROL_ZOOM_RATIO` 做数码变焦，否则回退 `SCALER_CROP_REGION` 中心裁切。
- `value >= telephotoMag`：切换到选中的物理长焦镜头，长焦本身使用原生 `1.0`。

核心代码：

```kotlin
val effMag = value.coerceIn(1.0, telephotoMag.toDouble())

if (effMag >= telephotoMag) {
    if (mgr.isUsingWideAngle) {
        effectiveMagnification = telephotoMag.toDouble()
        camZoom = 1.0
        bindTelephotoCamera()
    }
    return
} else {
    camZoom = effMag
    effectiveMagnification = effMag
    if (!mgr.isUsingWideAngle) {
        bindWideAngleCamera()
    }
    physicalBinder?.setZoom(effMag.toFloat())
    if (keepAnchor) updateCenterFromAnchorForZoom(effectiveMagnification)
    refreshUi()
}
```

`Camera2PhysicalBinder.setZoom()` 会先缓存倍率：

```kotlin
requestedZoomRatio = ratio.coerceAtLeast(1f)
```

当前项目中的 `Camera2Controller.setZoomRatio()` 会优先尝试 `CONTROL_ZOOM_RATIO`，并在失败时自动回退 `SCALER_CROP_REGION`。即使 session 还没创建成功，后续 `startPreview()` 也会把缓存的 zoom 写入 repeating request，避免“第二次校准切回 5x 时画面仍是 1x，必须手动点倍率才生效”的问题。

## UI 表现

双摄模式下 UI 变化：

- 倍率显示为 `x5.0(W)` 或 `x10.0(T)`。
- `W` 表示当前绑定广角，`T` 表示当前绑定长焦。
- zoom 滑块范围变为 `1x..telephotoMag`，每 `0.1x` 一个步进。
- 距离输入为空且 BLE 已连接时，点击“输入 d1/d2”会发送测距命令，收到距离大于 0 的数据后自动提交，不要求 `flag=1`。
- 距离输入非法或小于等于 0 时只显示错误提示，不会发送测距命令。
- 输入按钮文案：
  - `双摄采集：输入d1`
  - `双摄采集：输入d2`
  - `双摄采集：采集广角5x`
  - `双摄采集：采集长焦10x`
- 采集按钮只有在 `dualCalibStep == 1 || dualCalibStep == 2` 且不 busy 时可点击。
- OIS 开关禁用，并显示 `OIS 光学防抖（双摄校准已关闭）`。

## 生命周期和重置

Activity 进入后台时关闭 Camera2 物理绑定：

```kotlin
override fun onPause() {
    if (isDualCalibMode) {
        physicalBinder?.close()
    }
    super.onPause()
}
```

回到前台时按当前镜头状态恢复绑定：

```kotlin
override fun onResume() {
    super.onResume()
    if (isDualCalibMode && physicalBinder?.isOpen() != true) {
        if (mgr.isUsingWideAngle) bindWideAngleCamera() else bindTelephotoCamera()
    }
}
```

重置时清理双摄状态：

```kotlin
isDualCalibMode = false
dualCameraManager = null
physicalBinder?.close()
physicalBinder = null
restoreOisAfterDualCalibration()
wideSamples.clear()
teleSamples.clear()
wideCalibration = null
teleCalibration = null
dualCalibStep = 0
bindCameraUseCases()
```

## 已知问题和处理方式

### 1. 双摄校准时 OIS 开启导致坐标不稳定

问题：OIS 会动态移动光学组件，激光点像素坐标会被防抖补偿影响。两次采样之间即使目标距离稳定，坐标也可能发生额外漂移。

处理：

- 进入双摄后暂存原 OIS 状态并强制关闭。
- 双摄期间 UI 禁用 OIS 开关。
- CameraX 和 Camera2 request 都写入 `LENS_OPTICAL_STABILIZATION_MODE_OFF`。
- 退出双摄或重置后恢复原 OIS 状态。

### 2. 5x 采集闪退：`Not bound to a valid Camera`

问题：双摄模式下已经调用 `cameraProvider?.unbindAll()` 并切到 Camera2 物理预览，但采集流程仍然调用 CameraX `ImageCapture.takePicture()`。此时 `ImageCapture` 不再绑定有效 Camera，点击采集会崩溃。

处理：

- 双摄模式下不使用 CameraX `ImageCapture`。
- 改为 `PixelCopy` 从当前 `SurfaceView` 预览取帧。
- 统一走 `ImageUtils.cropToAspect()` 和 `ImageUtils.resize()`，保持检测坐标系一致。
- 采集异常被捕获并写入日志，避免直接闪退。

### 3. 第二次校准切到 5x 后仍显示 1x

问题：物理镜头切换是异步的。切回广角并立刻设置 5x 时，Camera2 session 可能还没 ready；旧代码在 session 未就绪时直接丢掉 zoom 请求，导致画面仍是 1x，需要用户再点一次倍率才生效。

处理：

- `Camera2PhysicalBinder` 增加 `requestedZoomRatio`。
- `setZoom()` 先缓存目标 zoom，再尝试写入当前 session。
- `startPreview()` 创建 repeating request 时写入缓存 zoom。
- `MainActivity.setCameraZoom()` 在双摄分支中不再依赖 CameraX 的 `camera` 对象，避免 CameraX 已解绑后提前 return。

### 4. 两次采集后点击应用提示“配置失败”

问题：目标距离应用后，`applyTargetDistance()` 内部已经会切回广角 1x，外层又重复调用了一次 `setCameraZoom(1.0)`。这会连续创建两个 Camera2 session，前一个旧 session 被后一个顶掉后触发 `Session 配置失败`，虽然后一个 session 随后成功，但用户会看到错误 Toast。

处理：

- 删除外层重复的 `setCameraZoom(1.0)`。
- 绑定回调中增加 `physicalBinder !== binder` 校验，旧 binder 的异步回调不再更新 UI 或弹 Toast。

### 5. d1 和 d2 相同导致标定异常

问题：两点标定公式分母是 `d2 - d1`。如果两次距离相同，会触发非法参数，无法计算 `ax/bx/ay/by`。

处理：

- 输入 d2 时检查不能与 d1 相同。
- `computeDualCalibration()` 对 `PredictionCalculator.buildCalibration()` 做 `try/catch`，失败时提示用户检查 d1/d2。

### 6. CameraX unbind 后 Surface 未就绪

问题：`unbindAll()` 可能销毁 `PreviewView` 底层 Surface。Camera2 立刻绑定时如果 Surface 无效，会导致 session 创建失败。

处理：

- `bindWhenSurfaceReady()` 先检查 Surface 是否有效。
- 如果无效，注册 `SurfaceHolder.Callback`，等 `surfaceCreated()` 后再执行绑定。

### 7. Camera2 session 竞争

问题：快速从广角切长焦或从长焦切广角时，旧 session 的关闭和新 session 的创建可能交错。

处理：

- 每次绑定前都调用 `physicalBinder?.close()`。
- 每次绑定创建新的 `Camera2PhysicalBinder` 实例。
- onConfigured/onFailed 回调都检查 binder 身份，过期回调静默退出。

## 限制和注意事项

- 物理镜头绑定依赖 Android 9/API 28 的逻辑多摄能力：`physicalCameraIds` 和 `OutputConfiguration.setPhysicalCameraId()`。
- `CONTROL_ZOOM_RATIO` 写入依赖 Android 11/API 30；当前代码在低版本、倍率超出 range 或 request 失败时会自动回退 `SCALER_CROP_REGION`。
- `CONTROL_ZOOM_RATIO` 可能受厂商逻辑 zoom、融合和畸变校正影响，必须真机对比同一距离下广角 5x 的绿光坐标偏移。
- 厂商可能隐藏物理镜头 ID，或把长焦标记为异常 facing。当前代码对部分异常做了焦距兜底，但不能保证所有机型都能锁定物理长焦。
- PixelCopy 采集的是预览帧，不是 CameraX full-res 拍照帧。它适合当前“校准预览坐标”的目标，但如果未来需要高分辨率离线校准，需要重新设计采集路径。
- UI 文案里的“长焦10x”应改为动态 `${telephotoMag}x`，否则选择 5x、3x 等长焦时文案会误导。
- 当前双摄 `dualCalibStep` 注释仍提到 `3/4`，实际流程只用 `0/1/2`，建议后续清理注释。

## 手动验证清单

每次改双摄相关代码后，建议按下面顺序验证：

1. 点击“双摄校准”，确认扫描结果能列出广角和长焦。
2. 锁定长焦后，确认 OIS 开关变为禁用，文字显示“双摄校准已关闭”。
3. 输入 d1，确认画面自动切到广角 5x，倍率显示为 `W`。
4. 点击采集广角，确认导出 `dual_*_raw_*.png` 和 `dual_*_marked_*.png`，且应用不闪退。
5. 广角采集后，确认自动切到长焦，倍率显示为 `T`。
6. 点击采集长焦，确认第一次采集完成后提示输入 d2。
7. 输入 d2，确认自动切回广角 5x，不需要手动再点倍率。
8. 重复广角和长焦采集。
9. 输入目标距离 D，确认没有出现“配置失败”Toast，画面回到广角 1x。
10. 拖动 zoom 滑块，确认低于长焦阈值时在广角上缩放，到达阈值时切到长焦。
11. 按 Home 再回到应用，确认当前双摄镜头能恢复预览。
12. 点击重置，确认退出双摄、CameraX 默认预览恢复、OIS 状态恢复。

## 构建验证

代码改动后至少运行：

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
```

当前已知单元测试阻塞点：`app/src/test/.../ExampleUnitTest.kt` 里存在历史遗留问题，可能导致 `:app:testDebugUnitTest` 失败。该问题与双摄校准功能无直接关系。

## 后续建议

- 把 UI 文案中的固定“10x”改为动态 `${telephotoMag}x`。
- 清理 `dualCalibStep` 注释，使其与当前 `0/1/2` 状态一致。
- 在进入双摄时增加 API 版本检查，低于 API 28 时直接提示“不支持物理镜头绑定”。
- 继续记录不同机型上 `CONTROL_ZOOM_RATIO` 和 `SCALER_CROP_REGION` 的广角 5x 坐标差异，再决定最终默认方案。
- 为 `DualCameraManager.identifyCameras()` 增加更多机型日志样本，持续修正厂商镜头分类异常。
