package com.enjoyyourtime.globalfitness.ui.training

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.data.WorkoutEdits
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.RoutineExercise
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import java.util.UUID

private const val MAX_ROUTINE_NOTE = 300

/** "−" for the set stepper (material-icons-core has no Remove icon). */
private val MinusIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Remove") {
        materialPath {
            moveTo(19f, 13f)
            horizontalLineTo(5f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(14f)
            close()
        }
    }
}

/**
 * Creates a routine, edits one of the user's ([routineId]) or starts a new one as a copy of any
 * routine ([fromId], e.g. a built-in one): name, note and exercises, each with its sets and target
 * reps or time, in order. Exercises are added through the exercise picker ([onAddExercises]), whose
 * choice comes back as [pickedExerciseIds] ([onPickedConsumed] clears it). [onSaved] gets the saved
 * routine's id once the save has finished and the page is in front (also after a rotation).
 */
@Composable
fun RoutineEditorScreen(
    routineId: String?,
    fromId: String?,
    pickedExerciseIds: List<String>?,
    onPickedConsumed: () -> Unit,
    onAddExercises: () -> Unit,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val vm = trainingViewModel()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val title = stringResource(if (routineId == null) R.string.training_routine_new else R.string.training_routine_edit_title)
    if (!loaded) {
        TrainingSubPage(title = title, onBack = onBack) { padding ->
            Box(Modifier.subPageContent(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        return
    }
    val existing = routineId?.let { vm.userRoutine(it) }
    if (routineId != null && existing == null) {
        TrainingSubPage(title = title, onBack = onBack) { padding ->
            Box(Modifier.subPageContent(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.training_routine_missing), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
        }
        return
    }
    val context = LocalContext.current
    RoutineEditorForm(
        vm = vm,
        routineId = routineId,
        title = title,
        initial = {
            val source = fromId?.let { vm.routineTemplate(it) }
            when {
                existing != null -> RoutineForm.of(existing.name, existing.note, existing.exercises)
                source != null -> RoutineForm.of(source.name.resolve(context).take(MAX_ROUTINE_NAME), source.note, source.exercises)
                else -> RoutineForm.EMPTY
            }
        },
        pickedExerciseIds = pickedExerciseIds,
        onPickedConsumed = onPickedConsumed,
        onAddExercises = onAddExercises,
        onBack = onBack,
        onSaved = onSaved,
    )
}

@Composable
private fun RoutineEditorForm(
    vm: TrainingViewModel,
    routineId: String?,
    title: String,
    initial: () -> RoutineForm,
    pickedExerciseIds: List<String>?,
    onPickedConsumed: () -> Unit,
    onAddExercises: () -> Unit,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    // The form as it opened (survives recreation): leaving with changes asks first.
    val opened = rememberSaveable(saver = RoutineFormSaver) { initial() }
    var form by rememberSaveable(stateSaver = RoutineFormSaver) { mutableStateOf(opened) }
    var triedSave by rememberSaveable { mutableStateOf(false) }
    var askDiscard by rememberSaveable { mutableStateOf(false) }
    val exercises by vm.exercises.collectAsStateWithLifecycle()
    // One id per editor session: the view model keeps its save's result across rotation and Home.
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    val save by remember(requestId) { vm.routineSave(requestId) }.collectAsStateWithLifecycle(null)
    val saving = save == EditorSave.Saving || save is EditorSave.Saved
    val changed = form.isChangedFrom(opened)
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnSaved by rememberUpdatedState(onSaved)

    LaunchedEffect(pickedExerciseIds) {
        if (pickedExerciseIds != null) {
            form = form.add(pickedExerciseIds, vm::routineTarget)
            onPickedConsumed()
        }
    }
    LaunchedEffect(save) {
        when (val s = save) {
            // Navigates only with the page in front: a save that ends while the app is in the background waits.
            is EditorSave.Saved -> lifecycle.withResumed { currentOnSaved(s.id) }
            // Consumed once shown: consuming first changes this effect's key, which would cancel the snackbar.
            EditorSave.Failed -> try {
                snackbar.showSnackbar(context.getString(R.string.training_routine_save_failed))
            } finally {
                vm.consumeRoutineSave(requestId)
            }
            else -> Unit
        }
    }
    // The page left (for good, or for the picker, which is never opened while saving) forgets its
    // finished save; not on rotation, where the session goes on with the recreated page.
    DisposableEffect(requestId) {
        onDispose { if (activity?.isChangingConfigurations != true) vm.closeRoutineEditor(requestId) }
    }
    val leave = { if (changed && !saving) askDiscard = true else onBack() }
    BackHandler(enabled = changed && !saving) { askDiscard = true }

    fun save() {
        triedSave = true
        if (!form.isValid || saving) return
        vm.saveRoutine(requestId, routineId, form.copy(name = form.name.trim(), note = form.note.trim()))
    }

    TrainingSubPage(
        title = title,
        onBack = leave,
        snackbar = snackbar,
        actions = { TextButton(onClick = ::save, enabled = !saving) { Text(stringResource(R.string.action_save)) } },
    ) { padding ->
        Column(
            Modifier
                .subPageContent(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { form = form.copy(name = it.take(MAX_ROUTINE_NAME)) },
                label = { Text(stringResource(R.string.training_editor_name)) },
                placeholder = { Text(stringResource(R.string.training_routine_name_hint)) },
                singleLine = true,
                isError = triedSave && form.nameMissing,
                supportingText = { if (triedSave && form.nameMissing) Text(stringResource(R.string.training_editor_name_error)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.note,
                onValueChange = { form = form.copy(note = it.take(MAX_ROUTINE_NOTE)) },
                label = { Text(stringResource(R.string.training_routine_note)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.training_routine_exercises), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                if (form.exercises.isEmpty()) {
                    val error = triedSave && form.exercisesMissing
                    Text(
                        stringResource(if (error) R.string.training_routine_exercises_error else R.string.training_routine_editor_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                form.exercises.forEachIndexed { index, target ->
                    // Keyed by exercise: the target fields keep their text when the list is reordered.
                    key(target.exerciseId) {
                        RoutineExerciseCard(
                            vm = vm,
                            target = target,
                            exercise = exercises[target.exerciseId] ?: TrainingRepository.unknownExercise(target.exerciseId),
                            index = index,
                            count = form.exercises.size,
                            onChange = { transform -> form = form.update(target.exerciseId, transform) },
                            onSets = { form = form.withSets(target.exerciseId, it) },
                            onMove = { delta -> form = form.move(target.exerciseId, delta) },
                            onRemove = { form = form.remove(target.exerciseId) },
                        )
                    }
                }
                FilledTonalButton(onClick = onAddExercises, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.training_routine_add_exercises))
                }
            }

            Button(onClick = ::save, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.action_save))
            }
        }
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(stringResource(R.string.training_discard_changes)) },
            confirmButton = {
                TextButton(onClick = {
                    askDiscard = false
                    onBack()
                }) { Text(stringResource(R.string.training_discard), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askDiscard = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Saves a [RoutineForm] across recreation as plain values ([RoutineForm.toSaved]). */
private val RoutineFormSaver: Saver<RoutineForm, Any> = listSaver(save = { it.toSaved() }, restore = { RoutineForm.fromSaved(it) })

/** One exercise of the routine: sets stepper, target reps or time, and its menu (move, remove). */
@Composable
private fun RoutineExerciseCard(
    vm: TrainingViewModel,
    target: RoutineExercise,
    exercise: Exercise,
    index: Int,
    count: Int,
    onChange: ((RoutineExercise) -> RoutineExercise) -> Unit,
    onSets: (Int) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val name = exercise.displayName()
    var menu by remember { mutableStateOf(false) }
    AppCard {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExerciseThumbnail(exercise, size = 40.dp, vm = vm)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(exercise.kind.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ExerciseInfoButton(exercise)
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.training_exercise_options, name))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (index > 0) {
                            MenuEntry(stringResource(R.string.training_move_up), Icons.Filled.KeyboardArrowUp) {
                                menu = false
                                onMove(-1)
                            }
                        }
                        if (index < count - 1) {
                            MenuEntry(stringResource(R.string.training_move_down), Icons.Filled.KeyboardArrowDown) {
                                menu = false
                                onMove(1)
                            }
                        }
                        MenuEntry(stringResource(R.string.training_remove_exercise), Icons.Filled.Delete) {
                            menu = false
                            onRemove()
                        }
                    }
                }
            }
            Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SetsStepper(target.sets, name, onSets)
                Spacer(Modifier.weight(1f))
                if (exercise.kind.usesDuration) {
                    TimeTargetField(target.durationSec, name, onValue = { s -> onChange { it.copy(durationSec = s) } })
                } else {
                    RepsTargetField(target.reps, name, onValue = { r -> onChange { it.copy(reps = r) } })
                }
            }
        }
    }
}

@Composable
private fun MenuEntry(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, contentDescription = null) }, onClick = onClick)
}

/** − "3 sets" +, within 1..[WorkoutEdits.MAX_ROUTINE_SETS]; the count is announced as it changes. */
@Composable
private fun SetsStepper(sets: Int, name: String, onSets: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { onSets(sets - 1) }, enabled = sets > 1) {
            Icon(MinusIcon, contentDescription = stringResource(R.string.training_routine_fewer_sets, name))
        }
        Text(
            Fmt.plural(R.plurals.training_sets_count, sets, Fmt.count(sets.toLong())),
            style = MaterialTheme.typography.titleSmall.merge(TabularNumbers),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 72.dp).padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        FilledTonalIconButton(onClick = { onSets(sets + 1) }, enabled = sets < WorkoutEdits.MAX_ROUTINE_SETS) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.training_routine_more_sets, name))
        }
    }
}

/** Target reps; empty means "as last time" (the sets take the reps of the last session). */
@Composable
private fun RepsTargetField(reps: Int?, name: String, onValue: (Int?) -> Unit) {
    val locale = Fmt.locale
    var text by remember { mutableStateOf(reps?.let { TrainingFormat.count(it, locale) }.orEmpty()) }
    val description = stringResource(R.string.training_spoken_pair, stringResource(R.string.training_field_reps), name)
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new.filter(Char::isDigit).take(RoutineFormat.MAX_REPS_DIGITS)
            onValue(RoutineFormat.parseReps(text))
        },
        label = { Text(stringResource(R.string.training_col_reps), maxLines = 1) },
        placeholder = { Text("—") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.merge(TabularNumbers),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        modifier = Modifier.width(96.dp).semantics { contentDescription = description },
    )
}

/** Target time typed as digits from the right ("130" is 1:30), like the workout's time cells. */
@Composable
private fun TimeTargetField(seconds: Int?, name: String, onValue: (Int?) -> Unit) {
    val locale = Fmt.locale
    fun masked(s: Int?) = TrainingFormat.maskDuration(TrainingFormat.secondsToDigits(s), locale)
    var field by remember { mutableStateOf(masked(seconds).let { TextFieldValue(it, TextRange(it.length)) }) }
    val description = stringResource(R.string.training_spoken_pair, stringResource(R.string.training_col_time), name)
    OutlinedTextField(
        value = field,
        onValueChange = { new ->
            val digits = TrainingFormat.digitsOnly(new.text)
            val text = TrainingFormat.maskDuration(digits, locale)
            field = TextFieldValue(text, TextRange(text.length))
            onValue(TrainingFormat.digitsToSeconds(digits)?.takeIf { it > 0 })
        },
        label = { Text(stringResource(R.string.training_col_time), maxLines = 1) },
        placeholder = { Text(TrainingFormat.duration(0, locale)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.merge(TabularNumbers),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        modifier = Modifier.width(112.dp).semantics { contentDescription = description },
    )
}
