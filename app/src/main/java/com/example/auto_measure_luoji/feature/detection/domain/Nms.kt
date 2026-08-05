package com.example.auto_measure_luoji.feature.detection.domain

import android.graphics.RectF
import com.example.auto_measure_luoji.feature.detection.model.Detection
import kotlin.math.max
import kotlin.math.min

object Nms {

    fun classAwareNms(dets: List<Detection>, iouThreshold: Float, maxOut: Int): List<Detection> {
        val byCls = dets.groupBy { it.classId }
        val out = ArrayList<Detection>(minOf(maxOut, dets.size))

        for ((_, list) in byCls) {
            val sorted = list.sortedByDescending { it.score }.toMutableList()
            val picked = ArrayList<Detection>()

            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                picked.add(best)
                if (picked.size >= maxOut) break

                val it = sorted.iterator()
                while (it.hasNext()) {
                    val cur = it.next()
                    if (iou(best.box, cur.box) >= iouThreshold) it.remove()
                }
            }
            out.addAll(picked)
            if (out.size >= maxOut) break
        }

        return out.sortedByDescending { it.score }.take(maxOut)
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interW = max(0f, interRight - interLeft)
        val interH = max(0f, interBottom - interTop)
        val interArea = interW * interH

        val areaA = max(0f, a.right - a.left) * max(0f, a.bottom - a.top)
        val areaB = max(0f, b.right - b.left) * max(0f, b.bottom - b.top)

        val union = areaA + areaB - interArea
        return if (union <= 0f) 0f else interArea / union
    }
}
