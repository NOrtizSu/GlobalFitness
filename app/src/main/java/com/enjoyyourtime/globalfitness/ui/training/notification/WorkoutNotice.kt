package com.enjoyyourtime.globalfitness.ui.training.notification

import com.enjoyyourtime.globalfitness.training.domain.WorkoutTime
import com.enjoyyourtime.globalfitness.training.live.RestTimer
import com.enjoyyourtime.globalfitness.training.model.Workout

/** The texts of the ongoing notification, from the app's resources (a fake in tests). */
interface WorkoutNoticeTexts {
    /** "Workout in progress · 8 sets" ([workingSets] 0: without the sets). */
    fun running(workingSets: Int): String

    /** "Paused · 25:13", with the active time so far. */
    fun paused(activeMs: Long): String

    /** "Rest", next to the countdown. */
    fun rest(): String
}

/**
 * What the ongoing notification of the workout in progress shows, decided by [WorkoutNotice.of]
 * (pure, so every state is tested) and drawn by [WorkoutNotifications].
 */
data class WorkoutNotice(
    val workoutId: String,
    val title: String,
    val text: String,
    /** Shown in the header, next to the clock ("Rest" during a rest). */
    val subText: String?,
    /**
     * Wall-clock time the chronometer counts from (elapsed: the start plus the pauses, so it shows
     * the active time) or down to (the rest's end); null: no clock (paused).
     */
    val chronometerBase: Long?,
    val countDown: Boolean,
    val action: Action,
    /** When this content stops being right by itself (the rest ends): it is posted again then. */
    val changesAt: Long?,
) {
    /** The notification's one button. */
    enum class Action { PAUSE, RESUME }

    companion object {
        /**
         * - Running: the active time as a chronometer counting up, the button pauses.
         * - Paused: no clock, "Paused · 25:13" (the time trained so far stands still), the button resumes.
         * - Resting (a rest of this workout counting down): the countdown to its end next to "Rest",
         *   back to the running clock once it ends ([changesAt]).
         *
         * A workout left running for weeks shows no clock: Android 15+ ignores notifications whose
         * time is more than two weeks old ([MAX_CLOCK_AGE_MS]), and it would not be posted at all.
         */
        fun of(workout: Workout, title: String, rest: RestTimer?, workingSets: Int, now: Long, texts: WorkoutNoticeTexts): WorkoutNotice {
            if (workout.paused) {
                return WorkoutNotice(
                    workoutId = workout.id,
                    title = title,
                    text = texts.paused(WorkoutTime.activeMs(workout, now)),
                    subText = null,
                    chronometerBase = null,
                    countDown = false,
                    action = Action.RESUME,
                    changesAt = null,
                )
            }
            val resting = rest?.takeIf { it.workoutId == workout.id && !it.paused && it.endsAt > now }
            val base = WorkoutTime.runningBase(workout).takeIf { now - it < MAX_CLOCK_AGE_MS }
            return WorkoutNotice(
                workoutId = workout.id,
                title = title,
                text = texts.running(workingSets),
                subText = resting?.let { texts.rest() },
                chronometerBase = resting?.endsAt ?: base,
                countDown = resting != null,
                action = Action.PAUSE,
                changesAt = resting?.endsAt,
            )
        }

        /**
         * Android took [shown] away at [now]: post it again, unless the user swiped a rest countdown
         * away before the rest's end (left alone until the rest ends). A removal from a moment before
         * the end on is the countdown's own timeout; the removal of a version without a timeout (a
         * stale timeout alarm, see [mustCancelFirst], or the user's swipe on Android 14+) brings it
         * back at once, as Android 13 and older never let it be swiped.
         */
        fun repostAfterRemoval(shown: WorkoutNotice?, now: Long): Boolean {
            val end = shown?.changesAt ?: return true
            return now >= end - TIMEOUT_SLACK_MS
        }

        /**
         * Whether the notification on screen must be cancelled before [next] is posted. Android 8 to
         * 14 keep the timeout alarm of a version posted with a timeout (the rest's countdown) when the
         * next version has another timeout or none, and that alarm later removes whatever then has the
         * notification's id (the running clock, the paused one). The app's own cancel clears the alarm
         * and sends no delete intent. [shown]: the version this process posted (null: none known, e.g.
         * a fresh process or after a refresh); then [activeTimeoutMs] is the timeout of the
         * notification Android shows with that id (0: none, or nothing shown).
         */
        fun mustCancelFirst(shown: WorkoutNotice?, activeTimeoutMs: Long, next: WorkoutNotice): Boolean {
            if (shown == null) return activeTimeoutMs > 0
            val timeoutAt = shown.changesAt ?: return false
            return timeoutAt != next.changesAt
        }

        /** How long before the rest's end a removal still counts as its timeout. */
        const val TIMEOUT_SLACK_MS = 2_000L

        /** Under the two weeks after which Android 15+ drops a notification for its old time. */
        const val MAX_CLOCK_AGE_MS = 13 * 24 * 3_600_000L
    }
}
