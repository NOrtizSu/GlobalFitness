package com.enjoyyourtime.globalfitness.review

import com.enjoyyourtime.globalfitness.ads.AdSuppression
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPolicyTest {
    private val policy = ReviewPolicy()
    private val day = ReviewPolicy.DAY_MS
    private val install = 1_700_000_000_000L

    /** Fake wall clock + what the prompter stores and reads at a good moment. */
    private class User(var now: Long, var history: ReviewHistory) {
        var finishedWorkouts = 3
        var workoutInProgress = false
        var onboardingCompleted = true
        var busy = false

        fun state() = ReviewState(now, history, finishedWorkouts, workoutInProgress, onboardingCompleted, busy)
    }

    /** A user on their first day: [ReviewHistory.firstSeenAt] is now. */
    private fun newUser() = User(now = install, history = ReviewHistory(firstSeenAt = install))

    /** A good moment: the prompter records the ask when the policy says so. */
    private fun User.goodMoment(): ReviewVerdict =
        policy.evaluate(state()).also { if (it == ReviewVerdict.ASK) history = history.asked(now) }

    @Test
    fun asksAfterThreeWorkoutsAndThreeDaysOfUse() {
        val u = newUser().apply { now += 3 * day }
        assertEquals(ReviewVerdict.ASK, u.goodMoment())
        assertEquals(1, u.history.asks)
        assertEquals(u.now, u.history.lastAskedAt)
    }

    @Test
    fun needsThreeFinishedWorkouts() {
        val u = newUser().apply {
            now += 10 * day
            finishedWorkouts = 2
        }
        assertEquals(ReviewVerdict.TOO_FEW_WORKOUTS, u.goodMoment())
        u.finishedWorkouts = 3
        assertEquals(ReviewVerdict.ASK, u.goodMoment())
    }

    @Test
    fun needsThreeDaysSinceTheFirstLaunch() {
        val u = newUser().apply { now += 3 * day - 1 }
        assertEquals(ReviewVerdict.TOO_EARLY, u.goodMoment())
        u.now += 1
        assertEquals(ReviewVerdict.ASK, u.goodMoment())
    }

    @Test
    fun anExistingUserWaitsThreeDaysAfterTheUpgrade() {
        // Months of workouts before this code existed: the first-seen time is the upgrade.
        val upgrade = install + 200 * day
        val u = User(now = upgrade, history = ReviewHistory(firstSeenAt = upgrade)).apply { finishedWorkouts = 80 }
        assertEquals(ReviewVerdict.TOO_EARLY, u.goodMoment())
        u.now = upgrade + 3 * day - 1
        assertEquals(ReviewVerdict.TOO_EARLY, u.goodMoment())
        u.now = upgrade + 3 * day
        assertEquals(ReviewVerdict.ASK, u.goodMoment())
    }

    @Test
    fun atMostOnceEvery120Days() {
        val u = newUser().apply { now += 5 * day }
        assertEquals(ReviewVerdict.ASK, u.goodMoment())
        u.now += 120 * day - 1
        assertEquals(ReviewVerdict.ASKED_RECENTLY, u.goodMoment())
        u.now += 1
        assertEquals(ReviewVerdict.ASK, u.goodMoment())
        assertEquals(2, u.history.asks)
    }

    @Test
    fun atMostThreeAsksEver() {
        val u = newUser().apply { now += 3 * day }
        repeat(3) {
            assertEquals(ReviewVerdict.ASK, u.goodMoment())
            u.now += 120 * day
        }
        assertEquals(3, u.history.asks)
        assertEquals(ReviewVerdict.ASKED_ENOUGH, u.goodMoment())
        u.now += 1_000 * day
        assertEquals(ReviewVerdict.ASKED_ENOUGH, u.goodMoment())
        assertEquals(3, u.history.asks)
    }

    @Test
    fun neverDuringAWorkout() {
        val u = newUser().apply {
            now += 10 * day
            workoutInProgress = true
        }
        assertEquals(ReviewVerdict.WORKOUT_IN_PROGRESS, u.goodMoment())
        assertEquals(0, u.history.asks)
    }

    @Test
    fun neverDuringTheOnboarding() {
        val u = newUser().apply {
            now += 10 * day
            onboardingCompleted = false
        }
        assertEquals(ReviewVerdict.ONBOARDING, u.goodMoment())
    }

    @Test
    fun neverOverAnInterstitialOrWhileSomethingHoldsTheAdsOff() {
        val u = newUser().apply {
            now += 10 * day
            busy = true
        }
        assertEquals(ReviewVerdict.BUSY, u.goodMoment())
        u.busy = false
        assertEquals(ReviewVerdict.ASK, u.goodMoment())
    }

    @Test
    fun aClockSetBackDoesNotCountAsTimePassed() {
        val firstSeenAhead = newUser().apply { history = ReviewHistory(firstSeenAt = now + 30 * day) }
        assertEquals(ReviewVerdict.TOO_EARLY, firstSeenAhead.goodMoment())
        val askedAhead = newUser().apply { history = ReviewHistory(firstSeenAt = now - 30 * day, lastAskedAt = now + day, asks = 1) }
        assertEquals(ReviewVerdict.ASKED_RECENTLY, askedAhead.goodMoment())
    }

    @Test
    fun theOnboardingWinsOverEveryOtherReason() {
        val u = newUser().apply {
            onboardingCompleted = false
            workoutInProgress = true
            busy = true
            finishedWorkouts = 0
        }
        assertEquals(ReviewVerdict.ONBOARDING, u.goodMoment())
    }

    private fun workout(id: String, done: Boolean, inProgress: Boolean = false) = Workout(
        id, install, if (inProgress) null else install + 1, null, null, install,
        listOf(WorkoutEntry("e$id", "pull_up", listOf(WorkoutSet("s$id", reps = 5, done = done)))),
    )

    @Test
    fun theStateCountsSavedWorkoutsAndWhatHoldsTheAdsOff() {
        val workouts = listOf(workout("a", done = true), workout("b", done = true), workout("c", done = false), workout("d", done = true, inProgress = true))
        fun state(interstitial: Boolean = false, reasons: Set<String> = emptySet()) =
            ReviewState.of(install, ReviewHistory(install), workouts, onboardingCompleted = true, interstitialShowing = interstitial, suppressions = reasons)
        val s = state()
        assertEquals(2, s.finishedWorkouts)
        assertTrue(s.workoutInProgress)
        assertFalse(s.busy)
        // The rating flow's own hold (taken while the card is prepared) does not stop it; anything else does.
        assertFalse(state(reasons = setOf(AdSuppression.REVIEW_FLOW)).busy)
        assertTrue(state(reasons = setOf(AdSuppression.REVIEW_FLOW, AdSuppression.CELEBRATION)).busy)
        assertTrue(state(reasons = setOf(AdSuppression.SCALE_MEASUREMENT)).busy)
        assertTrue(state(interstitial = true).busy)
    }

    @Test
    fun asksOnlyWhileTheActivityIsInFront() {
        assertTrue(policy.mayAsk(ReviewVerdict.ASK, activityResumed = true))
        // Paused, stopped or already destroyed (the prompter only keeps a weak reference to it).
        assertFalse(policy.mayAsk(ReviewVerdict.ASK, activityResumed = false))
        assertFalse(policy.mayAsk(ReviewVerdict.BUSY, activityResumed = true))
    }
}
