package com.enjoyyourtime.globalfitness.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.data.MuscleMapMode
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.theme.ChartTheme

/**
 * Colors of the body map. They come from the fixed chart palette (status colors mean the same as
 * on every other chart and chip) and the light steps are blended with the scheme's neutral surface,
 * so they sit right on a dynamic, light or dark theme. Strength diverges orange ↔ blue (readable
 * with red–green color blindness); [neutral] is the "no data" color of the legend.
 */
@Immutable
class MuscleShadeColors(private val colors: Map<MuscleShade, Color>, val neutral: Color) {
    /** Null for null: the body map draws its own neutral color. */
    operator fun get(shade: MuscleShade?): Color? = shade?.let { colors.getValue(it) }
}

@Composable
fun rememberMuscleShadeColors(): MuscleShadeColors {
    val chart = ChartTheme.colors
    val neutral = MaterialTheme.colorScheme.surfaceVariant
    return remember(chart, neutral) {
        val tint = { c: Color, fraction: Float -> lerp(neutral, c, fraction) }
        MuscleShadeColors(
            colors = mapOf(
                MuscleShade.SETS_LOW to tint(chart.good, 0.4f),
                MuscleShade.SETS_OPTIMAL to chart.good,
                MuscleShade.SETS_HIGH to chart.warning,
                MuscleShade.STRENGTH_DOWN_MORE to chart.series2,
                MuscleShade.STRENGTH_DOWN to tint(chart.series2, 0.5f),
                MuscleShade.STRENGTH_STABLE to tint(chart.mutedInk, 0.45f),
                MuscleShade.STRENGTH_UP to tint(chart.series1, 0.55f),
                MuscleShade.STRENGTH_UP_MORE to chart.series1,
                MuscleShade.RECOVERY_FATIGUED to chart.serious,
                MuscleShade.RECOVERY_PARTIAL to chart.warning,
                MuscleShade.RECOVERY_DONE to chart.good,
            ),
            neutral = neutral,
        )
    }
}

/** What a color means in [mode] (null: that mode's "nothing to show"). */
@Composable
fun shadeLabel(mode: MuscleMapMode, shade: MuscleShade?): String = when (shade) {
    null -> stringResource(
        when (mode) {
            MuscleMapMode.WEEKLY_SETS -> R.string.progress_level_none
            MuscleMapMode.STRENGTH -> R.string.common_no_data
            MuscleMapMode.RECOVERY -> R.string.progress_recovery_not_trained
        },
    )
    MuscleShade.SETS_LOW -> stringResource(R.string.progress_level_low)
    MuscleShade.SETS_OPTIMAL -> stringResource(R.string.progress_level_optimal)
    MuscleShade.SETS_HIGH -> stringResource(R.string.progress_level_high)
    MuscleShade.STRENGTH_DOWN_MORE -> stringResource(R.string.progress_strength_down_more, percentText(-MuscleMapLogic.STRONG_PERCENT, 0))
    MuscleShade.STRENGTH_DOWN -> stringResource(R.string.progress_strength_down)
    MuscleShade.STRENGTH_STABLE -> stringResource(R.string.progress_strength_stable)
    MuscleShade.STRENGTH_UP -> stringResource(R.string.progress_strength_up)
    MuscleShade.STRENGTH_UP_MORE -> stringResource(R.string.progress_strength_up_more, percentText(MuscleMapLogic.STRONG_PERCENT, 0))
    MuscleShade.RECOVERY_FATIGUED -> stringResource(R.string.progress_recovery_fatigued)
    MuscleShade.RECOVERY_PARTIAL -> stringResource(R.string.progress_recovery_recovering)
    MuscleShade.RECOVERY_DONE -> stringResource(R.string.progress_recovery_recovered)
}

/** "+4,2 %" with the sign always shown. */
@Composable
fun percentText(value: Double, decimals: Int = 1): String = stringResource(R.string.value_percent, Fmt.signed(value, decimals))

/** A legend or list swatch; the outline keeps the neutral one visible on any card. */
@Composable
fun ShadeSwatch(color: Color, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    Box(
        modifier
            .size(size)
            .background(color, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

/** Swatch + meaning for every color of [mode]. */
@Composable
fun MuscleLegend(mode: MuscleMapMode, colors: MuscleShadeColors, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MuscleMapLogic.legend(mode).forEach { shade ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShadeSwatch(colors[shade] ?: colors.neutral)
                Text(shadeLabel(mode, shade), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
