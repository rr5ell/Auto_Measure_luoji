# 五区测光单文件集成说明

作者：Codex Agent；日期：2026-09-23。

## 交付文件

[BacklightMeteringAlgorithm.kt](../../app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/backlightbalance/BacklightMeteringAlgorithm.kt)

算法源码只需要这一个文件，接入方可修改文件首行的包名。依赖Kotlin标准库（1.9或以上）与JDK的`java.nio.ByteBuffer`，不依赖Android SDK、Compose、本项目的相机控制器或偏好文件。本项目使用Kotlin 2.0.21；算法文件已在不加载Android及其他项目源码的JVM环境编译并执行测试。

文件已合并原`HighlightStatistics.kt`、`RegionalMetering.kt`、`HighlightExposurePolicy.kt`和`BacklightBalanceSettings.kt`；这些类型仍在单文件内，避免改动原有统计、决策及参数面板的数据接口。推荐集成入口是`BacklightMeteringAlgorithm`，不要绕过入口直接调用低层策略而遗漏断流恢复和采样节流。

## 算法职责

### TextureView接入方式

本App普通预览已改用`TextureMeteringSource`：每500ms从TextureView读取最长边320像素的ARGB小图，再调用单文件中的`HighlightStatistics.sampleArgb`，把结果交给同一个`algorithm.evaluate`。GL/HDR预览仍使用原SDR YUV分析流，主面板显示实际来源；不对HLG截图直接套用SDR阈值，也不静默混用两路数据。

```kotlin
// pixels：从不透明SDR Bitmap.getPixels取得，按行连续存储，每项0xAARRGGBB。
// frameReceivedNs：这一张新画面到达的单调时刻，不能每次截同一张图就更新。
val statistics = HighlightStatistics.sampleArgb(pixels, width, height, frameReceivedNs)
val output = algorithm.evaluate(statistics, nowNs)
output.requestedIndex?.let { index -> /* 宿主向相机提交整数曝光补偿档位 */ }
```

- `pixels.size`必须等于`width*height`，不透明8-bit SDR RGB；空图、透明图、非法尺寸返回null。
- RGB转换定义为`round(16+219*(0.299R+0.587G+0.114B)/255)`，黑16、白235；这是有限范围亮度代理，并非相机原始Y或物理照度。原默认阈值保留，真机效果需要复核。
- TextureView截图已按输出方向排列，传旋转0；YUV路径仍按传感器方向旋转区域标签。
- `TextureMeteringSource.onFrame`在`onSurfaceTextureUpdated`记录新帧身份和单调接收时间。相同帧不刷新年龄；冻结超过1.5秒输入null，算法撤销自动补偿。新曝光循环只接收开始之后更新的画面，视图销毁须调用`invalidate`。
- 取图在主线程、统计在Default线程；Bitmap及时释放。截图不含检测框/按钮，也不含View的RenderEffect显示增强，避免显示提亮再反馈控制相机。不保证父布局变换或窗口裁剪与取图一致，不能将此称为完整屏幕截图。
- TextureView适配器依赖Android，放在宿主UI目录；交付给其他平台的算法仍只需原单文件，新增ARGB接口不引入Android依赖。

```text
8-bit SDR Y平面 + 图像布局 + 单调时钟时间
    -> 最多64×48点稀疏采样
    -> 上/下/左/右/中心五区统计、综合亮度G、P20、P99
    -> 判断条件、中心保护、连续确认、稳定等待、硬件限幅
    -> Output：统计数据 + 状态 + 可选的新EV请求
    -> 宿主向相机提交EV，由AE选择实际ISO/快门
```

它不是图像增强滤镜，不返回新的图像，不设置ISO/快门/光圈，不识别旗杆，也不恢复已经过曝丢失的细节。局部色调映射不包含在此交付文件中。禁止启用EIS；稳定需求只使用OIS或算法层物理补偿。

## 初始化输入

```kotlin
val algorithm = BacklightMeteringAlgorithm(
    range = BacklightExposureRange(minimum = -9, maximum = 9, stepEv = 1f / 3f),
    manualEv = 0f,
    settings = BacklightBalanceSettings(),
)
```

- `minimum/maximum`是硬件整数档位，不是EV：上述范围对应-3..+3 EV。
- `stepEv`是每个硬件档位的EV值；由设备能力读取，不能假设所有设备都是1/3 EV。
- `manualEv`是用户曝光补偿基线，不是快门测得的曝光值；初始化时按硬件步进量化并限制到设备范围。
- `settings`包含14项可调判断参数。类型、单位、默认值与合法范围已逐项写在源码KDoc。中心阈值必须满足`deepShadow <= protectBelow < releaseAt`。
- 使用不可变配置；改变参数、基线、镜头或会话时需要恢复旧基线，再创建新实例。
- 不支持曝光补偿时可以传默认`BacklightExposureRange()`，输出为`EXPOSURE_UNSUPPORTED`，不会请求曝光。

## 每帧输入格式

调用`process(frame: FrameInput?, nowNs: Long): Output`。

| 字段 | 类型 | 数据格式/单位 |
|---|---|---|
| `yPlane` | `ByteBuffer` | 8-bit SDR亮度，每个Y按无符号0..255解释；只读与直接缓冲区均可 |
| `width / height` | `Int` | Y平面宽高，像素数；必须大于0，不是控件尺寸 |
| `rowStrideBytes` | `Int` | 相邻行首的字节跨度，包含行尾填充 |
| `pixelStrideBytes` | `Int` | 相邻Y像素的字节跨度，通常为1 |
| `sampledAtNs` | `Long` | 取得/统计图像时的单调时钟时间，纳秒 |
| `rotationDegrees` | `Int` | 源图到输出方向的顺时针整90度旋转；0/90/180/270，也可归一化负数或360 |
| `nowNs` | `Long` | 本次决策时间，必须与sampledAtNs同一单调时钟基准 |

像素地址为`buffer.position() + y * rowStrideBytes + x * pixelStrideBytes`，读取不能超过`limit()`。连续灰度数组可用`ByteBuffer.wrap(bytes)`，设`rowStrideBytes=width`、`pixelStrideBytes=1`。

算法不改变缓冲区位置、不持有源图；同步返回后可关闭相机Image。禁止直接传JPEG、RGB、10-bit P010/HLG或RAW。Android的YUV_420_888应取`image.planes[0]`及其实际布局字段。

建议在收到相机帧时记录`elapsedRealtimeNanos()`，决策时用同一个时钟。不得将毫秒、Unix时间或未经核实的`Image.timestamp`混用。方向只改变四边区域标签，不包含前摄镜像、动态裁剪或屏幕坐标映射。

## 输出格式

| 字段 | 类型 | 含义 |
|---|---|---|
| `statistics` | `HighlightStatistics?` | 有效帧的不可变五区统计；无效/跳帧/不支持时为null，结合status判断 |
| `requestedEv` | `Float?` | 要提交的完整EV补偿值；不是增量；null表示本次不要写相机 |
| `requestedIndex` | `Int?` | 同一请求的硬件整数档位，EV=index×stepEv；与requestedEv二选一提交 |
| `currentEv` | `Float` | 算法期望的当前完整EV，不是CaptureResult实际曝光 |
| `correctionEv` | `Float` | currentEv减去量化后手动基线，负数为自动降曝 |
| `subjectProtected` | `Boolean` | 中心暗部保护是否生效 |
| `status` | `Status` | 稳定状态码，适合业务判断 |
| `reason` | `String` | 中文状态说明，供显示/日志，禁止解析文案作为业务协议 |

**`requestedEv=0f`意味着提交零补偿，不等于null。算法已更新内部期望值，但未操作相机，宿主必须成功执行请求。**

统计字段：
- `regions.top/bottom/left/right/center`各包含`mean`（各端裁去5%后的平均Y）、`brightFraction`（未截尾样本中Y>=235的比例0..1）、`count`（未截尾采样点数，不是区域总像素数）。
- `regions.weightedLuma`为综合亮度G：中心40%、四边各15%的几何平均；不再按区域面积加权。每区mean先取至少1，因此全黑输入的G为1。
- `regions.weightedHighlights`为五区高亮占比的相同权重算术平均。判断时调用`hasBroadClipping(settings)`使用当前参数；兼容属性`broadClipping`仅表示默认参数的判断。
- `centerDarkLuma`为中心未截尾采样的P20；`percentile99`为全图未截尾采样的P99，均为0..255的Y编码值，不是lux或RAW饱和率。
- `brightFraction`为全图采样高亮比例；`sampledAtNs`为输入采样时间。小图缺失某一区时低层采样可返回诊断数据，但统一入口拒绝其驱动曝光。

状态码：

| 状态 | 宿主行为 |
|---|---|
| `EXPOSURE_UNSUPPORTED` | 不提交EV |
| `INVALID_SAMPLE` | 等待有效五区数据，目前没有自动补偿要撤销 |
| `RESTORE_BASELINE` | 提交返回的基线EV/档位 |
| `SKIPPED` | 重复、乱序或间隔不足500ms，不计确认次数，不提交EV |
| `HOLD` | 稳定等待、确认不足、死区、保护或到边界，保持曝光 |
| `UPDATE_EXPOSURE` | 提交返回的新EV/档位 |

## 可运行的纯Kotlin示例

```kotlin
import java.nio.ByteBuffer
import com.example.auto_measure_luoji.feature.camera.domain.backlightbalance.*

fun main() {
    val algorithm = BacklightMeteringAlgorithm(BacklightExposureRange(-9, 9, 1f / 3f))
    for (ms in 0L..2500L step 500) {
        val ns = ms * 1_000_000L
        val frame = BacklightMeteringAlgorithm.FrameInput(
            ByteBuffer.wrap(ByteArray(64 * 48) { 60 }),
            width = 64, height = 48, rowStrideBytes = 64, pixelStrideBytes = 1,
            sampledAtNs = ns, rotationDegrees = 0,
        )
        val result = algorithm.process(frame, ns)
        println("t=$ms status=${result.status} EV=${result.requestedEv} index=${result.requestedIndex}")
    }
}
```

默认首次等待1.5秒；0/500/1000ms不计确认，1500/2000/2500ms累计三条。2500ms输出`UPDATE_EXPOSURE`、约`+0.33333334 EV`、`index=1`。这里是固定灰度模拟数据，未模拟相机收到EV后的画面反馈，不代表真实相机会一直加曝。

## 宿主接入与状态管理

1. 单个相机会话只创建一个实例，串行调用。每帧可调用`process`，入口限制新统计间隔至少500ms；不要每帧重建，否则无法完成等待与确认。
2. 独立分析线程可调用同文件的`HighlightStatistics.sample`，再把快照交给曝光线程`evaluate(snapshot, nowNs)`。二者是同一算法的两种接入方式，不要重复提交同一帧；本项目使用这一方式保持采样/控制线程解耦。
3. 宿主启用AE后，把`requestedIndex`下发为`CONTROL_AE_EXPOSURE_COMPENSATION`；或者使用接受EV的封装提交`requestedEv`，不要把EV浮点数直接当整数档位。
4. 当图像丢失，仍需每约500ms调用`process(null, nowNs)`或`evaluate(null, nowNs)`，否则算法不会自行运行，也无法发出基线恢复请求。过期超过1.5秒、布局非法或缺少五区都会触发保护。
5. `pause(nowNs)`只清确认、重新等待，保留当前EV；退出/关闭功能应执行`reset(nowNs)`并下发返回的基线请求。场景切换涉及手动曝光/校准等时，宿主负责控制优先级，禁止多个EV控制器同时改曝光。
6. 算法假定每个非null请求都被及时成功提交。若相机拒绝，停止循环、恢复基线并重置或重建算法；不要把算法期望值当成真实采集结果。
7. 基线、参数、镜头变化时恢复旧实例对应基线，再在新的硬件范围下创建实例。旧会话帧须在宿主侧按会话代际过滤，不能混入新会话。

## 算法边界

- 中心宽高各占30%，面积9%；其余四区各22.75%。使用归一化距离划分，不随图像宽高比改变面积比例。
- `e=log2(targetLuma/G)`是编码亮度反馈误差，不是ISO/快门推导的物理EV。
- 实际单次步幅按硬件向上取整，增减上限向内取整。粗档的实际死区至少为步幅的0.6倍。
- 深暗中心恢复已有负补偿的优先级高于P99/大范围高光阻止条件；仍要完成连续确认，完整条件顺序在文件内保留。
- 原低层策略类仅清计数，不负责坏数据恢复；公开入口`process/evaluate`统一处理恢复。接入方应使用公开入口。
- 禁止把SDR统计亮度等同照度、RAW饱和率或HDR物理亮度，也不能保证该策略改善所有机型、场景或模型识别率。
