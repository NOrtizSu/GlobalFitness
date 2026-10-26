package com.enjoyyourtime.globalfitness.training

import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.training.data.TrainingBatch
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.data.WorkoutEdits
import com.enjoyyourtime.globalfitness.training.model.CustomExercise
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.RoutineExercise
import com.enjoyyourtime.globalfitness.training.model.Tombstone
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrainingRepositoryTest {
    private var now = 1_000_000L
    private var nextId = 0

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    /**
     * The repository's flows run on a foreground test dispatcher (backgroundScope work is not run by
     * advanceUntilIdle) in a scope outside runTest, cancelled after each test.
     */
    private fun TestScope.repo(store: FakeTrainingStore = FakeTrainingStore()): TrainingRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        return TrainingRepository(
            store = store,
            scope = scope,
            io = dispatcher,
            compute = dispatcher,
            clock = { now },
            newId = { "id${nextId++}" },
        )
    }

    @Test
    fun startEditAndFinishWorkout() = runTest {
        val store = FakeTrainingStore()
        val repo = repo(store)
        advanceUntilIdle()
        val w = repo.startWorkout()
        advanceUntilIdle()
        assertEquals(w, repo.activeWorkout.value)
        now += 1000
        val withExercise = repo.updateWorkout(w.id) { WorkoutEdits.addExercise(it, "barbell_bench_press", emptyList()) { "id${nextId++}" } }!!
        assertEquals(1, withExercise.entries.size)
        assertEquals(1, withExercise.entries[0].sets.size)
        assertTrue(withExercise.updatedAt > w.updatedAt)

        val entry = withExercise.entries[0]
        val set = entry.sets[0]
        repo.updateWorkout(w.id) { WorkoutEdits.updateSet(it, entry.id, set.id) { s -> s.copy(weightKg = 80.0, reps = 5) } }
        repo.updateWorkout(w.id) { WorkoutEdits.setDone(it, entry.id, set.id, true, now) }
        val finished = repo.finishWorkout(w.id, now + 60_000)!!
        advanceUntilIdle()
        assertFalse(finished.inProgress)
        assertNull(repo.activeWorkout.value)
        assertEquals(finished, store.workouts[w.id])
        assertEquals(80.0, store.workouts[w.id]!!.entries[0].sets[0].weightKg!!, 0.0)
    }

    @Test
    fun finishingAnEmptyWorkoutDeletesIt() = runTest {
        val store = FakeTrainingStore()
        val repo = repo(store)
        advanceUntilIdle()
        val w = repo.startWorkout()
        assertNull(repo.finishWorkout(w.id))
        assertTrue(repo.workouts.value.isEmpty())
        assertEquals(Tombstone.WORKOUT, store.tombstones.values.single().kind)
    }

    @Test
    fun templateCopiesWorkingSetsAsPlanned() = runTest {
        val repo = repo()
        advanceUntilIdle()
        val template = Workout(
            id = "t", startedAt = 0, endedAt = 10, name = "Push", note = null, updatedAt = 0,
            entries = listOf(
                com.enjoyyourtime.globalfitness.training.model.WorkoutEntry(
                    id = "e", exerciseId = "barbell_bench_press",
                    sets = listOf(
                        WorkoutSet("s0", weightKg = 40.0, reps = 10, warmup = true, done = true, doneAt = 1),
                        WorkoutSet("s1", weightKg = 80.0, reps = 5, done = true, doneAt = 2),
                    ),
                ),
            ),
        )
        val w = repo.startWorkout(template = template)
        assertEquals("Push", w.name)
        val sets = w.entries.single().sets
        assertEquals(1, sets.size)
        assertEquals(80.0, sets[0].weightKg!!, 0.0)
        assertFalse(sets[0].done)
        assertTrue(sets[0].id != "s1")
    }

    @Test
    fun usedCustomExerciseIsArchivedUnusedIsDeleted() = runTest {
        val store = FakeTrainingStore()
        val repo = repo(store)
        advanceUntilIdle()
        val used = repo.createCustomExercise(" My press ", ExerciseKind.WEIGHT_REPS, Equipment.MACHINE, setOf(Muscle.CHEST), setOf(Muscle.CHEST, Muscle.TRICEPS))
        assertEquals("My press", used.name)
        assertEquals(setOf(Muscle.TRICEPS), used.secondary)
        assertTrue(used.id.startsWith("u:"))
        val unused = repo.createCustomExercise("Other", ExerciseKind.CARDIO, Equipment.CARDIO_MACHINE, emptySet(), emptySet())
        repo.updateNote(unused.id) { it.copy(notes = "seat 3") }

        val w = repo.startWorkout()
        repo.updateWorkout(w.id) { WorkoutEdits.addExercise(it, used.id, emptyList()) { "x${nextId++}" } }

        repo.removeCustomExercise(used.id)
        repo.removeCustomExercise(unused.id)
        assertTrue(store.custom[used.id]!!.archived)
        assertNull(store.custom[unused.id])
        assertNull(repo.notes.value[unused.id])
        assertEquals(setOf(Tombstone.CUSTOM_EXERCISE, Tombstone.NOTE), store.tombstones.values.map { it.kind }.toSet())
        advanceUntilIdle()
        assertTrue(repo.exercises.value[used.id]!!.archived)
        assertNull(repo.exercises.value[unused.id])
    }

    @Test
    fun customExerciseOnlyARoutineUsesIsArchivedToo() = runTest {
        val store = FakeTrainingStore()
        val repo = repo(store)
        advanceUntilIdle()
        val machine = repo.createCustomExercise("Hip thrust 2", ExerciseKind.WEIGHT_REPS, Equipment.MACHINE, setOf(Muscle.GLUTES), emptySet())
        val routine = repo.createRoutine("Glutes", listOf(RoutineExercise(machine.id, 3, 10)))

        assertTrue(repo.removeCustomExercise(machine.id))
        assertTrue(store.custom[machine.id]!!.archived)
        assertTrue(store.tombstones.isEmpty())
        advanceUntilIdle()
        // The routine still names it, so starting it never plans an unknown exercise.
        assertEquals(UiText.raw("Hip thrust 2"), repo.exercise(machine.id).name)

        // Once no routine names it, removing it deletes it.
        repo.deleteRoutine(routine.id)
        assertFalse(repo.removeCustomExercise(machine.id))
        assertNull(store.custom[machine.id])
        assertFalse(repo.removeCustomExercise("u:gone"))
    }

    @Test
    fun emptyNoteIsDeleted() = runTest {
        val store = FakeTrainingStore()
        val repo = repo(store)
        advanceUntilIdle()
        assertNotNull(repo.updateNote("leg_press_machine") { it.copy(notes = "  seat 5  ", favorite = true) })
        assertEquals("seat 5", store.notes["leg_press_machine"]!!.notes)
        advanceUntilIdle()
        assertTrue(repo.exercises.value["leg_press_machine"]!!.favorite)
        assertNull(repo.updateNote("leg_press_machine") { it.copy(notes = "", favorite = false) })
        assertNull(store.notes["leg_press_machine"])
    }

    @Test
    fun remoteBatchReplacesAndRemovesWithoutTombstones() = runTest {
        val existing = Workout("a", 1, 2, null, null, 1)
        val store = FakeTrainingStore(workouts = listOf(existing, Workout("b", 3, 4, null, null, 1)))
        val repo = repo(store)
        advanceUntilIdle()
        repo.applyRemote(TrainingBatch(upsertWorkouts = listOf(existing.copy(name = "Legs", updatedAt = 5)), removeWorkouts = listOf("b")))
        assertEquals(listOf("a"), repo.workouts.value.map { it.id })
        assertEquals("Legs", repo.workouts.value[0].name)
        assertTrue(store.tombstones.isEmpty())
    }

    @Test
    fun customExercisesAreKnownOnceLoaded() = runTest {
        val custom = CustomExercise("u:row", "Rowing", ExerciseKind.CARDIO, Equipment.CARDIO_MACHINE, emptySet(), emptySet(), archived = false, createdAt = 1, updatedAt = 1)
        val repo = repo(FakeTrainingStore(custom = listOf(custom)))
        var seen: Exercise? = null
        // What a screen (or the achievements) reads the moment the data says it is loaded.
        launch {
            repo.loaded.first { it }
            seen = repo.exercises.value[custom.id]
        }
        advanceUntilIdle()
        assertEquals(ExerciseKind.CARDIO, seen?.kind)
    }

    @Test
    fun remoteVersionsFollowTheCloudsWorkouts() = runTest {
        val repo = repo()
        advanceUntilIdle()
        val synced = Workout("r", 1, 2, null, null, 5)
        repo.applyRemote(TrainingBatch(upsertWorkouts = listOf(synced)))
        assertEquals(mapOf("r" to 5L), repo.remoteVersions.value)
        // Edited here: another version, this phone's.
        now = 10_000_000L
        val edited = repo.updateWorkout("r") { it.copy(name = "Legs") }!!
        assertTrue(repo.remoteVersions.value["r"] != edited.updatedAt)
        repo.applyRemote(TrainingBatch(removeWorkouts = listOf("r")))
        assertTrue(repo.remoteVersions.value.isEmpty())
    }
}
