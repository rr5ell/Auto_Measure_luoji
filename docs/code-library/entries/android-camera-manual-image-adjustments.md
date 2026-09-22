# android-camera-manual-image-adjustments

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、Compose、Camera2、OpenGL ES
- 标签：camera2, exposure, contrast, preview, calibration
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\imageadjustment\ImageAdjustments.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\manualsensor\ManualExposure.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\manualsensor\ManualSensorRequest.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\imageadjustment\ImageAdjustmentDialog.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\imageadjustment\PreviewContrast.kt
- 接入路径：Camera2Controller.kt、CameraScreen.kt、GlStabilizedPreviewView.kt（以上 feature/camera 对应目录）。
- 最后更新：2026-09-22
- 作者：Codex Agent

## 适用场景
- 为当前镜头提供手动曝光补偿及独立于模型输入的预览对比度。

## 禁止场景
- 禁止启用 EIS；如需稳定，仅允许 OIS 或算法层物理补偿。
- 禁止对模型/绿光取帧或检测框直接套用显示对比度，禁止改变采样坐标和帧时间戳。
- 不得把补偿请求值当作 CaptureResult 实测值，不能承诺 HLG 提亮主体或与原厂相机效果一致。

## 核心参数
| 参数名 | 类型 | 默认值 | 说明 | 边界条件 |
|---|---|---|---|---|
| manual_exposure_ev | Float | 0 | 相机曝光补偿偏好 | 按当前镜头范围/步进量化；非法值回退 |
| preview_contrast | Float | 1 | 仅显示对比度 | 0.5~2；非法值回退 |
| 校准覆盖 | ImageAdjustments | -2 EV、1 对比度 | 临时覆盖，不写偏好 | 退出后恢复手动值 |
| ManualExposure | 可空 | null（自动） | 手动快门、固定 ISO、可写光圈 | 不持久化；校准/后台/会话重建清除 |
| timeNs | Long | 当前新鲜回传 | Camera2 纳秒，显示除以 1e6 为毫秒 | 当前硬件曝光范围与最大帧时长交集 |
| aperture | Float? | 当前回传或 null | 仅支持多个可写档位时指定 | 固定光圈不下发请求 |

## 复用方式
- 复用 ExposureCompensationRange 进行界面档位量化，复用 Camera2Controller.setExposureCompensationEv 提交请求。
- TextureView 复用 PreviewContrast.colorMatrix，通过 TexturePreviewAdjustments 在 API 31+ 的 RenderEffect 或旧系统专属父容器应用。不能设置 TextureView.setLayerPaint 的滤镜，否则 getBitmap 也会改变；GL 使用 FRAGMENT 在最终上屏绘制应用，不修改 outputTarget。
- 入口已移至“更多 -> EV / 对比度”。开启逆光暗部增强或局部色调映射/Retinex 时，TextureView 改用 TexturePreviewAdjustments 的 AGSL 效果，先增强后对比度，不能重复叠加 ColorMatrix；详见 android-preview-shadow-enhancement 和 android-preview-local-tone-retinex。
- 对比度在当前 SDR/HLG 编码域按中点 0.5 调整，k=1 原样；无额外全帧 CPU 处理或几何变换。
- 面板期间暂停自动 HDR 判断；参数改变清空四条确认，不重置停判截止时间。
- 手动快门复用 ManualSensorRequest，检查 MANUAL_SENSOR/写入键，开启时 Camera2Controller.enableManualExposure 从当前会话新鲜回传初始化。帧时长为快门和 33.333333 ms 的较大值，受设备上限约束；实际帧率仍可能受流配置和厂商限制。
- 快门期间 AE_OFF，原 ISO 固定；EV/五区闭环/自动 HDR 暂停，CenterMeteringRequest 的 allowTapAe=false，点击只对焦。关闭时先恢复模板再应用当前测光区域；禁止把手动 AE_OFF 保存成点击测光的恢复模式。

## 注意事项
- EV 会影响相机采集，YOLO 是否受益需实际验证；显示对比度不会改变 YOLO 输入。
- 更换镜头需使用新镜头范围；相机未就绪、校准及测量时锁定控制，颜色调整不能覆盖校准策略。
- 高对比度可能裁剪明暗细节，低对比度可能泛灰，不是局部色调映射算法。
- 手动快门改变采集和模型输入；长曝光降帧和运动模糊不可通过显示处理避免。严格物理校准不开放手动控制；进入校准恢复 AE，不记忆/自动重新启用手动快门。

## 测试与验证
- 命令：`gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon`。
- ImageAdjustmentsTest：范围/步进/非法值/校准恢复及矩阵一致性。
- ImageAdjustmentDialogTest：交互、还原、锁定和不支持曝光状态。
- ManualExposureTest：对数滑块往返、非法/越界值、固定/可变光圈及长快门帧时长。
- CenterMeteringInstrumentedTest：追加手动 AE_OFF、点击 AF 保留手动值、退出模板恢复以及 1/10/20 ms 实际回传断言；需连接支持手动传感器的真机执行。
- GlFrameBindingInstrumentedTest：对比度屏幕像素变化、原始缓存不变及恢复默认。
- 未覆盖风险：厂商实机 HLG 显示、EV 生效差异与原有会话竞争。
- 2026-09-20：61 项单元测试、Debug 应用与测试包构建通过；模拟器面板 3 项和 GL 像素/帧映射 1 项通过。已检查实际页面截图及参数保存，未执行真机 HDR 验证。
- 2026-09-22：111 项单元测试、Debug/测试 APK 和 laserCompare 编译通过；模拟器 5 项参数面板、1 项底层请求/实际回传测试通过，实际页面 19.805/244.075 ms 调整、固定 ISO 和恢复自动已验证。手机未连接，三星、GL/HDR 组合、可变光圈硬件及长期稳定性仍待验证。

## 给 AI 的指令
优先复用以上标准路径，只做最小适配；不要为对比度另建相机管线或修改模型输入。
