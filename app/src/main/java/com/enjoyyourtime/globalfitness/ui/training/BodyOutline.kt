package com.enjoyyourtime.globalfitness.ui.training

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * One closed or open piece of an outline as absolute cubic Béziers, in figure units: [points] holds
 * the start point (x, y) and then six numbers per segment (first control point, second control
 * point, end point). Every SVG command (lines, quadratics, arcs) is turned into cubics, so a warp
 * only has to move points: moving the control points with the curve keeps it smooth.
 */
internal class Contour(val points: FloatArray, val closed: Boolean) {
    val segments: Int get() = (points.size - 2) / 6

    /** The same contour with every point moved by [warp] (which writes the new x, y into its third argument). */
    fun map(warp: (x: Float, y: Float, out: FloatArray) -> Unit): Contour {
        val out = FloatArray(points.size)
        val p = FloatArray(2)
        var i = 0
        while (i < points.size) {
            warp(points[i], points[i + 1], p)
            out[i] = p[0]
            out[i + 1] = p[1]
            i += 2
        }
        return Contour(out, closed)
    }
}

/** Builds a Compose path from contours (one subpath each). */
internal fun pathOf(contours: List<Contour>): Path = Path().apply {
    for (c in contours) {
        val p = c.points
        moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) {
            cubicTo(p[i], p[i + 1], p[i + 2], p[i + 3], p[i + 4], p[i + 5])
            i += 6
        }
        if (c.closed) close()
    }
}

/**
 * The SVG path data [d] as cubic contours. Segments longer than [maxSegment] figure units are split
 * in halves until they are not, so a warp that varies over a few dozen units bends them smoothly.
 */
internal fun outlineOf(d: String, maxSegment: Float = MAX_SEGMENT): List<Contour> {
    val builder = OutlineBuilder(maxSegment)
    for (node in PathParser().parsePathString(d).toNodes()) builder.add(node)
    return builder.finish()
}

/** About 1/50 of the figure's width: fine enough for the body warp, cheap enough to warp often. */
private const val MAX_SEGMENT = 14f

private class OutlineBuilder(private val maxSegment: Float) {
    private val contours = ArrayList<Contour>()
    private var points = FloatArrayList()
    private var startX = 0f
    private var startY = 0f
    private var x = 0f
    private var y = 0f

    /** Second control point of the last cubic (for S) or the control point of the last quadratic (for T). */
    private var lastCubicX = Float.NaN
    private var lastCubicY = Float.NaN
    private var lastQuadX = Float.NaN
    private var lastQuadY = Float.NaN

    fun add(node: PathNode) {
        val wasCubic = !lastCubicX.isNaN()
        val wasQuad = !lastQuadX.isNaN()
        val reflectCubicX = if (wasCubic) 2 * x - lastCubicX else x
        val reflectCubicY = if (wasCubic) 2 * y - lastCubicY else y
        val reflectQuadX = if (wasQuad) 2 * x - lastQuadX else x
        val reflectQuadY = if (wasQuad) 2 * y - lastQuadY else y
        lastCubicX = Float.NaN
        lastCubicY = Float.NaN
        lastQuadX = Float.NaN
        lastQuadY = Float.NaN
        when (node) {
            is PathNode.MoveTo -> moveTo(node.x, node.y)
            is PathNode.RelativeMoveTo -> moveTo(x + node.dx, y + node.dy)
            is PathNode.LineTo -> lineTo(node.x, node.y)
            is PathNode.RelativeLineTo -> lineTo(x + node.dx, y + node.dy)
            is PathNode.HorizontalTo -> lineTo(node.x, y)
            is PathNode.RelativeHorizontalTo -> lineTo(x + node.dx, y)
            is PathNode.VerticalTo -> lineTo(x, node.y)
            is PathNode.RelativeVerticalTo -> lineTo(x, y + node.dy)
            is PathNode.CurveTo -> cubicTo(node.x1, node.y1, node.x2, node.y2, node.x3, node.y3)
            is PathNode.RelativeCurveTo ->
                cubicTo(x + node.dx1, y + node.dy1, x + node.dx2, y + node.dy2, x + node.dx3, y + node.dy3)
            is PathNode.ReflectiveCurveTo -> cubicTo(reflectCubicX, reflectCubicY, node.x1, node.y1, node.x2, node.y2)
            is PathNode.RelativeReflectiveCurveTo ->
                cubicTo(reflectCubicX, reflectCubicY, x + node.dx1, y + node.dy1, x + node.dx2, y + node.dy2)
            is PathNode.QuadTo -> quadTo(node.x1, node.y1, node.x2, node.y2)
            is PathNode.RelativeQuadTo -> quadTo(x + node.dx1, y + node.dy1, x + node.dx2, y + node.dy2)
            is PathNode.ReflectiveQuadTo -> quadTo(reflectQuadX, reflectQuadY, node.x, node.y)
            is PathNode.RelativeReflectiveQuadTo -> quadTo(reflectQuadX, reflectQuadY, x + node.dx, y + node.dy)
            is PathNode.ArcTo -> arcTo(
                node.horizontalEllipseRadius, node.verticalEllipseRadius, node.theta,
                node.isMoreThanHalf, node.isPositiveArc, node.arcStartX, node.arcStartY,
            )
            is PathNode.RelativeArcTo -> arcTo(
                node.horizontalEllipseRadius, node.verticalEllipseRadius, node.theta,
                node.isMoreThanHalf, node.isPositiveArc, x + node.arcStartDx, y + node.arcStartDy,
            )
            PathNode.Close -> close()
        }
    }

    fun finish(): List<Contour> {
        flush(closed = false)
        return contours
    }

    private fun moveTo(nx: Float, ny: Float) {
        flush(closed = false)
        startX = nx
        startY = ny
        x = nx
        y = ny
        points.add(nx, ny)
    }

    private fun ensureStarted() {
        if (points.size == 0) points.add(x, y)
    }

    private fun lineTo(nx: Float, ny: Float) {
        ensureStarted()
        appendCubic(x + (nx - x) / 3, y + (ny - y) / 3, x + (nx - x) * 2 / 3, y + (ny - y) * 2 / 3, nx, ny)
    }

    private fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, nx: Float, ny: Float) {
        ensureStarted()
        appendCubic(x1, y1, x2, y2, nx, ny)
        lastCubicX = x2
        lastCubicY = y2
    }

    private fun quadTo(qx: Float, qy: Float, nx: Float, ny: Float) {
        ensureStarted()
        appendCubic(x + (qx - x) * 2 / 3, y + (qy - y) * 2 / 3, nx + (qx - nx) * 2 / 3, ny + (qy - ny) * 2 / 3, nx, ny)
        lastQuadX = qx
        lastQuadY = qy
    }

    private fun close() {
        if (points.size == 0) return
        if (x != startX || y != startY) lineTo(startX, startY)
        flush(closed = true)
        x = startX
        y = startY
    }

    private fun flush(closed: Boolean) {
        if (points.size > 2) contours += Contour(points.toArray(), closed)
        points = FloatArrayList()
    }

    /** Appends one cubic from the current point, split in halves while it is longer than [maxSegment]. */
    private fun appendCubic(x1: Float, y1: Float, x2: Float, y2: Float, nx: Float, ny: Float, depth: Int = 0) {
        val length = hypot(x1 - x, y1 - y) + hypot(x2 - x1, y2 - y1) + hypot(nx - x2, ny - y2)
        if (length > maxSegment && depth < MAX_SPLIT_DEPTH) {
            // de Casteljau at t = ½.
            val ax = (x + x1) / 2
            val ay = (y + y1) / 2
            val bx = (x1 + x2) / 2
            val by = (y1 + y2) / 2
            val cx = (x2 + nx) / 2
            val cy = (y2 + ny) / 2
            val abx = (ax + bx) / 2
            val aby = (ay + by) / 2
            val bcx = (bx + cx) / 2
            val bcy = (by + cy) / 2
            val mx = (abx + bcx) / 2
            val my = (aby + bcy) / 2
            appendCubic(ax, ay, abx, aby, mx, my, depth + 1)
            appendCubic(bcx, bcy, cx, cy, nx, ny, depth + 1)
            return
        }
        points.add(x1, y1)
        points.add(x2, y2)
        points.add(nx, ny)
        x = nx
        y = ny
    }

    /**
     * An elliptical arc from the current point to ([nx], [ny]) as cubics of at most a quarter turn
     * each (the SVG "endpoint to centre" conversion, SVG 1.1 appendix F.6).
     */
    private fun arcTo(rxIn: Float, ryIn: Float, thetaDeg: Float, largeArc: Boolean, sweep: Boolean, nx: Float, ny: Float) {
        ensureStarted()
        if (nx == x && ny == y) return
        var rx = abs(rxIn.toDouble())
        var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) {
            lineTo(nx, ny)
            return
        }
        val phi = thetaDeg * PI / 180.0
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        val hx = (x - nx) / 2.0
        val hy = (y - ny) / 2.0
        val x1p = cosPhi * hx + sinPhi * hy
        val y1p = -sinPhi * hx + cosPhi * hy
        val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lambda > 1) {
            val s = sqrt(lambda)
            rx *= s
            ry *= s
        }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        val coef = (if (largeArc == sweep) -1 else 1) * sqrt(max(0.0, num / den))
        val cxp = coef * rx * y1p / ry
        val cyp = -coef * ry * x1p / rx
        val cx = cosPhi * cxp - sinPhi * cyp + (x + nx) / 2.0
        val cy = sinPhi * cxp + cosPhi * cyp + (y + ny) / 2.0
        val start = atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
        var sweepAngle = atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx) - start
        if (sweep && sweepAngle < 0) sweepAngle += 2 * PI
        if (!sweep && sweepAngle > 0) sweepAngle -= 2 * PI
        val n = max(1, ceil(abs(sweepAngle) / (PI / 2) - 1e-7).toInt())
        val delta = sweepAngle / n
        val k = 4.0 / 3.0 * tan(delta / 4)
        fun px(ux: Double, uy: Double) = (cx + rx * cosPhi * ux - ry * sinPhi * uy).toFloat()
        fun py(ux: Double, uy: Double) = (cy + rx * sinPhi * ux + ry * cosPhi * uy).toFloat()
        var t = start
        for (i in 0 until n) {
            val t2 = t + delta
            val c1x = cos(t) - k * sin(t)
            val c1y = sin(t) + k * cos(t)
            val c2x = cos(t2) + k * sin(t2)
            val c2y = sin(t2) - k * cos(t2)
            // The last piece ends exactly on the requested point (no rounding gap before the next command).
            val ex = if (i == n - 1) nx else px(cos(t2), sin(t2))
            val ey = if (i == n - 1) ny else py(cos(t2), sin(t2))
            appendCubic(px(c1x, c1y), py(c1x, c1y), px(c2x, c2y), py(c2x, c2y), ex, ey)
            t = t2
        }
    }

    private companion object {
        const val MAX_SPLIT_DEPTH = 6
    }
}

/** A growable float list (avoids boxing thousands of coordinates). */
private class FloatArrayList {
    private var data = FloatArray(64)
    var size = 0
        private set

    fun add(a: Float, b: Float) {
        if (size + 2 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = a
        data[size++] = b
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
