# android-preview-glare-suppression

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、AGSL、OpenGL ES
- 标签：camera, preview, glare-suppression, agsl, opengl
- 标准代码路径：E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\glaresuppression\GlareSuppressionShader.kt
- 相关文档：E:\StudioProjects\Auto_Measure_luoji\README.md
- 最后更新：2026-09-21
- 作者：Codex Agent

## 适用场景
- 预览端对局部白雾进行有限抑制的实验，不是成熟光学反演或细节重建算法。

## 禁止场景
- 禁止启用 EIS；如需稳定，仅允许 OIS 或算法层物理补偿。
- 不修改相机曝光、相机会话、模型输入、测量/校准缓存或坐标映射。
- 不用输出证明恢复了过曝信息；未经精度回归不得用于测量。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| 模式 | PreviewToneMode | OFF | GLARE_SUPPRESSION 的 Shader 值为 3 | 与其他增强互斥，校准覆盖 OFF |
| 邻域 | 网格 | 5x5 | 复用 LocalToneShader.radius | 半径短边 2.5%，4~32 像素，稀疏采样 |
| 灰底系数 | Float | 0.35 | 局部最小亮度乘系数 | 不是真实杂散光估计 |
| 扣除上限 | Float | 0.12 | 限制 v | 还受 0.5*最小通道限制 |
| 纹理渐入范围 | Float | 0.015~0.15 | smoothstep(邻域最大-最小) | 平坦区域不变 |
| 高光渐退范围 | Float | 0.7~0.98 | 按中心亮度降低扣除量 | 白色不变 |

## 复用方式
- GlareSuppressionShader.functions 生成共享 GLSL/AGSL，宿主提供 toneLuma 和边界钳位。
- veil/channel 为 CPU 数学参考，仅用于测试，不能作为逐帧 CPU 处理路径。
- 复用 PreviewToneMode 偏好、互斥菜单、forCalibration；处理顺序为眩光抑制后对比度。

## 注意事项
- 普通预览要求 Android 13+；旧系统需手动启用 GL，不自动重建相机会话。
- SDR/HLG 各自在编码域调节，无物理线性光保证；无新增依赖或神经网络模型。
- 非眩光纹理可能被压暗；运动、稀疏采样可能产生局部光晕/闪烁。优先评估真实目标边缘而非整体观感。

## 测试与验证
- 命令：gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileLaserCompareKotlin --offline --no-daemon。
- GlareSuppressionShaderTest：端点、平坦区、灰底抑制、高光、低纹理、通道保护及异常输入。
- TextureShadowInstrumentedTest / GlFrameBindingInstrumentedTest：实际 GPU 上屏变化、关闭恢复及未处理源缓存。
- CameraToolbarInstrumentedTest：互斥开关、关闭、重建持久化。
- 75 项单元测试、Debug 构建和 laserCompare Kotlin 编译通过，三项重点设备回归分别通过，默认及窄屏大字体菜单截图检查通过。
- 全量模拟器测试因系统 EGL 崩溃中断；串行 TextureView -> GL 初始化存在未定位超时/ANR，GL 单独运行通过。
- 未覆盖风险：真实眩光改善、持续 FPS、HLG 和旧系统实机。

## 给 AI 的指令
复用显示处理通道，保持算法取帧隔离；不能把此启发式实现描述成真实光学眩光反演或神经网络去眩光。
