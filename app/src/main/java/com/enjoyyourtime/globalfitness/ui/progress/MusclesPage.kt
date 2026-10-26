package com.enjoyyourtime.globalfitness.ui.progress

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.data.MuscleMapMode
import com.enjoyyourtime.globalfitness.training.domain.MuscleRecovery
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.LoadUnit
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.NoticeCard
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.theme.ChartTheme
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import com.enjoyyourtime.globalfitness.ui.training.BodyMapPair
import com.enjoyyourtime.globalfitness.ui.training.ExerciseThumbnail
import com.enjoyyourtime.globalfitness.ui.training.TrainingRoutes
import com.enjoyyourtime.globalfitness.ui.training.displayName
import com.enjoyyourtime.globalfitness.ui.training.label
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Index of the figure card in the page's list (after the mode controls), to scroll back to it. */
private const val FIGURE_INDEX = 1

/**
 * Progreso → Músculos: the body map colored by weekly sets, strength change or recovery (the mode
 * is remembered), the detail of the tapped muscle with the exercises that trained it, and every
 * muscle in a list ordered by the mode's value, which is also the screen reader's way through the
 * figure.
 */
@Composable
fun MusclesPage(
    vm: MusclesViewModel,
    female: Boolean,
    listState: LazyListState,
    onOpenTraining: () -> Unit,
    onOpen: (route: String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val unit by vm.loadUnit.collectAsStateWithLifecycle()
    var selectedCode by rememberSaveable { mutableStateOf<String?>(null) }
    // The figure as it was some time ago instead of now (only its shape: the colours stay current).
    var showPast by rememberSaveable { mutableStateOf(false) }
    // Forgotten once there is no past to show, so it does not come back selected when there is again.
    val noPast = state.loaded && state.pastShape == null
    LaunchedEffect(noPast) {
        if (noPast) showPast = false
    }
    val selected = selectedCode?.let(Muscle::fromCode)
    val scope = rememberCoroutineScope()
    val colors = rememberMuscleShadeColors()
    val snapshots = state.snapshots
    val texts = snapshots.mapValues { (_, s) -> muscleTexts(mode, s, state.now) }
    val toggle = { m: Muscle -> selectedCode = if (m == selected) null else m.code }

    ProgressTabContent(listState) {
        val error = state.loadError
        when {
            error != null -> item(key = "error") {
                Text(
                    stringResource(R.string.error_database_open, error),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            !state.loaded -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            !state.hasWorkouts -> item(key = "empty") {
                NoticeCard(
                    title = stringResource(R.string.progress_empty_title),
                    text = stringResource(R.string.progress_empty_text),
                    icon = AppIcons.FitnessCenter,
                    actions = {
                        Button(onClick = onOpenTraining, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.progress_empty_action))
                        }
                    },
                )
            }
            else -> {
                item(key = "controls") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeSelector(mode, onMode = vm::setMode)
                        if (mode == MuscleMapMode.STRENGTH) PeriodChips(state.period, onPeriod = vm::setPeriod)
                    }
                }
                item(key = "figure") {
                    val past = state.pastShape
                    val pastAt = state.pastAt
                    val shown = if (showPast && past != null) past else state.shape
                    val pastText = if (past != null && pastAt != null) pastLabel(pastAt, state.now) else null
                    // Which moment the figure shows, for TalkBack too (only when there is a choice).
                    val moment = pastText?.let { if (showPast) it else stringResource(R.string.body_shape_now) }
                    AppCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (pastText != null) {
                                ShapeMomentSelector(showPast, pastText, onShowPast = { showPast = it })
                            }
                            BodyMapPair(
                                female = female,
                                fill = { m -> colors[snapshots[m]?.let { MuscleMapLogic.shade(mode, it) }] },
                                modifier = Modifier.fillMaxWidth(),
                                selected = selected,
                                onMuscleClick = toggle,
                                describe = { m -> texts[m]?.description },
                                shape = shown.shape,
                                stateDescription = moment,
                            )
                            MuscleLegend(mode, colors)
                            Text(
                                stringResource(mode.captionRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(if (shown.fromScale) R.string.body_shape_caption_scale else R.string.body_shape_caption_training),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item(key = "detail") {
                    val snapshot = selected?.let { snapshots[it] }
                    if (snapshot == null) {
                        Text(
                            stringResource(R.string.progress_tap_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    } else {
                        MuscleDetailCard(
                            snapshot = snapshot,
                            period = state.period,
                            now = state.now,
                            unit = unit,
                            colors = colors,
                            onClose = { selectedCode = null },
                            onOpenExercise = { id -> onOpen(TrainingRoutes.exercise(id)) },
                        )
                    }
                }
                item(key = "list-title") { SectionTitle(stringResource(R.string.progress_all_muscles)) }
                // One item: the 16 rows read as one rounded block, 2 dp apart.
                item(key = "list") {
                    val ranking = MuscleMapLogic.ranking(mode, snapshots)
                    Column {
                        ranking.forEachIndexed { index, m ->
                            MuscleRow(
                                muscle = m,
                                texts = texts.getValue(m),
                                color = colors[MuscleMapLogic.shade(mode, snapshots.getValue(m))] ?: colors.neutral,
                                selected = m == selected,
                                index = index,
                                count = ranking.size,
                                onClick = {
                                    selectedCode = m.code
                                    // The detail card sits under the figure: bring both into view.
                                    scope.launch { listState.animateScrollToItem(FIGURE_INDEX) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@get:StringRes
private val MuscleMapMode.labelRes: Int
    get() = when (this) {
        MuscleMapMode.WEEKLY_SETS -> R.string.progress_mode_sets
        MuscleMapMode.STRENGTH -> R.string.progress_mode_strength
        MuscleMapMode.RECOVERY -> R.string.progress_mode_recovery
    }

@get:StringRes
private val MuscleMapMode.captionRes: Int
    get() = when (this) {
        MuscleMapMode.WEEKLY_SETS -> R.string.progress_caption_sets
        MuscleMapMode.STRENGTH -> R.string.progress_caption_strength
        MuscleMapMode.RECOVERY -> R.string.progress_caption_recovery
    }

@get:StringRes
private val StrengthPeriod.labelRes: Int
    get() = when (this) {
        StrengthPeriod.M1 -> R.string.range_1m
        StrengthPeriod.M3 -> R.string.range_3m
        StrengthPeriod.M6 -> R.string.range_6m
        StrengthPeriod.Y1 -> R.string.range_1y
    }

@Composable
private fun ModeSelector(mode: MuscleMapMode, onMode: (MuscleMapMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        MuscleMapMode.entries.forEachIndexed { i, m ->
            SegmentedButton(
                selected = m == mode,
                onClick = { onMode(m) },
                shape = SegmentedButtonDefaults.itemShape(i, MuscleMapMode.entries.size),
                icon = {},
                label = {
                    // Three segments on a narrow phone: long words shrink a little instead of breaking.
                    BasicText(
                        stringResource(m.labelRes),
                        style = MaterialTheme.typography.labelLarge.copy(color = LocalContentColor.current),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                    )
                },
            )
        }
    }
}

/** "3 months ago | Now": which moment the figure's shape shows. */
@Composable
private fun ShapeMomentSelector(showPast: Boolean, pastLabel: String, onShowPast: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(true to pastLabel, false to stringResource(R.string.body_shape_now)).forEachIndexed { i, (past, label) ->
            SegmentedButton(
                selected = showPast == past,
                onClick = { onShowPast(past) },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
                icon = {},
                label = {
                    // A long "N weeks ago" on a narrow phone shrinks a little instead of being cut.
                    BasicText(
                        label,
                        style = MaterialTheme.typography.labelLarge.copy(color = LocalContentColor.current),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                    )
                },
            )
        }
    }
}

/** "3 months ago", or "5 weeks ago" when the data starts later. */
@Composable
private fun pastLabel(pastAt: Long, now: Long): String {
    val months = MusclesViewModel.COMPARE_MONTHS
    if (pastAt <= MusclesViewModel.compareFrom(now)) {
        return pluralStringResource(R.plurals.body_shape_months_ago, months, Fmt.count(months.toLong()))
    }
    val weeks = ((now - pastAt) / WEEK_MS.toDouble()).roundToInt().coerceAtLeast(1)
    return pluralStringResource(R.plurals.body_shape_weeks_ago, weeks, Fmt.count(weeks.toLong()))
}

private val WEEK_MS = Duration.ofDays(7).toMillis()

@Composable
private fun PeriodChips(period: StrengthPeriod, onPeriod: (StrengthPeriod) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StrengthPeriod.entries.forEach { p ->
            FilterChip(selected = p == period, onClick = { onPeriod(p) }, label = { Text(stringResource(p.labelRes)) })
        }
    }
}

/**
 * A muscle's state in one mode: the value line, its color's meaning and [description], what
 * TalkBack reads for the muscle on the figure (the map adds the muscle's name).
 */
data class MuscleTexts(val value: String, val status: String?, val description: String)

@Composable
private fun muscleTexts(mode: MuscleMapMode, s: MuscleSnapshot, now: Long): MuscleTexts {
    val shade = MuscleMapLogic.shade(mode, s)
    val (value, status) = when (mode) {
        MuscleMapMode.WEEKLY_SETS -> weeklyText(s) to shadeLabel(mode, shade)
        MuscleMapMode.STRENGTH -> strengthText(s) to shade?.let { shadeLabel(mode, it) }
        MuscleMapMode.RECOVERY -> when (shade) {
            null -> shadeLabel(mode, null) to null
            MuscleShade.RECOVERY_DONE -> lastTrainedText(s.recovery, now) to shadeLabel(mode, shade)
            else -> recoveryText(s.recovery, now) to shadeLabel(mode, shade)
        }
    }
    val description = status?.let { stringResource(R.string.progress_state_description, value, it) } ?: value
    return MuscleTexts(value, status, description)
}

/** "12 sets · target 10–20". */
@Composable
private fun weeklyText(s: MuscleSnapshot): String {
    val sets = s.volume.sets
    val range = s.muscle.weeklySets
    return listOf(
        pluralStringResource(R.plurals.progress_sets, sets.roundToInt(), Fmt.num(sets, MuscleMapLogic.decimals(sets))),
        stringResource(R.string.progress_target, Fmt.count(range.first.toLong()), Fmt.count(range.last.toLong())),
    ).joinToString(" · ")
}

/** "+4,2 % · 2 exercises" or "Not enough data". */
@Composable
private fun strengthText(s: MuscleSnapshot): String {
    val change = s.strength.changePercent ?: return stringResource(R.string.common_not_enough_data)
    return percentText(change) + " · " + pluralStringResource(R.plurals.progress_exercises, s.strength.exercises, s.strength.exercises)
}

/** "65 % recovered · full ~Tomorrow, 18:00". */
@Composable
private fun recoveryText(r: MuscleRecovery, now: Long): String = listOfNotNull(
    stringResource(R.string.progress_recovery_percent, Fmt.percent(r.fraction * 100)),
    r.readyAt?.let { stringResource(R.string.progress_recovery_full_at, readyTime(MuscleMapLogic.roundedReadyAt(it), now)) },
).joinToString(" · ")

@Composable
private fun lastTrainedText(r: MuscleRecovery, now: Long): String =
    r.lastTrainedAt?.let { stringResource(R.string.progress_last_trained, Fmt.relative(it, now)) }
        ?: stringResource(R.string.progress_recovery_not_trained)

/** "Today, 18:00", "Tomorrow, 09:00" or "Thu 16 Oct, 18:00". */
@Composable
private fun readyTime(at: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val tomorrow = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
    return if (day == tomorrow) stringResource(R.string.progress_time_tomorrow_at, Fmt.time(at)) else Fmt.relative(at, now)
}

/** One muscle of the ordered list (rounded outer corners on the first and last row). */
@Composable
private fun MuscleRow(
    muscle: Muscle,
    texts: MuscleTexts,
    color: Color,
    selected: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val outer = 20.dp
    val inner = 4.dp
    val shape = RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = shape,
        color = if (selected) scheme.secondaryContainer else scheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (index == 0) 0.dp else 2.dp)
            .semantics { this.selected = selected },
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ShadeSwatch(color, size = 14.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(muscle.label(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    texts.value,
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                    color = scheme.onSurfaceVariant,
                )
            }
            texts.status?.let {
                Spacer(Modifier.width(12.dp))
                Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** The tapped muscle: this week's sets against its range, strength, recovery and recent exercises. */
@Composable
private fun MuscleDetailCard(
    snapshot: MuscleSnapshot,
    period: StrengthPeriod,
    now: Long,
    unit: LoadUnit,
    colors: MuscleShadeColors,
    onClose: () -> Unit,
    onOpenExercise: (String) -> Unit,
) {
    val muscle = snapshot.muscle
    AppCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    muscle.label(),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailBlock(stringResource(R.string.progress_detail_week)) {
                    val shade = MuscleMapLogic.shade(MuscleMapMode.WEEKLY_SETS, snapshot)
                    Text(weeklyText(snapshot), style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
                    RangeBar(
                        sets = snapshot.volume.sets,
                        range = muscle.weeklySets,
                        valueColor = colors[shade] ?: MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    Text(
                        shadeLabel(MuscleMapMode.WEEKLY_SETS, shade),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DetailBlock(stringResource(R.string.progress_strength_title, stringResource(period.labelRes))) {
                    Text(strengthText(snapshot), style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
                }
                DetailBlock(stringResource(R.string.progress_mode_recovery)) {
                    val shade = MuscleMapLogic.recoveryShade(snapshot.recovery)
                    Text(
                        when (shade) {
                            null -> stringResource(R.string.progress_recovery_not_trained)
                            MuscleShade.RECOVERY_DONE -> stringResource(R.string.progress_recovery_recovered)
                            else -> recoveryText(snapshot.recovery, now)
                        },
                        style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                    )
                    if (shade != null) {
                        Text(
                            lastTrainedText(snapshot.recovery, now),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (snapshot.exercises.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.progress_recent_exercises),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
                    snapshot.exercises.forEach { ex -> RecentExerciseRow(ex, unit, today, onClick = { onOpenExercise(ex.exercise.id) }) }
                    Text(
                        stringResource(R.string.progress_recent_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp, top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailBlock(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/**
 * This week's sets on a track from 0 to a bit past the range's top, with the range highlighted.
 * Decorative: the text above says the same.
 */
@Composable
private fun RangeBar(sets: Double, range: IntRange, valueColor: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val band = ChartTheme.colors.good.copy(alpha = 0.35f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .clearAndSetSemantics { },
    ) {
        val top = max(range.last * 1.25, sets).toFloat()
        val rtl = layoutDirection == LayoutDirection.Rtl
        val h = size.height
        val radius = CornerRadius(h / 2, h / 2)
        fun x(v: Float) = (v / top * size.width).let { if (rtl) size.width - it else it }
        fun bar(from: Float, to: Float, color: Color, height: Float) {
            val a = x(from)
            val b = x(to)
            drawRoundRect(color, Offset(minOf(a, b), (h - height) / 2), Size(abs(b - a), height), CornerRadius(height / 2, height / 2))
        }
        drawRoundRect(track, cornerRadius = radius)
        bar(range.first.toFloat(), range.last.toFloat(), band, h)
        if (sets > 0) bar(0f, sets.toFloat(), valueColor, h * 0.5f)
    }
}

@Composable
private fun RecentExerciseRow(ex: MuscleExercise, unit: LoadUnit, today: LocalDate, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = stringResource(R.string.progress_open_exercise), onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExerciseThumbnail(ex.exercise, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ex.exercise.displayName(), style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(bestSetText(ex, unit), Fmt.relativeDay(ex.last.date, today)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                color = scheme.onSurfaceVariant,
            )
        }
        ex.changePercent?.let { change ->
            Spacer(Modifier.width(8.dp))
            val icon = when (MuscleMapLogic.strengthShade(change)) {
                MuscleShade.STRENGTH_UP, MuscleShade.STRENGTH_UP_MORE -> AppIcons.TrendUp
                MuscleShade.STRENGTH_DOWN, MuscleShade.STRENGTH_DOWN_MORE -> AppIcons.TrendDown
                else -> AppIcons.TrendFlat
            }
            Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(percentText(change), style = MaterialTheme.typography.labelLarge.merge(TabularNumbers))
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
}

/**
 * "80 kg × 8", "+10 kg × 6" (added to the body weight), "−30 kg × 8" (an assisted machine's
 * help, taken off the body weight), "12 reps", "60 s".
 */
@Composable
private fun bestSetText(ex: MuscleExercise, unit: LoadUnit): String? {
    val set = ex.last.bestSet ?: return null
    val kind = ex.exercise.kind
    val load = set.weightKg?.takeIf { it > 0 }?.let { kg ->
        when (kind) {
            ExerciseKind.ASSISTED_REPS -> loadText(-kg, unit, signed = true)
            ExerciseKind.BODYWEIGHT_REPS -> loadText(kg, unit, signed = true)
            else -> loadText(kg, unit, signed = false)
        }
    }
    val reps = set.reps?.takeIf { it > 0 }
    val seconds = set.durationSec?.takeIf { it > 0 }?.let { stringResource(R.string.progress_set_seconds, Fmt.count(it.toLong())) }
    return when {
        load != null && reps != null -> stringResource(R.string.progress_set_load_reps, load, Fmt.count(reps.toLong()))
        load != null && seconds != null -> stringResource(R.string.progress_set_load_reps, load, seconds)
        reps != null -> pluralStringResource(R.plurals.progress_set_reps, reps, reps)
        else -> seconds ?: load
    }
}

/** A load in the user's unit: "82,5 kg", "180 lb" ([signed]: "+10 kg" added to or "−30 kg" taken off the body weight). */
@Composable
private fun loadText(kg: Double, unit: LoadUnit, signed: Boolean): String {
    val v = unit.fromKg(kg)
    val decimals = MuscleMapLogic.decimals(v)
    val number = if (signed) Fmt.signed(v, decimals) else Fmt.num(v, decimals)
    val unitLabel = stringResource(if (unit == LoadUnit.LB) R.string.unit_lb else R.string.unit_kg)
    return stringResource(R.string.progress_load_value, number, unitLabel)
}
