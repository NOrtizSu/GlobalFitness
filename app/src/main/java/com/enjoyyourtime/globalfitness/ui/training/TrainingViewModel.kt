package com.enjoyyourtime.globalfitness.ui.training

import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.enjoyyourtime.globalfitness.AppContainer
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.ble.LiveScale
import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.Sex
import com.enjoyyourtime.globalfitness.training.data.CelebrationTracker
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.data.WorkoutEdits
import com.enjoyyourtime.globalfitness.training.domain.AchievementBoard
import com.enjoyyourtime.globalfitness.training.domain.AchievementInput
import com.enjoyyourtime.globalfitness.training.domain.Achievements
import com.enjoyyourtime.globalfitness.training.domain.BodySample
import com.enjoyyourtime.globalfitness.training.domain.BodyweightTimeline
import com.enjoyyourtime.globalfitness.training.domain.Celebration
import com.enjoyyourtime.globalfitness.training.domain.ExerciseHistory
import com.enjoyyourtime.globalfitness.training.domain.ExerciseSession
import com.enjoyyourtime.globalfitness.training.domain.GoalAnchor
import com.enjoyyourtime.globalfitness.training.domain.LoadModel
import com.enjoyyourtime.globalfitness.training.domain.PersonalRecords
import com.enjoyyourtime.globalfitness.training.domain.RecordKind
import com.enjoyyourtime.globalfitness.training.model.CustomExercise
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.ExerciseNote
import com.enjoyyourtime.globalfitness.training.model.LoadUnit
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Routine
import com.enjoyyourtime.globalfitness.training.model.RoutineExercise
import com.enjoyyourtime.globalfitness.training.model.RoutineTemplate
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import com.enjoyyourtime.globalfitness.ui.compositionOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

/** A value that is still loading, does not exist (deleted, bad link) or is ready. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    data object Missing : Loadable<Nothing>

    data class Ready<T>(val value: T) : Loadable<T>
}

/** What the Entreno tab and the Hoy card show. */
data class TrainingOverview(
    val loaded: Boolean,
    val active: WorkoutSummary? = null,
    /** Today's last finished workout. */
    val today: WorkoutSummary? = null,
    /** The last finished workout. */
    val last: WorkoutSummary? = null,
    /** Finished workouts, newest first. */
    val recent: List<WorkoutSummary> = emptyList(),
    /** Past workouts offered for "Repetir". */
    val templates: List<WorkoutSummary> = emptyList(),
    val week: WeekSummary? = null,
    val hasWorkouts: Boolean = false,
)

/** One exercise of the open workout with what its set table needs. */
data class EntryUi(
    val entry: WorkoutEntry,
    val exercise: Exercise,
    /** The set of last time that each set corresponds to, by set id. */
    val previous: Map<String, WorkoutSet>,
    /** Records beaten by the sets of this entry, by set id. */
    val records: Map<String, Set<RecordKind>>,
)

data class WorkoutUi(
    val workout: Workout,
    val entries: List<EntryUi>,
    val workingSets: Int,
    val volumeKg: Double,
    /** Sets with values that are not ticked (asked about when finishing). */
    val unfinishedFilled: Int,
)

data class ExerciseDetail(
    val exercise: Exercise,
    /** Oldest first. */
    val sessions: List<ExerciseSession>,
    val records: PersonalRecords,
    /** Whether any workout or routine uses it (a custom one is then archived instead of deleted). */
    val used: Boolean,
    /** Only the user's routines use it, no workout (the archive dialog names them). */
    val usedByRoutinesOnly: Boolean,
    /** Its strength depends on the body weight and the user never weighed in. */
    val needsBodyweight: Boolean,
)

/** Shown when a workout is finished. */
data class FinishSummary(
    val workout: WorkoutSummary,
    val records: Int,
    val recordExerciseIds: List<String>,
    val muscles: Map<Muscle, Double>,
)

/** How finishing a workout ended; kept by the view model until its page shows it (survives rotation). */
sealed interface FinishOutcome {
    data class Finished(val summary: FinishSummary) : FinishOutcome

    /** Nothing was completed: the workout was deleted. */
    data object Deleted : FinishOutcome
}

/** An exercise taken out of a workout, with where it was, for "Undo". */
data class RemovedEntry(val workoutId: String, val index: Int, val entry: WorkoutEntry)

/** A set deleted from an exercise, with where it was, for "Undo". */
data class RemovedSet(val workoutId: String, val entryId: String, val index: Int, val set: WorkoutSet)

/** The rest countdown after a completed set. Times are wall-clock epoch ms. */
data class RestTimer(val id: Long, val workoutId: String, val endsAt: Long, val totalSec: Int)

sealed interface TrainingEvent {
    /** A set just beat a personal record. */
    data class Record(val exerciseId: String, val set: WorkoutSet, val kinds: Set<RecordKind>) : TrainingEvent

    /** A picked or captured photo could not be read. */
    data object PhotoFailed : TrainingEvent
}

/** What to do with filled-in sets that were not ticked when a workout is finished or left. */
enum class UnfinishedSets { COMPLETE, DISCARD }

/** Body samples with the measurement list they were derived from (to tell whether they are current). */
private class BodySamples(val source: List<Measurement>, val samples: List<BodySample>)

/** An achievement board, and whether it came from fully loaded, current data (only those are celebrated). */
private class Evaluation(val board: AchievementBoard, val complete: Boolean)

/** The editor's fields for a custom exercise or machine ([id] null: a new one). */
data class ExerciseDraft(
    val id: String?,
    val name: String,
    val kind: ExerciseKind,
    val equipment: Equipment,
    val primary: Set<Muscle>,
    val secondary: Set<Muscle>,
    /** Machine settings; null when the editor did not change them (the detail page may have). */
    val notes: String?,
    /** Photo file name; [photoChanged] tells whether it replaces the stored one. */
    val photo: String?,
    val photoChanged: Boolean,
)

/**
 * Activity-scoped state of every training screen and the Hoy card (get it with
 * [trainingViewModel]). Derived lists are computed on [Dispatchers.Default]; writes go through
 * [TrainingRepository] and the pure edits of [WorkoutEdits] / [WorkoutUiEdits]; photo files and
 * bitmaps are read and written on [Dispatchers.IO].
 */
class TrainingViewModel(container: AppContainer) : ViewModel() {
    private val repo = container.training.repository
    private val settings = container.training.settings
    private val photos = container.training.photos
    private val appScope = container.appScope

    val loaded: StateFlow<Boolean> = repo.loaded
    val loadUnit: StateFlow<LoadUnit> = settings.loadUnit
    val restSeconds: StateFlow<Int> = settings.restSeconds
    val exercises: StateFlow<Map<String, Exercise>> = repo.exercises

    /** The body map draws the profile's figure. */
    val female: StateFlow<Boolean> = container.repository.profile
        .map { it?.sex == Sex.FEMALE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Wall clock, every second while a screen shows elapsed or remaining times. */
    val clock: StateFlow<Long> = ticker(1_000L).stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), now())

    /** The local date, again at every local midnight (the eager achievements follow it in the background too). */
    private val today: Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now())
            delay(untilTomorrow())
        }
    }.distinctUntilChanged()

    /**
     * The weight each set really moves (body weight from the scale and manual weighings, minus
     * assistance, plus extra load): every statistic of the training screens goes through it.
     */
    val loadModel: StateFlow<LoadModel> = container.repository.measurements
        .map { LoadModel(BodyweightTimeline.fromMeasurements(it)) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, LoadModel.WITHOUT_BODYWEIGHT)

    val overview: StateFlow<TrainingOverview> =
        combine(repo.loaded, repo.workouts, repo.exercises, today, loadModel) { loaded, workouts, exercises, day, load ->
            if (loaded) buildOverview(workouts, exercises, day, load) else TrainingOverview(loaded = false)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrainingOverview(loaded = false))

    /** Every workout the lists show, newest first (null while loading). */
    val history: StateFlow<List<WorkoutSummary>?> =
        combine(repo.loaded, repo.workouts, repo.exercises, loadModel) { loaded, workouts, exercises, load ->
            if (!loaded) {
                null
            } else {
                val lookup = lookup(exercises)
                workouts.asReversed().filter(TrainingSummaries::isVisible).map { TrainingSummaries.summary(it, lookup, load) }
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Exercises by how recently they were done, for the picker. */
    val recentExerciseIds: StateFlow<List<String>> =
        repo.workouts.map { ExerciseHistory.recentExerciseIds(it, RECENT_EXERCISES) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Every routine with what its cards show: the user's first, then the built-in ones, the last one
     * used leading its group ([RoutineStats.ordered]). Eager: the Entreno tab, the Hoy card and the
     * workout's routine sheet show it without waiting.
     */
    val routines: StateFlow<List<RoutineSummary>> =
        combine(repo.routineTemplates, repo.exercises, settings.lastRoutineId) { templates, exercises, last ->
            val lookup = lookup(exercises)
            RoutineStats.ordered(templates, last).map { RoutineStats.summary(it, lookup) }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The routine a workout was last started from or filled with, while it exists (the Hoy card offers it). */
    val lastRoutine: StateFlow<RoutineSummary?> =
        combine(routines, settings.lastRoutineId) { list, id -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val starting = Mutex()

    private val _rest = MutableStateFlow<RestTimer?>(null)
    val rest: StateFlow<RestTimer?> = _rest.asStateFlow()
    private var restSeq = 0L

    private val _events = MutableSharedFlow<TrainingEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<TrainingEvent> = _events.asSharedFlow()

    private val bitmaps = object : LruCache<String, ImageBitmap>(BITMAP_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /** Where the camera writes a new photo (shared through the FileProvider). */
    val cameraTarget get() = photos.cameraTarget

    private val _finishes = MutableStateFlow<Map<String, FinishOutcome>>(emptyMap())
    private val editorSaves = EditorSaves()
    private val routineSaves = EditorSaves()

    // ---- achievements ----------------------------------------------------------------------

    /** Weighings with their composition estimate, for the body goals and the lifts' body weight. */
    private val bodySamples: Flow<BodySamples?> =
        combine(container.repository.profile, container.repository.measurements) { profile, measurements ->
            profile?.let { p ->
                // Read after the measurements, so it covers them.
                val imported = container.repository.importedIds.value
                BodySamples(
                    source = measurements,
                    samples = measurements.map { m ->
                        p.compositionOf(m).let { c -> BodySample(m.timestamp, m.weightKg, c.fatPercent, c.muscleKg, imported = m.id in imported) }
                    },
                )
            }
        }.flowOn(Dispatchers.Default)

    private val allLoaded: Flow<Boolean> = combine(repo.loaded, container.repository.loaded) { a, b -> a && b }

    private val ledger = container.training.celebrations

    /**
     * Every achievement, derived from the workouts and weighings (null until both are loaded and the
     * profile exists), and whether the board may be celebrated. The flows combined only say that
     * something changed: they are collected separately, so the data is read here, together. The body
     * samples are derived on another dispatcher: a board made before they caught up with the
     * measurements is shown but not celebrated (at start they arrive empty first).
     */
    private val evaluation: StateFlow<Evaluation?> =
        combine(allLoaded, repo.workouts, repo.exercises, bodySamples, today) { loaded, _, exercises, body, _ ->
            val profile = container.repository.profile.value
            if (!loaded || body == null || profile == null) return@combine null
            val workouts = repo.workouts.value
            val remote = repo.remoteVersions.value
            val now = now()
            val stored = ledger.goalAnchor()
            val anchor = GoalAnchor.next(stored, profile.targetWeightKg, now)
            if (anchor != null && anchor != stored) ledger.setGoalAnchor(anchor)
            val board = Achievements.evaluate(
                AchievementInput(
                    workouts = workouts,
                    exercise = lookup(exercises),
                    body = body.samples,
                    sex = profile.sex,
                    ageYears = profile.ageAt(now),
                    targetWeightKg = profile.targetWeightKg,
                    zone = ZoneId.systemDefault(),
                    firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
                    now = now,
                    targetSetAt = anchor?.setAt,
                    remoteVersions = remote,
                ),
            )
            Evaluation(board, complete = body.source === container.repository.measurements.value)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Every achievement (null until the data is loaded and the profile exists). Eager: new levels are celebrated wherever the user is. */
    val achievements: StateFlow<AchievementBoard?> =
        evaluation.map { it?.board }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val celebrations = CelebrationTracker(ledger)

    /** Reasons the celebration waits (typing in a workout, its summary on screen, finishing). */
    private val celebrationHolds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The workout in progress holds every celebration until it is finished: its sets can still be
     * corrected (a typo must not leave a medal), and the levels it reaches wait for its summary.
     */
    private val workoutInProgress: Flow<Boolean> = repo.activeWorkout.map { it != null }.distinctUntilChanged()

    /** A weighing on the scale, and its result while it shows: no body level covers it. */
    private val weighing: Flow<Boolean> = container.scaleMonitor.live.map { it != LiveScale.Idle }.distinctUntilChanged()

    /**
     * The levels to celebrate now: empty while anything holds them. They appear once nothing has held
     * them for a moment, so a screen that takes over (the finish summary) can claim the hold first.
     */
    @OptIn(FlowPreview::class)
    val celebration: StateFlow<List<Celebration>> =
        combine(celebrations.pending, celebrationHolds, workoutInProgress, weighing) { pending, holds, training, weighing ->
            if (holds.isEmpty() && !training && !weighing) pending else emptyList()
        }
            .debounce { if (it.isEmpty()) 0L else CELEBRATION_SETTLE_MS }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Levels waiting to be celebrated, on screen or held (e.g. behind a workout's summary): ads and
     * the rating card stay away meanwhile, so they never open just before the celebration.
     */
    val celebrationWaiting: StateFlow<Boolean> =
        celebrations.pending.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Keeps the celebrations waiting while [hold] is true for [reason] (see [celebration]). */
    fun holdCelebrations(reason: String, hold: Boolean) = celebrationHolds.update { if (hold) it + reason else it - reason }

    /** The overlay shows [shown]: those levels are never celebrated again. */
    fun onCelebrationShown(shown: List<Celebration>) = celebrations.shown(shown)

    fun dismissCelebration(shown: List<Celebration>) = celebrations.dismiss(shown)

    /** Boards of fully loaded data go to the celebrations once they settle (a burst of changes, a restore, gives one). */
    @OptIn(FlowPreview::class)
    private suspend fun feedCelebrations() =
        evaluation.filterNotNull().filter { it.complete }.map { it.board }.debounce(BOARD_SETTLE_MS).collect(celebrations::onBoard)

    init {
        viewModelScope.launch { feedCelebrations() }
        viewModelScope.launch { cleanUp() }
        // The body figure is parsed in the background before the first body map needs it; its shaped
        // outlines too for people who train (Hoy's weekly card shapes the figure from the start).
        viewModelScope.launch {
            val female = container.repository.profile.filterNotNull().first().sex == Sex.FEMALE
            preloadBodyMap(female)
            repo.loaded.first { it }
            if (repo.workouts.value.isNotEmpty()) preloadShapedBodyMap(female)
        }
    }

    /**
     * Once the data is loaded: deletes workouts logged for another day that never got an exercise,
     * and photo files no note refers to (a photo picked in an editor that was never saved, e.g. after
     * a process death). Recent files are kept: the cloud backup may be restoring their notes.
     */
    private suspend fun cleanUp() {
        repo.awaitLoaded()
        TrainingSummaries.abandonedLogs(repo.workouts.value, now(), ABANDONED_LOG_MS).forEach { repo.deleteWorkout(it.id) }
        val used = repo.notes.value.values.mapNotNullTo(HashSet()) { it.photo }
        withContext(Dispatchers.IO) { photos.deleteUnused(used, UNUSED_PHOTO_GRACE_MS) }
    }

    // ---- derived screens -------------------------------------------------------------------

    fun workout(id: String): Flow<Loadable<WorkoutUi>> =
        combine(repo.loaded, repo.workouts, repo.exercises, loadModel) { loaded, workouts, exercises, load ->
            if (!loaded) return@combine Loadable.Loading
            val w = workouts.firstOrNull { it.id == id } ?: return@combine Loadable.Missing
            Loadable.Ready(buildWorkout(workouts, w, lookup(exercises), load))
        }.flowOn(Dispatchers.Default)

    fun exerciseDetail(id: String): Flow<Loadable<ExerciseDetail>> =
        combine(repo.loaded, repo.workouts, repo.routines, repo.exercises, loadModel) { loaded, workouts, routines, exercises, load ->
            if (!loaded) return@combine Loadable.Loading
            val known = exercises[id]
            val inWorkouts = workouts.any { w -> w.entries.any { it.exerciseId == id } }
            if (known == null && !inWorkouts) return@combine Loadable.Missing
            val inRoutines = routines.any { r -> r.exercises.any { it.exerciseId == id } }
            val exercise = known ?: TrainingRepository.unknownExercise(id)
            val sessions = ExerciseHistory.sessions(workouts, id, ZoneId.systemDefault(), exercise, load)
            Loadable.Ready(
                ExerciseDetail(
                    exercise = exercise,
                    sessions = sessions,
                    records = ExerciseHistory.records(sessions),
                    used = inWorkouts || inRoutines,
                    usedByRoutinesOnly = inRoutines && !inWorkouts,
                    needsBodyweight = load.needsBodyweight(exercise),
                ),
            )
        }.flowOn(Dispatchers.Default)

    fun routine(id: String): Flow<Loadable<RoutineDetail>> =
        combine(repo.loaded, repo.routineTemplates, repo.exercises, repo.activeWorkout.map { it?.id }.distinctUntilChanged()) { loaded, templates, exercises, active ->
            if (!loaded) return@combine Loadable.Loading
            val t = templates.firstOrNull { it.id == id } ?: return@combine Loadable.Missing
            val lookup = lookup(exercises)
            Loadable.Ready(
                RoutineDetail(
                    template = t,
                    items = t.exercises.map { RoutineItemUi(it, lookup(it.exerciseId)) },
                    sets = t.exercises.sumOf { it.sets },
                    muscles = RoutineStats.muscles(t.exercises, lookup),
                    activeWorkoutId = active,
                ),
            )
        }.flowOn(Dispatchers.Default)

    /** The photo if it is already decoded at this size (no IO). */
    fun cachedPhoto(name: String, maxPx: Int): ImageBitmap? = bitmaps.get("$name@$maxPx")

    /** A photo no larger than about [maxPx] (null when the file is missing or unreadable). */
    suspend fun photoBitmap(name: String, maxPx: Int): ImageBitmap? {
        val key = "$name@$maxPx"
        bitmaps.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            try {
                photos.loadBitmap(name, maxPx)?.asImageBitmap()
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }
        if (bitmap != null) bitmaps.put(key, bitmap)
        return bitmap
    }

    // ---- workouts --------------------------------------------------------------------------

    /** Opens the workout in progress, or starts an empty one now. */
    fun startWorkout(onStarted: (String) -> Unit) = launch {
        // Serialized: a double tap must open one workout, not start two.
        val id = starting.withLock { (repo.activeWorkout.value ?: repo.startWorkout()).id }
        onStarted(id)
    }

    /** Starts a workout with [templateId]'s exercises and sets as planned sets (or opens the live one). */
    fun repeatWorkout(templateId: String, onStarted: (String) -> Unit) = launch {
        val id = starting.withLock {
            repo.activeWorkout.value?.id ?: repo.workout(templateId)?.let { repo.startWorkout(template = it).id }
        } ?: return@launch
        onStarted(id)
    }

    /**
     * Logs a workout done on [date] (at the current time of day, never in the future). It is
     * created finished, so it never becomes the workout "in progress".
     */
    fun logPastWorkout(date: LocalDate, onStarted: (String) -> Unit) = launch {
        val zone = ZoneId.systemDefault()
        val now = now()
        var start = date.atTime(LocalTime.now()).atZone(zone).toInstant().toEpochMilli()
        var end = start + PAST_WORKOUT_MS
        if (end > now) {
            end = now
            start = maxOf(now - PAST_WORKOUT_MS, date.atStartOfDay(zone).toInstant().toEpochMilli())
        }
        val id = starting.withLock { repo.logFinishedWorkout(start, end).id }
        onStarted(id)
    }

    /** Adds exercises at the end, each pre-filled with its sets of last time. */
    fun addExercises(workoutId: String, exerciseIds: List<String>) = edit(workoutId) { w ->
        exerciseIds.fold(w) { acc, id ->
            WorkoutEdits.addExercise(acc, id, ExerciseHistory.previousSets(repo.workouts.value, id, workoutId), ::newId)
        }
    }

    fun updateSet(workoutId: String, entryId: String, setId: String, transform: (WorkoutSet) -> WorkoutSet) =
        edit(workoutId) { WorkoutEdits.updateSet(it, entryId, setId, transform) }

    /**
     * Ticks or unticks a set. A set ticked without its defining value (reps, time) takes last
     * time's values for the fields still empty; in a live workout the rest timer starts; a set
     * beating a personal record sends [TrainingEvent.Record].
     */
    fun setDone(workoutId: String, entryId: String, setId: String, done: Boolean) = launch {
        val updated = repo.updateWorkout(workoutId) { w ->
            val entry = w.entries.firstOrNull { it.id == entryId } ?: return@updateWorkout w
            val set = entry.sets.firstOrNull { it.id == setId } ?: return@updateWorkout w
            val filled = if (done && !SetTable.hasValues(set, kindOf(entry.exerciseId))) {
                val previous = ExerciseHistory.previousSets(repo.workouts.value, entry.exerciseId, w.id)
                SetTable.previousFor(entry.sets, previous)[setId]?.let { SetTable.fillMissing(set, it) } ?: set
            } else {
                set
            }
            WorkoutEdits.updateSet(w, entryId, setId) { filled.copy(done = done, doneAt = if (done) WorkoutUiEdits.doneAtFor(w, now()) else null) }
        } ?: return@launch
        if (!done) return@launch
        if (updated.inProgress) startRest(workoutId)
        val entry = updated.entries.firstOrNull { it.id == entryId } ?: return@launch
        val set = entry.sets.firstOrNull { it.id == setId } ?: return@launch
        val workouts = repo.workouts.value
        val lookup = lookup(repo.exercises.value)
        val load = loadModel.value
        val records = withContext(Dispatchers.Default) { WorkoutRecords.forWorkout(workouts, updated, lookup, load)[setId] }
        if (!records.isNullOrEmpty()) _events.tryEmit(TrainingEvent.Record(entry.exerciseId, set, records))
    }

    fun addSet(workoutId: String, entryId: String) = edit(workoutId) { WorkoutEdits.addSet(it, entryId, ::newId) }

    fun addWarmup(workoutId: String, entryId: String) = edit(workoutId) { WorkoutUiEdits.addWarmup(it, entryId, ::newId) }

    fun setWarmup(workoutId: String, entryId: String, setId: String, warmup: Boolean) =
        edit(workoutId) { WorkoutUiEdits.setWarmup(it, entryId, setId, warmup) }

    /** Deletes a set; [onRemoved] gets it as it was (latest values included) for "Undo". */
    fun removeSet(workoutId: String, entryId: String, setId: String, onRemoved: (RemovedSet) -> Unit) = launch {
        var removed: RemovedSet? = null
        repo.updateWorkout(workoutId) { w ->
            val sets = w.entries.firstOrNull { it.id == entryId }?.sets.orEmpty()
            val index = sets.indexOfFirst { it.id == setId }
            if (index >= 0) removed = RemovedSet(workoutId, entryId, index, sets[index])
            WorkoutEdits.removeSet(w, entryId, setId)
        }
        removed?.let(onRemoved)
    }

    fun restoreSet(removed: RemovedSet) = edit(removed.workoutId) { WorkoutUiEdits.insertSet(it, removed.entryId, removed.set, removed.index) }

    /** Moves an exercise [delta] places up (negative) or down. */
    fun moveEntry(workoutId: String, entryId: String, delta: Int) = edit(workoutId) { WorkoutUiEdits.moveEntry(it, entryId, delta) }

    /** Takes an exercise out of the workout; [onRemoved] gets it as it was for "Undo". */
    fun removeEntry(workoutId: String, entryId: String, onRemoved: (RemovedEntry) -> Unit) = launch {
        var removed: RemovedEntry? = null
        repo.updateWorkout(workoutId) { w ->
            val index = w.entries.indexOfFirst { it.id == entryId }
            if (index >= 0) removed = RemovedEntry(workoutId, index, w.entries[index])
            WorkoutEdits.removeEntry(w, entryId)
        }
        removed?.let(onRemoved)
    }

    fun restoreEntry(removed: RemovedEntry) = edit(removed.workoutId) { WorkoutUiEdits.insertEntry(it, removed.entry, removed.index) }

    fun setEntryNote(workoutId: String, entryId: String, note: String) =
        edit(workoutId) { w -> WorkoutEdits.updateEntry(w, entryId) { it.copy(note = note.trim().ifEmpty { null }) } }

    /** Kept as typed (trimmed when the workout is finished) so spaces can be typed between words. */
    fun rename(workoutId: String, name: String) = edit(workoutId) { it.copy(name = name.takeIf { n -> n.isNotBlank() }) }

    fun setWorkoutNote(workoutId: String, note: String) = edit(workoutId) { it.copy(note = note.takeIf { n -> n.isNotBlank() }) }

    /** Moves the start; a finished workout keeps its duration, but never ends in the future. */
    fun setStart(workoutId: String, start: Long) = edit(workoutId) { w ->
        val now = now()
        val clamped = start.coerceAtMost(now)
        WorkoutUiEdits.retime(w, clamped, w.endedAt?.let { (clamped + (it - w.startedAt)).coerceAtMost(now) })
    }

    /** Duration of a finished workout (it never ends in the future). */
    fun setDuration(workoutId: String, minutes: Int) = edit(workoutId) { w ->
        if (w.inProgress) w else WorkoutUiEdits.retime(w, w.startedAt, (w.startedAt + minutes.coerceAtLeast(1) * 60_000L).coerceAtMost(now()))
    }

    /**
     * Finishes a workout in progress: filled-in sets that were not ticked are completed or dropped
     * as chosen, empty planned sets are dropped, and a workout without any completed set is deleted.
     * The [FinishOutcome] waits in [finishOutcome] until the page consumes it.
     */
    fun finish(workoutId: String, unfinished: UnfinishedSets?) = launch {
        // The levels this workout reaches wait for its summary (which holds them while it shows).
        holdCelebrations(FINISHING, true)
        try {
            val result = close(workoutId, unfinished)
            val outcome = result?.let { w -> FinishOutcome.Finished(withContext(Dispatchers.Default) { summarize(w) }) } ?: FinishOutcome.Deleted
            _finishes.update { it + (workoutId to outcome) }
        } finally {
            holdCelebrations(FINISHING, false)
        }
    }

    fun finishOutcome(workoutId: String): Flow<FinishOutcome?> = _finishes.map { it[workoutId] }.distinctUntilChanged()

    fun consumeFinish(workoutId: String) {
        _finishes.update { it - workoutId }
    }

    /** Leaves a finished workout (see [finish]); nothing is shown afterwards. */
    fun closeFinished(workoutId: String, unfinished: UnfinishedSets?) = launch { close(workoutId, unfinished) }

    private suspend fun close(workoutId: String, unfinished: UnfinishedSets?): Workout? {
        val current = repo.workout(workoutId) ?: return null
        if (_rest.value?.workoutId == workoutId) _rest.value = null
        repo.updateWorkout(workoutId) { w ->
            val completed = if (unfinished == UnfinishedSets.COMPLETE) WorkoutUiEdits.completeFilled(w, ::kindOf, now()) else w
            WorkoutUiEdits.dropUnfinished(completed).copy(
                name = completed.name?.trim()?.ifEmpty { null },
                note = completed.note?.trim()?.ifEmpty { null },
            )
        }
        if (current.inProgress) return repo.finishWorkout(workoutId)
        val saved = repo.workout(workoutId) ?: return null
        if (saved.doneSets > 0) return saved
        repo.deleteWorkout(workoutId)
        return null
    }

    fun deleteWorkout(workoutId: String) = launch {
        if (_rest.value?.workoutId == workoutId) _rest.value = null
        repo.deleteWorkout(workoutId)
    }

    // ---- rest timer ------------------------------------------------------------------------

    private fun startRest(workoutId: String) {
        val seconds = settings.restSeconds.value
        if (seconds <= 0) return
        _rest.value = RestTimer(++restSeq, workoutId, now() + seconds * 1000L, seconds)
    }

    /** Adds (or with a negative [deltaSec] removes) rest time; the timer ends when none is left. */
    fun adjustRest(deltaSec: Int) {
        _rest.update { r ->
            r ?: return@update null
            val end = r.endsAt + deltaSec * 1000L
            if (end <= now()) null else r.copy(endsAt = end, totalSec = (r.totalSec + deltaSec).coerceAtLeast(1))
        }
    }

    fun skipRest() {
        _rest.value = null
    }

    /** Clears the ended timer [id]; true only for the first caller, which signals the end. */
    fun consumeRestEnd(id: Long): Boolean {
        val r = _rest.value ?: return false
        return r.id == id && _rest.compareAndSet(r, null)
    }

    // ---- routines --------------------------------------------------------------------------

    /** A built-in or user routine as stored right now. */
    fun routineTemplate(id: String): RoutineTemplate? = repo.routineTemplates.value.firstOrNull { it.id == id }

    /** One of the user's routines (null for built-in ones and deleted ones). */
    fun userRoutine(id: String): Routine? = repo.routine(id)

    /**
     * Starts a workout from the routine, named [name] (the routine's, in the user's language), or
     * opens the one in progress; remembers the routine as the last one used.
     */
    fun startRoutine(routineId: String, name: String, onStarted: (String) -> Unit) = launch {
        // Serialized with the other starts: a double tap opens one workout.
        val id = starting.withLock {
            repo.activeWorkout.value?.id ?: routineTemplate(routineId)?.let { t ->
                settings.setLastRoutine(t.id)
                repo.startFromRoutine(t, name = name).id
            }
        } ?: return@launch
        onStarted(id)
    }

    /**
     * Adds the routine's exercises to a workout in one step (the ones it already has are skipped).
     * [onDone] gets the entries added, for "Undo"; none when the workout already had them all.
     */
    fun addRoutine(workoutId: String, routineId: String, onDone: (List<String>) -> Unit) = launch {
        val t = routineTemplate(routineId) ?: return@launch
        val added = repo.addRoutineToWorkout(workoutId, t) ?: return@launch
        if (added.isNotEmpty()) settings.setLastRoutine(t.id)
        onDone(added)
    }

    fun undoAddRoutine(workoutId: String, entryIds: List<String>) = edit(workoutId) { RoutineEdits.removeAdded(it, entryIds) }

    /** Saves the workout's exercises as a new routine; [onSaved] gets its id (null: the workout has no exercise). */
    fun saveWorkoutAsRoutine(workoutId: String, name: String, onSaved: (String?) -> Unit) = launch {
        onSaved(repo.createRoutineFromWorkout(workoutId, name.trim())?.id)
    }

    fun deleteRoutine(routineId: String) = launch { repo.deleteRoutine(routineId) }

    /** The target an exercise gets when the editor adds it to a routine ([RoutineStats.defaultTarget]). */
    fun routineTarget(exerciseId: String): RoutineExercise =
        RoutineStats.defaultTarget(exerciseId, kindOf(exerciseId), ExerciseHistory.previousWorkingSets(repo.workouts.value, exerciseId, null))

    /** The save started by the routine editor session [requestId], if any. */
    fun routineSave(requestId: String): Flow<EditorSave?> = routineSaves.state.map { it[requestId] }.distinctUntilChanged()

    /**
     * Creates ([routineId] null) or updates one of the user's routines for the editor session
     * [requestId]. Like [saveExercise], the result waits in [routineSave] across rotation and Home,
     * and a repeated call for the same session is ignored.
     */
    fun saveRoutine(requestId: String, routineId: String?, form: RoutineForm) {
        if (!routineSaves.start(requestId)) return
        launch {
            val id = try {
                if (routineId == null) {
                    repo.createRoutine(form.name, form.exercises, form.note).id
                } else {
                    repo.updateRoutine(routineId) { it.copy(name = form.name, note = form.note, exercises = form.exercises) }?.id
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            routineSaves.finish(requestId, id)
        }
    }

    /** Forgets a save result the routine editor has acted on (shown its failure). */
    fun consumeRoutineSave(requestId: String) = routineSaves.consume(requestId)

    /** The routine editor session [requestId] is gone for good (not a rotation). */
    fun closeRoutineEditor(requestId: String) {
        routineSaves.close(requestId)
    }

    // ---- exercises: favourites, notes, photos, custom ones -----------------------------------

    fun toggleFavorite(exerciseId: String) = launch {
        repo.setFavorite(exerciseId, !repo.exercise(exerciseId).favorite)
    }

    /** Machine settings ("seat 4, back 2"); blank removes them. */
    fun setExerciseNotes(exerciseId: String, notes: String) = launch {
        repo.updateNote(exerciseId) { it.copy(notes = notes) }
    }

    /** Stores a picked or captured photo for the exercise (replacing its previous one). */
    fun setPhoto(exerciseId: String, uri: Uri, fromCamera: Boolean) = launch {
        val name = importPhoto(uri, exerciseId, fromCamera)
        if (name == null) {
            _events.tryEmit(TrainingEvent.PhotoFailed)
            return@launch
        }
        var old: String? = null
        repo.updateNote(exerciseId) {
            old = it.photo
            it.copy(photo = name)
        }
        old?.takeIf { it != name }?.let { withContext(Dispatchers.IO) { photos.delete(it) } }
    }

    /** Removes the photo (also when its file is missing on this phone). */
    fun removePhoto(exerciseId: String) = launch {
        var old: String? = null
        repo.updateNote(exerciseId) {
            old = it.photo
            it.copy(photo = null)
        }
        old?.let { withContext(Dispatchers.IO) { photos.delete(it) } }
    }

    /** Photo files imported for the editor that no saved exercise uses yet. */
    private val draftPhotos = mutableSetOf<String>()

    /** Imports a photo for the editor before the exercise is saved; [onResult] gets its file name. */
    fun importDraftPhoto(uri: Uri, fromCamera: Boolean, onResult: (String?) -> Unit) = launch {
        val name = importPhoto(uri, DRAFT_PHOTO_ID, fromCamera)
        if (name == null) _events.tryEmit(TrainingEvent.PhotoFailed) else draftPhotos += name
        onResult(name)
    }

    /**
     * Deletes the editor's imported photos except [keep] (the one just saved), when the editor
     * saves or closes. A photo still importing when the editor closed goes with the next editor.
     */
    fun discardDraftPhotos(keep: String?) {
        val unused = draftPhotos.filter { it != keep }
        draftPhotos.clear()
        if (unused.isNotEmpty()) appScope.launch(Dispatchers.IO) { unused.forEach(photos::delete) }
    }

    /** A picked or captured image stored as a photo file; null when it cannot be read or stored. */
    private suspend fun importPhoto(uri: Uri, exerciseId: String, fromCamera: Boolean): String? = withContext(Dispatchers.IO) {
        try {
            photos.importFrom(uri, exerciseId)
        } catch (e: Exception) {
            // Disk full, unreadable or revoked content: reported as a photo that could not be read.
            null
        } finally {
            if (fromCamera) photos.cameraTarget.delete()
        }
    }

    /** The save started by the editor session [requestId], if any. */
    fun editorSave(requestId: String): Flow<EditorSave?> = editorSaves.state.map { it[requestId] }.distinctUntilChanged()

    /**
     * Creates or updates a custom exercise with its notes and photo for the editor session
     * [requestId]. The result waits in [editorSave] (the editor may be recreated or in the
     * background meanwhile); a second call for the same session while saving or saved is ignored.
     */
    fun saveExercise(requestId: String, draft: ExerciseDraft) {
        if (!editorSaves.start(requestId)) return
        launch {
            val id = try {
                writeExercise(draft)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // The photo being saved is kept; any other picked in this editor goes.
            if (id != null) discardDraftPhotos(keep = draft.photo)
            val editorClosed = editorSaves.finish(requestId, id)
            if (editorClosed && id == null) discardDraftPhotos(keep = null)
        }
    }

    /** Forgets a save result the editor has acted on (shown its failure). */
    fun consumeEditorSave(requestId: String) = editorSaves.consume(requestId)

    /**
     * The editor session [requestId] is gone for good (not a rotation). Unsaved photos picked in it
     * are deleted, except while its save is still running: that save keeps the one it stores.
     */
    fun closeEditor(requestId: String) {
        if (editorSaves.close(requestId)) discardDraftPhotos(keep = null)
    }

    /** Writes the draft; null when the exercise being edited no longer exists. */
    private suspend fun writeExercise(draft: ExerciseDraft): String? {
        val id = if (draft.id == null) {
            repo.createCustomExercise(draft.name, draft.kind, draft.equipment, draft.primary, draft.secondary).id
        } else {
            val current = repo.customExercises.value.firstOrNull { it.id == draft.id } ?: return null
            repo.updateCustomExercise(
                current.copy(name = draft.name, kind = draft.kind, equipment = draft.equipment, primary = draft.primary, secondary = draft.secondary),
            ) ?: return null
            draft.id
        }
        var oldPhoto: String? = null
        repo.updateNote(id) {
            oldPhoto = it.photo
            it.copy(notes = draft.notes ?: it.notes, photo = if (draft.photoChanged) draft.photo else it.photo)
        }
        if (draft.photoChanged) oldPhoto?.takeIf { it != draft.photo }?.let { withContext(Dispatchers.IO) { photos.delete(it) } }
        return id
    }

    /**
     * Removes a custom exercise: archived when a workout or routine uses it (they keep its name), else
     * deleted with its note and photo. [onDone] tells whether it was archived (as decided by the
     * repository under its write lock).
     */
    fun removeCustomExercise(exerciseId: String, onDone: (archived: Boolean) -> Unit) = launch {
        val photo = repo.notes.value[exerciseId]?.photo
        val archived = repo.removeCustomExercise(exerciseId)
        if (!archived && photo != null) withContext(Dispatchers.IO) { photos.delete(photo) }
        onDone(archived)
    }

    fun restoreCustomExercise(exerciseId: String) = launch {
        val current = repo.customExercises.value.firstOrNull { it.id == exerciseId } ?: return@launch
        repo.updateCustomExercise(current.copy(archived = false))
    }

    /** The custom exercise behind [exerciseId] (null for built-in ones). */
    fun customExercise(exerciseId: String): CustomExercise? = repo.customExercises.value.firstOrNull { it.id == exerciseId }

    /**
     * The user's note of an exercise as stored right now ([exercises] follows a moment later), so an
     * editor opened just after saving the machine settings starts from them.
     */
    fun storedNote(exerciseId: String): ExerciseNote? = repo.notes.value[exerciseId]

    // ---- settings --------------------------------------------------------------------------

    fun setLoadUnit(unit: LoadUnit) = settings.setLoadUnit(unit)

    fun setRestSeconds(seconds: Int) = settings.setRestSeconds(seconds)

    // ---- helpers ---------------------------------------------------------------------------

    private fun launch(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch(block = block)

    private fun edit(workoutId: String, transform: (Workout) -> Workout): Job = launch { repo.updateWorkout(workoutId, transform) }

    private fun kindOf(exerciseId: String): ExerciseKind = repo.exercise(exerciseId).kind

    private fun lookup(exercises: Map<String, Exercise>): (String) -> Exercise = { id -> exercises[id] ?: TrainingRepository.unknownExercise(id) }

    private fun buildOverview(workouts: List<Workout>, exercises: Map<String, Exercise>, day: LocalDate, load: LoadModel): TrainingOverview {
        val zone = ZoneId.systemDefault()
        val lookup = lookup(exercises)
        val summary = { w: Workout -> TrainingSummaries.summary(w, lookup, load) }
        val finished = workouts.filter { !it.inProgress && it.doneSets > 0 }
        val active = workouts.lastOrNull { it.inProgress }
        return TrainingOverview(
            loaded = true,
            active = active?.let(summary),
            today = TrainingSummaries.finishedOn(workouts, day, zone)?.let(summary),
            last = finished.lastOrNull()?.let(summary),
            recent = finished.takeLast(RECENT_WORKOUTS).asReversed().map(summary),
            templates = TrainingSummaries.templates(workouts).map(summary),
            week = TrainingSummaries.week(workouts, day, WeekFields.of(Locale.getDefault()).firstDayOfWeek, zone, lookup, load),
            hasWorkouts = finished.isNotEmpty() || active != null,
        )
    }

    private fun buildWorkout(workouts: List<Workout>, w: Workout, exercise: (String) -> Exercise, load: LoadModel): WorkoutUi {
        val kindOf = { id: String -> exercise(id).kind }
        val records = WorkoutRecords.forWorkout(workouts, w, exercise, load)
        val entries = w.entries.map { e ->
            val previous = ExerciseHistory.previousSets(workouts, e.exerciseId, w.id)
            val ids = e.sets.map { it.id }.toSet()
            EntryUi(
                entry = e,
                exercise = exercise(e.exerciseId),
                previous = SetTable.previousFor(e.sets, previous),
                records = records.filterKeys { it in ids },
            )
        }
        return WorkoutUi(
            workout = w,
            entries = entries,
            workingSets = TrainingSummaries.workingSets(w),
            volumeKg = ExerciseHistory.workoutVolumeKg(w, exercise, load),
            unfinishedFilled = WorkoutUiEdits.unfinishedFilled(w, kindOf),
        )
    }

    private fun summarize(w: Workout): FinishSummary {
        val lookup = lookup(repo.exercises.value)
        val load = loadModel.value
        val records = WorkoutRecords.forWorkout(repo.workouts.value, w, lookup, load)
        val recordExercises = w.entries.filter { e -> e.sets.any { it.id in records } }.map { it.exerciseId }.distinct()
        return FinishSummary(
            workout = TrainingSummaries.summary(w, lookup, load),
            records = records.size,
            recordExerciseIds = recordExercises,
            muscles = ExerciseHistory.workoutMuscles(w, lookup),
        )
    }

    companion object {
        private const val RECENT_WORKOUTS = 5
        private const val RECENT_EXERCISES = 12
        private const val PAST_WORKOUT_MS = 60 * 60_000L
        private const val DRAFT_PHOTO_ID = "draft"
        private const val BITMAP_CACHE_BYTES = 24 * 1024 * 1024

        /** A workout logged for another day still without exercises this long after its last change is dropped. */
        private const val ABANDONED_LOG_MS = 10 * 60_000L

        /** Photo files younger than this are never swept (an editor or the cloud restore may still attach them). */
        private const val UNUSED_PHOTO_GRACE_MS = 24 * 3_600_000L

        /** How long the achievements must stay unchanged before a board is celebrated. */
        private const val BOARD_SETTLE_MS = 300L

        /** How long nothing must hold the celebrations before they show. */
        private const val CELEBRATION_SETTLE_MS = 400L
        private const val FINISHING = "finishing"

        private fun now() = System.currentTimeMillis()

        private fun newId() = UUID.randomUUID().toString()

        /**
         * Until a moment after the next local midnight, at most an hour: a change of time zone or of
         * the clock is noticed within the hour.
         */
        private fun untilTomorrow(): Long {
            val now = ZonedDateTime.now()
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            return (Duration.between(now, midnight).toMillis() + 1_000L).coerceIn(1_000L, 3_600_000L)
        }

        /** Emits the time now and then on every multiple of [periodMs]. */
        private fun ticker(periodMs: Long): Flow<Long> = flow {
            while (true) {
                val t = now()
                emit(t)
                delay(periodMs - t % periodMs)
            }
        }

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as GlobalFitnessApp
                TrainingViewModel(app.container)
            }
        }
    }
}
