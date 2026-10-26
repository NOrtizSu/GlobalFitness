package com.enjoyyourtime.globalfitness.review

import com.enjoyyourtime.globalfitness.ads.AdSuppression
import com.enjoyyourtime.globalfitness.training.model.Workout

/**
 * What is kept between launches. [firstSeenAt] is the first launch with this code: a new install
 * and an existing user who just upgraded both start counting their days of use then.
 */
data class ReviewHistory(
    val firstSeenAt: Long,
    val lastAskedAt: Long? = null,
    val asks: Int = 0,
) {
    /** The rating card was requested at [at] (whether Google showed it is never known). */
    fun asked(at: Long): ReviewHistory = copy(lastAskedAt = at, asks = asks + 1)
}

/** Inputs of one "may we ask for a rating now?" decision. Times are wall-clock milliseconds. */
data class ReviewState(
    val now: Long,
    val history: ReviewHistory,
    /** Workouts saved with at least one completed set (the one in progress does not count). */
    val finishedWorkouts: Int,
    val workoutInProgress: Boolean,
    val onboardingCompleted: Boolean,
    /** An interstitial is on screen, or something that holds the ads off is (a weighing, the consent form, a purchase…). */
    val busy: Boolean,
) {
    companion object {
        /**
         * The state from the app's live values: [suppressions] are the reasons the ads are held off.
         * The rating flow's own ([AdSuppression.REVIEW_FLOW], held while the card is prepared) does not
         * make the app busy; any other does.
         */
        fun of(
            now: Long,
            history: ReviewHistory,
            workouts: List<Workout>,
            onboardingCompleted: Boolean,
            interstitialShowing: Boolean,
            suppressions: Set<String>,
        ) = ReviewState(
            now = now,
            history = history,
            finishedWorkouts = workouts.count { !it.inProgress && it.doneSets > 0 },
            workoutInProgress = workouts.any { it.inProgress },
            onboardingCompleted = onboardingCompleted,
            busy = interstitialShowing || (suppressions - AdSuppression.REVIEW_FLOW).isNotEmpty(),
        )
    }
}

enum class ReviewVerdict {
    ASK,
    ONBOARDING,
    WORKOUT_IN_PROGRESS,
    BUSY,
    ASKED_ENOUGH,
    ASKED_RECENTLY,
    TOO_FEW_WORKOUTS,
    TOO_EARLY,
}

/**
 * When to ask for a Google Play rating, evaluated only at a good moment (an achievement
 * celebration or a saved workout was just closed): never during the onboarding, a workout or
 * anything that holds the ads off, at most [maxAsks] times ever and once every [minIntervalMs],
 * and only after [minFinishedWorkouts] saved workouts and [minUseMs] since [ReviewHistory.firstSeenAt].
 * A clock set back makes an interval look unfinished: no ask until it has really passed.
 */
class ReviewPolicy(
    val minFinishedWorkouts: Int = 3,
    val minUseMs: Long = 3 * DAY_MS,
    val minIntervalMs: Long = 120 * DAY_MS,
    val maxAsks: Int = 3,
) {
    fun evaluate(s: ReviewState): ReviewVerdict = when {
        !s.onboardingCompleted -> ReviewVerdict.ONBOARDING
        s.workoutInProgress -> ReviewVerdict.WORKOUT_IN_PROGRESS
        s.busy -> ReviewVerdict.BUSY
        s.history.asks >= maxAsks -> ReviewVerdict.ASKED_ENOUGH
        s.history.lastAskedAt != null && s.now - s.history.lastAskedAt < minIntervalMs -> ReviewVerdict.ASKED_RECENTLY
        s.finishedWorkouts < minFinishedWorkouts -> ReviewVerdict.TOO_FEW_WORKOUTS
        s.now - s.history.firstSeenAt < minUseMs -> ReviewVerdict.TOO_EARLY
        else -> ReviewVerdict.ASK
    }

    /**
     * Whether the card may be prepared or launched now: the [verdict] is [ReviewVerdict.ASK] and the
     * activity that would show it is still there and in front ([activityResumed]): Google's card must
     * never open over another app or a closing screen.
     */
    fun mayAsk(verdict: ReviewVerdict, activityResumed: Boolean): Boolean = activityResumed && verdict == ReviewVerdict.ASK

    companion object {
        const val DAY_MS = 24 * 60 * 60_000L
    }
}
