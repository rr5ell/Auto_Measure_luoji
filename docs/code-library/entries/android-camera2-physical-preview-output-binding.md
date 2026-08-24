# android-camera2-physical-preview-output-binding

## Metadata
- status: deprecated
- tech: Android | Kotlin
- tags: camera2, logical-multi-camera, physical-output, set-physical-camera-id, fusion-avoidance
- canonical_code_paths:
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\Camera2Controller.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\CameraScreen.kt
- related_docs:
  - E:\StudioProjects\Auto_Measure_luoji\README.md
  - E:\StudioProjects\Auto_Measure_luoji\CHANGELOG.md
  - E:\StudioProjects\Auto_Measure_luoji\ERROR_REPORT.md
- last_updated: 2026-08-24
- author: Codex Agent

## 废弃原因
- 2026-08-24 起，普通预览恢复父逻辑相机 Session，不再通过 `bindPhysicalPreview` 或 `OutputConfiguration.setPhysicalCameraId()` 绑定预览 Surface。
- 该历史方案会让普通预览更接近固定物理镜头流，不符合当前验证逻辑多摄/HAL 自动镜头选择的目标。

## 当前规则
- 普通预览只允许打开父逻辑 camera id，并保持 `strictPhysical=false`。
- 普通预览禁止传入或重新引入 `bindPhysicalPreview`。
- 普通预览禁止用 `OutputConfiguration.setPhysicalCameraId()` 绑定预览 Surface。
- 双摄校准 `strictPhysical=true` 路径仍允许使用 `OutputConfiguration.setPhysicalCameraId()` 绑定物理镜头，失败即报错。
- Camera2 相关修改仍禁止启用 EIS；如需稳定只能使用 OIS 或算法层物理补偿。

## 核心参数状态
| 参数名 | 当前状态 | 说明 |
|---|---|---|
| `bindPhysicalPreview` | removed | 普通预览物理输出绑定入口已删除 |
| `currentPhysicalPreviewBound` | removed | 普通预览不再维护物理输出绑定状态 |
| `currentPhysicalOutputId` | removed | 普通预览不再记录强制绑定的物理输出 ID |
| `strictPhysical` | active | 仅双摄校准物理 Session 使用 |

## 替代复用方式
- 普通预览复用 `android-camera2-control-zoom-ratio-preview-zoom` 中的逻辑相机 zoom 链路。
- 当前画面镜头显示复用 `android-camera2-active-physical-lens-display`，从逻辑相机 capture result 读取 active physical id。

## 测试与验证
- 测试命令：`.\gradlew.bat :app:compileDebugKotlin`
- 测试命令：`.\gradlew.bat :app:assembleDebug :app:assembleLaserCompare`
- 已覆盖边界：普通预览纯逻辑 Session、双摄 strict physical 路径保留、EIS 继续关闭。
- 未覆盖风险：不同厂商对逻辑多摄 active physical id 的回传频率和融合语义不同，需要真机观察画面和日志。

## 给 AI 的指令
不要复用本条目实现普通预览物理输出绑定。除非哥哥明确要求再次实验关闭融合，否则普通预览必须保持父逻辑相机 Session；`setPhysicalCameraId()` 只能保留在双摄校准 `strictPhysical=true` 路径。
