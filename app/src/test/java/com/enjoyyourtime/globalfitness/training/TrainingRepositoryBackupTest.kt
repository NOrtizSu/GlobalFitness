package com.enjoyyourtime.globalfitness.training

import com.enjoyyourtime.globalfitness.training.data.TrainingBatch
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.data.TrainingVersions
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.RoutineExercise
import com.enjoyyourtime.globalfitness.training.model.Tombstone
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the cloud backup relies on in [TrainingRepository]: tombstone times and safe remote applies. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainingRepositoryBackupTest {
    private var now = 10_000L
    private var nextId = 0
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun TestScope.repo(store: FakeTrainingStore): TrainingRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        return TrainingRepository(store, scope, io = dispatcher, compute = dispatcher, clock = { now }, newId = { "id${nextId++}" })
    }

    @Test
    fun deletionsAreNeverOlderThanTheVersionTheyDelete() = runTest {
        val store = FakeTrainingStore()
        val repo = repo(store)
        advanceUntilIdle()
        // Versions written "in the future" (another phone's clock ran ahead), then deleted here.
        now = 50_000
        val w = repo.startWorkout()
        repo.updateNote("leg_press_machine") { it.copy(notes = "seat 4") }
        val machine = repo.createCustomExercise("Unused", ExerciseKind.WEIGHT_REPS, Equipment.MACHINE, emptySet(), emptySet())
        repo.updateNote(machine.id) { it.copy(notes = "x") }
        val routine = repo.createRoutine("Legs", listOf(RoutineExercise("leg_press_machine", 3, reps = 10)))
        now = 10_000

        repo.deleteWorkout(w.id)
        repo.updateNote("leg_press_machine") { it.copy(notes = null) }
        repo.removeCustomExercise(machine.id)
        repo.deleteRoutine(routine.id)
        val tombstones = store.tombstones.values.associateBy { it.kind to it.id }
        assertEquals(50_001L, tombstones.getValue(Tombstone.WORKOUT to w.id).deletedAt)
        assertEquals(50_001L, tombstones.getValue(Tombstone.NOTE to "leg_press_machine").deletedAt)
        assertEquals(50_001L, tombstones.getValue(Tombstone.CUSTOM_EXERCISE to machine.id).deletedAt)
        assertEquals(50_001L, tombstones.getValue(Tombstone.NOTE to machine.id).deletedAt)
        assertEquals(50_001L, tombstones.getValue(Tombstone.ROUTINE to routine.id).deletedAt)
    }

    @Test
    fun aRemoteApplyLeavesRoutinesChangedSinceTheSyncReadThem() = runTest {
        val store = FakeTrainingStore()
        val repo = repo(store)
        advanceUntilIdle()
        val edited = repo.createRoutine("Push", listOf(RoutineExercise("barbell_bench_press", 3, reps = 8)))
        val removed = repo.createRoutine("Pull", listOf(RoutineExercise("lat_pulldown_machine", 3, reps = 10)))
        val untouched = repo.createRoutine("Core", listOf(RoutineExercise("plank", 3, durationSec = 45)))
        val seen = TrainingVersions(routines = repo.routines.value.associate { it.id to it.updatedAt })

        // While the sync runs, the user edits one routine and creates another.
        now += 1_000
        repo.updateRoutine(edited.id) { it.copy(name = "Push (mine)") }
        val created = repo.createRoutine("New", listOf(RoutineExercise("dips", 3, reps = 10)))

        val fromCloud = TrainingBatch(
            upsertRoutines = listOf(
                edited.copy(name = "Push (cloud)", updatedAt = 99_000),
                created.copy(name = "Cloud", updatedAt = 99_000),
                untouched.copy(note = "cloud", updatedAt = 99_000),
            ),
            removeRoutines = listOf(removed.id),
        )
        val applied = repo.applyRemoteIfUnchanged(fromCloud, seen)
        assertEquals(listOf(untouched.id), applied.upsertRoutines.map { it.id })
        assertEquals(listOf(removed.id), applied.removeRoutines)
        assertEquals("Push (mine)", repo.routine(edited.id)!!.name)
        assertEquals("New", repo.routine(created.id)!!.name)
        assertEquals("cloud", repo.routine(untouched.id)!!.note)
        assertNull(repo.routine(removed.id))
        assertTrue(store.tombstones.isEmpty()) // removals from the cloud leave none
    }

    @Test
    fun finishingDecidesUnderTheLock() = runTest {
        val store = FakeTrainingStore()
        val repo = repo(store)
        advanceUntilIdle()
        val w = repo.startWorkout()
        repo.updateWorkout(w.id) { it.copy(entries = listOf(WorkoutEntry("e", "squat", listOf(WorkoutSet("s", reps = 5, done = true, doneAt = now))))) }
        now += 1_000
        val finished = repo.finishWorkout(w.id, at = now)
        assertNotNull(finished)
        assertEquals(now, finished!!.endedAt)
        assertEquals(finished, store.workouts[w.id])

        val empty = repo.startWorkout()
        assertNull(repo.finishWorkout(empty.id))
        assertNull(store.workouts[empty.id])
        assertNull(repo.finishWorkout("gone"))
    }

    @Test
    fun aRemoteRemovalNeverDeletesAMachineAWorkoutUses() = runTest {
        val store = FakeTrainingStore()
        val repo = repo(store)
        advanceUntilIdle()
        val machine = repo.createCustomExercise("Leg press 2", ExerciseKind.WEIGHT_REPS, Equipment.MACHINE, emptySet(), emptySet())
        val unused = repo.createCustomExercise("Old", ExerciseKind.WEIGHT_REPS, Equipment.MACHINE, emptySet(), emptySet())
        val w = repo.startWorkout()
        repo.updateWorkout(w.id) { it.copy(entries = listOf(WorkoutEntry("e", machine.id))) }
        val seen = TrainingVersions(
            workouts = repo.workouts.value.associate { it.id to it.updatedAt },
            customExercises = repo.customExercises.value.associate { it.id to it.updatedAt },
        )
        val applied = repo.applyRemoteIfUnchanged(TrainingBatch(removeCustomExercises = listOf(machine.id, unused.id)), seen)
        assertEquals(listOf(unused.id), applied.removeCustomExercises)
        assertEquals(listOf(machine.id), repo.customExercises.value.map { it.id })
        assertTrue(store.tombstones.isEmpty()) // removals from the cloud leave none
    }

    @Test
    fun aRemoteRemovalNeverDeletesAMachineARoutineUses() = runTest {
        val store = FakeTrainingStore()
        val repo = repo(store)
        advanceUntilIdle()
        val mine = repo.createCustomExercise("Row 2", ExerciseKind.WEIGHT_REPS, Equipment.MACHINE, emptySet(), emptySet())
        val arriving = repo.createCustomExercise("Fly 2", ExerciseKind.WEIGHT_REPS, Equipment.MACHINE, emptySet(), emptySet())
        val dropped = repo.createCustomExercise("Old", ExerciseKind.WEIGHT_REPS, Equipment.MACHINE, emptySet(), emptySet())
        val routine = repo.createRoutine("Back", listOf(RoutineExercise(mine.id, 3, 10)))
        val leaving = repo.createRoutine("Old one", listOf(RoutineExercise(dropped.id, 3, 10)))
        val seen = TrainingVersions(
            customExercises = repo.customExercises.value.associate { it.id to it.updatedAt },
            routines = repo.routines.value.associate { it.id to it.updatedAt },
        )
        // A routine in the same batch counts too; one the batch removes no longer does.
        val incoming = routine.copy(id = "r-cloud", name = "Chest", exercises = listOf(RoutineExercise(arriving.id, 3, 12)))
        val applied = repo.applyRemoteIfUnchanged(
            TrainingBatch(
                removeCustomExercises = listOf(mine.id, arriving.id, dropped.id),
                upsertRoutines = listOf(incoming),
                removeRoutines = listOf(leaving.id),
            ),
            seen,
        )
        assertEquals(listOf(dropped.id), applied.removeCustomExercises)
        assertEquals(listOf(mine.id, arriving.id), repo.customExercises.value.map { it.id })
        assertEquals(setOf(routine.id, incoming.id), repo.routines.value.map { it.id }.toSet())
    }
}
