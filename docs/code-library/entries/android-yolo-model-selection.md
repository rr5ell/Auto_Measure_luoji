# android-yolo-model-selection

## 元数据
- 状态：active
- 技术栈：Android | Kotlin | Jetpack Compose | TensorFlow Lite
- 标签：camera, yolo, model-selection, lifecycle
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\detection\modelselection\YoloModelOption.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\detection\modelselection\YoloModelDialog.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
- 最后更新：2026-10-15
- 作者：Codex Agent

## 适用场景与参数
- 更多菜单选择内置V34/V29模型，默认V34，偏好键`yolo_model_asset`记录assets相对路径。
- 成功加载才保存，未知偏好回退V34；失败仍可重试或选择另一模型。
- 主面板通过`CollapsibleStatusPanel.modelNameText`常驻显示模型名；加载中/失败显示明确状态，折叠详情不隐藏模型名。
- 复用`YoloDetector`现有输入/解析/阈值，普通Bitmap及GL/YUV共用当前实例。

## 线程与生命周期
- 切换立即停止识别并清空旧框，GPU创建、推理、释放必须串行固定在`YoloInference`线程。
- 工作线程执行前检查`yoloModelRef`身份，主线程发布结果前检查当前模型与识别状态；取消协程不能当作native推理已立即停止。
- 退出页面将最终模型释放排入同一执行器后关闭调度器，不从主线程调用GPU close。

## 禁止事项
- 不将任意文件名当作可兼容模型；新增模型必须验证输入、输出、标签及预处理格式。
- 禁止启用EIS；如需稳定，仅允许OIS或算法层物理补偿。本功能不变更任何防抖状态。
- 不自动恢复识别、不覆盖失败模型的持久化偏好，不自动上传安装包。

## 测试与验证
- `:app:testDebugUnitTest`；`YoloModelAssetsTest`、`YoloModelDialogTest`、`YoloModelCameraTest`。
- 覆盖未知偏好、真实模型CPU推理、加载中不可切换、错误重试、普通/GL页面切换及重开页面恢复。
- 厂商GPU和HDR组合需真机验证；模拟器推理不能证明识别精度。

## 给AI的指令
优先复用上述代码路径，保持单线程所有权和两处旧结果检查，不重新设计模型加载器。
