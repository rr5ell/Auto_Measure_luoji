# android-camera2-auto-hdr-exposure

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、Camera2、Jetpack Compose
- 标签：camera2, hdr, exposure, state-machine, preferences, visible-samples, fault-pause
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\autohdr\AutoHdrPolicy.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\autohdr\AutoHdrPreferences.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\autohdr\AutoHdrDialog.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
- 相关资产：android-camera2-exposure-display、android-camera2-hlg-preview
- 最后更新：2026-09-20
- 作者：Codex Agent

## 适用场景
- 按用户规定的实际曝光阈值切换现有 HLG10 预览，不通过图像亮度或 TYPE_LIGHT 决策。

## 禁止场景
- 禁止启用 EIS；如需稳定，仅允许 OIS 或算法层物理补偿。
- 不得绕过 HDR 相机/显示能力检查；不得在后台、校准、测距、统计或未就绪时切换。
- 绿光对比和防抖标定运行中也禁止切换，避免触发旧流程的状态清理。
- 不得将 HDR 预览效果等同于 YOLO 输入效果；模型仍按既有 SDR YUV 管线运行。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| 自动开关 | Boolean | false | 记忆用户选择 | 自动开启时禁用手动 HDR |
| lowerMs | Double | 0.3 | 曝光严格小于此值开启 | 有限正数，须小于上限 |
| upperMs | Double | 25.0 | 曝光严格大于此值开启 | 有限且大于下限 |
| cooldownSeconds | Int | 10 | 成功开启或关闭后的停判 | 1~3600 秒 |
| 决策轮询 | Long | 500 ms | 与主面板显示共用同一快照 | 停判、业务锁、故障暂停优先，不排队决定 |
| REQUIRED_SAMPLES | Int | 4 | 连续有效采样确认数 | 开、关均需四条；重复帧不累计 |
| 曝光时效 | Long | 2 秒 | 复用 ExposureReading.isFresh | 缺失、非正、过期均保持当前模式 |

## 复用方式
- AutoHdrPolicy.decide 返回 null 表示不切换，连续四条新帧满足条件才返回 true/false；未完成的切换由界面单独锁定。
- resetConfirmation 只清除确认，不修改停判截止时间。无效、乱序、条件不满足、参数/状态/相机/物理来源/会话代次变化均清除确认；重复帧不计数。
- canApplyConfirmedDecision 只复核已确认批次和当前曝光，不累计数据。能力检查期间跳过普通决策，业务锁或自动关闭仍作废批次；不得在异步复核中再次调用 decide。
- 仅在 onCameraOpened 确认目标会话成功时调用 transitionCompleted；切换申请、参数编辑、暂停恢复不能重新计算当前窗口。
- 停判结束重新收集接收时间不早于停判截止时刻的四条新曝光；关闭区间包含两端边界。后台时间计入单调时钟，恢复需重新确认。
- 能力检查结果使用参数版本、镜头、前台/业务状态和最新曝光复核；自动开关入口双向复核四条确认。
- 能力、相机或 GL 错误只设置 autoHdrFault 并清除批次，保留自动开关和偏好，不自动反复重试。用户点击“重新判断”时清除故障，重新累计四条；只允许就绪、业务空闲且没有切换时恢复，不改变剩余停判时间。
- 保留既有故障回退 SDR，它不属于正常曝光条件开关；校准中故障仍禁止回退 SDR。故障未清除时不允许自动决策或自动申请切换。
- 参数和开关复用既有 camera_screen_settings；不修改模型或其他相机偏好。关闭自动开关只取消自动控制，不强制关闭 HDR。

## 注意事项
- 当前使用连续四条采样确认，没有额外亮度滞回；HDR 改变曝光后仍可能在不同停判窗口间往返，需真机场景评估。这里指主面板每 500 ms 更新的有效采样，不是每个相机帧都参与判断；不同帧的曝光数值允许相同。
- 主面板专行显示成功开关、切换中、模式、停判倒计时、开启/关闭确认 n/4 及故障原因；日志 tag 为 AutoHdr，保留原始曝光用于排查边界。
- 修改参数只影响后续决策和下一次成功切换的停判时长，不缩短正在执行的窗口。

## 测试与验证
- 命令：`gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon`。
- AutoHdrPolicyTest：精确阈值、连续四条、去重/乱序、条件打断、跨会话隔离、异步复核、开/关 10 秒窗口、最新条件、缺失/过期数据、业务锁、自定义设置和非法输入。
- AutoHdrDialogInstrumentedTest：手动互斥、忙碌锁、参数校验、独立偏好存储及故障时自动开关保持/恢复就绪锁；HdrDetectionToggleInstrumentedTest 验证新入口的识别保持。
- 未覆盖风险：真实曝光变化、设备会话失败、亮暗环境长时间往返和校准精度，均需真机验证。
- 2026-09-20：同步主面板采样及故障暂停版本的 Debug 应用、设备测试包编译及 54 项单元测试通过（状态机 22 项）；新增停判后四次显示更新及故障恢复重新计数回归。ADB 无设备，面板和 HDR 真机测试未执行，本轮未重跑全量 Lint。

## 给 AI 的指令
优先复用本状态机与现有 HDR 切换流程。不得另建 HDR 管线，不得将停判改为持续累积旧条件。
