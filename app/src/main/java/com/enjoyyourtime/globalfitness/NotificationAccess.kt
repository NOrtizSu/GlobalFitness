package com.enjoyyourtime.globalfitness

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Android's notification access, per listener. Fortia has two (band forwarding and the music
 * controls) and Android lists, grants and revokes each one separately: asking about the app's
 * package would report one as granted when only the other is.
 */
object NotificationAccess {
    /** Settings.Secure key of the enabled notification listeners (components joined by ':'). */
    const val ENABLED_LISTENERS = "enabled_notification_listeners"

    /** Whether the user turned notification access on for this [component] itself. */
    fun isGranted(context: Context, component: ComponentName): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component) == true
        } else {
            // Android 8.0 keeps the enabled listeners in this setting (what NotificationManagerCompat reads too).
            listed(Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS), component.flattenToString(), component.flattenToShortString())
        }

    /** Whether the [setting]'s list holds the component, written in full or in short form. */
    fun listed(setting: String?, flattened: String, short: String): Boolean =
        setting?.split(':')?.any { it == flattened || it == short } == true

    /**
     * Opens Android's notification access page for [component] (its own page on Android 11+,
     * else the list); false when no settings screen could be opened.
     */
    fun openSettings(context: Context, component: ComponentName): Boolean =
        open(Build.VERSION.SDK_INT, component.flattenToString()) { action, extra ->
            val intent = Intent(action)
            if (extra != null) intent.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, extra)
            context.startActivity(intent)
        }

    /**
     * Starts [start] with the listener's own page (API 30+, with the [component] as extra) and,
     * when that fails, the list of every app with notification access. False when neither opened.
     */
    fun open(sdk: Int, component: String, start: (action: String, component: String?) -> Unit): Boolean {
        val pages = buildList {
            if (sdk >= Build.VERSION_CODES.R) add(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS to component)
            add(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS to null)
        }
        for ((action, extra) in pages) {
            try {
                start(action, extra)
                return true
            } catch (e: ActivityNotFoundException) {
                // Next page.
            } catch (e: SecurityException) {
                // Next page.
            }
        }
        return false
    }
}
