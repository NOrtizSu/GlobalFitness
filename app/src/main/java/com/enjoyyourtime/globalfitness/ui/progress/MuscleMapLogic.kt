package com.enjoyyourtime.globalfitness.ui.progress

import com.enjoyyourtime.globalfitness.training.data.MuscleMapMode
import com.enjoyyourtime.globalfitness.training.domain.ExerciseSession
import com.enjoyyourtime.globalfitness.training.domain.MuscleRecovery
import com.enjoyyourtime.globalfitness.training.domain.MuscleStrength
import com.enjoyyourtime.globalfitness.training.domain.MuscleVolume
import com.enjoyyourtime.globalfitness.training.domain.TrainingMath
import com.enjoyyourtime.globalfitness.training.domain.VolumeLevel
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.Muscle
import java.time.Duration
import kotlin.math.abs

/** Period of the strength mode (the same choices as the body charts, without "all"). */
enum class StrengthPeriod(val days: Int) {
    M1(30),
    M3(91),
    M6(182),
    Y1(365),
}

/** One exercise that trained a muscle, for the muscle's detail card. */
data class MuscleExercise(
    val exercise: Exercise,
    val last: ExerciseSession,
    /** Best estimated 1RM of [last] against the session before, in percent; null without two scored sessions. */
    val changePercent: Double?,
)

/** Everything the Músculos page shows about one muscle. */
data class MuscleSnapshot(
    val muscle: Muscle,
    val volume: MuscleVolume,
    val strength: MuscleStrength,
    val recovery: MuscleRecovery,
    /** Exercises that trained it, most recent first (at most [MuscleMapLogic.RECENT_EXERCISES]). */
    val exercises: List<MuscleExercise> = emptyList(),
)

/**
 * The color class of a muscle on the body map in one mode. Null (not a member) is the figure's
 * neutral color: no sets this week, no strength data, never trained.
 */
enum class MuscleShade(val mode: MuscleMapMode) {
    SETS_LOW(MuscleMapMode.WEEKLY_SETS),
    SETS_OPTIMAL(MuscleMapMode.WEEKLY_SETS),
    SETS_HIGH(MuscleMapMode.WEEKLY_SETS),
    STRENGTH_DOWN_MORE(MuscleMapMode.STRENGTH),
    STRENGTH_DOWN(MuscleMapMode.STRENGTH),
    STRENGTH_STABLE(MuscleMapMode.STRENGTH),
    STRENGTH_UP(MuscleMapMode.STRENGTH),
    STRENGTH_UP_MORE(MuscleMapMode.STRENGTH),
    RECOVERY_FATIGUED(MuscleMapMode.RECOVERY),
    RECOVERY_PARTIAL(MuscleMapMode.RECOVERY),
    RECOVERY_DONE(MuscleMapMode.RECOVERY),
}

/** Groups of muscles named in Hoy's one-line summary. */
enum class MuscleRegion(
    /** The muscles that decide whether the group is on track (the ones trained directly). */
    val leads: List<Muscle>,
) {
    CHEST(listOf(Muscle.CHEST)),
    BACK(listOf(Muscle.LATS)),
    SHOULDERS(listOf(Muscle.SHOULDERS)),
    ARMS(listOf(Muscle.BICEPS, Muscle.TRICEPS)),
    CORE(listOf(Muscle.ABS)),
    LEGS(listOf(Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES)),
}

/** Hoy's "Muscles this week" line. */
sealed interface WeekSummary {
    data object NoSets : WeekSummary

    data object AllOnTrack : WeekSummary

    /** Groups in range (or above) and groups below it; [onTrack] may be empty, [below] never is. */
    data class Mixed(val onTrack: List<MuscleRegion>, val below: List<MuscleRegion>) : WeekSummary
}

/** Pure rules behind the body map's colors, legend, list order and summaries (unit-tested). */
object MuscleMapLogic {
    /** Strength changes within ± this many percent read as stable. */
    const val STABLE_PERCENT = 2.0

    /** Strength changes beyond ± this many percent get the strongest color. */
    const val STRONG_PERCENT = 5.0

    /** Below this recovery fraction a muscle is "fatigued", up to 1 "recovering". */
    const val FATIGUED_BELOW = 0.5

    const val RECENT_EXERCISES = 5

    private val HOUR_MS = Duration.ofHours(1).toMillis()

    fun shade(mode: MuscleMapMode, s: MuscleSnapshot): MuscleShade? = when (mode) {
        MuscleMapMode.WEEKLY_SETS -> setsShade(s.volume.level)
        MuscleMapMode.STRENGTH -> strengthShade(s.strength.changePercent)
        MuscleMapMode.RECOVERY -> recoveryShade(s.recovery)
    }

    fun setsShade(level: VolumeLevel): MuscleShade? = when (level) {
        VolumeLevel.NONE -> null
        VolumeLevel.LOW -> MuscleShade.SETS_LOW
        VolumeLevel.OPTIMAL -> MuscleShade.SETS_OPTIMAL
        VolumeLevel.HIGH -> MuscleShade.SETS_HIGH
    }

    fun strengthShade(changePercent: Double?): MuscleShade? = when {
        changePercent == null -> null
        changePercent <= -STRONG_PERCENT -> MuscleShade.STRENGTH_DOWN_MORE
        changePercent <= -STABLE_PERCENT -> MuscleShade.STRENGTH_DOWN
        changePercent < STABLE_PERCENT -> MuscleShade.STRENGTH_STABLE
        changePercent < STRONG_PERCENT -> MuscleShade.STRENGTH_UP
        else -> MuscleShade.STRENGTH_UP_MORE
    }

    fun recoveryShade(r: MuscleRecovery): MuscleShade? = when {
        r.lastTrainedAt == null -> null
        r.fraction < FATIGUED_BELOW -> MuscleShade.RECOVERY_FATIGUED
        r.fraction < 1.0 -> MuscleShade.RECOVERY_PARTIAL
        else -> MuscleShade.RECOVERY_DONE
    }

    /** Legend entries of [mode] from the "worst" end to the best, then null (the neutral color). */
    fun legend(mode: MuscleMapMode): List<MuscleShade?> = MuscleShade.entries.filter { it.mode == mode } + null

    /**
     * The muscles in the order of [mode]'s value: most weekly sets first; biggest strength gain
     * first (muscles without data last); least recovered first (never trained last). Ties keep the
     * anatomical order of [Muscle].
     */
    fun ranking(mode: MuscleMapMode, snapshots: Map<Muscle, MuscleSnapshot>): List<Muscle> {
        val list = Muscle.entries.mapNotNull { snapshots[it] }
        val sorted = when (mode) {
            MuscleMapMode.WEEKLY_SETS -> list.sortedByDescending { it.volume.sets }
            MuscleMapMode.STRENGTH -> list.sortedWith(
                compareBy<MuscleSnapshot> { it.strength.changePercent == null }.thenByDescending { it.strength.changePercent ?: 0.0 },
            )
            MuscleMapMode.RECOVERY -> list.sortedWith(
                compareBy<MuscleSnapshot> { it.recovery.lastTrainedAt == null }
                    .thenBy { it.recovery.fraction }
                    .thenByDescending { it.recovery.readyAt ?: Long.MIN_VALUE },
            )
        }
        return sorted.map { it.muscle }
    }

    /** Groups on track when every lead muscle is in range or above it this week. */
    fun weekSummary(weekly: Map<Muscle, MuscleVolume>): WeekSummary {
        if (weekly.values.none { it.sets > 0.0 }) return WeekSummary.NoSets
        val (onTrack, below) = MuscleRegion.entries.partition { region ->
            region.leads.all { m -> weekly[m]?.level.let { it == VolumeLevel.OPTIMAL || it == VolumeLevel.HIGH } }
        }
        return if (below.isEmpty()) WeekSummary.AllOnTrack else WeekSummary.Mixed(onTrack, below)
    }

    /** The recovery estimate shown to the user, rounded to the nearest hour (it is only an estimate). */
    fun roundedReadyAt(readyAt: Long): Long = Math.floorDiv(readyAt + HOUR_MS / 2, HOUR_MS) * HOUR_MS

    /**
     * Exercises that trained [muscle] (primary or secondary), most recent session first, with the
     * change of their best estimated 1RM against the session before. Like the sets and the recovery
     * of this page, sports, cardio and stretches count for no muscle (a football match is not a quads session).
     */
    fun recentExercises(
        muscle: Muscle,
        sessions: Map<String, List<ExerciseSession>>,
        exercises: (String) -> Exercise,
        limit: Int = RECENT_EXERCISES,
    ): List<MuscleExercise> =
        sessions.mapNotNull { (id, list) ->
            val exercise = exercises(id)
            val last = list.lastOrNull()
            if (muscle !in exercise.muscles || last == null || !TrainingMath.countsForMuscles(exercise)) {
                null
            } else {
                MuscleExercise(exercise, last, lastChangePercent(list))
            }
        }
            .sortedByDescending { it.last.at }
            .take(limit)

    /** Best e1RM of the last session against the previous scored one, in percent. */
    fun lastChangePercent(sessions: List<ExerciseSession>): Double? {
        val last = sessions.lastOrNull()?.bestE1rm ?: return null
        val previous = sessions.dropLast(1).lastOrNull { it.bestE1rm != null }?.bestE1rm ?: return null
        if (previous <= 0.0) return null
        return (last / previous - 1.0) * 100.0
    }

    /** Decimals to show a load or set count with: none for whole numbers, else one or two. */
    fun decimals(v: Double): Int = when {
        abs(v - Math.round(v)) < 0.005 -> 0
        abs(v * 10 - Math.round(v * 10)) < 0.05 -> 1
        else -> 2
    }
}
