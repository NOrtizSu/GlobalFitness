package com.enjoyyourtime.globalfitness.ui.band

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.band.HeartRateSample
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.charts.AxisMath
import com.enjoyyourtime.globalfitness.ui.theme.ChartTheme
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers

/** Live heart rate line (bpm over the last minutes), drawn with the app's chart tokens. */
@Composable
fun LiveHeartRateChart(samples: List<HeartRateSample>, modifier: Modifier = Modifier, height: Dp = 160.dp) {
    val colors = ChartTheme.colors
    val textMeasurer = rememberTextMeasurer(cacheSize = 16)
    val labelStyle = MaterialTheme.typography.labelSmall.merge(TabularNumbers).copy(color = colors.mutedInk)
    // 10 bpm of headroom keeps the ticks on whole numbers (steps of 5 or more).
    val ticks = remember(samples) {
        AxisMath.niceTicks(samples.minOf { it.bpm } - 10.0, samples.maxOf { it.bpm } + 10.0, 4)
    }
    val yLabels = remember(ticks, labelStyle, textMeasurer) { ticks.values.map { textMeasurer.measure(Fmt.count(it.toLong()), labelStyle) } }
    val description = pluralStringResource(
        R.plurals.band_live_chart_desc,
        samples.size,
        samples.size,
        Fmt.string(R.string.value_bpm, Fmt.count(samples.minOf { it.bpm }.toLong())),
        Fmt.string(R.string.value_bpm, Fmt.count(samples.maxOf { it.bpm }.toLong())),
    )

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description },
    ) {
        val labelWidth = yLabels.maxOf { it.size.width }.toFloat()
        val left = labelWidth + 8.dp.toPx()
        val right = size.width - 4.dp.toPx()
        val top = 6.dp.toPx()
        val bottom = size.height - 6.dp.toPx()
        val t0 = samples.first().timestampMs
        val span = (samples.last().timestampMs - t0).coerceAtLeast(1L).toFloat()
        val yRange = (ticks.max - ticks.min).takeIf { it > 0 } ?: 1.0

        fun xOf(t: Long) = left + (t - t0) / span * (right - left)
        fun yOf(v: Double) = bottom - ((v - ticks.min) / yRange).toFloat() * (bottom - top)

        ticks.values.forEachIndexed { i, v ->
            val y = yOf(v)
            drawLine(colors.gridline, Offset(left, y), Offset(right, y), strokeWidth = 1f)
            val layout = yLabels[i]
            drawText(layout, topLeft = Offset(left - 4.dp.toPx() - layout.size.width, y - layout.size.height / 2f))
        }

        val path = Path()
        samples.forEachIndexed { i, s ->
            val x = xOf(s.timestampMs)
            val y = yOf(s.bpm.toDouble())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, colors.series2, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = samples.last()
        drawCircle(colors.series2, 4.dp.toPx(), Offset(xOf(last.timestampMs), yOf(last.bpm.toDouble())))
    }
}
