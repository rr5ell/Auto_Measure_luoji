# android-camera-backlight-balance

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、Camera2、AGSL、OpenGL ES
- 标签：camera, exposure, histogram, regional-metering, local-tone-mapping
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\backlightbalance\BacklightMeteringAlgorithm.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\backlightbalance\BacklightBalancePreferences.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\backlightbalance\BacklightBalanceDialog.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\backlightbalance\TextureMeteringSource.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\localtone\LocalToneShader.kt
- 接入：Camera2Controller.kt、CameraScreen.kt、PreviewToneMode.kt。
- 最后更新：2026-09-24
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
| Y 高亮 | >=235 | 普通预览为RGB有限范围亮度代理，GL/HDR为SDR YUV代理，不是RAW饱和率 |
| 网格/间隔 | 最多 64x48 / 500 ms | 绝对索引，尊重 position、rowStride、pixelStride |
| 综合测光 | 中心 40%、四边各 15% | 各区两端各 5% 截尾，编码亮度对数域平均；目标 112/255，误差死区至少 +/-0.35 |
| 分区高光 | 加权近白占比 >=35% 或至少三区各 >=25% | 综合偏暗时不继续加曝，提示反差超限；不以单个亮天空决定整幅曝光 |
| 回退余量 | P99 < floor(16+219/2^实际步幅-8) | 实际0.4 EV时为173，实际2/3 EV时为145；不能仅因像素从235降到234就回升；深暗中心优先恢复分支可绕过此门槛 |
| 中心暗部 | 输入图像中心宽高 30% 的 P20 | <64 停止减曝，>=96 解除；<40 连续确认后优先恢复已有负补偿，不等同目标分割 |
| 档位/范围 | 目标 0.4 EV / 相对手动 -2/3..+1 EV | 向上量化：每档0.2时实际0.4，每档1/3时实际2/3；连续三条确认，粗档扩大死区，边界可截短 |
| 稳定期/过期 | 1.5 秒 / 1.5 秒 | 单调时钟；数据丢失/分区无效撤销自动正负补偿 |
| 显示 | 暗部最多 3 倍，高光最多压低 15% | Y=0.005..0.03 平滑开放增益，纯黑不变；通道同比例缩放，最后应用用户对比度 |

## 复用方式
- EV下发契约见[算法曝光输出与相机下发说明](../../integration/backlight-exposure-output.md)：对外统一将requestedIndex直接写入Camera2曝光补偿键并提交；requestedEv仅显示/日志，不再提供下发方式选择。明确正负号/null/0、禁止重复换算累加及回传验证边界；现有App内部封装未改动。
- 默认目标步幅改为0.4 EV，不改设备硬件步进；已有保存值不覆盖，恢复默认并保存或单独修改步幅后生效。有效死区=max(配置死区,实际步幅*0.6)，默认1/3 EV硬件对应0.4。
- 接入方负责取图与等比例缩放，向算法传入实际像素数组和实际宽高；单文件不获取或缩放图片。640仅是本App普通TextureView取图上限，不是算法硬性限制；64x48是稀疏采样网格，不是输出缩图尺寸。完整职责表、尺寸公式和数组示例见接入说明。
- 集成交付只需`BacklightMeteringAlgorithm.kt`，详细契约见[单文件集成说明](../../integration/backlight-metering.md)。旧四个算法源文件已合并，其同名类型保留在新文件中，旧文件路径标记为superseded；禁止再维护另一份算法副本。
- 统一入口`process(FrameInput?, nowNs)`同步读取8-bit SDR Y平面，`evaluate(HighlightStatistics?, nowNs)`接收已有采样快照。输出包含统计、状态、可选完整EV和对应整数档位；`decision`明确增加/降低/保持/恢复/无法判断，`reason`区分确认进度、稳定期、保护、边界、重复/乱序/过密、无图/布局/时间/统计错误；每会话单实例串行使用，禁止逐帧重建。
- 核心仅依赖Kotlin标准库与ByteBuffer；`BacklightExposureRange`承接硬件范围，UI侧从既有相机范围类型转换。显示Shader、SharedPreferences、Camera2仍留在宿主，不属于单文件。
- 入口统一处理初始等待、至少500ms的新统计节流、非法/过期/断流恢复基线；无图像时宿主仍需定时调用null输入。EV下发失败应停止并恢复/重置。关闭用reset并提交其返回值，pause仅清计数并重新等待。
- “更多 -> 逆光平衡”打开草稿面板，保存并应用同时更新参数与开关；取消不保存，恢复默认只修改草稿。关闭状态也可编辑参数，编辑期间恢复手动EV基线并暂停自动EV/HDR判断，关闭面板重建策略，不能沿用旧确认计数。
- 可调范围：目标Y 1..254，死区0.05..2档，深暗P20 0..254、保护P20 1..254、解除P20 2..255且深暗<=保护<解除；高亮加权/单区占比各1..100%，区域数1..5；目标步幅0.1..1 EV、相对增减上限各0..3 EV；连续确认1..10次、等待0.5..10秒；P99额外余量0..64。默认值仍取上表。
- `BacklightParameter`统一定义标签、范围、整数约束和稳定存储键。构造策略必须校验设置；存储于现有SharedPreferences的`backlight_balance_*`字段，非法存储整体回退默认，不引入第二份曝光状态。
- 五区面积/权重、采样、Y>=235及1.5秒过期上限保持固定；等待时间变长不得放宽旧帧有效期，P99门槛随实际硬件步幅及余量重算。旧PPT说明的是默认参数，不代表用户自定义后的阈值。
- 两页说明与精确边界：[PPT（含讲者备注）](../../presentations/四边测光逻辑_两页说明_9比16.pptx) / [PDF](../../presentations/四边测光逻辑_两页说明_9比16.pdf)，基于 `0.8.35 / fb348fb`。示意图宽:高=9:16，面积不等于权重，按代码首条命中决策，不可省略中心暗部恢复优先级。
- 普通预览使用TextureMeteringSource，每500ms取最长边640像素ARGB小图，不放大小图；后台sampleArgb转换为有限范围亮度代理，统计仍最多64x48点；不含View的RenderEffect，不混入显示增强反馈。0xAARRGGBB行连续、不透明，公式与边界见集成文档。增加截图尺寸不等于提高采样密度，细小高光与性能需真机复核。
- onSurfaceTextureUpdated提供帧身份和单调时间，重复帧不刷新年龄；冻结1.5秒、源失效时撤销补偿；新循环拒绝旧帧。销毁调用invalidate，所有适配器入口在主线程使用。
- GL/HDR保留YUV五区采样，分析线程先取统计快照再转交其他借用者，不持有Image、不改缓冲区位置，代际检查拒绝旧会话。
- TextureView按截图方向传rotation=0；YUV依赖本项目竖屏后摄，按SENSOR_ORIENTATION置换四边。跨项目横屏/前摄、父布局裁剪及显示映射必须重新适配，不能宣称截图等于完整屏幕。ARGB代理与原始Y阈值不保证完全等效。
- UI 单一曝光协程管理补偿，校准/手动面板/模式变化重启并恢复基线；自动 HDR 的同步和异步提交入口均拦截该模式。
- 暗部映射沿用 LocalToneShader 的双边基底，不新增第二套滤波器。普通和 GL 共用核心函数，HLG 在自身编码域显示。

## 测试与验证
- 命令：gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileLaserCompareKotlin --offline --no-daemon。
- 单测：连续确认、迟滞、等待、重复/过期/非法统计、硬件范围、Y 平面布局、灰阶单调性和边缘保护。
- 中心暗部回归：暗中心亮天空不减曝、突然变暗恢复、64/96 迟滞、小尺寸与背景隔离、5/255 深暗输入的两路实际渲染。
- 设备回归：TextureShadowInstrumentedTest、GlFrameBindingInstrumentedTest、CameraToolbarInstrumentedTest。
- 五区回归：方向与角点归属、截尾均值、边缘变化贡献、单区高光/多区反差保护、正负补偿上限、无效数据及简化场景闭环稳定性。
- 最新结果（2026-09-24，默认目标步幅0.4 EV）：142项项目单测、Debug应用/测试APK、laserCompare Kotlin编译通过；单文件无Android独立编译，48项算法测试通过。覆盖默认值、旧字段兼容、硬件量化和边界截短，以及截图尺寸、决策方向、0EV、确认进度、无效与跳帧原因；本轮未运行设备仪器测试，真实手机的性能与精度待验证。
- 先前320版本曾通过TextureView取样2项、参数面板4项及主工具栏1项模拟器回归；不代表本轮640尺寸或完整GL/HLG效果已经设备验证。
- 相机页面自动化涉及真实帧回调与Compose虚拟delay，等待取样时须显式推进500ms测试时钟并允许真实帧更新；不得为修复测试超时放宽生产帧年龄校验。
- 风险：真实手机 AE/HLG 的响应、运动闪烁、ISP YUV 处理差异、模型准确率与长期帧率，不能由模拟器确认。

## 给 AI 的指令
优先复用上述分区统计、曝光策略和共享滤波器。关闭必须恢复手动基线；状态的补偿是请求值，不是 CaptureResult 实测曝光。不得把此功能描述为多帧 HDR 或独立分区曝光；经验中间调不是所有场景唯一正确的曝光。
