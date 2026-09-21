# android-camera-backlight-balance

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、Camera2、AGSL、OpenGL ES
- 标签：camera, exposure, histogram, local-tone-mapping
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\backlightbalance\HighlightStatistics.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\backlightbalance\HighlightExposurePolicy.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\localtone\LocalToneShader.kt
- 接入：Camera2Controller.kt、CameraScreen.kt、PreviewToneMode.kt。
- 最后更新：2026-09-21
- 作者：Codex Agent

## 适用场景
- 单帧实时预览的高光保护实验，复用现有分析流而不引入另一个模型或全帧 CPU 滤镜。

## 禁止场景
- 禁止启用 EIS；如需稳定，仅允许 OIS 或算法层物理补偿。
- 禁止把显示增强当作恢复信息或改善识别的证明，不修改模型图像/坐标。
- 不与自动 HDR 曝光时间闭环同时运行；不覆盖校准曝光或手动 EV 偏好。

## 核心参数
| 参数 | 默认值 | 约束 |
|---|---|---|
| 模式 | BACKLIGHT_BALANCE，默认 OFF | 沿用 preview_local_tone_mode，其他显示增强互斥 |
| Y 高亮 | >=235 | SDR YUV 代理，不是 RAW 饱和率 |
| 网格/间隔 | 最多 64x48 / 500 ms | 绝对索引，尊重 position、rowStride、pixelStride |
| 减曝/回退 | >2% / <0.5% | 各连续 3 条有效新统计，稳定期不累计 |
| 回退余量 | P99 < floor(16+219/2^步幅-8) | 常见 1/3 EV 约 182；不能仅因像素从 235 降到 234 就回升 |
| 档位/下限 | 约 1/3 EV / 相对手动最多 -2 EV | 硬件整数档位，不能越界或上调超出手动基线 |
| 稳定期/过期 | 1.5 秒 / 1.5 秒 | 单调时钟；数据丢失撤销负补偿 |
| 显示 | 暗部最多 3 倍，高光最多压低 15% | 近黑保护、通道同比例缩放，最后应用用户对比度 |

## 复用方式
- 分析线程先取统计快照再转交其他借用者，不持有 Image，不改变缓冲区位置；代际检查拒绝旧会话数据。
- UI 单一曝光协程管理补偿，校准/手动面板/模式变化重启并恢复基线；自动 HDR 的同步和异步提交入口均拦截该模式。
- 暗部映射沿用 LocalToneShader 的双边基底，不新增第二套滤波器。普通和 GL 共用核心函数，HLG 在自身编码域显示。

## 测试与验证
- 命令：gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileLaserCompareKotlin --offline --no-daemon。
- 单测：连续确认、迟滞、等待、重复/过期/非法统计、硬件范围、Y 平面布局、灰阶单调性和边缘保护。
- 设备回归：TextureShadowInstrumentedTest、GlFrameBindingInstrumentedTest、CameraToolbarInstrumentedTest。
- 本轮结果：90 项单测、Debug 应用/测试 APK、laserCompare Kotlin 编译通过；最终包以上 3 项专项设备回归通过。默认与窄屏大字体布局已检查。
- 风险：真实手机 AE/HLG 的响应、运动闪烁、ISP YUV 处理差异、模型准确率与长期帧率，不能由模拟器确认。

## 给 AI 的指令
优先复用上述策略和共享滤波器。关闭必须恢复手动基线；状态的补偿是请求值，不是 CaptureResult 实测曝光。不得把此功能描述为多帧 HDR。
