package com.enjoyyourtime.globalfitness.ui.training

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.domain.ExerciseSession
import com.enjoyyourtime.globalfitness.training.domain.PersonalRecords
import com.enjoyyourtime.globalfitness.training.domain.TrainingMath
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.LoadUnit
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.ConfirmDialog
import com.enjoyyourtime.globalfitness.ui.components.InfoRow
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.components.fullBleed
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Period of the progress chart. */
private enum class ProgressRange(@param:StringRes val label: Int, val days: Int?) {
    M1(R.string.range_1m, 30),
    M3(R.string.range_3m, 91),
    M6(R.string.range_6m, 182),
    Y1(R.string.range_1y, 365),
    ALL(R.string.range_all, null),
}

private const val DAY_MS = 24L * 3600 * 1000

/**
 * One exercise or machine: the user's photo, muscles, machine settings, progress chart, personal
 * records and every session. Custom exercises can be edited, archived or deleted from here.
 */
@Composable
fun ExerciseDetailScreen(exerciseId: String, onBack: () -> Unit, onOpenWorkout: (String) -> Unit, onEdit: () -> Unit) {
    val vm = trainingViewModel()
    val state by remember(exerciseId) { vm.exerciseDetail(exerciseId) }.collectAsStateWithLifecycle(Loadable.Loading)
    val unit by vm.loadUnit.collectAsStateWithLifecycle()
    val female by vm.female.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    PhotoFailedMessages(vm, snackbar)

    val detail = (state as? Loadable.Ready)?.value
    if (detail == null) {
        TrainingSubPage(title = stringResource(R.string.training_exercise_title), onBack = onBack) { padding ->
            Box(Modifier.subPageContent(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                if (state == Loadable.Missing) {
                    Text(stringResource(R.string.training_exercise_missing), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                } else {
                    CircularProgressIndicator()
                }
            }
        }
        return
    }
    val exercise = detail.exercise
    var notesText by rememberSaveable(exercise.id) { mutableStateOf(exercise.notes.orEmpty()) }
    val custom = !exercise.builtIn && vm.customExercise(exercise.id) != null
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberPhotoLauncher(
        vm,
        onPhoto = { uri, fromCamera -> vm.setPhoto(exercise.id, uri, fromCamera) },
        onUnavailable = { scope.launch { snackbar.showSnackbar(context.getString(R.string.training_photo_unavailable)) } },
    )

    TrainingSubPage(
        title = exercise.displayName(),
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            // How it is done, when the exercise has a guide or an animation (the page shows the rest).
            if (hasHowTo(exercise)) ExerciseInfoButton(exercise)
            IconButton(onClick = { vm.toggleFavorite(exercise.id) }) {
                Icon(
                    if (exercise.favorite) Icons.Filled.Star else TrainingIcons.StarBorder,
                    contentDescription = stringResource(if (exercise.favorite) R.string.training_unfavorite else R.string.training_make_favorite),
                    tint = if (exercise.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (custom) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.training_more_options))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.training_edit)) },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = {
                                menu = false
                                // The editor starts from the stored settings: the ones being typed are saved first.
                                scope.launch {
                                    if (notesText.trim() != vm.storedNote(exercise.id)?.notes.orEmpty()) vm.setExerciseNotes(exercise.id, notesText).join()
                                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onEdit()
                                }
                            },
                        )
                        if (exercise.archived) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.training_restore)) },
                                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    vm.restoreCustomExercise(exercise.id)
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (detail.used) R.string.training_archive else R.string.action_delete)) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    confirmRemove = true
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.subPageContent(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "photo") { PhotoHeader(vm, exercise, launcher, onRemove = { vm.removePhoto(exercise.id) }) }
            item(key = "title") { TitleBlock(exercise) }
            if (exercise.muscles.isNotEmpty()) {
                item(key = "muscles") { MusclesCard(exercise, female) }
            }
            if (exercise.kind == ExerciseKind.ASSISTED_REPS) {
                item(key = "assist") { InfoLine(stringResource(R.string.training_assist_hint)) }
            }
            if (exercise.mobility) {
                item(key = "mobility") { InfoLine(stringResource(R.string.training_mobility_note)) }
            }
            item(key = "settings-title") { SectionTitle(stringResource(notesTitleRes(exercise.equipment))) }
            item(key = "settings") {
                SettingsNotesField(notesText, onText = { notesText = it }, stored = exercise.notes, onSave = { vm.setExerciseNotes(exercise.id, it) }, hint = notesHintRes(exercise.equipment))
            }
            item(key = "progress-title") { SectionTitle(stringResource(R.string.training_progress)) }
            item(key = "progress") { ProgressSection(detail, unit) }
            // A stretch has no records (see TrainingMath.hasRecords): the note above says so.
            if (!exercise.mobility) {
                item(key = "records-title") { SectionTitle(stringResource(R.string.training_personal_records)) }
                item(key = "records") { RecordsCard(exercise, detail.records, unit) }
            }
            if (detail.sessions.isNotEmpty()) {
                item(key = "history-title") { SectionTitle(stringResource(R.string.training_history_title)) }
                val sessions = detail.sessions.asReversed()
                itemsIndexed(sessions, key = { _, s -> s.entryId }) { index, s ->
                    SessionRow(s, exercise.kind, unit, index, sessions.size, onClick = { onOpenWorkout(s.workoutId) })
                }
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(if (detail.used) R.string.training_archive_title else R.string.training_delete_exercise_title, exercise.displayName()),
            text = stringResource(
                when {
                    detail.usedByRoutinesOnly -> R.string.training_archive_routines_text
                    detail.used -> R.string.training_archive_text
                    else -> R.string.training_delete_exercise_text
                },
            ),
            confirmLabel = stringResource(if (detail.used) R.string.training_archive else R.string.action_delete),
            onConfirm = {
                confirmRemove = false
                vm.removeCustomExercise(exercise.id) { archived -> if (!archived) onBack() }
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

/** Shows "the photo could not be read" from the view model in [snackbar]. */
@Composable
internal fun PhotoFailedMessages(vm: TrainingViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            if (event == TrainingEvent.PhotoFailed) {
                snackbar.currentSnackbarData?.dismiss()
                // Not awaited: the next message replaces this one instead of queueing behind it.
                launch { snackbar.showSnackbar(context.getString(R.string.training_photo_failed)) }
            }
        }
    }
}

/**
 * The machine photo across the page; tap it to take another, pick one or remove it. Without a
 * photo (or when its file has not reached this phone yet) a placeholder offers to add one, and
 * "remove" stays available while the exercise still refers to a photo.
 */
@Composable
private fun PhotoHeader(vm: TrainingViewModel, exercise: Exercise, launcher: PhotoLauncher, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val px = with(LocalDensity.current) { 480.dp.roundToPx() }
    val photo = rememberPhoto(vm, exercise.photo, px)
    Box {
        if (photo != null) {
            Image(
                photo,
                contentDescription = stringResource(R.string.training_photo_of, exercise.displayName()),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(MaterialTheme.shapes.large)
                    .clickable(onClickLabel = stringResource(R.string.training_photo_change)) { menu = true },
            )
        } else {
            val sport = exercise.equipment == Equipment.SPORT
            val addPhoto = stringResource(if (sport) R.string.training_photo_add_sport else R.string.training_photo_add)
            AppCard(onClick = { menu = true }, onClickLabel = addPhoto) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(56.dp).clip(MaterialTheme.shapes.medium),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(exercise.equipment.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(addPhoto, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(if (sport) R.string.training_photo_add_text_sport else R.string.training_photo_add_text),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(TrainingIcons.PhotoCamera, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        PhotoMenu(expanded = menu, onDismiss = { menu = false }, launcher = launcher, hasPhoto = exercise.photo != null, onRemove = onRemove)
    }
}

@Composable
private fun TitleBlock(exercise: Exercise) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(exercise.displayName(), style = MaterialTheme.typography.headlineSmall)
        Text(
            listOfNotNull(
                exercise.equipment.label(),
                // "Sports & activities" says it: they are not "Cardio" in the gym's sense (as the info sheet).
                if (TrainingMath.isSport(exercise)) null else exercise.kind.label(),
                if (exercise.mobility) stringResource(R.string.training_mobility) else null,
                if (exercise.builtIn) null else stringResource(R.string.training_own_exercise),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (exercise.archived) {
            Text(stringResource(R.string.training_archived_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MusclesCard(exercise: Exercise, female: Boolean) {
    AppCard { ExerciseMuscles(exercise, female, Modifier.padding(16.dp)) }
}

/** Primary (strong) and secondary (lighter) muscles as chips and on a small body figure (also in the info sheet). */
@Composable
internal fun ExerciseMuscles(exercise: Exercise, female: Boolean, modifier: Modifier = Modifier) {
    val strong = MaterialTheme.colorScheme.primary
    val light = strong.copy(alpha = 0.4f)
    val primaryText = stringResource(R.string.training_muscle_primary)
    val secondaryText = stringResource(R.string.training_muscle_secondary)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        MuscleChips(exercise.primary, exercise.secondary - exercise.primary, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        BodyMapPair(
            female = female,
            fill = { m ->
                when (m) {
                    in exercise.primary -> strong
                    in exercise.secondary -> light
                    else -> null
                }
            },
            modifier = Modifier.width(120.dp).height(130.dp),
            describe = { m ->
                when (m) {
                    in exercise.primary -> primaryText
                    in exercise.secondary -> secondaryText
                    else -> null
                }
            },
        )
    }
}

@Composable
private fun InfoLine(text: String) {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * "Seat 4 · back 2" ([text], held by the page so "Edit" can save it first): saved when the
 * keyboard's done key is pressed, the field loses focus or the page closes. [stored] replaces the
 * text while the field is not being edited.
 */
@Composable
private fun SettingsNotesField(text: String, onText: (String) -> Unit, stored: String?, onSave: (String) -> Unit, @StringRes hint: Int) {
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(stored) { if (!focused) onText(stored.orEmpty()) }
    val save by rememberUpdatedState { if (text.trim() != stored.orEmpty()) onSave(text) }
    DisposableEffect(Unit) { onDispose { save() } }
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = { onText(it.take(MAX_SETTINGS_LENGTH)) },
        placeholder = { Text(stringResource(hint)) },
        leadingIcon = { Icon(TrainingIcons.Tune, contentDescription = null) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        maxLines = 4,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (focused && !it.isFocused) save()
                focused = it.isFocused
            },
    )
}

private const val MAX_SETTINGS_LENGTH = 300

@StringRes
private fun ProgressMetric.labelRes(kind: ExerciseKind): Int = when (this) {
    ProgressMetric.E1RM -> R.string.training_metric_e1rm
    ProgressMetric.HEAVIEST -> if (kind == ExerciseKind.BODYWEIGHT_REPS) R.string.training_metric_extra_load else R.string.training_metric_heaviest
    ProgressMetric.VOLUME -> R.string.training_stat_volume
    ProgressMetric.MAX_REPS -> R.string.training_metric_max_reps
    ProgressMetric.TOTAL_REPS -> R.string.training_metric_total_reps
    ProgressMetric.LONGEST -> R.string.training_metric_longest
    ProgressMetric.TOTAL_TIME -> R.string.training_metric_total_time
    ProgressMetric.DISTANCE -> R.string.training_metric_distance
    ProgressMetric.LEAST_ASSISTANCE -> R.string.training_metric_least_assistance
}

/** A session's value as the chart plots it: loads in the user's unit, times in minutes, distances in km. */
private fun plotted(metric: ProgressMetric, raw: Double, unit: LoadUnit): Double = when {
    metric.isLoad -> unit.fromKg(raw)
    metric.isTime -> raw / 60.0
    metric == ProgressMetric.DISTANCE -> raw / 1000.0
    else -> raw
}

/** A plotted value ([plotted]) with its unit. */
private fun formatMetric(metric: ProgressMetric, v: Double, unit: LoadUnit): String = when {
    metric == ProgressMetric.VOLUME -> loadWithUnit(Fmt.count(v.roundToLong()), unit)
    metric.isLoad -> loadWithUnit(TrainingFormat.number(v, 1, Fmt.locale), unit)
    metric.isTime -> TrainingFormat.duration((v * 60).roundToInt(), Fmt.locale)
    metric == ProgressMetric.DISTANCE -> kmText(TrainingFormat.number(v, 2, Fmt.locale))
    else -> Fmt.string(R.string.training_value_reps, Fmt.count(v.roundToLong()))
}

@Composable
private fun ProgressSection(detail: ExerciseDetail, unit: LoadUnit) {
    val exercise = detail.exercise
    // A stretch progresses in time held; a load on it is no measure of progress.
    val metrics = ProgressMetric.forExercise(exercise).filterNot { exercise.mobility && it.usesMovedLoad }
    var chosen by rememberSaveable { mutableStateOf<ProgressMetric?>(null) }
    var range by rememberSaveable { mutableStateOf(ProgressRange.M3) }
    val metric = chosen?.takeIf { it in metrics } ?: metrics.first()
    val now = System.currentTimeMillis()
    val from = range.days?.let { now - it * DAY_MS } ?: Long.MIN_VALUE
    val points = remember(detail.sessions, metric, range, unit) {
        detail.sessions.filter { it.at >= from }.mapNotNull { s ->
            metric.of(s)?.let { v -> ProgressPoint(s.at, plotted(metric, v, unit)) }
        }
    }
    val label = stringResource(metric.labelRes(exercise.kind))
    // Time ticks read like the values ("25:00"), the others as plain numbers next to the title's unit.
    val formatTick = remember(metric) {
        if (metric.isTime) {
            { v: Double, _: Int -> TrainingFormat.duration((v * 60).roundToInt(), Fmt.locale) }
        } else {
            { v: Double, decimals: Int -> Fmt.num(v, decimals) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (metrics.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fullBleed(16.dp),
            ) {
                items(metrics) { m ->
                    FilterChip(selected = m == metric, onClick = { chosen = m }, label = { Text(stringResource(m.labelRes(exercise.kind))) })
                }
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ProgressRange.entries.forEachIndexed { i, r ->
                SegmentedButton(
                    selected = r == range,
                    onClick = { range = r },
                    shape = SegmentedButtonDefaults.itemShape(i, ProgressRange.entries.size),
                    icon = {},
                    label = { Text(stringResource(r.label)) },
                )
            }
        }
        AppCard {
            Column(Modifier.padding(16.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                points.lastOrNull()?.let {
                    Text(formatMetric(metric, it.v, unit), style = MaterialTheme.typography.headlineSmall.merge(TabularNumbers))
                }
                Spacer(Modifier.height(12.dp))
                when {
                    detail.needsBodyweight && metric.usesMovedLoad -> ChartNote(stringResource(R.string.training_needs_bodyweight))
                    detail.sessions.isEmpty() -> ChartNote(stringResource(R.string.training_progress_empty))
                    points.isEmpty() -> ChartNote(stringResource(R.string.training_progress_none_in_period))
                    else -> ProgressChart(points, formatValue = { formatMetric(metric, it, unit) }, label = label, formatTick = formatTick)
                }
            }
        }
    }
}

@Composable
private fun ChartNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    )
}

/** The records that mean something for the exercise's kind; assisted machines show the least assistance. */
@Composable
private fun RecordsCard(exercise: Exercise, records: PersonalRecords, unit: LoadUnit) {
    val kind = exercise.kind
    val rows = buildList {
        when (kind) {
            ExerciseKind.WEIGHT_REPS -> {
                records.bestE1rmKg?.let { add(R.string.training_metric_e1rm to estimateText(it, unit)) }
                records.heaviestKg?.let { add(R.string.training_metric_heaviest to loadText(it, unit)) }
                records.mostReps?.let { add(R.string.training_metric_max_reps to Fmt.count(it.toLong())) }
                records.bestVolumeKg?.let { add(R.string.training_record_volume to volumeText(it, unit)) }
            }
            ExerciseKind.BODYWEIGHT_REPS -> {
                records.mostReps?.let { add(R.string.training_metric_max_reps to Fmt.count(it.toLong())) }
                if (exercise.bodyweightFactor != null) records.bestE1rmKg?.let { add(R.string.training_metric_e1rm to estimateText(it, unit)) }
            }
            ExerciseKind.ASSISTED_REPS -> {
                records.leastAssistanceKg?.let { add(R.string.training_metric_least_assistance to loadText(it, unit)) }
                records.bestE1rmKg?.let { add(R.string.training_metric_e1rm to estimateText(it, unit)) }
                records.mostReps?.let { add(R.string.training_metric_max_reps to Fmt.count(it.toLong())) }
            }
            ExerciseKind.DURATION -> {
                records.longestSec?.let { add(R.string.training_metric_longest to TrainingFormat.duration(it, Fmt.locale)) }
                records.heaviestKg?.let { add(R.string.training_metric_heaviest to loadText(it, unit)) }
            }
            ExerciseKind.CARDIO -> {
                records.longestSec?.let { add(R.string.training_metric_longest to TrainingFormat.duration(it, Fmt.locale)) }
                records.longestDistanceM?.let { add(R.string.training_metric_distance to kmText(TrainingFormat.distanceKm(it, Fmt.locale))) }
            }
        }
    }
    AppCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.training_no_records),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            rows.forEach { (label, value) ->
                InfoRow(stringResource(label), value, trailing = { Icon(TrainingIcons.Trophy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) })
            }
        }
    }
}

@Composable
private fun SessionRow(session: ExerciseSession, kind: ExerciseKind, unit: LoadUnit, index: Int, count: Int, onClick: () -> Unit) {
    GroupedRow(index, count, onClick) {
        Column(Modifier.weight(1f)) {
            Text(Fmt.weekdayDayMonth(session.date), style = MaterialTheme.typography.bodyLarge)
            Text(
                TrainingFormat.setsSummary(session.sets, kind, unit, Fmt.locale, { loadWithUnit(it, unit) }, ::kmText),
                style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        session.bestE1rm?.takeIf { kind != ExerciseKind.CARDIO }?.let {
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(estimateText(it, unit), style = MaterialTheme.typography.titleSmall.merge(TabularNumbers))
                Text(stringResource(R.string.training_metric_e1rm_short), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
