package com.enjoyyourtime.globalfitness.ui.training.notification

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.enjoyyourtime.globalfitness.MainActivity
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.data.TrainingSettings
import com.enjoyyourtime.globalfitness.training.domain.LoadModel
import com.enjoyyourtime.globalfitness.training.live.RestTimer
import com.enjoyyourtime.globalfitness.training.live.RestTimers
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.training.TrainingFormat
import com.enjoyyourtime.globalfitness.ui.training.TrainingSummaries
import com.enjoyyourtime.globalfitness.ui.training.workoutTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The ongoing notification of the workout in progress: its name, the time trained as a
 * chronometer (none while paused: "Paused · 25:13"), the rest countdown while resting, one Pause /
 * Resume button ([WorkoutNotificationReceiver], works with the app closed) and a tap that opens the
 * workout ([MainActivity.openWorkoutIntent]). Content: [WorkoutNotice].
 *
 * A plain notification, no foreground service: nothing has to keep running for it (the clock is
 * Android's chronometer, the button a broadcast), and Google Play asks a declaration for every
 * foreground-service type (the "health" one needs more permissions). It is posted again whenever the
 * workout or its rest changes and when the process starts with a workout in progress (or [refresh]),
 * and cancelled once the workout is finished or discarded, the setting is off or notifications are
 * not allowed. Android 14+ lets the user swipe it away: it comes back at once (its delete intent),
 * a rest countdown at the rest's end.
 *
 * The end of a rest: Android's countdown goes on below zero, so the running clock must be posted
 * again when the rest ends, also while Android keeps the app frozen in the background (Android 14+
 * freezes cached apps within seconds: a coroutine waiting for the end does not run). No exact alarm
 * (that permission is restricted by Google Play), and an inexact one came up to a minute late on the
 * emulator. So the countdown is posted with Android's own timeout at the rest's end
 * (`setTimeoutAfter`): the system takes it away on time and sends its delete intent, which wakes the
 * app to post the running clock ([onRestEnd]; it comes back at once). The coroutine (process running)
 * and an inexact alarm (should a phone not send the delete intent on a timeout) stay as backups. A
 * process that died lost its rest anyway: the broadcast starts it and it posts the running clock.
 *
 * That timeout outlives the countdown on Android 8 to 14: an update without a timeout (the rest
 * skipped, the workout paused during the rest, the running clock posted a moment before Android's
 * own alarm) keeps the old alarm, which then removes the new version. So the countdown is cancelled
 * before such an update ([WorkoutNotice.mustCancelFirst]; a fresh process asks Android what it
 * shows), and every version has the delete intent as a safety net: one taken away without a
 * timeout of its own is posted again at once.
 */
class WorkoutNotifications(
    private val context: Context,
    private val repository: TrainingRepository,
    private val rest: RestTimers,
    private val settings: TrainingSettings,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val manager = NotificationManagerCompat.from(context)
    private val prompts = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val refreshes = MutableStateFlow(0)

    /** What is on screen (main thread only); null when nothing is known to be, or it must be posted again. */
    private var posted: WorkoutNotice? = null

    /**
     * What the state asks for, worked out without asking Android anything: null when no notification
     * should show. [refresh] makes a [refresh] call pass even when the content is the same.
     */
    private data class Wanted(val notice: WorkoutNotice?, val refresh: Int)

    private val texts = object : WorkoutNoticeTexts {
        override fun running(workingSets: Int): String {
            val label = context.getString(R.string.training_in_progress)
            if (workingSets <= 0) return label
            return label + " · " + context.resources.getQuantityString(R.plurals.training_sets_count, workingSets, Fmt.count(workingSets.toLong()))
        }

        override fun paused(activeMs: Long): String =
            context.getString(R.string.workout_paused_time, TrainingFormat.duration((activeMs / 1000).toInt(), Fmt.locale))

        override fun rest(): String = context.getString(R.string.training_rest)
    }

    /**
     * Follows the workout in progress from now on (called once, at start). Only a change of what
     * the notification shows reaches Android: typing a set's values changes the workout many times
     * without changing it.
     */
    fun start() {
        createChannel()
        scope.launch {
            combine(repository.loaded, repository.activeWorkout, repository.exercises, rest.state, settings.workoutNotification) { loaded, w, ex, r, on ->
                // Until the data is loaded the notification of a workout in progress stays as it was.
                if (!loaded) null else Wanted(w?.takeIf { on }?.let { notice(it, ex, r, clock()) }, refresh = 0)
            }
                .filterNotNull()
                .combine(refreshes) { wanted, n -> wanted.copy(refresh = n) }
                .distinctUntilChanged()
                .collectLatest(::show)
        }
    }

    /**
     * Posts the notification again as it should be now: the app came to the front (a permission or
     * the language may have changed, or the user swiped it away), a permission was granted.
     */
    fun refresh() {
        createChannel()
        posted = null
        refreshes.update { it + 1 }
    }

    /**
     * The rest shown may be over, or the notification is gone: Android removed it ([removed]: the
     * countdown's timeout at the rest's end, a stale timeout, or the user swiped it away; a countdown
     * swiped away during the rest is left alone until the rest ends, see
     * [WorkoutNotice.repostAfterRemoval]) or the backup alarm went off. Call once the training data
     * is loaded.
     */
    fun onRestEnd(removed: Boolean) {
        if (removed && !WorkoutNotice.repostAfterRemoval(posted, clock())) return
        posted = null
        showNow()
    }

    /** Posts what the current state shows, at once (the notification's button, before its broadcast ends). */
    fun showNow() {
        if (!repository.loaded.value) return
        // From the list itself: the derived activeWorkout may not have caught up with the write yet.
        val w = repository.workouts.value.lastOrNull { it.inProgress }
        if (w == null || !settings.workoutNotification.value || !allowed()) {
            cancel()
            return
        }
        post(notice(w, repository.exercises.value, rest.state.value, clock()))
    }

    private suspend fun show(wanted: Wanted) {
        val notice = wanted.notice
        if (notice == null || !allowed()) {
            cancel()
            return
        }
        val changesAt = notice.changesAt
        if (changesAt != null && changesAt <= clock()) {
            // Worked out before the rest ended (a refresh passes the last content on): as it is now.
            showNow()
            cancelRestAlarm()
            return
        }
        post(notice)
        if (changesAt == null) {
            cancelRestAlarm()
            return
        }
        scheduleRestAlarm(changesAt)
        delay((changesAt - clock()).coerceAtLeast(0))
        // The rest is over: the running clock, from the state as it is now.
        showNow()
        cancelRestAlarm()
    }

    private fun notice(w: Workout, exercises: Map<String, Exercise>, rest: RestTimer?, now: Long): WorkoutNotice {
        val summary = TrainingSummaries.summary(w, { id -> exercises[id] ?: TrainingRepository.unknownExercise(id) }, LoadModel.WITHOUT_BODYWEIGHT)
        return WorkoutNotice.of(w, workoutTitle(context, summary, exercises), rest, summary.workingSets, now, texts)
    }

    private fun post(notice: WorkoutNotice) {
        val shown = posted
        if (notice == shown) return
        val paused = notice.action == WorkoutNotice.Action.RESUME
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_workout)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setSubText(notice.subText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            // The name and the clock on the lock screen too: that is where it is looked at in the gym.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Not bridged to a watch: its button and clock belong to this phone's workout.
            .setLocalOnly(true)
            .setContentIntent(openIntent(notice.workoutId))
            // Every version: one taken away by a stale timeout comes back at once (see the class comment).
            .setDeleteIntent(removedIntent())
            .addAction(
                if (paused) R.drawable.ic_notification_resume else R.drawable.ic_notification_pause,
                context.getString(if (paused) R.string.workout_resume else R.string.workout_pause),
                actionIntent(notice.workoutId, paused),
            )
        val base = notice.chronometerBase
        if (base == null) {
            builder.setShowWhen(false).setUsesChronometer(false)
        } else {
            builder.setWhen(base).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(notice.countDown)
        }
        // A countdown is taken away by Android at its end, which tells the app (see the class comment).
        notice.changesAt?.let { end -> builder.setTimeoutAfter((end - clock()).coerceAtLeast(1)) }
        posted = try {
            // An earlier countdown's timeout must not outlive it (Android 8 to 14).
            if (WorkoutNotice.mustCancelFirst(shown, if (shown == null) activeTimeoutMs() else 0L, notice)) manager.cancel(NOTIFICATION_ID)
            manager.notify(NOTIFICATION_ID, builder.build())
            notice
        } catch (e: SecurityException) {
            // The permission was revoked between the check and the post.
            null
        }
    }

    /** The timeout of the notification Android shows with our id (0: none, or nothing shown). */
    private fun activeTimeoutMs(): Long = try {
        manager.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID && it.tag == null }?.notification?.timeoutAfter ?: 0L
    } catch (e: RuntimeException) {
        // A system that cannot tell: cancelling first is always safe.
        1L
    }

    private fun cancel() {
        posted = null
        manager.cancel(NOTIFICATION_ID)
        cancelRestAlarm()
    }

    private fun openIntent(workoutId: String): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        MainActivity.openWorkoutIntent(context, workoutId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(workoutId: String, resume: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context,
        if (resume) REQUEST_RESUME else REQUEST_PAUSE,
        WorkoutNotificationReceiver.intent(context, if (resume) WorkoutNotificationReceiver.ACTION_RESUME else WorkoutNotificationReceiver.ACTION_PAUSE)
            .putExtra(WorkoutNotificationReceiver.EXTRA_WORKOUT_ID, workoutId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun restEndIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_REST_END,
        WorkoutNotificationReceiver.intent(context, WorkoutNotificationReceiver.ACTION_REST_END),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun removedIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_REST_REMOVED,
        WorkoutNotificationReceiver.intent(context, WorkoutNotificationReceiver.ACTION_REST_REMOVED),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The backup of the rest's end: inexact and not waking the phone up; replaces the previous one. */
    private fun scheduleRestAlarm(at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + (at - clock()).coerceAtLeast(0), restEndIntent())
    }

    private fun cancelRestAlarm() {
        context.getSystemService(AlarmManager::class.java)?.cancel(restEndIntent())
    }

    private fun createChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.workout_notification_channel))
                .setDescription(context.getString(R.string.workout_notification_channel_text))
                .setShowBadge(false)
                .setVibrationEnabled(false)
                .setSound(null, null)
                .build(),
        )
    }

    // ---- permission -----------------------------------------------------------------------------

    /** Notifications allowed for the app (Android 13+: the permission) and the channel not turned off. */
    fun allowed(): Boolean =
        manager.areNotificationsEnabled() && manager.getNotificationChannelCompat(CHANNEL_ID)?.importance != NotificationManagerCompat.IMPORTANCE_NONE

    /** Android 13+ asks for notifications: granted (always true before). */
    fun permissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Android's permission dialog was shown once on this install (never again on its own). */
    val permissionAsked: Boolean get() = prompts.getBoolean(KEY_ASKED, false)

    /** Whether starting a workout should show Android's permission dialog: the first time only, with the notification on. */
    fun shouldAskPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && settings.workoutNotification.value && !permissionAsked && !permissionGranted()

    fun markPermissionAsked() {
        prompts.edit { putBoolean(KEY_ASKED, true) }
    }

    companion object {
        const val CHANNEL_ID = "workout_in_progress"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN = 1
        private const val REQUEST_PAUSE = 2
        private const val REQUEST_RESUME = 3
        private const val REQUEST_REST_END = 4
        private const val REQUEST_REST_REMOVED = 5

        /** Own file, excluded from backups: a restored or new phone asks again. */
        private const val PREFS = "workout_notification"
        private const val KEY_ASKED = "permission_asked"

        /** Opens Android's notification settings of the app (to allow them after the dialog was refused); false when none opens. */
        fun openSettings(context: Context): Boolean {
            val notifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            for (intent in listOf(notifications, details)) {
                try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return true
                } catch (e: ActivityNotFoundException) {
                    continue
                }
            }
            return false
        }
    }
}
