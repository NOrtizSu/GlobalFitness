package com.enjoyyourtime.globalfitness.ui.training.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The workout notification's broadcasts (not exported: only this app's immutable PendingIntents
 * reach it): Pause / Resume, which work with the app's screens closed (a fresh process loads the
 * training data first), and the end of a rest or a removal: Android took the notification away,
 * or the backup alarm went off ([WorkoutNotifications]). The work gets [WORK_TIMEOUT_MS] (Android
 * gives a broadcast about ten seconds): a store that never answers must not hold the broadcast,
 * which always finishes.
 */
class WorkoutNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as GlobalFitnessApp).container
        val notifications = container.workoutNotifications
        when (intent.action) {
            ACTION_PAUSE, ACTION_RESUME -> {
                val workoutId = intent.getStringExtra(EXTRA_WORKOUT_ID) ?: return
                val pending = goAsync()
                container.appScope.launch {
                    try {
                        withTimeoutOrNull(WORK_TIMEOUT_MS) {
                            val pausing = container.training.pausing
                            if (intent.action == ACTION_PAUSE) pausing.pause(workoutId) else pausing.resume(workoutId)
                            // On screen before the broadcast ends (Android may freeze the process right after).
                            notifications.showNow()
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_REST_END, ACTION_REST_REMOVED -> {
                val pending = goAsync()
                container.appScope.launch {
                    try {
                        withTimeoutOrNull(WORK_TIMEOUT_MS) {
                            container.training.repository.awaitLoaded()
                            notifications.onRestEnd(removed = intent.action == ACTION_REST_REMOVED)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.enjoyyourtime.globalfitness.action.PAUSE_WORKOUT"
        const val ACTION_RESUME = "com.enjoyyourtime.globalfitness.action.RESUME_WORKOUT"
        const val ACTION_REST_END = "com.enjoyyourtime.globalfitness.action.REST_END"
        const val ACTION_REST_REMOVED = "com.enjoyyourtime.globalfitness.action.REST_REMOVED"
        const val EXTRA_WORKOUT_ID = "workoutId"

        /** Under the ten seconds Android lets a broadcast run. */
        private const val WORK_TIMEOUT_MS = 8_000L

        fun intent(context: Context, action: String): Intent = Intent(context, WorkoutNotificationReceiver::class.java).setAction(action)
    }
}
