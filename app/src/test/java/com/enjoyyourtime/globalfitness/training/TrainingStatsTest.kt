package com.enjoyyourtime.globalfitness.training

import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.domain.ExerciseHistory
import com.enjoyyourtime.globalfitness.training.domain.MuscleStats
import com.enjoyyourtime.globalfitness.training.domain.RecordKind
import com.enjoyyourtime.globalfitness.training.domain.TrainingMath
import com.enjoyyourtime.globalfitness.training.domain.VolumeLevel
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
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

class TrainingStatsTest {
    private val day = Duration.ofDays(1).toMillis()
    private val hour = Duration.ofHours(1).toMillis()
    private val now = 100 * day
    private val exercises = TrainingRepository.buildExercises(ExerciseCatalog.all, emptyList(), emptyMap())
    private val lookup: (String) -> Exercise = { exercises[it] ?: TrainingRepository.unknownExercise(it) }
    private var ids = 0

    private fun set(kg: Double?, reps: Int?, at: Long, warmup: Boolean = false, done: Boolean = true, sec: Int? = null) =
        WorkoutSet("s${ids++}", weightKg = kg, reps = reps, durationSec = sec, warmup = warmup, done = done, doneAt = if (done) at else null)

    private fun workout(at: Long, vararg entries: Pair<String, List<WorkoutSet>>) = Workout(
        id = "w${ids++}", startedAt = at, endedAt = at + hour, name = null, note = null, updatedAt = at,
        entries = entries.map { (ex, sets) -> WorkoutEntry("e${ids++}", ex, sets) },
    )

    @Test
    fun epleyEstimate() {
        assertEquals(100.0, TrainingMath.e1rm(100.0, 1)!!, 1e-9)
        assertEquals(100.0 * (1 + 5 / 30.0), TrainingMath.e1rm(100.0, 5)!!, 1e-9)
        assertNull(TrainingMath.e1rm(100.0, 13))
        assertNull(TrainingMath.e1rm(0.0, 5))
        assertNull(TrainingMath.e1rm(null, 5))
    }

    @Test
    fun weeklySetsCountPrimaryOneSecondaryHalfAndSkipWarmupsAndPlanned() {
        val bench = workout(
            now - 2 * day,
            "barbell_bench_press" to listOf(
                set(40.0, 10, now - 2 * day, warmup = true),
                set(80.0, 8, now - 2 * day),
                set(80.0, 8, now - 2 * day),
                set(80.0, 8, now - 2 * day, done = false),
            ),
        )
        val old = workout(now - 9 * day, "barbell_bench_press" to listOf(set(80.0, 8, now - 9 * day)))
        val week = MuscleStats.weekly(listOf(old, bench), lookup, now)
        assertEquals(2.0, week.getValue(Muscle.CHEST).sets, 1e-9)
        assertEquals(1.0, week.getValue(Muscle.TRICEPS).sets, 1e-9)
        assertEquals(VolumeLevel.LOW, week.getValue(Muscle.CHEST).level)
        assertEquals(VolumeLevel.NONE, week.getValue(Muscle.QUADS).level)
    }

    @Test
    fun cardioDoesNotCountForMuscles() {
        val w = workout(now - day, "treadmill" to listOf(set(null, null, now - day, sec = 1800)))
        assertTrue(MuscleStats.weekly(listOf(w), lookup, now).values.all { it.sets == 0.0 })
    }

    @Test
    fun strengthCompareFirstAndLastThirdIncludingStartingPoint() {
        val before = workout(now - 40 * day, "barbell_bench_press" to listOf(set(100.0, 1, now - 40 * day)))
        val a = workout(now - 20 * day, "barbell_bench_press" to listOf(set(102.0, 1, now - 20 * day)))
        val b = workout(now - 10 * day, "barbell_bench_press" to listOf(set(110.0, 1, now - 10 * day)))
        val s = MuscleStats.strength(listOf(before, a, b), lookup, now - 30 * day, now + 1)
        // Series = [100 (start), 102, 110]; k = 1 → 110 / 100.
        assertEquals(10.0, s.getValue(Muscle.CHEST).changePercent!!, 1e-9)
        assertNull(s.getValue(Muscle.QUADS).changePercent)
        // Secondary muscles do not get a strength trend.
        assertNull(s.getValue(Muscle.TRICEPS).changePercent)
    }

    @Test
    fun recoveryUsesTheLeastRecoveredWorkout() {
        val hard = workout(
            now - 24 * hour,
            "barbell_back_squat" to List(6) { set(100.0, 5, now - 24 * hour) },
        )
        val r = MuscleStats.recovery(listOf(hard), lookup, now)
        // 6 sets on quads → 72 h; 24 h elapsed → one third recovered.
        assertEquals(1.0 / 3, r.getValue(Muscle.QUADS).fraction, 1e-9)
        assertEquals(now + 48 * hour, r.getValue(Muscle.QUADS).readyAt)
        // Hamstrings only got 3 secondary sets → 48 h window.
        assertEquals(0.5, r.getValue(Muscle.HAMSTRINGS).fraction, 1e-9)
        assertEquals(1.0, r.getValue(Muscle.CHEST).fraction, 1e-9)
        assertNull(r.getValue(Muscle.CHEST).lastTrainedAt)
    }

    @Test
    fun newRecordsNeedHistory() {
        val first = set(80.0, 5, 1)
        assertTrue(ExerciseHistory.newRecords(first, ExerciseKind.WEIGHT_REPS, emptyList()).isEmpty())
        val heavier = set(85.0, 3, 2)
        assertEquals(setOf(RecordKind.E1RM, RecordKind.WEIGHT), ExerciseHistory.newRecords(heavier, ExerciseKind.WEIGHT_REPS, listOf(first)))
        val moreReps = set(null, 12, 3)
        assertEquals(setOf(RecordKind.REPS), ExerciseHistory.newRecords(moreReps, ExerciseKind.BODYWEIGHT_REPS, listOf(set(null, 10, 1))))
    }

    @Test
    fun previousSetsComeFromTheLastEarlierSession() {
        val a = workout(now - 3 * day, "leg_press_machine" to listOf(set(150.0, 10, now - 3 * day)))
        val b = workout(now - day, "leg_press_machine" to listOf(set(160.0, 10, now - day), set(160.0, 9, now - day, done = false)))
        val current = workout(now, "leg_press_machine" to emptyList())
        val prev = ExerciseHistory.previousSets(listOf(a, b, current), "leg_press_machine", current.id)
        assertEquals(listOf(160.0), prev.map { it.weightKg })
        val sessions = ExerciseHistory.sessions(listOf(a, b, current), "leg_press_machine", ZoneOffset.UTC)
        assertEquals(2, sessions.size)
        assertEquals(160.0, ExerciseHistory.records(sessions).heaviestKg!!, 0.0)
    }

    @Test
    fun catalogIdsAreUniqueAndWithoutColon() {
        val ids = ExerciseCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { ':' in it })
        assertTrue(ExerciseCatalog.all.filter { it.kind != ExerciseKind.CARDIO }.all { it.primary.isNotEmpty() })
    }
}
