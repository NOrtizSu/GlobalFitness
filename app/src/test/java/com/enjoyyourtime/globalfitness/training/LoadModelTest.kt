package com.enjoyyourtime.globalfitness.training

import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.domain.BodyweightTimeline
import com.enjoyyourtime.globalfitness.training.domain.ExerciseHistory
import com.enjoyyourtime.globalfitness.training.domain.LoadModel
import com.enjoyyourtime.globalfitness.training.domain.MuscleStats
import com.enjoyyourtime.globalfitness.training.domain.RecordKind
import com.enjoyyourtime.globalfitness.training.domain.TrainingMath
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class LoadModelTest {
    private val day = Duration.ofDays(1).toMillis()
    private val now = 200 * day
    private val exercises = TrainingRepository.buildExercises(ExerciseCatalog.all, emptyList(), emptyMap())
    private val lookup: (String) -> Exercise = { exercises[it] ?: TrainingRepository.unknownExercise(it) }
    private var ids = 0

    private fun set(kg: Double?, reps: Int, at: Long) = WorkoutSet("s${ids++}", weightKg = kg, reps = reps, done = true, doneAt = at)

    private fun workout(at: Long, exerciseId: String, vararg sets: WorkoutSet) = Workout(
        "w${ids++}", at, at + 3_600_000, null, null, at, listOf(WorkoutEntry("e${ids++}", exerciseId, sets.toList())),
    )

    @Test
    fun timelineUsesLastWeighingBeforeOrTheFirstOne() {
        val t = BodyweightTimeline(listOf(10L to 80.0, 30L to 78.0, 20L to 79.0, 40L to 0.0))
        assertEquals(80.0, t.at(5)!!, 0.0)
        assertEquals(80.0, t.at(10)!!, 0.0)
        assertEquals(79.0, t.at(25)!!, 0.0)
        assertEquals(78.0, t.at(1_000)!!, 0.0)
        assertNull(BodyweightTimeline.EMPTY.at(5))
    }

    @Test
    fun assistedLoadIsBodyweightMinusAssistance() {
        val model = LoadModel(BodyweightTimeline(listOf(0L to 80.0)))
        val assisted = lookup("assisted_pull_up_machine")
        assertEquals(ExerciseKind.ASSISTED_REPS, assisted.kind)
        assertEquals(50.0, model.loadKg(assisted, set(30.0, 8, 1), 1)!!, 1e-9)
        // Zero assistance = a full pull-up; a blank assistance is unknown.
        assertEquals(80.0, model.loadKg(assisted, set(0.0, 5, 1), 1)!!, 1e-9)
        assertNull(model.loadKg(assisted, set(null, 5, 1), 1))
        // Assistance heavier than the body: no meaningful load.
        assertNull(model.loadKg(assisted, set(90.0, 5, 1), 1))
        // Without body weight nothing can be said.
        assertNull(LoadModel.WITHOUT_BODYWEIGHT.loadKg(assisted, set(30.0, 8, 1), 1))
        assertTrue(LoadModel.WITHOUT_BODYWEIGHT.needsBodyweight(assisted))
    }

    @Test
    fun bodyweightExercisesAddExtraLoadToTheirShareOfBodyweight() {
        val model = LoadModel(BodyweightTimeline(listOf(0L to 80.0)))
        assertEquals(90.0, model.loadKg(lookup("pull_up"), set(10.0, 5, 1), 1)!!, 1e-9)
        assertEquals(52.0, model.loadKg(lookup("push_up"), set(null, 10, 1), 1)!!, 1e-9)
        // No factor (crunches...): only the extra load.
        assertNull(model.loadKg(lookup("crunch"), set(null, 20, 1), 1))
        assertEquals(10.0, model.loadKg(lookup("crunch"), set(10.0, 12, 1), 1)!!, 1e-9)
        assertEquals(100.0, model.loadKg(lookup("barbell_bench_press"), set(100.0, 1, 1), 1)!!, 1e-9)
    }

    @Test
    fun lessAssistanceIsStrongerAndARecord() {
        val model = LoadModel(BodyweightTimeline(listOf(0L to 80.0)))
        val ex = lookup("assisted_pull_up_machine")
        val a = workout(now - 40 * day, ex.id, set(40.0, 8, now - 40 * day))
        val b = workout(now - 20 * day, ex.id, set(30.0, 8, now - 20 * day))
        val c = workout(now - 5 * day, ex.id, set(20.0, 8, now - 5 * day))
        val sessions = ExerciseHistory.sessions(listOf(a, b, c), ex.id, exercise = ex, load = model)
        val e1 = sessions.map { it.bestE1rm!! }
        assertTrue(e1[0] < e1[1] && e1[1] < e1[2])
        assertEquals(TrainingMath.e1rm(60.0, 8)!!, e1[2], 1e-9)
        val records = ExerciseHistory.records(sessions)
        assertEquals(20.0, records.leastAssistanceKg!!, 0.0)
        assertEquals(60.0, records.heaviestKg!!, 1e-9)

        val strength = MuscleStats.strength(listOf(a, b, c), lookup, now - 60 * day, now + 1, model)
        assertTrue(strength.getValue(Muscle.LATS).changePercent!! > 0)
        // Without body weight the assisted machine says nothing about strength (instead of "weaker").
        assertNull(MuscleStats.strength(listOf(a, b, c), lookup, now - 60 * day, now + 1).getValue(Muscle.LATS).changePercent)

        val next = set(15.0, 8, now)
        val beaten = ExerciseHistory.newRecords(next, ex.kind, listOf(a, b, c).flatMap { it.entries[0].sets }) { s -> model.loadKg(ex, s, now) }
        assertEquals(setOf(RecordKind.E1RM, RecordKind.ASSISTANCE), beaten)
        // More assistance is never a record, even by default (no load function).
        assertFalse(RecordKind.ASSISTANCE in ExerciseHistory.newRecords(set(50.0, 8, now), ex.kind, listOf(set(20.0, 8, 1))))
        assertTrue(ExerciseHistory.newRecords(set(50.0, 8, now), ex.kind, listOf(set(20.0, 8, 1))).isEmpty())
        // A blank assistance neither beats nor blocks the record.
        assertTrue(ExerciseHistory.newRecords(set(null, 8, now), ex.kind, listOf(set(20.0, 8, 1))).isEmpty())
        assertEquals(
            setOf(RecordKind.ASSISTANCE),
            ExerciseHistory.newRecords(set(15.0, 8, now), ex.kind, listOf(set(20.0, 8, 1), set(null, 8, 2))),
        )
    }

    @Test
    fun customAssistedMachineLiftsTheWholeBody() {
        val custom = TrainingRepository.buildExercises(
            emptyList(),
            listOf(
                com.enjoyyourtime.globalfitness.training.model.CustomExercise(
                    "u:1", "Gravitron", ExerciseKind.ASSISTED_REPS, Equipment.MACHINE, setOf(Muscle.LATS), emptySet(), false, 0, 0,
                ),
            ),
            emptyMap(),
        ).getValue("u:1")
        assertEquals(1.0, custom.bodyweightFactor!!, 0.0)
    }
}
