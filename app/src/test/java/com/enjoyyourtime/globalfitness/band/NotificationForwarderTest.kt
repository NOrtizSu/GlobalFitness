package com.enjoyyourtime.globalfitness.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationForwarderTest {
    private val forwarder = NotificationForwarder(maxTracked = 3)
    private val allowed = setOf("com.chat")

    private fun notification(key: String = "k1", pkg: String = "com.chat", title: String = "Ana", body: String = "Hola") =
        PhoneNotification(key, pkg, "Chat", title, body, postTime = 1_000)

    @Test
    fun forwardsAllowedAppsOnlyWhileEnabledAndConnected() {
        assertNull(forwarder.onPosted(notification(), enabled = false, allowed = allowed, connected = true))
        assertNull(forwarder.onPosted(notification(), enabled = true, allowed = allowed, connected = false))
        assertNull(forwarder.onPosted(notification(pkg = "com.other"), enabled = true, allowed = allowed, connected = true))

        val sent = forwarder.onPosted(notification(), enabled = true, allowed = allowed, connected = true)
        assertNotNull(sent)
        assertEquals("com.chat", sent!!.packageName)
        assertEquals("Chat", sent.appName)
        assertEquals("Ana", sent.title)
        assertEquals("Hola", sent.body)
        assertEquals(NotificationForwarder.idFor("k1"), sent.id)
        assertTrue(sent.id >= 0)
    }

    @Test
    fun skipsEmptyAndUnchangedUpdatesButResendsChangedText() {
        assertNull(forwarder.onPosted(notification(title = " ", body = ""), enabled = true, allowed = allowed, connected = true))
        val first = forwarder.onPosted(notification(), enabled = true, allowed = allowed, connected = true)
        assertNull(forwarder.onPosted(notification(), enabled = true, allowed = allowed, connected = true))
        val updated = forwarder.onPosted(notification(body = "¿Vienes?"), enabled = true, allowed = allowed, connected = true)
        assertEquals(first!!.id, updated!!.id)
    }

    @Test
    fun dismissesOnlyForwardedNotifications() {
        assertNull(forwarder.onRemoved("k1", connected = true))
        forwarder.onPosted(notification(), enabled = true, allowed = allowed, connected = true)
        assertEquals(NotificationForwarder.idFor("k1") to "com.chat", forwarder.onRemoved("k1", connected = true))
        assertNull(forwarder.onRemoved("k1", connected = true))
    }

    @Test
    fun forgetsTheOldestBeyondTheLimit() {
        (1..4).forEach { forwarder.onPosted(notification(key = "k$it"), enabled = true, allowed = allowed, connected = true) }
        assertNull(forwarder.onRemoved("k1", connected = true))
        assertNotNull(forwarder.onRemoved("k4", connected = true))
    }
}
