package com.example.auto_measure_luoji.feature.camera.domain.gldetection

import kotlin.math.abs

class GlDetectionTicket internal constructor(
    val id: Long,
    val timestampNs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotation: Int,
)

data class GlDetectionBox(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val label: String,
)

data class GlDetectionPoint(val x: Float, val y: Float)

data class GlDetectionQuad(val points: List<GlDetectionPoint>, val label: String) {
    /** 保留旋转前框内百分比的含义，不能用旋转后的外接矩形代替判定点。 */
    fun point(u: Float, v: Float): GlDetectionPoint {
        val origin = points[0]
        return GlDetectionPoint(
            origin.x + u * (points[1].x - origin.x) + v * (points[3].x - origin.x),
            origin.y + u * (points[1].y - origin.y) + v * (points[3].y - origin.y),
        )
    }
}

data class GlDetectionPresentation(
    val timestampNs: Long,
    val viewWidth: Int,
    val viewHeight: Int,
    val boxes: List<GlDetectionQuad>,
)

/**
 * 将 detectFromYuv 返回的“旋转后原图像素”映射到该帧实际 GL 输出像素。
 *
 * 设 R 为 YUV 预处理旋转，S 为该帧 SurfaceTexture 的采样矩阵，D 为实际显示矩阵。
 * shader 做的是 q = S * uv：由输出纹理坐标查找原始缓冲区，所以检测框必须使用 S^-1，
 * 而不是再乘一次 S。完整链路为：
 * 检测像素 -> R^-1 -> 原始缓冲区归一化 q -> S^-1 -> 纹理 uv
 * -> NDC(2u-1, 2v-1) -> D -> 屏幕((x+1)W/2, (1-y)H/2)。
 *
 * S 已包含旋转、镜像和边缘采样裁切。禁止额外 centerCrop 或手工翻转叠加一次。
 * D 来自渲染器同一次 buildDisplayMatrix，包含基础缩放、锚点、自研 EIS 与 Roll。
 * 框使用连续像素边界 [0,W]/[0,H]，故逆旋转使用 1-x，而非采样像素索引的 W-1-x。
 * 普通相机会话的 YUV 与 OES 必须具有相同宽高、视场及 SENSOR_TIMESTAMP；
 * 不满足同源前提时，单靠此矩阵不能校正不同物理镜头的视差。
 */
class GlDetectionCoordinates(
    sampling: FloatArray,
    display: FloatArray,
    private val viewWidth: Int,
    private val viewHeight: Int,
    private val ticket: GlDetectionTicket,
) {
    private val s = sampling.copyOf()
    private val d = display.copyOf()
    private val determinant: Float

    init {
        require(s.size == 16 && d.size == 16 && s.all { it.isFinite() } && d.all { it.isFinite() })
        require(viewWidth > 0 && viewHeight > 0 && ticket.sourceWidth > 0 && ticket.sourceHeight > 0)
        require(ticket.rotation in listOf(0, 90, 180, 270))
        require(abs(s[3]) < 1e-6f && abs(s[7]) < 1e-6f && abs(s[15] - 1f) < 1e-6f)
        determinant = s[0] * s[5] - s[4] * s[1]
        require(abs(determinant) > 1e-8f) { "不可逆的纹理采样矩阵" }
    }

    fun point(x: Float, y: Float): GlDetectionPoint {
        require(x.isFinite() && y.isFinite())
        val quarterTurn = ticket.rotation == 90 || ticket.rotation == 270
        val rotatedWidth = if (quarterTurn) ticket.sourceHeight else ticket.sourceWidth
        val rotatedHeight = if (quarterTurn) ticket.sourceWidth else ticket.sourceHeight
        val rx = x / rotatedWidth
        val ry = y / rotatedHeight
        val (qx, qy) = when (ticket.rotation) {
            90 -> ry to (1f - rx)
            180 -> (1f - rx) to (1f - ry)
            270 -> (1f - ry) to rx
            else -> rx to ry
        }
        // 对 2x2 仿射采样矩阵求逆，平移先扣除；列主序索引与 GL 保持一致。
        val a = qx - s[12]
        val b = qy - s[13]
        val u = (s[5] * a - s[4] * b) / determinant
        val v = (-s[1] * a + s[0] * b) / determinant
        val nx = 2f * u - 1f
        val ny = 2f * v - 1f
        val w = d[3] * nx + d[7] * ny + d[15]
        require(w.isFinite() && abs(w) > 1e-8f)
        val outX = (d[0] * nx + d[4] * ny + d[12]) / w
        val outY = (d[1] * nx + d[5] * ny + d[13]) / w
        return GlDetectionPoint((outX + 1f) * viewWidth / 2f, (1f - outY) * viewHeight / 2f)
    }

    fun box(box: GlDetectionBox): GlDetectionQuad {
        require(box.right >= box.left && box.bottom >= box.top)
        return GlDetectionQuad(listOf(
            point(box.left, box.top), point(box.right, box.top),
            point(box.right, box.bottom), point(box.left, box.bottom),
        ), box.label)
    }
}
