package com.example.auto_measure_luoji.feature.camera.domain.outdoorbrightness

/** 只管理窗口亮度请求；窗口属性回读不是面板HBM状态或实测亮度。由UI线程串行调用。 */
class OutdoorBrightnessController(
    private val readBrightness: () -> Float,
    private val writeBrightness: (Float) -> Unit,
) {
    private var originalBrightness: Float? = null
    val hasOverride: Boolean get() = originalBrightness != null

    fun requestMaximum(): Result<Unit> = runCatching {
        if (originalBrightness == null) {
            val previous = readBrightness()
            check(previous.isFinite() && (previous == -1f || previous in 0f..1f)) { "窗口原亮度无效：$previous" }
            originalBrightness = previous
        }
        // 写入抛异常也保留恢复值，避免部分生效后丢失原设置。
        writeBrightness(1f)
        check(readBrightness() == 1f) { "窗口未接受最高亮度请求" }
    }

    fun restore(): Result<Unit> = runCatching {
        val previous = originalBrightness ?: return@runCatching
        // 其他控制器已更改亮度时不覆盖它；写回失败保留恢复值以便重试。
        if (readBrightness() == 1f) {
            writeBrightness(previous)
            check(readBrightness() == previous) { "窗口未接受亮度恢复请求" }
        }
        originalBrightness = null
    }

    companion object {
        const val HBM_FAILURE = "HBM 开启失败：当前应用未接入此设备的厂商 HBM 控制及状态回读接口，无法请求或确认进入。"
        const val HBM_DETAIL = "这不代表屏幕不支持 HBM。未读取到具体限制，不能判定为光照不足、温度或电量限制。"
        const val MAXIMUM_REQUESTED = "窗口最高亮度已请求；HBM 状态未确认"
    }
}
