@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.training

import android.animation.ValueAnimator
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutBoundsHolder
import androidx.compose.ui.layout.layoutBounds
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseGuide
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseGuides
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.training.motion.ExerciseAnimation
import com.enjoyyourtime.globalfitness.ui.training.motion.hasAnimation
import kotlinx.coroutines.launch

/** Whether the info sheet can show how [exercise] is done (its steps or its animation), not only what the exercise page has. */
internal fun hasHowTo(exercise: Exercise): Boolean = ExerciseGuides[exercise.id] != null || hasAnimation(exercise)

/**
 * The ⓘ button next to an exercise: opens its [ExerciseInfoSheet]. It only adds a way in; tapping the
 * row or card itself keeps doing what it did. Nothing is shown for an exercise that no longer exists
 * (a deleted one still named in a past workout). [onOpenProgress]: see [ExerciseInfoSheet].
 */
@Composable
fun ExerciseInfoButton(exercise: Exercise, modifier: Modifier = Modifier, onOpenProgress: (() -> Unit)? = null) {
    val vm = trainingViewModel()
    if (!exercise.builtIn && vm.customExercise(exercise.id) == null) return
    var open by rememberSaveable(exercise.id) { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = stringResource(R.string.training_info_open, exercise.displayName()),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) ExerciseInfoSheet(exercise, onDismiss = { open = false }, onOpenProgress = onOpenProgress)
}

/**
 * How [exercise] is done: its animation (with play / pause), equipment and kind, the muscles it
 * works, the numbered steps and the tips of its guide, then the user's photo and machine settings.
 * The user's own exercises have no guide: the sheet says so and shows their photo and settings.
 * [onOpenProgress] adds "See progress", which opens the exercise's page once the sheet has hidden;
 * pages where leaving would interrupt something (a picker, an editor) and the exercise's own page
 * leave it out.
 */
@Composable
fun ExerciseInfoSheet(exercise: Exercise, onDismiss: () -> Unit, onOpenProgress: (() -> Unit)? = null) {
    val vm = trainingViewModel()
    val female by vm.female.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    val guide = ExerciseGuides[exercise.id]
    val notes = exercise.notes?.trim()?.takeIf { it.isNotEmpty() }
    // The sheet's scrolling viewport, against which the animation tells whether it is in sight.
    val viewport = remember { LayoutBoundsHolder() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .layoutBounds(viewport)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (hasAnimation(exercise)) AnimationPanel(exercise, viewport)
            InfoTitle(exercise)
            if (exercise.muscles.isNotEmpty()) ExerciseMuscles(exercise, female)
            when {
                guide != null -> GuideSections(guide)
                !exercise.builtIn -> NoGuide(addHint = exercise.photo == null && notes == null)
            }
            exercise.photo?.let { InfoPhoto(vm, it, exercise.displayName()) }
            notes?.let { SettingsSection(it) }
            if (onOpenProgress != null) {
                OutlinedButton(
                    onClick = {
                        if (!leaving) {
                            leaving = true
                            scope.launch { sheetState.hide() }.invokeOnCompletion {
                                onDismiss()
                                onOpenProgress()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.training_info_progress))
                }
            }
        }
    }
}

/**
 * The looping animation, at most 280 dp wide so the name shows below it in the half-open sheet. It
 * starts paused when the system "remove animations" setting is on; the button plays it anyway.
 * Scrolled out of the sheet's [viewport] it stops building frames (it starts over when back in sight).
 */
@Composable
private fun AnimationPanel(exercise: Exercise, viewport: LayoutBoundsHolder) {
    val animatorsOn = remember { ValueAnimator.areAnimatorsEnabled() }
    var playing by rememberSaveable(exercise.id) { mutableStateOf(animatorsOn) }
    var inSight by remember { mutableStateOf(true) }
    val description = stringResource(R.string.training_info_animation, exercise.displayName())
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = 280.dp).fillMaxWidth().clip(MaterialTheme.shapes.large)) {
            ExerciseAnimation(
                exercise,
                modifier = Modifier
                    .fillMaxWidth()
                    .onVisibilityChanged(minFractionVisible = 0f, viewportBounds = viewport) { inSight = it }
                    .clearAndSetSemantics { contentDescription = description },
                playing = playing && inSight,
            )
            FilledTonalIconButton(onClick = { playing = !playing }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                Icon(
                    if (playing) PauseIcon else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.training_info_pause else R.string.training_info_play),
                )
            }
        }
    }
}

@Composable
private fun InfoTitle(exercise: Exercise) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(exercise.displayName(), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(
            listOfNotNull(
                exercise.equipment.label(),
                exercise.kind.label(),
                if (exercise.builtIn) null else stringResource(R.string.training_own_exercise),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
}

/** The guide's steps, numbered, and its tips, bulleted: one line each, so they read as lists. */
@Composable
private fun GuideSections(guide: ExerciseGuide) {
    val stepsText = stringResource(guide.steps)
    val tipsText = stringResource(guide.tips)
    val steps = remember(stepsText) { GuideText.steps(stepsText) }
    val tips = remember(tipsText) { GuideText.tips(tipsText) }
    if (steps.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetHeading(stringResource(R.string.training_info_steps))
            steps.forEachIndexed { i, step -> StepRow(i + 1, step) }
        }
    }
    if (tips.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetHeading(stringResource(R.string.training_info_tips))
            tips.forEach { TipRow(it) }
        }
    }
}

/** A numbered step, read by TalkBack as "Step 2, …". The badge grows with large text. */
@Composable
private fun StepRow(number: Int, text: String) {
    val numberText = Fmt.count(number.toLong())
    val spoken = stringResource(R.string.training_info_step, numberText)
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
        ) {
            Box(Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                Text(numberText, style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(top = 2.dp))
    }
}

@Composable
private fun TipRow(text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // The bullet in the text's own style, so it stays on the first line's middle at any text size.
        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clearAndSetSemantics {})
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** In place of the guide for the user's own exercise; [addHint] when it has no photo or settings to show instead. */
@Composable
private fun NoGuide(addHint: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.training_info_no_guide), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (addHint) {
                Text(stringResource(R.string.training_info_no_guide_add), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The user's photo of the machine, once loaded (nothing while the file has not reached this phone). */
@Composable
private fun InfoPhoto(vm: TrainingViewModel, name: String, exerciseName: String) {
    val px = with(LocalDensity.current) { 480.dp.roundToPx() }
    val photo = rememberPhoto(vm, name, px) ?: return
    Image(
        photo,
        contentDescription = stringResource(R.string.training_photo_of, exerciseName),
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().height(200.dp).clip(MaterialTheme.shapes.large),
    )
}

@Composable
private fun SettingsSection(notes: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetHeading(stringResource(R.string.training_machine_settings))
        Row(verticalAlignment = Alignment.Top) {
            Icon(TrainingIcons.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(notes, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
    }
}

/** "Pause" for the animation (material-icons-core has no Pause icon). */
private val PauseIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Pause") {
        materialPath {
            moveTo(6f, 19f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            horizontalLineTo(6f)
            close()
            moveTo(14f, 5f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            close()
        }
    }
}
