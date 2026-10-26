package com.enjoyyourtime.globalfitness.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.LoadUnit
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.achievements.AchievementsCard
import com.enjoyyourtime.globalfitness.ui.achievements.AchievementsScreen
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.DatePickerModal
import com.enjoyyourtime.globalfitness.ui.components.NoticeCard
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.components.TabPage
import com.enjoyyourtime.globalfitness.ui.components.fullBleed
import com.enjoyyourtime.globalfitness.ui.navigation.openSubPage
import com.enjoyyourtime.globalfitness.ui.navigation.subPage
import com.enjoyyourtime.globalfitness.ui.navigation.subPageWithArgs
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import com.enjoyyourtime.globalfitness.ui.training.music.TrainingMusicCard
import com.enjoyyourtime.globalfitness.ui.training.music.musicSuggestions
import java.time.LocalDate
import java.time.format.TextStyle

/**
 * The "Entreno" tab: today's / the active workout, start a workout, routines, repeat a workout, the
 * day's music, recent workouts and entry points to the catalog and history. [onOpen] opens a
 * training sub-page route.
 */
@Composable
fun TrainingScreen(onOpen: (route: String) -> Unit, modifier: Modifier = Modifier) {
    val vm = trainingViewModel()
    val overview by vm.overview.collectAsStateWithLifecycle()
    val exercises by vm.exercises.collectAsStateWithLifecycle()
    val unit by vm.loadUnit.collectAsStateWithLifecycle()
    val routines by vm.routines.collectAsStateWithLifecycle()
    val music = musicSuggestions()
    var pickDate by rememberSaveable { mutableStateOf(false) }
    val openWorkout = rememberWorkoutOpener(onOpen)
    RestTimerEffect(vm)

    TabPage(
        title = stringResource(R.string.training_title),
        modifier = modifier,
        actions = {
            IconButton(onClick = { onOpen(TrainingRoutes.CATALOG) }) {
                Icon(TrainingIcons.MenuBook, contentDescription = stringResource(R.string.training_catalog_title))
            }
            IconButton(onClick = { onOpen(TrainingRoutes.HISTORY) }) {
                Icon(TrainingIcons.History, contentDescription = stringResource(R.string.training_history_title))
            }
            IconButton(onClick = { onOpen(TrainingRoutes.SETTINGS) }) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.training_settings_title))
            }
        },
    ) {
        if (!overview.loaded) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            return@TabPage
        }
        val active = overview.active
        if (active != null) {
            item(key = "active") { ActiveWorkoutCard(vm, active, exercises, onContinue = { openWorkout(active.id) }) }
        } else {
            item(key = "start") { StartCard(onStart = { vm.startWorkout(openWorkout) }, onLogPast = { pickDate = true }) }
        }
        if (!overview.hasWorkouts) {
            item(key = "intro") {
                NoticeCard(
                    title = stringResource(R.string.training_intro_title),
                    text = stringResource(R.string.training_intro_text),
                    icon = TrainingIcons.EditNote,
                    actions = {
                        TextButton(onClick = { onOpen(TrainingRoutes.CATALOG) }) { Text(stringResource(R.string.training_open_catalog)) }
                    },
                )
            }
        }
        // Routines: the user's first, then the built-in ones; one row keeps the tab short.
        item(key = "routines-title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.training_routines_title), Modifier.weight(1f))
                TextButton(onClick = { onOpen(TrainingRoutes.routineEditor()) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.training_routine_new))
                }
            }
        }
        if (routines.isNotEmpty()) {
            item(key = "routines") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fullBleed(16.dp),
                ) {
                    items(routines, key = { it.id }) { r -> RoutineCard(r, onClick = { onOpen(TrainingRoutes.routine(r.id)) }) }
                }
            }
        }
        if (active == null && overview.templates.isNotEmpty()) {
            item(key = "repeat-title") { SectionTitle(stringResource(R.string.training_repeat_title)) }
            item(key = "repeat") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fullBleed(16.dp),
                ) {
                    items(overview.templates, key = { it.id }) { t ->
                        TemplateCard(t, workoutTitle(t, exercises), onClick = { vm.repeatWorkout(t.id, openWorkout) })
                    }
                }
            }
        }
        music?.let { suggestions -> item(key = "music") { TrainingMusicCard(suggestions) } }
        val week = overview.week
        if (week != null && overview.hasWorkouts) {
            item(key = "week") { WeekCard(week, unit) }
        }
        item(key = "achievements") { AchievementsCard(onOpen = { onOpen(TrainingRoutes.ACHIEVEMENTS) }) }
        if (overview.recent.isNotEmpty()) {
            item(key = "recent-title") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.training_recent_title), Modifier.weight(1f))
                    TextButton(onClick = { onOpen(TrainingRoutes.HISTORY) }) { Text(stringResource(R.string.training_see_all)) }
                }
            }
            itemsIndexed(overview.recent, key = { _, s -> "recent-${s.id}" }) { index, s ->
                WorkoutRow(
                    summary = s,
                    title = workoutTitle(s, exercises),
                    subtitle = listOfNotNull(daysAgoText(s.startedAt), s.durationMs?.let(::durationText)).joinToString(" · "),
                    unit = unit,
                    index = index,
                    count = overview.recent.size,
                    onClick = { openWorkout(s.id) },
                )
            }
        }
    }

    if (pickDate) {
        DatePickerModal(
            initial = LocalDate.now(),
            onPick = { vm.logPastWorkout(it, openWorkout) },
            onDismiss = { pickDate = false },
            minYear = 2000,
        )
    }
}

/**
 * Opens a workout page. Starting a workout answers after a database write: by then the user may
 * have switched tab, and the page must not pop up over another section.
 */
@Composable
private fun rememberWorkoutOpener(onOpen: (route: String) -> Unit): (String) -> Unit {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val open by rememberUpdatedState(onOpen)
    return remember(lifecycle) {
        { id -> if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) open(TrainingRoutes.workout(id)) }
    }
}

@Composable
private fun ActiveWorkoutCard(vm: TrainingViewModel, active: WorkoutSummary, exercises: Map<String, Exercise>, onContinue: () -> Unit) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val rest by vm.rest.collectAsStateWithLifecycle()
    AppCard(containerColor = MaterialTheme.colorScheme.primaryContainer, onClick = onContinue, onClickLabel = stringResource(R.string.action_continue)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardLabel(stringResource(R.string.training_in_progress), onContainer)
            Text(workoutTitle(active, exercises), style = MaterialTheme.typography.titleLarge, color = onContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ElapsedText(vm, active.startedAt, style = MaterialTheme.typography.displaySmall, color = onContainer)
            Text(exercisesAndSets(active.exercises, active.workingSets), style = MaterialTheme.typography.bodyMedium, color = onContainer)
            rest?.takeIf { it.workoutId == active.id }?.let { RestLine(vm, it, onContainer) }
            Spacer(Modifier.size(8.dp))
            Button(onClick = onContinue, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_continue))
            }
        }
    }
}

@Composable
private fun StartCard(onStart: () -> Unit, onLogPast: () -> Unit) {
    AppCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.training_start_title), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.training_start_empty))
            }
            OutlinedButton(onClick = onLogPast, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Filled.DateRange, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.training_log_past))
            }
        }
    }
}

@Composable
private fun TemplateCard(t: WorkoutSummary, title: String, onClick: () -> Unit) {
    AppCard(Modifier.width(200.dp), onClick = onClick, onClickLabel = stringResource(R.string.training_repeat_action)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(TrainingIcons.Replay, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                exercisesAndSets(t.exercises, t.workingSets),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(daysAgoText(t.startedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** This week: workouts, working sets, volume and a dot per day trained. */
@Composable
private fun WeekCard(week: WeekSummary, unit: LoadUnit) {
    val today = LocalDate.now()
    val days = (0L until 7L).map { week.start.plusDays(it) }
    val description = stringResource(
        R.string.training_week_desc,
        Fmt.plural(R.plurals.training_workouts_count, week.workouts, Fmt.count(week.workouts.toLong())),
        Fmt.plural(R.plurals.training_sets_count, week.workingSets, Fmt.count(week.workingSets.toLong())),
        volumeText(week.volumeKg, unit),
    )
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.training_this_week), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Row(Modifier.clearAndSetSemantics { contentDescription = description }) {
                WeekStat(stringResource(R.string.training_stat_workouts), Fmt.count(week.workouts.toLong()), Modifier.weight(1f))
                WeekStat(stringResource(R.string.training_stat_sets), Fmt.count(week.workingSets.toLong()), Modifier.weight(1f))
                WeekStat(stringResource(R.string.training_stat_volume), volumeText(week.volumeKg, unit), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.SpaceBetween) {
                days.forEach { d -> DayDot(d, trained = d in week.days, today = d == today) }
            }
        }
    }
}

@Composable
private fun WeekStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleMedium.merge(TabularNumbers), maxLines = 1)
    }
}

@Composable
private fun DayDot(day: LocalDate, trained: Boolean, today: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            day.dayOfWeek.getDisplayName(TextStyle.NARROW_STANDALONE, Fmt.locale),
            style = MaterialTheme.typography.labelSmall,
            color = if (today) primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier
                .size(12.dp)
                .background(if (trained) primary else Color.Transparent, CircleShape)
                .border(1.5.dp, if (trained || today) primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
    }
}

/** A workout in a list; consecutive rows read as one rounded block. */
@Composable
internal fun WorkoutRow(
    summary: WorkoutSummary,
    title: String,
    subtitle: String,
    unit: LoadUnit,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    GroupedRow(index, count, onClick, modifier, onLongClick, onLongClickLabel) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                Fmt.plural(R.plurals.training_sets_count, summary.workingSets, Fmt.count(summary.workingSets.toLong())),
                style = MaterialTheme.typography.titleSmall.merge(TabularNumbers),
            )
            if (summary.volumeKg > 0) {
                Text(volumeText(summary.volumeKg, unit), style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CardLabel(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(TrainingIcons.FitnessCenter, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/**
 * Card for the "Hoy" tab: the workout in progress (resume) or today's / the last workout summary
 * with a start button (the routine used last first). [onOpenTraining] switches to the Entreno tab;
 * [onOpen] opens a sub-page.
 */
@Composable
fun TrainingTodayCard(onOpenTraining: () -> Unit, onOpen: (route: String) -> Unit, modifier: Modifier = Modifier) {
    val vm = trainingViewModel()
    val overview by vm.overview.collectAsStateWithLifecycle()
    val exercises by vm.exercises.collectAsStateWithLifecycle()
    val unit by vm.loadUnit.collectAsStateWithLifecycle()
    val lastRoutine by vm.lastRoutine.collectAsStateWithLifecycle()
    val openWorkout = rememberWorkoutOpener(onOpen)
    RestTimerEffect(vm)
    // Nothing until the data is known: the card would otherwise flash "no workouts yet".
    if (!overview.loaded) return
    val active = overview.active
    val today = overview.today
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        active != null -> {
            val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
            val open = { onOpen(TrainingRoutes.workout(active.id)) }
            AppCard(modifier, containerColor = MaterialTheme.colorScheme.primaryContainer, onClick = open, onClickLabel = stringResource(R.string.action_continue)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CardLabel(stringResource(R.string.training_in_progress), onContainer)
                    Text(workoutTitle(active, exercises), style = MaterialTheme.typography.titleMedium, color = onContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ElapsedText(vm, active.startedAt, style = MaterialTheme.typography.headlineSmall, color = onContainer)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            Fmt.plural(R.plurals.training_sets_count, active.workingSets, Fmt.count(active.workingSets.toLong())),
                            style = MaterialTheme.typography.bodyMedium,
                            color = onContainer,
                        )
                    }
                    Button(onClick = open, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.action_continue)) }
                }
            }
        }
        today != null -> AppCard(modifier, onClick = { onOpen(TrainingRoutes.workout(today.id)) }, onClickLabel = stringResource(R.string.training_open_workout)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CardLabel(stringResource(R.string.training_today_done), variant)
                Text(workoutTitle(today, exercises), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        today.durationMs?.let(::durationText),
                        Fmt.plural(R.plurals.training_sets_count, today.workingSets, Fmt.count(today.workingSets.toLong())),
                        today.volumeKg.takeIf { it > 0 }?.let { volumeText(it, unit) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                    color = variant,
                )
            }
        }
        else -> AppCard(modifier, onClick = onOpenTraining, onClickLabel = stringResource(R.string.training_open_tab)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardLabel(stringResource(R.string.training_title), variant)
                val last = overview.last
                Text(
                    if (last != null) {
                        stringResource(R.string.training_last_workout, daysAgoText(last.startedAt), workoutTitle(last, exercises))
                    } else {
                        stringResource(R.string.training_no_workouts_yet)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                val routine = lastRoutine
                if (routine == null) {
                    FilledTonalButton(
                        onClick = { vm.startWorkout(openWorkout) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.training_start_empty))
                    }
                } else {
                    // The routine used last is one tap away; an empty workout stays next to it.
                    val name = routine.template.name.resolve(LocalContext.current)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilledTonalButton(
                            onClick = { vm.startRoutine(routine.id, name, openWorkout) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.training_start_routine, name),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { vm.startWorkout(openWorkout) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.training_start_blank))
                        }
                    }
                }
            }
        }
    }
}

/** Registers every training sub-page ([TrainingRoutes] and internal ones) in the app's NavHost. */
fun NavGraphBuilder.trainingGraph(navController: NavHostController) {
    val open: (String) -> Unit = { navController.openSubPage(it) }
    val workoutArg = listOf(navArgument(TrainingRoutes.ARG_WORKOUT_ID) { type = NavType.StringType })

    subPageWithArgs(TrainingRoutes.WORKOUT, navController, workoutArg) { back, entry ->
        WorkoutScreen(workoutId = entry.stringArg(TrainingRoutes.ARG_WORKOUT_ID).orEmpty(), onBack = back, onOpen = open)
    }
    subPageWithArgs(TrainingRoutes.PICKER, navController, workoutArg) { back, entry ->
        val workoutId = entry.stringArg(TrainingRoutes.ARG_WORKOUT_ID).orEmpty()
        val vm = trainingViewModel()
        val created by entry.savedStateHandle.getStateFlow<String?>(TrainingRoutes.RESULT_CREATED_EXERCISE, null).collectAsStateWithLifecycle()
        ExercisePickerScreen(
            onAdd = { vm.addExercises(workoutId, it) },
            onBack = back,
            onCreate = { open(TrainingRoutes.editor(forPicker = true)) },
            createdExerciseId = created,
            onCreatedTicked = { entry.savedStateHandle[TrainingRoutes.RESULT_CREATED_EXERCISE] = null },
        )
    }
    subPageWithArgs(TrainingRoutes.ROUTINE_PICKER, navController) { back, entry ->
        val created by entry.savedStateHandle.getStateFlow<String?>(TrainingRoutes.RESULT_CREATED_EXERCISE, null).collectAsStateWithLifecycle()
        ExercisePickerScreen(
            // Handed to the routine editor below, which adds them to its form.
            onAdd = { navController.previousBackStackEntry?.savedStateHandle?.set(TrainingRoutes.RESULT_PICKED_EXERCISES, ArrayList(it)) },
            onBack = back,
            onCreate = { open(TrainingRoutes.editor(forPicker = true)) },
            createdExerciseId = created,
            onCreatedTicked = { entry.savedStateHandle[TrainingRoutes.RESULT_CREATED_EXERCISE] = null },
        )
    }
    subPageWithArgs(
        TrainingRoutes.ROUTINE,
        navController,
        listOf(navArgument(TrainingRoutes.ARG_ROUTINE_ID) { type = NavType.StringType }),
    ) { back, entry ->
        val id = entry.stringArg(TrainingRoutes.ARG_ROUTINE_ID).orEmpty()
        RoutineScreen(
            routineId = id,
            onBack = back,
            onOpenExercise = { open(TrainingRoutes.exercise(it)) },
            onEdit = { open(TrainingRoutes.routineEditor(routineId = id)) },
            onDuplicate = { open(TrainingRoutes.routineEditor(from = id)) },
            onOpenWorkout = { workoutId ->
                // The workout replaces the routine page: back from it returns to where the routine was opened.
                // Not single-top: after the pop, that would reuse a workout page below (one opened from the
                // history) for this workout and drop it from the back stack. The check stops double taps.
                if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    navController.navigate(TrainingRoutes.workout(workoutId)) {
                        popUpTo(TrainingRoutes.ROUTINE) { inclusive = true }
                    }
                }
            },
        )
    }
    subPageWithArgs(
        TrainingRoutes.ROUTINE_EDITOR,
        navController,
        listOf(
            navArgument(TrainingRoutes.ARG_ROUTINE_ID) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument(TrainingRoutes.ARG_FROM) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { back, entry ->
        val routineId = entry.stringArg(TrainingRoutes.ARG_ROUTINE_ID)
        val picked by entry.savedStateHandle.getStateFlow<List<String>?>(TrainingRoutes.RESULT_PICKED_EXERCISES, null).collectAsStateWithLifecycle()
        RoutineEditorScreen(
            routineId = routineId,
            fromId = entry.stringArg(TrainingRoutes.ARG_FROM),
            pickedExerciseIds = picked,
            onPickedConsumed = { entry.savedStateHandle[TrainingRoutes.RESULT_PICKED_EXERCISES] = null },
            onAddExercises = { open(TrainingRoutes.ROUTINE_PICKER) },
            onBack = back,
            onSaved = { savedId ->
                // The editor calls this with the page in front; the check keeps a stale call from navigating.
                if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    if (routineId != null) {
                        navController.popBackStack()
                    } else {
                        // A new routine (or a copy) opens its page in place of the editor; back from a copy
                        // returns to the routine it copies (not single-top, which would reuse that page).
                        navController.navigate(TrainingRoutes.routine(savedId)) {
                            popUpTo(TrainingRoutes.ROUTINE_EDITOR) { inclusive = true }
                        }
                    }
                }
            },
        )
    }
    subPageWithArgs(
        TrainingRoutes.EXERCISE,
        navController,
        listOf(navArgument(TrainingRoutes.ARG_EXERCISE_ID) { type = NavType.StringType }),
    ) { back, entry ->
        val id = entry.stringArg(TrainingRoutes.ARG_EXERCISE_ID).orEmpty()
        ExerciseDetailScreen(
            exerciseId = id,
            onBack = back,
            onOpenWorkout = { open(TrainingRoutes.workout(it)) },
            onEdit = { open(TrainingRoutes.editor(exerciseId = id)) },
        )
    }
    subPage(TrainingRoutes.CATALOG, navController) { back ->
        CatalogScreen(onBack = back, onOpenExercise = { open(TrainingRoutes.exercise(it)) }, onCreate = { open(TrainingRoutes.editor()) })
    }
    subPageWithArgs(
        TrainingRoutes.EDITOR,
        navController,
        listOf(
            navArgument(TrainingRoutes.ARG_EXERCISE_ID) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument(TrainingRoutes.ARG_PICKER) {
                type = NavType.BoolType
                defaultValue = false
            },
        ),
    ) { back, entry ->
        val editId = entry.stringArg(TrainingRoutes.ARG_EXERCISE_ID)
        val forPicker = entry.arguments?.getBoolean(TrainingRoutes.ARG_PICKER) == true
        ExerciseEditorScreen(
            exerciseId = editId,
            onBack = back,
            onSaved = { savedId ->
                // The editor calls this with the page in front; the check keeps a stale call from navigating.
                if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    when {
                        // Back to the picker, which ticks the new exercise next to the ones already ticked.
                        forPicker -> {
                            navController.previousBackStackEntry?.savedStateHandle?.set(TrainingRoutes.RESULT_CREATED_EXERCISE, savedId)
                            navController.popBackStack()
                        }
                        editId == null -> navController.navigate(TrainingRoutes.exercise(savedId)) {
                            popUpTo(TrainingRoutes.EDITOR) { inclusive = true }
                            launchSingleTop = true
                        }
                        else -> navController.popBackStack()
                    }
                }
            },
        )
    }
    subPage(TrainingRoutes.HISTORY, navController) { back ->
        HistoryScreen(onBack = back, onOpenWorkout = { open(TrainingRoutes.workout(it)) })
    }
    subPage(TrainingRoutes.SETTINGS, navController) { back -> TrainingSettingsScreen(onBack = back) }
    subPage(TrainingRoutes.ACHIEVEMENTS, navController) { back -> AchievementsScreen(onBack = back) }
}

/** A route argument; Navigation has already decoded the Uri-encoded value. */
private fun NavBackStackEntry.stringArg(name: String): String? = arguments?.getString(name)
