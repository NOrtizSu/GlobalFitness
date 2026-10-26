package com.enjoyyourtime.globalfitness.ui.training

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.charts.AxisMath
import com.enjoyyourtime.globalfitness.ui.theme.ChartTheme
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import kotlin.math.abs

/** One session on the progress chart: when, and the metric's value. */
data class ProgressPoint(val t: Long, val v: Double)

private class ChartBounds {
    var left = 0f
    var right = 0f
}

/** A span longer than this labels the time axis with months instead of days. */
private const val LONG_SPAN_MS = 200L * 24 * 3600 * 1000

/**
 * The progress of one exercise: a point per session joined by a line, nice round Y ticks and four
 * dates. Press or drag to read a session (date and value). [formatValue] adds the unit; [formatTick]
 * writes a Y tick with the decimals its step needs (a time as "25:00"); [label] names the metric for
 * TalkBack. [points] must not be empty.
 */
@Composable
fun ProgressChart(
    points: List<ProgressPoint>,
    formatValue: (Double) -> String,
    label: String,
    modifier: Modifier = Modifier,
    formatTick: (value: Double, decimals: Int) -> String = { v, d -> Fmt.num(v, d) },
    height: Dp = 220.dp,
) {
    val colors = ChartTheme.colors
    val surface = ChartTheme.surface
    val tooltipBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val tooltipBorder = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val labelStyle = MaterialTheme.typography.labelSmall.merge(TabularNumbers).copy(color = colors.mutedInk)
    val valueStyle = MaterialTheme.typography.titleSmall.merge(TabularNumbers).copy(color = colors.ink, fontWeight = FontWeight.SemiBold)
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val bounds = remember { ChartBounds() }

    val xStart = points.first().t
    val xEnd = points.last().t.coerceAtLeast(xStart + 1)
    val span = xEnd - xStart
    val ticks = remember(points) { AxisMath.niceTicks(points.minOf { it.v }, points.maxOf { it.v }) }
    val decimals = AxisMath.decimalsFor(ticks.step)
    val xTimes = remember(xStart, xEnd) { AxisMath.timeTicks(xStart, xEnd, 4) }
    val yLabels = remember(ticks, labelStyle, measurer, formatTick) { ticks.values.map { measurer.measure(formatTick(it, decimals), labelStyle) } }
    val xLabels = remember(xTimes, labelStyle, measurer) {
        xTimes.map { measurer.measure(if (span > LONG_SPAN_MS) Fmt.monthShortYear(it) else Fmt.dayMonth(it), labelStyle) }
    }
    val xLabelHeight = remember(labelStyle, measurer) { measurer.measure("0", labelStyle).size.height.toFloat() }
    val description = Fmt.plural(
        R.plurals.training_chart_desc,
        points.size,
        label,
        Fmt.count(points.size.toLong()),
        formatValue(points.first().v),
        formatValue(points.last().v),
        formatValue(points.maxOf { it.v }),
    )

    fun nearest(x: Float): Int? {
        if (bounds.right <= bounds.left) return null
        val t = xStart + (span * ((x - bounds.left) / (bounds.right - bounds.left)).coerceIn(0f, 1f).toDouble()).toLong()
        return points.indices.minByOrNull { abs(points[it].t - t) }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(points) { detectTapGestures(onPress = { selected = nearest(it.x) }) }
            .pointerInput(points) {
                detectHorizontalDragGestures(onDragStart = { selected = nearest(it.x) }) { change, _ ->
                    change.consume()
                    selected = nearest(change.position.x)
                }
            },
    ) {
        val left = yLabels.maxOf { it.size.width } + 10.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - xLabelHeight - 8.dp.toPx()
        bounds.left = left
        bounds.right = right
        val yRange = (ticks.max - ticks.min).takeIf { it > 0 } ?: 1.0
        // A single session sits in the middle.
        fun xOf(t: Long): Float = if (points.size == 1) (left + right) / 2f else left + ((t - xStart).toDouble() / span).toFloat() * (right - left)
        fun yOf(v: Double): Float = bottom - ((v - ticks.min) / yRange).toFloat() * (bottom - top)

        ticks.values.forEachIndexed { i, v ->
            val y = yOf(v)
            drawLine(colors.gridline, Offset(left, y), Offset(right, y), strokeWidth = 1f)
            val layout = yLabels[i]
            drawText(layout, topLeft = Offset(left - 6.dp.toPx() - layout.size.width, y - layout.size.height / 2f))
        }
        drawLine(colors.axis, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1.dp.toPx())
        if (points.size > 1) {
            xTimes.forEachIndexed { i, t ->
                val layout = xLabels[i]
                val w = layout.size.width.toFloat()
                val x = xOf(t)
                val lx = when (i) {
                    0 -> x
                    xTimes.lastIndex -> x - w
                    else -> x - w / 2f
                }.coerceIn(left, (right - w).coerceAtLeast(left))
                drawText(layout, topLeft = Offset(lx, bottom + 5.dp.toPx()))
            }
        } else {
            val layout = xLabels.first()
            drawText(layout, topLeft = Offset(xOf(xStart) - layout.size.width / 2f, bottom + 5.dp.toPx()))
        }

        if (points.size > 1) {
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(xOf(p.t), yOf(p.v)) else path.lineTo(xOf(p.t), yOf(p.v)) }
            drawPath(path, colors.series1, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val radius = 3.5.dp.toPx()
        for (p in points) {
            val c = Offset(xOf(p.t), yOf(p.v))
            drawCircle(surface, radius + 2.dp.toPx(), c)
            drawCircle(colors.series1, radius, c)
        }

        val sel = selected?.let { points.getOrNull(it) } ?: return@Canvas
        val sx = xOf(sel.t)
        val sy = yOf(sel.v)
        drawLine(colors.mutedInk, Offset(sx, top), Offset(sx, bottom), strokeWidth = 1.dp.toPx())
        drawCircle(surface, 7.dp.toPx(), Offset(sx, sy))
        drawCircle(colors.series1, 5.dp.toPx(), Offset(sx, sy))
        val lines = listOf(measurer.measure(Fmt.dayMonthYear(sel.t), labelStyle), measurer.measure(formatValue(sel.v), valueStyle))
        val pad = 8.dp.toPx()
        val boxW = lines.maxOf { it.size.width } + pad * 2
        val boxH = lines.sumOf { it.size.height } + 2.dp.toPx() + pad * 2
        val offset = 12.dp.toPx()
        val bx = (if (sx + offset + boxW <= size.width) sx + offset else sx - offset - boxW).coerceIn(0f, (size.width - boxW).coerceAtLeast(0f))
        val by = (sy - boxH / 2f).coerceIn(0f, (size.height - boxH).coerceAtLeast(0f))
        val corner = CornerRadius(8.dp.toPx())
        drawRoundRect(tooltipBackground, Offset(bx, by), Size(boxW, boxH), corner)
        drawRoundRect(tooltipBorder, Offset(bx, by), Size(boxW, boxH), corner, style = Stroke(1f))
        var ty = by + pad
        for (layout in lines) {
            drawText(layout, topLeft = Offset(bx + pad, ty))
            ty += layout.size.height + 2.dp.toPx()
        }
    }
}
