package com.enjoyyourtime.globalfitness.training.domain

import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import java.time.LocalDate
import java.time.ZoneId

/** Per-set arithmetic shared by the statistics. */
object TrainingMath {
    /** Above this many reps a one-rep-max estimate is too unreliable to show. */
    const val MAX_REPS_FOR_1RM = 12

    /**
     * Estimated one-rep max (Epley: w × (1 + reps / 30); a single is the weight itself). Null for
     * sets without load or with more than [MAX_REPS_FOR_1RM] reps.
     */
    fun e1rm(weightKg: Double?, reps: Int?): Double? {
        if (weightKg == null || reps == null || weightKg <= 0.0 || reps <= 0 || reps > MAX_REPS_FOR_1RM) return null
        return if (reps == 1) weightKg else weightKg * (1 + reps / 30.0)
    }

    /** From the set's weight field only: use [LoadModel] for bodyweight and assisted exercises. */
    fun e1rm(set: WorkoutSet): Double? = e1rm(set.weightKg, set.reps)

    /** Weight field × reps of a set (kg); 0 without either. Use [LoadModel] for bodyweight and assisted exercises. */
    fun volume(set: WorkoutSet): Double {
        val w = set.weightKg ?: return 0.0
        val r = set.reps ?: return 0.0
        return if (w > 0 && r > 0) w * r else 0.0
    }

    /**
     * Whether a completed set counts as a hard set for a muscle: strength kinds always, cardio
     * never (it trains the heart, not a muscle group). Callers use the [Exercise] overload.
     */
    private fun countsForMuscles(kind: ExerciseKind): Boolean = kind != ExerciseKind.CARDIO

    /**
     * Whether [exercise]'s completed sets are training load for its muscles (hard sets, recovery,
     * body shape, balanced weeks): as [countsForMuscles] by kind, and never for a stretch or
     * mobility drill ([Exercise.mobility]), which is logged but trains nothing to recover from.
     */
    fun countsForMuscles(exercise: Exercise): Boolean = countsForMuscles(exercise.kind) && !exercise.mobility

    /** Whether [exercise] has personal records and strength trends: every exercise but stretches and mobility drills. */
    fun hasRecords(exercise: Exercise): Boolean = !exercise.mobility

    /**
     * A sport or activity ([Equipment.SPORT]) logged by time: no machine level, no rest timer after
     * its sets, titled with its own name in Health Connect. An own exercise with that equipment but
     * logged another way (reps) is treated like any exercise of its kind.
     */
    fun isSport(exercise: Exercise): Boolean = exercise.equipment == Equipment.SPORT && exercise.kind == ExerciseKind.CARDIO

    /** Contribution of a set to its exercise's muscles: 1 for primary, ½ for secondary. */
    const val PRIMARY_SHARE = 1.0
    const val SECONDARY_SHARE = 0.5
}

/**
 * The working sets of one exercise inside one workout. [loads] is the weight each set actually
 * moved (see [LoadModel]; same order as [sets]); statistics use it, never the raw weight field, so
 * assisted machines and bodyweight exercises compare like with like.
 */
data class ExerciseSession(
    val workoutId: String,
    val entryId: String,
    val exerciseId: String,
    val date: LocalDate,
    /** Time of the session (last completed set, else the workout start). */
    val at: Long,
    val sets: List<WorkoutSet>,
    val kind: ExerciseKind = ExerciseKind.WEIGHT_REPS,
    val loads: List<Double?> = sets.map { s -> s.weightKg?.takeIf { it > 0.0 } },
) {
    private fun e1rmAt(i: Int): Double? = TrainingMath.e1rm(loads.getOrNull(i), sets[i].reps)

    val bestE1rm: Double? get() = sets.indices.mapNotNull { e1rmAt(it) }.maxOrNull()

    /** Heaviest weight moved (body weight included for bodyweight / assisted exercises). */
    val topWeightKg: Double? get() = loads.filterNotNull().filter { it > 0 }.maxOrNull()

    val volumeKg: Double get() = sets.indices.sumOf { i -> (loads.getOrNull(i) ?: 0.0) * (sets[i].reps ?: 0).coerceAtLeast(0) }
    val totalReps: Int get() = sets.sumOf { it.reps ?: 0 }
    val maxReps: Int? get() = sets.mapNotNull { it.reps }.maxOrNull()
    val totalDurationSec: Int get() = sets.sumOf { it.durationSec ?: 0 }
    val maxDurationSec: Int? get() = sets.mapNotNull { it.durationSec }.maxOrNull()
    val totalDistanceM: Double get() = sets.sumOf { it.distanceM ?: 0.0 }

    /** Lowest assistance used on an assisted machine (null for other kinds). */
    val leastAssistanceKg: Double? get() = if (kind.isAssisted) sets.mapNotNull { it.weightKg }.minOrNull() else null

    /** The set with the highest estimated 1RM, else the heaviest, else the one with most reps. */
    val bestSet: WorkoutSet?
        get() = sets.indices.maxWithOrNull(
            compareBy<Int> { e1rmAt(it) ?: 0.0 }
                .thenBy { loads.getOrNull(it) ?: 0.0 }
                .thenBy { sets[it].reps ?: 0 }
                .thenBy { sets[it].durationSec ?: 0 },
        )?.let { sets[it] }
}

/** Personal records of one exercise. Every value is null when no set qualifies. */
data class PersonalRecords(
    val bestE1rmKg: Double? = null,
    val heaviestKg: Double? = null,
    val mostReps: Int? = null,
    val bestVolumeKg: Double? = null,
    val longestSec: Int? = null,
    val longestDistanceM: Double? = null,
    /** Assisted machines: the least assistance ever used. */
    val leastAssistanceKg: Double? = null,
)

/** Kinds of personal record a newly completed set can beat. */
enum class RecordKind { E1RM, WEIGHT, REPS, DURATION, ASSISTANCE }

object ExerciseHistory {
    /**
     * Sessions of [exerciseId] (working sets only, sessions without any skipped), oldest first.
     * Workouts in progress are included: the screen shows today's sets as they are done.
     *
     * Pass the [exercise] and a [load] model built from the body weight so assisted and bodyweight
     * exercises get their real loads; without them each set's weight field is taken as its load.
     */
    fun sessions(
        workouts: List<Workout>,
        exerciseId: String,
        zone: ZoneId = ZoneId.systemDefault(),
        exercise: Exercise? = null,
        load: LoadModel = LoadModel.WITHOUT_BODYWEIGHT,
    ): List<ExerciseSession> =
        workouts.flatMap { w ->
            w.entries.filter { it.exerciseId == exerciseId }.mapNotNull { e ->
                val working = e.sets.filter { it.isWorking }
                if (working.isEmpty()) {
                    null
                } else {
                    ExerciseSession(
                        workoutId = w.id,
                        entryId = e.id,
                        exerciseId = exerciseId,
                        date = w.date(zone),
                        at = working.mapNotNull { it.doneAt }.maxOrNull() ?: w.startedAt,
                        sets = working,
                        kind = exercise?.kind ?: ExerciseKind.WEIGHT_REPS,
                        loads = working.map { s ->
                            if (exercise == null) s.weightKg?.takeIf { it > 0.0 } else load.loadKg(exercise, s, s.doneAt ?: w.startedAt)
                        },
                    )
                }
            }
        }.sortedBy { it.at }

    /** Every session of every exercise, grouped by exercise id (each list oldest first). */
    fun allSessions(
        workouts: List<Workout>,
        zone: ZoneId = ZoneId.systemDefault(),
        exercises: ((String) -> Exercise)? = null,
        load: LoadModel = LoadModel.WITHOUT_BODYWEIGHT,
    ): Map<String, List<ExerciseSession>> {
        val ids = workouts.flatMap { w -> w.entries.map { it.exerciseId } }.toSet()
        return ids.associateWith { sessions(workouts, it, zone, exercises?.invoke(it), load) }.filterValues { it.isNotEmpty() }
    }

    fun records(sessions: List<ExerciseSession>): PersonalRecords {
        if (sessions.isEmpty()) return PersonalRecords()
        val sets = sessions.flatMap { it.sets }
        return PersonalRecords(
            bestE1rmKg = sessions.mapNotNull { it.bestE1rm }.maxOrNull(),
            heaviestKg = sessions.mapNotNull { it.topWeightKg }.maxOrNull(),
            mostReps = sets.mapNotNull { it.reps }.filter { it > 0 }.maxOrNull(),
            bestVolumeKg = sessions.map { it.volumeKg }.filter { it > 0 }.maxOrNull(),
            longestSec = sets.mapNotNull { it.durationSec }.filter { it > 0 }.maxOrNull(),
            longestDistanceM = sessions.map { it.totalDistanceM }.filter { it > 0 }.maxOrNull(),
            leastAssistanceKg = sessions.mapNotNull { it.leastAssistanceKg }.minOrNull(),
        )
    }

    /**
     * Records [set] beats compared with every working set done before it ([before]: sessions of
     * the same exercise in earlier workouts plus the earlier sets of the current one). Nothing is a
     * record the first time an exercise is done. [load] gives each set's real load (see
     * [LoadModel]); by default the weight field, and nothing for assisted sets (whose weight is help).
     */
    fun newRecords(
        set: WorkoutSet,
        kind: ExerciseKind,
        before: List<WorkoutSet>,
        load: ((WorkoutSet) -> Double?)? = null,
    ): Set<RecordKind> {
        if (!set.isWorking) return emptySet()
        val previous = before.filter { it.isWorking && it.id != set.id }
        if (previous.isEmpty()) return emptySet()
        val loadOf: (WorkoutSet) -> Double? = load ?: { s -> if (kind.isAssisted) null else s.weightKg?.takeIf { it > 0.0 } }
        val result = mutableSetOf<RecordKind>()
        val e1 = TrainingMath.e1rm(loadOf(set), set.reps)
        val bestE1 = previous.mapNotNull { TrainingMath.e1rm(loadOf(it), it.reps) }.maxOrNull()
        if (e1 != null && bestE1 != null && e1 > bestE1 + EPS) result += RecordKind.E1RM
        if (kind.isAssisted) {
            // Only sets with an assistance value compare (a blank one is unknown, not zero).
            val reps = set.reps ?: 0
            val assistance = set.weightKg
            val least = previous.mapNotNull { it.weightKg }.minOrNull()
            if (reps > 0 && assistance != null && least != null && assistance < least - EPS) result += RecordKind.ASSISTANCE
        } else {
            val w = loadOf(set)
            val heaviest = previous.mapNotNull { loadOf(it) }.maxOrNull()
            if (kind.usesLoad && w != null && heaviest != null && w > heaviest + EPS) result += RecordKind.WEIGHT
        }
        if (kind == ExerciseKind.BODYWEIGHT_REPS && (set.weightKg ?: 0.0) <= 0.0) {
            val reps = set.reps
            val most = previous.filter { (it.weightKg ?: 0.0) <= 0.0 }.mapNotNull { it.reps }.maxOrNull()
            if (reps != null && most != null && reps > most) result += RecordKind.REPS
        }
        if (kind == ExerciseKind.DURATION) {
            val d = set.durationSec
            val longest = previous.mapNotNull { it.durationSec }.maxOrNull()
            if (d != null && longest != null && d > longest) result += RecordKind.DURATION
        }
        return result
    }

    /**
     * The sets of the last session of [exerciseId] before [beforeWorkoutId]'s workout started (all
     * sets, warm-ups included), used to pre-fill a new entry and to show "last time" hints.
     */
    fun previousSets(workouts: List<Workout>, exerciseId: String, beforeWorkoutId: String?): List<WorkoutSet> =
        lastSessionSets(workouts, exerciseId, beforeWorkoutId) { it.done }

    /**
     * Like [previousSets], from the last session with a done working set: one where only warm-ups
     * were done is skipped, so routine targets and loads come from real work instead of falling back
     * to 3 × 10 without a load.
     */
    fun previousWorkingSets(workouts: List<Workout>, exerciseId: String, beforeWorkoutId: String?): List<WorkoutSet> =
        lastSessionSets(workouts, exerciseId, beforeWorkoutId) { it.done && !it.warmup }

    /** The done sets of the last entry of [exerciseId] before [beforeWorkoutId] with a set that [counts]. */
    private fun lastSessionSets(
        workouts: List<Workout>,
        exerciseId: String,
        beforeWorkoutId: String?,
        counts: (WorkoutSet) -> Boolean,
    ): List<WorkoutSet> {
        val current = workouts.firstOrNull { it.id == beforeWorkoutId }
        return workouts
            .asSequence()
            .filter { it.id != beforeWorkoutId && (current == null || it.startedAt <= current.startedAt) }
            .sortedByDescending { it.startedAt }
            .flatMap { w -> w.entries.asSequence().filter { it.exerciseId == exerciseId } }
            .firstOrNull { e -> e.sets.any(counts) }
            ?.sets
            ?.filter { it.done }
            .orEmpty()
    }

    /** Exercises ordered by how recently they were done (most recent first), for the picker. */
    fun recentExerciseIds(workouts: List<Workout>, limit: Int = 12): List<String> =
        workouts.sortedByDescending { it.startedAt }
            .flatMap { w -> w.entries.filter { e -> e.sets.any { it.done } }.map { it.exerciseId } }
            .distinct()
            .take(limit)

    /**
     * Total working volume (kg) of a workout, for summaries. With [exercises] and a body-weight
     * [load] model, bodyweight and assisted sets count the weight they really moved; otherwise the
     * weight field counts (assisted sets excluded only when [exercises] tells which they are).
     */
    fun workoutVolumeKg(
        w: Workout,
        exercises: ((String) -> Exercise)? = null,
        load: LoadModel = LoadModel.WITHOUT_BODYWEIGHT,
    ): Double = w.entries.sumOf { e ->
        val ex = exercises?.invoke(e.exerciseId)
        e.sets.filter { it.isWorking }.sumOf { s ->
            val kg = if (ex == null) s.weightKg?.takeIf { it > 0.0 } else load.loadKg(ex, s, s.doneAt ?: w.startedAt)
            (kg ?: 0.0) * (s.reps ?: 0).coerceAtLeast(0)
        }
    }

    /** Muscles trained in a workout with their hard-set counts (primary 1, secondary ½). */
    fun workoutMuscles(w: Workout, exercises: (String) -> Exercise): Map<Muscle, Double> =
        MuscleStats.setsByMuscle(listOf(w), exercises, from = Long.MIN_VALUE, to = Long.MAX_VALUE)

    private const val EPS = 1e-6
}
