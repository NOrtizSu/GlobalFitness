@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.achievements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.domain.Achievement
import com.enjoyyourtime.globalfitness.training.domain.AchievementBoard
import com.enjoyyourtime.globalfitness.training.domain.AchievementCategory
import com.enjoyyourtime.globalfitness.training.domain.AchievementState
import com.enjoyyourtime.globalfitness.training.model.LoadUnit
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import com.enjoyyourtime.globalfitness.ui.training.TrainingSubPage
import com.enjoyyourtime.globalfitness.ui.training.subPageContent
import com.enjoyyourtime.globalfitness.ui.training.trainingViewModel

/**
 * The achievements page: how many medals are earned, then every achievement by category — the
 * level reached with its medal, or greyed with the progress towards the first level. A card opens
 * its levels, dates and how it is counted.
 */
@Composable
fun AchievementsScreen(onBack: () -> Unit) {
    val vm = trainingViewModel()
    val board by vm.achievements.collectAsStateWithLifecycle()
    val unit by vm.loadUnit.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    TrainingSubPage(title = stringResource(R.string.achievements_title), onBack = onBack) { padding ->
        val b = board
        if (b == null) {
            Box(Modifier.subPageContent(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@TrainingSubPage
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 152.dp),
            modifier = Modifier.subPageContent(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary", span = { GridItemSpan(maxLineSpan) }) { SummaryCard(b) }
            for (category in AchievementCategory.entries) {
                val states = b.states.filter { it.achievement.category == category && it.available > 0 }
                if (states.isEmpty()) continue
                item(key = "title-$category", span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(stringResource(AchievementTexts.category(category)))
                }
                items(states, key = { it.achievement.key }) { s ->
                    AchievementCard(s, unit, onClick = { open = s.achievement.key })
                }
            }
        }
        open?.let { key -> Achievement.fromKey(key) }?.let { a -> b.state(a) }?.let { s ->
            AchievementSheet(s, unit, onDismiss = { open = null })
        }
    }
}

@Composable
private fun SummaryCard(board: AchievementBoard) {
    AppCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            TrophyBadge(size = 64.dp, earned = board.earned > 0)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    earnedText(board),
                    style = MaterialTheme.typography.titleMedium.merge(TabularNumbers),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                LinearProgressIndicator(
                    progress = { if (board.total == 0) 0f else board.earned.toFloat() / board.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** "12 of 70 medals" (every level on offer, see [AchievementBoard.total]). */
private fun earnedText(board: AchievementBoard): String =
    Fmt.plural(R.plurals.achievements_earned, board.total, Fmt.count(board.earned.toLong()), Fmt.count(board.total.toLong()))

@Composable
private fun AchievementCard(s: AchievementState, unit: LoadUnit, onClick: () -> Unit) {
    val earned = s.level > 0
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    AppCard(onClick = onClick, onClickLabel = stringResource(R.string.achievements_levels)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Medal(s.achievement, s.level, size = 64.dp)
            Text(
                stringResource(AchievementTexts.title(s.achievement)),
                style = MaterialTheme.typography.titleSmall,
                color = if (earned) MaterialTheme.colorScheme.onSurface else variant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(levelText(s), style = MaterialTheme.typography.bodySmall, color = variant, textAlign = TextAlign.Center)
            Progress(s, unit, Modifier.padding(top = 2.dp))
        }
    }
}

/** "Level 3 of 8" or "Not earned yet". */
@Composable
private fun levelText(s: AchievementState): String =
    if (s.level > 0) {
        stringResource(R.string.achievements_level, Fmt.count(s.level.toLong()), Fmt.count(s.available.toLong()))
    } else {
        stringResource(R.string.achievements_not_started)
    }

/** The bar towards the next level with "23 / 25", what blocks it, or "Every level earned". */
@Composable
private fun Progress(s: AchievementState, unit: LoadUnit, modifier: Modifier = Modifier) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    val blocker = s.blocker
    when {
        blocker != null -> Text(
            stringResource(AchievementTexts.blocker(blocker)),
            style = MaterialTheme.typography.bodySmall,
            color = variant,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        s.next == null -> Text(
            stringResource(R.string.achievements_all_levels),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = modifier,
        )
        else -> Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(progress = { (s.fraction ?: 0.0).toFloat() }, modifier = Modifier.fillMaxWidth())
            AchievementTexts.progress(s, unit)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall.merge(TabularNumbers), color = variant, maxLines = 1)
            }
        }
    }
}

/** Every level of an achievement with its date or progress, and how it is counted. */
@Composable
private fun AchievementSheet(s: AchievementState, unit: LoadUnit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Medal(s.achievement, s.level, size = 72.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(AchievementTexts.title(s.achievement)),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(levelText(s), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            s.blocker?.let { Text(stringResource(AchievementTexts.blocker(it)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Text(stringResource(R.string.achievements_levels), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
            Column {
                val shown = maxOf(s.available, s.level)
                for (level in 1..shown) {
                    if (level > 1) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                    LevelRow(s, level, unit)
                }
            }
            Text(stringResource(R.string.achievements_how), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
            Text(AchievementTexts.rule(s.achievement), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.achievement.isLift) {
                Text(stringResource(R.string.achievements_lift_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LevelRow(s: AchievementState, level: Int, unit: LoadUnit) {
    val unlock = s.unlocked.getOrNull(level - 1)
    val isNext = unlock == null && level == s.level + 1
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Medal(s.achievement, if (unlock != null) level else 0, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(AchievementTexts.goal(s, level, unit), style = MaterialTheme.typography.bodyLarge)
            when {
                unlock != null -> Text(
                    stringResource(R.string.achievements_earned_on, Fmt.dayMonthYear(unlock.at)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                isNext && s.blocker == null -> AchievementTexts.progress(s, unit)?.let {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(progress = { (s.fraction ?: 0.0).toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * The Entreno tab's entry to the achievements: medals earned, the last one, and the level closest
 * to being reached with its progress.
 */
@Composable
fun AchievementsCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val vm = trainingViewModel()
    val board by vm.achievements.collectAsStateWithLifecycle()
    val unit by vm.loadUnit.collectAsStateWithLifecycle()
    val b = board ?: return
    val latest = b.latest
    val closest = b.closest
    AppCard(modifier, onClick = onOpen, onClickLabel = stringResource(R.string.achievements_open)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (latest != null) Medal(latest.first.achievement, latest.second.level, size = 56.dp) else TrophyBadge(size = 56.dp, earned = false)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.achievements_title), style = MaterialTheme.typography.titleMedium)
                Text(earnedText(b), style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (closest != null) {
                    Text(
                        stringResource(R.string.achievements_next, AchievementTexts.goal(closest, closest.level + 1, unit)),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(progress = { (closest.fraction ?: 0.0).toFloat() }, modifier = Modifier.weight(1f))
                        AchievementTexts.progress(closest, unit)?.let {
                            Spacer(Modifier.width(8.dp))
                            Text(it, style = MaterialTheme.typography.labelSmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "12 of 70 medals" for the Perfil row; null until the achievements are known. */
@Composable
fun achievementsSummary(): String? {
    val board by trainingViewModel().achievements.collectAsStateWithLifecycle()
    return board?.let(::earnedText)
}

/**
 * Keeps new celebrations waiting while the user types: a field of the page has focus ([focused]) and
 * the keyboard is open. Hiding the keyboard (the field keeps its focus) lets them show.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HoldCelebrationsWhileTyping(reason: String, focused: Boolean) {
    HoldCelebrations(reason, active = focused && WindowInsets.isImeVisible)
}

/**
 * Keeps new celebrations waiting while [active] (a dialog that covers the celebration, typing);
 * they show a moment after the last hold ends or leaves the screen.
 */
@Composable
fun HoldCelebrations(reason: String, active: Boolean = true) {
    val vm = trainingViewModel()
    DisposableEffect(vm, reason, active) {
        if (active) vm.holdCelebrations(reason, true)
        onDispose { if (active) vm.holdCelebrations(reason, false) }
    }
}
