package com.enjoyyourtime.globalfitness.training

import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.catalog.RoutineCatalog
import com.enjoyyourtime.globalfitness.training.data.TrainingBatch
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.data.WorkoutEdits
import com.enjoyyourtime.globalfitness.training.domain.ExerciseHistory
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.RoutineExercise
import com.enjoyyourtime.globalfitness.training.model.Tombstone
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineTest {
    private var now = 1_000_000L
    private var next = 0
    private val newId = { "id${next++}" }
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun TestScope.repo(store: FakeTrainingStore = FakeTrainingStore()): TrainingRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        return TrainingRepository(store, scope, dispatcher, dispatcher, clock = { now }, newId = newId)
    }

    private fun done(kg: Double?, reps: Int?, sec: Int? = null, warmup: Boolean = false) =
        WorkoutSet(newId(), weightKg = kg, reps = reps, durationSec = sec, warmup = warmup, done = true, doneAt = 1)

    @Test
    fun builtInRoutinesUseCatalogExercisesAndHaveNames() {
        val ids = RoutineCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (r in RoutineCatalog.all) {
            assertTrue("${r.id} must not contain ':'", ':' !in r.id)
            assertEquals(R.string::class.java.getField("routine_${r.id}").getInt(null), r.nameRes)
            assertTrue("${r.id} is empty", r.exercises.isNotEmpty())
            for (e in r.exercises) {
                val ex = ExerciseCatalog[e.exerciseId]
                assertTrue("${r.id}: unknown exercise ${e.exerciseId}", ex != null)
                assertTrue("${r.id}: ${e.exerciseId} needs sets", e.sets in 1..WorkoutEdits.MAX_ROUTINE_SETS)
                // Timed and cardio exercises get a time target, the others reps.
                if (ex!!.kind.usesDuration) assertTrue("${r.id}: ${e.exerciseId} needs a time", e.durationSec != null)
                if (ex.kind.usesReps) assertTrue("${r.id}: ${e.exerciseId} needs reps", e.reps != null)
            }
        }
    }

    @Test
    fun routinePlanTakesLoadsFromLastTimeAndRepsFromTheTarget() {
        val previous = listOf(done(20.0, 12, warmup = true), done(60.0, 8), done(62.5, 6))
        val plan = WorkoutEdits.planFromRoutine(
            listOf(RoutineExercise("barbell_bench_press", sets = 3, reps = 10), RoutineExercise("plank", 2, durationSec = 45)),
            previous = { id -> if (id == "barbell_bench_press") previous else emptyList() },
            newId = newId,
        )
        val bench = plan[0].sets
        assertEquals(3, bench.size)
        // Warm-ups are not a model; the third set repeats the last working set's load.
        assertEquals(listOf(60.0, 62.5, 62.5), bench.map { it.weightKg })
        assertEquals(listOf(10, 10, 10), bench.map { it.reps })
        assertTrue(bench.none { it.done })
        assertEquals(listOf(45, 45), plan[1].sets.map { it.durationSec })
        assertNull(plan[1].sets[0].weightKg)
    }

    @Test
    fun aPlannedDistanceKeepsLastTimesPaceForThePlannedTime() {
        val run = WorkoutSet(newId(), durationSec = 1800, distanceM = 5000.0, level = 7, done = true, doneAt = 1)
        val plan = WorkoutEdits.planFromRoutine(
            listOf(RoutineExercise("treadmill", 1, durationSec = 600), RoutineExercise("bike", 1)),
            previous = { listOf(run) },
            newId = newId,
        )
        // A 10:00 target after 5 km in 30:00: 1667 m, never 5 km in ten minutes (30 km/h).
        val short = plan[0].sets.single()
        assertEquals(600, short.durationSec)
        assertEquals(1667.0, short.distanceM!!, 0.0)
        assertEquals(7, short.level)
        // Without a time target, last time's time and distance.
        assertEquals(1800, plan[1].sets.single().durationSec)
        assertEquals(5000.0, plan[1].sets.single().distanceM!!, 0.0)
        // No time last time: no pace to keep, unless no time is planned either.
        val distanceOnly = WorkoutSet("d", distanceM = 3000.0)
        assertNull(WorkoutEdits.plannedDistance(distanceOnly, 600))
        assertEquals(3000.0, WorkoutEdits.plannedDistance(distanceOnly, null)!!, 0.0)
        assertNull(WorkoutEdits.plannedDistance(null, 600))
    }

    @Test
    fun routinesPlanFromTheLastSessionWithWorkingSets() = runTest {
        val working = Workout("a", 0, 10, null, null, 1, listOf(WorkoutEntry("ea", "barbell_bench_press", listOf(done(80.0, 6), done(80.0, 6)))))
        // The latest session only got a warm-up done.
        val warmupOnly = Workout("b", 100, 110, null, null, 1, listOf(WorkoutEntry("eb", "barbell_bench_press", listOf(done(40.0, 12, warmup = true)))))
        val history = listOf(working, warmupOnly)
        assertEquals(listOf(40.0), ExerciseHistory.previousSets(history, "barbell_bench_press", null).map { it.weightKg })
        assertEquals(listOf(80.0, 80.0), ExerciseHistory.previousWorkingSets(history, "barbell_bench_press", null).map { it.weightKg })

        val repo = repo(FakeTrainingStore(workouts = history))
        advanceUntilIdle()
        val template = repo.routineTemplates.value.first { it.builtIn }.copy(exercises = listOf(RoutineExercise("barbell_bench_press", 3, 8)))
        val w = repo.startFromRoutine(template)
        assertEquals(listOf(80.0, 80.0, 80.0), w.entries.single().sets.map { it.weightKg })
    }

    @Test
    fun addingARoutineToAWorkoutReturnsOnlyTheEntriesItAdded() = runTest {
        val repo = repo()
        advanceUntilIdle()
        val w = repo.startWorkout()
        repo.updateWorkout(w.id) { WorkoutEdits.addExercise(it, "leg_press_machine", emptyList(), newId) }
        val template = repo.routineTemplates.value.first { it.builtIn }.copy(
            exercises = listOf(RoutineExercise("leg_press_machine", 3, 12), RoutineExercise("leg_extension_machine", 3, 15)),
        )
        val added = repo.addRoutineToWorkout(w.id, template)!!
        val entries = repo.workout(w.id)!!.entries
        assertEquals(listOf(entries[1].id), added)
        assertEquals("leg_extension_machine", entries[1].exerciseId)
        // Again: nothing new to add.
        assertEquals(emptyList<String>(), repo.addRoutineToWorkout(w.id, template))
        assertNull(repo.addRoutineToWorkout("gone", template))
    }

    @Test
    fun addingARoutineSkipsExercisesAlreadyInTheWorkout() {
        val w = Workout("w", 0, null, null, null, 0, listOf(WorkoutEntry("e", "leg_press_machine", listOf(done(100.0, 10)))))
        val routine = listOf(RoutineExercise("leg_press_machine", 3, 12), RoutineExercise("leg_extension_machine", 3, 15))
        val added = WorkoutEdits.addRoutine(w, routine, { emptyList() }, newId)
        assertEquals(listOf("leg_press_machine", "leg_extension_machine"), added.entries.map { it.exerciseId })
        assertEquals(w.entries[0], added.entries[0])
        assertEquals(added, WorkoutEdits.addRoutine(added, routine, { emptyList() }, newId))
    }

    @Test
    fun workoutBecomesARoutine() {
        val w = Workout(
            "w", 0, 10, "Push", null, 0,
            listOf(
                WorkoutEntry("a", "barbell_bench_press", listOf(done(40.0, 12, warmup = true), done(80.0, 8), done(80.0, 8), done(80.0, 7))),
                WorkoutEntry("b", "plank", listOf(done(null, null, sec = 60))),
            ),
        )
        assertEquals(
            listOf(RoutineExercise("barbell_bench_press", 3, reps = 8), RoutineExercise("plank", 1, durationSec = 60)),
            WorkoutEdits.routineFrom(w),
        )
    }

    @Test
    fun createUpdateDeleteAndStartFromRoutine() = runTest {
        val history = Workout(
            "old", 0, 10, null, null, 1,
            listOf(WorkoutEntry("x", "barbell_squat_missing", emptyList()), WorkoutEntry("y", "leg_press_machine", listOf(done(150.0, 10)))),
        )
        val store = FakeTrainingStore(workouts = listOf(history))
        val repo = repo(store)
        advanceUntilIdle()

        val r = repo.createRoutine(
            "  Legs A ",
            listOf(RoutineExercise("leg_press_machine", 0, 12), RoutineExercise("leg_press_machine", 4, 10), RoutineExercise("leg_extension_machine", 3, -1)),
        )
        assertEquals("Legs A", r.name)
        // Sets clamped to at least one, duplicates dropped, non-positive targets removed.
        assertEquals(listOf(RoutineExercise("leg_press_machine", 1, 12), RoutineExercise("leg_extension_machine", 3, null)), r.exercises)
        assertEquals(r, store.routines[r.id])
        advanceUntilIdle()
        assertEquals(r.id, repo.routineTemplates.value.first().id)
        assertTrue(repo.routineTemplates.value.any { it.builtIn })

        now += 1000
        val renamed = repo.updateRoutine(r.id) { it.copy(name = "Legs B") }!!
        assertTrue(renamed.updatedAt > r.updatedAt)

        advanceUntilIdle()
        val template = repo.routineTemplates.value.first { it.id == r.id }
        val w = repo.startFromRoutine(template)
        assertEquals(listOf(150.0), w.entries[0].sets.map { it.weightKg })
        assertTrue(w.inProgress)
        assertNull(w.name)
        // The routine screens name the workout after the routine (in the user's language).
        assertEquals("Legs", repo.startFromRoutine(template, name = " Legs ").name)
        assertNull(repo.startFromRoutine(template, name = "  ").name)

        repo.deleteRoutine(r.id)
        assertNull(store.routines[r.id])
        val tomb = store.tombstones[Tombstone.ROUTINE to r.id]!!
        assertTrue(tomb.deletedAt > renamed.updatedAt)
    }

    @Test
    fun remoteRoutinesApplyWithoutTombstones() = runTest {
        val store = FakeTrainingStore()
        val repo = repo(store)
        advanceUntilIdle()
        val r = repo.createRoutine("Mine", listOf(RoutineExercise("plank", 3, durationSec = 30)))
        repo.applyRemote(TrainingBatch(removeRoutines = listOf(r.id)))
        assertTrue(repo.routines.value.isEmpty())
        assertFalse(store.tombstones.containsKey(Tombstone.ROUTINE to r.id))
    }

    @Test
    fun durationKindsAreTheTimedOnes() {
        // The routine targets rely on this split.
        assertTrue(ExerciseKind.DURATION.usesDuration && ExerciseKind.CARDIO.usesDuration)
        assertFalse(ExerciseKind.ASSISTED_REPS.usesDuration)
    }
}
