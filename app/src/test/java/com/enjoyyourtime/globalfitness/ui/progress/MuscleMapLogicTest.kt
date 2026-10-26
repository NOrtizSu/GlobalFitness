package com.enjoyyourtime.globalfitness.ui.progress

import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.data.MuscleMapMode
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.domain.BodyweightTimeline
import com.enjoyyourtime.globalfitness.training.domain.ExerciseHistory
import com.enjoyyourtime.globalfitness.training.domain.LoadModel
import com.enjoyyourtime.globalfitness.training.domain.MuscleRecovery
import com.enjoyyourtime.globalfitness.training.domain.MuscleStats
import com.enjoyyourtime.globalfitness.training.domain.MuscleStrength
import com.enjoyyourtime.globalfitness.training.domain.MuscleVolume
import com.enjoyyourtime.globalfitness.training.domain.VolumeLevel
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZoneOffset

class MuscleMapLogicTest {
    private val day = Duration.ofDays(1).toMillis()
    private val hour = Duration.ofHours(1).toMillis()
    private val now = 200 * day

    private fun snapshot(
        muscle: Muscle,
        sets: Double = 0.0,
        change: Double? = null,
        lastTrainedAt: Long? = null,
        fraction: Double = 1.0,
        readyAt: Long? = null,
    ) = MuscleSnapshot(
        muscle = muscle,
        volume = MuscleVolume(muscle, sets, MuscleStats.level(muscle, sets)),
        strength = MuscleStrength(muscle, change, exercises = if (change == null) 0 else 1, sessions = if (change == null) 0 else 3),
        recovery = MuscleRecovery(muscle, lastTrainedAt, fraction, readyAt),
    )

    private fun all(vararg custom: MuscleSnapshot): Map<Muscle, MuscleSnapshot> {
        val given = custom.associateBy { it.muscle }
        return Muscle.entries.associateWith { given[it] ?: snapshot(it) }
    }

    // ---- colors ------------------------------------------------------------------------------

    @Test
    fun weeklySetsShadeFollowsTheMusclesRange() {
        // Chest: 10–20 sets.
        assertNull(MuscleMapLogic.shade(MuscleMapMode.WEEKLY_SETS, snapshot(Muscle.CHEST, sets = 0.0)))
        assertEquals(MuscleShade.SETS_LOW, MuscleMapLogic.shade(MuscleMapMode.WEEKLY_SETS, snapshot(Muscle.CHEST, sets = 4.5)))
        assertEquals(MuscleShade.SETS_OPTIMAL, MuscleMapLogic.shade(MuscleMapMode.WEEKLY_SETS, snapshot(Muscle.CHEST, sets = 10.0)))
        assertEquals(MuscleShade.SETS_OPTIMAL, MuscleMapLogic.shade(MuscleMapMode.WEEKLY_SETS, snapshot(Muscle.CHEST, sets = 20.0)))
        assertEquals(MuscleShade.SETS_HIGH, MuscleMapLogic.shade(MuscleMapMode.WEEKLY_SETS, snapshot(Muscle.CHEST, sets = 21.0)))
        // A small muscle is in range with fewer sets.
        assertEquals(MuscleShade.SETS_OPTIMAL, MuscleMapLogic.shade(MuscleMapMode.WEEKLY_SETS, snapshot(Muscle.NECK, sets = 4.0)))
    }

    @Test
    fun strengthShadeDivergesAroundStable() {
        assertNull(MuscleMapLogic.strengthShade(null))
        assertEquals(MuscleShade.STRENGTH_DOWN_MORE, MuscleMapLogic.strengthShade(-7.0))
        assertEquals(MuscleShade.STRENGTH_DOWN_MORE, MuscleMapLogic.strengthShade(-5.0))
        assertEquals(MuscleShade.STRENGTH_DOWN, MuscleMapLogic.strengthShade(-3.0))
        assertEquals(MuscleShade.STRENGTH_STABLE, MuscleMapLogic.strengthShade(-1.9))
        assertEquals(MuscleShade.STRENGTH_STABLE, MuscleMapLogic.strengthShade(0.0))
        assertEquals(MuscleShade.STRENGTH_STABLE, MuscleMapLogic.strengthShade(1.99))
        assertEquals(MuscleShade.STRENGTH_UP, MuscleMapLogic.strengthShade(2.0))
        assertEquals(MuscleShade.STRENGTH_UP_MORE, MuscleMapLogic.strengthShade(5.0))
        assertEquals(MuscleShade.STRENGTH_UP_MORE, MuscleMapLogic.strengthShade(12.0))
    }

    @Test
    fun recoveryShadeFromFatiguedToRecoveredAndNeverTrainedIsNeutral() {
        assertNull(MuscleMapLogic.recoveryShade(MuscleRecovery(Muscle.QUADS, null, 1.0, null)))
        assertEquals(MuscleShade.RECOVERY_FATIGUED, MuscleMapLogic.recoveryShade(MuscleRecovery(Muscle.QUADS, now - hour, 0.1, now + day)))
        assertEquals(MuscleShade.RECOVERY_PARTIAL, MuscleMapLogic.recoveryShade(MuscleRecovery(Muscle.QUADS, now - day, 0.5, now + hour)))
        assertEquals(MuscleShade.RECOVERY_DONE, MuscleMapLogic.recoveryShade(MuscleRecovery(Muscle.QUADS, now - 30 * day, 1.0, null)))
    }

    @Test
    fun legendListsTheModesShadesThenTheNeutralColor() {
        assertEquals(
            listOf(MuscleShade.SETS_LOW, MuscleShade.SETS_OPTIMAL, MuscleShade.SETS_HIGH, null),
            MuscleMapLogic.legend(MuscleMapMode.WEEKLY_SETS),
        )
        assertEquals(
            listOf(
                MuscleShade.STRENGTH_DOWN_MORE, MuscleShade.STRENGTH_DOWN, MuscleShade.STRENGTH_STABLE,
                MuscleShade.STRENGTH_UP, MuscleShade.STRENGTH_UP_MORE, null,
            ),
            MuscleMapLogic.legend(MuscleMapMode.STRENGTH),
        )
        assertEquals(
            listOf(MuscleShade.RECOVERY_FATIGUED, MuscleShade.RECOVERY_PARTIAL, MuscleShade.RECOVERY_DONE, null),
            MuscleMapLogic.legend(MuscleMapMode.RECOVERY),
        )
        // Every shade belongs to exactly one legend.
        val listed = MuscleMapMode.entries.flatMap { MuscleMapLogic.legend(it) }.filterNotNull()
        assertEquals(MuscleShade.entries.toSet(), listed.toSet())
        assertEquals(MuscleShade.entries.size, listed.size)
    }

    // ---- list order --------------------------------------------------------------------------

    @Test
    fun weeklyRankingPutsTheMostTrainedFirstAndKeepsAnatomicalOrderOnTies() {
        val order = MuscleMapLogic.ranking(
            MuscleMapMode.WEEKLY_SETS,
            all(snapshot(Muscle.QUADS, sets = 12.0), snapshot(Muscle.CHEST, sets = 6.0), snapshot(Muscle.BICEPS, sets = 6.0)),
        )
        assertEquals(listOf(Muscle.QUADS, Muscle.CHEST, Muscle.BICEPS), order.take(3))
        assertEquals(Muscle.entries.size, order.size)
        // The untrained rest in the enum's order.
        assertEquals(Muscle.entries - setOf(Muscle.QUADS, Muscle.CHEST, Muscle.BICEPS), order.drop(3))
    }

    @Test
    fun strengthRankingPutsTheBiggestGainFirstAndMusclesWithoutDataLast() {
        val order = MuscleMapLogic.ranking(
            MuscleMapMode.STRENGTH,
            all(snapshot(Muscle.LATS, change = -3.0), snapshot(Muscle.CHEST, change = 8.0), snapshot(Muscle.QUADS, change = 0.5)),
        )
        assertEquals(listOf(Muscle.CHEST, Muscle.QUADS, Muscle.LATS), order.take(3))
        assertEquals(Muscle.SHOULDERS, order[3])
    }

    @Test
    fun recoveryRankingPutsTheLeastRecoveredFirstAndNeverTrainedLast() {
        val order = MuscleMapLogic.ranking(
            MuscleMapMode.RECOVERY,
            all(
                snapshot(Muscle.CHEST, lastTrainedAt = now - 40 * day, fraction = 1.0),
                snapshot(Muscle.QUADS, lastTrainedAt = now - hour, fraction = 0.05, readyAt = now + 3 * day),
                snapshot(Muscle.HAMSTRINGS, lastTrainedAt = now - hour, fraction = 0.05, readyAt = now + 2 * day),
                snapshot(Muscle.BICEPS, lastTrainedAt = now - day, fraction = 0.6, readyAt = now + hour),
            ),
        )
        // Same fraction: the one that needs longer first.
        assertEquals(listOf(Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.BICEPS, Muscle.CHEST), order.take(4))
        assertEquals(Muscle.SHOULDERS, order[4])
    }

    // ---- Hoy summary ---------------------------------------------------------------------------

    private fun week(vararg sets: Pair<Muscle, Double>): Map<Muscle, MuscleVolume> {
        val given = sets.toMap()
        return Muscle.entries.associateWith { m -> (given[m] ?: 0.0).let { MuscleVolume(m, it, MuscleStats.level(m, it)) } }
    }

    @Test
    fun summaryWithoutSetsSaysSo() {
        assertEquals(WeekSummary.NoSets, MuscleMapLogic.weekSummary(week()))
    }

    @Test
    fun summaryNamesGroupsOnTrackAndBelow() {
        val summary = MuscleMapLogic.weekSummary(
            week(Muscle.CHEST to 12.0, Muscle.LATS to 10.0, Muscle.QUADS to 12.0, Muscle.HAMSTRINGS to 3.0, Muscle.GLUTES to 9.0),
        )
        assertEquals(
            WeekSummary.Mixed(
                onTrack = listOf(MuscleRegion.CHEST, MuscleRegion.BACK),
                below = listOf(MuscleRegion.SHOULDERS, MuscleRegion.ARMS, MuscleRegion.CORE, MuscleRegion.LEGS),
            ),
            summary,
        )
    }

    @Test
    fun aboveRangeCountsAsOnTrackAndEveryGroupInRangeIsAllOnTrack() {
        val summary = MuscleMapLogic.weekSummary(
            week(
                Muscle.CHEST to 25.0, Muscle.LATS to 12.0, Muscle.SHOULDERS to 10.0, Muscle.BICEPS to 8.0, Muscle.TRICEPS to 9.0,
                Muscle.ABS to 6.0, Muscle.QUADS to 10.0, Muscle.HAMSTRINGS to 8.0, Muscle.GLUTES to 8.0,
            ),
        )
        assertEquals(WeekSummary.AllOnTrack, summary)
    }

    @Test
    fun summaryWithNothingOnTrackOnlyListsTheGroupsBelow() {
        val summary = MuscleMapLogic.weekSummary(week(Muscle.CHEST to 2.0))
        assertEquals(WeekSummary.Mixed(onTrack = emptyList(), below = MuscleRegion.entries), summary)
    }

    // ---- recovery estimate, recent exercises, number formatting --------------------------------

    @Test
    fun readyTimeIsRoundedToTheNearestHour() {
        val base = 10 * day + 18 * hour
        assertEquals(base, MuscleMapLogic.roundedReadyAt(base + 29 * 60_000))
        assertEquals(base + hour, MuscleMapLogic.roundedReadyAt(base + 30 * 60_000))
        assertEquals(base, MuscleMapLogic.roundedReadyAt(base - 25 * 60_000))
    }

    private val exercises = TrainingRepository.buildExercises(ExerciseCatalog.all, emptyList(), emptyMap())
    private val lookup: (String) -> Exercise = { exercises[it] ?: TrainingRepository.unknownExercise(it) }
    private var ids = 0

    private fun workout(at: Long, exerciseId: String, kg: Double, reps: Int) = Workout(
        id = "w${ids++}", startedAt = at, endedAt = at + hour, name = null, note = null, updatedAt = at,
        entries = listOf(WorkoutEntry("e${ids++}", exerciseId, listOf(WorkoutSet("s${ids++}", weightKg = kg, reps = reps, done = true, doneAt = at)))),
    )

    @Test
    fun recentExercisesOfAMuscleIncludeSecondaryOnesMostRecentFirst() {
        val workouts = listOf(
            workout(now - 10 * day, "barbell_bench_press", 80.0, 5),
            workout(now - 3 * day, "barbell_bench_press", 84.0, 5),
            workout(now - 2 * day, "lat_pulldown_machine", 60.0, 10),
            workout(now - 1 * day, "pull_up", 10.0, 6),
        )
        val sessions = ExerciseHistory.allSessions(workouts, ZoneOffset.UTC)

        val biceps = MuscleMapLogic.recentExercises(Muscle.BICEPS, sessions, lookup)
        assertEquals(listOf("pull_up", "lat_pulldown_machine"), biceps.map { it.exercise.id })
        assertNull(biceps.first().changePercent) // a single session

        val triceps = MuscleMapLogic.recentExercises(Muscle.TRICEPS, sessions, lookup)
        assertEquals(listOf("barbell_bench_press"), triceps.map { it.exercise.id })
        assertEquals(5.0, triceps.single().changePercent!!, 1e-9) // 84 vs 80 at the same reps

        assertTrue(MuscleMapLogic.recentExercises(Muscle.CALVES, sessions, lookup).isEmpty())
        assertEquals(1, MuscleMapLogic.recentExercises(Muscle.BICEPS, sessions, lookup, limit = 1).size)
    }

    @Test
    fun assistedMachineTrendUsesTheBodyWeightLoad() {
        // 80 kg body weight: 30 kg of assistance lifts 50 kg, 20 kg lifts 60 kg (+20 %), at the same reps.
        val load = LoadModel(BodyweightTimeline(listOf(0L to 80.0)))
        val workouts = listOf(
            workout(now - 5 * day, "assisted_pull_up_machine", 30.0, 8),
            workout(now - 1 * day, "assisted_pull_up_machine", 20.0, 8),
        )
        val sessions = ExerciseHistory.allSessions(workouts, ZoneOffset.UTC, exercises = lookup, load = load)
        val lats = MuscleMapLogic.recentExercises(Muscle.LATS, sessions, lookup)
        assertEquals(20.0, lats.single().changePercent!!, 1e-9)
        // Without a body weight there is no load to compare.
        val noWeight = ExerciseHistory.allSessions(workouts, ZoneOffset.UTC, exercises = lookup, load = LoadModel.WITHOUT_BODYWEIGHT)
        assertNull(MuscleMapLogic.recentExercises(Muscle.LATS, noWeight, lookup).single().changePercent)
    }

    @Test
    fun decimalsOnlyWhenTheValueNeedsThem() {
        assertEquals(0, MuscleMapLogic.decimals(80.0))
        assertEquals(0, MuscleMapLogic.decimals(12.0))
        assertEquals(1, MuscleMapLogic.decimals(82.5))
        assertEquals(1, MuscleMapLogic.decimals(7.5))
        assertEquals(2, MuscleMapLogic.decimals(81.25))
    }

    @Test
    fun levelsUsedByTheShadesComeFromTheTrainingStatistics() {
        // Guards the contract with MuscleStats.level: NONE is the only neutral weekly value.
        assertEquals(VolumeLevel.NONE, MuscleStats.level(Muscle.CHEST, 0.0))
        assertNull(MuscleMapLogic.setsShade(VolumeLevel.NONE))
        VolumeLevel.entries.filter { it != VolumeLevel.NONE }.forEach { assertTrue(MuscleMapLogic.setsShade(it) != null) }
    }
}
