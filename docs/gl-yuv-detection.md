# 普通 GL 与 YUV 同帧识别

日期：2026-09-15。作者：Codex Agent。

## 使用与边界

- 在“更多 -> 自研EIS参数”开启 GL 并保存，然后点击顶部“识别”。
- 普通 GL 运行时使用 YUV 图源；退出 GL 后恢复原有图源选择，不改写其偏好。
- 保持现有 YOLO 模型、单框/多框阈值和 native YUV 预处理，不新增模型或依赖。
- HDR 模式不开放 YOLO；GL 绿光检测和截图校准仍禁用。
- 严格同帧模式按推理速度更新画面，推理期间保留上一对图像与框。它不是在满速新预览上跟踪旧检测框，也不承诺相机满帧率。
- 普通逻辑相机预览不绑定物理输出；系统 EIS 固定关闭，仅允许 OIS 或应用层算法补偿。

## 数据与坐标

1. ImageReader 取出最新 YUV 帧，保留 Image.timestamp。
2. GL 在三个待选纹理缓存中寻找相同时间戳，固定一张纹理，并保存其采样矩阵和对应显示矩阵。纹理最多额外固定一张，不按推理耗时无限堆积。
3. 找不到匹配帧时等待不超过 500 ms；被 SurfaceTexture 跳过的帧直接丢弃，不使用邻帧替代。
4. 模型在专用 YoloInference 线程创建、运行和释放。Image 始终在 finally 中关闭。
5. 将模型返回的旋转后原图坐标逆旋转到 YUV 缓冲区，归一化后应用该帧 SurfaceTexture 采样矩阵的逆，再应用实际 GL 显示矩阵。
6. 对框的四个角分别映射，保留旋转四边形。框内百分比判定点由原框角插值，不使用旋转后外接矩形代替。
7. 框、标签和对应图像写入同一个 FBO，一起提交。Compose 不再对 GL 框叠加实时 wrapper 变换。
8. 完成提交后释放固定帧。取消、前后台切换、镜头/倍率切换和尺寸变化会让未完成票据失效；已使用票据不能重复提交。

坐标前提：YUV 和 OES 来自同一逻辑会话，使用相同流尺寸、视场和时间戳。矩阵包含采样边缘裁切、镜像、方向、基础缩放、自研 EIS 与 Roll；不能额外重复 centerCrop。厂商不同流的融合差异仍需真机用已知目标验证。

API 依据：[SurfaceTexture 采样矩阵](https://developer.android.com/reference/android/graphics/SurfaceTexture#getTransformMatrix(float[]))、[Image 时间戳](https://developer.android.com/reference/android/media/Image#getTimestamp())。

## 测试

- 单元测试：`gradlew.bat :app:testDebugUnitTest --offline --no-daemon`。
- 构建：`gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon`。
- GL 像素回归：`GlFrameBindingInstrumentedTest`，检查精确帧配对、迟到矩阵、固定帧跨六帧保留、框与白色矩形像素边界、同 FBO 中文标签像素及旧票据拒绝。
- 模型链路回归：`GlYuvModelInstrumentedTest`，真实相机 YUV -> 现有模型 CPU 推理 -> 同时间戳 GL 输出；至少两帧。
- 数学回归：`GlDetectionCoordinatesTest`，覆盖 0/90/180/270 度、镜像、采样裁切、平移、Roll、矩阵快照与非法输入。
- 构建成功，10 项 JVM 测试全部通过。
- 已连接真机 SM-S9480（Android API 36、1080x2340、450 dpi）安装本轮 Debug 包；同帧 GL 像素测试与真实相机模型闭环测试均通过。
- 实际相机页验证普通 GL 下“识别”按钮可用、YUV 推理持续输出、前后台切换恢复；关闭 GL 后自动恢复原“预览截图”图源，重新开启 GL 后恢复强制 YUV。
- 一次现场采样中推理约 32–40 ms，整次处理约 70–120 ms；这是当时设备与场景的观察值，不是性能保证。
- 现场画面未检出目标，真实旗杆定位精度尚未验证；合成白色矩形用于核对检测框的像素边界。没有将现场截图登记到代码资产库。
- 全量 Lint 仍有原有 8 项错误、62 项警告和 1 项提示。错误仍位于 BLE/语音权限、MainActivity 的 Fragment 版本要求和 Manifest 相机特性声明；本轮无新增 Lint 错误，新增一项 Bitmap 的 KTX 风格建议。
- 首次真机测试受锁屏影响未进入 GL 表面；解锁后重新执行两项测试均通过。
