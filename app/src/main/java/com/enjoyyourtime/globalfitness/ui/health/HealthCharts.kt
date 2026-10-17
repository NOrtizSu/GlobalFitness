package com.enjoyyourtime.globalfitness.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.health.HrSample
import com.enjoyyourtime.globalfitness.health.SleepStage
import com.enjoyyourtime.globalfitness.health.TimedValue
import com.enjoyyourtime.globalfitness.health.domain.DailyHrRange
import com.enjoyyourtime.globalfitness.health.domain.NightSleep
import com.enjoyyourtime.globalfitness.health.domain.SleepAnalysis
import com.enjoyyourtime.globalfitness.health.labelRes
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.charts.AxisMath
import com.enjoyyourtime.globalfitness.ui.charts.AxisTicks
import com.enjoyyourtime.globalfitness.ui.theme.ChartColors
import com.enjoyyourtime.globalfitness.ui.theme.ChartTheme
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import java.time.LocalDate
import kotlin.math.abs

// Health charts follow the app's chart rules: 2dp lines, recessive gridlines, one Y axis,
// press / drag for a tooltip, a legend when there are two or more series, and status never
// conveyed by color alone.

/** Stage colors, derived from the chart tokens. */
fun ChartColors.stage(stage: SleepStage): Color = when (stage) {
    SleepStage.DEEP -> series4
    SleepStage.LIGHT -> series1
    SleepStage.REM -> series3
    SleepStage.AWAKE -> series2
    SleepStage.UNKNOWN -> mutedInk
}

enum class LegendKind { DOT, LINE, DASHED, BOX }

data class LegendItem(val label: String, val color: Color, val kind: LegendKind)

@Composable
fun ChartLegend(items: List<LegendItem>, modifier: Modifier = Modifier) {
    val surface = ChartTheme.surface
    FlowRow(
        modifier = modifier.padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 18.dp, height = 14.dp)) {
                    when (item.kind) {
                        LegendKind.DOT -> {
                            drawCircle(surface, 6.dp.toPx())
                            drawCircle(item.color, 4.dp.toPx())
                        }
                        LegendKind.LINE, LegendKind.DASHED -> drawLine(
                            item.color,
                            Offset(1.dp.toPx(), center.y),
                            Offset(size.width - 1.dp.toPx(), center.y),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            pathEffect = if (item.kind == LegendKind.DASHED) {
                                PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                            } else {
                                null
                            },
                        )
                        LegendKind.BOX -> drawRoundRect(
                            item.color,
                            topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                            size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                Text(item.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---- Shared drawing helpers ---------------------------------------------------------------

private class ChartText(
    val measurer: TextMeasurer,
    val label: TextStyle,
    val value: TextStyle,
    val body: TextStyle,
)

@Composable
private fun rememberChartText(): ChartText {
    val colors = ChartTheme.colors
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val label = MaterialTheme.typography.labelSmall.merge(TabularNumbers).copy(color = colors.mutedInk)
    val value = MaterialTheme.typography.titleSmall.merge(TabularNumbers).copy(color = colors.ink, fontWeight = FontWeight.SemiBold)
    val body = MaterialTheme.typography.labelSmall.merge(TabularNumbers).copy(color = colors.ink)
    return remember(measurer, label, value, body) { ChartText(measurer, label, value, body) }
}

/** Plot rectangle, written while drawing and read by gesture handlers. */
private class Plot {
    var left = 0f
    var right = 0f
    var top = 0f
    var bottom = 0f
    val width get() = right - left
}

private fun DrawScope.layoutPlot(plot: Plot, yLabelWidth: Float, xLabelHeight: Float) {
    plot.left = yLabelWidth + 10.dp.toPx()
    plot.right = size.width - 8.dp.toPx()
    plot.top = 8.dp.toPx()
    plot.bottom = size.height - xLabelHeight - 8.dp.toPx()
}

/** Gridlines + Y labels (single axis) and the baseline. */
private fun DrawScope.drawYAxis(plot: Plot, ticks: AxisTicks, labels: List<TextLayoutResult>, colors: ChartColors) {
    val range = (ticks.max - ticks.min).takeIf { it > 0 } ?: 1.0
    ticks.values.forEachIndexed { i, v ->
        val y = plot.bottom - ((v - ticks.min) / range).toFloat() * (plot.bottom - plot.top)
        drawLine(colors.gridline, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1f)
        val layout = labels[i]
        drawText(layout, topLeft = Offset(plot.left - 6.dp.toPx() - layout.size.width, y - layout.size.height / 2f))
    }
    drawLine(colors.axis, Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), strokeWidth = 1.dp.toPx())
}

/** X labels at [xs]; the first is left-aligned, the last right-aligned, others centered. */
private fun DrawScope.drawXLabels(plot: Plot, xs: List<Float>, labels: List<TextLayoutResult>, colors: ChartColors) {
    xs.forEachIndexed { i, x ->
        val layout = labels[i]
        val w = layout.size.width.toFloat()
        val lx = when (i) {
            0 -> x - if (xs.size == 1) w / 2f else 0f
            xs.lastIndex -> x - w
            else -> x - w / 2f
        }.coerceIn(0f, (size.width - w).coerceAtLeast(0f))
        drawLine(colors.axis, Offset(x, plot.bottom), Offset(x, plot.bottom + 3.dp.toPx()), strokeWidth = 1f)
        drawText(layout, topLeft = Offset(lx, plot.bottom + 5.dp.toPx()))
    }
}

private fun DrawScope.drawTooltip(lines: List<TextLayoutResult>, anchor: Offset, background: Color, border: Color) {
    if (lines.isEmpty()) return
    val pad = 8.dp.toPx()
    val gap = 2.dp.toPx()
    val boxW = lines.maxOf { it.size.width } + pad * 2
    val boxH = lines.sumOf { it.size.height } + gap * (lines.size - 1) + pad * 2
    val offset = 12.dp.toPx()
    var bx = if (anchor.x + offset + boxW <= size.width) anchor.x + offset else anchor.x - offset - boxW
    bx = bx.coerceIn(0f, (size.width - boxW).coerceAtLeast(0f))
    val by = (anchor.y - boxH / 2f).coerceIn(0f, (size.height - boxH).coerceAtLeast(0f))
    val corner = CornerRadius(8.dp.toPx())
    drawRoundRect(background, Offset(bx, by), Size(boxW, boxH), corner)
    drawRoundRect(border, Offset(bx, by), Size(boxW, boxH), corner, style = Stroke(1f))
    var ty = by + pad
    for (layout in lines) {
        drawText(layout, topLeft = Offset(bx + pad, ty))
        ty += layout.size.height + gap
    }
}

/** Reference-line label on a translucent surface chip so it stays legible over data. */
private fun DrawScope.drawLabelChip(layout: TextLayoutResult, topLeft: Offset, background: Color) {
    val padX = 3.dp.toPx()
    val padY = 1.dp.toPx()
    drawRoundRect(
        background.copy(alpha = 0.85f),
        topLeft = Offset(topLeft.x - padX, topLeft.y - padY),
        size = Size(layout.size.width + padX * 2, layout.size.height + padY * 2),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
    drawText(layout, topLeft = topLeft)
}

private fun DrawScope.dashedHorizontal(plot: Plot, y: Float, color: Color) {
    drawLine(
        color = color,
        start = Offset(plot.left, y),
        end = Offset(plot.right, y),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
    )
}

/** Press or horizontal drag selects the item under the finger (selection stays after release). */
private fun Modifier.scrub(key: Any?, pick: (Float) -> Unit): Modifier =
    this
        .pointerInput(key) { detectTapGestures(onPress = { pick(it.x) }) }
        .pointerInput(key) {
            detectHorizontalDragGestures(onDragStart = { pick(it.x) }) { change, _ ->
                change.consume()
                pick(change.position.x)
            }
        }

private fun yTicksLabels(text: ChartText, ticks: AxisTicks): List<TextLayoutResult> {
    val decimals = AxisMath.decimalsFor(ticks.step)
    return ticks.values.map { text.measurer.measure(Fmt.num(it, decimals), text.label) }
}

private fun dayTickIndices(count: Int): List<Int> =
    if (count <= 1) listOf(0) else listOf(0, (count - 1) / 3, (count - 1) * 2 / 3, count - 1).distinct()

// ---- Daily steps ------------------------------------------------------------------------

/** Daily steps bars with a dashed goal line. [days] is oldest first; null = no data. */
@Composable
fun StepsBarChart(days: List<Pair<LocalDate, Long?>>, goal: Long, modifier: Modifier = Modifier, height: Dp = 200.dp) {
    val colors = ChartTheme.colors
    val surface = ChartTheme.surface
    val text = rememberChartText()
    val tooltipBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val tooltipBorder = MaterialTheme.colorScheme.outlineVariant
    var selected by remember(days) { mutableStateOf<Int?>(null) }
    val plot = remember { Plot() }
    val maxValue = maxOf(days.maxOfOrNull { it.second ?: 0L } ?: 0L, goal)
    val ticks = remember(maxValue) { AxisMath.niceTicks(0.0, maxValue.toDouble()) }
    val yLabels = remember(ticks, text) { ticks.values.map { text.measurer.measure(HealthFormat.count(it.toLong()), text.label) } }
    val tickIdx = remember(days.size) { dayTickIndices(days.size) }
    val xLabels = remember(days, tickIdx, text) { tickIdx.map { text.measurer.measure(HealthFormat.dayMonth(days[it].first), text.label) } }
    val xLabelHeight = remember(text) { text.measurer.measure("0", text.label).size.height.toFloat() }
    val goalText = stringResource(R.string.chart_goal, HealthFormat.count(goal))
    val goalLabel = remember(goalText, text) { text.measurer.measure(goalText, text.label) }
    val withData = days.mapNotNull { it.second }
    val description = if (withData.isNotEmpty()) {
        Fmt.plural(R.plurals.chart_steps_desc_avg, days.size, days.size, HealthFormat.count(withData.average().toLong()))
    } else {
        Fmt.plural(R.plurals.chart_steps_desc, days.size, days.size)
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .scrub(days) { x ->
                if (days.isNotEmpty() && plot.width > 0) {
                    selected = ((x - plot.left) / plot.width * days.size).toInt().coerceIn(0, days.lastIndex)
                }
            },
    ) {
        layoutPlot(plot, yLabels.maxOf { it.size.width }.toFloat(), xLabelHeight)
        drawYAxis(plot, ticks, yLabels, colors)
        val range = (ticks.max - ticks.min).takeIf { it > 0 } ?: 1.0
        fun yOf(v: Double) = plot.bottom - ((v - ticks.min) / range).toFloat() * (plot.bottom - plot.top)
        val slot = plot.width / days.size.coerceAtLeast(1)
        val barW = (slot * 0.7f).coerceAtLeast(1.dp.toPx())
        drawXLabels(plot, tickIdx.map { plot.left + slot * (it + 0.5f) }, xLabels, colors)

        days.forEachIndexed { i, (_, steps) ->
            if (steps == null || steps <= 0) return@forEachIndexed
            val x = plot.left + slot * i + (slot - barW) / 2f
            val y = yOf(steps.toDouble())
            val alpha = if (selected == null || selected == i) 1f else 0.45f
            drawRoundRect(
                colors.series1.copy(alpha = alpha),
                topLeft = Offset(x, y),
                size = Size(barW, plot.bottom - y),
                cornerRadius = CornerRadius(minOf(barW / 2f, 3.dp.toPx())),
            )
        }
        val gy = yOf(goal.toDouble())
        dashedHorizontal(plot, gy, colors.mutedInk)
        drawLabelChip(goalLabel, Offset(plot.left + 6.dp.toPx(), gy - goalLabel.size.height - 3.dp.toPx()), surface)

        val sel = selected?.let { days.getOrNull(it) } ?: return@Canvas
        val sx = plot.left + slot * (selected!! + 0.5f)
        val steps = sel.second
        val lines = buildList {
            add(text.measurer.measure(HealthFormat.weekdayDayMonth(sel.first), text.label))
            add(text.measurer.measure(if (steps == null) Fmt.string(R.string.common_no_data) else HealthFormat.steps(steps), text.value))
            if (steps != null) {
                val pct = steps * 100.0 / goal
                add(
                    text.measurer.measure(
                        if (steps >= goal) Fmt.string(R.string.chart_goal_reached) else Fmt.string(R.string.chart_goal_percent, Fmt.percent(pct)),
                        text.body,
                    ),
                )
            }
        }
        drawTooltip(lines, Offset(sx, yOf((steps ?: 0L).toDouble())), tooltipBg, tooltipBorder)
    }
}

// ---- Heart rate of one day --------------------------------------------------------------

/** Heart rate through one day (line, gaps > 30 min not joined) plus the resting HR line. */
@Composable
fun HeartRateDayChart(
    samples: List<HrSample>,
    restingHr: Int?,
    dayStart: Long,
    dayEnd: Long,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
) {
    val colors = ChartTheme.colors
    val text = rememberChartText()
    val surface = ChartTheme.surface
    val tooltipBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val tooltipBorder = MaterialTheme.colorScheme.outlineVariant
    var selected by remember(samples) { mutableStateOf<Int?>(null) }
    val plot = remember { Plot() }
    val ticks = remember(samples, restingHr) {
        val values = samples.map { it.bpm.toDouble() } + listOfNotNull(restingHr?.toDouble())
        if (values.isEmpty()) AxisMath.niceTicks(40.0, 120.0) else AxisMath.niceTicks(values.min(), values.max())
    }
    val yLabels = remember(ticks, text) { yTicksLabels(text, ticks) }
    val xTimes = remember(dayStart, dayEnd) { AxisMath.timeTicks(dayStart, dayEnd, 5) }
    val xLabels = remember(xTimes, text) {
        // The end of the day reads "24:00" on a 24-hour clock (the next day's "00:00" otherwise).
        xTimes.mapIndexed { i, t ->
            text.measurer.measure(if (i == xTimes.lastIndex && Fmt.hour24 != false) "24:00" else Fmt.time(t), text.label)
        }
    }
    val xLabelHeight = remember(text) { text.measurer.measure("0", text.label).size.height.toFloat() }
    val span = (dayEnd - dayStart).coerceAtLeast(1L)
    val description = when {
        samples.isEmpty() -> stringResource(R.string.chart_hr_day_none)
        restingHr != null -> stringResource(
            R.string.chart_hr_day_desc_resting,
            HealthFormat.bpm(samples.minOf { it.bpm }),
            HealthFormat.bpm(samples.maxOf { it.bpm }),
            HealthFormat.bpm(restingHr),
        )
        else -> stringResource(R.string.chart_hr_day_desc, HealthFormat.bpm(samples.minOf { it.bpm }), HealthFormat.bpm(samples.maxOf { it.bpm }))
    }

    Column(modifier) {
        ChartLegend(
            buildList {
                add(LegendItem(stringResource(R.string.health_heart_rate), colors.series1, LegendKind.LINE))
                if (restingHr != null) add(LegendItem(stringResource(R.string.chart_resting), colors.series2, LegendKind.DASHED))
            },
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = description }
                .scrub(samples) { x ->
                    if (samples.isEmpty() || plot.width <= 0) return@scrub
                    val t = dayStart + ((x - plot.left) / plot.width * span).toLong()
                    selected = nearestByTime(samples.size, { samples[it].time }, t)
                },
        ) {
            layoutPlot(plot, yLabels.maxOf { it.size.width }.toFloat(), xLabelHeight)
            drawYAxis(plot, ticks, yLabels, colors)
            val range = (ticks.max - ticks.min).takeIf { it > 0 } ?: 1.0
            fun xOf(t: Long) = plot.left + ((t - dayStart).toDouble() / span).toFloat() * plot.width
            fun yOf(v: Double) = plot.bottom - ((v - ticks.min) / range).toFloat() * (plot.bottom - plot.top)
            drawXLabels(plot, xTimes.map { xOf(it) }, xLabels, colors)

            clipRect(plot.left, plot.top, plot.right, plot.bottom) {
                restingHr?.let { dashedHorizontal(plot, yOf(it.toDouble()), colors.series2) }
                val path = Path()
                var previous: HrSample? = null
                for (s in samples) {
                    val x = xOf(s.time)
                    val y = yOf(s.bpm.toDouble())
                    if (previous == null || s.time - previous.time > HR_GAP_MS) path.moveTo(x, y) else path.lineTo(x, y)
                    previous = s
                }
                drawPath(path, colors.series1, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (samples.size == 1) drawCircle(colors.series1, 3.dp.toPx(), Offset(xOf(samples[0].time), yOf(samples[0].bpm.toDouble())))
            }

            val sel = selected?.let { samples.getOrNull(it) } ?: return@Canvas
            val c = Offset(xOf(sel.time), yOf(sel.bpm.toDouble()))
            drawLine(colors.mutedInk, Offset(c.x, plot.top), Offset(c.x, plot.bottom), strokeWidth = 1.dp.toPx())
            drawCircle(surface, 7.dp.toPx(), c)
            drawCircle(colors.series1, 5.dp.toPx(), c)
            val lines = buildList {
                add(text.measurer.measure(Fmt.time(sel.time), text.label))
                add(text.measurer.measure(HealthFormat.bpm(sel.bpm), text.value))
                restingHr?.let { add(text.measurer.measure(Fmt.string(R.string.chart_resting_value, HealthFormat.bpm(it)), text.body)) }
            }
            drawTooltip(lines, c, tooltipBg, tooltipBorder)
        }
    }
}

private const val HR_GAP_MS = 30 * 60_000L

private fun nearestByTime(size: Int, timeAt: (Int) -> Long, t: Long): Int? {
    if (size == 0) return null
    var lo = 0
    var hi = size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (timeAt(mid) < t) lo = mid + 1 else hi = mid
    }
    return listOf(lo - 1, lo).filter { it in 0 until size }.minByOrNull { abs(timeAt(it) - t) }
}

// ---- Daily heart-rate range -------------------------------------------------------------

/** Daily min–max heart rate as range bars with the daily average as a dot. */
@Composable
fun HeartRateRangeChart(days: List<LocalDate>, ranges: List<DailyHrRange>, modifier: Modifier = Modifier, height: Dp = 200.dp) {
    val colors = ChartTheme.colors
    val text = rememberChartText()
    val surface = ChartTheme.surface
    val tooltipBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val tooltipBorder = MaterialTheme.colorScheme.outlineVariant
    var selected by remember(days, ranges) { mutableStateOf<Int?>(null) }
    val plot = remember { Plot() }
    val byDate = remember(ranges) { ranges.associateBy { it.date } }
    val ticks = remember(ranges) {
        if (ranges.isEmpty()) AxisMath.niceTicks(40.0, 160.0) else AxisMath.niceTicks(ranges.minOf { it.min }.toDouble(), ranges.maxOf { it.max }.toDouble())
    }
    val yLabels = remember(ticks, text) { yTicksLabels(text, ticks) }
    val tickIdx = remember(days.size) { dayTickIndices(days.size) }
    val xLabels = remember(days, tickIdx, text) { tickIdx.map { text.measurer.measure(HealthFormat.dayMonth(days[it]), text.label) } }
    val xLabelHeight = remember(text) { text.measurer.measure("0", text.label).size.height.toFloat() }
    val description = if (ranges.isEmpty()) {
        stringResource(R.string.health_no_hr_data)
    } else {
        Fmt.plural(
            R.plurals.chart_hr_range_desc,
            ranges.size,
            ranges.size,
            HealthFormat.bpm(ranges.minOf { it.min }),
            HealthFormat.bpm(ranges.maxOf { it.max }),
        )
    }

    Column(modifier) {
        ChartLegend(
            listOf(
                LegendItem(stringResource(R.string.chart_hr_day_range), colors.series1.copy(alpha = 0.35f), LegendKind.BOX),
                LegendItem(stringResource(R.string.body_stat_average), colors.series1, LegendKind.DOT),
            ),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = description }
                .scrub(days) { x ->
                    if (days.isNotEmpty() && plot.width > 0) {
                        selected = ((x - plot.left) / plot.width * days.size).toInt().coerceIn(0, days.lastIndex)
                    }
                },
        ) {
            layoutPlot(plot, yLabels.maxOf { it.size.width }.toFloat(), xLabelHeight)
            drawYAxis(plot, ticks, yLabels, colors)
            val range = (ticks.max - ticks.min).takeIf { it > 0 } ?: 1.0
            fun yOf(v: Double) = plot.bottom - ((v - ticks.min) / range).toFloat() * (plot.bottom - plot.top)
            val slot = plot.width / days.size.coerceAtLeast(1)
            val barW = (slot * 0.55f).coerceIn(2.dp.toPx(), 10.dp.toPx())
            drawXLabels(plot, tickIdx.map { plot.left + slot * (it + 0.5f) }, xLabels, colors)
            days.forEachIndexed { i, d ->
                val r = byDate[d] ?: return@forEachIndexed
                val cx = plot.left + slot * (i + 0.5f)
                val yTop = yOf(r.max.toDouble())
                val yBottom = yOf(r.min.toDouble())
                val alpha = if (selected == null || selected == i) 0.35f else 0.18f
                drawRoundRect(
                    colors.series1.copy(alpha = alpha),
                    topLeft = Offset(cx - barW / 2f, yTop),
                    size = Size(barW, (yBottom - yTop).coerceAtLeast(2.dp.toPx())),
                    cornerRadius = CornerRadius(barW / 2f),
                )
                drawCircle(surface, 4.dp.toPx(), Offset(cx, yOf(r.avg.toDouble())))
                drawCircle(colors.series1, 3.dp.toPx(), Offset(cx, yOf(r.avg.toDouble())))
            }
            val index = selected ?: return@Canvas
            val day = days.getOrNull(index) ?: return@Canvas
            val r = byDate[day]
            val cx = plot.left + slot * (index + 0.5f)
            val lines = buildList {
                add(text.measurer.measure(HealthFormat.weekdayDayMonth(day), text.label))
                if (r == null) {
                    add(text.measurer.measure(Fmt.string(R.string.common_no_data), text.value))
                } else {
                    add(text.measurer.measure(Fmt.string(R.string.value_bpm, "${Fmt.count(r.min.toLong())}–${Fmt.count(r.max.toLong())}"), text.value))
                    add(text.measurer.measure(Fmt.string(R.string.chart_average_value, HealthFormat.bpm(r.avg)), text.body))
                }
            }
            drawTooltip(lines, Offset(cx, r?.let { yOf(it.avg.toDouble()) } ?: plot.bottom), tooltipBg, tooltipBorder)
        }
    }
}

// ---- Hypnogram --------------------------------------------------------------------------

/** Stages of one sleep session over time; Y rows are labeled with the stage names. */
@Composable
fun Hypnogram(night: NightSleep, modifier: Modifier = Modifier, height: Dp = 180.dp) {
    val colors = ChartTheme.colors
    val text = rememberChartText()
    val tooltipBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val tooltipBorder = MaterialTheme.colorScheme.outlineVariant
    val session = night.mainSession
    val stages = remember(session) { SleepAnalysis.normalizedStages(session) }
    val rows = remember(stages) {
        val present = stages.map { it.stage }.toSet()
        listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.UNKNOWN, SleepStage.LIGHT, SleepStage.DEEP)
            .filter { it != SleepStage.UNKNOWN || it in present }
    }
    var selected by remember(stages) { mutableStateOf<Int?>(null) }
    val plot = remember { Plot() }
    val rowLabels = remember(rows, text) { rows.map { text.measurer.measure(Fmt.string(it.labelRes), text.label) } }
    val xTimes = remember(session) { AxisMath.timeTicks(session.start, session.end, 4) }
    val xLabels = remember(xTimes, text) { xTimes.map { text.measurer.measure(Fmt.time(it), text.label) } }
    val xLabelHeight = remember(text) { text.measurer.measure("0", text.label).size.height.toFloat() }
    val span = (session.end - session.start).coerceAtLeast(1L)
    val stageItems = SleepStage.entries.filter { night.minutesOf(it) > 0 }
        .map { stringResource(R.string.chart_composition_item, stringResource(it.labelRes), HealthFormat.minutes(night.minutesOf(it))) }
    val description = stringResource(R.string.chart_hypnogram_desc, Fmt.time(session.start), Fmt.time(session.end), Fmt.list(stageItems))

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .scrub(stages) { x ->
                if (stages.isEmpty() || plot.width <= 0) return@scrub
                val t = session.start + ((x - plot.left) / plot.width * span).toLong()
                selected = stages.indexOfFirst { t >= it.start && t < it.end }.takeIf { it >= 0 }
                    ?: nearestByTime(stages.size, { stages[it].start }, t)
            },
    ) {
        layoutPlot(plot, rowLabels.maxOf { it.size.width }.toFloat(), xLabelHeight)
        val rowH = (plot.bottom - plot.top) / rows.size
        rows.forEachIndexed { i, _ ->
            val cy = plot.top + rowH * (i + 0.5f)
            drawLine(colors.gridline, Offset(plot.left, cy), Offset(plot.right, cy), strokeWidth = 1f)
            val layout = rowLabels[i]
            drawText(layout, topLeft = Offset(plot.left - 6.dp.toPx() - layout.size.width, cy - layout.size.height / 2f))
        }
        drawLine(colors.axis, Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), strokeWidth = 1.dp.toPx())
        fun xOf(t: Long) = plot.left + ((t - session.start).toDouble() / span).toFloat() * plot.width
        drawXLabels(plot, xTimes.map { xOf(it) }, xLabels, colors)

        val barH = rowH * 0.6f
        stages.forEachIndexed { i, s ->
            val row = rows.indexOf(s.stage).takeIf { it >= 0 } ?: return@forEachIndexed
            val cy = plot.top + rowH * (row + 0.5f)
            val x0 = xOf(s.start)
            val x1 = xOf(s.end).coerceAtLeast(x0 + 1f)
            val alpha = if (selected == null || selected == i) 1f else 0.5f
            drawRoundRect(
                colors.stage(s.stage).copy(alpha = alpha),
                topLeft = Offset(x0, cy - barH / 2f),
                size = Size(x1 - x0, barH),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }

        val sel = selected?.let { stages.getOrNull(it) } ?: return@Canvas
        val row = rows.indexOf(sel.stage)
        val anchor = Offset((xOf(sel.start) + xOf(sel.end)) / 2f, plot.top + rowH * (row + 0.5f))
        val minutes = ((sel.end - sel.start) / 60_000L).toInt()
        val lines = listOf(
            text.measurer.measure("${Fmt.time(sel.start)}–${Fmt.time(sel.end)}", text.label),
            text.measurer.measure(Fmt.string(sel.stage.labelRes), text.value),
            text.measurer.measure(HealthFormat.minutes(minutes), text.body),
        )
        drawTooltip(lines, anchor, tooltipBg, tooltipBorder)
    }
}

// ---- Nightly sleep, stacked by stage ----------------------------------------------------

private val STACK_ORDER = listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.UNKNOWN, SleepStage.AWAKE)

/** Nightly sleep duration (hours) stacked by stage, with the 7–9 h target band. */
@Composable
fun SleepStackedChart(nights: List<Pair<LocalDate, NightSleep?>>, modifier: Modifier = Modifier, height: Dp = 200.dp) {
    val colors = ChartTheme.colors
    val surface = ChartTheme.surface
    val text = rememberChartText()
    val tooltipBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val tooltipBorder = MaterialTheme.colorScheme.outlineVariant
    var selected by remember(nights) { mutableStateOf<Int?>(null) }
    val plot = remember { Plot() }
    val present = remember(nights) { STACK_ORDER.filter { st -> nights.any { (_, n) -> (n?.minutesOf(st) ?: 0) > 0 } } }
    val maxHours = remember(nights) {
        maxOf(nights.maxOfOrNull { (_, n) -> n?.let { (it.asleepMinutes + it.awakeMinutes) / 60.0 } ?: 0.0 } ?: 0.0, SleepAnalysis.TARGET_MAX_HOURS)
    }
    val ticks = remember(maxHours) { AxisMath.niceTicks(0.0, maxHours) }
    val yLabels = remember(ticks, text) {
        val decimals = AxisMath.decimalsFor(ticks.step)
        ticks.values.map { text.measurer.measure(HealthFormat.hours(it, decimals), text.label) }
    }
    val tickIdx = remember(nights.size) { dayTickIndices(nights.size) }
    val xLabels = remember(nights, tickIdx, text) { tickIdx.map { text.measurer.measure(HealthFormat.dayMonth(nights[it].first), text.label) } }
    val xLabelHeight = remember(text) { text.measurer.measure("0", text.label).size.height.toFloat() }
    val bandText = stringResource(R.string.chart_sleep_target, HealthFormat.hours(SleepAnalysis.TARGET_MIN_HOURS, 0), HealthFormat.hours(SleepAnalysis.TARGET_MAX_HOURS, 0))
    val bandLabel = remember(bandText, text) { text.measurer.measure(bandText, text.label) }
    val withData = nights.mapNotNull { it.second }
    val description = if (withData.isNotEmpty()) {
        Fmt.plural(R.plurals.chart_sleep_desc_avg, nights.size, nights.size, HealthFormat.minutes(withData.map { it.asleepMinutes }.average().toInt()))
    } else {
        Fmt.plural(R.plurals.chart_sleep_desc, nights.size, nights.size)
    }

    Column(modifier) {
        if (present.size >= 2) ChartLegend(present.map { LegendItem(stringResource(it.labelRes), colors.stage(it), LegendKind.BOX) })
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = description }
                .scrub(nights) { x ->
                    if (nights.isNotEmpty() && plot.width > 0) {
                        selected = ((x - plot.left) / plot.width * nights.size).toInt().coerceIn(0, nights.lastIndex)
                    }
                },
        ) {
            layoutPlot(plot, yLabels.maxOf { it.size.width }.toFloat(), xLabelHeight)
            val range = (ticks.max - ticks.min).takeIf { it > 0 } ?: 1.0
            fun yOfHours(h: Double) = plot.bottom - ((h - ticks.min) / range).toFloat() * (plot.bottom - plot.top)
            val bandTop = yOfHours(SleepAnalysis.TARGET_MAX_HOURS)
            val bandBottom = yOfHours(SleepAnalysis.TARGET_MIN_HOURS)
            drawRect(colors.good.copy(alpha = 0.10f), Offset(plot.left, bandTop), Size(plot.width, bandBottom - bandTop))
            drawYAxis(plot, ticks, yLabels, colors)
            drawLabelChip(bandLabel, Offset(plot.left + 6.dp.toPx(), bandTop - bandLabel.size.height - 3.dp.toPx()), surface)
            val slot = plot.width / nights.size.coerceAtLeast(1)
            val barW = (slot * 0.7f).coerceAtLeast(1.dp.toPx())
            drawXLabels(plot, tickIdx.map { plot.left + slot * (it + 0.5f) }, xLabels, colors)
            nights.forEachIndexed { i, (_, night) ->
                if (night == null) return@forEachIndexed
                val x = plot.left + slot * i + (slot - barW) / 2f
                val alpha = if (selected == null || selected == i) 1f else 0.45f
                var acc = 0.0
                for (stage in STACK_ORDER) {
                    val m = night.minutesOf(stage)
                    if (m <= 0) continue
                    val y0 = yOfHours(acc / 60.0)
                    acc += m
                    val y1 = yOfHours(acc / 60.0)
                    drawRect(colors.stage(stage).copy(alpha = alpha), Offset(x, y1), Size(barW, y0 - y1))
                }
            }
            val index = selected ?: return@Canvas
            val (date, night) = nights.getOrNull(index) ?: return@Canvas
            val cx = plot.left + slot * (index + 0.5f)
            val lines = buildList {
                add(text.measurer.measure(Fmt.string(R.string.chart_night_of, HealthFormat.weekdayDayMonth(date)), text.label))
                if (night == null) {
                    add(text.measurer.measure(Fmt.string(R.string.common_no_data), text.value))
                } else {
                    add(text.measurer.measure(Fmt.string(R.string.health_asleep_value, HealthFormat.minutes(night.asleepMinutes)), text.value))
                    for (stage in STACK_ORDER.reversed()) {
                        val m = night.minutesOf(stage)
                        if (m > 0) add(text.measurer.measure(Fmt.string(R.string.chart_label_value, Fmt.string(stage.labelRes), HealthFormat.minutes(m)), text.body))
                    }
                }
            }
            val top = night?.let { yOfHours((it.asleepMinutes + it.awakeMinutes) / 60.0) } ?: plot.bottom
            drawTooltip(lines, Offset(cx, top), tooltipBg, tooltipBorder)
        }
    }
}

// ---- Readings over time (SpO2, HRV, respiratory rate, body fat) -------------------------

/** Blood-oxygen readings as dots, with a dashed 95 % reference line. */
@Composable
fun Spo2Chart(points: List<TimedValue>, xStart: Long, xEnd: Long, modifier: Modifier = Modifier) {
    val description = if (points.isEmpty()) {
        stringResource(R.string.chart_spo2_none)
    } else {
        Fmt.plural(
            R.plurals.chart_spo2_desc,
            points.size,
            points.size,
            Fmt.percent(points.minOf { it.value }),
            Fmt.percent(points.maxOf { it.value }),
        )
    }
    ReadingsChart(
        points = points,
        xStart = xStart,
        xEnd = xEnd,
        format = { HealthFormat.percent(it) },
        axisFormat = { v, decimals -> Fmt.percent(v, decimals) },
        description = description,
        floor = 90.0,
        ceiling = 100.0,
        reference = 95.0,
        modifier = modifier,
    )
}

/**
 * Readings over time as dots (value range from the data, widened to [floor] / [ceiling] when set),
 * with an optional dashed [reference] line. Press or drag for the reading's date and value.
 */
@Composable
fun ReadingsChart(
    points: List<TimedValue>,
    xStart: Long,
    xEnd: Long,
    format: (Double) -> String,
    axisFormat: (Double, Int) -> String,
    description: String,
    modifier: Modifier = Modifier,
    floor: Double? = null,
    ceiling: Double? = null,
    reference: Double? = null,
    height: Dp = 180.dp,
) {
    val colors = ChartTheme.colors
    val text = rememberChartText()
    val surface = ChartTheme.surface
    val tooltipBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val tooltipBorder = MaterialTheme.colorScheme.outlineVariant
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val plot = remember { Plot() }
    val ticks = remember(points, floor, ceiling, reference) {
        val values = points.map { it.value } + listOfNotNull(floor, ceiling, reference)
        if (values.isEmpty()) AxisMath.niceTicks(0.0, 1.0) else AxisMath.niceTicks(values.min(), values.max())
    }
    val yLabels = remember(ticks, text) {
        val decimals = AxisMath.decimalsFor(ticks.step)
        ticks.values.map { text.measurer.measure(axisFormat(it, decimals), text.label) }
    }
    val xTimes = remember(xStart, xEnd) { AxisMath.timeTicks(xStart, xEnd, 4) }
    val xLabels = remember(xTimes, text) { xTimes.map { text.measurer.measure(Fmt.dayMonth(it), text.label) } }
    val xLabelHeight = remember(text) { text.measurer.measure("0", text.label).size.height.toFloat() }
    val refLabel = remember(reference, text) { reference?.let { text.measurer.measure(format(it), text.label) } }
    val span = (xEnd - xStart).coerceAtLeast(1L)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .scrub(points) { x ->
                if (points.isEmpty() || plot.width <= 0) return@scrub
                val t = xStart + ((x - plot.left) / plot.width * span).toLong()
                selected = nearestByTime(points.size, { points[it].time }, t)
            },
    ) {
        layoutPlot(plot, yLabels.maxOf { it.size.width }.toFloat(), xLabelHeight)
        drawYAxis(plot, ticks, yLabels, colors)
        val range = (ticks.max - ticks.min).takeIf { it > 0 } ?: 1.0
        fun xOf(t: Long) = plot.left + ((t - xStart).toDouble() / span).toFloat() * plot.width
        fun yOf(v: Double) = plot.bottom - ((v - ticks.min) / range).toFloat() * (plot.bottom - plot.top)
        drawXLabels(plot, xTimes.map { xOf(it) }, xLabels, colors)
        val ry = reference?.let { yOf(it) }
        if (ry != null) dashedHorizontal(plot, ry, colors.mutedInk)
        clipRect(plot.left, plot.top - 6.dp.toPx(), plot.right, plot.bottom) {
            val dot = colors.series1.copy(alpha = 0.6f)
            for (p in points) {
                val c = Offset(xOf(p.time), yOf(p.value))
                drawCircle(surface, 5.dp.toPx(), c)
                drawCircle(dot, 3.5.dp.toPx(), c)
            }
        }
        if (refLabel != null && ry != null) drawLabelChip(refLabel, Offset(plot.left + 6.dp.toPx(), ry + 3.dp.toPx()), surface)
        val sel = selected?.let { points.getOrNull(it) } ?: return@Canvas
        val c = Offset(xOf(sel.time), yOf(sel.value))
        drawCircle(surface, 7.dp.toPx(), c)
        drawCircle(colors.series1, 5.dp.toPx(), c)
        val lines = listOf(
            text.measurer.measure(Fmt.dateTime(sel.time), text.label),
            text.measurer.measure(format(sel.value), text.value),
        )
        drawTooltip(lines, c, tooltipBg, tooltipBorder)
    }
}
