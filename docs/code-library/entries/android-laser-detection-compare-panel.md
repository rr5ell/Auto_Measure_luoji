# android-laser-detection-compare-panel

## Metadata
- status: active
- tech: Android | Kotlin
- tags: compose, camera-preview, laser-detection, debug-tool, gallery-output
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\build.gradle.kts
  - E:\StudioProjects\Auto_Measure_luoji\app\src\laserCompare\res\values\strings.xml
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\calibration\util\ImageUtils.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\calibration\detector\LaserDetector.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-07-30
- author: Codex Agent

## 适用场景
- 需要在真机上验证同一帧图片经过新绿光算法和旧 resize 算法后的坐标差异。
- 需要把对比工具作为另一个 App 安装到同一台手机，避免影响主 App 测试。
- 需要把识别结果保存到相册，方便离线查看 raw 图和 marked 图。

## 禁止场景
- 禁止把该调试入口改成无节流连续保存图片，避免快速占用设备存储。
- 禁止在该入口中擅自改动 Camera2 防抖策略；EIS 默认 OFF，只有用户显式选择实验模式时才可开启。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| `showLaserComparePanel` | `Boolean` | `false` | 顶部 `绿光` 按钮展开状态 | 仅控制 UI 显示 |
| `laserCompareEnabled` | `Boolean` | `false` | 识别开关状态 | 触发单次识别，完成后自动恢复 false |
| `uint8Bytes` | `ByteArray` | 无 | `1080x1920` PNG 图片 bytes | 必须可由 `BitmapFactory` 解码 |
| `normalizedFrame` | `Bitmap` | 无 | 同一帧归一化 Bitmap | 新旧算法 marked 图必须来自同一帧 |
| `laserCompare` | Android build type | 无 | 独立安装包 | 包名为 `com.example.auto_measure_luoji.lasercompare` |
| `isLaserCompareApp` | `Boolean` | 包名判断 | 控制对比包专用按钮 | 对比包固定显示 `绿光对比` |

## 复用方式
- 顶部工具条复用 `TopActionButton`，使用 `绿光/绿光中` 文案和绿色 accent。
- `laserCompare` 对比包在顶部第二行最左侧固定显示 `绿光对比/对比中`，并隐藏主 App 第一行普通 `绿光` 入口。
- 面板复用 `LaserComparePanel`，通过 `Switch` 触发 `runLaserCompareRecognition()`。
- 新算法调用 `LaserDetector.detectLaserCenterFromBytes(uint8Bytes)`，使用 Pillow-style resize 和大绿光区域几何中心修正。
- 旧算法调用 `LaserDetector.detectLaserCenterFromBytesLegacy(uint8Bytes)`，保留旧 resize 和旧 bright-core refine，不包含大绿光区域几何中心修正。
- 输出文件名使用 `laser_compare_raw_*`、`laser_new_marked_*` 和 `laser_old_marked_*` 前缀。
- 对比 App 使用 `.\gradlew.bat :app:assembleLaserCompare` 构建。

## 注意事项
- 该入口用于调试对比，不参与正式普通校准和双镜头校准状态机。
- marked 图通过 `ImageUtils.drawMarkOnBitmap()` 的 `labelPrefix` 和 `markColor` 参数区分 new 与 old 结果。
- 截图和归一化 Bitmap 由调用方在 finally 中回收；bytes 入口内部解码 Bitmap 由 `LaserDetector` 回收。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug`
- 测试命令：`.\gradlew.bat :app:assembleLaserCompare`
- 已覆盖边界：预览未就绪、截图失败、新算法未命中、旧算法未命中、异常回收。
- UI 验证：对比包启动后顶部第二行可直接看到 `绿光对比`。
- 未覆盖风险：真机 MediaStore 保存权限/厂商相册刷新延迟。

## 给 AI 的指令
后续对绿光识别结果有争议时，优先使用该面板在同一帧上同时生成新旧算法两组结果，避免不同帧造成误判。
