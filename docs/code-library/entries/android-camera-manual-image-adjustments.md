# android-camera-manual-image-adjustments

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、Compose、Camera2、OpenGL ES
- 标签：camera2, exposure, contrast, preview, calibration
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\imageadjustment\ImageAdjustments.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\imageadjustment\ImageAdjustmentDialog.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\imageadjustment\PreviewContrast.kt
- 接入路径：Camera2Controller.kt、CameraScreen.kt、GlStabilizedPreviewView.kt（以上 feature/camera 对应目录）。
- 最后更新：2026-09-21
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

## 复用方式
- 复用 ExposureCompensationRange 进行界面档位量化，复用 Camera2Controller.setExposureCompensationEv 提交请求。
- TextureView 复用 PreviewContrast.colorMatrix，通过 TexturePreviewAdjustments 在 API 31+ 的 RenderEffect 或旧系统专属父容器应用。不能设置 TextureView.setLayerPaint 的滤镜，否则 getBitmap 也会改变；GL 使用 FRAGMENT 在最终上屏绘制应用，不修改 outputTarget。
- 入口已移至“更多 -> EV / 对比度”。开启逆光暗部增强时，TextureView 改用 TexturePreviewAdjustments 的 AGSL 效果，先暗部增强后对比度，不能重复叠加 ColorMatrix；详见 android-preview-shadow-enhancement。
- 对比度在当前 SDR/HLG 编码域按中点 0.5 调整，k=1 原样；无额外全帧 CPU 处理或几何变换。
- 面板期间暂停自动 HDR 判断；参数改变清空四条确认，不重置停判截止时间。

## 注意事项
- EV 会影响相机采集，YOLO 是否受益需实际验证；显示对比度不会改变 YOLO 输入。
- 更换镜头需使用新镜头范围；相机未就绪、校准及测量时锁定控制，颜色调整不能覆盖校准策略。
- 高对比度可能裁剪明暗细节，低对比度可能泛灰，不是局部色调映射算法。

## 测试与验证
- 命令：`gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon`。
- ImageAdjustmentsTest：范围/步进/非法值/校准恢复及矩阵一致性。
- ImageAdjustmentDialogTest：交互、还原、锁定和不支持曝光状态。
- GlFrameBindingInstrumentedTest：对比度屏幕像素变化、原始缓存不变及恢复默认。
- 未覆盖风险：厂商实机 HLG 显示、EV 生效差异与原有会话竞争。
- 2026-09-20：61 项单元测试、Debug 应用与测试包构建通过；模拟器面板 3 项和 GL 像素/帧映射 1 项通过。已检查实际页面截图及参数保存，未执行真机 HDR 验证。

## 给 AI 的指令
优先复用以上标准路径，只做最小适配；不要为对比度另建相机管线或修改模型输入。
