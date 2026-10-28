package com.enjoyyourtime.globalfitness.band

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.NotificationAccess
import com.enjoyyourtime.globalfitness.wearable.WearableManager

/**
 * Optional phone → band notification forwarding. Android only binds it after the user grants
 * notification access; [WearableManager] then forwards allowed apps' notifications, and only while a
 * wearable is connected in direct mode. Notification text is never stored or logged.
 */
class BandNotificationListener : NotificationListenerService() {
    private val wearables: WearableManager
        get() = (application as GlobalFitnessApp).container.wearables

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = toPhoneNotification(sbn) ?: return
        wearables.onPhoneNotification(notification)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        wearables.onPhoneNotificationRemoved(sbn.key)
    }

    private fun toPhoneNotification(sbn: StatusBarNotification): PhoneNotification? {
        if (sbn.packageName == packageName || sbn.isOngoing) return null
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        return PhoneNotification(sbn.key, sbn.packageName, appLabel(sbn.packageName), title, body, sbn.postTime)
    }

    private fun appLabel(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    companion object {
        /** Access for this listener itself: the music controls' listener is granted separately. */
        fun isAccessGranted(context: Context): Boolean = NotificationAccess.isGranted(context, component(context))

        fun component(context: Context) = ComponentName(context, BandNotificationListener::class.java)
    }
}
