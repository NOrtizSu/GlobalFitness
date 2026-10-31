package com.enjoyyourtime.globalfitness.ui.training

import com.enjoyyourtime.globalfitness.health.WorkoutExportPlanner
import com.enjoyyourtime.globalfitness.training.catalog.SportsCatalog
import com.enjoyyourtime.globalfitness.training.data.WorkoutEdits
import com.enjoyyourtime.globalfitness.training.domain.ExerciseHistory
import com.enjoyyourtime.globalfitness.training.domain.ExerciseSession
import com.enjoyyourtime.globalfitness.training.domain.LoadModel
import com.enjoyyourtime.globalfitness.training.domain.RecordKind
import com.enjoyyourtime.globalfitness.training.domain.TrainingMath
import com.enjoyyourtime.globalfitness.training.domain.WorkoutTime
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutPause
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * A value column of the set table (besides the set number, "last time" and the tick). [ASSISTANCE]
 * edits the same weight field as [LOAD], labelled as the machine's counterweight.
 */
enum class SetField { LOAD, EXTRA_LOAD, ASSISTANCE, REPS, TIME, DISTANCE, LEVEL, KCAL }

/** Columns of the set table of one exercise. */
data class SetColumns(val previous: Boolean, val fields: List<SetField>)

/** Pure rules of the set table: which columns an exercise shows and what counts as filled in. */
object SetTable {
    fun columns(kind: ExerciseKind): SetColumns = when (kind) {
        ExerciseKind.WEIGHT_REPS -> SetColumns(previous = true, fields = listOf(SetField.LOAD, SetField.REPS))
        ExerciseKind.BODYWEIGHT_REPS -> SetColumns(previous = true, fields = listOf(SetField.EXTRA_LOAD, SetField.REPS))
        ExerciseKind.ASSISTED_REPS -> SetColumns(previous = true, fields = listOf(SetField.ASSISTANCE, SetField.REPS))
        ExerciseKind.DURATION -> SetColumns(previous = true, fields = listOf(SetField.TIME, SetField.LOAD))
        // Cardio has four values: the last session shows as the cells' placeholders instead.
        ExerciseKind.CARDIO -> SetColumns(previous = false, fields = listOf(SetField.TIME, SetField.DISTANCE, SetField.LEVEL, SetField.KCAL))
    }

    /**
     * The columns of [exercise]: those of its kind, except that a sport or activity has no machine
     * level, and a distance only where it covers one ([SportsCatalog.coversDistance]), and a stretch
     * or mobility drill only its time (nothing to load).
     */
    fun columns(exercise: Exercise): SetColumns {
        val columns = columns(exercise.kind)
        if (exercise.mobility && exercise.kind == ExerciseKind.DURATION) return columns.copy(fields = listOf(SetField.TIME))
        if (!TrainingMath.isSport(exercise)) return columns
        val distance = SportsCatalog.coversDistance(exercise)
        return columns.copy(fields = columns.fields.filter { it != SetField.LEVEL && (distance || it != SetField.DISTANCE) })
    }

    /**
     * Whether ticking a set of [exercise] in a live workout starts the rest timer: not after a sport
     * or activity (the match was the session) nor a stretch (held one after another); cardio machines
     * and strength sets keep it.
     */
    fun startsRest(exercise: Exercise): Boolean = !TrainingMath.isSport(exercise) && !exercise.mobility

    /** Whether the set has the value that defines it (reps, time or distance). */
    fun hasValues(set: WorkoutSet, kind: ExerciseKind): Boolean = when (kind) {
        ExerciseKind.WEIGHT_REPS, ExerciseKind.BODYWEIGHT_REPS, ExerciseKind.ASSISTED_REPS -> (set.reps ?: 0) > 0
        ExerciseKind.DURATION -> (set.durationSec ?: 0) > 0
        ExerciseKind.CARDIO -> (set.durationSec ?: 0) > 0 || (set.distanceM ?: 0.0) > 0.0
    }

    /**
     * The set of the last session each set corresponds to: the n-th warm-up to the n-th warm-up,
     * the n-th working set to the n-th working set (by position, as they were planned).
     */
    fun previousFor(sets: List<WorkoutSet>, previous: List<WorkoutSet>): Map<String, WorkoutSet> {
        val warmups = previous.filter { it.warmup }
        val working = previous.filter { !it.warmup }
        var w = 0
        var n = 0
        val result = HashMap<String, WorkoutSet>()
        for (s in sets) {
            val match = if (s.warmup) warmups.getOrNull(w++) else working.getOrNull(n++)
            if (match != null) result[s.id] = match
        }
        return result
    }

    /**
     * The empty values of [set] taken from [from] (ticking a set with only the new weight typed
     * keeps that weight and takes last time's reps).
     */
    fun fillMissing(set: WorkoutSet, from: WorkoutSet): WorkoutSet = set.copy(
        weightKg = set.weightKg ?: from.weightKg,
        reps = set.reps ?: from.reps,
        durationSec = set.durationSec ?: from.durationSec,
        distanceM = set.distanceM ?: from.distanceM,
        level = set.level ?: from.level,
        kcal = set.kcal ?: from.kcal,
    )

    /** The values of [from] copied into [set] (ids, warm-up flag and completion kept). */
    fun copyValues(set: WorkoutSet, from: WorkoutSet): WorkoutSet = set.copy(
        weightKg = from.weightKg,
        reps = from.reps,
        durationSec = from.durationSec,
        distanceM = from.distanceM,
        level = from.level,
        kcal = from.kcal,
    )

    /** Working-set number of each set ("1", "2"…); warm-ups have none (they show "W"). */
    fun workingNumbers(sets: List<WorkoutSet>): Map<String, Int> {
        var n = 0
        return sets.filter { !it.warmup }.associate { it.id to ++n }
    }
}

/**
 * Values a text field saved that the store has not echoed back yet. Every keystroke is saved, and
 * the saved value comes back a moment later: an echo of an earlier keystroke ("1" while "10" is
 * already typed) must not reset the text; any other new value (copied from last time, synced from
 * another phone) must.
 */
class SentValues<T>(private val same: (T, T) -> Boolean) {
    private val sent = ArrayDeque<T>()

    fun sent(value: T) {
        sent.addLast(value)
        if (sent.size > MAX_PENDING) sent.removeFirst()
    }

    /**
     * Whether the field should show [stored] instead of its text. [textMeansStored]: the text
     * already says [stored] (everything echoed, nothing to do).
     */
    fun shouldReset(stored: T, textMeansStored: Boolean): Boolean {
        if (textMeansStored) {
            sent.clear()
            return false
        }
        if (sent.any { same(it, stored) }) return false
        sent.clear()
        return true
    }

    private companion object {
        const val MAX_PENDING = 64
    }
}

/**
 * The save of one editor session ([TrainingViewModel.saveExercise], [TrainingViewModel.saveRoutine]);
 * kept across rotation and Home.
 */
sealed interface EditorSave {
    data object Saving : EditorSave

    /** [id]: the exercise or routine saved. */
    data class Saved(val id: String) : EditorSave

    /** What was being edited is gone, or the database refused the write: the form stays open. */
    data object Failed : EditorSave
}

/**
 * An editor's saves (exercise or routine editor) by editor session id. The editor may be recreated
 * (rotation) or in the background while a save runs, so the result waits here; a session closed
 * while saving is remembered so its late result is dropped.
 */
class EditorSaves {
    private val _state = MutableStateFlow<Map<String, EditorSave>>(emptyMap())
    val state: StateFlow<Map<String, EditorSave>> = _state.asStateFlow()
    private val closed = mutableSetOf<String>()

    /** Marks [session] as saving; false when it is already saving or saved (a repeated tap). */
    fun start(session: String): Boolean {
        val current = _state.value[session]
        if (current == EditorSave.Saving || current is EditorSave.Saved) return false
        _state.update { it + (session to EditorSave.Saving) }
        return true
    }

    /** The save of [session] ended ([savedId] null: failed). True when its editor was closed meanwhile. */
    fun finish(session: String, savedId: String?): Boolean {
        if (closed.remove(session)) {
            _state.update { it - session }
            return true
        }
        _state.update { it + (session to (savedId?.let(EditorSave::Saved) ?: EditorSave.Failed)) }
        return false
    }

    /** The editor acted on its result; a save still running stays. */
    fun consume(session: String) {
        if (_state.value[session] != EditorSave.Saving) _state.update { it - session }
    }

    /**
     * The editor of [session] is gone for good. True when its unsaved photos can be deleted now
     * (no save of it is running, and none stored them).
     */
    fun close(session: String): Boolean = when (_state.value[session]) {
        EditorSave.Saving -> {
            closed += session
            false
        }
        is EditorSave.Saved -> {
            _state.update { it - session }
            false
        }
        EditorSave.Failed, null -> {
            _state.update { it - session }
            true
        }
    }
}

/** The fields of the exercise editor. */
data class ExerciseForm(
    val name: String,
    val equipment: Equipment,
    val kind: ExerciseKind,
    val primary: Set<Muscle>,
    val secondary: Set<Muscle>,
    /** Machine settings. */
    val settings: String,
    /** Photo file name. */
    val photo: String?,
) {
    /**
     * Whether leaving would lose something compared with the form as it [opened]. Spaces around the
     * texts and a secondary muscle that is also primary (it is dropped on save) do not count.
     */
    fun isChangedFrom(opened: ExerciseForm): Boolean =
        name.trim() != opened.name.trim() ||
            equipment != opened.equipment ||
            kind != opened.kind ||
            primary != opened.primary ||
            secondary - primary != opened.secondary - opened.primary ||
            settings.trim() != opened.settings.trim() ||
            photo != opened.photo
}

/** Pure workout edits used by the workout screen, on top of [com.enjoyyourtime.globalfitness.training.data.WorkoutEdits]. */
object WorkoutUiEdits {
    /** Adds an empty warm-up set after the existing warm-ups (before the first working set). */
    fun addWarmup(w: Workout, entryId: String, newId: () -> String): Workout = w.copy(
        entries = w.entries.map { e ->
            if (e.id != entryId) return@map e
            val at = e.sets.indexOfFirst { !it.warmup }.let { if (it < 0) e.sets.size else it }
            e.copy(sets = e.sets.toMutableList().apply { add(at, WorkoutSet(id = newId(), warmup = true)) })
        },
    )

    /** Makes a set a warm-up or a working set; warm-ups stay grouped before the working sets. */
    fun setWarmup(w: Workout, entryId: String, setId: String, warmup: Boolean): Workout = w.copy(
        entries = w.entries.map { e ->
            if (e.id != entryId) return@map e
            val changed = e.sets.map { if (it.id == setId) it.copy(warmup = warmup) else it }
            e.copy(sets = changed.filter { it.warmup } + changed.filter { !it.warmup })
        },
    )

    /**
     * Puts a removed exercise back at [index] (clamped), with its ids, so an undo leaves no trace.
     * Nothing changes when it is already there again.
     */
    fun insertEntry(w: Workout, entry: WorkoutEntry, index: Int): Workout {
        if (w.entries.any { it.id == entry.id }) return w
        val list = w.entries.toMutableList()
        list.add(index.coerceIn(0, list.size), entry)
        return w.copy(entries = list)
    }

    /** Puts a deleted set back at [index] (clamped) of its exercise, if the exercise is still there. */
    fun insertSet(w: Workout, entryId: String, set: WorkoutSet, index: Int): Workout = w.copy(
        entries = w.entries.map { e ->
            if (e.id != entryId || e.sets.any { it.id == set.id }) {
                e
            } else {
                e.copy(sets = e.sets.toMutableList().apply { add(index.coerceIn(0, size), set) })
            }
        },
    )

    /** Moves an entry [delta] places (found by id: the list may have changed since it was drawn). */
    fun moveEntry(w: Workout, entryId: String, delta: Int): Workout {
        val from = w.entries.indexOfFirst { it.id == entryId }
        return if (from < 0) w else WorkoutEdits.moveEntry(w, from, from + delta)
    }

    /** When a set ticked in a past workout happened: its end (sets of live workouts use the clock). */
    fun doneAtFor(w: Workout, now: Long): Long = if (w.inProgress) now else (w.endedAt ?: now).coerceAtLeast(w.startedAt)

    /**
     * The set [setId] of [entryId] as [filled], ticked ([done]) or unticked at [now] (see
     * [doneAtFor]). Ticking one in a paused workout resumes it: a set means the user trains again
     * (from [resumeAtForTick]).
     */
    fun tick(w: Workout, entryId: String, setId: String, filled: WorkoutSet, done: Boolean, now: Long): Workout {
        val changed = WorkoutEdits.updateSet(w, entryId, setId) { filled.copy(done = done, doneAt = if (done) doneAtFor(w, now) else null) }
        return if (done && changed.paused) WorkoutTime.resume(changed, resumeAtForTick(changed, filled, now)) else changed
    }

    /**
     * When a set ticked at [now] in the paused [w] resumed it: when the set began (its expected
     * length before [now]), never before the pause did. The user trained without resuming first, and
     * the set then lies after the pause, not inside it (a whole pause shorter than the set is dropped).
     */
    fun resumeAtForTick(w: Workout, set: WorkoutSet, now: Long): Long {
        val pause = w.pauses.lastOrNull()?.takeIf { it.end == null } ?: return now
        return maxOf(pause.start, now - WorkoutExportPlanner.nominalMs(set)).coerceAtMost(now)
    }

    /** Ticks every filled-in set that is not done yet ("finish: complete the remaining sets"). */
    fun completeFilled(w: Workout, kindOf: (String) -> ExerciseKind, now: Long): Workout {
        val at = doneAtFor(w, now)
        return w.copy(
            entries = w.entries.map { e ->
                val kind = kindOf(e.exerciseId)
                e.copy(sets = e.sets.map { s -> if (!s.done && SetTable.hasValues(s, kind)) s.copy(done = true, doneAt = at) else s })
            },
        )
    }

    /** Drops the sets that were not ticked, and the exercises left without sets. */
    fun dropUnfinished(w: Workout): Workout = w.copy(
        entries = w.entries.map { e -> e.copy(sets = e.sets.filter { it.done }) }.filter { it.sets.isNotEmpty() },
    )

    /** Sets that are not ticked but hold values (what "finish" asks about). */
    fun unfinishedFilled(w: Workout, kindOf: (String) -> ExerciseKind): Int =
        w.entries.sumOf { e -> e.sets.count { !it.done && SetTable.hasValues(it, kindOf(e.exerciseId)) } }

    /**
     * Moves the workout to [start] (and, for a finished one, makes it last until [end]). Sets and
     * pauses of a finished workout move along and stay inside it, so their statistics keep the right
     * day; those of a workout in progress keep their real times (only pauses before the new start
     * are cut).
     */
    fun retime(w: Workout, start: Long, end: Long?): Workout {
        val delta = start - w.startedAt
        if (w.inProgress) return w.copy(startedAt = start, pauses = WorkoutTime.normalize(w.pauses, start, null))
        val newEnd = (end ?: w.endedAt?.plus(delta) ?: start).coerceAtLeast(start)
        return w.copy(
            startedAt = start,
            endedAt = newEnd,
            entries = w.entries.map { e ->
                e.copy(sets = e.sets.map { s -> s.doneAt?.let { s.copy(doneAt = (it + delta).coerceIn(start, newEnd)) } ?: s })
            },
            pauses = WorkoutTime.normalize(WorkoutTime.shifted(w.pauses, delta), start, newEnd),
        )
    }
}

/** A workout as the lists show it. [muscles]: the most trained ones, most sets first. */
data class WorkoutSummary(
    val id: String,
    val name: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val exercises: Int,
    val workingSets: Int,
    val doneSets: Int,
    val volumeKg: Double,
    val muscles: List<Muscle>,
    val exerciseIds: List<String>,
    val pauses: List<WorkoutPause> = emptyList(),
) {
    val inProgress: Boolean get() = endedAt == null

    /** In progress and paused right now. */
    val paused: Boolean get() = inProgress && pauses.isNotEmpty() && pauses.last().end == null

    /** Time trained (pauses left out); null while in progress. */
    val durationMs: Long? get() = endedAt?.let { WorkoutTime.activeMs(startedAt, it.coerceAtLeast(startedAt), pauses) }
}

/** Workouts, working sets and volume of the current calendar week, and the days trained. */
data class WeekSummary(
    val start: LocalDate,
    val workouts: Int,
    val workingSets: Int,
    val volumeKg: Double,
    val days: Set<LocalDate>,
)

object TrainingSummaries {
    /** Workouts the lists show: the one in progress and every one with a completed set. */
    fun isVisible(w: Workout): Boolean = w.inProgress || w.doneSets > 0

    fun workingSets(w: Workout): Int = w.entries.sumOf { e -> e.sets.count { it.isWorking } }

    /** [load] counts the weight really moved by bodyweight and assisted sets in the volume. */
    fun summary(w: Workout, exercise: (String) -> Exercise, load: LoadModel, maxMuscles: Int = 3): WorkoutSummary = WorkoutSummary(
        id = w.id,
        name = w.name?.trim()?.takeIf { it.isNotEmpty() },
        startedAt = w.startedAt,
        endedAt = w.endedAt,
        exercises = w.entries.size,
        workingSets = workingSets(w),
        doneSets = w.doneSets,
        volumeKg = ExerciseHistory.workoutVolumeKg(w, exercise, load),
        muscles = ExerciseHistory.workoutMuscles(w, exercise).entries
            .sortedWith(compareByDescending<Map.Entry<Muscle, Double>> { it.value }.thenBy { it.key.ordinal })
            .take(maxMuscles)
            .map { it.key },
        exerciseIds = w.entries.map { it.exerciseId },
        pauses = w.pauses,
    )

    /** First day of the week containing [today], for a locale whose weeks start on [firstDay]. */
    fun weekStart(today: LocalDate, firstDay: DayOfWeek): LocalDate = today.with(TemporalAdjusters.previousOrSame(firstDay))

    fun week(
        workouts: List<Workout>,
        today: LocalDate,
        firstDay: DayOfWeek,
        zone: ZoneId,
        exercise: (String) -> Exercise,
        load: LoadModel,
    ): WeekSummary {
        val start = weekStart(today, firstDay)
        val end = start.plusDays(7)
        val inWeek = workouts.filter { w -> w.doneSets > 0 && w.date(zone).let { !it.isBefore(start) && it.isBefore(end) } }
        return WeekSummary(
            start = start,
            workouts = inWeek.size,
            workingSets = inWeek.sumOf { workingSets(it) },
            volumeKg = inWeek.sumOf { ExerciseHistory.workoutVolumeKg(it, exercise, load) },
            days = inWeek.map { it.date(zone) }.toSet(),
        )
    }

    /**
     * Finished workouts worth repeating, most recent first: one per name (or per list of exercises
     * when unnamed), so three "Pierna" days offer "Pierna" once.
     */
    fun templates(workouts: List<Workout>, limit: Int = 4): List<Workout> =
        workouts.asReversed()
            .asSequence()
            .filter { !it.inProgress && it.doneSets > 0 }
            .distinctBy { w -> w.name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: w.entries.joinToString(",") { it.exerciseId } }
            .take(limit)
            .toList()

    /**
     * Workouts logged for another day that never got an exercise (the page was left by a process
     * death, or never opened) and were last changed more than [graceMs] before [now]: nothing to keep.
     */
    fun abandonedLogs(workouts: List<Workout>, now: Long, graceMs: Long): List<Workout> =
        workouts.filter { !it.inProgress && it.entries.isEmpty() && it.updatedAt < now - graceMs }

    /** The last finished workout of [day], if any. */
    fun finishedOn(workouts: List<Workout>, day: LocalDate, zone: ZoneId): Workout? =
        workouts.lastOrNull { !it.inProgress && it.doneSets > 0 && it.date(zone) == day }

    fun dayOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}

/** Personal records each set of a workout beat when it was done (see [ExerciseHistory.newRecords]). */
object WorkoutRecords {
    /**
     * For every working set of [w], the records it beats compared with the same exercise's
     * working sets in earlier workouts and the sets before it in [w]. [load] gives each set the
     * weight it really moved (body weight minus assistance, plus extra load...).
     */
    fun forWorkout(workouts: List<Workout>, w: Workout, exercise: (String) -> Exercise, load: LoadModel): Map<String, Set<RecordKind>> {
        val ids = w.entries.map { it.exerciseId }.toSet()
        val seen = HashMap<String, MutableList<WorkoutSet>>()
        // When each set was done, for the body weight of that day (a set without a time: its workout's start).
        val times = HashMap<String, Long>()
        for (other in workouts) {
            val earlier = other.startedAt < w.startedAt || (other.startedAt == w.startedAt && other.id < w.id)
            if (other.id != w.id && !earlier) continue
            for (e in other.entries) {
                if (e.exerciseId !in ids) continue
                for (s in e.sets) times[s.id] = s.doneAt ?: other.startedAt
                if (other.id != w.id) seen.getOrPut(e.exerciseId) { ArrayList() } += e.sets.filter { it.isWorking }
            }
        }
        val result = HashMap<String, Set<RecordKind>>()
        for (e in w.entries) {
            val before = seen.getOrPut(e.exerciseId) { ArrayList() }
            val ex = exercise(e.exerciseId)
            // Stretches are logged by time but a longer hold is no personal record.
            if (!TrainingMath.hasRecords(ex)) continue
            val loadOf = { s: WorkoutSet -> load.loadKg(ex, s, times[s.id] ?: s.doneAt ?: w.startedAt) }
            for (s in e.sets) {
                if (!s.isWorking) continue
                val records = ExerciseHistory.newRecords(s, ex.kind, before, loadOf)
                if (records.isNotEmpty()) result[s.id] = records
                before += s
            }
        }
        return result
    }
}

/** What the progress chart of an exercise can plot. */
enum class ProgressMetric {
    E1RM,
    HEAVIEST,
    VOLUME,
    MAX_REPS,
    TOTAL_REPS,
    LONGEST,
    TOTAL_TIME,
    DISTANCE,

    /** Assisted machines: the least counterweight used (lower is better). */
    LEAST_ASSISTANCE,
    ;

    /** The session's value: kilograms, repetitions, seconds or metres; null when it has none. */
    fun of(session: ExerciseSession): Double? = when (this) {
        E1RM -> session.bestE1rm
        HEAVIEST -> session.topWeightKg
        VOLUME -> session.volumeKg.takeIf { it > 0 }
        MAX_REPS -> session.maxReps?.takeIf { it > 0 }?.toDouble()
        TOTAL_REPS -> session.totalReps.takeIf { it > 0 }?.toDouble()
        LONGEST -> session.maxDurationSec?.takeIf { it > 0 }?.toDouble()
        TOTAL_TIME -> session.totalDurationSec.takeIf { it > 0 }?.toDouble()
        DISTANCE -> session.totalDistanceM.takeIf { it > 0 }
        LEAST_ASSISTANCE -> session.leastAssistanceKg
    }

    /** Shown as a weight in the user's unit. */
    val isLoad: Boolean get() = this == E1RM || this == HEAVIEST || this == VOLUME || this == LEAST_ASSISTANCE

    /** Computed from the weight really moved: bodyweight and assisted exercises need the body weight for it. */
    val usesMovedLoad: Boolean get() = this == E1RM || this == HEAVIEST || this == VOLUME

    val isTime: Boolean get() = this == LONGEST || this == TOTAL_TIME

    companion object {
        /**
         * Metrics worth plotting, the most telling first. Bodyweight exercises whose load is a share
         * of the body weight ([hasBodyweightFactor]: pull-ups, dips) get a strength curve (e1RM with
         * the body weight); the others (crunches...) only their extra load.
         */
        fun forKind(kind: ExerciseKind, hasBodyweightFactor: Boolean): List<ProgressMetric> = when (kind) {
            ExerciseKind.WEIGHT_REPS -> listOf(E1RM, HEAVIEST, VOLUME, MAX_REPS)
            ExerciseKind.BODYWEIGHT_REPS ->
                if (hasBodyweightFactor) listOf(MAX_REPS, E1RM, TOTAL_REPS) else listOf(MAX_REPS, TOTAL_REPS, HEAVIEST)
            ExerciseKind.ASSISTED_REPS -> listOf(E1RM, LEAST_ASSISTANCE, MAX_REPS)
            ExerciseKind.DURATION -> listOf(LONGEST, TOTAL_TIME, HEAVIEST)
            ExerciseKind.CARDIO -> listOf(TOTAL_TIME, DISTANCE)
        }

        /** [forKind], without a distance chart for a sport that logs none (football, yoga...). */
        fun forExercise(exercise: Exercise): List<ProgressMetric> {
            val metrics = forKind(exercise.kind, exercise.bodyweightFactor != null)
            return if (TrainingMath.isSport(exercise) && !SportsCatalog.coversDistance(exercise)) metrics - DISTANCE else metrics
        }
    }
}
