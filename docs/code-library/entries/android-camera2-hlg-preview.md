# android-camera2-hlg-preview

## 元数据
- 状态：active（实验功能）
- 技术栈：Android、Kotlin、Camera2、OpenGL ES 3
- 标签：camera2, hdr, hlg, frame-sync, logical-camera
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\hdr\HdrPreviewCapabilities.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\hdr\HlgPreviewGl.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\GlStabilizedPreviewView.kt
- 相关文档：E:\StudioProjects\Auto_Measure_luoji\docs\eis-hdr-lsc-sync.md
- 最后更新：2026-09-15
- 作者：Codex Agent

## 适用场景
- 父逻辑相机 HLG10 实时取景与应用层逐帧补偿。

## 禁止场景
- 禁止启用系统 EIS；仅允许 OIS 或算法层物理补偿。
- 禁止普通预览绑定物理 Surface，禁止把 SDR 输入混入 HLG 表面。
- HDR/GL 期间禁止未适配的 YOLO、绿光、截图校准。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| hdrPreviewEnabled | Boolean | false | HDR 开关，不跨重启保存 | Android 13+ |
| dynamicRangeProfile | Long | HLG10 | HDR 会话格式 | 实际逻辑相机支持 |
| GL 缓存 | 格式 | RGB10_A2 | 保留 10-bit 精度 | ES 3、HLG EGL 表面 |

## 复用方式
- 后台调用 `HdrPreviewCapabilities.checkCamera()` 和 `HlgPreviewGl.checkSupport()`。
- `CameraScreen.applyHdrPreviewMode()` 同时切换相机会话及 GL View，关闭时恢复先前 GL 选择。
- 复用 `FrameTransformQueue` 精确匹配，不使用临近时间戳回退。

## 注意事项
- 源实现来自 Auto_measure `2e14622`；本项目针对父逻辑相机检查和建立 HDR 输出，不复制源项目的普通物理绑定。
- 光学候选镜头与实际活跃物理镜头由 HAL 决定，不用候选 physicalId 代替逻辑相机能力。

## 测试与验证
- `HdrPreviewInstrumentedTest`：不支持屏幕、HLG 色彩标记、10-bit 精度、同帧输出。
- 真正 HLG 输出测试在不支持硬件时跳过，不能据此宣称 HDR 实机画质已通过。
- 构建与测试结果见关联文档。

## 给 AI 的指令
优先复用上述标准代码。不要另建稳定器或更改普通预览物理绑定策略。
