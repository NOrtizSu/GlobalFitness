package com.enjoyyourtime.globalfitness.ui.training

import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.data.WorkoutEdits
import com.enjoyyourtime.globalfitness.training.domain.TrainingMath
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.RoutineExercise
import com.enjoyyourtime.globalfitness.training.model.RoutineTemplate
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import java.util.Locale

/** A routine as the lists show it: [muscles] are the most trained ones, most planned sets first. */
data class RoutineSummary(
    val template: RoutineTemplate,
    val exercises: Int,
    val sets: Int,
    val muscles: List<Muscle>,
) {
    val id: String get() = template.id
}

/** One exercise of the routine page with its target. */
data class RoutineItemUi(val target: RoutineExercise, val exercise: Exercise)

/** What the routine page shows. [activeWorkoutId]: the workout in progress the routine can be added to. */
data class RoutineDetail(
    val template: RoutineTemplate,
    val items: List<RoutineItemUi>,
    val sets: Int,
    val muscles: Map<Muscle, Double>,
    val activeWorkoutId: String?,
)

/** Pure rules of the routine screens: muscles worked, list order and the target of a new exercise. */
object RoutineStats {
    const val DEFAULT_SETS = 3
    const val DEFAULT_REPS = 10
    const val DEFAULT_HOLD_SEC = 30
    const val DEFAULT_CARDIO_SEC = 600

    /** A match, a walk, a class: an hour. */
    const val DEFAULT_SPORT_SEC = 3_600

    /**
     * Planned hard sets per muscle (primary 1, secondary ½; cardio and stretches count for none), the same
     * weighting as the workout summary, so a routine and the workout it starts read alike.
     */
    fun muscles(exercises: List<RoutineExercise>, exercise: (String) -> Exercise): Map<Muscle, Double> {
        val result = LinkedHashMap<Muscle, Double>()
        for (e in exercises) {
            val ex = exercise(e.exerciseId)
            if (!TrainingMath.countsForMuscles(ex)) continue
            for (m in ex.primary) result[m] = (result[m] ?: 0.0) + e.sets * TrainingMath.PRIMARY_SHARE
            for (m in ex.secondary - ex.primary) result[m] = (result[m] ?: 0.0) + e.sets * TrainingMath.SECONDARY_SHARE
        }
        return result
    }

    /** The [max] muscles with the most sets; ties keep the body order of [Muscle]. */
    fun topMuscles(muscles: Map<Muscle, Double>, max: Int = 3): List<Muscle> =
        muscles.entries
            .sortedWith(compareByDescending<Map.Entry<Muscle, Double>> { it.value }.thenBy { it.key.ordinal })
            .take(max)
            .map { it.key }

    fun summary(template: RoutineTemplate, exercise: (String) -> Exercise): RoutineSummary = RoutineSummary(
        template = template,
        exercises = template.exercises.size,
        sets = template.exercises.sumOf { it.sets },
        muscles = topMuscles(muscles(template.exercises, exercise), max = 2),
    )

    /**
     * The user's routines first (in the repository's order, newest first), then the built-in ones;
     * the routine used last leads its group, one tap away.
     */
    fun ordered(templates: List<RoutineTemplate>, lastUsedId: String?): List<RoutineTemplate> {
        val (own, builtIn) = templates.partition { !it.builtIn }
        // A stable sort: the others keep their order.
        fun leadWithLast(list: List<RoutineTemplate>) = list.sortedByDescending { it.id == lastUsedId }
        return leadWithLast(own) + leadWithLast(builtIn)
    }

    /**
     * The target of an exercise added to a routine: as many sets as its last session's working sets
     * and the reps (or time) done most often then; never done, 3 × 10 (timed 3 × 0:30, cardio
     * 1 × 10:00, a [sport] or activity 1 × 60:00). [previous] are the sets of its last session.
     */
    fun defaultTarget(exerciseId: String, kind: ExerciseKind, previous: List<WorkoutSet>, sport: Boolean = false): RoutineExercise {
        val working = previous.filter { !it.warmup }
        val sets = if (working.isEmpty()) {
            if (kind == ExerciseKind.CARDIO) 1 else DEFAULT_SETS
        } else {
            working.size.coerceAtMost(WorkoutEdits.MAX_ROUTINE_SETS)
        }
        return if (kind.usesDuration) {
            val fallback = when {
                kind != ExerciseKind.CARDIO -> DEFAULT_HOLD_SEC
                sport -> DEFAULT_SPORT_SEC
                else -> DEFAULT_CARDIO_SEC
            }
            RoutineExercise(exerciseId, sets, durationSec = working.mapNotNull { s -> s.durationSec?.takeIf { it > 0 } }.mostCommon() ?: fallback)
        } else {
            RoutineExercise(exerciseId, sets, reps = working.mapNotNull { s -> s.reps?.takeIf { it > 0 } }.mostCommon() ?: DEFAULT_REPS)
        }
    }

    /**
     * Whether a routine already has exactly these exercises in this order: offering to save the
     * workout (e.g. one started from that routine) as a new routine would only duplicate it.
     */
    fun matchesRoutine(exerciseIds: List<String>, templates: List<RoutineTemplate>): Boolean {
        val ids = exerciseIds.distinct()
        return ids.isNotEmpty() && templates.any { t -> t.exercises.map { it.exerciseId } == ids }
    }

    /** How many of the routine's exercises a workout with [present] exercises does not have yet. */
    fun newExercises(template: RoutineTemplate, present: Set<String>): Int =
        template.exercises.map { it.exerciseId }.distinct().count { it !in present }

    private fun <T> List<T>.mostCommon(): T? = groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}

/** Locale-aware texts of routine targets, free of Android resources (unit tests). */
object RoutineFormat {
    private const val TIMES = "×"

    /** Digits a reps target field keeps (999 at most, like a set cell). */
    const val MAX_REPS_DIGITS = 3

    /**
     * "3 × 10", or "3 × 0:45" for timed and cardio exercises. Null when the exercise has no target
     * value (its sets take last time's): the caller shows "3 sets".
     */
    fun target(e: RoutineExercise, kind: ExerciseKind, locale: Locale): String? {
        val value = targetValue(e, kind, locale) ?: return null
        return "${TrainingFormat.count(e.sets, locale)} $TIMES $value"
    }

    /** The target's reps ("10") or time ("0:45"), the field [kind] uses; null when it has none. */
    fun targetValue(e: RoutineExercise, kind: ExerciseKind, locale: Locale): String? =
        if (kind.usesDuration) {
            e.durationSec?.takeIf { it > 0 }?.let { TrainingFormat.duration(it, locale) }
        } else {
            e.reps?.takeIf { it > 0 }?.let { TrainingFormat.count(it, locale) }
        }

    /** A typed reps target: digits only (locale digits accepted); empty or 0 means none. */
    fun parseReps(text: String): Int? =
        TrainingFormat.ascii(text).filter { it in '0'..'9' }.take(MAX_REPS_DIGITS).toIntOrNull()?.takeIf { it > 0 }
}

/**
 * The fields of the routine editor. [exercises] never holds an exercise twice (the edits below
 * keep it so), which lets every edit find an exercise by its id.
 */
data class RoutineForm(val name: String, val note: String, val exercises: List<RoutineExercise>) {
    val nameMissing: Boolean get() = name.isBlank()
    val exercisesMissing: Boolean get() = exercises.isEmpty()
    val isValid: Boolean get() = !nameMissing && !exercisesMissing

    /**
     * Whether leaving would lose something compared with the form as it [opened]. Spaces around the
     * texts and differences the save cleans away (an empty or zero target) do not count.
     */
    fun isChangedFrom(opened: RoutineForm): Boolean =
        name.trim() != opened.name.trim() ||
            note.trim() != opened.note.trim() ||
            TrainingRepository.cleanRoutine(exercises) != TrainingRepository.cleanRoutine(opened.exercises)

    /** Adds the exercises not in the routine yet, at the end, each with its [target]. */
    fun add(exerciseIds: List<String>, target: (String) -> RoutineExercise): RoutineForm {
        val present = exercises.mapTo(HashSet()) { it.exerciseId }
        val added = exerciseIds.distinct().filter { it !in present }.map { id -> target(id).copy(exerciseId = id) }
        return if (added.isEmpty()) this else copy(exercises = exercises + added)
    }

    fun remove(exerciseId: String): RoutineForm = copy(exercises = exercises.filterNot { it.exerciseId == exerciseId })

    /** Moves an exercise [delta] places up (negative) or down; nothing at the ends. */
    fun move(exerciseId: String, delta: Int): RoutineForm {
        val from = exercises.indexOfFirst { it.exerciseId == exerciseId }
        val to = from + delta
        if (from < 0 || to !in exercises.indices || from == to) return this
        return copy(exercises = exercises.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun update(exerciseId: String, transform: (RoutineExercise) -> RoutineExercise): RoutineForm =
        copy(exercises = exercises.map { if (it.exerciseId == exerciseId) transform(it).copy(exerciseId = exerciseId) else it })

    /** Sets kept within 1..[WorkoutEdits.MAX_ROUTINE_SETS]. */
    fun withSets(exerciseId: String, sets: Int): RoutineForm =
        update(exerciseId) { it.copy(sets = sets.coerceIn(1, WorkoutEdits.MAX_ROUTINE_SETS)) }

    /** Plain values for the saved instance state (no target is stored as -1). */
    fun toSaved(): List<Any> = listOf(
        name,
        note,
        ArrayList(exercises.map { it.exerciseId }),
        exercises.map { it.sets }.toIntArray(),
        exercises.map { it.reps ?: NONE }.toIntArray(),
        exercises.map { it.durationSec ?: NONE }.toIntArray(),
    )

    companion object {
        private const val NONE = -1

        val EMPTY = RoutineForm(name = "", note = "", exercises = emptyList())

        /** Reads back [toSaved]. */
        fun fromSaved(values: List<Any>): RoutineForm {
            @Suppress("UNCHECKED_CAST")
            val ids = values[2] as List<String>
            val sets = values[3] as IntArray
            val reps = values[4] as IntArray
            val durations = values[5] as IntArray
            return RoutineForm(
                name = values[0] as String,
                note = values[1] as String,
                exercises = ids.indices.map { i ->
                    RoutineExercise(ids[i], sets[i], reps = reps[i].takeIf { it != NONE }, durationSec = durations[i].takeIf { it != NONE })
                },
            )
        }

        fun of(name: String, note: String?, exercises: List<RoutineExercise>) =
            RoutineForm(name = name, note = note.orEmpty(), exercises = exercises.distinctBy { it.exerciseId })
    }
}

/** Pure workout edits of the routine actions. */
object RoutineEdits {
    /**
     * Undoes adding a routine: takes the added entries out again, except any that got a completed
     * set meanwhile (the user already trained it).
     */
    fun removeAdded(w: Workout, entryIds: Collection<String>): Workout {
        val ids = entryIds.toSet()
        return w.copy(entries = w.entries.filterNot { e -> e.id in ids && e.sets.none { it.done } })
    }
}
