package com.enjoyyourtime.globalfitness.training

import com.enjoyyourtime.globalfitness.health.WorkoutExerciseInfo
import com.enjoyyourtime.globalfitness.health.WorkoutExportPlanner
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.domain.LoadModel
import com.enjoyyourtime.globalfitness.training.domain.WorkoutTime
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutPause
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import com.enjoyyourtime.globalfitness.training.music.TodayTraining
import com.enjoyyourtime.globalfitness.training.music.TrainingDay
import com.enjoyyourtime.globalfitness.ui.training.TrainingSummaries
import com.enjoyyourtime.globalfitness.ui.training.WorkoutUiEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * A sport or cardio session logged after it was done ("football, 1:30:00" ticked in a workout
 * started a minute ago): the workout lasts the time logged, in the history, Health Connect and the
 * music's "hard day" alike.
 */
class LoggedTimeTest {
    private val t0 = 1_700_000_000_000L
    private val min = 60_000L
    private val exercises = TrainingRepository.buildExercises(ExerciseCatalog.all, emptyList(), emptyMap())
    private val lookup = { id: String -> exercises[id] ?: TrainingRepository.unknownExercise(id) }
    private val timed = { id: String -> lookup(id).kind.usesDuration }

    private fun football(seconds: Int = 5_400, doneAt: Long?) = WorkoutEntry("e-f", "football", listOf(WorkoutSet("s-f", durationSec = seconds, done = doneAt != null, doneAt = doneAt)))

    private fun workout(start: Long, end: Long?, vararg entries: WorkoutEntry, pauses: List<WorkoutPause> = emptyList(), id: String = "w") =
        Workout(id, start, end, null, null, 1, entries.toList(), pauses)

    @Test
    fun aMatchLoggedInAWorkoutStartedTwoMinutesAgoMovesItsStartBack() {
        val now = t0 + 2 * min
        val live = workout(t0, null, football(doneAt = now), WorkoutEntry("e-b", "barbell_bench_press", listOf(WorkoutSet("s-b", weightKg = 60.0, reps = 8, done = true, doneAt = t0 + min))))
        assertEquals(90 * min, WorkoutTime.loggedMs(live, timed))
        val fitted = WorkoutTime.fitToLogged(live, WorkoutTime.loggedMs(live, timed), now, notBefore = null)
        assertEquals(now - 90 * min, fitted.startedAt)
        assertEquals(90 * min, WorkoutTime.activeMs(fitted, now))
        // Finished a moment later: the history, the summary and Health Connect say 1:30 (and a bit).
        val finished = fitted.copy(endedAt = now + 5_000)
        assertTrue(WorkoutTime.durationMs(finished)!! >= 90 * min)
        assertTrue(TrainingSummaries.summary(finished, lookup, LoadModel.WITHOUT_BODYWEIGHT).durationMs!! >= 90 * min)
        val session = WorkoutExportPlanner.session(finished) { WorkoutExerciseInfo.of(lookup(it)) { null } }
        assertTrue(session.end - session.start >= 90 * min)
        assertTrue("the match is one long segment", session.segments.any { it.end - it.start >= 89 * min })
        // The next day is a recovery day: 90 minutes of football.
        val zone = ZoneOffset.UTC
        val day = Instant.ofEpochMilli(finished.startedAt).atZone(zone).toLocalDate()
        assertEquals(TrainingDay.RECOVERY, TodayTraining.classify(listOf(finished), day.plusDays(1), zone, finished.endedAt!! + 20 * 60 * min, lookup))
    }

    @Test
    fun itsPausesStayAndOnlyTheActiveTimeIsFitted() {
        val pauses = listOf(WorkoutPause(t0 + min, t0 + 3 * min))
        val now = t0 + 5 * min
        val live = workout(t0, null, football(seconds = 30 * 60, doneAt = now), pauses = pauses)
        val fitted = WorkoutTime.fitToLogged(live, WorkoutTime.loggedMs(live, timed), now, notBefore = null)
        assertEquals(pauses, fitted.pauses)
        assertEquals(30 * min, WorkoutTime.activeMs(fitted, now))
        assertEquals(now - 32 * min, fitted.startedAt)
    }

    @Test
    fun itNeverMovesOverThePreviousWorkout() {
        val before = workout(t0 - 60 * min, t0 - 30 * min, id = "before")
        val now = t0 + 2 * min
        val live = workout(t0, null, football(doneAt = now))
        val previousEnd = WorkoutTime.previousEnd(listOf(before, live), live)
        assertEquals(t0 - 30 * min, previousEnd)
        val fitted = WorkoutTime.fitToLogged(live, WorkoutTime.loggedMs(live, timed), now, previousEnd)
        assertEquals(t0 - 30 * min, fitted.startedAt)
        // A workout that started later is no bound, and one that overlaps leaves the start where it is.
        assertEquals(null, WorkoutTime.previousEnd(listOf(live, workout(t0 + min, t0 + 2 * min, id = "later")), live))
        assertSame(live, WorkoutTime.fitToLogged(live, 90 * min, now, notBefore = t0 + min))
    }

    @Test
    fun aWorkoutAlreadyLongEnoughIsLeftAsItIs() {
        val now = t0 + 100 * min
        val live = workout(t0, null, football(doneAt = now))
        assertSame(live, WorkoutTime.fitToLogged(live, WorkoutTime.loggedMs(live, timed), now, null))
        // Sets not ticked and strength sets log no time.
        val planned = workout(t0, null, football(doneAt = null))
        assertEquals(0L, WorkoutTime.loggedMs(planned, timed))
    }

    @Test
    fun aPastWorkoutLoggedAfterwardsIsExtendedAndItsSetsMoveWithItsEnd() {
        // "Log a past workout": an hour by default, its sets done at its end.
        val start = t0 - 10 * 24 * 60 * min
        val past = workout(start, start + 60 * min, football(doneAt = start + 60 * min))
        val fitted = WorkoutTime.fitToLogged(past, WorkoutTime.loggedMs(past, timed), now = t0, notBefore = null)
        assertEquals(start, fitted.startedAt)
        assertEquals(start + 90 * min, fitted.endedAt)
        assertEquals(start + 90 * min, fitted.entries.single().sets.single().doneAt)
        assertEquals(90 * min, WorkoutTime.durationMs(fitted))
        // Undo: the hour and the set's time come back.
        assertEquals(past, WorkoutTime.unfit(fitted, past, fitted))
        // With a pause inside it, the end moves past the pause too.
        val paused = past.copy(pauses = listOf(WorkoutPause(start + 20 * min, start + 30 * min)))
        val withPause = WorkoutTime.fitToLogged(paused, WorkoutTime.loggedMs(paused, timed), now = t0, notBefore = null)
        assertEquals(start + 100 * min, withPause.endedAt)
        assertEquals(90 * min, WorkoutTime.durationMs(withPause))
    }

    @Test
    fun aPastWorkoutOfTodayCannotEndInTheFutureSoItsStartMovesBack() {
        val now = t0
        val past = workout(now - 60 * min, now, football(doneAt = now))
        val fitted = WorkoutTime.fitToLogged(past, WorkoutTime.loggedMs(past, timed), now, notBefore = now - 80 * min)
        assertEquals(now, fitted.endedAt)
        assertEquals(now - 80 * min, fitted.startedAt) // the previous workout ended then
        val free = WorkoutTime.fitToLogged(past, WorkoutTime.loggedMs(past, timed), now, notBefore = null)
        assertEquals(now - 90 * min, free.startedAt)
        assertEquals(90 * min, WorkoutTime.durationMs(free))
    }

    @Test
    fun undoPutsTheStartBackUnlessTheWorkoutWasMovedSince() {
        val now = t0 + 2 * min
        val live = workout(t0, null, football(doneAt = now))
        val fitted = WorkoutTime.fitToLogged(live, WorkoutTime.loggedMs(live, timed), now, null)
        assertEquals(live, WorkoutTime.unfit(fitted, live, fitted))
        // A set ticked since stays.
        val more = fitted.copy(entries = fitted.entries + WorkoutEntry("e-w", "outdoor_walk", listOf(WorkoutSet("s-w", durationSec = 600, done = true, doneAt = now + min))))
        assertEquals(t0, WorkoutTime.unfit(more, live, fitted).startedAt)
        assertEquals(2, WorkoutTime.unfit(more, live, fitted).entries.size)
        // The user moved the start meanwhile: left alone.
        val moved = WorkoutUiEdits.retime(fitted, fitted.startedAt - 5 * min, null)
        assertSame(moved, WorkoutTime.unfit(moved, live, fitted))
    }
}
