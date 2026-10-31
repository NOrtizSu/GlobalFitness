package com.enjoyyourtime.globalfitness.training.data

import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.catalog.RoutineCatalog
import com.enjoyyourtime.globalfitness.training.domain.ExerciseHistory
import com.enjoyyourtime.globalfitness.training.domain.WorkoutTime
import com.enjoyyourtime.globalfitness.training.model.CatalogExercise
import com.enjoyyourtime.globalfitness.training.model.CatalogRoutine
import com.enjoyyourtime.globalfitness.training.model.CustomExercise
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.ExerciseNote
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Routine
import com.enjoyyourtime.globalfitness.training.model.RoutineExercise
import com.enjoyyourtime.globalfitness.training.model.RoutineTemplate
import com.enjoyyourtime.globalfitness.training.model.Tombstone
import com.enjoyyourtime.globalfitness.training.model.Workout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Where a change came from: the cloud backup ignores its own writes, Health Connect reacts to all. */
enum class ChangeOrigin { LOCAL, CLOUD }

/**
 * Single source of truth of the training data, mirroring [com.enjoyyourtime.globalfitness.data.Repository]:
 * everything is loaded into memory at start, writes go to the [TrainingStore] on [io] (serialized
 * by a mutex) and then update the StateFlows.
 */
class TrainingRepository(
    private val store: TrainingStore,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    compute: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val catalog: List<CatalogExercise> = ExerciseCatalog.all,
    private val routineCatalog: List<CatalogRoutine> = RoutineCatalog.all,
) {
    private val mutex = Mutex()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _workouts = MutableStateFlow<List<Workout>>(emptyList())

    /** Every workout, oldest first (by start time). */
    val workouts: StateFlow<List<Workout>> = _workouts.asStateFlow()

    private val _custom = MutableStateFlow<List<CustomExercise>>(emptyList())

    /** The user's own exercises and machines (archived ones included). */
    val customExercises: StateFlow<List<CustomExercise>> = _custom.asStateFlow()

    private val _notes = MutableStateFlow<Map<String, ExerciseNote>>(emptyMap())

    /** Machine settings, photos and favorites by exercise id. */
    val notes: StateFlow<Map<String, ExerciseNote>> = _notes.asStateFlow()

    private val _routines = MutableStateFlow<List<Routine>>(emptyList())

    /** The user's own routines, oldest first. */
    val routines: StateFlow<List<Routine>> = _routines.asStateFlow()

    private val _remoteVersions = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * Workouts as they last arrived from the cloud backup in this process (id → [Workout.updatedAt]):
     * the achievements file what they reach without a celebration (the phone that logged them had
     * it). A local edit gives the workout another version, which is this phone's again. Updated
     * before [workouts]: read after it, it always covers the list just read.
     */
    val remoteVersions: StateFlow<Map<String, Long>> = _remoteVersions.asStateFlow()

    private val _changes = MutableSharedFlow<ChangeOrigin>(extraBufferCapacity = 16)

    /** Emitted after every successful write. */
    val changes: SharedFlow<ChangeOrigin> = _changes.asSharedFlow()

    /** Every exercise by id: catalog + custom ones, with the user's notes. */
    val exercises: StateFlow<Map<String, Exercise>> =
        combine(_custom, _notes) { custom, notes -> buildExercises(catalog, custom, notes) }
            .flowOn(compute)
            .stateIn(scope, SharingStarted.Eagerly, buildExercises(catalog, emptyList(), emptyMap()))

    /** Every routine to choose from: the user's own first (newest first), then the built-in ones. */
    val routineTemplates: StateFlow<List<RoutineTemplate>> =
        _routines.map { own -> buildRoutineTemplates(routineCatalog, own) }
            .flowOn(compute)
            .stateIn(scope, SharingStarted.Eagerly, buildRoutineTemplates(routineCatalog, emptyList()))

    /** The workout in progress (the most recent one without an end), if any. */
    val activeWorkout: StateFlow<Workout?> =
        _workouts.map { list -> list.lastOrNull { it.inProgress } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            mutex.withLock {
                try {
                    val (w, c, n) = withContext(io) { Triple(store.loadWorkouts(), store.loadCustomExercises(), store.loadNotes()) }
                    val r = withContext(io) { store.loadRoutines() }
                    _workouts.value = sorted(w)
                    _custom.value = c
                    _notes.value = n.associateBy { it.exerciseId }
                    _routines.value = r
                    // [exercises] is derived on another dispatcher: loaded only once it has the custom
                    // exercises, so nothing loaded meets them as unknown (another kind, no muscles).
                    exercises.first { map -> c.all { it.id in map } }
                    _loaded.value = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _loadError.value = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    suspend fun awaitLoaded() {
        loaded.first { it }
    }

    private suspend fun <T> write(origin: ChangeOrigin = ChangeOrigin.LOCAL, block: suspend () -> T): T {
        awaitLoaded()
        val result = mutex.withLock { block() }
        _changes.tryEmit(origin)
        return result
    }

    fun workout(id: String): Workout? = _workouts.value.firstOrNull { it.id == id }

    fun exercise(id: String): Exercise = exercises.value[id] ?: unknownExercise(id)

    // ---- workouts --------------------------------------------------------------------------

    /**
     * Starts a workout at [at]. With a [template], its exercises are copied with their sets as
     * planned (not done) sets, so the user only confirms or adjusts them.
     */
    suspend fun startWorkout(at: Long = clock(), name: String? = null, template: Workout? = null): Workout = write {
        val now = clock()
        val workout = Workout(
            id = newId(),
            startedAt = at,
            endedAt = null,
            name = name ?: template?.name,
            note = null,
            updatedAt = now,
            entries = template?.let { WorkoutEdits.planFrom(it, newId) }.orEmpty(),
        )
        withContext(io) { store.upsertWorkout(workout) }
        _workouts.value = sorted(_workouts.value + workout)
        workout
    }

    /**
     * Starts a workout from a routine: its exercises with their target sets, loads (and reps or time
     * when the routine has none) taken from the last time each one was done. [name]: the workout's
     * (the routine's name in the user's language).
     */
    suspend fun startFromRoutine(routine: RoutineTemplate, at: Long = clock(), name: String? = null): Workout = write {
        val history = _workouts.value
        val workout = Workout(
            id = newId(),
            startedAt = at,
            endedAt = null,
            name = name?.trim()?.takeIf { it.isNotEmpty() },
            note = null,
            updatedAt = clock(),
            entries = WorkoutEdits.planFromRoutine(routine.exercises, { id -> ExerciseHistory.previousWorkingSets(history, id, null) }, newId),
        )
        withContext(io) { store.upsertWorkout(workout) }
        _workouts.value = sorted(_workouts.value + workout)
        workout
    }

    /**
     * Adds a routine's exercises to a workout in one step (exercises it already has are skipped),
     * pre-filled like [startFromRoutine] from the sessions before that workout. Returns the ids of
     * the entries added, decided under the write lock (so "Undo" never takes out entries a sync added
     * meanwhile); null when the workout no longer exists.
     */
    suspend fun addRoutineToWorkout(workoutId: String, routine: RoutineTemplate): List<String>? = write {
        val current = _workouts.value.firstOrNull { it.id == workoutId } ?: return@write null
        val previous = { id: String -> ExerciseHistory.previousWorkingSets(_workouts.value, id, workoutId) }
        val stored = storeWorkoutLocked(current, WorkoutEdits.addRoutine(current, routine.exercises, previous, newId))
        val before = current.entries.mapTo(HashSet()) { it.id }
        stored.entries.map { it.id }.filterNot { it in before }
    }

    /**
     * Creates an empty workout that is already finished ([start] to [end]): a workout logged for
     * another day. One write, so it is never seen as the workout in progress.
     */
    suspend fun logFinishedWorkout(start: Long, end: Long): Workout = write {
        val workout = Workout(
            id = newId(),
            startedAt = start,
            endedAt = maxOf(end, start),
            name = null,
            note = null,
            updatedAt = clock(),
        )
        withContext(io) { store.upsertWorkout(workout) }
        _workouts.value = sorted(_workouts.value + workout)
        workout
    }

    /**
     * Applies [transform] to the workout and stores the result with a new [Workout.updatedAt].
     * Returns the stored workout, or null when it no longer exists.
     */
    suspend fun updateWorkout(id: String, transform: (Workout) -> Workout): Workout? = write {
        val current = _workouts.value.firstOrNull { it.id == id } ?: return@write null
        storeWorkoutLocked(current, transform(current))
    }

    /**
     * Ends the workout (empty workouts are deleted instead). Decided under the write lock. Finished
     * while paused, the pause ends with it: the paused stretch does not count.
     */
    suspend fun finishWorkout(id: String, at: Long = clock()): Workout? = write {
        val current = _workouts.value.firstOrNull { it.id == id } ?: return@write null
        if (current.entries.none { e -> e.sets.any { it.done } }) {
            deleteWorkoutLocked(id)
            null
        } else {
            val end = maxOf(at, current.startedAt)
            storeWorkoutLocked(current, current.copy(endedAt = end, pauses = WorkoutTime.closed(current.pauses, end)))
        }
    }

    /** Pauses the workout in progress at [at]; a finished or already paused one is left as it is. */
    suspend fun pauseWorkout(id: String, at: Long = clock()): Workout? = updateWorkout(id) { WorkoutTime.pause(it, at) }

    /** Resumes a paused workout at [at]; one that is not paused is left as it is. */
    suspend fun resumeWorkout(id: String, at: Long = clock()): Workout? = updateWorkout(id) { WorkoutTime.resume(it, at) }

    suspend fun deleteWorkout(id: String) = write { deleteWorkoutLocked(id) }

    /** Call inside [write]. The pauses are stored [WorkoutTime.normalize]d, whatever edit made them. */
    private suspend fun storeWorkoutLocked(current: Workout, changed: Workout): Workout {
        val normalized = changed.copy(pauses = WorkoutTime.normalize(changed.pauses, changed.startedAt, changed.endedAt))
        if (normalized == current) return current
        val updated = normalized.copy(id = current.id, updatedAt = maxOf(clock(), current.updatedAt + 1))
        withContext(io) { store.upsertWorkout(updated) }
        _workouts.value = sorted(_workouts.value.map { if (it.id == current.id) updated else it })
        return updated
    }

    /** Call inside [write]. The tombstone is newer than the version it deletes (see [deletedAt]). */
    private suspend fun deleteWorkoutLocked(id: String) {
        val deletedAt = deletedAt(_workouts.value.firstOrNull { it.id == id }?.updatedAt)
        withContext(io) { store.deleteWorkout(id, deletedAt) }
        _workouts.value = _workouts.value.filterNot { it.id == id }
    }

    /**
     * Time of a deletion: never older than the deleted version, even if this phone's clock is behind
     * the phone that wrote it, so the cloud backup (last writer wins) never brings it back.
     */
    private fun deletedAt(updatedAt: Long?): Long = if (updatedAt == null) clock() else maxOf(clock(), updatedAt + 1)

    // ---- custom exercises -------------------------------------------------------------------

    suspend fun createCustomExercise(
        name: String,
        kind: ExerciseKind,
        equipment: Equipment,
        primary: Set<Muscle>,
        secondary: Set<Muscle>,
    ): CustomExercise = write {
        val now = clock()
        val created = CustomExercise(
            id = CustomExercise.PREFIX + newId(),
            name = name.trim(),
            kind = kind,
            equipment = equipment,
            primary = primary,
            secondary = secondary - primary,
            archived = false,
            createdAt = now,
            updatedAt = now,
        )
        withContext(io) { store.upsertCustomExercise(created) }
        _custom.value = _custom.value + created
        created
    }

    suspend fun updateCustomExercise(updated: CustomExercise): CustomExercise? = write {
        val current = _custom.value.firstOrNull { it.id == updated.id } ?: return@write null
        storeCustomLocked(current, updated)
    }

    /** Call inside [write]. */
    private suspend fun storeCustomLocked(current: CustomExercise, updated: CustomExercise): CustomExercise {
        val stored = updated.copy(
            name = updated.name.trim(),
            secondary = updated.secondary - updated.primary,
            createdAt = current.createdAt,
            updatedAt = maxOf(clock(), current.updatedAt + 1),
        )
        withContext(io) { store.upsertCustomExercise(stored) }
        _custom.value = _custom.value.map { if (it.id == stored.id) stored else it }
        return stored
    }

    /**
     * Removes a custom exercise. One used by any workout or routine is only archived (hidden from the
     * picker) so the history and the routines keep its name; an unused one is deleted together with
     * its note. Decided under the write lock, so a workout or routine that starts using it meanwhile is
     * always seen. Returns true when it was archived (false: deleted, or already gone).
     */
    suspend fun removeCustomExercise(id: String): Boolean = write {
        val current = _custom.value.firstOrNull { it.id == id } ?: return@write false
        if (id in exercisesInUse(_workouts.value, _routines.value)) {
            storeCustomLocked(current, current.copy(archived = true))
            return@write true
        }
        val note = _notes.value[id]
        withContext(io) {
            store.deleteCustomExercise(id, deletedAt(current.updatedAt))
            if (note != null) store.deleteNote(id, deletedAt(note.updatedAt))
        }
        _custom.value = _custom.value.filterNot { it.id == id }
        _notes.value = _notes.value - id
        false
    }

    // ---- notes, photos, favorites -----------------------------------------------------------

    /** Changes the user's note of an exercise; an empty note is deleted. */
    suspend fun updateNote(exerciseId: String, transform: (ExerciseNote) -> ExerciseNote): ExerciseNote? = write {
        val now = clock()
        val current = _notes.value[exerciseId]
        val base = current ?: ExerciseNote(exerciseId, notes = null, photo = null, favorite = false, updatedAt = 0)
        val changed = transform(base).copy(exerciseId = exerciseId)
        if (changed == base) return@write current
        val stored = changed.copy(
            notes = changed.notes?.trim()?.takeIf { it.isNotEmpty() },
            updatedAt = maxOf(now, base.updatedAt + 1),
        )
        if (stored.isEmpty) {
            if (current != null) withContext(io) { store.deleteNote(exerciseId, deletedAt(current.updatedAt)) }
            _notes.value = _notes.value - exerciseId
            null
        } else {
            withContext(io) { store.upsertNote(stored) }
            _notes.value = _notes.value + (exerciseId to stored)
            stored
        }
    }

    suspend fun setFavorite(exerciseId: String, favorite: Boolean) {
        updateNote(exerciseId) { it.copy(favorite = favorite) }
    }

    // ---- routines ---------------------------------------------------------------------------

    fun routine(id: String): Routine? = _routines.value.firstOrNull { it.id == id }

    /** Saves a new routine (blank names and empty exercise lists are the screen's job to refuse). */
    suspend fun createRoutine(name: String, exercises: List<RoutineExercise>, note: String? = null): Routine = write {
        val now = clock()
        val routine = Routine(
            id = newId(),
            name = name.trim(),
            exercises = cleanRoutine(exercises),
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = now,
            updatedAt = now,
        )
        withContext(io) { store.upsertRoutine(routine) }
        _routines.value = _routines.value + routine
        routine
    }

    /** Saves a workout's exercises as a new routine (see [WorkoutEdits.routineFrom]). */
    suspend fun createRoutineFromWorkout(workoutId: String, name: String): Routine? {
        val w = workout(workoutId) ?: return null
        val exercises = WorkoutEdits.routineFrom(w)
        if (exercises.isEmpty()) return null
        return createRoutine(name, exercises)
    }

    /** Changes a routine; null when it no longer exists. */
    suspend fun updateRoutine(id: String, transform: (Routine) -> Routine): Routine? = write {
        val current = _routines.value.firstOrNull { it.id == id } ?: return@write null
        val changed = transform(current)
        if (changed == current) return@write current
        val stored = changed.copy(
            id = current.id,
            name = changed.name.trim(),
            exercises = cleanRoutine(changed.exercises),
            note = changed.note?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = current.createdAt,
            updatedAt = maxOf(clock(), current.updatedAt + 1),
        )
        withContext(io) { store.upsertRoutine(stored) }
        _routines.value = _routines.value.map { if (it.id == id) stored else it }
        stored
    }

    suspend fun deleteRoutine(id: String) = write {
        val current = _routines.value.firstOrNull { it.id == id } ?: return@write
        withContext(io) { store.deleteRoutine(id, deletedAt(current.updatedAt)) }
        _routines.value = _routines.value.filterNot { it.id == id }
    }

    // ---- cloud backup -----------------------------------------------------------------------

    /** Deletions the cloud backup still has to propagate. */
    suspend fun tombstones(): List<Tombstone> {
        awaitLoaded()
        return mutex.withLock { withContext(io) { store.loadTombstones() } }
    }

    /** Forgets tombstones the cloud already has. */
    suspend fun dropTombstones(tombstones: Collection<Tombstone>) {
        if (tombstones.isEmpty()) return
        awaitLoaded()
        mutex.withLock { withContext(io) { store.dropTombstones(tombstones) } }
    }

    /** Applies changes from the cloud in one transaction (no local tombstones for its removals). */
    suspend fun applyRemote(batch: TrainingBatch) {
        if (batch.isEmpty) return
        write(ChangeOrigin.CLOUD) { applyLocked(batch) }
    }

    /**
     * [applyRemote] for a sync that read the data a while ago: items whose local copy changed since
     * [seen] (another updatedAt, or created / deleted meanwhile) are left out, checked under the write
     * lock, so an edit made while the sync was running is never overwritten (the next sync decides
     * again). Returns what was applied.
     */
    suspend fun applyRemoteIfUnchanged(batch: TrainingBatch, seen: TrainingVersions): TrainingBatch {
        if (batch.isEmpty) return batch
        return write(ChangeOrigin.CLOUD) {
            val workouts = _workouts.value.associate { it.id to it.updatedAt }
            val custom = _custom.value.associate { it.id to it.updatedAt }
            val notes = _notes.value.mapValues { it.value.updatedAt }
            val routines = _routines.value.associate { it.id to it.updatedAt }
            val upsertWorkouts = batch.upsertWorkouts.filter { workouts[it.id] == seen.workouts[it.id] }
            val removeWorkouts = batch.removeWorkouts.filter { workouts[it] == seen.workouts[it] }
            val upsertRoutines = batch.upsertRoutines.filter { routines[it.id] == seen.routines[it.id] }
            val removeRoutines = batch.removeRoutines.filter { routines[it] == seen.routines[it] }
            // A custom exercise still used by a workout or a routine (after this batch) is never removed:
            // they would show "unknown exercise". The sync keeps it archived instead.
            val workoutsGone = removeWorkouts.toSet() + upsertWorkouts.map { it.id }
            val routinesGone = removeRoutines.toSet() + upsertRoutines.map { it.id }
            val inUse = exercisesInUse(
                _workouts.value.filterNot { it.id in workoutsGone } + upsertWorkouts,
                _routines.value.filterNot { it.id in routinesGone } + upsertRoutines,
            )
            val safe = TrainingBatch(
                upsertWorkouts = upsertWorkouts,
                removeWorkouts = removeWorkouts,
                upsertCustomExercises = batch.upsertCustomExercises.filter { custom[it.id] == seen.customExercises[it.id] },
                removeCustomExercises = batch.removeCustomExercises.filter { custom[it] == seen.customExercises[it] && it !in inUse },
                upsertNotes = batch.upsertNotes.filter { notes[it.exerciseId] == seen.notes[it.exerciseId] },
                removeNotes = batch.removeNotes.filter { notes[it] == seen.notes[it] },
                upsertRoutines = upsertRoutines,
                removeRoutines = removeRoutines,
            )
            if (!safe.isEmpty) applyLocked(safe)
            safe
        }
    }

    /** Call under [mutex] (inside [write]). */
    private suspend fun applyLocked(batch: TrainingBatch) {
        withContext(io) { store.applyBatch(batch) }
        val removedWorkouts = batch.removeWorkouts.toSet()
        val upserted = batch.upsertWorkouts.associateBy { it.id }
        if (removedWorkouts.isNotEmpty() || upserted.isNotEmpty()) {
            _remoteVersions.value = _remoteVersions.value - removedWorkouts + upserted.mapValues { it.value.updatedAt }
        }
        _workouts.value = sorted(
            _workouts.value.filterNot { it.id in removedWorkouts || it.id in upserted } +
                upserted.values.filterNot { it.id in removedWorkouts },
        )
        val removedCustom = batch.removeCustomExercises.toSet()
        val upsertedCustom = batch.upsertCustomExercises.associateBy { it.id }
        _custom.value = _custom.value.filterNot { it.id in removedCustom || it.id in upsertedCustom } +
            upsertedCustom.values.filterNot { it.id in removedCustom }
        _notes.value = (_notes.value + batch.upsertNotes.associateBy { it.exerciseId }) - batch.removeNotes.toSet()
        val removedRoutines = batch.removeRoutines.toSet()
        val upsertedRoutines = batch.upsertRoutines.associateBy { it.id }
        _routines.value = _routines.value.filterNot { it.id in removedRoutines || it.id in upsertedRoutines } +
            upsertedRoutines.values.filterNot { it.id in removedRoutines }
    }

    private fun sorted(list: List<Workout>) = list.sortedWith(compareBy({ it.startedAt }, { it.id }))

    companion object {
        fun buildExercises(
            catalog: List<CatalogExercise>,
            custom: List<CustomExercise>,
            notes: Map<String, ExerciseNote>,
        ): Map<String, Exercise> {
            val result = LinkedHashMap<String, Exercise>(catalog.size + custom.size)
            for (c in catalog) {
                val n = notes[c.id]
                result[c.id] = Exercise(
                    id = c.id,
                    name = UiText.res(c.nameRes),
                    kind = c.kind,
                    equipment = c.equipment,
                    primary = c.primary,
                    secondary = c.secondary,
                    builtIn = true,
                    notes = n?.notes,
                    photo = n?.photo,
                    favorite = n?.favorite == true,
                    hcSegment = c.hcSegment,
                    hcSession = c.hcSession,
                    bodyweightFactor = c.bodyweightFactor,
                    mobility = c.mobility,
                )
            }
            for (c in custom) {
                val n = notes[c.id]
                result[c.id] = Exercise(
                    id = c.id,
                    name = UiText.raw(c.name),
                    kind = c.kind,
                    equipment = c.equipment,
                    primary = c.primary,
                    secondary = c.secondary,
                    builtIn = false,
                    archived = c.archived,
                    notes = n?.notes,
                    photo = n?.photo,
                    favorite = n?.favorite == true,
                    // An assisted machine always lifts the whole body; other own exercises count extra load only.
                    bodyweightFactor = if (c.kind == ExerciseKind.ASSISTED_REPS) 1.0 else null,
                )
            }
            return result
        }

        /** The user's routines (newest first) followed by the built-in ones. */
        fun buildRoutineTemplates(catalog: List<CatalogRoutine>, own: List<Routine>): List<RoutineTemplate> =
            own.sortedByDescending { it.updatedAt }.map { r ->
                RoutineTemplate(r.id, UiText.raw(r.name), r.exercises, builtIn = false, note = r.note, updatedAt = r.updatedAt)
            } + catalog.map { c -> RoutineTemplate(c.id, UiText.res(c.nameRes), c.exercises, builtIn = true) }

        /**
         * Exercise ids that [workouts] or [routines] refer to: a custom exercise among them is archived,
         * never deleted, or they would show "unknown exercise" (and a routine would plan unknown entries).
         */
        fun exercisesInUse(workouts: Iterable<Workout>, routines: Iterable<Routine>): Set<String> = HashSet<String>().apply {
            for (w in workouts) w.entries.mapTo(this) { it.exerciseId }
            for (r in routines) r.exercises.mapTo(this) { it.exerciseId }
        }

        /** Positive set counts within bounds, no duplicate exercises, no empty targets (null). */
        fun cleanRoutine(exercises: List<RoutineExercise>): List<RoutineExercise> =
            exercises.distinctBy { it.exerciseId }.map { e ->
                e.copy(
                    sets = e.sets.coerceIn(1, WorkoutEdits.MAX_ROUTINE_SETS),
                    reps = e.reps?.takeIf { it > 0 },
                    durationSec = e.durationSec?.takeIf { it > 0 },
                )
            }

        /** Placeholder for an id that is not known (e.g. a custom exercise not restored yet). */
        fun unknownExercise(id: String) = Exercise(
            id = id,
            name = UiText.res(R.string.training_unknown_exercise),
            kind = ExerciseKind.WEIGHT_REPS,
            equipment = Equipment.OTHER,
            primary = emptySet(),
            secondary = emptySet(),
            builtIn = false,
            archived = true,
        )
    }
}
