# android-preview-local-tone-retinex

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、AGSL、OpenGL ES
- 标签：camera, preview, local-tone-mapping, retinex, bilateral-filter
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\localtone\LocalToneShader.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\localtone\PreviewToneMode.kt
- 接入路径：feature/camera/ui 下 CameraScreen.kt、GlStabilizedPreviewView.kt、backlightshadow/TexturePreviewAdjustments.kt、imageadjustment/PreviewContrast.kt。
- 相关文档：E:\StudioProjects\Auto_Measure_luoji\README.md
- 最后更新：2026-09-21
- 作者：Codex Agent

## 适用场景
- 在同一相机预览中比较照明估计和保边基底压缩，不影响原有识别与校准。

## 禁止场景
- 禁止启用 EIS；如需稳定，仅允许 OIS 或算法层物理补偿。
- 禁止将显示结果替换相机/YUV/模型输入或 outputTarget，不改变坐标、时间戳和检测框。
- 不宣称等同 MSRCR、厂商多帧 HDR 或真实细节恢复，不自动重建相机会话。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| preview_local_tone_mode | String | OFF | OFF/LOCAL_TONE/RETINEX/GLARE_SUPPRESSION/BACKLIGHT_BALANCE | 无效值回退 OFF；单值持久化保证互斥 |
| 邻域 | 采样网格 | 5x5 | [1,4,6,4,1] 外积空间权重 | 固定 25 次邻域采样，不是稠密大核 |
| 半径 | 像素 | 短边*0.025 | 间距为半径/2 | 限制 4~32；GL/AGSL 坐标单位不同 |
| 双边范围标准差 | Float | 0.35 | 对数亮度差 | 边缘两侧不会无条件混合 |
| 局部基底提亮强度 | Float | 0.4 | 向 0.7 参考亮度压缩暗基底 | 保留对数细节层 |
| Retinex 照明压缩 | Float | 0.45 | Y*L^(-0.45) | 单尺度照明估计，不做颜色恢复矩阵 |
| 最大增益 | Float | 4 | 两模式共用 | 还受 1/max(R,G,B) 限制 |

## 复用方式
- GPU 使用 LocalToneShader.functions，宿主提供 toneLuma(position) 并钳位采样坐标；CPU gain 仅用于参考测试。
- TexturePreviewAdjustments.apply 接收模式；GL 调用 setPreviewToneMode，在最终上屏合成应用。
- CameraScreen 的五个增强互斥（含眩光抑制和逆光平衡）；校准通过 forCalibration(true) 临时覆盖，不写回偏好。
- BACKLIGHT_BALANCE 复用双边基底，允许高光增益低于 1、近黑限制增益；此模式还包含受限负 EV 采集控制，不能沿用其他模式“仅影响显示”的整体描述，详见 android-camera-backlight-balance。
- 效果顺序：局部增强或旧暗部曲线，然后对比度。关闭模式应立刻恢复原有显示处理。

## 注意事项
- 在各自 SDR/HLG 编码域调色，不假设 HLG 是 sRGB。普通预览需要 API 33+，GL 无新增系统 API 要求。
- 首次布局前推迟采样；实际尺寸变化需更新步长及边界。GL 使用视口尺寸，半径始终以像素定义。
- 邻域随画面运动会变化；固定参数只避免全局拉伸跳动，不保证无闪烁。暗部噪声、Retinex 边缘光晕及稀疏采样伪影需真机评估。
- 本实现根据经典分解思想编写为受限实时版本，不是对论文完整算法的逐项复现。
- 原理参考：[MIT 双边滤波资料](https://people.csail.mit.edu/fredo/comp-photo-book/05-edges-matter-02-bilateral-filtering.html)、[IPOL Multiscale Retinex](https://www.ipol.im/pub/art/2014/107/)。

## 测试与验证
- 构建：gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileLaserCompareKotlin --offline --no-daemon。
- LocalToneShaderTest：模式/校准、端点、邻域依赖、边缘保护、色域和非法值。
- TextureShadowInstrumentedTest / GlFrameBindingInstrumentedTest：实际屏幕像素、亮暗交界邻域和原始缓存隔离、关闭恢复。
- CameraToolbarInstrumentedTest：新增开关互斥、原暗部开关互斥、重建恢复。
- 未覆盖风险：真实逆光场景效果、厂商 HLG 显示、长期帧率与旧系统实机。
- 2026-09-21：69 项单元测试、Debug 应用/测试包构建及 laserCompare Kotlin 编译通过；最终包 10 项模拟器回归通过，默认与窄屏大字体菜单截图检查通过。

## 给 AI 的指令
优先复用共享滤波代码与模式状态，避免为两个渲染器分别维护算法；不要把已通过的像素回归当作真实逆光画质结论。
