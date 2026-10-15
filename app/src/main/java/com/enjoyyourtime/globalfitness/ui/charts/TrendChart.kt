package com.enjoyyourtime.globalfitness.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.domain.TimePoint
import com.enjoyyourtime.globalfitness.domain.Trends
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.theme.ChartColors
import com.enjoyyourtime.globalfitness.ui.theme.ChartTheme
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import kotlin.math.abs

/** Shaded reference range drawn behind the data. */
data class ChartBand(val range: ClosedFloatingPointRange<Double>, val label: String)

/** Dashed horizontal reference line (e.g. weight goal). */
data class ChartTarget(val value: Double, val label: String)

private class PlotGeometry {
    var left = 0f
    var right = 0f
}

/**
 * Time-series chart: raw measurements as translucent dots, 7-day moving average as a solid
 * line, optional healthy band and target line. Press or drag to scrub.
 */
@Composable
fun TrendChart(
    points: List<TimePoint>,
    average: List<TimePoint>,
    xStart: Long,
    xEnd: Long,
    valueDecimals: Int,
    unit: String,
    metricLabel: String,
    band: ChartBand?,
    target: ChartTarget?,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
) {
    val colors = ChartTheme.colors
    val surface = MaterialTheme.colorScheme.surface
    val tooltipBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val tooltipBorder = MaterialTheme.colorScheme.outlineVariant
    val textMeasurer = rememberTextMeasurer(cacheSize = 48)
    val labelStyle = MaterialTheme.typography.labelSmall.merge(TabularNumbers).copy(color = colors.mutedInk)
    val tooltipValueStyle = MaterialTheme.typography.titleSmall.merge(TabularNumbers)
        .copy(color = colors.ink, fontWeight = FontWeight.SemiBold)
    val tooltipBodyStyle = MaterialTheme.typography.labelSmall.merge(TabularNumbers).copy(color = colors.ink)

    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val geometry = remember { PlotGeometry() }

    val yTicks = remember(points, average, target) {
        val values = points.map { it.v } + average.map { it.v } + listOfNotNull(target?.value)
        if (values.isEmpty()) AxisMath.niceTicks(0.0, 1.0) else AxisMath.niceTicks(values.min(), values.max())
    }
    val tickDecimals = AxisMath.decimalsFor(yTicks.step)
    val span = (xEnd - xStart).coerceAtLeast(1L)
    val xTickTimes = remember(xStart, xEnd) { AxisMath.timeTicks(xStart, xEnd, 4) }
    val longSpan = span > 200 * Trends.DAY_MS
    val averageByTime = remember(average) { average.associate { it.t to it.v } }

    // Static labels are measured once per input change, not on every scrub frame.
    val yLabels = remember(yTicks, tickDecimals, labelStyle, textMeasurer) {
        yTicks.values.map { textMeasurer.measure(Fmt.num(it, tickDecimals), labelStyle) }
    }
    val xLabels = remember(xTickTimes, longSpan, labelStyle, textMeasurer) {
        xTickTimes.map { t -> textMeasurer.measure(if (longSpan) Fmt.monthShortYear(t) else Fmt.dayMonth(t), labelStyle) }
    }
    val xLabelHeight = remember(labelStyle, textMeasurer) { textMeasurer.measure("0", labelStyle).size.height.toFloat() }
    val bandLabel = remember(band, labelStyle, textMeasurer) { band?.let { textMeasurer.measure(it.label, labelStyle) } }
    val targetLabel = remember(target, labelStyle, textMeasurer) { target?.let { textMeasurer.measure(it.label, labelStyle) } }

    fun nearestIndex(x: Float): Int? {
        if (points.isEmpty() || geometry.right <= geometry.left) return null
        val fraction = ((x - geometry.left) / (geometry.right - geometry.left)).coerceIn(0f, 1f)
        val t = xStart + (span * fraction.toDouble()).toLong()
        var lo = 0
        var hi = points.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (points[mid].t < t) lo = mid + 1 else hi = mid
        }
        return listOf(lo - 1, lo).filter { it in points.indices }.minByOrNull { abs(points[it].t - t) }
    }

    val description = buildString {
        append("Gráfico de $metricLabel con ${points.size} mediciones")
        if (points.isNotEmpty()) {
            append(", de ${Fmt.num(points.minOf { it.v }, valueDecimals)} a ${Fmt.num(points.maxOf { it.v }, valueDecimals)} $unit")
        }
    }

    Column(modifier) {
        ChartLegend(colors)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = description }
                .pointerInput(points, xStart, xEnd) {
                    detectTapGestures(onPress = { pos -> selected = nearestIndex(pos.x) })
                }
                .pointerInput(points, xStart, xEnd) {
                    detectHorizontalDragGestures(
                        onDragStart = { pos -> selected = nearestIndex(pos.x) },
                    ) { change, _ ->
                        change.consume()
                        selected = nearestIndex(change.position.x)
                    }
                },
        ) {
            val labelWidth = yLabels.maxOf { it.size.width }.toFloat()
            val left = labelWidth + 10.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - xLabelHeight - 8.dp.toPx()
            geometry.left = left
            geometry.right = right
            val yRange = (yTicks.max - yTicks.min).takeIf { it > 0 } ?: 1.0

            fun xOf(t: Long): Float = left + ((t - xStart).toDouble() / span).toFloat() * (right - left)
            fun yOf(v: Double): Float = bottom - ((v - yTicks.min) / yRange).toFloat() * (bottom - top)

            // Gridlines and Y labels (one axis only).
            yTicks.values.forEachIndexed { i, v ->
                val y = yOf(v)
                drawLine(colors.gridline, Offset(left, y), Offset(right, y), strokeWidth = 1f)
                val layout = yLabels[i]
                drawText(layout, topLeft = Offset(left - 6.dp.toPx() - layout.size.width, y - layout.size.height / 2f))
            }
            drawLine(colors.axis, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1.dp.toPx())

            // X date labels.
            xTickTimes.forEachIndexed { i, t ->
                val layout = xLabels[i]
                val x = xOf(t)
                val w = layout.size.width.toFloat()
                val lx = when (i) {
                    0 -> x
                    xTickTimes.lastIndex -> x - w
                    else -> x - w / 2f
                }.coerceIn(left, (right - w).coerceAtLeast(left))
                drawLine(colors.axis, Offset(x, bottom), Offset(x, bottom + 3.dp.toPx()), strokeWidth = 1f)
                drawText(layout, topLeft = Offset(lx, bottom + 5.dp.toPx()))
            }

            clipRect(left, top, right, bottom) {
                band?.let { b ->
                    val yTop = yOf(b.range.endInclusive).coerceIn(top, bottom)
                    val yBottom = yOf(b.range.start).coerceIn(top, bottom)
                    if (yBottom - yTop > 0.5f) {
                        drawRect(
                            color = colors.good.copy(alpha = 0.10f),
                            topLeft = Offset(left, yTop),
                            size = Size(right - left, yBottom - yTop),
                        )
                        val layout = bandLabel!!
                        if (yBottom - yTop > layout.size.height + 4.dp.toPx()) {
                            drawText(layout, topLeft = Offset(left + 6.dp.toPx(), yTop + 2.dp.toPx()))
                        }
                    }
                }

                target?.let { tg ->
                    val y = yOf(tg.value)
                    drawLine(
                        color = colors.mutedInk,
                        start = Offset(left, y),
                        end = Offset(right, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                    )
                    val layout = targetLabel!!
                    val ly = if (y - layout.size.height - 2.dp.toPx() >= top) y - layout.size.height - 2.dp.toPx() else y + 2.dp.toPx()
                    drawText(layout, topLeft = Offset(right - layout.size.width - 4.dp.toPx(), ly))
                }

                val radius = 4.dp.toPx()
                val ring = 2.dp.toPx()
                val dotColor = colors.series1.copy(alpha = 0.45f)
                for (p in points) {
                    val c = Offset(xOf(p.t), yOf(p.v))
                    drawCircle(surface, radius + ring, c)
                    drawCircle(dotColor, radius, c)
                }

                if (average.size >= 2) {
                    val path = Path()
                    average.forEachIndexed { i, p ->
                        if (i == 0) path.moveTo(xOf(p.t), yOf(p.v)) else path.lineTo(xOf(p.t), yOf(p.v))
                    }
                    drawPath(
                        path = path,
                        color = colors.series1,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }

            // Scrubbing: crosshair, highlighted point and tooltip.
            val sel = selected?.let { points.getOrNull(it) } ?: return@Canvas
            val sx = xOf(sel.t)
            val sy = yOf(sel.v)
            drawLine(colors.mutedInk, Offset(sx, top), Offset(sx, bottom), strokeWidth = 1.dp.toPx())
            drawCircle(surface, 5.dp.toPx() + 2.dp.toPx(), Offset(sx, sy))
            drawCircle(colors.series1, 5.dp.toPx(), Offset(sx, sy))

            val lines = buildList {
                add(textMeasurer.measure(Fmt.dateTime(sel.t), labelStyle))
                add(textMeasurer.measure(Fmt.withUnit(Fmt.num(sel.v, valueDecimals), unit), tooltipValueStyle))
                averageByTime[sel.t]?.let {
                    add(textMeasurer.measure("Media 7 días: " + Fmt.withUnit(Fmt.num(it, valueDecimals), unit), tooltipBodyStyle))
                }
            }
            val pad = 8.dp.toPx()
            val gap = 2.dp.toPx()
            val boxW = lines.maxOf { it.size.width } + pad * 2
            val boxH = lines.sumOf { it.size.height } + gap * (lines.size - 1) + pad * 2
            val offset = 12.dp.toPx()
            var bx = if (sx + offset + boxW <= size.width) sx + offset else sx - offset - boxW
            bx = bx.coerceIn(0f, (size.width - boxW).coerceAtLeast(0f))
            val by = (sy - boxH / 2f).coerceIn(0f, (size.height - boxH).coerceAtLeast(0f))
            val corner = CornerRadius(8.dp.toPx())
            drawRoundRect(tooltipBackground, Offset(bx, by), Size(boxW, boxH), corner)
            drawRoundRect(tooltipBorder, Offset(bx, by), Size(boxW, boxH), corner, style = Stroke(1f))
            var ty = by + pad
            for (layout in lines) {
                drawText(layout, topLeft = Offset(bx + pad, ty))
                ty += layout.size.height + gap
            }
        }
    }
}

@Composable
private fun ChartLegend(colors: ChartColors) {
    val surface = MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier.padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(14.dp)) {
                drawCircle(surface, 6.dp.toPx())
                drawCircle(colors.series1.copy(alpha = 0.45f), 4.dp.toPx())
            }
            Spacer(Modifier.width(4.dp))
            Text("Mediciones", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = 18.dp, height = 14.dp)) {
                drawLine(
                    colors.series1,
                    Offset(1.dp.toPx(), center.y),
                    Offset(size.width - 1.dp.toPx(), center.y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text("Media 7 días", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
