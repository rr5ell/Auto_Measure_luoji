# android-preview-fps

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、Jetpack Compose、OpenGL
- 标签：camera, preview, fps, telemetry
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\previewfps\PreviewFrameRate.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\GlStabilizedPreviewView.kt
- 最后更新：2026-09-18
- 作者：Codex Agent

## 适用场景
- 按新图像更新次数统计应用预览速度，不依赖模型运行或像素回读。

## 禁止场景
- 禁止用相机请求 FPS、YOLO 推理次数或重复 GL 绘制替代新画面次数。
- 禁止启用系统 EIS；如需稳定，仅允许 OIS 或算法层物理补偿。
- 不将该指标称为物理屏幕呈现帧率，GL 统计发生在绘制调用完成后、交换缓冲之前。

## 核心参数
| 参数 | 值 | 说明 |
|---|---|---|
| 采样窗口 | 至少 1 秒 | 按实际单调时间差计算，不假定轮询准时 |
| 时间单位 | 纳秒 | 采样时钟为 elapsedRealtimeNanos，图像时间戳只用于判重 |
| 显示精度 | 1 位小数 | 就绪前为 --，完整窗口无新帧为 0.0 |

## 复用方式
- 为每个预览 View 创建独立计数器；会话就绪、镜头或模式变化时调用 reset。
- TextureView.onSurfaceTextureUpdated 或 GL 成功绘制新图像后调用 onFrame。
- 主线程每秒调用 sample，返回 null 时不发布新读数。采样与计数使用同步锁，不产生逐帧界面任务。

## 测试与验证
- PreviewFrameRateTest：30 FPS、延迟采样、重复与非法时间戳、零帧、重置和时钟回退。
- GlFrameBindingInstrumentedTest：新图像增量及重复绘制的零增量。
- PreviewFpsInstrumentedTest：普通预览与 GL 的页面实时读数、前后台恢复；测试结束恢复原 GL 偏好，不启用 EIS。
- 本轮 22 项单元测试及 FPS 页面模拟器回归通过；完整 GL 回归中的预热和检测框时序检查曾失败，第三次完整通过，仍有模拟器时序波动风险。
- 真机 HDR 帧率准确度及长时负载需硬件验证，模拟器速度不能代表手机性能。

## 给 AI 的指令
优先复用上述计数器，不在 Camera2 请求或模型推理路径中重复统计预览 FPS。
