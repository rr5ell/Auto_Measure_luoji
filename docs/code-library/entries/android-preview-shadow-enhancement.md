# android-preview-shadow-enhancement

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、AGSL、OpenGL ES
- 标签：camera, preview, shadows, tone-curve, calibration
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\backlightshadow\ShadowEnhancement.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\backlightshadow\TexturePreviewAdjustments.kt
- 接入路径：feature/camera/ui/CameraScreen.kt、GlStabilizedPreviewView.kt、imageadjustment/PreviewContrast.kt。
- 相关文档：E:\StudioProjects\Auto_Measure_luoji\README.md
- 最后更新：2026-09-21
- 作者：Codex Agent

## 适用场景
- 逆光场景的显示端暗部提亮，独立于 Camera2 曝光、LSC 和 HDR 动态范围配置。

## 禁止场景
- 禁止启用 EIS；如需稳定，仅允许 OIS 或算法层物理补偿。
- 不用于镜头暗角校正、多帧 HDR 融合或丢失细节恢复，不将增强后的显示图送入既有模型/校准流程。
- 不自动切换 GL/HDR，不触发相机重建；不能假定所有厂商 HDR 屏显示相同。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| preview_shadow_enhancement | Boolean | false | 预览增强偏好 | 校准临时停用、不覆盖偏好 |
| 暗部增益 | Float | 1+2*(1-Y)^4 | 编码域曲线 | 以 1/max(R,G,B) 限制增益 |
| SDR 权重 | RGB | 0.2126,0.7152,0.0722 | BT.709 | 不是传感器测光值 |
| HLG 权重 | RGB | 0.2627,0.6780,0.0593 | BT.2020 | 不作 sRGB 解码 |

## 复用方式
- 已增加局部色调映射/Retinex，三个增强互斥，模式非 OFF 时优先局部算法。共享邻域实现见 android-preview-local-tone-retinex，不复制一套独立 shader。
- TextureView 调用 TexturePreviewAdjustments.apply；Android 13+ 以 AGSL 同时完成增强与对比度。关闭增强时，API 31+ 使用 ColorMatrix RenderEffect，API 24~30 使用专属父容器硬件层滤镜。禁止对 TextureView.setLayerPaint 设置颜色滤镜，该滤镜也会影响 getBitmap 原图。旧系统普通预览不支持开启增强，仍支持对比度。
- GL 调用 setShadowEnhancement，在最终上屏处理；outputTarget、诊断缓存和检测框均不处理。
- Shader 共用 GAIN_FUNCTION；调色顺序固定为暗部增强、对比度。无额外 CPU 全帧处理，不改变坐标、时间戳或帧配对。

## 注意事项
- 暗部噪声也会提升；全黑不抬成灰色，高饱和度像素受色域约束可能不明显提亮。
- 普通与双摄校准时禁用开关并临时绕过处理，退出恢复偏好。
- RenderEffect/Shader 错误返回 Result.failure，在主面板提示，保留可用对比度；不要吞错后宣称已生效。

## 测试与验证
- 构建：gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon。
- ShadowEnhancementTest：端点、灰阶单调、暗部/高光、色域及非法参数。
- TextureShadowInstrumentedTest：真实 TextureView 上屏像素、两种调节叠加/恢复、getBitmap 原图不变。
- GlFrameBindingInstrumentedTest：GL 上屏提亮、缓存不变和关闭恢复。
- CameraToolbarInstrumentedTest：四个顶部入口、更多、开关保存和 Activity 重建。
- 未覆盖风险：厂商真机 HLG 画质、GPU 兼容性与持续帧率。
- 2026-09-21：65 项单元测试、Debug 应用与设备测试包构建、laserCompare Kotlin 编译通过；10 项模拟器回归通过，包括旧系统父容器处理路径的像素检查（非旧系统实机）。
- API 依据：[Android RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect)、[AGSL 使用方式](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl)。

## 给 AI 的指令
优先复用标准路径与共享增益函数，不另建 CPU 图像处理管线；除非哥哥明确要求，不修改 YOLO 和校准的图像输入。
