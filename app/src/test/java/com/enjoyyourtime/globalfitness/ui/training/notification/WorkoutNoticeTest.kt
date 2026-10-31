package com.enjoyyourtime.globalfitness.ui.training.notification

import com.enjoyyourtime.globalfitness.training.live.RestTimer
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutPause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the workout's ongoing notification shows while running, paused and resting. */
class WorkoutNoticeTest {
    private val t0 = 1_700_000_000_000L
    private val min = 60_000L

    private val texts = object : WorkoutNoticeTexts {
        override fun running(workingSets: Int) = "In progress · $workingSets"

        override fun paused(activeMs: Long) = "Paused · ${activeMs / 1000}"

        override fun rest() = "Rest"
    }

    private val workout = Workout("w", t0, null, "Legs", null, 1, pauses = listOf(WorkoutPause(t0 + 10 * min, t0 + 13 * min)))

    private fun notice(w: Workout = workout, rest: RestTimer? = null, now: Long = t0 + 30 * min) =
        WorkoutNotice.of(w, "Legs", rest, workingSets = 6, now = now, texts = texts)

    @Test
    fun runningShowsTheActiveTimeAsAChronometerAndOffersPause() {
        val n = notice()
        assertEquals("Legs", n.title)
        assertEquals("In progress · 6", n.text)
        assertNull(n.subText)
        // now - base is the time trained: 30 minutes minus the 3 paused.
        assertEquals(t0 + 3 * min, n.chronometerBase)
        assertEquals(27 * min, t0 + 30 * min - n.chronometerBase!!)
        assertFalse(n.countDown)
        assertEquals(WorkoutNotice.Action.PAUSE, n.action)
        assertNull(n.changesAt)
        // The same content a minute later: nothing to post again.
        assertEquals(n, notice(now = t0 + 31 * min))
    }

    @Test
    fun pausedShowsTheTimeTrainedSoFarWithoutAClockAndOffersResume() {
        val paused = workout.copy(pauses = workout.pauses + WorkoutPause(t0 + 20 * min, null))
        val n = notice(paused, now = t0 + 45 * min)
        assertEquals("Paused · ${17 * 60}", n.text) // 20 minutes minus 3 paused
        assertNull(n.chronometerBase)
        assertEquals(WorkoutNotice.Action.RESUME, n.action)
        assertNull(n.changesAt)
        // A rest standing still with the workout shows nothing more.
        val rest = RestTimer(1, "w", endsAt = t0 + 21 * min, totalSec = 90, pausedLeftMs = 60_000)
        assertEquals(n, notice(paused, rest, now = t0 + 45 * min))
    }

    @Test
    fun restingCountsDownToTheRestEndThenBackToTheClock() {
        val rest = RestTimer(1, "w", endsAt = t0 + 31 * min, totalSec = 90)
        val n = notice(rest = rest, now = t0 + 30 * min)
        assertEquals("Rest", n.subText)
        assertEquals(t0 + 31 * min, n.chronometerBase)
        assertTrue(n.countDown)
        assertEquals(WorkoutNotice.Action.PAUSE, n.action)
        assertEquals(t0 + 31 * min, n.changesAt)
        // Once it ended (or for another workout), the running clock again.
        assertEquals(notice(), notice(rest = rest, now = t0 + 31 * min))
        assertEquals(notice(), notice(rest = rest.copy(workoutId = "other")))
    }

    @Test
    fun aWorkoutLeftRunningForWeeksHasNoClock() {
        // Android 15+ would ignore a notification whose time is two weeks old: none, so it still shows.
        val n = notice(now = t0 + 20 * 24 * 60 * min)
        assertNull(n.chronometerBase)
        assertEquals("In progress · 6", n.text)
        assertEquals(WorkoutNotice.Action.PAUSE, n.action)
    }

    @Test
    fun aCountdownRemovedAtItsEndComesBackButNotOneTheUserSwipedAway() {
        val resting = notice(rest = RestTimer(1, "w", endsAt = t0 + 31 * min, totalSec = 90), now = t0 + 30 * min)
        // Android's timeout at the rest's end (its broadcast arrives a moment later, or a moment before).
        assertTrue(WorkoutNotice.repostAfterRemoval(resting, now = t0 + 31 * min + 300))
        assertTrue(WorkoutNotice.repostAfterRemoval(resting, now = t0 + 31 * min - 500))
        // Swiped away in the middle of the rest: left alone.
        assertFalse(WorkoutNotice.repostAfterRemoval(resting, now = t0 + 30 * min + 20_000))
        // Nothing known about it (a new process): posted again.
        assertTrue(WorkoutNotice.repostAfterRemoval(null, now = t0))
        // A version without a timeout taken away (a stale timeout, or a swipe on Android 14+): posted again.
        assertTrue(WorkoutNotice.repostAfterRemoval(notice(), now = t0 + 30 * min))
    }

    @Test
    fun aCountdownIsCancelledBeforeAVersionWithoutItsTimeout() {
        val rest = RestTimer(1, "w", endsAt = t0 + 31 * min, totalSec = 90)
        val resting = notice(rest = rest, now = t0 + 30 * min)
        val running = notice()
        val paused = notice(workout.copy(pauses = workout.pauses + WorkoutPause(t0 + 30 * min, null)), rest = rest, now = t0 + 30 * min)
        // The rest ends (the coroutine posts the clock just before Android's own alarm), is skipped, or
        // the workout is paused during it: the countdown's timeout alarm must not outlive it.
        assertTrue(WorkoutNotice.mustCancelFirst(resting, activeTimeoutMs = 0, next = running))
        assertTrue(WorkoutNotice.mustCancelFirst(resting, activeTimeoutMs = 0, next = paused))
        // A rest given 15 s more: another timeout.
        val longer = notice(rest = rest.copy(endsAt = rest.endsAt + 15_000), now = t0 + 30 * min)
        assertTrue(WorkoutNotice.mustCancelFirst(resting, activeTimeoutMs = 0, next = longer))
        // The same countdown (a new set count during the rest) keeps its alarm; nothing to cancel without one.
        assertFalse(WorkoutNotice.mustCancelFirst(resting, activeTimeoutMs = 0, next = notice(rest = rest, now = t0 + 30 * min).copy(text = "In progress · 7")))
        assertFalse(WorkoutNotice.mustCancelFirst(running, activeTimeoutMs = 0, next = paused))
        assertFalse(WorkoutNotice.mustCancelFirst(paused, activeTimeoutMs = 0, next = resting))
        // A fresh process (or after a refresh) knows only what Android shows: a timeout there is cancelled first.
        assertTrue(WorkoutNotice.mustCancelFirst(null, activeTimeoutMs = 45_000, next = running))
        assertFalse(WorkoutNotice.mustCancelFirst(null, activeTimeoutMs = 0, next = running))
    }
}
