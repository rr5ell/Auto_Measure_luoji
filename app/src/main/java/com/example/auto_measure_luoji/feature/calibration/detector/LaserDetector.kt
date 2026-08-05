package com.example.auto_measure_luoji.feature.calibration.detector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.auto_measure_luoji.feature.calibration.model.LaserResult
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object LaserDetector {

    // =========================
    // 参数（对齐 Dart / Python）
    // =========================
    private const val MAX_DIM = 1200
    private const val TOPK = 120
    private const val NMS_R = 12

    private const val DOG_SMALL = 1.2
    private const val DOG_LARGE = 6.0
    private const val DOG_W = 0.02

    private const val G_MIN = 70
    private const val SAT_MIN = 0.05
    private const val MX_MIN = 90

    private const val AREA_BIG = 12
    private const val BRIGHTCORE_REFINE_AREA_MAX = 150
    private const val ALWAYS_RETURN_BEST = false

    private const val CENTER_ROI_RATIO = 1.0
    private const val CENTER_SIGMA = 0.45
    private const val CENTER_WEIGHT = 1.0

    // =========================
    // 入口1：直接传 Bitmap
    // =========================
    fun detectLaserCenter(bitmap: Bitmap, debug: Boolean = false): LaserResult? {
        return detectLaserCenterAdaptive(bitmap, debug, useLegacyResize = false)
    }

    fun detectLaserCenterLegacy(bitmap: Bitmap, debug: Boolean = false): LaserResult? {
        return detectLaserCenterAdaptive(bitmap, debug, useLegacyResize = true)
    }

    // =========================
    // 入口2：传图片 bytes
    // ByteArray 对应 Flutter/Dart 侧 Uint8List 的 Android 表达。
    // bytes 必须是 BitmapFactory 支持的完整图片编码，例如 PNG / JPEG。
    // =========================
    fun detectLaserCenterFromBytes(bytes: ByteArray, debug: Boolean = false): LaserResult? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            detectLaserCenterAdaptive(bmp, debug, useLegacyResize = false)
        } finally {
            bmp.recycle()
        }
    }

    fun detectLaserCenterFromBytesLegacy(bytes: ByteArray, debug: Boolean = false): LaserResult? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            detectLaserCenterAdaptive(bmp, debug, useLegacyResize = true)
        } finally {
            bmp.recycle()
        }
    }

    // =========================
    // lerp
    // =========================
    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    // =========================
    // RGB 提取
    // 输出 int 数组，长度 = w*h*3，顺序 RGBRGB...
    // =========================
    private fun bitmapToRgbArray(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val rgb = IntArray(w * h * 3)
        var j = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            rgb[j++] = (p shr 16) and 0xFF
            rgb[j++] = (p shr 8) and 0xFF
            rgb[j++] = p and 0xFF
        }
        return rgb
    }

    // =========================
    // sha1（调试用）
    // =========================
    private fun sha1OfRgb(rgb: IntArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(rgb.size)
        for (i in rgb.indices) {
            buf[i] = (rgb[i] and 0xFF).toByte()
        }
        val digest = md.digest(buf)
        return digest.joinToString("") { "%02x".format(it) }
    }

    // =========================
    // PIL 风格 bilinear resize（像素中心对齐）
    // 输入/输出都是 RGB int 数组（0..255）
    // =========================
    private data class ResampleContrib(val first: Int, val weights: DoubleArray)

    private fun pillowBilinearFilter(x: Double): Double {
        val ax = abs(x)
        return if (ax < 1.0) 1.0 - ax else 0.0
    }

    private fun makePillowBilinearContribs(inputSize: Int, outputSize: Int): Array<ResampleContrib> {
        val scale = inputSize.toDouble() / outputSize.toDouble()
        val filterscale = max(1.0, scale)
        val support = filterscale
        val invFilterscale = 1.0 / filterscale

        return Array(outputSize) { outIndex ->
            val center = (outIndex + 0.5) * scale
            var first = floor(center - support + 0.5).toInt()
            var lastExclusive = floor(center + support + 0.5).toInt()

            if (first < 0) first = 0
            if (lastExclusive > inputSize) lastExclusive = inputSize

            if (lastExclusive <= first) {
                val nearest = floor(center).toInt().coerceIn(0, inputSize - 1)
                return@Array ResampleContrib(nearest, doubleArrayOf(1.0))
            }

            val weights = DoubleArray(lastExclusive - first)
            var sum = 0.0
            for (i in weights.indices) {
                val source = first + i
                val weight = pillowBilinearFilter((source - center + 0.5) * invFilterscale)
                weights[i] = weight
                sum += weight
            }

            if (sum > 0.0) {
                for (i in weights.indices) {
                    weights[i] /= sum
                }
            }

            ResampleContrib(first, weights)
        }
    }

    private fun resizeRgbBilinearPIL(
        srcRgb: IntArray,
        sw: Int,
        sh: Int,
        dw: Int,
        dh: Int,
    ): IntArray {
        if (sw == dw && sh == dh) return srcRgb.copyOf()

        val xContribs = makePillowBilinearContribs(sw, dw)
        val yContribs = makePillowBilinearContribs(sh, dh)
        val tmp = FloatArray(dw * sh * 3)
        val out = IntArray(dw * dh * 3)

        for (y in 0 until sh) {
            val srcRow = y * sw * 3
            val tmpRow = y * dw * 3
            for (x in 0 until dw) {
                val contrib = xContribs[x]
                var rr = 0.0
                var gg = 0.0
                var bb = 0.0
                for (i in contrib.weights.indices) {
                    val src = srcRow + (contrib.first + i) * 3
                    val weight = contrib.weights[i]
                    rr += srcRgb[src].toDouble() * weight
                    gg += srcRgb[src + 1].toDouble() * weight
                    bb += srcRgb[src + 2].toDouble() * weight
                }
                val dst = tmpRow + x * 3
                tmp[dst] = rr.toFloat()
                tmp[dst + 1] = gg.toFloat()
                tmp[dst + 2] = bb.toFloat()
            }
        }

        for (y in 0 until dh) {
            val contrib = yContribs[y]
            val outRow = y * dw * 3
            for (x in 0 until dw) {
                var rr = 0.0
                var gg = 0.0
                var bb = 0.0
                for (i in contrib.weights.indices) {
                    val src = ((contrib.first + i) * dw + x) * 3
                    val weight = contrib.weights[i]
                    rr += tmp[src].toDouble() * weight
                    gg += tmp[src + 1].toDouble() * weight
                    bb += tmp[src + 2].toDouble() * weight
                }
                val dst = outRow + x * 3
                out[dst] = rr.roundToInt().coerceIn(0, 255)
                out[dst + 1] = gg.roundToInt().coerceIn(0, 255)
                out[dst + 2] = bb.roundToInt().coerceIn(0, 255)
            }
        }

        return out
    }

    private fun resizeRgbBilinearLegacy(
        srcRgb: IntArray,
        sw: Int,
        sh: Int,
        dw: Int,
        dh: Int,
    ): IntArray {
        val out = IntArray(dw * dh * 3)

        val scaleX = sw.toDouble() / dw.toDouble()
        val scaleY = sh.toDouble() / dh.toDouble()

        fun clampInt(v: Int, lo: Int, hi: Int): Int =
            if (v < lo) lo else if (v > hi) hi else v

        for (y in 0 until dh) {
            val sy = (y + 0.5) * scaleY - 0.5
            val y0 = floor(sy).toInt()
            val y1 = y0 + 1
            val wy = sy - y0

            val yy0 = clampInt(y0, 0, sh - 1)
            val yy1 = clampInt(y1, 0, sh - 1)

            for (x in 0 until dw) {
                val sx = (x + 0.5) * scaleX - 0.5
                val x0 = floor(sx).toInt()
                val x1 = x0 + 1
                val wx = sx - x0

                val xx0 = clampInt(x0, 0, sw - 1)
                val xx1 = clampInt(x1, 0, sw - 1)

                val p00 = (yy0 * sw + xx0) * 3
                val p10 = (yy0 * sw + xx1) * 3
                val p01 = (yy1 * sw + xx0) * 3
                val p11 = (yy1 * sw + xx1) * 3

                val o = (y * dw + x) * 3

                for (c in 0..2) {
                    val v00 = srcRgb[p00 + c].toDouble()
                    val v10 = srcRgb[p10 + c].toDouble()
                    val v01 = srcRgb[p01 + c].toDouble()
                    val v11 = srcRgb[p11 + c].toDouble()

                    val v0 = v00 + (v10 - v00) * wx
                    val v1 = v01 + (v11 - v01) * wx
                    val v = v0 + (v1 - v0) * wy

                    out[o + c] = v.roundToInt().coerceIn(0, 255)
                }
            }
        }
        return out
    }

    // =========================
    // PIL GaussianBlur 近似：3 次 box blur
    // =========================
    private fun boxesForGauss(sigma: Double, n: Int = 3): IntArray {
        val wIdeal = sqrt((12 * sigma * sigma / n) + 1)
        var wl = floor(wIdeal).toInt()
        if (wl % 2 == 0) wl -= 1
        val wu = wl + 2

        val mIdeal =
            (12 * sigma * sigma - n * wl * wl - 4 * n * wl - 3 * n) / (-4 * wl - 4)
        val m = mIdeal.roundToInt()

        val sizes = IntArray(n) { i -> if (i < m) wl else wu }
        return IntArray(n) { i -> (sizes[i] - 1) / 2 }
    }

    private fun boxBlurSeparableF32(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return src.copyOf()

        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)

        // horizontal
        for (y in 0 until h) {
            val pad = FloatArray(w + 2 * r)
            val rowOff = y * w

            for (i in 0 until r) {
                pad[i] = src[rowOff]
            }
            for (x in 0 until w) {
                pad[r + x] = src[rowOff + x]
            }
            for (i in 0 until r) {
                pad[r + w + i] = src[rowOff + (w - 1)]
            }

            val c = FloatArray(pad.size + 1)
            for (i in pad.indices) {
                c[i + 1] = c[i] + pad[i]
            }

            val win = 2 * r + 1
            for (x in 0 until w) {
                val sum = c[x + win] - c[x]
                tmp[rowOff + x] = sum / win.toFloat()
            }
        }

        // vertical
        for (x in 0 until w) {
            val pad = FloatArray(h + 2 * r)

            for (i in 0 until r) {
                pad[i] = tmp[x]
            }
            for (y in 0 until h) {
                pad[r + y] = tmp[y * w + x]
            }
            for (i in 0 until r) {
                pad[r + h + i] = tmp[(h - 1) * w + x]
            }

            val c = FloatArray(pad.size + 1)
            for (i in pad.indices) {
                c[i + 1] = c[i] + pad[i]
            }

            val win = 2 * r + 1
            for (y in 0 until h) {
                val sum = c[y + win] - c[y]
                out[y * w + x] = sum / win.toFloat()
            }
        }

        return out
    }

    private fun gaussianBlurPILLikeFromU8(srcU8: IntArray, w: Int, h: Int, sigma: Double): FloatArray {
        if (!sigma.isFinite() || sigma <= 1e-9) {
            return FloatArray(w * h) { i -> srcU8[i].toFloat() }
        }

        var cur = FloatArray(w * h) { i -> srcU8[i].toFloat() }
        val rads = boxesForGauss(sigma, 3)
        for (r in rads) {
            cur = boxBlurSeparableF32(cur, w, h, r)
        }
        return cur
    }

    // =========================
    // TopK + NMS
    // =========================
    private data class HeapEntry(val value: Float, val idx: Int)

    private class MinHeap {
        private val a = ArrayList<HeapEntry>()
        val length: Int get() = a.size
        val min: HeapEntry get() = a.first()

        fun push(e: HeapEntry) {
            a.add(e)
            siftUp(a.lastIndex)
        }

        fun replaceMin(e: HeapEntry) {
            a[0] = e
            siftDown(0)
        }

        fun toListCopy(): List<HeapEntry> = a.toList()

        private fun siftUp(start: Int) {
            var i = start
            while (i > 0) {
                val p = (i - 1) shr 1
                if (a[p].value <= a[i].value) break
                val t = a[p]
                a[p] = a[i]
                a[i] = t
                i = p
            }
        }

        private fun siftDown(start: Int) {
            var i = start
            val n = a.size
            while (true) {
                val l = i * 2 + 1
                val r = l + 1
                if (l >= n) break
                var m = l
                if (r < n && a[r].value < a[l].value) m = r
                if (a[i].value <= a[m].value) break
                val t = a[i]
                a[i] = a[m]
                a[m] = t
                i = m
            }
        }
    }

    private data class Peak(val x: Int, val y: Int, val value: Float)

    private fun nmsTopK(score: FloatArray, w: Int, h: Int, topK: Int, r: Int): List<Peak> {
        val heap = MinHeap()

        for (idx in score.indices) {
            val v = score[idx]
            if (!v.isFinite() || v <= -1e8f) continue

            if (heap.length < topK) {
                heap.push(HeapEntry(v, idx))
            } else if (v > heap.min.value) {
                heap.replaceMin(HeapEntry(v, idx))
            }
        }

        val cand = heap.toListCopy().sortedByDescending { it.value }
        val peaks = ArrayList<Peak>()
        val r2 = r * r

        for (e in cand) {
            val idx = e.idx
            val y = idx / w
            val x = idx - y * w

            val x1 = max(0, x - r)
            val x2 = min(w - 1, x + r)
            val y1 = max(0, y - r)
            val y2 = min(h - 1, y + r)

            var localMax = -1e18f
            for (yy in y1..y2) {
                val row = yy * w
                for (xx in x1..x2) {
                    val vv = score[row + xx]
                    if (vv > localMax) localMax = vv
                }
            }
            if (score[idx] < localMax) continue

            var ok = true
            for (p in peaks) {
                val dx = p.x - x
                val dy = p.y - y
                if (dx * dx + dy * dy <= r2) {
                    ok = false
                    break
                }
            }
            if (!ok) continue

            peaks.add(Peak(x, y, e.value))
            if (peaks.size >= topK) break
        }

        return peaks
    }

    // =========================
    // ring_mean / radial_means / isotropy
    // =========================
    private fun ringMeanFloat(mat: FloatArray, w: Int, h: Int, x0: Int, y0: Int, rIn: Int, rOut: Int): Double {
        val x1 = max(0, x0 - rOut)
        val x2 = min(w - 1, x0 + rOut)
        val y1 = max(0, y0 - rOut)
        val y2 = min(h - 1, y0 + rOut)

        val rIn2 = rIn * rIn
        val rOut2 = rOut * rOut

        var sum = 0.0
        var cnt = 0
        for (y in y1..y2) {
            val dy = y - y0
            val row = y * w
            for (x in x1..x2) {
                val dx = x - x0
                val d2 = dx * dx + dy * dy
                if (d2 >= rIn2 && d2 <= rOut2) {
                    sum += mat[row + x].toDouble()
                    cnt++
                }
            }
        }

        if (cnt == 0) {
            var s2 = 0.0
            var c2 = 0
            for (y in y1..y2) {
                val row = y * w
                for (x in x1..x2) {
                    s2 += mat[row + x].toDouble()
                    c2++
                }
            }
            return if (c2 == 0) 0.0 else s2 / c2
        }
        return sum / cnt
    }

    private fun ringMeanU8(mat: IntArray, w: Int, h: Int, x0: Int, y0: Int, rIn: Int, rOut: Int): Double {
        val x1 = max(0, x0 - rOut)
        val x2 = min(w - 1, x0 + rOut)
        val y1 = max(0, y0 - rOut)
        val y2 = min(h - 1, y0 + rOut)

        val rIn2 = rIn * rIn
        val rOut2 = rOut * rOut

        var sum = 0.0
        var cnt = 0
        for (y in y1..y2) {
            val dy = y - y0
            val row = y * w
            for (x in x1..x2) {
                val dx = x - x0
                val d2 = dx * dx + dy * dy
                if (d2 >= rIn2 && d2 <= rOut2) {
                    sum += mat[row + x].toDouble()
                    cnt++
                }
            }
        }

        if (cnt == 0) {
            var s2 = 0.0
            var c2 = 0
            for (y in y1..y2) {
                val row = y * w
                for (x in x1..x2) {
                    s2 += mat[row + x].toDouble()
                    c2++
                }
            }
            return if (c2 == 0) 0.0 else s2 / c2
        }
        return sum / cnt
    }

    private data class RadialMeans(val m0: Double, val m1: Double, val m2: Double)

    private fun radialMeans(v: FloatArray, w: Int, h: Int, x0: Int, y0: Int, r0: Int, r1: Int, r2: Int): RadialMeans {
        val x1 = max(0, x0 - r2)
        val x2 = min(w - 1, x0 + r2)
        val y1 = max(0, y0 - r2)
        val y2 = min(h - 1, y0 + r2)

        val r02 = r0 * r0
        val r12 = r1 * r1
        val r22 = r2 * r2

        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var c0 = 0
        var c1 = 0
        var c2 = 0

        for (y in y1..y2) {
            val dy = y - y0
            val row = y * w
            for (x in x1..x2) {
                val dx = x - x0
                val d2 = dx * dx + dy * dy
                if (d2 > r22) continue
                val value = v[row + x].toDouble()
                when {
                    d2 <= r02 -> {
                        s0 += value
                        c0++
                    }
                    d2 <= r12 -> {
                        s1 += value
                        c1++
                    }
                    else -> {
                        s2 += value
                        c2++
                    }
                }
            }
        }

        var patchSum = 0.0
        var patchCnt = 0
        for (y in y1..y2) {
            val row = y * w
            for (x in x1..x2) {
                patchSum += v[row + x].toDouble()
                patchCnt++
            }
        }
        val patchMean = if (patchCnt == 0) 0.0 else patchSum / patchCnt.toDouble()

        fun meanOr(sum: Double, cnt: Int): Double = if (cnt == 0) patchMean else sum / cnt.toDouble()

        return RadialMeans(
            m0 = meanOr(s0, c0),
            m1 = meanOr(s1, c1),
            m2 = meanOr(s2, c2),
        )
    }

    private fun isotropyScore(v: FloatArray, w: Int, h: Int, x0: Int, y0: Int, rad: Int = 7): Double {
        val x1 = max(1, x0 - rad)
        val x2 = min(w - 2, x0 + rad)
        val y1 = max(1, y0 - rad)
        val y2 = min(h - 2, y0 + rad)
        if (x2 <= x1 || y2 <= y1) return 0.0

        var j11 = 0.0
        var j22 = 0.0
        var j12 = 0.0
        for (y in y1..y2) {
            for (x in x1..x2) {
                val dx = v[y * w + (x + 1)] - v[y * w + (x - 1)]
                val dy = v[(y + 1) * w + x] - v[(y - 1) * w + x]
                j11 += dx * dx
                j22 += dy * dy
                j12 += dx * dy
            }
        }

        val tr = j11 + j22
        val det = j11 * j22 - j12 * j12
        if (tr <= 1e-9 || det < 0) return 0.0

        val disc = sqrt(max(tr * tr - 4 * det, 0.0))
        val l1 = (tr + disc) / 2
        val l2 = (tr - disc) / 2
        return l2 / (l1 + 1e-9)
    }

    // =========================
    // floodfill helpers
    // =========================
    private fun floodfillLocalToGlobal(
        localMask: ByteArray,
        lw: Int,
        lh: Int,
        seedX: Int,
        seedY: Int,
        xOff: Int,
        yOff: Int,
        w: Int,
    ): MutableList<Int> {
        if (seedX !in 0 until lw || seedY !in 0 until lh) return mutableListOf()
        val seedId = seedY * lw + seedX
        if (localMask[seedId].toInt() == 0) return mutableListOf()

        val visited = ByteArray(lw * lh)
        val stack = ArrayDeque<Int>()
        val out = ArrayList<Int>()

        stack.addLast(seedId)

        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (visited[id].toInt() == 1) continue
            visited[id] = 1
            if (localMask[id].toInt() == 0) continue

            val ly = id / lw
            val lx = id - ly * lw

            val gx = xOff + lx
            val gy = yOff + ly
            out.add(gy * w + gx)

            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = lx + dx
                    val ny = ly + dy
                    if (nx !in 0 until lw || ny !in 0 until lh) continue
                    stack.addLast(ny * lw + nx)
                }
            }
        }

        return out
    }

    private fun floodfillThresh8(
        mx: FloatArray,
        w: Int,
        h: Int,
        seedX: Int,
        seedY: Int,
        thr: Double,
    ): MutableList<Int> {
        if (seedX !in 0 until w || seedY !in 0 until h) return mutableListOf()
        val seedIdx = seedY * w + seedX
        if (mx[seedIdx] < thr.toFloat()) return mutableListOf()

        val visited = ByteArray(w * h)
        val stack = ArrayDeque<Int>()
        val out = ArrayList<Int>()

        stack.addLast(seedIdx)

        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (visited[id].toInt() == 1) continue
            visited[id] = 1
            if (mx[id] < thr.toFloat()) continue

            out.add(id)

            val y = id / w
            val x = id - y * w

            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    stack.addLast(ny * w + nx)
                }
            }
        }

        return out
    }

    // =========================
    // 峰值核心加权中心
    // =========================
    private data class Center(val cx: Double, val cy: Double)

    private fun centerFromRegionMean(region: List<Int>, w: Int): Center? {
        if (region.isEmpty()) return null

        var sumX = 0.0
        var sumY = 0.0
        for (id in region) {
            val y = id / w
            val x = id - y * w
            sumX += x.toDouble()
            sumY += y.toDouble()
        }

        val n = region.size.toDouble()
        return Center(sumX / n, sumY / n)
    }

    private fun centerFromRegionPeakWeighted(
        region: List<Int>,
        w: Int,
        r8: IntArray,
        g8: IntArray,
        b8: IntArray,
        sat: FloatArray,
    ): Center? {
        if (region.isEmpty()) return null

        fun respAt(idx: Int): Double {
            val r = r8[idx].toDouble()
            val g = g8[idx].toDouble()
            val b = b8[idx].toDouble()
            return (g + 0.8 * b - 0.3 * r) * (sat[idx].toDouble() + 0.1)
        }

        var bestV = -1e18
        var bestIdx = region.first()
        for (idx in region) {
            val v = respAt(idx)
            if (v > bestV) {
                bestV = v
                bestIdx = idx
            }
        }

        val y0 = bestIdx / w
        val x0 = bestIdx - y0 * w

        if (region.size <= 2) return Center(x0.toDouble(), y0.toDouble())

        val thr = 0.70 * bestV
        var sx = 0.0
        var sy = 0.0
        var sw = 0.0
        var used = 0

        for (idx in region) {
            val v = respAt(idx)
            if (v >= thr) {
                val yy = idx / w
                val xx = idx - yy * w
                val wgt = v * v
                sx += xx * wgt
                sy += yy * wgt
                sw += wgt
                used++
            }
        }

        if (sw <= 1e-9 || used == 0) return Center(x0.toDouble(), y0.toDouble())
        return Center(sx / sw, sy / sw)
    }

    // =========================
    // bright core refine
    // =========================
    private fun refineCenterByBrightcoreInRegion(
        mx: FloatArray,
        w: Int,
        h: Int,
        region: List<Int>,
        pad: Int = 12,
    ): Center? {
        if (region.isEmpty()) return null

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1

        for (id in region) {
            val yy = id / w
            val xx = id - yy * w
            if (xx < minX) minX = xx
            if (xx > maxX) maxX = xx
            if (yy < minY) minY = yy
            if (yy > maxY) maxY = yy
        }

        val x1 = max(0, minX - pad)
        val x2 = min(w - 1, maxX + pad)
        val y1 = max(0, minY - pad)
        val y2 = min(h - 1, maxY + pad)

        var vmax = -1e18
        var sx = x1
        var sy = y1
        for (yy in y1..y2) {
            val row = yy * w
            for (xx in x1..x2) {
                val v = mx[row + xx].toDouble()
                if (v > vmax) {
                    vmax = v
                    sx = xx
                    sy = yy
                }
            }
        }

        val border = ArrayList<Double>()
        for (xx in x1..x2) {
            border.add(mx[y1 * w + xx].toDouble())
            border.add(mx[y2 * w + xx].toDouble())
        }
        for (yy in y1..y2) {
            border.add(mx[yy * w + x1].toDouble())
            border.add(mx[yy * w + x2].toDouble())
        }
        border.sort()
        val bg = if (border.isEmpty()) 0.0 else border[border.size / 2]

        val alphas = doubleArrayOf(0.95, 0.92, 0.90, 0.88, 0.86, 0.85)
        for (alpha in alphas) {
            var thr = bg + alpha * (vmax - bg)
            thr = min(thr, vmax - 1e-3)

            val coords = floodfillThresh8(mx, w, h, sx, sy, thr)
            if (coords.isEmpty()) continue

            val area = coords.size
            if (area < 3) continue
            if (area > 600) continue

            var sumX = 0.0
            var sumY = 0.0
            for (id in coords) {
                val yy = id / w
                val xx = id - yy * w
                sumX += xx
                sumY += yy
            }
            return Center(sumX / area.toDouble(), sumY / area.toDouble())
        }

        var sumX = 0.0
        var sumY = 0.0
        for (id in region) {
            val yy = id / w
            val xx = id - yy * w
            sumX += xx
            sumY += yy
        }
        return Center(sumX / region.size.toDouble(), sumY / region.size.toDouble())
    }

    // =========================
    // 主检测
    // =========================
    private fun detectLaserCenterAdaptive(
        bitmap: Bitmap,
        debug: Boolean = false,
        useLegacyResize: Boolean,
    ): LaserResult? {
        val w0 = bitmap.width
        val h0 = bitmap.height

        // --- build RGB0 bytes ---
        val rgb0 = bitmapToRgbArray(bitmap)

        if (debug) {
            val h0Sha = sha1OfRgb(rgb0)
            println("RGB0 sha1=$h0Sha size=${w0}x$h0")
        }

        // --- resize to small (PIL-like) ---
        var scale = 1.0
        var w = w0
        var h = h0
        val rgbSmall: IntArray

        val maxSide = max(w0, h0)
        if (maxSide > MAX_DIM) {
            scale = MAX_DIM.toDouble() / maxSide.toDouble()
            val newW = max(1, (w0 * scale).roundToInt())
            val newH = max(1, (h0 * scale).roundToInt())

            rgbSmall = if (useLegacyResize) {
                resizeRgbBilinearLegacy(rgb0, w0, h0, newW, newH)
            } else {
                resizeRgbBilinearPIL(rgb0, w0, h0, newW, newH)
            }
            w = newW
            h = newH
        } else {
            rgbSmall = rgb0
        }

        if (debug) {
            val hs = sha1OfRgb(rgbSmall)
            println("RGBsmall sha1=$hs size=${w}x$h scale=${"%.6f".format(scale)}")
        }

        val n = w * h

        // split channels
        val r8 = IntArray(n)
        val g8 = IntArray(n)
        val b8 = IntArray(n)

        for (i in 0 until n) {
            r8[i] = rgbSmall[i * 3]
            g8[i] = rgbSmall[i * 3 + 1]
            b8[i] = rgbSmall[i * 3 + 2]
        }

        // sat / mx / scoreColor
        val sat = FloatArray(n)
        val mx = FloatArray(n)
        val scoreColor = FloatArray(n)

        for (i in 0 until n) {
            val r = r8[i].toDouble()
            val g = g8[i].toDouble()
            val b = b8[i].toDouble()

            val mxv = max(r, max(g, b))
            val mnv = min(r, min(g, b))
            mx[i] = mxv.toFloat()

            val satv = (mxv - mnv) / (mxv + 1e-6)
            sat[i] = satv.toFloat()

            val cd = g - max(r, b)
            val bg = b / (g + 1e-6)

            val scoreGreen =
                (max(cd, 0.0) / 50.0 + 1.0) *
                        (satv + 0.05) *
                        (g / 255.0) *
                        bg.coerceIn(0.0, 1.5)

            val bg2 = bg.coerceIn(0.0, 2.0)
            val scoreCyan = (bg2 * bg2) * (satv + 0.05) * (g / 255.0)
            scoreColor[i] = max(scoreGreen, scoreCyan).toFloat()
        }

        // --- DoG on G (PIL-like GaussianBlur approximation) ---
        val blurS = gaussianBlurPILLikeFromU8(g8, w, h, DOG_SMALL)
        val blurL = gaussianBlurPILLikeFromU8(g8, w, h, DOG_LARGE)

        val score = FloatArray(n)
        for (i in 0 until n) {
            val gr = g8[i] - r8[i]
            val gate =
                (g8[i] >= G_MIN) &&
                        (sat[i] >= SAT_MIN.toFloat()) &&
                        (mx[i] >= MX_MIN.toFloat()) &&
                        (gr >= 6)

            if (!gate) {
                score[i] = -1e9f
                continue
            }

            val dog = (blurS[i] - blurL[i]).toDouble()
            val dogp = dog.coerceIn(0.0, 40.0)
            score[i] = (scoreColor[i] + DOG_W * dogp).toFloat()
        }

        // ROI
        val x1R = (w * (1 - CENTER_ROI_RATIO) / 2).toInt()
        val x2R = (w * (1 + CENTER_ROI_RATIO) / 2).toInt()
        val y1R = (h * (1 - CENTER_ROI_RATIO) / 2).toInt()
        val y2R = (h * (1 + CENTER_ROI_RATIO) / 2).toInt()

        for (y in 0 until h) {
            val row = y * w
            val inY = y >= y1R && y < y2R
            for (x in 0 until w) {
                if (!(inY && x >= x1R && x < x2R)) {
                    score[row + x] = -1e9f
                }
            }
        }

        val peaks = nmsTopK(score, w, h, TOPK, NMS_R)
        if (peaks.isEmpty()) return null

        val cx0 = w / 2.0
        val cy0 = h / 2.0
        val roiHalfW = (x2R - x1R) / 2.0
        val roiHalfH = (y2R - y1R) / 2.0

        var bestFinal = -1e18
        var bestCxS = 0.0
        var bestCyS = 0.0

        // debug fields
        var dbgPeakX = 0
        var dbgPeakY = 0
        var dbgArea = 0
        var dbgIdx0 = 0
        var dbgS0 = 0.0
        var dbgSatRing = 0.0
        var dbgSatRingMax = 0.0
        var dbgContrast = 0.0
        var dbgIso = 0.0
        var dbgIsoMin = 0.0
        var dbgDrop01 = 0.0
        var dbgDrop12 = 0.0
        var dbgDrop01Min = 0.0
        var dbgDrop12Min = 0.0
        var dbgCoreCxS = 0.0
        var dbgCoreCyS = 0.0
        var dbgRefCxS = 0.0
        var dbgRefCyS = 0.0
        var dbgCenterMode = ""

        for (pk in peaks) {
            val x0p = pk.x
            val y0p = pk.y
            val s0 = pk.value.toDouble()

            val dxn = (x0p - cx0) / (roiHalfW + 1e-6)
            val dyn = (y0p - cy0) / (roiHalfH + 1e-6)
            val t = min(1.0, sqrt(dxn * dxn + dyn * dyn))

            val centerPrior = exp(-(t * t) / (2 * CENTER_SIGMA * CENTER_SIGMA))

            val satRingMax = lerp(0.32, 0.18, t)
            val isoMin = lerp(0.06, 0.18, t)
            val aspectMax = lerp(4.0, 2.4, t)

            val contrastMinTiny = lerp(3.0, 12.0, t)
            val contrastMinBig = lerp(8.0, 22.0, t)
            val drop01Min = lerp(1.0, 6.0, t)
            val drop12Min = lerp(1.0, 4.0, t)

            val idx0 = y0p * w + x0p

            if ((g8[idx0] - r8[idx0]) < 6) continue

            val satRing = ringMeanFloat(sat, w, h, x0p, y0p, 10, 25)
            if (satRing > satRingMax) continue

            // region segmentation (rad=45)
            val rad = 45
            val x1 = max(0, x0p - rad)
            val x2 = min(w - 1, x0p + rad)
            val y1 = max(0, y0p - rad)
            val y2 = min(h - 1, y0p + rad)

            var smax = -1e18
            var gmax = -1e18
            var satmax = -1e18
            for (yy in y1..y2) {
                val row = yy * w
                for (xx in x1..x2) {
                    val id = row + xx
                    val sv = scoreColor[id].toDouble()
                    val gv = g8[id].toDouble()
                    val st = sat[id].toDouble()
                    if (sv > smax) smax = sv
                    if (gv > gmax) gmax = gv
                    if (st > satmax) satmax = st
                }
            }

            val thrS = max(0.03, smax * 0.45)
            val thrG = max(50.0, gmax * 0.55)
            val thrSat = max(0.04, satmax * 0.40)

            val lw = x2 - x1 + 1
            val lh = y2 - y1 + 1
            val localMask = ByteArray(lw * lh)

            for (ly in 0 until lh) {
                val yy = y1 + ly
                val row = yy * w
                for (lx in 0 until lw) {
                    val xx = x1 + lx
                    val id = row + xx
                    val ok =
                        (scoreColor[id] >= thrS.toFloat()) &&
                                (g8[id].toDouble() >= thrG) &&
                                (sat[id].toDouble() >= thrSat)
                    localMask[ly * lw + lx] = if (ok) 1 else 0
                }
            }

            var region = floodfillLocalToGlobal(
                localMask = localMask,
                lw = lw,
                lh = lh,
                seedX = x0p - x1,
                seedY = y0p - y1,
                xOff = x1,
                yOff = y1,
                w = w,
            )
            if (region.isEmpty()) region = mutableListOf(idx0)

            val area = region.size

            if (area > AREA_BIG) {
                var minX = Int.MAX_VALUE
                var minY = Int.MAX_VALUE
                var maxX = -1
                var maxY = -1
                for (id in region) {
                    val yy = id / w
                    val xx = id - yy * w
                    if (xx < minX) minX = xx
                    if (xx > maxX) maxX = xx
                    if (yy < minY) minY = yy
                    if (yy > maxY) maxY = yy
                }
                val ww = (maxX - minX + 1).toDouble()
                val hh = (maxY - minY + 1).toDouble()
                val asp = max(ww / hh, hh / ww)
                if (asp > aspectMax) continue
            }

            val contrast = g8[idx0].toDouble() - ringMeanU8(g8, w, h, x0p, y0p, 4, 18)

            val rm = radialMeans(mx, w, h, x0p, y0p, 2, 7, 16)
            val drop01 = rm.m0 - rm.m1
            val drop12 = rm.m1 - rm.m2
            if (drop01 < drop01Min || drop12 < drop12Min) continue

            if (area > AREA_BIG) {
                if (contrast < contrastMinBig) continue
            } else {
                if (contrast < contrastMinTiny) continue
            }

            val iso = isotropyScore(mx, w, h, x0p, y0p, 7)
            if (iso < isoMin) continue

            var finalScore = s0 + 0.01 * max(contrast, 0.0) + 0.5 * iso
            finalScore *= (1.0 + CENTER_WEIGHT * centerPrior)

            if (finalScore > bestFinal) {
                val core = centerFromRegionPeakWeighted(region, w, r8, g8, b8, sat)

                var cxS = core?.cx ?: x0p.toDouble()
                var cyS = core?.cy ?: y0p.toDouble()
                val coreCxS = cxS
                val coreCyS = cyS

                val useLargeComponentMean = !useLegacyResize && area >= BRIGHTCORE_REFINE_AREA_MAX
                val refined = if (useLargeComponentMean) {
                    centerFromRegionMean(region, w)
                } else {
                    refineCenterByBrightcoreInRegion(mx, w, h, region, 12)
                }
                if (refined != null) {
                    cxS = refined.cx
                    cyS = refined.cy
                }

                bestFinal = finalScore
                bestCxS = cxS
                bestCyS = cyS

                dbgPeakX = x0p
                dbgPeakY = y0p
                dbgIdx0 = idx0
                dbgS0 = s0
                dbgArea = area
                dbgSatRing = satRing
                dbgSatRingMax = satRingMax
                dbgContrast = contrast
                dbgIso = iso
                dbgIsoMin = isoMin
                dbgDrop01 = drop01
                dbgDrop12 = drop12
                dbgDrop01Min = drop01Min
                dbgDrop12Min = drop12Min
                dbgCoreCxS = coreCxS
                dbgCoreCyS = coreCyS
                dbgRefCxS = cxS
                dbgRefCyS = cyS
                dbgCenterMode = if (useLargeComponentMean) {
                    "large-component-mean"
                } else {
                    "brightcore-refine"
                }
            }
        }

        if (!bestFinal.isFinite() || bestFinal <= -1e17) {
            return null
        }

        val cx = bestCxS / scale
        val cy = bestCyS / scale
        val conf = 1.0 / (1.0 + exp(-1.2 * (bestFinal - 0.6)))

        var report: String? = null
        if (debug) {
            val rr = r8[dbgIdx0].toDouble()
            val gg = g8[dbgIdx0].toDouble()
            val bb = b8[dbgIdx0].toDouble()

            val mxv = max(rr, max(gg, bb))
            val mnv = min(rr, min(gg, bb))
            val satv = (mxv - mnv) / (mxv + 1e-6)
            val cd = gg - max(rr, bb)
            val bg = bb / (gg + 1e-6)

            val scoreGreen =
                (max(cd, 0.0) / 50.0 + 1.0) *
                        (satv + 0.05) *
                        (gg / 255.0) *
                        bg.coerceIn(0.0, 1.5)

            val bg2 = bg.coerceIn(0.0, 2.0)
            val scoreCyan = (bg2 * bg2) * (satv + 0.05) * (gg / 255.0)
            val scoreC = max(scoreGreen, scoreCyan)

            val dog = (blurS[dbgIdx0] - blurL[dbgIdx0]).toDouble()
            val dogp = dog.coerceIn(0.0, 40.0)

            report = buildString {
                appendLine("=== 输入/缩放 ===")
                appendLine("ALGORITHM=${if (useLegacyResize) "legacy-resize" else "current-pil-resize"}")
                appendLine("INPUT: ${w0}x${h0}")
                appendLine("MAX_DIM=$MAX_DIM -> small=${w}x${h}, scale=${"%.6f".format(scale)}")
                appendLine()
                appendLine("=== 绿色/青色判定（在最终选中峰值像素处） ===")
                appendLine("peak(small)=($dbgPeakX,$dbgPeakY) idx=$dbgIdx0")
                appendLine("RGB=(${rr.toInt()}, ${gg.toInt()}, ${bb.toInt()})")
                appendLine("mx=max(r,g,b)=${"%.1f".format(mxv)}  sat=(mx-mn)/(mx+eps)=${"%.4f".format(satv)}")
                appendLine("cd = g - max(r,b) = ${"%.2f".format(cd)}")
                appendLine("bg = b / (g+eps) = ${"%.4f".format(bg)}")
                appendLine("scoreGreen = ${"%.6f".format(scoreGreen)}")
                appendLine("scoreCyan  = ${"%.6f".format(scoreCyan)}")
                appendLine("scoreColor = ${"%.6f".format(scoreC)}")
                appendLine("DoG: dog=${"%.2f".format(dog)}  dogp=${"%.2f".format(dogp)}  DOG_W=$DOG_W")
                appendLine()
                appendLine("=== 过滤判据 ===")
                appendLine("satRing(10..25)=${"%.4f".format(dbgSatRing)}  maxAllowed=${"%.4f".format(dbgSatRingMax)}")
                appendLine("contrast=${"%.3f".format(dbgContrast)}")
                appendLine("drop01=${"%.3f".format(dbgDrop01)}  min=${"%.3f".format(dbgDrop01Min)}")
                appendLine("drop12=${"%.3f".format(dbgDrop12)}  min=${"%.3f".format(dbgDrop12Min)}")
                appendLine("iso=${"%.4f".format(dbgIso)}  min=${"%.4f".format(dbgIsoMin)}")
                appendLine("regionArea=$dbgArea")
                appendLine()
                appendLine("=== 坐标 ===")
                appendLine("centerMode=$dbgCenterMode")
                appendLine("coreCenter(small)=(${String.format("%.2f", dbgCoreCxS)}, ${String.format("%.2f", dbgCoreCyS)})")
                appendLine("refined(small)=(${String.format("%.2f", dbgRefCxS)}, ${String.format("%.2f", dbgRefCyS)})")
                appendLine("output(orig)=(${String.format("%.2f", cx)}, ${String.format("%.2f", cy)}) round=(${cx.roundToInt()},${cy.roundToInt()})")
                appendLine("finalScore=${String.format("%.6f", bestFinal)}  confidence=${String.format("%.4f", conf)}")
            }
        }

        return LaserResult(
            cx = cx,
            cy = cy,
            confidence = conf,
            finalScore = bestFinal,
            debugReport = report,
        )
    }
}
