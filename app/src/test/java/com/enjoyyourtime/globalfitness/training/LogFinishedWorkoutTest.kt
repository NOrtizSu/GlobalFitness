package com.enjoyyourtime.globalfitness.training

import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogFinishedWorkoutTest {
    private var scope: CoroutineScope? = null

    @After
    fun cancelScope() {
        scope?.cancel()
    }

    @Test
    fun aWorkoutLoggedForAnotherDayIsNeverInProgress() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FakeTrainingStore()
        val repoScope = CoroutineScope(SupervisorJob() + dispatcher).also { scope = it }
        var ids = 0
        val repo = TrainingRepository(store, repoScope, io = dispatcher, compute = dispatcher, clock = { 5_000_000L }, newId = { "past${ids++}" })
        advanceUntilIdle()

        val w = repo.logFinishedWorkout(start = 1_000_000L, end = 4_600_000L)
        advanceUntilIdle()

        assertEquals("past0", w.id)
        assertFalse(w.inProgress)
        assertEquals(4_600_000L, w.endedAt)
        assertNull(repo.activeWorkout.value)
        assertEquals(listOf(w), store.loadWorkouts())
        // An end before the start is clamped to the start.
        assertEquals(2_000_000L, repo.logFinishedWorkout(start = 2_000_000L, end = 1_000L).endedAt)
    }
}
