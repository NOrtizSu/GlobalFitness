@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.training

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.RoutineExercise
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.ConfirmDialog
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import kotlinx.coroutines.launch

/** Longest routine name: it labels cards and list rows. */
internal const val MAX_ROUTINE_NAME = 60

/** "6 exercises · Chest · Triceps". */
@Composable
internal fun routineSubtitle(r: RoutineSummary): String =
    (listOf(Fmt.plural(R.plurals.training_exercises_count, r.exercises, Fmt.count(r.exercises.toLong()))) + r.muscles.map { it.label() })
        .joinToString(" · ")

/** The user's routines get the accent colour; the built-in ones stay neutral. */
@Composable
private fun routineTint(builtIn: Boolean): Color =
    if (builtIn) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary

/** A routine in the Entreno tab's row: name, number of exercises and the muscles it trains most. */
@Composable
internal fun RoutineCard(r: RoutineSummary, onClick: () -> Unit) {
    val context = LocalContext.current
    AppCard(Modifier.width(200.dp), onClick = onClick, onClickLabel = stringResource(R.string.training_routine_open)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = routineTint(r.template.builtIn))
            // Two lines kept for every name, so the cards of the row line up.
            Text(r.template.name.resolve(context), style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                Fmt.plural(R.plurals.training_exercises_count, r.exercises, Fmt.count(r.exercises.toLong())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                r.muscles.map { it.label() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A routine: its exercises with their targets, the muscles they work, and starting a workout with it
 * (or, while one is in progress, adding the exercises it lacks). The user's routines are edited or
 * deleted here; a built-in one is duplicated into an editable copy. [onOpenWorkout] opens the
 * workout started or filled.
 */
@Composable
fun RoutineScreen(
    routineId: String,
    onBack: () -> Unit,
    onOpenExercise: (String) -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onOpenWorkout: (String) -> Unit,
) {
    val vm = trainingViewModel()
    val state by remember(routineId) { vm.routine(routineId) }.collectAsStateWithLifecycle(Loadable.Loading)
    val female by vm.female.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var deleting by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var lastDetail by remember { mutableStateOf<RoutineDetail?>(null) }
    (state as? Loadable.Ready)?.value?.let { lastDetail = it }
    // A routine being deleted stays on screen while the page leaves.
    val detail = (state as? Loadable.Ready)?.value ?: lastDetail.takeIf { deleting }

    if (detail == null) {
        TrainingSubPage(title = stringResource(R.string.training_routine_title), onBack = onBack) { padding ->
            Box(Modifier.subPageContent(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                if (state == Loadable.Missing) {
                    Text(stringResource(R.string.training_routine_missing), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                } else {
                    CircularProgressIndicator()
                }
            }
        }
        return
    }
    val t = detail.template
    val name = t.name.resolve(context)
    // Starting and adding answer after a database write: by then the page may have been left.
    val inFront = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
    val start: () -> Unit = { vm.startRoutine(t.id, name) { id -> if (inFront()) onOpenWorkout(id) } }
    val addToActive: (String) -> Unit = { workoutId ->
        vm.addRoutine(workoutId, t.id) { added ->
            if (added.isEmpty()) {
                scope.launch { snackbar.showSnackbar(context.getString(R.string.training_routine_nothing_added), withDismissAction = true) }
            } else if (inFront()) {
                onOpenWorkout(workoutId)
            }
        }
    }

    TrainingSubPage(
        title = stringResource(R.string.training_routine_title),
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            if (!t.builtIn) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.training_more_options))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.training_routine_delete)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = {
                                menu = false
                                confirmDelete = true
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.subPageContent(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                RoutineHeader(
                    detail = detail,
                    name = name,
                    onStart = start,
                    onAddToActive = addToActive,
                    onEdit = if (t.builtIn) onDuplicate else onEdit,
                )
            }
            item(key = "exercises-title") { SectionTitle(stringResource(R.string.training_routine_exercises)) }
            item(key = "exercises") { RoutineItems(vm, detail.items, onOpenExercise) }
            item(key = "loads") {
                Text(
                    stringResource(R.string.training_routine_loads_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (detail.muscles.isNotEmpty()) {
                item(key = "muscles-title") { SectionTitle(stringResource(R.string.training_routine_muscles)) }
                item(key = "muscles") {
                    AppCard { MusclesWorked(detail.muscles, female, Modifier.padding(16.dp), figureHeight = 150.dp, listed = 4) }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.training_routine_delete_title, name),
            text = stringResource(R.string.training_routine_delete_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                confirmDelete = false
                if (inFront()) {
                    deleting = true
                    vm.deleteRoutine(t.id)
                    onBack()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun RoutineHeader(
    detail: RoutineDetail,
    name: String,
    onStart: () -> Unit,
    onAddToActive: (String) -> Unit,
    onEdit: () -> Unit,
) {
    val t = detail.template
    val active = detail.activeWorkoutId
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (t.builtIn) R.string.training_routine_builtin else R.string.training_routine_own),
                style = MaterialTheme.typography.labelLarge,
                color = routineTint(t.builtIn),
            )
            Text(name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            t.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                exercisesAndSets(detail.items.size, detail.sets),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            if (active == null) {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.training_routine_start))
                }
            } else {
                Button(onClick = { onAddToActive(active) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.training_routine_add_to_active))
                }
                Text(stringResource(R.string.training_routine_active_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (t.builtIn) R.string.training_routine_duplicate else R.string.training_edit))
            }
        }
    }
}

/** The routine's exercises as one rounded block (one list item: the list's spacing stays outside); a row opens the exercise. */
@Composable
private fun RoutineItems(vm: TrainingViewModel, items: List<RoutineItemUi>, onOpenExercise: (String) -> Unit) {
    Column {
        items.forEachIndexed { index, item ->
            key(item.target.exerciseId) { RoutineItemRow(vm, item, index, items.size, onOpenExercise) }
        }
    }
}

@Composable
private fun RoutineItemRow(vm: TrainingViewModel, item: RoutineItemUi, index: Int, count: Int, onOpenExercise: (String) -> Unit) {
    val exercise = item.exercise
    GroupedRow(index = index, count = count, onClick = { onOpenExercise(exercise.id) }) {
        ExerciseThumbnail(exercise, size = 44.dp, vm = vm)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(exercise.displayName(), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                exercise.primary.sortedBy { it.ordinal }.map { it.label() }.ifEmpty { listOf(exercise.equipment.label()) }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        TargetText(item.target, exercise.kind)
        ExerciseInfoButton(exercise, onOpenProgress = { onOpenExercise(exercise.id) })
    }
}

/** "3 × 10" / "3 × 0:45", or "3 sets" without a target value; read out as "3 sets, 10 reps". */
@Composable
private fun TargetText(target: RoutineExercise, kind: ExerciseKind) {
    val locale = Fmt.locale
    val sets = Fmt.plural(R.plurals.training_sets_count, target.sets, Fmt.count(target.sets.toLong()))
    val value = RoutineFormat.targetValue(target, kind, locale)
    val spoken = when {
        value == null -> sets
        kind.usesDuration -> stringResource(R.string.training_spoken_pair, sets, value)
        else -> stringResource(R.string.training_spoken_pair, sets, stringResource(R.string.training_value_reps, value))
    }
    Text(
        RoutineFormat.target(target, kind, locale) ?: sets,
        style = MaterialTheme.typography.titleSmall.merge(TabularNumbers),
        modifier = Modifier.semantics { contentDescription = spoken },
    )
}

/**
 * The routines to add to a workout in one tap (the user's first, then the built-in ones). A routine
 * whose exercises the workout already has all says so. [onPick] runs once, after the sheet hid.
 */
@Composable
internal fun RoutineSheet(routines: List<RoutineSummary>, present: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(false) }
    val (own, builtIn) = routines.partition { !it.template.builtIn }
    val pick = { id: String ->
        if (!picked) {
            picked = true
            scope.launch { sheetState.hide() }.invokeOnCompletion { onPick(id) }
        }
    }
    val ownTitle = stringResource(R.string.training_routines_own)
    val builtInTitle = stringResource(R.string.training_routines_builtin)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "title") {
                Text(
                    stringResource(R.string.training_routine_add),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
                )
            }
            sheetGroup("own", ownTitle, own, present, pick)
            sheetGroup("builtin", builtInTitle, builtIn, present, pick)
        }
    }
}

private fun LazyListScope.sheetGroup(key: String, title: String, list: List<RoutineSummary>, present: Set<String>, onPick: (String) -> Unit) {
    if (list.isEmpty()) return
    item(key = "h-$key") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
        )
    }
    items(list, key = { "$key-${it.id}" }) { r -> SheetRow(r, alreadyIn = RoutineStats.newExercises(r.template, present) == 0, onClick = { onPick(r.id) }) }
}

@Composable
private fun SheetRow(r: RoutineSummary, alreadyIn: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.training_routine_add), onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = routineTint(r.template.builtIn))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(r.template.name.resolve(context), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (alreadyIn) stringResource(R.string.training_routine_already_in) else routineSubtitle(r),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Filled.Add, contentDescription = null, tint = if (alreadyIn) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)
    }
}

/** Asks the name of the routine a workout is saved as ([initialName]: the workout's name or title). */
@Composable
internal fun SaveRoutineDialog(initialName: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName.take(MAX_ROUTINE_NAME)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.training_routine_save_from_workout)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.training_routine_save_text), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_ROUTINE_NAME) },
                    label = { Text(stringResource(R.string.training_editor_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
