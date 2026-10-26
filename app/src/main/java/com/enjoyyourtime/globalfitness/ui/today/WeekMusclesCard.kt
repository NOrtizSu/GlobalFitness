package com.enjoyyourtime.globalfitness.ui.today

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.data.MuscleMapMode
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.progress.MuscleMapLogic
import com.enjoyyourtime.globalfitness.ui.progress.MuscleRegion
import com.enjoyyourtime.globalfitness.ui.progress.MusclesUiState
import com.enjoyyourtime.globalfitness.ui.progress.WeekSummary
import com.enjoyyourtime.globalfitness.ui.progress.rememberMuscleShadeColors
import com.enjoyyourtime.globalfitness.ui.training.BodyMapPair

/**
 * Hoy's "Muscles this week": a small body map colored by this week's sets and one line naming the
 * muscle groups on track and below their range. The figure is decorative (the line says it);
 * tapping the card opens Progreso → Músculos.
 */
@Composable
fun WeekMusclesCard(muscles: MusclesUiState, female: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = rememberMuscleShadeColors()
    val snapshots = muscles.snapshots
    AppCard(modifier, onClick = onOpen, onClickLabel = stringResource(R.string.today_muscles_open)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BodyMapPair(
                female = female,
                fill = { m -> colors[snapshots[m]?.let { MuscleMapLogic.shade(MuscleMapMode.WEEKLY_SETS, it) }] },
                modifier = Modifier.width(104.dp).clearAndSetSemantics { },
                showCaptions = false,
                shape = muscles.shape.shape,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.today_muscles_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    weekSummaryText(MuscleMapLogic.weekSummary(snapshots.mapValues { it.value.volume })),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "On track: chest and back · Below target: legs". */
@Composable
private fun weekSummaryText(summary: WeekSummary): String = when (summary) {
    WeekSummary.NoSets -> stringResource(R.string.today_muscles_none)
    WeekSummary.AllOnTrack -> stringResource(R.string.today_muscles_all_on_track)
    is WeekSummary.Mixed -> {
        val onTrack = summary.onTrack.map { stringResource(it.labelRes) }
        val below = summary.below.map { stringResource(it.labelRes) }
        listOfNotNull(
            onTrack.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.today_muscles_on_track, Fmt.list(it)) },
            stringResource(R.string.today_muscles_below, Fmt.list(below)),
        ).joinToString(" · ")
    }
}

@get:StringRes
private val MuscleRegion.labelRes: Int
    get() = when (this) {
        MuscleRegion.CHEST -> R.string.progress_region_chest
        MuscleRegion.BACK -> R.string.progress_region_back
        MuscleRegion.SHOULDERS -> R.string.progress_region_shoulders
        MuscleRegion.ARMS -> R.string.progress_region_arms
        MuscleRegion.CORE -> R.string.progress_region_core
        MuscleRegion.LEGS -> R.string.progress_region_legs
    }
