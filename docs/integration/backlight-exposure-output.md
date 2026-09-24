# 算法曝光输出与相机下发说明

作者：Codex Agent；日期：2026-09-24。

适用范围：当前项目的五区测光算法及Camera2接入。本文解释数据契约，不改变算法行为。取图与完整生命周期见[五区测光集成说明](backlight-metering.md)。

## 先看结论

**算法输出的`requestedEv`是带正负号、单位为EV的完整曝光补偿请求，类型是`Float?`；不是本次增加量，不是ISO或快门时间，也不是图像亮度。**

**最终写入相机`CONTROL_AE_EXPOSURE_COMPENSATION`的是`Int`整数档位，不是EV小数。** 算法同时输出对应的`requestedIndex`，接入方可直接使用它，或将`requestedEv`交给接受EV的相机封装，二选一即可。

例如手机每档为0.2 EV时，算法输出`requestedEv=0.4f`、`requestedIndex=2`，两者表示同一个请求：把相机曝光补偿设为+0.4 EV。数字2表示相对于零补偿的第2档，不是“再增加2档”。

## 三个容易混淆的数值

| 名称 | 代码字段 | 数据与单位 | 谁决定 |
|---|---|---|---|
| 每次希望调整多少 | `BacklightBalanceSettings.stepEv` | `Float`，EV；默认0.4 | 算法配置，可在参数面板修改 |
| 相机每个整数档位代表多少EV | `BacklightExposureRange.stepEv` | `Float`，EV/档；无固定通用值 | 从当前相机的`CONTROL_AE_COMPENSATION_STEP`读取 |
| 这次要把补偿设置成多少 | `Output.requestedEv` | `Float?`，EV；如0.4、0.8、-0.4 | 算法根据当前状态、判断结果及硬件限制计算 |

前两项虽然都叫`stepEv`，含义不同。0.1、0.2、1/3 EV/档只是硬件步进的示例，不是App提供的硬件步进选项，也不能用算法默认0.4覆盖设备上报的步进。

这里的EV指**自动曝光补偿量**，不是由光圈、ISO、快门反推的场景曝光值。正值请求比零补偿更亮，负值请求更暗；是否已执行、最终ISO和快门是多少，需要检查相机返回结果。

## 输出字段怎么用

| 字段 | 类型 | 含义与处理 |
|---|---|---|
| `requestedEv` | `Float?` | 本次新的完整EV补偿请求；交给接受EV的封装，不再叠加旧值或手动基线 |
| `requestedIndex` | `Int?` | 同一请求的整数档位；适合直接写Camera2曝光补偿键，不再除以步进 |
| `currentEv` | `Float` | 算法期望的当前完整补偿值，包含本次请求；不证明相机已执行 |
| `correctionEv` | `Float` | 相对于量化后手动基线的自动补偿，不是应直接写给相机的完整值 |
| `decision` | 枚举 | 本次动作，如增加、降低、保持、恢复基线、无法判断 |
| `status / reason` | 枚举 / 字符串 | 等待、保护、边界或输入无效等原因；不要通过解析中文文案驱动控制 |

基本关系：

```text
完整补偿EV = 整数档位 × 硬件每档EV
currentEv = 量化后手动基线EV + correctionEv
requestedEv非null时，requestedEv = currentEv
```

手动基线已经是+0.4 EV，算法再增加0.4 EV时，输出是`requestedEv=0.8`、`correctionEv=0.4`。应设置+0.8 EV，不能把0.8再加到旧值上。这个例子假设硬件每档0.2 EV且范围足够。

### 正负号、零与空值

- `0.4f`本身就是正数，`-0.4f`本身就是负数，不需要另外传符号。`+0.40 EV`只是界面格式，不能作为相机数值参数。
- `requestedEv == null`：本次不下发新命令，继续按`status/reason`等待或处理异常；不等于0 EV。
- `requestedEv == 0f`：明确要求设置零补偿，必须下发；零补偿仍然可以由AE自动调整ISO和快门，不是关闭自动曝光。
- 增加/降低是相对于上一次期望值：从-0.6变为-0.2是增加，虽然新请求仍为负数。不要对结果取绝对值后按`decision`重新拼符号。
- 恢复基线按输出值执行。手动基线不是0时，恢复也不一定返回0。

## 默认0.4最终变成多少

算法先将目标步幅向上量化成可执行的整数档数，再更新当前档位；接近自动或硬件边界时截短。

```text
单次移动档数 = max(1, ceil(目标步幅 / 硬件每档EV - 0.001))
名义单次实际步幅 = 单次移动档数 × 硬件每档EV
```

0.001只抵消浮点边界误差。相对基线的默认自动范围仍为-2/3..+1 EV，上下限向硬件可用档位的范围内取整，不保证每次恰好0.4 EV。

下表假设：手动基线0、使用新默认配置、硬件范围足够、每次均满足同方向判断与确认条件。

| 硬件每档EV | 从0连续增加：完整EV / 下发整数 | 从0连续降低：完整EV / 下发整数 |
|---|---|---|
| 0.1 | +0.4 / 4 → +0.8 / 8 → +1.0 / 10 | -0.4 / -4 → -0.6 / -6 |
| 0.2 | +0.4 / 2 → +0.8 / 4 → +1.0 / 5 | -0.4 / -2 → -0.6 / -3 |
| 1/3 | 约+0.6667 / 2 → +1.0 / 3 | 约-0.6667 / -2 |

这是连续单方向的示例，不是固定档位列表。中途反向调整会出现其他硬件允许的补偿值；到边界后不再发出同方向新请求。已有保存步幅不会被新默认覆盖，需修改参数或恢复默认后保存。

## 本项目实际下发链路

```text
BacklightMeteringAlgorithm.evaluate(...)
    输出 requestedEv（已按硬件量化的完整补偿值）
        ↓
CameraScreen：cameraController.setExposureCompensationEv(ev)
        ↓
Camera2Controller.applyExposureCompensation
    requestedSteps = roundToInt(ev / 设备每档EV)
    clampedSteps = 将requestedSteps限制到设备整数档位范围
        ↓
builder.set(CONTROL_AE_EXPOSURE_COMPENSATION, clampedSteps)
        ↓
提交预览请求，之后用相机回传验证
```

注意两处取整不同：算法对**单次目标步幅向上取整**；控制器对**完整EV转换档位四舍五入**。算法输出已落在硬件档位上，只要两边使用同一个相机范围/步进，控制器会得到相同的`requestedIndex`。

因此，直接调用控制器设置0.4 EV，与把算法目标步幅设置0.4 EV不是同一件事。例如硬件每档1/3 EV：前者四舍五入到1档（约0.3333 EV）；后者从零首次增加时算法向上量化到2档（约0.6667 EV）。

当前控制器无预览builder/session时会保留待设置EV并提前返回；方法返回成功不能单独证明相机已经执行。算法也没有读取实际ISO/快门来确认命令执行，接入方必须管理会话有效性与回传。

## 给接入方的下发示例

以下为两种替代接法，不要同时执行。均要求有效会话、匹配的硬件能力、启用AE，并由宿主串行管理相机请求。

**方式一：直接使用整数输出。**

```kotlin
// output来自algorithm.evaluate/process；builder/session/handler由宿主管理。
output.requestedIndex?.let { index ->
    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index)
    session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
}
```

保留宿主已有的预览目标、回调和其他相机设置；只修改builder而不重新提交请求不会下发。生产接入还须处理会话关闭、请求失败与回传，这段仅展示字段映射。

**方式二：使用接受EV的相机封装。本项目采用此方式。**

```kotlin
output.requestedEv?.let { ev ->
    cameraController.setExposureCompensationEv(ev).getOrThrow()
}
```

不得再加上旧EV、`manualEv`或`correctionEv`。相机拒绝请求时停止闭环，按宿主生命周期恢复基线并重建/重置算法，不能让期望状态继续与实际相机状态分离。

显示可以保留两位小数，但控制必须使用原始`Float`或`Int`。例如0.6666667显示为+0.67 EV只是格式化，不能先转成显示字符串再解析下发。独立集成推荐整数输出，避免多一层EV换算。

## 与原相机显示的EV怎样比较

应比较原相机的曝光补偿与我们的**已请求/回传的完整补偿值**，不要与算法目标步幅0.4比较。两边确实采用相同补偿值时，其补偿单位相同；测光目标、曝光策略和图像处理仍可能不同，所以画面不保证一样亮。

仅凭原相机界面显示“0.4”，无法确认内部档位、显示舍入或私有处理。本文没有据此断言原相机实际下发值，也不将本项目变量名`actualEv`视为传感器曝光实测：控制器该值由请求档位乘步进计算。

建议排查时同时记录：相机标识、硬件范围/步进、目标步幅、`requestedEv`、`requestedIndex`、`correctionEv`、`decision/status/reason`及设备回传的补偿、ISO、曝光时间；不要只看显示小数或请求提交成功。

## 对照源码

- [算法与输出类型](../../app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/backlightbalance/BacklightMeteringAlgorithm.kt)：`Output`、`BacklightExposureRange`、`HighlightExposurePolicy`。
- [曝光调用入口](../../app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt)：`output.requestedEv`传给控制器。
- [相机控制器](../../app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/Camera2Controller.kt)：`setExposureCompensationEv`、`applyExposureCompensation`、`publishExposureState`。

禁止为实现曝光调节启用EIS；如需稳定，仅允许OIS或算法层物理补偿。
