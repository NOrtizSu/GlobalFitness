package com.enjoyyourtime.globalfitness.training.player

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.enjoyyourtime.globalfitness.NotificationAccess

/**
 * Exists only because Android shows an app the media session that is playing (song, artist,
 * cover, state) and lets it control that session only while the app has an enabled notification
 * listener ([android.media.session.MediaSessionManager.getActiveSessions]). It ignores every
 * notification: nothing is read, stored, logged or sent. The manifest also asks Android 12+ to
 * send it none, and once Android binds it, it unbinds itself: being granted is all the media
 * session needs, so Fortia is not kept running in the background for it.
 */
class MediaAccessListener : NotificationListenerService() {
    override fun onListenerConnected() {
        requestUnbind()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    companion object {
        fun component(context: Context) = ComponentName(context, MediaAccessListener::class.java)

        /** Whether the user turned notification access on for this listener (not the band's). */
        fun isGranted(context: Context): Boolean = NotificationAccess.isGranted(context, component(context))

        /** Opens Android's notification access page for this listener; false when none opened. */
        fun openSettings(context: Context): Boolean = NotificationAccess.openSettings(context, component(context))
    }
}
