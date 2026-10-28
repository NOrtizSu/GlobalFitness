package com.enjoyyourtime.globalfitness.training.player

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCacheTest {
    private var now = 1_000_000L

    private fun state(playback: Int, stamp: Long = 0L) = SessionState("com.example.music", playback, 0L, 5_000L, stamp, 1f)

    @Test
    fun aCallbackDuringTheFirstReadWinsOverIt() {
        val cache = SessionStateCache { now }
        val newer = state(PlaybackState.STATE_PLAYING)
        val read = cache.get {
            // The callback lands while Android's answer is on its way back (an older state).
            cache.update(newer)
            state(PlaybackState.STATE_PAUSED)
        }
        assertEquals(PlaybackStatus.PLAYING, read.status)
        assertEquals(PlaybackStatus.PLAYING, cache.get { error("read once only") }.status)
    }

    @Test
    fun theStateIsReadOnceThenTakenFromCallbacks() {
        val cache = SessionStateCache { now }
        var reads = 0
        cache.get {
            reads++
            state(PlaybackState.STATE_PLAYING)
        }
        cache.update(state(PlaybackState.STATE_PAUSED))
        assertEquals(PlaybackStatus.PAUSED, cache.get { reads++; state(PlaybackState.STATE_PLAYING) }.status)
        assertEquals(1, reads)
    }

    @Test
    fun aPauseSeenHappeningIsStampedWithFortiasClock() {
        val cache = SessionStateCache { now }
        cache.get { state(PlaybackState.STATE_PLAYING, stamp = 42L) }
        assertNull(cache.get { error("") }.pausedAt)

        now = 2_000_000L
        cache.update(state(PlaybackState.STATE_PAUSED, stamp = 7L))
        assertEquals(2_000_000L, cache.get { error("") }.pausedAt)

        // The app posts its paused state again (a new stamp, even 0): still the same pause.
        now = 2_500_000L
        cache.update(state(PlaybackState.STATE_PAUSED, stamp = 0L))
        cache.update(state(PlaybackState.STATE_PAUSED, stamp = 9_999_999L))
        assertEquals(2_000_000L, cache.get { error("") }.pausedAt)

        cache.update(state(PlaybackState.STATE_PLAYING))
        assertNull(cache.get { error("") }.pausedAt)
        now = 3_000_000L
        cache.update(state(PlaybackState.STATE_BUFFERING))
        cache.update(state(PlaybackState.STATE_PAUSED))
        assertEquals(3_000_000L, cache.get { error("") }.pausedAt)
    }

    @Test
    fun onlyTheFirstReadTrustsTheAppsStampAndNeverBeyondNow() {
        fun firstPause(stamp: Long) = SessionStateCache { now }.get { state(PlaybackState.STATE_PAUSED, stamp) }.pausedAt
        assertEquals(now - 30_000L, firstPause(now - 30_000L))
        // Another clock (in the future), or none: from now.
        assertEquals(now, firstPause(now + 60_000L))
        assertEquals(now, firstPause(0L))
        assertEquals(now, firstPause(-1L))
        assertNull(SessionStateCache { now }.get { state(PlaybackState.STATE_PLAYING, now - 30_000L) }.pausedAt)

        // A paused state in a callback before anything was read: something just changed.
        val early = SessionStateCache { now }
        early.update(state(PlaybackState.STATE_PAUSED, stamp = 5L))
        assertEquals(now, early.get { error("not read: a callback came first") }.pausedAt)
    }

    @Test
    fun aSongIsKeptOnlyWhileWanted() {
        val cache = SongCache<String>()
        // Not shown: callbacks are dropped and nothing is read.
        assertFalse(cache.update("ignored"))
        assertNull(cache.current())

        var reads = 0
        assertEquals("first", cache.get { reads++; "first" })
        assertTrue(cache.update("second"))
        assertEquals("second", cache.get { reads++; "unused" })
        assertEquals(1, reads)

        cache.forget()
        assertNull(cache.current())
        assertFalse(cache.update("after"))
        // Wanted again: read again.
        assertEquals("fresh", cache.get { reads++; "fresh" })
        assertEquals(2, reads)

        // The song can be none.
        assertTrue(cache.update(null))
        assertNull(cache.get { error("") })
    }

    @Test
    fun aSongCallbackDuringTheFirstReadWinsOverIt() {
        val cache = SongCache<String>()
        val read = cache.get {
            assertTrue(cache.update("newer"))
            "older"
        }
        assertSame("newer", read)
    }

    @Test
    fun aSongForgottenDuringTheFirstReadIsNotKept() {
        val cache = SongCache<String>()
        val read = cache.get {
            cache.forget()
            "late"
        }
        assertNull(read)
        assertNull(cache.current())
    }
}
