package com.enjoyyourtime.globalfitness.training.data

import com.enjoyyourtime.globalfitness.training.model.RoutineExercise
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet

/**
 * Pure edits of a [Workout], used with [TrainingRepository.updateWorkout]. They never change ids
 * of existing entries or sets, so the cloud backup and Health Connect see the same items.
 */
object WorkoutEdits {

    /** Copies [template]'s exercises with their working sets as planned (not done) sets. */
    fun planFrom(template: Workout, newId: () -> String): List<WorkoutEntry> =
        template.entries.map { entry ->
            val source = entry.sets.filter { it.done && !it.warmup }.ifEmpty { entry.sets }
            WorkoutEntry(
                id = newId(),
                exerciseId = entry.exerciseId,
                sets = source.map { it.copy(id = newId(), done = false, doneAt = null) },
                note = entry.note,
            )
        }

    /**
     * Turns a routine into planned entries: each exercise gets its target number of sets, with the
     * load (and reps / time when the routine has no target) of the last time it was done, set by set
     * ([previous] gives those sets for an exercise id, empty if never done). A distance follows the
     * planned time at last time's pace ([plannedDistance]); the machine level is copied as it was.
     */
    fun planFromRoutine(
        exercises: List<RoutineExercise>,
        previous: (String) -> List<WorkoutSet>,
        newId: () -> String,
    ): List<WorkoutEntry> = exercises.map { target ->
        val last = previous(target.exerciseId).filter { !it.warmup }
        val count = target.sets.coerceIn(1, MAX_ROUTINE_SETS)
        WorkoutEntry(
            id = newId(),
            exerciseId = target.exerciseId,
            sets = List(count) { i ->
                val model = last.getOrNull(i) ?: last.lastOrNull()
                val durationSec = target.durationSec ?: model?.durationSec
                WorkoutSet(
                    id = newId(),
                    weightKg = model?.weightKg,
                    reps = target.reps ?: model?.reps,
                    durationSec = durationSec,
                    distanceM = plannedDistance(model, durationSec),
                    level = model?.level,
                )
            },
        )
    }

    /**
     * The distance planned for [durationSec] from [model] (last time's set): its distance for the same
     * time, else scaled to the new time so the pace stays last time's (to whole metres). A 10:00
     * target after 5 km in 30:00 plans 1.67 km, never 5 km in 10:00 (30 km/h). Without last time's
     * time there is no pace to keep: no distance.
     */
    internal fun plannedDistance(model: WorkoutSet?, durationSec: Int?): Double? {
        val distance = model?.distanceM ?: return null
        val was = model.durationSec
        return when {
            durationSec == was -> distance
            durationSec == null || was == null || was <= 0 -> null
            else -> Math.round(distance * durationSec / was).toDouble()
        }
    }

    /**
     * Adds a routine's exercises at the end of the workout in one step. Exercises the workout already
     * has are skipped, so adding a routine twice or on top of a repeated workout never duplicates.
     */
    fun addRoutine(
        w: Workout,
        exercises: List<RoutineExercise>,
        previous: (String) -> List<WorkoutSet>,
        newId: () -> String,
    ): Workout {
        val present = w.entries.mapTo(HashSet()) { it.exerciseId }
        val missing = exercises.filter { it.exerciseId !in present }.distinctBy { it.exerciseId }
        if (missing.isEmpty()) return w
        return w.copy(entries = w.entries + planFromRoutine(missing, previous, newId))
    }

    /**
     * The routine a workout would make: its exercises in order, each with its number of working sets
     * (at least one) and the reps / time it was done for most often.
     */
    fun routineFrom(w: Workout): List<RoutineExercise> = w.entries.map { e ->
        val working = e.sets.filter { it.done && !it.warmup }.ifEmpty { e.sets.filter { !it.warmup } }.ifEmpty { e.sets }
        RoutineExercise(
            exerciseId = e.exerciseId,
            sets = working.size.coerceIn(1, MAX_ROUTINE_SETS),
            reps = working.mapNotNull { it.reps }.mostCommon(),
            durationSec = working.mapNotNull { it.durationSec }.mostCommon(),
        )
    }

    /** Upper bound of a routine exercise's sets (protects against a mistyped "100"). */
    const val MAX_ROUTINE_SETS = 20

    private fun <T> List<T>.mostCommon(): T? = groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /**
     * Adds an exercise at the end. Its first set is pre-filled from [previous] (the sets of the
     * last time it was done), otherwise it starts with one empty set.
     */
    fun addExercise(w: Workout, exerciseId: String, previous: List<WorkoutSet>, newId: () -> String): Workout {
        val planned = previous.filter { !it.warmup }.ifEmpty { previous }
            .map { it.copy(id = newId(), done = false, doneAt = null) }
            .ifEmpty { listOf(WorkoutSet(id = newId())) }
        return w.copy(entries = w.entries + WorkoutEntry(id = newId(), exerciseId = exerciseId, sets = planned))
    }

    fun removeEntry(w: Workout, entryId: String): Workout = w.copy(entries = w.entries.filterNot { it.id == entryId })

    /** Moves an entry from one position to another (drag to reorder). */
    fun moveEntry(w: Workout, from: Int, to: Int): Workout {
        if (from !in w.entries.indices || to !in w.entries.indices || from == to) return w
        val list = w.entries.toMutableList()
        list.add(to, list.removeAt(from))
        return w.copy(entries = list)
    }

    fun updateEntry(w: Workout, entryId: String, transform: (WorkoutEntry) -> WorkoutEntry): Workout =
        w.copy(entries = w.entries.map { if (it.id == entryId) transform(it).copy(id = it.id) else it })

    /** Adds a set copying the values of the last one (not done). */
    fun addSet(w: Workout, entryId: String, newId: () -> String): Workout = updateEntry(w, entryId) { e ->
        val last = e.sets.lastOrNull()
        val set = last?.copy(id = newId(), done = false, doneAt = null, warmup = false) ?: WorkoutSet(id = newId())
        e.copy(sets = e.sets + set)
    }

    fun updateSet(w: Workout, entryId: String, setId: String, transform: (WorkoutSet) -> WorkoutSet): Workout =
        updateEntry(w, entryId) { e -> e.copy(sets = e.sets.map { if (it.id == setId) transform(it).copy(id = it.id) else it }) }

    fun removeSet(w: Workout, entryId: String, setId: String): Workout =
        updateEntry(w, entryId) { e -> e.copy(sets = e.sets.filterNot { it.id == setId }) }

    /** Marks a set done (now) or not done. */
    fun setDone(w: Workout, entryId: String, setId: String, done: Boolean, now: Long): Workout =
        updateSet(w, entryId, setId) { it.copy(done = done, doneAt = if (done) now else null) }
}
