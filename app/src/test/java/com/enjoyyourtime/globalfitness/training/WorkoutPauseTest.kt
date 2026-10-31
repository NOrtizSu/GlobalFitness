package com.enjoyyourtime.globalfitness.training

import com.enjoyyourtime.globalfitness.health.WorkoutExportPlanner
import com.enjoyyourtime.globalfitness.training.data.PauseColumn
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.data.WorkoutEdits
import com.enjoyyourtime.globalfitness.training.domain.LoadModel
import com.enjoyyourtime.globalfitness.training.domain.WorkoutTime
import com.enjoyyourtime.globalfitness.training.live.RestTimers
import com.enjoyyourtime.globalfitness.training.live.WorkoutPausing
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutPause
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import com.enjoyyourtime.globalfitness.training.music.TodayTraining
import com.enjoyyourtime.globalfitness.training.music.TrainingDay
import com.enjoyyourtime.globalfitness.ui.training.TrainingSummaries
import com.enjoyyourtime.globalfitness.ui.training.WorkoutUiEdits
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** Pausing a workout: the active-time math, its storage, the repository, the rest timer and the edits. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutPauseTest {
    private val t0 = 1_700_000_000_000L
    private val min = 60_000L

    private fun workout(start: Long = t0, end: Long? = null, pauses: List<WorkoutPause> = emptyList(), entries: List<WorkoutEntry> = emptyList()) =
        Workout(id = "w", startedAt = start, endedAt = end, name = null, note = null, updatedAt = 1, entries = entries, pauses = pauses)

    // ---- active time -----------------------------------------------------------------------------

    @Test
    fun severalPausesAreLeftOutOfTheActiveTime() {
        val w = workout(end = t0 + 60 * min, pauses = listOf(WorkoutPause(t0 + 10 * min, t0 + 15 * min), WorkoutPause(t0 + 30 * min, t0 + 40 * min)))
        assertEquals(15 * min, WorkoutTime.pausedMs(w.startedAt, w.endedAt!!, w.pauses))
        assertEquals(45 * min, WorkoutTime.activeMs(w, now = t0 + 999 * min))
        assertEquals(45 * min, WorkoutTime.durationMs(w))
        assertEquals(45 * min, TrainingSummaries.summary(w, TrainingRepository::unknownExercise, LoadModel.WITHOUT_BODYWEIGHT).durationMs)
        assertEquals(t0 + 15 * min, WorkoutTime.runningBase(w))
        // Halfway through the second pause only five of its minutes count as paused.
        assertEquals(25 * min, WorkoutTime.activeMs(w.startedAt, t0 + 35 * min, w.pauses))
    }

    @Test
    fun aPausedWorkoutsClockStandsStill() {
        val running = workout(pauses = listOf(WorkoutPause(t0 + 5 * min, t0 + 7 * min)))
        val paused = WorkoutTime.pause(running, t0 + 20 * min)
        assertTrue(paused.paused)
        assertEquals(18 * min, WorkoutTime.activeMs(paused, now = t0 + 20 * min))
        assertEquals(18 * min, WorkoutTime.activeMs(paused, now = t0 + 50 * min))
        assertNull(WorkoutTime.durationMs(paused))
        // Pausing twice, or a finished workout, changes nothing.
        assertSame(paused, WorkoutTime.pause(paused, t0 + 30 * min))
        val finished = workout(end = t0 + 30 * min)
        assertSame(finished, WorkoutTime.pause(finished, t0 + 10 * min))

        val resumed = WorkoutTime.resume(paused, t0 + 50 * min)
        assertFalse(resumed.paused)
        assertEquals(WorkoutPause(t0 + 20 * min, t0 + 50 * min), resumed.pauses.last())
        assertEquals(t0 + 32 * min, WorkoutTime.runningBase(resumed))
        assertEquals(28 * min, WorkoutTime.activeMs(resumed, now = t0 + 60 * min))
        assertSame(resumed, WorkoutTime.resume(resumed, t0 + 70 * min))
    }

    @Test
    fun aPauseNeverStartsBeforeThePreviousOneEndedAndAnEmptyOneIsDropped() {
        val w = workout(pauses = listOf(WorkoutPause(t0 + 10 * min, t0 + 12 * min)))
        // A clock set back: the pause starts where the last one ended.
        assertEquals(WorkoutPause(t0 + 12 * min, null), WorkoutTime.pause(w, t0 + 11 * min).pauses.last())
        val instant = WorkoutTime.resume(WorkoutTime.pause(w, t0 + 20 * min), t0 + 20 * min)
        assertEquals(w.pauses, instant.pauses)
    }

    @Test
    fun normalizeSortsClipsMergesAndClosesPauses() {
        val pauses = listOf(
            WorkoutPause(t0 + 40 * min, t0 + 45 * min),
            WorkoutPause(t0 - 5 * min, t0 + 2 * min), // starts before the workout
            WorkoutPause(t0 + 10 * min, t0 + 20 * min),
            WorkoutPause(t0 + 15 * min, t0 + 25 * min), // overlaps the previous one
            WorkoutPause(t0 + 25 * min, t0 + 26 * min), // touches it
            WorkoutPause(t0 + 30 * min, t0 + 30 * min), // empty
            WorkoutPause(t0 + 55 * min, null), // left open by an older app that finished the workout
            WorkoutPause(t0 + 70 * min, t0 + 80 * min), // after the end
        )
        val expected = listOf(
            WorkoutPause(t0, t0 + 2 * min),
            WorkoutPause(t0 + 10 * min, t0 + 26 * min),
            WorkoutPause(t0 + 40 * min, t0 + 45 * min),
            WorkoutPause(t0 + 55 * min, t0 + 60 * min),
        )
        assertEquals(expected, WorkoutTime.normalize(pauses, t0, t0 + 60 * min))
        // In progress, an open pause stays open and swallows anything after it.
        assertEquals(
            listOf(WorkoutPause(t0 + 5 * min, null)),
            WorkoutTime.normalize(listOf(WorkoutPause(t0 + 8 * min, t0 + 9 * min), WorkoutPause(t0 + 5 * min, null)), t0, null),
        )
    }

    @Test
    fun theDurationOfAPastWorkoutIsItsActiveTimeAndItsPausesMoveWithIt() {
        val pauses = listOf(WorkoutPause(t0 + 10 * min, t0 + 20 * min), WorkoutPause(t0 + 40 * min, t0 + 50 * min))
        val w = workout(end = t0 + 70 * min, pauses = pauses)
        // 30 active minutes end after the first pause (10 + 10 paused + 20): the second one is dropped.
        val end = WorkoutTime.endFor(t0, 30 * min, w.pauses)
        assertEquals(t0 + 40 * min, end)
        val shorter = WorkoutUiEdits.retime(w, t0, end)
        assertEquals(listOf(pauses[0]), shorter.pauses)
        assertEquals(30 * min, WorkoutTime.durationMs(shorter))
        // 50 active minutes: both pauses lengthen it.
        assertEquals(t0 + 70 * min, WorkoutTime.endFor(t0, 50 * min, w.pauses))
        // Moving the start moves the pauses along.
        val moved = WorkoutUiEdits.retime(w, t0 - 60 * min, null)
        assertEquals(WorkoutTime.shifted(pauses, -60 * min), moved.pauses)
        assertEquals(WorkoutTime.durationMs(w), WorkoutTime.durationMs(moved))
        // A workout in progress keeps its real times: only pauses before the new start are cut.
        val live = workout(pauses = pauses)
        assertEquals(listOf(WorkoutPause(t0 + 15 * min, t0 + 20 * min), pauses[1]), WorkoutUiEdits.retime(live, t0 + 15 * min, null).pauses)
    }

    @Test
    fun loggingASetWhilePausedResumesTheWorkoutWhenTheSetBegan() {
        val set = WorkoutSet(id = "s", weightKg = 60.0, reps = 8)
        val w = WorkoutTime.pause(workout(entries = listOf(WorkoutEntry("e", "barbell_bench_press", listOf(set)))), t0 + 10 * min)
        val ticked = WorkoutUiEdits.tick(w, "e", "s", set, done = true, now = t0 + 14 * min)
        assertFalse(ticked.paused)
        // 8 reps take about 24 s: the workout runs again from then, so the set lies after the pause.
        val resumedAt = t0 + 14 * min - 24_000
        assertEquals(resumedAt, WorkoutUiEdits.resumeAtForTick(w, set, t0 + 14 * min))
        assertEquals(listOf(WorkoutPause(t0 + 10 * min, resumedAt)), ticked.pauses)
        assertEquals(t0 + 14 * min, ticked.entries[0].sets[0].doneAt)
        val segments = WorkoutExportPlanner.segments(
            listOf(WorkoutExportPlanner.LoggedSet(1, 8, WorkoutExportPlanner.nominalMs(set), t0 + 14 * min)),
            t0,
            t0 + 20 * min,
            ticked.pauses,
        )
        assertEquals(listOf(t0 + 10 * min to resumedAt, resumedAt to t0 + 14 * min), segments.map { it.start to it.end })
        // A set longer than the pause (a 5-minute hold ticked 3 minutes after pausing): the pause never was.
        val hold = WorkoutSet(id = "s", durationSec = 300)
        val held = WorkoutUiEdits.tick(w, "e", "s", hold, done = true, now = t0 + 13 * min)
        assertFalse(held.paused)
        assertTrue(held.pauses.isEmpty())
        // Unticking does not resume; a running workout is left as it is.
        assertTrue(WorkoutUiEdits.tick(w, "e", "s", set, done = false, now = t0 + 14 * min).paused)
        assertEquals(t0 + 14 * min, WorkoutUiEdits.resumeAtForTick(workout(), set, t0 + 14 * min))
    }

    @Test
    fun aSetLoggedWhilePausedResumesTheRestFromWhenTheWorkoutResumed() {
        var clock = t0
        val rest = RestTimers(clock = { clock })
        rest.start("w", 90)
        clock = t0 + 30_000
        rest.pause("w", at = clock) // 60 s left
        val set = WorkoutSet(id = "s", weightKg = 60.0, reps = 8)
        val w = WorkoutTime.pause(workout(entries = listOf(WorkoutEntry("e", "barbell_bench_press", listOf(set)))), t0 + 30_000)
        clock = t0 + 5 * min
        // As the workout's page does: the rest goes on from the moment the workout ran again.
        val resumedAt = WorkoutUiEdits.resumeAtForTick(w, set, clock)
        rest.resume("w", at = resumedAt)
        assertEquals(resumedAt + 60_000, rest.state.value!!.endsAt)
        assertFalse(rest.state.value!!.paused)
    }

    @Test
    fun settingTheDurationOfAPastWorkoutKeepsItsPausesAndNeverEndsInTheFuture() {
        val pauses = listOf(WorkoutPause(t0 + 10 * min, t0 + 20 * min))
        val w = workout(end = t0 + 40 * min, pauses = pauses)
        // As the duration chip does: 45 active minutes end after the pause, 55 minutes after the start.
        val now = t0 + 50 * min
        val longer = WorkoutUiEdits.retime(w, w.startedAt, WorkoutTime.endFor(w.startedAt, 45 * min, w.pauses).coerceAtMost(now))
        assertEquals(t0 + 50 * min, longer.endedAt) // 55 would be in the future
        assertEquals(pauses, longer.pauses)
        assertEquals(40 * min, WorkoutTime.durationMs(longer))
        val shorter = WorkoutUiEdits.retime(w, w.startedAt, WorkoutTime.endFor(w.startedAt, 5 * min, w.pauses).coerceAtMost(now))
        assertEquals(t0 + 5 * min, shorter.endedAt)
        assertTrue(shorter.pauses.isEmpty())
    }

    @Test
    fun theRepositoryStoresPausesNormalizedWhateverTheEdit() = runTest {
        val store = FakeTrainingStore()
        val repo = repo(store)
        advanceUntilIdle()
        val started = repo.startWorkout(at = t0)
        val messy = listOf(WorkoutPause(t0 + 20 * min, t0 + 25 * min), WorkoutPause(t0 - 5 * min, t0 + 2 * min), WorkoutPause(t0 + 22 * min, t0 + 30 * min))
        val stored = repo.updateWorkout(started.id) { it.copy(endedAt = t0 + 40 * min, pauses = messy) }!!
        assertEquals(listOf(WorkoutPause(t0, t0 + 2 * min), WorkoutPause(t0 + 20 * min, t0 + 30 * min)), stored.pauses)
        assertEquals(stored, store.workouts.getValue(started.id))
        // Already normalized: no new version.
        assertSame(stored, repo.updateWorkout(started.id) { it.copy(pauses = messy) })
    }

    @Test
    fun yesterdaysHardDayCountsActiveMinutesOnly() {
        val zone = ZoneOffset.UTC
        val day = Instant.ofEpochMilli(t0).atZone(zone).toLocalDate()
        val sets = (0 until 3).map { WorkoutSet(id = "s$it", reps = 10, done = true, doneAt = t0 + (it + 1) * 40 * min) }
        val long = workout(end = t0 + 125 * min, entries = listOf(WorkoutEntry("e", "barbell_squat", sets)))
        val exercise = { id: String -> TrainingRepository.unknownExercise(id) }
        assertEquals(TrainingDay.RECOVERY, TodayTraining.classify(listOf(long), day.plusDays(1), zone, t0 + 30 * 60 * min, exercise))
        // The same two hours with an hour of pause: not a hard day.
        val paused = long.copy(pauses = listOf(WorkoutPause(t0 + 45 * min, t0 + 105 * min)))
        assertTrue(TodayTraining.classify(listOf(paused), day.plusDays(1), zone, t0 + 30 * 60 * min, exercise) != TrainingDay.RECOVERY)
    }

    // ---- storage ---------------------------------------------------------------------------------

    @Test
    fun theDatabaseColumnRoundTripsAndSkipsWhatItCannotRead() {
        val pauses = listOf(WorkoutPause(t0, t0 + min), WorkoutPause(t0 + 5 * min, t0 + 9 * min), WorkoutPause(t0 + 20 * min, null))
        val text = PauseColumn.encode(pauses)
        assertEquals("$t0:${t0 + min};${t0 + 5 * min}:${t0 + 9 * min};${t0 + 20 * min}:", text)
        assertEquals(pauses, PauseColumn.decode(text))
        // Workouts stored before the column existed (null) and without pauses.
        assertNull(PauseColumn.encode(emptyList()))
        assertEquals(emptyList<WorkoutPause>(), PauseColumn.decode(null))
        assertEquals(listOf(WorkoutPause(5, 6)), PauseColumn.decode("x:1;5:6;7;8:y;"))
    }

    // ---- repository --------------------------------------------------------------------------------

    private var now = t0
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun TestScope.repo(store: FakeTrainingStore = FakeTrainingStore()): TrainingRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        var id = 0
        return TrainingRepository(store, scope, io = dispatcher, compute = dispatcher, clock = { now }, newId = { "id${id++}" })
    }

    @Test
    fun pauseResumeAndFinishWhilePausedAreStored() = runTest {
        val store = FakeTrainingStore()
        val repo = repo(store)
        advanceUntilIdle()
        val started = repo.startWorkout(at = t0)
        val withSet = repo.updateWorkout(started.id) { WorkoutEdits.addExercise(it, "barbell_bench_press", emptyList()) { "set" } }!!
        val entry = withSet.entries[0]
        repo.updateWorkout(started.id) { WorkoutEdits.setDone(it, entry.id, entry.sets[0].id, true, t0 + 5 * min) }

        now = t0 + 10 * min
        val paused = repo.pauseWorkout(started.id)!!
        assertTrue(paused.paused)
        advanceUntilIdle()
        assertTrue(repo.activeWorkout.value!!.paused)
        assertTrue(store.workouts.getValue(started.id).paused)

        now = t0 + 20 * min
        val resumed = repo.resumeWorkout(started.id)!!
        assertEquals(listOf(WorkoutPause(t0 + 10 * min, t0 + 20 * min)), resumed.pauses)

        now = t0 + 30 * min
        repo.pauseWorkout(started.id)
        // Finished while paused: the pause ends with it and does not count.
        val finished = repo.finishWorkout(started.id, at = t0 + 45 * min)!!
        assertEquals(listOf(WorkoutPause(t0 + 10 * min, t0 + 20 * min), WorkoutPause(t0 + 30 * min, t0 + 45 * min)), finished.pauses)
        assertEquals(20 * min, WorkoutTime.durationMs(finished))
        assertEquals(finished, store.workouts.getValue(started.id))
        // A finished workout can no longer be paused.
        assertEquals(finished, repo.pauseWorkout(started.id))
        assertNull(repo.pauseWorkout("missing"))
    }

    @Test
    fun pausingTheWorkoutPausesItsRestTimer() = runTest {
        val repo = repo()
        advanceUntilIdle()
        val w = repo.startWorkout(at = t0)
        val rest = RestTimers(clock = { now })
        val pausing = WorkoutPausing(repo, rest, clock = { now })
        rest.start(w.id, 90)
        now = t0 + 30_000
        pausing.pause(w.id)
        assertEquals(60_000L, rest.state.value!!.pausedLeftMs)
        // The countdown stands still: 60 s left, however long the pause lasts.
        now = t0 + 10 * min
        assertEquals(60_000L, rest.state.value!!.leftMs(now))
        rest.adjust(15)
        assertEquals(75_000L, rest.state.value!!.leftMs(now))
        pausing.resume(w.id)
        val running = rest.state.value!!
        assertFalse(running.paused)
        assertEquals(now + 75_000, running.endsAt)
        advanceUntilIdle()
        assertFalse(repo.activeWorkout.value!!.paused)
    }

    @Test
    fun restTimersOfOtherWorkoutsOrThatEndedAreLeftAlone() {
        var clock = 0L
        val rest = RestTimers(clock = { clock })
        rest.start("a", 60)
        rest.pause("b", at = 10_000)
        assertFalse(rest.state.value!!.paused)
        rest.pause("a", at = 70_000) // already over
        assertFalse(rest.state.value!!.paused)
        clock = 0
        rest.adjust(-60)
        assertNull(rest.state.value)
        rest.start("a", 30)
        rest.pause("a", at = 10_000)
        rest.adjust(-30) // only 20 s were left
        assertNull(rest.state.value)
        rest.start("a", 30)
        rest.clear("b")
        assertTrue(rest.state.value != null)
        rest.clear("a")
        assertNull(rest.state.value)
    }
}
