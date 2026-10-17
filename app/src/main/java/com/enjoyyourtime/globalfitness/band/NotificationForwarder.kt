package com.enjoyyourtime.globalfitness.band

import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification

/** A phone notification as read by [BandNotificationListener]. */
data class PhoneNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val body: String,
    val postTime: Long,
)

/**
 * Phone → band forwarding rules: only when the user enabled it, for allowed apps and while the band is
 * connected in direct mode. Updates of a notification with unchanged text are not resent; a removed
 * notification is dismissed on the band only if it was forwarded.
 */
class NotificationForwarder(private val maxTracked: Int = 64) {
    private class Forwarded(val id: Int, val packageName: String, val signature: Int)

    private val forwarded = LinkedHashMap<String, Forwarded>()

    @Synchronized
    fun onPosted(n: PhoneNotification, enabled: Boolean, allowed: Set<String>, connected: Boolean): BandNotification? {
        if (!enabled || !connected || n.packageName !in allowed) return null
        if (n.title.isBlank() && n.body.isBlank()) return null
        val signature = (n.title + '\u0000' + n.body).hashCode()
        if (forwarded[n.key]?.signature == signature) return null
        val id = idFor(n.key)
        forwarded.remove(n.key)
        forwarded[n.key] = Forwarded(id, n.packageName, signature)
        while (forwarded.size > maxTracked) forwarded.remove(forwarded.keys.first())
        return BandNotification(id, n.packageName, n.appName, n.title, n.body, n.postTime)
    }

    /** @return (id, package) to dismiss on the band, or null. */
    @Synchronized
    fun onRemoved(key: String, connected: Boolean): Pair<Int, String>? {
        val f = forwarded.remove(key) ?: return null
        return if (connected) f.id to f.packageName else null
    }

    @Synchronized
    fun clear() = forwarded.clear()

    companion object {
        /** Stable, positive id per notification key (the band field is an unsigned 32-bit id). */
        fun idFor(key: String): Int = key.hashCode() and 0x7fffffff
    }
}
