# android-laser-detector-bytes-input

## Metadata
- status: active
- tech: Android | Kotlin
- tags: image-processing, laser-detection, bytes-input, coordinate-map, calibration
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\calibration\detector\LaserDetector.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\calibration\domain\CalibrationManager.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\calibration\util\ImageUtils.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-07-31
- author: Codex Agent

## 2026-07-31 Update
- 普通校准距离输入为空且 BLE 已连接时复用 BLE 自动测距流程；收到 `line > 0` 后回填距离并用当前归一化 PNG bytes 进入采集。
- 普通校准的重复距离保护只能在 `CalibrationManager.collectSample()` 返回非空绿光结果后更新；绿光识别失败时保留距离并提示重试。

## 2026-07-30 Update
- Android detector `MAX_DIM` downsample must stay aligned with Python `1218.py` / Pillow `Image.BILINEAR`; avoid replacing it with Android point bilinear interpolation unless the Python reference is updated at the same time.
- Current/new laser detection must not use bright-core refinement as the final center for large bloomed green components. Keep `BRIGHTCORE_REFINE_AREA_MAX = 150` so big regions use the connected component center, while small spots still use bright-core refinement.
- Legacy comparison entry points intentionally keep the old bright-core refinement path, even for large green components, so `laserCompare` can compare old behavior against the current detector.

## 适用场景
- 普通校准或双镜头校准需要把绿光识别输入从 `Bitmap` 边界切换为图片 bytes。
- 后续需要从文件、网络、native 层或测试样例传入完整图片编码 bytes，并复用现有绿光质心检测算法。

## 禁止场景
- 禁止把裸 RGB/YUV/NV21 帧直接传入 `detectLaserCenterFromBytes(bytes)`，除非同时新增明确的宽高、stride、像素格式和坐标系解析入口。
- Camera2 相关调用仍禁止启用 EIS；如需稳定，只允许显式 OIS 或算法层物理补偿，并必须实机验证坐标漂移。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `snapshotBytes` | `ByteArray` | 无 | 已归一化图片 bytes；Android `ByteArray` 对应 Flutter/Dart 侧 `Uint8List` bytes | 必须能被 `BitmapFactory.decodeByteArray()` 解码 |
| `OUT_W` | `Int` | `1080` | 绿光识别归一化图像宽度 | 坐标结果基于该宽度 |
| `OUT_H` | `Int` | `1920` | 绿光识别归一化图像高度 | 坐标结果基于该高度 |
| `currentZoom` | `Double` | 当前 UI/相机倍率 | 样本记录使用的倍率 | 需与采集时真实相机 zoom 一致 |

## 复用方式
- 优先调用 `ImageUtils.resize()` 把预览帧归一化为 `1080x1920`。
- 使用 `ImageUtils.bitmapToPngBytes()` 得到完整 PNG 图片 bytes。
- 普通校准调用 `CalibrationManager.collectSample(snapshotBytes, distanceMeters, currentZoom)`。
- 双镜头校准直接调用 `LaserDetector.detectLaserCenterFromBytes(snapshotBytes)`。

## 注意事项
- bytes 入口当前接收 PNG/JPEG 等完整图片编码；不是裸像素数组。
- 检测结果坐标属于归一化后的 `1080x1920` 图像坐标系。
- `LaserDetector.detectLaserCenter(bitmap)` 用于 Bitmap 直传识别，新校准流程优先使用 `detectLaserCenterFromBytes(bytes)`。
- bytes 解码产生的内部 `Bitmap` 由 `LaserDetector` 自行回收，外部预览截图和标记图仍由调用方负责回收。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug`
- 已覆盖边界：普通校准、双镜头校准都通过同一份归一化 PNG bytes 调用绿光检测。
- 未覆盖风险：真机不同厂商 PNG 编码/解码性能差异；若后续改为裸帧输入，需要新增单独入口。

## 给 AI 的指令
优先复用本条目的 canonical code path。除非哥哥明确要求重构，否则只做最小必要适配，不重新设计绿光检测算法或坐标系。
