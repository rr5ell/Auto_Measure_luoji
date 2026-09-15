package com.example.auto_measure_luoji.feature.camera.domain

import java.util.TreeMap

/** 每个补偿只属于一个相机帧；乱序回调不能把未来矩阵配给当前纹理。 */
internal class FrameTransformQueue<T>(private val capacity: Int = 90) {
    private val entries = TreeMap<Long, T>()

    init { require(capacity > 0) }

    @Synchronized
    fun offer(timestampNs: Long, value: T) {
        if (timestampNs <= 0L) return
        entries[timestampNs] = value
        while (entries.size > capacity) entries.pollFirstEntry()
    }

    @Synchronized
    fun exact(timestampNs: Long): T? {
        return entries[timestampNs]
    }

    @Synchronized
    fun clear() = entries.clear()
}
