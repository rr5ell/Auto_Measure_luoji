# android-window-outdoor-brightness

## 元数据
- 状态：active
- 技术栈：Android、Kotlin、Compose
- 标签：display, brightness, lifecycle, error-handling
- 标准代码路径：
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\domain\outdoorbrightness\OutdoorBrightnessController.kt
  - E:\StudioProjects\Auto_Measure_luoji\app\src\main\java\com\example\auto_measure_luoji\feature\camera\ui\outdoorbrightness\OutdoorBrightnessControl.kt
- 最后更新：2026-09-30
- 作者：Codex Agent

## 适用场景
- 页面内可逆的窗口最高亮度请求，以及无法控制HBM时的真实失败提示。

## 禁止场景
- 不把screenBrightness=1.0或属性回读当作面板HBM激活、实测nit或已绕过热限制。
- 不写系统亮度、不请求WRITE_SETTINGS、不反射厂商私有接口、不root写设备节点，不持久化高亮。
- 禁止启用EIS；如需稳定，仅允许OIS或算法层物理补偿。该功能不更改相机曝光、HDR或防抖。

## 核心参数
| 参数 | 值 | 说明 |
|---|---|---|
| 窗口最高亮度请求 | 1f | 公开窗口亮度上限，不是HBM启用证据 |
| 原值 | -1f或0..1 | 首次请求前保存，重复点击不覆盖；-1代表跟随系统 |
| HBM能力 | 未接入厂商控制和回读接口 | 明确失败，不猜测具体的温度/电量/光照限制 |

## 复用方式
- “更多”按钮只打开失败详情，不自动改变亮度；用户显式点击独立操作后调用requestMaximum。
- 控制器通过读写lambda隔离Android窗口；UI线程串行调用。写入及回读失败以Result返回，写入可能部分生效时保留原值以便恢复。
- 页面持有remember状态，弹窗关闭不销毁控制器；ON_PAUSE、ON_DESTROY、onDispose尝试恢复原亮度。重新进入前台不自动再次增亮。
- 恢复只在窗口仍为本功能设置的1f时写回，避免覆盖其他控制器的改动；恢复失败保留原值以便重试。同值1f的外部写入无法区分归属，不应并行运行其他窗口亮度控制器。

## 测试与验证
- 测试命令：gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileLaserCompareKotlin --offline --no-daemon。
- JVM覆盖：原值/系统默认恢复、重复请求、无窗口、无效原值、拒绝写入、部分写入异常、恢复重试与外部亮度改动。
- 最新结果：149项JVM单测、2项模拟器Compose测试通过；Debug应用/测试APK和laserCompare Kotlin编译通过。Compose覆盖HBM失败展示、仅显式替代操作修改亮度、恢复与无窗口失败。
- 相机页补充检查因模拟器UI树/截图异常未完成；真实窗口后台恢复与厂商面板行为仍需真机验证。
- 未覆盖风险：厂商亮度策略、温控及真实HBM激活；不声称通过窗口请求验证这些状态。

## 参考依据
- [Android窗口亮度接口](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness)：窗口亮度覆盖范围为0..1，负值采用系统偏好。

## 给AI的指令
复用控制器与UI状态，不用另一个固定成功提示代替能力验证。后续若接入厂商HBM，必须提供真实控制、状态回读及失败映射，不把窗口最高亮度回退包装为HBM成功。
