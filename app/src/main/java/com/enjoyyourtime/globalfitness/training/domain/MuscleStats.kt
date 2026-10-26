package com.enjoyyourtime.globalfitness.training.domain

import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Workout
import java.time.Duration
import kotlin.math.max

/** Weekly hard sets of a muscle compared with its [Muscle.weeklySets] range. */
enum class VolumeLevel { NONE, LOW, OPTIMAL, HIGH }

data class MuscleVolume(val muscle: Muscle, val sets: Double, val level: VolumeLevel)

/**
 * Strength trend of a muscle: the mean change of the estimated 1RM of the exercises that train it
 * as a primary muscle, weighted by how often each was done. Null [changePercent] when no exercise
 * has two comparable sessions.
 */
data class MuscleStrength(val muscle: Muscle, val changePercent: Double?, val exercises: Int, val sessions: Int)

/** One exercise's strength trend (see [MuscleStats.exerciseTrends]): its estimated 1RM [change] as a fraction, over [sessions] sessions. */
class ExerciseTrend(val exercise: Exercise, val change: Double, val sessions: Int)

/**
 * Recovery of a muscle: [fraction] goes from 0 (just trained hard) to 1 (recovered). Null
 * [lastTrainedAt] = not trained in the look-back window.
 */
data class MuscleRecovery(val muscle: Muscle, val lastTrainedAt: Long?, val fraction: Double, val readyAt: Long?)

/** Body map statistics. Pure functions over the workouts (times in epoch ms). */
object MuscleStats {
    val WEEK_MS: Long = Duration.ofDays(7).toMillis()
    private val HOUR_MS: Long = Duration.ofHours(1).toMillis()

    /** How far back recovery looks: nothing takes longer than [recoveryHours]'s maximum. */
    private val RECOVERY_LOOKBACK_MS: Long = Duration.ofDays(5).toMillis()

    /**
     * Hard sets per muscle among the working sets done in [from, to) (primary 1, secondary ½).
     * A set's time is when it was ticked, else its workout's start.
     */
    fun setsByMuscle(workouts: List<Workout>, exercises: (String) -> Exercise, from: Long, to: Long): Map<Muscle, Double> {
        val result = HashMap<Muscle, Double>()
        for (w in workouts) {
            for (e in w.entries) {
                val ex = exercises(e.exerciseId)
                if (!TrainingMath.countsForMuscles(ex.kind)) continue
                val n = e.sets.count { s -> s.isWorking && (s.doneAt ?: w.startedAt).let { it in from until to } }
                if (n == 0) continue
                for (m in ex.primary) result[m] = (result[m] ?: 0.0) + n * TrainingMath.PRIMARY_SHARE
                for (m in ex.secondary - ex.primary) result[m] = (result[m] ?: 0.0) + n * TrainingMath.SECONDARY_SHARE
            }
        }
        return result
    }

    fun level(muscle: Muscle, sets: Double): VolumeLevel = when {
        sets <= 0.0 -> VolumeLevel.NONE
        sets < muscle.weeklySets.first -> VolumeLevel.LOW
        sets <= muscle.weeklySets.last -> VolumeLevel.OPTIMAL
        else -> VolumeLevel.HIGH
    }

    /** Hard sets of every muscle in the 7 days up to [now]. */
    fun weekly(workouts: List<Workout>, exercises: (String) -> Exercise, now: Long): Map<Muscle, MuscleVolume> {
        val sets = setsByMuscle(workouts, exercises, now - WEEK_MS, now + 1)
        return Muscle.entries.associateWith { m -> (sets[m] ?: 0.0).let { MuscleVolume(m, it, level(m, it)) } }
    }

    /**
     * Strength change per muscle over [from, to): the [exerciseTrends] of the exercises that train
     * it as a primary muscle, weighted by their sessions.
     */
    fun strength(
        workouts: List<Workout>,
        exercises: (String) -> Exercise,
        from: Long,
        to: Long,
        load: LoadModel = LoadModel.WITHOUT_BODYWEIGHT,
    ): Map<Muscle, MuscleStrength> {
        val trends = HashMap<Muscle, MutableList<ExerciseTrend>>()
        for (trend in exerciseTrends(workouts, exercises, from, to, load)) {
            for (m in trend.exercise.primary) trends.getOrPut(m) { ArrayList() } += trend
        }
        return Muscle.entries.associateWith { m ->
            val list = trends[m].orEmpty()
            val weight = list.sumOf { it.sessions }
            MuscleStrength(
                muscle = m,
                changePercent = if (weight == 0) null else list.sumOf { it.change * it.sessions } / weight * 100.0,
                exercises = list.size,
                sessions = weight,
            )
        }
    }

    /**
     * Strength change of each exercise over [from, to). For each exercise with a measurable load, the
     * best estimated 1RM of the first third of its sessions (including the last one before [from], as
     * the starting point) is compared with the best of the last third; exercises without two such
     * sessions are left out. [load] turns body weight and assistance into the weight really lifted,
     * so an assisted pull-up with less assistance (or a weighted dip) counts as getting stronger;
     * without body weight those exercises are left out.
     */
    fun exerciseTrends(
        workouts: List<Workout>,
        exercises: (String) -> Exercise,
        from: Long,
        to: Long,
        load: LoadModel = LoadModel.WITHOUT_BODYWEIGHT,
    ): List<ExerciseTrend> {
        val result = ArrayList<ExerciseTrend>()
        for ((id, all) in ExerciseHistory.allSessions(workouts, exercises = exercises, load = load)) {
            val ex = exercises(id)
            if (!ex.kind.usesReps) continue
            val scored = all.filter { it.bestE1rm != null }
            val inPeriod = scored.filter { it.at in from until to }
            if (inPeriod.isEmpty()) continue
            val start = scored.lastOrNull { it.at < from }
            val series = listOfNotNull(start) + inPeriod
            if (series.size < 2) continue
            val k = max(1, series.size / 3)
            val baseline = series.take(k).maxOf { it.bestE1rm!! }
            val current = series.takeLast(k).maxOf { it.bestE1rm!! }
            if (baseline <= 0.0) continue
            result += ExerciseTrend(ex, current / baseline - 1.0, series.size)
        }
        return result
    }

    /** Hours a muscle needs after a workout that gave it [sets] hard sets. */
    fun recoveryHours(sets: Double): Int = when {
        sets <= 2.0 -> 24
        sets < 6.0 -> 48
        sets < 10.0 -> 72
        else -> 96
    }

    /**
     * Recovery per muscle at [now]: every workout of the last days leaves the muscle tired for
     * [recoveryHours] of its sets; the least recovered of them decides.
     */
    fun recovery(workouts: List<Workout>, exercises: (String) -> Exercise, now: Long): Map<Muscle, MuscleRecovery> {
        val fraction = HashMap<Muscle, Double>()
        val readyAt = HashMap<Muscle, Long>()
        val last = HashMap<Muscle, Long>()
        for (w in workouts) {
            val at = w.lastActivityAt
            if (at > now) continue
            val recent = at >= now - RECOVERY_LOOKBACK_MS
            for ((m, sets) in setsByMuscle(listOf(w), exercises, Long.MIN_VALUE, Long.MAX_VALUE)) {
                last[m] = max(last[m] ?: Long.MIN_VALUE, at)
                if (!recent) continue
                val window = recoveryHours(sets) * HOUR_MS
                val f = ((now - at).toDouble() / window).coerceIn(0.0, 1.0)
                if (f < (fraction[m] ?: 1.0)) fraction[m] = f
                readyAt[m] = max(readyAt[m] ?: Long.MIN_VALUE, at + window)
            }
        }
        return Muscle.entries.associateWith { m ->
            MuscleRecovery(
                muscle = m,
                lastTrainedAt = last[m],
                fraction = fraction[m] ?: 1.0,
                readyAt = readyAt[m]?.takeIf { it > now },
            )
        }
    }
}
