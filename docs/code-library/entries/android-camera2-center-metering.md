# android-camera2-center-metering

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、Camera2、Compose
- 标签：camera2, exposure, metering, coordinate-map
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\centermetering\CenterMeteringRegion.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\centermetering\CenterMeteringRequest.kt
- 接入路径：同项目 feature/camera/domain/Camera2Controller.kt 与 feature/camera/ui/CameraScreen.kt。
- 相关文档：E:\StudioProjects\Auto_Measure_luoji\README.md
- 最后更新：2026-09-21
- 作者：Codex Agent

## 适用场景
- 使用 Camera2 的 AE 区域提示，提高相机对中心目标的曝光关注，不是显示调色。

## 禁止场景
- 禁止启用 EIS；如需稳定，仅允许 OIS 或算法层物理补偿。
- 不强制更改 AF、AE 模式、EV 或 AE_LOCK；校准和严格物理会话暂停中心测光。
- 不保证厂商完全忽略其他区域，也不能宣称解决所有逆光或光学眩光。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| center_exposure_metering_enabled | Boolean | false | 持久化用户选择 | 不支持时不应用，但可关闭已保存的选择 |
| FRACTION | Double | 0.30 | 可见预览宽高比例 | 约 9% 面积，最小 1x1 |
| 权重 | Int | 1000 | 单一区域非零权重 | 单一区域的不同非零值效果等价 |
| displayScale | Float | 1 | 纯 UI 中心放大 | 非有限或小于 1 回退 1 |

## 复用方式
- CenterMeteringRegion.calculate 接受相机坐标系 crop 和未旋转的预览尺寸；先做宽高比裁切，再取中心 30%，纯 UI 放大再除以显示倍率。
- CONTROL_ZOOM_RATIO 已使用变焦后坐标域，不再除 zoom；SCALER_CROP_REGION 必须传缩小后的实际请求 crop。
- CenterMeteringRequest 按 CONTROL_MAX_REGIONS_AE、请求键和阵列能力判定支持，按畸变校正模式选择坐标边界。
- 每次创建请求记忆 TEMPLATE_PREVIEW 原区域；关闭、校准或拒绝时恢复，不遗留旧手动区域。
- 空/全零权重模板改用有效全幅零权重矩形，表达默认测光语义；不能恢复零面积矩形后仅凭 Builder 判断成功，必须检查 CaptureResult。
- 控制器通过 submitMeteredPreview 在启动、缩放及开关变化时应用；过期会话提交直接丢弃且不发布打开通知，区域请求被拒绝时撤回并重试一次。

## 注意事项
- 请求使用父逻辑相机坐标，不能将物理镜头特征混入逻辑请求。无逐帧图像计算，不增加模型开销。
- 原图、YOLO 等会受到真实曝光变化影响，不能描述为仅上屏效果。
- 自动 HDR 不被关闭，但曝光变化可能改变其判断；校准期间保留选择并暂停。
- 不追踪校准锚点的显示平移及自研运动补偿的逐帧平移/旋转；用于原始预览中心曝光参考，不冒充任意显示变换后的中心。
- 主面板“已请求”不是 HAL 确认；center-metering-result 日志提供请求和返回区域对比。
- API 依据：本地 Android 36 SDK 的 android/hardware/camera2/CaptureRequest.java 中 CONTROL_AE_REGIONS 文档。

## 测试与验证
- gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileLaserCompareKotlin --offline --no-daemon。
- CenterMeteringRegionTest：宽高比、裁切/纯 UI 放大、非零原点、小区域、非法尺寸。
- CenterMeteringInstrumentedTest：真实模板区域恢复、缩放及 AF/EV/AE_LOCK/EIS 不变，能力不支持分支。
- CenterMeteringUiTest：持久化、关闭恢复、重建和不可用提示。
- 81 项单元测试、Debug 应用/测试包构建和 laserCompare Kotlin 编译通过；中心测光请求/真实 CaptureResult、开关持久化、原增强菜单三项设备回归通过。
- 模拟器确认开启中心权重 1000、关闭全幅权重 0，默认与 320dp/1.3 倍字体菜单检查通过。
- 真机逆光、HDR、光学多镜头切换收益与厂商 AE 行为未验证，未执行全量设备回归。

## 给 AI 的指令
复用现有区域换算和模板回退，不做曝光锁定或自动提高 EV；不修改模型坐标，不为开关重建相机。
