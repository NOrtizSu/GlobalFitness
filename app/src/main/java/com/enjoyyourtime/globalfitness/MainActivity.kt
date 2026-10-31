package com.enjoyyourtime.globalfitness

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.enjoyyourtime.globalfitness.ui.AppRoot
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.theme.GlobalFitnessTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** The workout the notification asked to open, until the navigation opens it. */
    private val workoutToOpen = MutableStateFlow<String?>(null)

    override fun onStart() {
        super.onStart()
        // The 24-hour clock setting may have changed while the app was in the background.
        Fmt.init(this)
        // So may the notification permission or the language; a swiped-away workout notification comes back.
        (application as GlobalFitnessApp).container.workoutNotifications.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val scanner = (application as GlobalFitnessApp).container.scanner
        // Scan only while the app is visible.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> scanner.onForegroundStart()
                    Lifecycle.Event.ON_STOP -> scanner.onForegroundStop()
                    else -> Unit
                }
            },
        )
        // Not again when the activity is recreated: its pages are restored as they were.
        if (savedInstanceState == null) takeWorkoutRequest(intent)

        setContent {
            GlobalFitnessTheme {
                AppRoot(workoutToOpen = workoutToOpen, onWorkoutOpened = { workoutToOpen.value = null })
            }
        }
    }

    /** The app was in the background (singleTop): the notification's tap arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeWorkoutRequest(intent)
    }

    private fun takeWorkoutRequest(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_WORKOUT) return
        // Reopened from the recent apps with the intent of an old tap: the workout may be long finished.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        workoutToOpen.value = intent.getStringExtra(EXTRA_WORKOUT_ID)
    }

    companion object {
        private const val ACTION_OPEN_WORKOUT = "com.enjoyyourtime.globalfitness.action.OPEN_WORKOUT"
        private const val EXTRA_WORKOUT_ID = "workoutId"

        /**
         * Opens the app on the workout [workoutId] (the workout notification's tap): the existing
         * activity, brought to the front with whatever was above it closed, or a new one at a cold
         * start. Never a second copy of the app.
         */
        fun openWorkoutIntent(context: Context, workoutId: String): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_WORKOUT)
                .putExtra(EXTRA_WORKOUT_ID, workoutId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
