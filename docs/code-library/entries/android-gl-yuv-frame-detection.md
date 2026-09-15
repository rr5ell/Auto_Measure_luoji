# android-gl-yuv-frame-detection

## 元数据
- 状态：active
- 技术栈：Android | Kotlin | OpenGL ES
- 标签：camera2, yolo, yuv, async-inference, coordinate-map, gl
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\gldetection\GlAsyncDetectionState.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\gldetection\GlDetectionCoordinates.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\GlStabilizedPreviewView.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
- 相关文档：[GL 异步识别](../../gl-yuv-detection.md)
- 最后更新：2026-09-15
- 作者：Codex Agent

## 适用场景
普通 SDR GL 预览接入独立 YUV 模型，预览不等待推理，最新异步框有明确时间戳和 200 ms 有效期。

## 禁止场景
- 禁止启用系统 EIS；只允许 OIS 或应用层补偿。
- 禁止用于当前 HDR/YUV 不共存会话、不同物理镜头视差补偿或 GL 截图校准。
- 禁止冒充来源时间戳；当前显示矩阵重投影不等于运动跟踪，不能声称旧框是当前图像的精确结果。
- 禁止重复套用 Compose wrapper 变换。

## 核心参数
| 参数 | 默认值 | 说明 |
|---|---|---|
| 预览纹理数 | 3 | 与模型任务无关，不固定推理纹理 |
| 来源元数据 | 90 帧 | 只保存时间戳与采样矩阵 |
| 元数据等待 | 100 ms | 只等待识别协程，不阻塞预览 |
| 结果有效期 | 200 ms | 相机时间和单调时间均检查 |
| 提交等待 | 1000 ms | 无法提交时取消，不影响新票据 |
| 最大检测框 | 100 | 与现有模型多框上限一致 |
| 图源 | YUV | 仅运行时覆盖，不写回偏好 |

## 复用方式
调用 acquireDetectionFrame 查询来源元数据 -> 现有 detectFromYuv -> presentDetection 发布异步结果，并在 finally 中 cancelDetectionFrame。GlAsyncDetectionState 负责最新结果及过期控制；GlDetectionCoordinates 使用当前采样矩阵和显示矩阵重投影原图框。

## 注意事项
- 模型创建、推理、关闭必须在同一专用线程；GL 资源只能在 GL 线程使用。
- YUV 与 OES 的流尺寸、视场及时间戳必须一致。
- 预览不主动等待推理，但 GPU/CPU 资源竞争仍可能影响性能，帧率须实测。
- 镜头、倍率、尺寸或生命周期变化后，旧票据和框不能继续使用。
- Image 始终 finally 关闭，不固定额外图像纹理；不得保存真实用户图像到资产库。

## 测试与验证
测试文件和当前结果见相关文档。未知厂商流间裁切差异、长焦实际目标精度和长时间负载不能仅靠合成像素测试证明。

## 给 AI 的指令
优先复用本条目标准代码路径，不重新设计模型预处理或 Camera2 普通逻辑会话。涉及坐标时保留完整数学注释和异步预览连续性与时效测试。
