package com.enjoyyourtime.globalfitness.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.Dot
import com.enjoyyourtime.globalfitness.ui.theme.ChartTheme
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers

private data class Segment(val label: String, val kg: Double, val color: Color)

/** Horizontal stacked bar Grasa / Músculo / Hueso (kg) for one measurement. */
@Composable
fun CompositionBar(fatKg: Double, muscleKg: Double, boneKg: Double, weightKg: Double, modifier: Modifier = Modifier) {
    val colors = ChartTheme.colors
    val segments = listOf(
        Segment("Grasa", fatKg, colors.series2),
        Segment("Músculo", muscleKg, colors.series1),
        Segment("Hueso", boneKg, colors.series3),
    )
    val total = segments.sumOf { it.kg }.takeIf { it > 0 } ?: 1.0
    val description = segments.joinToString(", ") { "${it.label} ${Fmt.num(it.kg, 1)} kg" }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(16.dp)
                .semantics { contentDescription = "Composición: $description" },
        ) {
            val gap = 2.dp.toPx()
            val radius = 4.dp.toPx()
            val available = size.width - gap * (segments.size - 1)
            var x = 0f
            segments.forEachIndexed { i, s ->
                val w = (s.kg / total).toFloat() * available
                if (w <= 0f) return@forEachIndexed
                val leftR = if (i == 0) CornerRadius(radius) else CornerRadius.Zero
                val rightR = if (i == segments.lastIndex) CornerRadius(radius) else CornerRadius.Zero
                val rr = RoundRect(
                    left = x,
                    top = 0f,
                    right = x + w,
                    bottom = size.height,
                    topLeftCornerRadius = leftR,
                    topRightCornerRadius = rightR,
                    bottomRightCornerRadius = rightR,
                    bottomLeftCornerRadius = leftR,
                )
                drawPath(Path().apply { addRoundRect(rr) }, s.color)
                x += w + gap
            }
        }
        segments.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(s.color)
                Text(s.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    "${Fmt.num(s.kg, 1)} kg · ${Fmt.num(s.kg / weightKg * 100, 1)} %",
                    style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
