# android-gl-yuv-frame-detection

## 元数据
- 状态：active
- 技术栈：Android | Kotlin | OpenGL ES
- 标签：camera2, yolo, yuv, frame-sync, coordinate-map, gl
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\gldetection\GlDetectionCoordinates.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\GlStabilizedPreviewView.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
- 相关文档：[GL 同帧识别](../../gl-yuv-detection.md)
- 最后更新：2026-09-15
- 作者：Codex Agent

## 适用场景
普通 SDR GL 预览接入现有 YUV 模型，并严格保证图像、补偿矩阵、框和标签来自同帧。

## 禁止场景
- 禁止启用系统 EIS；只允许 OIS 或应用层补偿。
- 禁止用于当前 HDR/YUV 不共存会话、不同物理镜头视差补偿或 GL 截图校准。
- 禁止用最近帧近似精确时间戳，禁止给旧检测结果使用最新矩阵。
- 禁止重复套用 Compose wrapper 变换。

## 核心参数
| 参数 | 默认值 | 说明 |
|---|---|---|
| 待选纹理数 | 3 | 最多额外固定一张推理纹理 |
| 同帧等待 | 500 ms | 超时取消票据 |
| 提交等待 | 1000 ms | 无法提交时取消，不影响新票据 |
| 最大检测框 | 100 | 与现有模型多框上限一致 |
| 图源 | YUV | 仅运行时覆盖，不写回偏好 |

## 复用方式
调用 acquireDetectionFrame -> 现有 detectFromYuv -> presentDetection，并在 finally 中 cancelDetectionFrame。GlDetectionCoordinates 使用固定帧采样矩阵与实际 GL 显示矩阵，将原框四角映射到输出像素。

## 注意事项
- 模型创建、推理、关闭必须在同一专用线程；GL 资源只能在 GL 线程使用。
- YUV 与 OES 的流尺寸、视场及时间戳必须一致。
- 识别模式按推理速度推进图像与框，不承诺满速相机预览。
- 镜头、倍率、尺寸或生命周期变化后，旧票据和框不能继续使用。
- Image 与固定纹理均有明确 finally/取消释放；不得保存真实用户图像到资产库。

## 测试与验证
测试文件和当前结果见相关文档。未知厂商流间裁切差异、长焦实际目标精度和长时间负载不能仅靠合成像素测试证明。

## 给 AI 的指令
优先复用本条目标准代码路径，不重新设计模型预处理或 Camera2 普通逻辑会话。涉及坐标时保留完整数学注释和同帧像素测试。
