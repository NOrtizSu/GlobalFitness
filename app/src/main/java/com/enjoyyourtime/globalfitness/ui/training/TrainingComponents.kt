@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.training

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.domain.WorkoutTime
import com.enjoyyourtime.globalfitness.training.live.RestTimer
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.LoadUnit
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.WorkoutPause
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.TopBarTitle
import com.enjoyyourtime.globalfitness.ui.navigation.ReserveAppSnackbarSpace
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/**
 * The training view model of the activity: every training screen and the Hoy card share one (a
 * plain `viewModel()` inside a NavHost destination would be scoped to that destination only).
 */
@Composable
fun trainingViewModel(): TrainingViewModel {
    val owner = LocalActivity.current as? ViewModelStoreOwner ?: checkNotNull(LocalViewModelStoreOwner.current)
    return viewModel(viewModelStoreOwner = owner, factory = TrainingViewModel.Factory)
}

/**
 * Scaffold of the training sub-pages: title bar with a back arrow (tinted once content scrolls
 * under it), optional bottom bar, button and snackbar. Content gets the padding to apply with
 * [subPageContent].
 */
@Composable
fun TrainingSubPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    snackbar: SnackbarHostState? = null,
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    RestTimerEffect(trainingViewModel())
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { TopBarTitle(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        // The app-wide snackbar (a weighing saved meanwhile) appears above the rest timer or buttons.
        bottomBar = { ReserveAppSnackbarSpace(bottomBar) },
        floatingActionButton = floatingActionButton,
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        content = content,
    )
}

/** The scaffold's [padding] plus room for the keyboard (the app draws edge to edge). */
fun Modifier.subPageContent(padding: PaddingValues): Modifier =
    fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()

/**
 * Signals the end of the rest timer with two short haptic pulses, only on the screen in front and
 * while the app is open (no sound; the workout's notification only shows the countdown). A timer
 * that ended in the background, or long ago, is cleared silently; one standing still while the
 * workout is paused waits. Several screens may host this effect: the view model hands each end out
 * once.
 */
@Composable
fun RestTimerEffect(vm: TrainingViewModel) {
    val rest by vm.rest.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val timer = rest?.takeIf { !it.paused } ?: return
    LaunchedEffect(timer) {
        delay((timer.endsAt - System.currentTimeMillis()).coerceAtLeast(0))
        val late = System.currentTimeMillis() - timer.endsAt > LATE_REST_END_MS
        val appVisible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        when {
            late || !appVisible -> vm.consumeRestEnd(timer.id)
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> if (vm.consumeRestEnd(timer.id)) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                delay(REST_PULSE_GAP_MS)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            // Another screen is in front: it signals the end if it hosts this effect, else it lapses.
            else -> Unit
        }
    }
}

private const val LATE_REST_END_MS = 3_000L
private const val REST_PULSE_GAP_MS = 180L

/**
 * The exercise photo (null while loading, or when the file is missing on this phone). A missing
 * file is looked for again a few times: after a reinstall the cloud backup brings photos later.
 */
@Composable
fun rememberPhoto(vm: TrainingViewModel, name: String?, maxPx: Int): ImageBitmap? {
    val state = produceState(initialValue = name?.let { vm.cachedPhoto(it, maxPx) }, name, maxPx) {
        // A new name never shows the previous photo while it loads.
        value = name?.let { vm.cachedPhoto(it, maxPx) }
        if (name == null || value != null) return@produceState
        repeat(PHOTO_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(PHOTO_RETRY_MS)
            vm.photoBitmap(name, maxPx)?.let {
                value = it
                return@produceState
            }
        }
    }
    return state.value
}

private const val PHOTO_ATTEMPTS = 4
private const val PHOTO_RETRY_MS = 5_000L

/** Starts the camera or the system photo picker; both deliver a content [Uri]. */
class PhotoLauncher(val canTakePhoto: Boolean, val takePhoto: () -> Unit, val pickPhoto: () -> Unit)

/**
 * Camera (into the FileProvider-shared [TrainingViewModel.cameraTarget], no permission needed) and
 * the Photo Picker (no storage permission). [onPhoto] gets the image and whether it came from the
 * camera; [onUnavailable] runs when no app can take the photo.
 */
@Composable
fun rememberPhotoLauncher(
    vm: TrainingViewModel,
    onPhoto: (uri: Uri, fromCamera: Boolean) -> Unit,
    onUnavailable: () -> Unit,
): PhotoLauncher {
    val context = LocalContext.current
    val unavailable by rememberUpdatedState(onUnavailable)
    val cameraUri = remember {
        try {
            FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, vm.cameraTarget)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved && cameraUri != null) onPhoto(cameraUri, true)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPhoto(uri, false)
    }
    return remember(camera, gallery, cameraUri) {
        PhotoLauncher(
            canTakePhoto = cameraUri != null,
            takePhoto = {
                try {
                    if (cameraUri != null) {
                        // The system may have cleared the cache folder the camera writes into.
                        vm.cameraTarget.parentFile?.mkdirs()
                        camera.launch(cameraUri)
                    }
                } catch (e: ActivityNotFoundException) {
                    unavailable()
                } catch (e: SecurityException) {
                    // Thrown when the app declares the CAMERA permission (e.g. merged from a library) without holding it.
                    unavailable()
                }
            },
            pickPhoto = {
                try {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (e: ActivityNotFoundException) {
                    unavailable()
                }
            },
        )
    }
}

private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

/** "Take photo", "Choose from gallery" and, with a photo, "Remove photo". */
@Composable
fun PhotoMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    launcher: PhotoLauncher,
    hasPhoto: Boolean,
    onRemove: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (launcher.canTakePhoto) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.training_photo_take)) },
                leadingIcon = { Icon(TrainingIcons.PhotoCamera, contentDescription = null) },
                onClick = {
                    onDismiss()
                    launcher.takePhoto()
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.training_photo_pick)) },
            leadingIcon = { Icon(TrainingIcons.Image, contentDescription = null) },
            onClick = {
                onDismiss()
                launcher.pickPhoto()
            },
        )
        if (hasPhoto) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.training_photo_remove)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onRemove()
                },
            )
        }
    }
}

/** Muscles as small labels (not buttons): primary ones filled, secondary ones outlined. */
@Composable
fun MuscleChips(primary: Collection<Muscle>, secondary: Collection<Muscle>, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        primary.sortedBy { it.ordinal }.forEach { m ->
            MuscleLabel(m.label(), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, border = null)
        }
        secondary.sortedBy { it.ordinal }.forEach { m ->
            MuscleLabel(m.label(), Color.Transparent, MaterialTheme.colorScheme.onSurfaceVariant, BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

@Composable
private fun MuscleLabel(text: String, container: Color, content: Color, border: BorderStroke?) {
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content, border = border) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/**
 * Live time trained since [startedAt] ("32:15", "1:02:03"), ticking every second; [pauses] do not
 * count, so it stands still while the workout is paused.
 */
@Composable
fun ElapsedText(
    vm: TrainingViewModel,
    startedAt: Long,
    pauses: List<WorkoutPause>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val now by vm.clock.collectAsStateWithLifecycle()
    val ms = WorkoutTime.activeMs(startedAt, maxOf(now, startedAt), pauses)
    val description = stringResource(R.string.training_elapsed_desc, durationText(ms))
    Text(
        TrainingFormat.duration((ms / 1000).toInt(), Fmt.locale),
        style = style.merge(TabularNumbers),
        color = color,
        maxLines = maxLines,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    )
}

/** Seconds of rest left, rounded up (0 once it ended); it stands still while the workout is paused. */
fun restRemainingSec(rest: RestTimer, now: Long): Int = (rest.leftMs(now) + 999).div(1000).toInt()

/** "Paused", next to the clock of a paused workout. */
@Composable
fun PausedLabel(color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(TrainingIcons.Pause, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.workout_paused), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** Pause or resume the workout in progress (its rest timer too): one tonal button, at least 48 dp high. */
@Composable
fun PauseResumeButton(paused: Boolean, onPause: () -> Unit, onResume: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = if (paused) onResume else onPause, modifier = modifier.heightIn(min = 48.dp)) {
        Icon(if (paused) Icons.Filled.PlayArrow else TrainingIcons.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (paused) R.string.workout_resume else R.string.workout_pause))
    }
}

/**
 * The buttons of the in-progress cards (Hoy, Entreno). Running: "Continue" opens the workout and
 * "Pause" pauses it. Paused: "Resume" (the filled one) and "View workout", which only opens it.
 * Each with its own icon, so the two that both look like "go on" are never side by side.
 */
@Composable
fun ActiveWorkoutButtons(paused: Boolean, onOpen: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (paused) {
            Button(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.workout_resume))
            }
            TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.training_view_workout))
            }
        } else {
            Button(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_continue))
            }
            FilledTonalButton(onClick = onPause, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(TrainingIcons.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.workout_pause))
            }
        }
    }
}

/** "Rest 1:12" for cards; "Rest paused · 1:12" while the workout is paused (the countdown stands still). */
@Composable
fun RestLine(vm: TrainingViewModel, rest: RestTimer, color: Color) {
    val now by vm.clock.collectAsStateWithLifecycle()
    val left = restRemainingSec(rest, now)
    if (left <= 0) return
    val time = TrainingFormat.duration(left, Fmt.locale)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (rest.paused) TrainingIcons.Pause else TrainingIcons.Timer, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (rest.paused) stringResource(R.string.workout_rest_paused) + " · " + time else stringResource(R.string.training_rest_value, time),
            style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
            color = color,
        )
    }
}

/**
 * A row of a list whose consecutive rows read as one rounded block (2 dp apart), like the body
 * history. [onLongClick] opens the row's actions.
 */
@Composable
fun GroupedRow(
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val outer = 20.dp
    val inner = 4.dp
    val shape = RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else 2.dp),
    ) {
        Row(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClickLabel)
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

// ---- texts -----------------------------------------------------------------------------------

/** The workout's name, else its main muscles, else its first exercise, else "Workout". */
@Composable
fun workoutTitle(summary: WorkoutSummary, exercises: Map<String, Exercise>): String = workoutTitle(LocalContext.current, summary, exercises)

/** [workoutTitle] outside a composition (the workout's notification). */
fun workoutTitle(context: Context, summary: WorkoutSummary, exercises: Map<String, Exercise>): String {
    summary.name?.let { return it }
    if (summary.muscles.isNotEmpty()) return summary.muscles.take(2).joinToString(" · ") { context.getString(it.labelRes) }
    val first = summary.exerciseIds.firstOrNull()?.let { exercises[it] }
    return first?.name?.resolve(context) ?: context.getString(R.string.training_workout_default)
}

/** "45 min", "1 h 05 min". */
fun durationText(ms: Long): String {
    val minutes = (ms / 60_000L).toInt().coerceAtLeast(0)
    val h = minutes / 60
    val m = minutes % 60
    return if (h == 0) {
        Fmt.string(R.string.value_minutes, Fmt.count(m.toLong()))
    } else {
        Fmt.string(R.string.value_hours_minutes, Fmt.count(h.toLong()), TrainingFormat.twoDigits(m, Fmt.locale))
    }
}

/** A number already formatted, with the load unit ("80 kg", "185 lb"). */
fun loadWithUnit(value: String, unit: LoadUnit): String = when (unit) {
    LoadUnit.KG -> Fmt.string(R.string.value_kg, value)
    LoadUnit.LB -> Fmt.string(R.string.training_value_lb, value)
}

fun unitLabel(unit: LoadUnit): String = Fmt.string(if (unit == LoadUnit.KG) R.string.unit_kg else R.string.unit_lb)

/** A load stored in kg, in the user's unit with it ("82,5 kg"). */
fun loadText(kg: Double, unit: LoadUnit): String = loadWithUnit(TrainingFormat.load(kg, unit, Fmt.locale), unit)

/** An estimated 1RM in the user's unit, at most one decimal ("72,5 kg"). */
fun estimateText(kg: Double, unit: LoadUnit): String = loadWithUnit(TrainingFormat.estimate(kg, unit, Fmt.locale), unit)

/** Total volume in the user's unit, whole ("8420 kg"). */
fun volumeText(kg: Double, unit: LoadUnit): String = loadWithUnit(Fmt.count(unit.fromKg(kg).roundToLong()), unit)

fun kmText(value: String): String = Fmt.string(R.string.value_km, value)

/** "Today", "Yesterday", "3 days ago" (within a week), else "15 Oct". */
fun daysAgoText(ms: Long, today: LocalDate = LocalDate.now()): String {
    val day = TrainingSummaries.dayOf(ms, ZoneId.systemDefault())
    val days = ChronoUnit.DAYS.between(day, today)
    return when {
        days <= 1 -> Fmt.relativeDay(day, today)
        days < 7 -> Fmt.plural(R.plurals.training_days_ago, days.toInt(), Fmt.count(days))
        else -> Fmt.dayMonth(day)
    }
}

/** "3 exercises · 12 sets". */
fun exercisesAndSets(exercises: Int, sets: Int): String =
    Fmt.plural(R.plurals.training_exercises_count, exercises, Fmt.count(exercises.toLong())) + " · " +
        Fmt.plural(R.plurals.training_sets_count, sets, Fmt.count(sets.toLong()))
