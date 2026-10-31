package com.enjoyyourtime.globalfitness.ui.training

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.model.CustomExercise
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import kotlinx.coroutines.launch
import java.util.UUID

private const val MAX_EXERCISE_NAME = 60
private const val MAX_SETTINGS = 300

/** What logging by [kind] means; for a sport or activity, timed logging has no machine level. */
@StringRes
private fun explanationRes(kind: ExerciseKind, equipment: Equipment): Int = when (kind) {
    ExerciseKind.WEIGHT_REPS -> R.string.training_kind_weight_reps_text
    ExerciseKind.BODYWEIGHT_REPS -> R.string.training_kind_bodyweight_reps_text
    ExerciseKind.ASSISTED_REPS -> R.string.training_kind_assisted_reps_text
    ExerciseKind.DURATION -> R.string.training_kind_duration_text
    ExerciseKind.CARDIO -> if (equipment == Equipment.SPORT) R.string.training_kind_sport_text else R.string.training_kind_cardio_text
}

/** How sets are usually logged with [equipment], until the user picks a kind themselves. */
private fun defaultKind(equipment: Equipment): ExerciseKind = when (equipment) {
    Equipment.BODYWEIGHT -> ExerciseKind.BODYWEIGHT_REPS
    Equipment.CARDIO_MACHINE, Equipment.SPORT -> ExerciseKind.CARDIO
    else -> ExerciseKind.WEIGHT_REPS
}

/**
 * Creates ([exerciseId] null) or edits one of the user's exercises or machines: name, equipment,
 * how it is logged, primary and secondary muscles, machine settings and a photo. Built-in exercises
 * are not edited here (their notes, photo and star are on the detail page). [onSaved] gets the saved
 * id once the save has finished and the page is in front (also after a rotation or Home meanwhile).
 * [sport]: a new one starts as a sport or activity.
 */
@Composable
fun ExerciseEditorScreen(exerciseId: String?, onBack: () -> Unit, onSaved: (String) -> Unit, sport: Boolean = false) {
    val vm = trainingViewModel()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val title = stringResource(
        when {
            exerciseId != null -> R.string.training_editor_edit
            sport -> R.string.training_new_activity
            else -> R.string.training_editor_new
        },
    )
    if (!loaded) {
        TrainingSubPage(title = title, onBack = onBack) { padding ->
            Box(Modifier.subPageContent(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        return
    }
    val existing = exerciseId?.let { vm.customExercise(it) }
    if (exerciseId != null && existing == null) {
        TrainingSubPage(title = title, onBack = onBack) { padding ->
            Box(Modifier.subPageContent(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.training_editor_not_editable), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
        }
        return
    }
    EditorForm(vm, existing, title, onBack, onSaved, newEquipment = if (sport) Equipment.SPORT else Equipment.MACHINE)
}

@Composable
private fun EditorForm(
    vm: TrainingViewModel,
    existing: CustomExercise?,
    title: String,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    newEquipment: Equipment,
) {
    // The form as it opened (survives recreation): leaving with changes asks first, and only machine
    // settings changed here are written over the stored ones. The note is read from the store itself,
    // so settings saved on the detail page just before "Edit" are there.
    val opened = rememberSaveable(saver = FormSaver) {
        val note = existing?.let { vm.storedNote(it.id) }
        ExerciseForm(
            name = existing?.name.orEmpty(),
            equipment = existing?.equipment ?: newEquipment,
            kind = existing?.kind ?: defaultKind(newEquipment),
            primary = existing?.primary.orEmpty(),
            secondary = existing?.secondary.orEmpty(),
            settings = note?.notes.orEmpty(),
            photo = note?.photo,
        )
    }
    var form by rememberSaveable(stateSaver = FormSaver) { mutableStateOf(opened) }
    var kindChosen by rememberSaveable { mutableStateOf(existing != null) }
    var triedSave by rememberSaveable { mutableStateOf(false) }
    var askDiscard by rememberSaveable { mutableStateOf(false) }
    // One id per editor session: the view model keeps its save's result across rotation and Home.
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    val save by remember(requestId) { vm.editorSave(requestId) }.collectAsStateWithLifecycle(null)
    val saving = save == EditorSave.Saving || save is EditorSave.Saved
    val primary = form.primary
    val secondary = form.secondary - primary
    val nameError = form.name.isBlank()
    val muscleError = primary.isEmpty() && form.kind != ExerciseKind.CARDIO
    val changed = form.isChangedFrom(opened)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnSaved by rememberUpdatedState(onSaved)
    PhotoFailedMessages(vm, snackbar)

    LaunchedEffect(save) {
        when (val s = save) {
            // Navigates only with the page in front: a save that ends while the app is in the background waits.
            is EditorSave.Saved -> lifecycle.withResumed { currentOnSaved(s.id) }
            // Consumed once shown: consuming first changes this effect's key, which would cancel the snackbar.
            EditorSave.Failed -> try {
                snackbar.showSnackbar(context.getString(R.string.training_save_failed))
            } finally {
                vm.consumeEditorSave(requestId)
            }
            else -> Unit
        }
    }

    // Leaving for good drops the photos picked here, unless a save is storing one (not on rotation:
    // the form keeps them).
    DisposableEffect(requestId) {
        onDispose { if (activity?.isChangingConfigurations != true) vm.closeEditor(requestId) }
    }
    val leave = { if (changed && !saving) askDiscard = true else onBack() }
    BackHandler(enabled = changed && !saving) { askDiscard = true }

    val launcher = rememberPhotoLauncher(
        vm,
        onPhoto = { uri, fromCamera ->
            vm.importDraftPhoto(uri, fromCamera) { file -> if (file != null) form = form.copy(photo = file) }
        },
        onUnavailable = { scope.launch { snackbar.showSnackbar(context.getString(R.string.training_photo_unavailable)) } },
    )

    fun save() {
        triedSave = true
        if (nameError || muscleError || saving) return
        vm.saveExercise(
            requestId,
            ExerciseDraft(
                id = existing?.id,
                name = form.name.trim(),
                kind = form.kind,
                equipment = form.equipment,
                primary = primary,
                secondary = secondary,
                notes = form.settings.takeIf { it.trim() != opened.settings.trim() },
                photo = form.photo,
                photoChanged = form.photo != opened.photo,
            ),
        )
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
                onValueChange = { form = form.copy(name = it.take(MAX_EXERCISE_NAME)) },
                label = { Text(stringResource(R.string.training_editor_name)) },
                placeholder = { Text(stringResource(R.string.training_editor_name_hint)) },
                singleLine = true,
                isError = triedSave && nameError,
                supportingText = { if (triedSave && nameError) Text(stringResource(R.string.training_editor_name_error)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            EditorSection(stringResource(R.string.training_editor_equipment)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Equipment.entries.forEach { e ->
                        FilterChip(
                            selected = e == form.equipment,
                            onClick = { form = form.copy(equipment = e, kind = if (kindChosen) form.kind else defaultKind(e)) },
                            label = { Text(e.label()) },
                            leadingIcon = { Icon(e.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
            }

            EditorSection(stringResource(R.string.training_editor_kind)) {
                Column(Modifier.selectableGroup()) {
                    ExerciseKind.entries.forEach { k ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .selectable(
                                    selected = k == form.kind,
                                    role = Role.RadioButton,
                                    onClick = {
                                        form = form.copy(kind = k)
                                        kindChosen = true
                                    },
                                )
                                .heightIn(min = 56.dp)
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = k == form.kind, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(k.label(), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(explanationRes(k, form.equipment)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (existing != null && form.kind != existing.kind) {
                    Text(stringResource(R.string.training_editor_kind_changed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            EditorSection(stringResource(R.string.training_muscle_primary)) {
                MuscleChooser(
                    selected = primary,
                    disabled = emptySet(),
                    onToggle = { m -> form = form.copy(primary = if (m in primary) primary - m else primary + m, secondary = secondary - m) },
                )
                if (triedSave && muscleError) {
                    Text(stringResource(R.string.training_editor_muscle_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            EditorSection(stringResource(R.string.training_muscle_secondary)) {
                MuscleChooser(
                    selected = secondary,
                    disabled = primary,
                    onToggle = { m -> form = form.copy(secondary = if (m in secondary) secondary - m else secondary + m) },
                )
            }

            EditorSection(stringResource(notesTitleRes(form.equipment))) {
                OutlinedTextField(
                    value = form.settings,
                    onValueChange = { form = form.copy(settings = it.take(MAX_SETTINGS)) },
                    placeholder = { Text(stringResource(notesHintRes(form.equipment))) },
                    leadingIcon = { Icon(TrainingIcons.Tune, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            EditorSection(stringResource(R.string.training_editor_photo)) {
                EditorPhoto(
                    vm = vm,
                    photo = form.photo,
                    equipment = form.equipment,
                    launcher = launcher,
                    onRemove = { form = form.copy(photo = null) },
                )
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

/** Saves an [ExerciseForm] across recreation as plain values (muscles as their stored codes). */
private val FormSaver: Saver<ExerciseForm, Any> = listSaver(
    save = { listOf(it.name, it.equipment.code, it.kind.code, Muscle.encode(it.primary), Muscle.encode(it.secondary), it.settings, it.photo.orEmpty()) },
    restore = {
        ExerciseForm(
            name = it[0],
            equipment = Equipment.fromCode(it[1]),
            kind = ExerciseKind.fromCode(it[2]),
            primary = Muscle.decode(it[3]),
            secondary = Muscle.decode(it[4]),
            settings = it[5],
            photo = it[6].ifEmpty { null },
        )
    },
)

@Composable
private fun EditorSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun MuscleChooser(selected: Set<Muscle>, disabled: Set<Muscle>, onToggle: (Muscle) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Muscle.entries.forEach { m ->
            FilterChip(
                selected = m in selected,
                enabled = m !in disabled,
                onClick = { onToggle(m) },
                label = { Text(m.label()) },
            )
        }
    }
}

@Composable
private fun EditorPhoto(vm: TrainingViewModel, photo: String?, equipment: Equipment, launcher: PhotoLauncher, onRemove: () -> Unit) {
    val px = with(LocalDensity.current) { 192.dp.roundToPx() }
    val bitmap = rememberPhoto(vm, photo, px)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(96.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(equipment.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (launcher.canTakePhoto) {
                OutlinedButton(onClick = launcher.takePhoto) {
                    Icon(TrainingIcons.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.training_photo_take))
                }
            }
            OutlinedButton(onClick = launcher.pickPhoto) {
                Icon(TrainingIcons.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.training_photo_pick))
            }
            if (photo != null) {
                TextButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.training_photo_remove))
                }
            }
        }
    }
}
