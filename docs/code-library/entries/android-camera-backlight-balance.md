# android-camera-backlight-balance

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、Camera2、AGSL、OpenGL ES
- 标签：camera, exposure, histogram, regional-metering, local-tone-mapping
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\backlightbalance\HighlightStatistics.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\backlightbalance\HighlightExposurePolicy.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\backlightbalance\RegionalMetering.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\localtone\LocalToneShader.kt
- 接入：Camera2Controller.kt、CameraScreen.kt、PreviewToneMode.kt。
- 最后更新：2026-09-22
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
| 综合测光 | 中心 40%、四边各 15% | 各区两端各 5% 截尾，编码亮度对数域平均；目标 112/255，误差死区至少 +/-0.35 |
| 分区高光 | 加权近白占比 >=35% 或至少三区各 >=25% | 综合偏暗时不继续加曝，提示反差超限；不以单个亮天空决定整幅曝光 |
| 回退余量 | P99 < floor(16+219/2^步幅-8) | 1/3 EV 时 floor 后为181（未取整约181.818）；不能仅因像素从235降到234就回升；深暗中心优先恢复分支可绕过此门槛 |
| 中心暗部 | 原始分析帧中心宽高 30% 的 P20 | <64 停止减曝，>=96 解除；<40 连续确认后优先恢复已有负补偿，不等同目标分割 |
| 档位/范围 | 约 1/3 EV / 相对手动 -2/3..+1 EV | 连续三条确认，硬件整数档位；粗档扩大误差死区，不能超过设备范围 |
| 稳定期/过期 | 1.5 秒 / 1.5 秒 | 单调时钟；数据丢失/分区无效撤销自动正负补偿 |
| 显示 | 暗部最多 3 倍，高光最多压低 15% | Y=0.005..0.03 平滑开放增益，纯黑不变；通道同比例缩放，最后应用用户对比度 |

## 复用方式
- 两页说明与精确边界：[PPT（含讲者备注）](../../presentations/四边测光逻辑_两页说明.pptx) / [PDF](../../presentations/四边测光逻辑_两页说明.pdf)，基于 `0.8.35 / fb348fb`。面积不等于权重，按代码首条命中决策，不可省略中心暗部恢复优先级。
- 分析线程先取统计快照再转交其他借用者，不持有 Image，不改变缓冲区位置；代际检查拒绝旧会话数据。
- 区域方向依赖本项目锁定竖屏、后摄，按 SENSOR_ORIENTATION 置换四边；不包含纯 UI 放大、显示平移或运动补偿。跨项目横屏/前摄必须重新适配方向，不直接复制参数。
- UI 单一曝光协程管理补偿，校准/手动面板/模式变化重启并恢复基线；自动 HDR 的同步和异步提交入口均拦截该模式。
- 暗部映射沿用 LocalToneShader 的双边基底，不新增第二套滤波器。普通和 GL 共用核心函数，HLG 在自身编码域显示。

## 测试与验证
- 命令：gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileLaserCompareKotlin --offline --no-daemon。
- 单测：连续确认、迟滞、等待、重复/过期/非法统计、硬件范围、Y 平面布局、灰阶单调性和边缘保护。
- 中心暗部回归：暗中心亮天空不减曝、突然变暗恢复、64/96 迟滞、小尺寸与背景隔离、5/255 深暗输入的两路实际渲染。
- 设备回归：TextureShadowInstrumentedTest、GlFrameBindingInstrumentedTest、CameraToolbarInstrumentedTest。
- 五区回归：方向与角点归属、截尾均值、边缘变化贡献、单区高光/多区反差保护、正负补偿上限、无效数据及简化场景闭环稳定性。
- 最新结果：102 项单测、Debug 应用/测试 APK、laserCompare Kotlin 编译通过；以上 3 项模拟器专项回归通过；窄屏大字体读数改为上下、左右分行。
- 风险：真实手机 AE/HLG 的响应、运动闪烁、ISP YUV 处理差异、模型准确率与长期帧率，不能由模拟器确认。

## 给 AI 的指令
优先复用上述分区统计、曝光策略和共享滤波器。关闭必须恢复手动基线；状态的补偿是请求值，不是 CaptureResult 实测曝光。不得把此功能描述为多帧 HDR 或独立分区曝光；经验中间调不是所有场景唯一正确的曝光。
