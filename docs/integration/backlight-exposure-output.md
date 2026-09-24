# 算法曝光输出与相机下发说明

作者：Codex Agent；日期：2026-09-24。

适用范围：当前项目的五区测光算法及Camera2接入。本文解释数据契约，不改变算法行为。取图与完整生命周期见[五区测光集成说明](backlight-metering.md)。

## 先看结论

**算法输出的`requestedEv`是带正负号、单位为EV的完整曝光补偿请求，类型是`Float?`；不是本次增加量，不是ISO或快门时间，也不是图像亮度。**

**唯一对外接法：将`output.requestedIndex`直接写入Camera2的`CONTROL_AE_EXPOSURE_COMPENSATION`，然后提交预览请求。** 下发的是`Int`整数档位，不是EV小数。`requestedEv`对外接入仅用于显示和日志，不作为下发参数。

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
| `requestedEv` | `Float?` | 同一请求对应的完整EV值，仅用于显示和日志，不参与相机下发 |
| `requestedIndex` | `Int?` | 唯一相机下发字段：完整整数档位，原样写Camera2曝光补偿键，不换算、不累加 |
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

手动基线已经是+0.4 EV，算法再增加0.4 EV时，输出是`requestedIndex=4`、`requestedEv=0.8`、`correctionEv=0.4`。直接下发整数4，不再加上旧档位或手动基线。这个例子假设硬件每档0.2 EV且范围足够。

### 正负号、零与空值

- `0.4f`本身就是正数，`-0.4f`本身就是负数，不需要另外传符号。`+0.40 EV`只是界面格式，不能作为相机数值参数。
- `requestedIndex == null`：本次不下发新命令，继续按`status/reason`等待或处理异常。禁止用`requestedIndex ?: 0`把空值变成零命令。
- `requestedIndex == 0`：必须下发整数0，表示零补偿，不是关闭自动曝光。
- `requestedIndex == -2`：原样下发整数-2；负号已经包含在数值中，不取绝对值，也不按决策重新添加符号。
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

## 统一对外下发链路

```text
BacklightMeteringAlgorithm.evaluate(...)
    输出 requestedIndex（已量化、限幅的完整整数档位）
        ↓
requestedIndex为null：不提交；非null：原样使用，包括0和负数
        ↓
builder.set(CONTROL_AE_EXPOSURE_COMPENSATION, requestedIndex)
        ↓
提交预览请求，之后用相机回传验证
```

初始化算法时仍须提供当前相机真实的`CONTROL_AE_COMPENSATION_RANGE`和`CONTROL_AE_COMPENSATION_STEP`，由算法完成量化与限幅。下发时不再乘除步进、四舍五入或将requestedEv转Int。切换相机/会话后重新读取能力并重建算法，不复用旧相机输出。

## 给接入方的下发示例

以下是唯一对外接法。要求有效会话、匹配的硬件能力、启用AE，并由宿主串行管理相机请求。

```kotlin
// output来自algorithm.evaluate/process；builder/session/handler由宿主管理。
output.requestedIndex?.let { index ->
    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index)
    session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
}
```

保留宿主已有的预览目标、回调和其他相机设置；只修改builder而不重新提交请求不会下发。生产接入还须处理会话关闭、请求失败与回传，这段仅展示字段映射。

不得再加上旧档位，也不通过`manualEv`、`correctionEv`或`requestedEv`重新计算下发值。关闭功能时`algorithm.reset(nowNs)`返回的requestedIndex也按同一方式执行；恢复值不一定为0。相机拒绝请求时停止闭环，按宿主生命周期恢复基线并重建/重置算法，不能让期望状态继续与实际相机状态分离。

requestedEv显示可以保留两位小数，例如0.6666667显示为+0.67 EV；控制只使用原始requestedIndex整数，禁止解析显示字符串下发。提交成功不证明设备已执行，仍需检查相机回传。

### 与现有App内部实现的区别

本次只统一对外接入文档，不修改运行代码。现有App内部仍通过CameraScreen把requestedEv交给Camera2Controller，再转换为整数档位提交；这是已有内部实现，不作为对外接入方案，接入方无需复制这层EV封装。当前控制器无有效会话时可能仅保留待设置值，不能将其返回成功等同设备执行成功。

## 与原相机显示的EV怎样比较

应比较原相机的曝光补偿与我们的**已请求/回传的完整补偿值**，不要与算法目标步幅0.4比较。两边确实采用相同补偿值时，其补偿单位相同；测光目标、曝光策略和图像处理仍可能不同，所以画面不保证一样亮。

仅凭原相机界面显示“0.4”，无法确认内部档位、显示舍入或私有处理。本文没有据此断言原相机实际下发值，也不将本项目变量名`actualEv`视为传感器曝光实测：控制器该值由请求档位乘步进计算。

建议排查时同时记录：相机标识、硬件范围/步进、目标步幅、`requestedEv`、`requestedIndex`、`correctionEv`、`decision/status/reason`及设备回传的补偿、ISO、曝光时间；不要只看显示小数或请求提交成功。

## 对照源码

- [算法与输出类型](../../app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/backlightbalance/BacklightMeteringAlgorithm.kt)：`Output`、`BacklightExposureRange`、`HighlightExposurePolicy`。
- [曝光调用入口](../../app/src/main/java/com/example/auto_measure_luoji/feature/camera/ui/CameraScreen.kt)：`output.requestedEv`传给控制器。
- [相机控制器](../../app/src/main/java/com/example/auto_measure_luoji/feature/camera/domain/Camera2Controller.kt)：`setExposureCompensationEv`、`applyExposureCompensation`、`publishExposureState`。

禁止为实现曝光调节启用EIS；如需稳定，仅允许OIS或算法层物理补偿。
