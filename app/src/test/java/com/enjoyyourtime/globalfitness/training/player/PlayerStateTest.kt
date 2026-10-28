package com.enjoyyourtime.globalfitness.training.player

import android.media.session.PlaybackState
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateTest {
    private val song = TrackInfo("Song", "Artist", 200_000L)

    private fun state(
        state: Int,
        actions: Long = 0L,
        position: Long = 10_000L,
        updatedAt: Long = 1_000L,
        speed: Float = 1f,
        pkg: String = "com.spotify.music",
    ) = SessionState(pkg, state, actions, position, updatedAt, speed)

    @Test
    fun playbackStatesFoldIntoWhatThePlayerShows() {
        assertEquals(PlaybackStatus.PLAYING, PlaybackStatus.of(PlaybackState.STATE_PLAYING))
        assertEquals(PlaybackStatus.PLAYING, PlaybackStatus.of(PlaybackState.STATE_FAST_FORWARDING))
        assertEquals(PlaybackStatus.BUFFERING, PlaybackStatus.of(PlaybackState.STATE_BUFFERING))
        assertEquals(PlaybackStatus.BUFFERING, PlaybackStatus.of(PlaybackState.STATE_SKIPPING_TO_NEXT))
        assertEquals(PlaybackStatus.BUFFERING, PlaybackStatus.of(PlaybackState.STATE_CONNECTING))
        assertEquals(PlaybackStatus.PAUSED, PlaybackStatus.of(PlaybackState.STATE_PAUSED))
        assertEquals(PlaybackStatus.STOPPED, PlaybackStatus.of(PlaybackState.STATE_STOPPED))
        assertEquals(PlaybackStatus.STOPPED, PlaybackStatus.of(PlaybackState.STATE_NONE))
        assertEquals(PlaybackStatus.STOPPED, PlaybackStatus.of(PlaybackState.STATE_ERROR))
        assertTrue(PlaybackStatus.BUFFERING.active)
        assertFalse(PlaybackStatus.PAUSED.active)
    }

    @Test
    fun theSongsTextsComeFromWhicheverFieldsTheAppFills() {
        assertEquals(TrackInfo("Display", "Artist", 1_000L), TrackInfo.of("Title", "Display", "Artist", "Album artist", "Sub", 1_000L))
        assertEquals(TrackInfo("Title", "Album artist", null), TrackInfo.of(" Title ", "  ", null, "Album artist", "Sub", 0L))
        assertEquals(TrackInfo("Title", "Sub", null), TrackInfo.of("Title", null, "", null, "Sub", -1L))
        // A subtitle that only repeats the title is not an artist.
        assertEquals(TrackInfo("Title", null, null), TrackInfo.of("Title", null, null, null, "Title", 0L))
        assertEquals(TrackInfo(null, null, null), TrackInfo.of(null, null, null, null, null, 0L))
        assertEquals(TrackInfo("Title", null, null, "512x512"), TrackInfo.of("Title", null, null, null, null, 0L, artKey = "512x512"))
    }

    @Test
    fun aPictureDoesNotMakeAnotherSong() {
        assertTrue(song.sameSong(song.copy(artKey = "512x512")))
        assertTrue(song.copy(artKey = "64x64").sameSong(song.copy(artKey = "512x512")))
        assertFalse(song.sameSong(song.copy(title = "Other")))
        assertFalse(song.sameSong(null))
    }

    @Test
    fun aSessionMapsToWhatTheCardShows() {
        val track = PlayerState.track(
            state(PlaybackState.STATE_PLAYING, actions = PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT),
            song,
            cover = null,
            app = "Spotify",
        )
        assertEquals("Song", track.title)
        assertEquals("Artist", track.artist)
        assertEquals("Spotify", track.app)
        assertEquals(PlaybackStatus.PLAYING, track.status)
        assertEquals(200_000L, track.durationMs)
        assertTrue(track.canNext)
        assertFalse(track.canPrevious)
        assertNull(track.cover)
    }

    @Test
    fun missingSongTextsAndDurationStayUnknown() {
        val track = PlayerState.track(state(PlaybackState.STATE_PAUSED), null, cover = null, app = null)
        assertNull(track.title)
        assertNull(track.artist)
        assertNull(track.app)
        assertNull(track.durationMs)
        assertNull(track.progressAt(5_000L))
    }

    @Test
    fun appsThatDeclareNoActionsGetEveryButton() {
        val track = PlayerState.track(state(PlaybackState.STATE_PLAYING, actions = 0L), song, null, null)
        assertTrue(track.canPrevious)
        assertTrue(track.canNext)
    }

    @Test
    fun thePositionMovesOnlyWhilePlaying() {
        val playing = PlayerState.track(state(PlaybackState.STATE_PLAYING, position = 10_000L, updatedAt = 1_000L), song, null, null)
        assertEquals(15_000L, playing.positionAt(6_000L))
        assertEquals(0.075f, playing.progressAt(6_000L)!!, 0.0001f)
        // Never past the end.
        assertEquals(200_000L, playing.positionAt(1_000_000L))
        assertEquals(1f, playing.progressAt(1_000_000L)!!, 0f)

        val paused = PlayerState.track(state(PlaybackState.STATE_PAUSED, position = 10_000L), song, null, null)
        assertEquals(10_000L, paused.positionAt(60_000L))

        val double = PlayerState.track(state(PlaybackState.STATE_PLAYING, speed = 2f), song, null, null)
        assertEquals(20_000L, double.positionAt(6_000L))

        val unknown = PlayerState.track(state(PlaybackState.STATE_PLAYING, position = PlaybackState.PLAYBACK_POSITION_UNKNOWN), song, null, null)
        assertNull(unknown.positionAt(6_000L))
        assertNull(unknown.progressAt(6_000L))

        val noDuration = PlayerState.track(state(PlaybackState.STATE_PLAYING), TrackInfo("Live", null, null), null, null)
        assertEquals(15_000L, noDuration.positionAt(6_000L))
        assertNull(noDuration.progressAt(6_000L))
    }

    @Test
    fun theWorkoutStripShowsWhilePlayingAndTwoMinutesAfterAPause() {
        // The pause's time is Fortia's own (SessionStateCache), not the app's stamp.
        fun track(playback: Int, pausedAt: Long?) = PlayerState.track(state(playback, updatedAt = 5L).copy(pausedAt = pausedAt), song, null, null)
        val pausedAt = 100_000L
        val paused = track(PlaybackState.STATE_PAUSED, pausedAt)

        assertTrue(track(PlaybackState.STATE_PLAYING, null).showsStrip(pausedAt + 60 * 60_000L))
        assertTrue(track(PlaybackState.STATE_BUFFERING, null).showsStrip(pausedAt + 60 * 60_000L))
        assertTrue(paused.showsStrip(pausedAt))
        assertTrue(paused.showsStrip(pausedAt + STRIP_AFTER_PAUSE_MS - 1))
        assertFalse(paused.showsStrip(pausedAt + STRIP_AFTER_PAUSE_MS))
        assertEquals(pausedAt + STRIP_AFTER_PAUSE_MS, paused.stripHidesAt())
        assertEquals(120_000L, STRIP_AFTER_PAUSE_MS)
        assertEquals(pausedAt, paused.pausedAt)

        // Playing never hides by itself (and carries no pause time); a pause without a time, or a stopped song, does not show.
        assertNull(track(PlaybackState.STATE_PLAYING, pausedAt).stripHidesAt())
        assertNull(track(PlaybackState.STATE_PLAYING, pausedAt).pausedAt)
        assertFalse(track(PlaybackState.STATE_PAUSED, null).showsStrip(pausedAt))
        assertFalse(track(PlaybackState.STATE_STOPPED, pausedAt).showsStrip(pausedAt))
        assertFalse(PlayerState.Idle.showsStrip(pausedAt))
        assertFalse(PlayerState.Keys.showsStrip(pausedAt))
        assertFalse(PlayerState.Off.showsStrip(pausedAt))
    }

    @Test
    fun theSessionPlayingIsChosenElseAPausedOneElseTheFirst() {
        val stopped = PlaybackStatus.STOPPED
        val paused = PlaybackStatus.PAUSED
        val playing = PlaybackStatus.PLAYING
        val buffering = PlaybackStatus.BUFFERING

        assertEquals(2, SessionChoice.pick(listOf(stopped, paused, playing)))
        assertEquals(1, SessionChoice.pick(listOf(paused, buffering)))
        assertEquals(1, SessionChoice.pick(listOf(stopped, paused, paused)))
        // Several playing: the first, as Android lists the most important first.
        assertEquals(0, SessionChoice.pick(listOf(playing, playing)))
        assertEquals(0, SessionChoice.pick(listOf(stopped, stopped)))
        assertNull(SessionChoice.pick(emptyList()))
    }

    @Test
    fun playPauseFollowsTheState() {
        assertEquals(Transport.PAUSE, SessionChoice.transport(PlayerCommand.PLAY_PAUSE, PlaybackStatus.PLAYING))
        assertEquals(Transport.PAUSE, SessionChoice.transport(PlayerCommand.PLAY_PAUSE, PlaybackStatus.BUFFERING))
        assertEquals(Transport.PLAY, SessionChoice.transport(PlayerCommand.PLAY_PAUSE, PlaybackStatus.PAUSED))
        assertEquals(Transport.PLAY, SessionChoice.transport(PlayerCommand.PLAY_PAUSE, PlaybackStatus.STOPPED))
        assertEquals(Transport.NEXT, SessionChoice.transport(PlayerCommand.NEXT, PlaybackStatus.PLAYING))
        assertEquals(Transport.PREVIOUS, SessionChoice.transport(PlayerCommand.PREVIOUS, PlaybackStatus.PAUSED))
    }

    @Test
    fun mediaKeysAreAPressAndAReleaseOfTheRightKey() {
        val sent = mutableListOf<Pair<Int, Int>>()
        val downTimes = mutableListOf<Long>()
        var now = 1_000L
        val keys = MediaKeys(clock = { now++ }) { down, action, code ->
            downTimes += down
            sent += action to code
        }
        keys.press(PlayerCommand.PLAY_PAUSE)
        keys.press(PlayerCommand.NEXT)
        keys.press(PlayerCommand.PREVIOUS)
        // The release carries its press's time (Android matches them to let play/pause through).
        assertEquals(listOf(1_000L, 1_000L, 1_001L, 1_001L, 1_002L, 1_002L), downTimes)
        assertEquals(
            listOf(
                KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.ACTION_UP to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.ACTION_UP to KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.ACTION_UP to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            ),
            sent,
        )
        // Android's values: down 0 / up 1; play/pause 85, next 87, previous 88.
        assertEquals(listOf(0 to 85, 1 to 85, 0 to 87, 1 to 87, 0 to 88, 1 to 88), sent)
    }

    @Test
    fun coversAreScaledDownToTheirShorterSideNeverUp() {
        assertEquals(168 to 168, AndroidMediaSessions.coverSize(640, 640, 168))
        assertEquals(252 to 168, AndroidMediaSessions.coverSize(960, 640, 168))
        assertEquals(100 to 100, AndroidMediaSessions.coverSize(100, 100, 168))
        assertEquals(0 to 0, AndroidMediaSessions.coverSize(0, 0, 168))
    }

    @Test
    fun aCoverIsOnlyReadFromTheMusicAppItself() {
        val providers = mapOf("com.spotify.music.art" to "com.spotify.music", "media" to "com.android.providers.media.module")
        fun allowed(scheme: String?, authority: String?) =
            AndroidMediaSessions.coverSourceAllowed(scheme, authority, "com.spotify.music") { providers[it] }

        assertTrue(allowed("content", "com.spotify.music.art"))
        assertTrue(allowed("android.resource", "com.spotify.music"))
        // Another app's provider or resources, unknown providers, files and the network: never.
        assertFalse(allowed("content", "media"))
        assertFalse(allowed("content", "unknown.provider"))
        assertFalse(allowed("content", null))
        assertFalse(allowed("android.resource", "com.other.app"))
        assertFalse(allowed("file", null))
        assertFalse(allowed("https", "i.scdn.co"))
        assertFalse(allowed(null, null))
    }
}
