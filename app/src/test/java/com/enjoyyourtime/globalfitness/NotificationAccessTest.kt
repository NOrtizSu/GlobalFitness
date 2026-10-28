package com.enjoyyourtime.globalfitness

import android.content.ActivityNotFoundException
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAccessTest {
    private val pkg = "com.enjoyyourtime.globalfitness"
    private val music = "$pkg/$pkg.training.player.MediaAccessListener"
    private val musicShort = "$pkg/.training.player.MediaAccessListener"
    private val band = "$pkg/$pkg.band.BandNotificationListener"
    private val bandShort = "$pkg/.band.BandNotificationListener"
    private val others = "com.google.android.as/com.google.android.apps.miphone.aiai.common.notification.service.AiAiNotificationListenerService"

    @Test
    fun grantingOneListenerDoesNotReportTheOther() {
        // Only the music controls granted: band forwarding must still ask for its own access.
        val musicOnly = "$others:$music"
        assertTrue(NotificationAccess.listed(musicOnly, music, musicShort))
        assertFalse(NotificationAccess.listed(musicOnly, band, bandShort))

        // Only band forwarding granted: the player stays on the media keys.
        val bandOnly = "$band:$others"
        assertTrue(NotificationAccess.listed(bandOnly, band, bandShort))
        assertFalse(NotificationAccess.listed(bandOnly, music, musicShort))

        val both = "$music:$bandShort"
        assertTrue(NotificationAccess.listed(both, music, musicShort))
        assertTrue(NotificationAccess.listed(both, band, bandShort))
    }

    @Test
    fun aListenerIsFoundWrittenInFullOrShort() {
        assertTrue(NotificationAccess.listed(musicShort, music, musicShort))
        assertFalse(NotificationAccess.listed("", music, musicShort))
        assertFalse(NotificationAccess.listed(null, music, musicShort))
        // Not a prefix match.
        assertFalse(NotificationAccess.listed("${music}Old", music, musicShort))
    }

    @Test
    fun androidsPageForTheListenerOpensElseTheList() {
        val opened = mutableListOf<Pair<String, String?>>()
        assertTrue(NotificationAccess.open(30, music) { action, extra -> opened += action to extra })
        assertEquals(listOf(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS to music), opened)

        opened.clear()
        assertTrue(
            NotificationAccess.open(34, music) { action, extra ->
                opened += action to extra
                if (extra != null) throw ActivityNotFoundException()
            },
        )
        assertEquals(
            listOf(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS to music, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS to null),
            opened,
        )

        // Before Android 11 there is only the list.
        opened.clear()
        assertTrue(NotificationAccess.open(29, music) { action, extra -> opened += action to extra })
        assertEquals(listOf(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS to null), opened)

        assertFalse(NotificationAccess.open(34, music) { _, _ -> throw ActivityNotFoundException() })
        assertFalse(NotificationAccess.open(26, music) { _, _ -> throw SecurityException("not exported") })
    }
}
