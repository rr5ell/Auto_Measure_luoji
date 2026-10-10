# android-preview-save-gallery

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、Compose、OpenGL ES、AGSL
- 标签：camera, preview, bitmap, gallery, mediastore
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\previewcapture\PreviewSaveControl.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\previewcapture\PreviewBitmapRenderer.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\backlightshadow\TexturePreviewAdjustments.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\GlStabilizedPreviewView.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\calibration\util\GallerySaver.kt
- 相关文档：E:\StudioProjects\Auto_Measure_luoji\README.md
- 最后更新：2026-10-10
- 作者：Codex Agent

## 适用场景
- 用户主动保存当前预览图像，保留显示变换和实际生效的颜色增强；不保存界面与检测标记。

## 禁止场景
- 禁止启用EIS；如需稳定，仅允许OIS或算法层物理补偿。
- 保存增强图不得替换测光、校准、识别输入。不能把PNG称为传感器原片或10位HDR照片。

## 核心参数
| 参数 | 默认 | 说明 |
|---|---|---|
| includeDisplayEffects | false | GL仅保存时传true，保持旧调用默认不增强 |
| GL取帧超时 | 1500 ms | 等待新的完成渲染帧，无新帧报错 |
| 输出尺寸 | 预览尺寸 | GL保持比例，宽<=2160、高<=3840，不放大小图 |
| 输出格式/目录 | PNG / Pictures/AutoMeasure | HDR经HLG到sRGB映射转SDR |

## 复用方式
- 顶部唤醒右侧使用PreviewSaveButton：固定尺寸系统保存图标、悬停/长按提示与无障碍名称；更多菜单不重复入口。
- 主线程调用普通/GL capturePreviewBitmap，调用者拥有返回Bitmap；后台IO调用GallerySaver.savePreview，finally回收。
- TexturePreviewAdjustments记录实际生效设置，保存使用独立shader避免并发uniform变化；离屏ImageReader只连接HardwareRenderer，不占用相机流。
- GallerySaver保留原savePng入口及LaserNative相册，新增savePreview流式编码；编码/发布失败删除新记录。
- API29+采用MediaStore待发布记录；API24~28必须先取得WRITE_EXTERNAL_STORAGE权限。不请求读照片或全盘权限。

## 注意事项
- getBitmap含TextureView显示矩阵，不再二次旋转/缩放。GL在既有几何变换结果上执行同一上屏shader，使用原viewport邻域尺度后再缩放。
- 校准时禁用保存入口；模式/会话变更丢弃结果。拍到的是点击时附近的预览帧，不保证精确到点击瞬间的时间戳。
- HDR转换并非厂商屏幕色调映射的复制，须真机核对；保存不修改HDR开关、AE、ISO或快门。
- Android官方离屏RenderEffect方法：[迁移指南](https://developer.android.com/guide/topics/renderscript/migrate)。

## 测试与验证
- 构建：gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileLaserCompareKotlin --offline --no-daemon。
- 回归：TextureShadowInstrumentedTest、PreviewCaptureInstrumentedTest、PreviewGalleryInstrumentedTest、PreviewSaveControlTest、PreviewSaveCameraTest。
- 149项单测、上述7项模拟器回归及Debug/laserCompare构建通过；包含普通/GL相机页到相册完整流程。
- GPU冷启动先完成预热再测取帧超时；UI/Toast使用真实主线程，不依赖Compose测试调度线程。
- 未覆盖厂商HLG/真实相机场景、旧系统实机权限流程；未安装真机或发布蒲公英。

## 给 AI 的指令
复用既有shader与GallerySaver，不复制另一套亮度算法，不用窗口截图混入操作控件，不更改校准取帧默认值。
