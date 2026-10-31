package com.enjoyyourtime.globalfitness.training.model

import com.enjoyyourtime.globalfitness.UiText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Muscle groups shown on the body map and used to tag exercises. Codes are stored in the database
 * and in the cloud backup: never rename them.
 *
 * [weeklySets] is the usual hypertrophy range of hard sets per week (lower bound: minimum that
 * still makes progress; upper bound: most people stop gaining beyond it). Small muscles that get a
 * lot of indirect work have a lower range.
 */
enum class Muscle(val code: String, val weeklySets: IntRange) {
    CHEST("chest", 10..20),
    SHOULDERS("shoulders", 10..20),
    BICEPS("biceps", 8..16),
    TRICEPS("triceps", 8..16),
    FOREARMS("forearms", 4..12),
    ABS("abs", 6..16),
    OBLIQUES("obliques", 4..12),
    TRAPS("traps", 6..16),
    LATS("lats", 10..20),
    LOWER_BACK("lower_back", 4..12),
    GLUTES("glutes", 8..16),
    QUADS("quads", 10..20),
    HAMSTRINGS("hamstrings", 8..16),
    ADDUCTORS("adductors", 4..12),
    CALVES("calves", 6..16),
    NECK("neck", 2..10),
    ;

    companion object {
        fun fromCode(code: String): Muscle? = entries.firstOrNull { it.code == code }

        /** "chest,triceps" → set; unknown codes are skipped (newer app versions may add muscles). */
        fun decode(text: String?): Set<Muscle> =
            text.orEmpty().split(',').mapNotNull { fromCode(it.trim()) }.toSet()

        fun encode(muscles: Collection<Muscle>): String = muscles.sortedBy { it.ordinal }.joinToString(",") { it.code }
    }
}

/**
 * What the exercise is done with. Codes are stored: never rename them. [SPORT] is a sport or an
 * outdoor activity (football, a walk, yoga): logged as [ExerciseKind.CARDIO], it is "Sports and
 * activities" in the catalog. An app older than it reads its code as [OTHER]; so that 1.3.0 never
 * writes an own sport back as "other", its cloud document has a higher schema version
 * ([com.enjoyyourtime.globalfitness.cloud.TrainingCodec.SPORT_SCHEMA_VERSION]).
 */
enum class Equipment(val code: String) {
    BARBELL("barbell"),
    DUMBBELL("dumbbell"),
    KETTLEBELL("kettlebell"),
    MACHINE("machine"),
    CABLE("cable"),
    SMITH_MACHINE("smith"),
    BODYWEIGHT("bodyweight"),
    BAND("band"),
    CARDIO_MACHINE("cardio"),
    SPORT("sport"),
    OTHER("other"),
    ;

    companion object {
        fun fromCode(code: String?): Equipment = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

/**
 * How the sets of an exercise are logged. Codes are stored: never rename them.
 *
 * - [WEIGHT_REPS]: load × repetitions (bench press, leg press...).
 * - [BODYWEIGHT_REPS]: repetitions, optionally with extra load (pull-ups, dips, push-ups).
 * - [ASSISTED_REPS]: repetitions on an assisted machine; the set's weight is the ASSISTANCE
 *   (counterweight), so less weight means a harder set. The weight actually lifted is the user's
 *   body weight minus the assistance (see the training load model).
 * - [DURATION]: seconds, optionally with load (plank, farmer's carry).
 * - [CARDIO]: time, and optionally distance, machine level and calories (treadmill, bike...); also
 *   sports and activities ([Equipment.SPORT]), which have no machine level.
 */
enum class ExerciseKind(val code: String) {
    WEIGHT_REPS("weight_reps"),
    BODYWEIGHT_REPS("bodyweight_reps"),
    ASSISTED_REPS("assisted_reps"),
    DURATION("duration"),
    CARDIO("cardio"),
    ;

    /** The set has a weight field (for [ASSISTED_REPS] it holds the assistance, not a load). */
    val usesLoad: Boolean get() = this != CARDIO
    val usesReps: Boolean get() = this == WEIGHT_REPS || this == BODYWEIGHT_REPS || this == ASSISTED_REPS
    val usesDuration: Boolean get() = this == DURATION || this == CARDIO

    /** The weight field is help, not load: lower is better. */
    val isAssisted: Boolean get() = this == ASSISTED_REPS

    companion object {
        fun fromCode(code: String?): ExerciseKind = entries.firstOrNull { it.code == code } ?: WEIGHT_REPS
    }
}

/** Unit the user loads plates in. Values are always stored in kilograms. */
enum class LoadUnit(val code: String, val kgPerUnit: Double) {
    KG("kg", 1.0),
    LB("lb", 0.45359237),
    ;

    fun toKg(value: Double): Double = value * kgPerUnit

    fun fromKg(kg: Double): Double = kg / kgPerUnit

    companion object {
        fun fromCode(code: String?): LoadUnit? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A built-in exercise of the catalog (see [com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog]).
 * [hcSegment] is the Health Connect `ExerciseSegment.SEGMENT_TYPE_*` of one set, [hcSession] the
 * `ExerciseSessionRecord.EXERCISE_TYPE_*` of a cardio exercise (null: generic strength training).
 */
data class CatalogExercise(
    /** Stable id (stored in workouts and the backup): never rename. Built-in ids never contain ':'. */
    val id: String,
    val nameRes: Int,
    val kind: ExerciseKind,
    val equipment: Equipment,
    val primary: Set<Muscle>,
    val secondary: Set<Muscle> = emptySet(),
    val hcSegment: Int? = null,
    val hcSession: Int? = null,
    /**
     * Share of the body weight moved by a [ExerciseKind.BODYWEIGHT_REPS] or
     * [ExerciseKind.ASSISTED_REPS] exercise (pull-up 1.0, push-up ≈ 0.65). Null when body weight
     * does not define the load (core work, jumps, bands): only extra load counts then.
     */
    val bodyweightFactor: Double? = null,
    /**
     * A stretch or mobility drill, logged by time ([ExerciseKind.DURATION]). It is logged, shown
     * and exported (as a stretching segment) like any exercise, but it is not training load: no
     * hard sets for its muscles, no recovery, no strength trend and no personal records. Its
     * [primary] muscles are the ones it stretches. Only built-in exercises are (see [CustomExercise]).
     */
    val mobility: Boolean = false,
)

/**
 * An exercise or machine the user created. Ids start with [PREFIX]. Known limit: an own exercise
 * cannot be a stretch or mobility drill ([CatalogExercise.mobility]); one logged by time counts as
 * training load like a plank.
 */
data class CustomExercise(
    val id: String,
    val name: String,
    val kind: ExerciseKind,
    val equipment: Equipment,
    val primary: Set<Muscle>,
    val secondary: Set<Muscle>,
    /** Hidden from the picker but kept so old workouts still show its name. */
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val PREFIX = "u:"

        fun isCustomId(id: String): Boolean = id.startsWith(PREFIX)
    }
}

/**
 * The user's own data about any exercise (built-in or custom): machine settings ("seat 4, back 2"),
 * a photo of the machine in their gym and the favorite star.
 */
data class ExerciseNote(
    val exerciseId: String,
    val notes: String?,
    /** File name inside the exercise photo folder (see ExercisePhotos), null when there is none. */
    val photo: String?,
    val favorite: Boolean,
    val updatedAt: Long,
) {
    val isEmpty: Boolean get() = notes.isNullOrBlank() && photo == null && !favorite
}

/** Catalog + custom exercise + the user's note, as the screens use it. */
data class Exercise(
    val id: String,
    val name: UiText,
    val kind: ExerciseKind,
    val equipment: Equipment,
    val primary: Set<Muscle>,
    val secondary: Set<Muscle>,
    val builtIn: Boolean,
    val archived: Boolean = false,
    val notes: String? = null,
    val photo: String? = null,
    val favorite: Boolean = false,
    val hcSegment: Int? = null,
    val hcSession: Int? = null,
    /** See [CatalogExercise.bodyweightFactor]. */
    val bodyweightFactor: Double? = null,
    /** A stretch or mobility drill: not training load (see [CatalogExercise.mobility]). */
    val mobility: Boolean = false,
) {
    val muscles: Set<Muscle> get() = primary + secondary
}

/** One set. Only [done] sets count for statistics; the others are planned (pre-filled) sets. */
data class WorkoutSet(
    val id: String,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val distanceM: Double? = null,
    /** Machine level / resistance on cardio machines. */
    val level: Int? = null,
    val kcal: Int? = null,
    val warmup: Boolean = false,
    val done: Boolean = false,
    val doneAt: Long? = null,
) {
    /** A completed set that is not a warm-up: what statistics count. */
    val isWorking: Boolean get() = done && !warmup
}

/** One exercise inside a workout, with its sets in order. */
data class WorkoutEntry(
    val id: String,
    val exerciseId: String,
    val sets: List<WorkoutSet> = emptyList(),
    val note: String? = null,
)

/**
 * A pause of a workout, from [start] to [end] (epoch ms). [end] is null while it lasts: only the
 * last pause of a workout in progress can be open. Paused time is not training time (see
 * [com.enjoyyourtime.globalfitness.training.domain.WorkoutTime]).
 */
data class WorkoutPause(val start: Long, val end: Long?)

/**
 * A training session. [endedAt] is null while it is in progress. [updatedAt] (client clock) is
 * bumped on every change; the cloud backup keeps the most recent copy of each workout. [pauses]:
 * sorted, inside the workout and not overlapping; start and end stay the real ones, so the time
 * trained is the span minus the pauses.
 */
data class Workout(
    val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val name: String?,
    val note: String?,
    val updatedAt: Long,
    val entries: List<WorkoutEntry> = emptyList(),
    val pauses: List<WorkoutPause> = emptyList(),
) {
    val inProgress: Boolean get() = endedAt == null

    /** In progress and paused right now (its last pause has not ended). */
    val paused: Boolean get() = inProgress && pauses.isNotEmpty() && pauses.last().end == null

    fun date(zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()

    /** Completed sets (including warm-ups). */
    val doneSets: Int get() = entries.sumOf { e -> e.sets.count { it.done } }

    /** Time of the last completed set, else [startedAt]. */
    val lastActivityAt: Long get() = entries.flatMap { it.sets }.mapNotNull { it.doneAt }.maxOrNull() ?: startedAt
}

/**
 * One exercise of a routine and its target: [sets] sets of [reps] repetitions, or of [durationSec]
 * seconds for timed and cardio exercises. Loads are not part of the target: a new workout takes
 * them from the last time the exercise was done.
 */
data class RoutineExercise(
    val exerciseId: String,
    val sets: Int,
    val reps: Int? = null,
    val durationSec: Int? = null,
)

/**
 * A routine the user saved: a reusable set of exercises to start a workout with, or to add to one
 * in a single step. [updatedAt] (client clock) is bumped on every change for the cloud backup.
 */
data class Routine(
    val id: String,
    val name: String,
    val exercises: List<RoutineExercise>,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A built-in routine (see [com.enjoyyourtime.globalfitness.training.catalog.RoutineCatalog]). */
data class CatalogRoutine(
    /** Stable id, never containing ':' (user routines are UUIDs). */
    val id: String,
    val nameRes: Int,
    val exercises: List<RoutineExercise>,
)

/** A built-in or user routine, as the screens use it. */
data class RoutineTemplate(
    val id: String,
    val name: UiText,
    val exercises: List<RoutineExercise>,
    val builtIn: Boolean,
    val note: String? = null,
    val updatedAt: Long = 0,
)

/** A deletion remembered for the cloud backup, so the item is removed on the other phones too. */
data class Tombstone(val kind: String, val id: String, val deletedAt: Long) {
    companion object {
        const val WORKOUT = "workout"
        const val CUSTOM_EXERCISE = "custom_exercise"
        const val NOTE = "note"
        const val ROUTINE = "routine"
    }
}
