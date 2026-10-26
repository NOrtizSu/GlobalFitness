@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.training

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.withResumed
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.domain.LoadModel
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.LoadUnit
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.achievements.HoldCelebrations
import com.enjoyyourtime.globalfitness.ui.achievements.HoldCelebrationsWhileTyping
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.ConfirmDialog
import com.enjoyyourtime.globalfitness.ui.components.DatePickerModal
import com.enjoyyourtime.globalfitness.ui.components.InfoRow
import com.enjoyyourtime.globalfitness.ui.components.TimeInputDialog
import com.enjoyyourtime.globalfitness.ui.components.WholeWordsAutoSize
import com.enjoyyourtime.globalfitness.ui.theme.ChartTheme
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import com.enjoyyourtime.globalfitness.ui.training.music.MusicSheet
import com.enjoyyourtime.globalfitness.ui.training.music.musicSuggestions
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

// The set number opens the set's menu: 40 × 48 dp, and Compose widens its touch area to 48 dp.
private val NUMBER_CELL = 40.dp
private val NUMBER_CELL_HEIGHT = 48.dp
private val CHECK_CELL = 48.dp
private val CELL_GAP = 6.dp
private val CELL_HEIGHT = 40.dp
private const val PREVIOUS_WEIGHT = 1.2f

/**
 * A workout, in progress or past: name, start and duration, one card per exercise with its set
 * table, the rest timer and finishing. Values are saved as they are typed; a past workout asks
 * about filled-in sets that were not ticked when it is left.
 */
@Composable
fun WorkoutScreen(workoutId: String, onBack: () -> Unit, onOpen: (route: String) -> Unit) {
    val vm = trainingViewModel()
    val state by remember(workoutId) { vm.workout(workoutId) }.collectAsStateWithLifecycle(Loadable.Loading)
    val unit by vm.loadUnit.collectAsStateWithLifecycle()
    val exercises by vm.exercises.collectAsStateWithLifecycle()
    val female by vm.female.collectAsStateWithLifecycle()
    val routines by vm.routines.collectAsStateWithLifecycle()
    val music = musicSuggestions()
    val snackbar = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()

    var lastUi by remember { mutableStateOf<WorkoutUi?>(null) }
    var closing by rememberSaveable { mutableStateOf(false) }
    var askUnfinished by rememberSaveable { mutableStateOf(false) }
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // Kept by the view model, so the summary survives a rotation and a finish that ends in the background.
    val outcome by remember(workoutId) { vm.finishOutcome(workoutId) }.collectAsStateWithLifecycle(null)

    (state as? Loadable.Ready)?.value?.let { lastUi = it }
    // While closing, the workout may already be gone: keep showing it until the page leaves.
    val ui = (state as? Loadable.Ready)?.value ?: lastUi.takeIf { closing }

    // A workout deleted while closing (nothing completed, or discarded) takes the page with it, as
    // soon as the page is in front: a back press while it was not was dropped.
    val gone = closing && (state == Loadable.Missing || outcome == FinishOutcome.Deleted)
    LaunchedEffect(gone) {
        if (gone) {
            lifecycle.withResumed {
                vm.consumeFinish(workoutId)
                onBack()
            }
        }
    }

    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.training_undo)

    /** A short message with "Undo" (a new one replaces it). */
    fun offerUndo(message: String, undo: () -> Unit) {
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            val result = snackbar.showSnackbar(message, actionLabel = undoLabel, withDismissAction = true, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }
    val onEntryRemoved: (RemovedEntry) -> Unit = { removed -> offerUndo(context.getString(R.string.training_exercise_removed)) { vm.restoreEntry(removed) } }
    val onSetDeleted: (RemovedSet) -> Unit = { removed -> offerUndo(context.getString(R.string.training_set_deleted)) { vm.restoreSet(removed) } }

    val currentExercises by rememberUpdatedState(exercises)
    val currentUnit by rememberUpdatedState(unit)
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            if (event is TrainingEvent.Record) {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                val ex = currentExercises[event.exerciseId] ?: return@collect
                val text = context.getString(R.string.training_record_message, ex.name.resolve(context), setSummaryText(event.set, ex.kind, currentUnit))
                snackbar.currentSnackbarData?.dismiss()
                // Not awaited: a new record replaces the message instead of queueing behind it.
                launch { snackbar.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Short) }
            }
        }
    }

    if (ui == null) {
        TrainingSubPage(title = stringResource(R.string.training_workout_title), onBack = onBack) { padding ->
            Box(Modifier.subPageContent(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                if (state == Loadable.Missing) {
                    Text(stringResource(R.string.training_workout_missing), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                } else {
                    CircularProgressIndicator()
                }
            }
        }
        return
    }

    val w = ui.workout
    val live = w.inProgress

    // During the page's enter animation a back press would not leave (the back action waits for
    // RESUMED): closing or deleting then would leave the page showing a workout that is gone.
    fun inFront() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    fun complete(choice: UnfinishedSets?) {
        if (!inFront()) return
        closing = true
        if (live) {
            vm.finish(w.id, choice)
        } else {
            vm.closeFinished(w.id, choice)
            onBack()
        }
    }

    fun requestFinish() {
        when {
            ui.unfinishedFilled > 0 -> askUnfinished = true
            w.doneSets == 0 && (live || w.entries.isNotEmpty()) -> confirmEmpty = true
            else -> complete(null)
        }
    }

    val back: () -> Unit = { if (live) onBack() else requestFinish() }
    BackHandler(enabled = !live && !closing && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) { back() }

    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pendingDay by rememberSaveable { mutableLongStateOf(Long.MIN_VALUE) }
    var editDuration by rememberSaveable { mutableStateOf(false) }
    var showRoutines by rememberSaveable { mutableStateOf(false) }
    var showMusic by rememberSaveable { mutableStateOf(false) }
    var askRoutineName by rememberSaveable { mutableStateOf(false) }
    // Saved from the finish summary, which then says so instead of offering it again.
    var routineSavedInSummary by rememberSaveable { mutableStateOf(false) }
    val suggestedName = workoutTitle(
        remember(w, exercises) {
            TrainingSummaries.summary(w, { exercises[it] ?: TrainingRepository.unknownExercise(it) }, LoadModel.WITHOUT_BODYWEIGHT)
        },
        exercises,
    )

    fun showMessage(message: String) {
        snackbar.currentSnackbarData?.dismiss()
        scope.launch { snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Short) }
    }

    fun onRoutineAdded(added: List<String>) {
        if (added.isEmpty()) {
            showMessage(context.getString(R.string.training_routine_nothing_added))
        } else {
            offerUndo(Fmt.plural(R.plurals.training_routine_added, added.size, Fmt.count(added.size.toLong()))) { vm.undoAddRoutine(w.id, added) }
        }
    }

    fun onRoutineSaved(routineId: String) {
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            val result = snackbar.showSnackbar(
                context.getString(R.string.training_routine_saved),
                actionLabel = context.getString(R.string.training_routine_view),
                withDismissAction = true,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onOpen(TrainingRoutes.routine(routineId))
        }
    }

    TrainingSubPage(
        title = stringResource(if (live) R.string.training_in_progress else R.string.training_workout_title),
        onBack = back,
        snackbar = snackbar,
        actions = {
            // The day's playlists, one tap away while training.
            if (live && music != null) {
                IconButton(onClick = { showMusic = true }) {
                    Icon(TrainingIcons.MusicNote, contentDescription = stringResource(R.string.music_title))
                }
            }
            TextButton(onClick = ::requestFinish) {
                Text(stringResource(if (live) R.string.training_finish else R.string.action_save))
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.training_more_options))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.training_change_start)) },
                        leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                        onClick = {
                            menu = false
                            pickDate = true
                        },
                    )
                    if (w.entries.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.training_routine_save_from_workout)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                            onClick = {
                                menu = false
                                askRoutineName = true
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(if (live) R.string.training_discard_workout else R.string.training_delete_workout)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menu = false
                            confirmDelete = true
                        },
                    )
                }
            }
        },
        bottomBar = {
            val rest by vm.rest.collectAsStateWithLifecycle()
            rest?.takeIf { live && it.workoutId == w.id }?.let { RestBar(vm, it) }
        },
    ) { padding ->
        LazyColumn(
            // An achievement reached while typing in the set table waits until the user stops typing.
            modifier = Modifier.subPageContent(padding).onFocusChanged { editing = it.hasFocus },
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                WorkoutHeader(
                    vm = vm,
                    ui = ui,
                    unit = unit,
                    suggestedName = suggestedName,
                    onEditStart = { pickDate = true },
                    onEditDuration = { editDuration = true },
                )
            }
            itemsIndexed(ui.entries, key = { _, e -> e.entry.id }) { index, entry ->
                EntryCard(
                    vm = vm,
                    workoutId = w.id,
                    ui = entry,
                    index = index,
                    count = ui.entries.size,
                    unit = unit,
                    onOpenExercise = { onOpen(TrainingRoutes.exercise(entry.exercise.id)) },
                    onRemoved = onEntryRemoved,
                    onSetDeleted = onSetDeleted,
                )
            }
            item(key = "add") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.entries.isEmpty()) {
                        Text(
                            stringResource(R.string.training_workout_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    FilledTonalButton(
                        onClick = { onOpen(TrainingRoutes.picker(w.id)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.training_add_exercise))
                    }
                    // A whole routine in one tap: the exercises the workout already has are skipped.
                    OutlinedButton(onClick = { showRoutines = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.training_routine_add))
                    }
                }
            }
            item(key = "note") {
                WorkoutNoteField(w.note, onChange = { vm.setWorkoutNote(w.id, it) })
            }
            if (live) {
                item(key = "finish") {
                    Button(onClick = ::requestFinish, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.training_finish_workout))
                    }
                }
            }
        }
    }

    if (pickDate) {
        DatePickerModal(
            initial = w.date(),
            onPick = { pendingDay = it.toEpochDay() },
            onDismiss = { pickDate = false },
            minYear = 2000,
        )
    }
    if (pendingDay != Long.MIN_VALUE) {
        val zone = ZoneId.systemDefault()
        TimeInputDialog(
            initial = Instant.ofEpochMilli(w.startedAt).atZone(zone).toLocalTime(),
            onPick = { t: LocalTime ->
                vm.setStart(w.id, LocalDate.ofEpochDay(pendingDay).atTime(t).atZone(zone).toInstant().toEpochMilli())
            },
            onDismiss = { pendingDay = Long.MIN_VALUE },
        )
    }
    if (editDuration) {
        DurationDialog(
            initialMinutes = ((w.endedAt ?: w.startedAt) - w.startedAt).div(60_000L).toInt(),
            onSave = {
                vm.setDuration(w.id, it)
                editDuration = false
            },
            onDismiss = { editDuration = false },
        )
    }
    if (askUnfinished) {
        UnfinishedDialog(
            count = ui.unfinishedFilled,
            onChoice = { choice ->
                askUnfinished = false
                complete(choice)
            },
            onDismiss = { askUnfinished = false },
        )
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = stringResource(R.string.training_discard_title),
            text = stringResource(R.string.training_discard_empty_text),
            confirmLabel = stringResource(R.string.training_discard),
            onConfirm = {
                confirmEmpty = false
                if (inFront()) {
                    closing = true
                    vm.deleteWorkout(w.id)
                    onBack()
                }
            },
            onDismiss = { confirmEmpty = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(if (live) R.string.training_discard_title else R.string.training_delete_title),
            text = stringResource(if (live) R.string.training_discard_text else R.string.training_delete_text, Fmt.dateTime(w.startedAt)),
            confirmLabel = stringResource(if (live) R.string.training_discard else R.string.action_delete),
            onConfirm = {
                confirmDelete = false
                if (inFront()) {
                    closing = true
                    vm.deleteWorkout(w.id)
                    onBack()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
    HoldCelebrationsWhileTyping("workout-fields:${w.id}", focused = editing)
    val finished = (outcome as? FinishOutcome.Finished)?.summary
    finished?.let { s ->
        // The levels this workout reached are celebrated once its summary is closed (dialogs cover the celebration).
        HoldCelebrations("workout-summary:${w.id}")
        // Not offered for a workout that is exactly a routine already (e.g. started from it).
        val offerRoutine = routineSavedInSummary || !RoutineStats.matchesRoutine(s.workout.exerciseIds, routines.map { it.template })
        val activity = LocalActivity.current
        FinishSummaryDialog(
            summary = s,
            title = workoutTitle(s.workout, exercises),
            exercises = exercises,
            unit = unit,
            female = female,
            onDone = {
                vm.consumeFinish(w.id)
                onBack()
                // A saved workout's summary was just closed: a good moment for Google Play's rating card.
                activity?.let((context.applicationContext as GlobalFitnessApp).container.reviews::onGoodMoment)
            },
            onSaveRoutine = if (offerRoutine) ({ askRoutineName = true }) else null,
            routineSaved = routineSavedInSummary,
        )
    }
    if (showRoutines) {
        RoutineSheet(
            routines = routines,
            present = w.entries.mapTo(HashSet()) { it.exerciseId },
            onPick = { routineId ->
                showRoutines = false
                vm.addRoutine(w.id, routineId, ::onRoutineAdded)
            },
            onDismiss = { showRoutines = false },
        )
    }
    if (showMusic) {
        music?.takeIf { live }?.let {
            MusicSheet(
                it,
                onDismiss = { showMusic = false },
                onOpenFailed = {
                    showMusic = false
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.music_open_failed), withDismissAction = true) }
                },
            )
        }
    }
    if (askRoutineName) {
        SaveRoutineDialog(
            initialName = w.name?.trim()?.takeIf { it.isNotEmpty() } ?: suggestedName,
            onSave = { name ->
                askRoutineName = false
                // From the summary dialog, which covers the snackbar: it shows the result itself.
                val fromSummary = finished != null
                if (fromSummary) routineSavedInSummary = true
                vm.saveWorkoutAsRoutine(w.id, name) { id -> if (id != null && !fromSummary) onRoutineSaved(id) }
            },
            onDismiss = { askRoutineName = false },
        )
    }
}

/** One set in words, with units ("85 kg × 8", "1:30", "30:00 · 5 km"). */
private fun setSummaryText(set: WorkoutSet, kind: ExerciseKind, unit: LoadUnit): String =
    TrainingFormat.setsSummary(listOf(set), kind, unit, Fmt.locale, { loadWithUnit(it, unit) }, ::kmText)

@Composable
private fun WorkoutHeader(
    vm: TrainingViewModel,
    ui: WorkoutUi,
    unit: LoadUnit,
    suggestedName: String,
    onEditStart: () -> Unit,
    onEditDuration: () -> Unit,
) {
    val w = ui.workout
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NameField(w.name, placeholder = suggestedName, onChange = { vm.rename(w.id, it) })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onEditStart,
                    label = { Text(Fmt.dateTime(w.startedAt)) },
                    leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                val end = w.endedAt
                if (end != null) {
                    AssistChip(
                        onClick = onEditDuration,
                        label = { Text(durationText(end - w.startedAt)) },
                        leadingIcon = { Icon(TrainingIcons.Timer, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            if (w.inProgress) {
                ElapsedText(vm, w.startedAt, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                listOfNotNull(
                    Fmt.plural(R.plurals.training_sets_count, ui.workingSets, Fmt.count(ui.workingSets.toLong())),
                    ui.volumeKg.takeIf { it > 0 }?.let { volumeText(it, unit) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The workout name, edited in place (an empty name shows [placeholder], the suggested title). */
@Composable
private fun NameField(name: String?, placeholder: String, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(name.orEmpty()) }
    val sent = remember { SentValues<String?> { a, b -> a == b } }
    LaunchedEffect(name) {
        if (sent.shouldReset(name, textMeansStored = text.takeIf { it.isNotBlank() } == name)) text = name.orEmpty()
    }
    val label = stringResource(R.string.training_workout_name)
    val style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it.take(MAX_NAME_LENGTH)
                sent.sent(text.takeIf { t -> t.isNotBlank() })
                onChange(text)
            },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text(placeholder, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

private const val MAX_NAME_LENGTH = 60
private const val MAX_NOTE_LENGTH = 500

@Composable
private fun WorkoutNoteField(note: String?, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(note.orEmpty()) }
    val sent = remember { SentValues<String?> { a, b -> a == b } }
    LaunchedEffect(note) {
        if (sent.shouldReset(note, textMeansStored = text.takeIf { it.isNotBlank() } == note)) text = note.orEmpty()
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.take(MAX_NOTE_LENGTH)
            sent.sent(text.takeIf { t -> t.isNotBlank() })
            onChange(text)
        },
        label = { Text(stringResource(R.string.training_workout_note)) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
}

@Composable
private fun EntryCard(
    vm: TrainingViewModel,
    workoutId: String,
    ui: EntryUi,
    index: Int,
    count: Int,
    unit: LoadUnit,
    onOpenExercise: () -> Unit,
    onRemoved: (RemovedEntry) -> Unit,
    onSetDeleted: (RemovedSet) -> Unit,
) {
    val exercise = ui.exercise
    val entry = ui.entry
    val columns = SetTable.columns(exercise.kind)
    val numbers = SetTable.workingNumbers(entry.sets)
    var menu by remember { mutableStateOf(false) }
    var editNote by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val name = exercise.displayName()

    AppCard {
        Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ExerciseThumbnail(exercise, size = 44.dp, vm = vm)
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClickLabel = stringResource(R.string.training_open_exercise), onClick = onOpenExercise)
                        .padding(vertical = 4.dp),
                ) {
                    Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(exercise.equipment.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ExerciseInfoButton(exercise, onOpenProgress = onOpenExercise)
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.training_exercise_options, name))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (exercise.kind != ExerciseKind.CARDIO) {
                            MenuItem(stringResource(R.string.training_add_warmup), TrainingIcons.Timer) {
                                menu = false
                                vm.addWarmup(workoutId, entry.id)
                            }
                        }
                        MenuItem(stringResource(R.string.training_entry_note), TrainingIcons.Notes) {
                            menu = false
                            editNote = true
                        }
                        if (index > 0) {
                            MenuItem(stringResource(R.string.training_move_up), Icons.Filled.KeyboardArrowUp) {
                                menu = false
                                vm.moveEntry(workoutId, entry.id, -1)
                            }
                        }
                        if (index < count - 1) {
                            MenuItem(stringResource(R.string.training_move_down), Icons.Filled.KeyboardArrowDown) {
                                menu = false
                                vm.moveEntry(workoutId, entry.id, 1)
                            }
                        }
                        MenuItem(stringResource(R.string.training_remove_exercise), Icons.Filled.Delete) {
                            menu = false
                            if (entry.sets.any { it.done }) confirmRemove = true else vm.removeEntry(workoutId, entry.id, onRemoved)
                        }
                    }
                }
            }
            exercise.notes?.let { HintLine(TrainingIcons.Tune, it) }
            entry.note?.let { HintLine(TrainingIcons.Notes, it) }
            // The first time on an assisted machine: its weight is help, not load.
            if (exercise.kind == ExerciseKind.ASSISTED_REPS && ui.previous.isEmpty()) {
                HintLine(Icons.Filled.Info, stringResource(R.string.training_assist_hint))
            }
            Spacer(Modifier.height(8.dp))
            SetHeaderRow(columns, unit)
            entry.sets.forEach { set ->
                key(set.id) {
                    SetRow(
                        set = set,
                        number = numbers[set.id],
                        previous = ui.previous[set.id],
                        record = ui.records[set.id].orEmpty().isNotEmpty(),
                        kind = exercise.kind,
                        columns = columns,
                        unit = unit,
                        onChange = { transform -> vm.updateSet(workoutId, entry.id, set.id, transform) },
                        onDone = { vm.setDone(workoutId, entry.id, set.id, it) },
                        onWarmup = { vm.setWarmup(workoutId, entry.id, set.id, it) },
                        onDelete = { vm.removeSet(workoutId, entry.id, set.id, onSetDeleted) },
                    )
                }
            }
            TextButton(onClick = { vm.addSet(workoutId, entry.id) }, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.training_add_set))
            }
        }
    }

    if (editNote) {
        TextDialog(
            title = stringResource(R.string.training_entry_note),
            initial = entry.note.orEmpty(),
            label = name,
            onSave = {
                vm.setEntryNote(workoutId, entry.id, it)
                editNote = false
            },
            onDismiss = { editNote = false },
        )
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(R.string.training_remove_exercise_title, name),
            text = stringResource(R.string.training_remove_exercise_text),
            confirmLabel = stringResource(R.string.training_remove),
            onConfirm = {
                confirmRemove = false
                vm.removeEntry(workoutId, entry.id, onRemoved)
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, contentDescription = null) }, onClick = onClick)
}

/** A line under the exercise name: the machine settings or the note of this entry. */
@Composable
private fun HintLine(icon: ImageVector, text: String) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun fieldLabel(field: SetField, unit: LoadUnit): String = when (field) {
    SetField.LOAD -> unitLabel(unit)
    SetField.EXTRA_LOAD -> stringResource(R.string.training_col_extra_load, unitLabel(unit))
    SetField.ASSISTANCE -> stringResource(R.string.training_col_assistance, unitLabel(unit))
    SetField.REPS -> stringResource(R.string.training_col_reps)
    SetField.TIME -> stringResource(R.string.training_col_time)
    SetField.DISTANCE -> stringResource(R.string.training_col_km)
    SetField.LEVEL -> stringResource(R.string.training_col_level)
    SetField.KCAL -> stringResource(R.string.unit_kcal)
}

@Composable
private fun fieldDescription(field: SetField): String = stringResource(
    when (field) {
        SetField.LOAD -> R.string.metric_weight
        SetField.EXTRA_LOAD -> R.string.training_field_extra_load
        SetField.ASSISTANCE -> R.string.training_field_assistance
        SetField.REPS -> R.string.training_field_reps
        SetField.TIME -> R.string.training_col_time
        SetField.DISTANCE -> R.string.training_field_distance
        SetField.LEVEL -> R.string.training_col_level
        SetField.KCAL -> R.string.training_field_kcal
    },
)

@Composable
private fun SetHeaderRow(columns: SetColumns, unit: LoadUnit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderText(stringResource(R.string.training_col_set), Modifier.width(NUMBER_CELL))
        if (columns.previous) HeaderText(stringResource(R.string.training_col_previous), Modifier.weight(PREVIOUS_WEIGHT))
        columns.fields.forEach { HeaderText(fieldLabel(it, unit), Modifier.weight(1f)) }
        Spacer(Modifier.width(CHECK_CELL))
    }
}

@Composable
private fun HeaderText(text: String, modifier: Modifier) {
    val style = MaterialTheme.typography.labelMedium
    BasicText(
        text,
        style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center),
        // Two lines: "Assistance (kg)" and longer translations wrap between words instead of being
        // cut; a word wider than its column ("Подход", "Уровень") shrinks rather than split.
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        autoSize = WholeWordsAutoSize(min = HEADER_MIN_SIZE, max = style.fontSize),
        modifier = modifier,
    )
}

/** The smallest a set-table header shrinks to (from labelMedium's 12 sp). */
private val HEADER_MIN_SIZE = 9.sp

@Composable
private fun SetRow(
    set: WorkoutSet,
    number: Int?,
    previous: WorkoutSet?,
    record: Boolean,
    kind: ExerciseKind,
    columns: SetColumns,
    unit: LoadUnit,
    onChange: ((WorkoutSet) -> WorkoutSet) -> Unit,
    onDone: (Boolean) -> Unit,
    onWarmup: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val setLabel = if (set.warmup) stringResource(R.string.training_warmup_set) else stringResource(R.string.training_set_number, Fmt.count((number ?: 0).toLong()))
    val background = if (set.done) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceContainerLow
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SetNumberCell(set, number, record, setLabel, onWarmup, onDelete)
        if (columns.previous) {
            val text = previous?.let { TrainingFormat.previous(it, kind, unit, Fmt.locale) }
            PreviousCell(text, onCopy = { if (previous != null) onChange { SetTable.copyValues(it, previous) } }, Modifier.weight(PREVIOUS_WEIGHT))
        }
        columns.fields.forEach { field ->
            FieldCell(field, set, previous, unit, stringResource(R.string.training_spoken_pair, fieldDescription(field), setLabel), onChange, Modifier.weight(1f))
        }
        Box(Modifier.width(CHECK_CELL), contentAlignment = Alignment.Center) {
            FilledTonalIconToggleButton(
                checked = set.done,
                onCheckedChange = onDone,
                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.training_set_done, setLabel))
            }
        }
    }
}

/** Set number ("W" for a warm-up) with a trophy on a record; tap or long-press for its menu. */
@Composable
private fun SetNumberCell(set: WorkoutSet, number: Int?, record: Boolean, setLabel: String, onWarmup: (Boolean) -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val options = stringResource(R.string.training_set_options, setLabel)
    val recordLabel = stringResource(R.string.training_spoken_pair, setLabel, stringResource(R.string.training_record))
    Box(
        Modifier
            .width(NUMBER_CELL)
            .height(NUMBER_CELL_HEIGHT)
            .clip(MaterialTheme.shapes.extraSmall)
            .combinedClickable(onClickLabel = options, onLongClickLabel = options, onClick = { menu = true }, onLongClick = { menu = true })
            .semantics { contentDescription = if (record) recordLabel else setLabel },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (set.warmup) stringResource(R.string.training_warmup_short) else number?.let { Fmt.count(it.toLong()) }.orEmpty(),
            style = MaterialTheme.typography.titleSmall.merge(TabularNumbers),
            color = if (set.warmup) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
        )
        if (record) {
            Icon(
                TrainingIcons.Trophy,
                contentDescription = null,
                tint = ChartTheme.colors.warning,
                modifier = Modifier.size(14.dp).align(Alignment.TopEnd),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (set.warmup) R.string.training_make_working else R.string.training_make_warmup)) },
                onClick = {
                    menu = false
                    onWarmup(!set.warmup)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.training_delete_set)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

/** Last time's set ("80 × 8"); tapping it copies its values into this set. */
@Composable
private fun PreviousCell(text: String?, onCopy: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .height(CELL_HEIGHT)
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(enabled = text != null, onClickLabel = stringResource(R.string.training_copy_previous), onClick = onCopy),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text ?: "—",
            style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FieldCell(
    field: SetField,
    set: WorkoutSet,
    previous: WorkoutSet?,
    unit: LoadUnit,
    description: String,
    onChange: ((WorkoutSet) -> WorkoutSet) -> Unit,
    modifier: Modifier,
) {
    val locale = Fmt.locale
    when (field) {
        SetField.LOAD, SetField.EXTRA_LOAD, SetField.ASSISTANCE -> NumberCell(
            model = set.weightKg,
            format = { TrainingFormat.load(it, unit, locale) },
            parse = { TrainingFormat.parseLoad(it, unit) },
            onValue = { kg -> onChange { it.copy(weightKg = kg) } },
            decimal = true,
            placeholder = previous?.weightKg?.takeIf { it > 0 }?.let { TrainingFormat.load(it, unit, locale) },
            description = description,
            modifier = modifier,
        )
        SetField.REPS -> NumberCell(
            model = set.reps?.toDouble(),
            format = { TrainingFormat.count(it.toInt(), locale) },
            parse = { TrainingFormat.parseCount(it, TrainingFormat.MAX_REPS) },
            onValue = { v -> onChange { it.copy(reps = v?.toInt()) } },
            decimal = false,
            placeholder = previous?.reps?.let { TrainingFormat.count(it, locale) },
            description = description,
            modifier = modifier,
        )
        SetField.DISTANCE -> NumberCell(
            model = set.distanceM,
            format = { TrainingFormat.distanceKm(it, locale) },
            parse = TrainingFormat::parseDistanceKm,
            onValue = { m -> onChange { it.copy(distanceM = m) } },
            decimal = true,
            placeholder = previous?.distanceM?.takeIf { it > 0 }?.let { TrainingFormat.distanceKm(it, locale) },
            description = description,
            modifier = modifier,
        )
        SetField.LEVEL -> NumberCell(
            model = set.level?.toDouble(),
            format = { TrainingFormat.count(it.toInt(), locale) },
            parse = { TrainingFormat.parseCount(it, TrainingFormat.MAX_LEVEL) },
            onValue = { v -> onChange { it.copy(level = v?.toInt()) } },
            decimal = false,
            placeholder = previous?.level?.let { TrainingFormat.count(it, locale) },
            description = description,
            modifier = modifier,
        )
        SetField.KCAL -> NumberCell(
            model = set.kcal?.toDouble(),
            format = { TrainingFormat.count(it.toInt(), locale) },
            parse = { TrainingFormat.parseCount(it, TrainingFormat.MAX_KCAL) },
            onValue = { v -> onChange { it.copy(kcal = v?.toInt()) } },
            decimal = false,
            placeholder = previous?.kcal?.let { TrainingFormat.count(it, locale) },
            description = description,
            modifier = modifier,
        )
        SetField.TIME -> DurationCell(
            seconds = set.durationSec,
            onValue = { s -> onChange { it.copy(durationSec = s) } },
            placeholder = previous?.durationSec?.takeIf { it > 0 }?.let { TrainingFormat.duration(it, locale) },
            description = description,
            modifier = modifier,
        )
    }
}

private fun sameNumber(a: Double?, b: Double?): Boolean = if (a == null || b == null) a == b else abs(a - b) < 1e-6

private fun sameValue(input: NumberInput, model: Double?): Boolean = when (input) {
    NumberInput.Empty -> model == null
    NumberInput.Invalid -> false
    is NumberInput.Value -> sameNumber(input.value, model)
}

/**
 * A numeric set cell. Every valid change is saved at once (nothing is lost when the screen
 * closes). The typed text is kept while it still means the stored value ("80," is not reset to
 * "80") and when the stored value is only the late echo of an earlier keystroke ([SentValues]).
 * Invalid text is shown in the error color and not saved.
 */
@Composable
private fun NumberCell(
    model: Double?,
    format: (Double) -> String,
    parse: (String) -> NumberInput,
    onValue: (Double?) -> Unit,
    decimal: Boolean,
    placeholder: String?,
    description: String,
    modifier: Modifier,
) {
    var field by remember { mutableStateOf(model?.let(format).orEmpty().let { TextFieldValue(it, TextRange(it.length)) }) }
    val sent = remember { SentValues<Double?>(::sameNumber) }
    LaunchedEffect(model) {
        if (sent.shouldReset(model, textMeansStored = sameValue(parse(field.text), model))) {
            val text = model?.let(format).orEmpty()
            field = TextFieldValue(text, TextRange(text.length))
        }
    }
    CellField(
        value = field,
        onValueChange = { new ->
            val text = new.text
                .filter { it.isDigit() || (decimal && (it == ',' || it == '.' || it == '٫')) }
                .take(if (decimal) MAX_DECIMAL_CHARS else MAX_COUNT_CHARS)
            val changed = text != field.text
            // Unfiltered input keeps the keyboard's composition; filtered input starts afresh.
            field = if (text == new.text) {
                new
            } else {
                TextFieldValue(text, TextRange(minOf(new.selection.start, text.length), minOf(new.selection.end, text.length)))
            }
            if (changed) {
                val value = when (val p = parse(text)) {
                    NumberInput.Empty -> null
                    is NumberInput.Value -> p.value
                    NumberInput.Invalid -> return@CellField
                }
                sent.sent(value)
                onValue(value)
            }
        },
        keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        placeholder = placeholder,
        invalid = parse(field.text) == NumberInput.Invalid,
        description = description,
        modifier = modifier,
    )
}

private const val MAX_DECIMAL_CHARS = 7
private const val MAX_COUNT_CHARS = 4

/** A time cell typed as digits from the right ("130" shows 1:30), with the number keyboard. */
@Composable
private fun DurationCell(seconds: Int?, onValue: (Int?) -> Unit, placeholder: String?, description: String, modifier: Modifier) {
    val locale = Fmt.locale
    fun masked(s: Int?) = TrainingFormat.maskDuration(TrainingFormat.secondsToDigits(s), locale)
    var field by remember { mutableStateOf(masked(seconds).let { TextFieldValue(it, TextRange(it.length)) }) }
    val sent = remember { SentValues<Int?> { a, b -> a == b } }
    LaunchedEffect(seconds) {
        val typed = TrainingFormat.digitsToSeconds(TrainingFormat.digitsOnly(field.text))
        if (sent.shouldReset(seconds, textMeansStored = typed == seconds)) {
            val text = masked(seconds)
            field = TextFieldValue(text, TextRange(text.length))
        }
    }
    CellField(
        value = field,
        onValueChange = { new ->
            val digits = TrainingFormat.digitsOnly(new.text)
            val text = TrainingFormat.maskDuration(digits, locale)
            val changed = text != field.text
            field = TextFieldValue(text, TextRange(text.length))
            if (changed) {
                val value = TrainingFormat.digitsToSeconds(digits)
                sent.sent(value)
                onValue(value)
            }
        },
        keyboardType = KeyboardType.Number,
        placeholder = placeholder,
        invalid = false,
        description = description,
        modifier = modifier,
    )
}

@Composable
private fun CellField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    keyboardType: KeyboardType,
    placeholder: String?,
    invalid: Boolean,
    description: String,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyLarge.merge(TabularNumbers)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style.copy(color = if (invalid) colors.onErrorContainer else colors.onSurface, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        cursorBrush = SolidColor(colors.primary),
        modifier = modifier
            .height(CELL_HEIGHT)
            .semantics { contentDescription = description },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (invalid) colors.errorContainer else colors.surfaceContainerHighest, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (value.text.isEmpty() && placeholder != null) {
                    Text(
                        placeholder,
                        style = style,
                        color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
                inner()
            }
        },
    )
}

/** The rest countdown under the workout: time left, −15 s / +15 s and skip. */
@Composable
private fun RestBar(vm: TrainingViewModel, rest: RestTimer) {
    val now by vm.clock.collectAsStateWithLifecycle()
    val left = restRemainingSec(rest, now)
    if (left <= 0) return
    val step = REST_STEP_SEC
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(
                progress = { (left / rest.totalSec.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(TrainingIcons.Timer, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.training_rest), style = MaterialTheme.typography.labelMedium)
                    Text(TrainingFormat.duration(left, Fmt.locale), style = MaterialTheme.typography.titleLarge.merge(TabularNumbers))
                }
                TextButton(onClick = { vm.adjustRest(-step) }) {
                    Text(stringResource(R.string.training_seconds_value, Fmt.signed(-step.toDouble(), 0)))
                }
                TextButton(onClick = { vm.adjustRest(step) }) {
                    Text(stringResource(R.string.training_seconds_value, Fmt.signed(step.toDouble(), 0)))
                }
                IconButton(onClick = vm::skipRest) {
                    Icon(TrainingIcons.SkipNext, contentDescription = stringResource(R.string.training_skip_rest))
                }
            }
        }
    }
}

private const val REST_STEP_SEC = 15

@Composable
private fun UnfinishedDialog(count: Int, onChoice: (UnfinishedSets) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.training_unfinished_title)) },
        text = { Text(Fmt.plural(R.plurals.training_unfinished_text, count, Fmt.count(count.toLong()))) },
        confirmButton = {
            TextButton(onClick = { onChoice(UnfinishedSets.COMPLETE) }) { Text(stringResource(R.string.training_unfinished_complete)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = { onChoice(UnfinishedSets.DISCARD) }) { Text(stringResource(R.string.training_unfinished_discard)) }
            }
        },
    )
}

@Composable
private fun DurationDialog(initialMinutes: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(Fmt.count(initialMinutes.toLong())) }
    val minutes = (TrainingFormat.parseCount(text, MAX_DURATION_MINUTES) as? NumberInput.Value)?.value?.toInt()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.training_duration)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(3) },
                label = { Text(stringResource(R.string.training_duration_minutes)) },
                singleLine = true,
                isError = minutes == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { minutes?.let(onSave) }, enabled = minutes != null) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val MAX_DURATION_MINUTES = 600

/** One multi-line text, e.g. the note of an exercise in this workout. */
@Composable
internal fun TextDialog(title: String, initial: String, label: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_NOTE_LENGTH) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * After "Terminar entreno": duration, sets, volume, records and the muscles worked on the body.
 * [onSaveRoutine] (null: not offered) saves the workout as a routine; [routineSaved] says it was.
 */
@Composable
private fun FinishSummaryDialog(
    summary: FinishSummary,
    title: String,
    exercises: Map<String, Exercise>,
    unit: LoadUnit,
    female: Boolean,
    onDone: () -> Unit,
    onSaveRoutine: (() -> Unit)?,
    routineSaved: Boolean,
) {
    val s = summary.workout
    AlertDialog(
        onDismissRequest = onDone,
        icon = { Icon(TrainingIcons.Trophy, contentDescription = null) },
        title = { Text(stringResource(R.string.training_finished_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                s.durationMs?.let { InfoRow(stringResource(R.string.training_duration), durationText(it)) }
                InfoRow(stringResource(R.string.training_stat_sets), Fmt.count(s.workingSets.toLong()))
                if (s.volumeKg > 0) InfoRow(stringResource(R.string.training_stat_volume), volumeText(s.volumeKg, unit))
                if (summary.records > 0) {
                    InfoRow(stringResource(R.string.training_records), Fmt.count(summary.records.toLong()))
                    Text(
                        summary.recordExerciseIds.mapNotNull { exercises[it] }.map { it.displayName() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (summary.muscles.isNotEmpty()) {
                    MusclesWorked(summary.muscles, female, Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.training_done)) } },
        dismissButton = onSaveRoutine?.let { save ->
            {
                TextButton(onClick = save, enabled = !routineSaved) {
                    Text(stringResource(if (routineSaved) R.string.training_routine_saved else R.string.training_routine_save_from_workout))
                }
            }
        },
    )
}

/**
 * Small front and back figure, each muscle as strong as its share of the workout's sets, and the
 * [listed] most trained muscles with their sets.
 */
@Composable
internal fun MusclesWorked(
    muscles: Map<Muscle, Double>,
    female: Boolean,
    modifier: Modifier = Modifier,
    figureHeight: Dp = 180.dp,
    listed: Int = Int.MAX_VALUE,
) {
    val primary = MaterialTheme.colorScheme.primary
    val max = muscles.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BodyMapPair(
            female = female,
            fill = { m -> muscles[m]?.let { primary.copy(alpha = (0.3 + 0.7 * it / max).toFloat().coerceIn(0.3f, 1f)) } },
            modifier = Modifier.fillMaxWidth().height(figureHeight),
            describe = { m -> muscles[m]?.let { setsText(it) } },
        )
        Text(
            muscles.entries.sortedByDescending { it.value }.take(listed)
                .map { (m, v) -> stringResource(R.string.training_muscle_sets, m.label(), setsText(v)) }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "4 sets", or "2.5 sets" (secondary muscles count half sets; fractions take no plural form). */
private fun setsText(sets: Double): String =
    if (sets % 1.0 == 0.0) {
        Fmt.plural(R.plurals.training_sets_count, sets.toInt(), Fmt.count(sets.toLong()))
    } else {
        Fmt.string(R.string.training_sets_fraction, Fmt.num(sets, 1))
    }
