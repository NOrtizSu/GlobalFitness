package com.enjoyyourtime.globalfitness.training.player

import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class MusicPlayerTest {
    private class FakeSession(override val id: Any, var playback: SessionState, var song: TrackInfo?) : MediaSessionHandle {
        val sent = mutableListOf<Transport>()
        var listener: (() -> Unit)? = null
        var covers = 0
        var songReads = 0
        var songWanted = false
        var picture: ImageBitmap? = null
        var failing: RuntimeException? = null

        override fun state(): SessionState {
            failing?.let { throw it }
            return playback
        }

        override fun liveStatus() = playback.status

        override fun track(): TrackInfo? {
            if (!songWanted) songReads++
            songWanted = true
            return song
        }

        override fun forgetTrack() {
            songWanted = false
        }

        override fun watch(onChange: () -> Unit): AutoCloseable {
            listener = onChange
            return AutoCloseable { listener = null }
        }

        override fun send(transport: Transport) {
            sent += transport
        }

        /** As on a phone: the picture is the kept song's, gone once the song is forgotten. */
        override fun cover(): ImageBitmap? {
            covers++
            return picture.takeIf { songWanted }
        }

        override fun appLabel(): String = "Music app"

        fun update(playback: SessionState = this.playback, song: TrackInfo? = this.song) {
            this.playback = playback
            this.song = song
            listener?.invoke()
        }
    }

    private class FakeSessions : MediaSessions {
        var granted = true
        var failing: RuntimeException? = null
        var list: List<FakeSession> = emptyList()
        var listener: (() -> Unit)? = null
        var reads = 0

        override fun accessGranted() = granted

        override fun active(): List<MediaSessionHandle> {
            reads++
            failing?.let { throw it }
            return list
        }

        override fun watch(onChange: () -> Unit): AutoCloseable {
            failing?.let { throw it }
            listener = onChange
            return AutoCloseable { listener = null }
        }

        fun change(newList: List<FakeSession>) {
            list = newList
            listener?.invoke()
        }
    }

    private fun state(playback: Int, position: Long = 0L, at: Long = 1_000L) = SessionState("com.example.music", playback, 0L, position, at, 1f)

    private fun song(title: String, art: String? = null) = TrackInfo(title, "Artist", 180_000L, art)

    private fun session(id: String, playback: Int, title: String?) = FakeSession(id, state(playback), title?.let { song(it) })

    /** A picture for the tests (never drawn): only its identity matters. */
    private fun picture(): ImageBitmap = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ImageBitmap::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "picture"
            else -> null
        }
    } as ImageBitmap

    private val keys = mutableListOf<Pair<Int, Int>>()

    private fun TestScope.player(sessions: MediaSessions, coverContext: CoroutineContext = UnconfinedTestDispatcher(testScheduler)) =
        MusicPlayer(sessions, MediaKeys({ 0L }) { _, action, code -> keys += action to code }, backgroundScope, coverContext)

    /** Collects [player]'s states into [into] until the test ends (or the returned job is cancelled). */
    private fun TestScope.collect(player: MusicPlayer, into: MutableList<PlayerState> = mutableListOf()) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.states().collect { into += it } }

    private val List<PlayerState>.lastTrack get() = last() as PlayerState.Track

    @Test
    fun withoutAccessOnlyTheKeysAndNoSessionIsTouched() = runTest {
        val sessions = FakeSessions().apply { granted = false }
        val states = mutableListOf<PlayerState>()
        collect(player(sessions), states)
        runCurrent()
        assertEquals(listOf(PlayerState.Keys), states)
        assertEquals(0, sessions.reads)
        assertNull(sessions.listener)
    }

    @Test
    fun withAccessAndNothingPlayingItIsIdle() = runTest {
        val sessions = FakeSessions()
        val states = mutableListOf<PlayerState>()
        collect(player(sessions), states)
        runCurrent()
        assertEquals(listOf(PlayerState.Idle), states)
        assertNotNull(sessions.listener)
    }

    @Test
    fun theSessionPlayingIsShownAndFollowed() = runTest {
        val paused = session("a", PlaybackState.STATE_PAUSED, "Paused song")
        val playing = session("b", PlaybackState.STATE_PLAYING, "Playing song")
        val sessions = FakeSessions().apply { list = listOf(paused, playing) }
        val states = mutableListOf<PlayerState>()
        collect(player(sessions), states)
        runCurrent()

        assertEquals("Playing song", states.lastTrack.title)
        assertEquals("Music app", states.lastTrack.app)
        // Both are followed: Android says nothing when a paused session starts playing.
        assertNotNull(playing.listener)
        assertNotNull(paused.listener)

        // Its song changes.
        playing.update(song = song("Next song"))
        runCurrent()
        assertEquals("Next song", states.lastTrack.title)

        // It pauses and the other one starts, with no change to the list: the other one is shown.
        playing.update(state(PlaybackState.STATE_PAUSED))
        paused.update(state(PlaybackState.STATE_PLAYING), song("Other song"))
        runCurrent()
        assertEquals("Other song", states.lastTrack.title)
        // Changes of the sessions' own state never asked Android for the list again.
        assertEquals(1, sessions.reads)

        // A session that ends is no longer followed.
        sessions.change(listOf(paused))
        runCurrent()
        assertNull(playing.listener)
        assertNotNull(paused.listener)

        sessions.change(emptyList())
        runCurrent()
        assertEquals(PlayerState.Idle, states.last())
        assertNull(paused.listener)
    }

    @Test
    fun onlyTheShownSessionsSongIsRead() = runTest {
        val shown = session("a", PlaybackState.STATE_PLAYING, "Shown")
        val other = session("b", PlaybackState.STATE_PAUSED, "Not looked at")
        val sessions = FakeSessions().apply { list = listOf(shown, other) }
        val job = collect(player(sessions))
        runCurrent()
        assertTrue(shown.songWanted)
        assertEquals(0, other.songReads)

        // The other one starts: its song is read now, and the first one's is let go.
        shown.update(state(PlaybackState.STATE_PAUSED))
        other.update(state(PlaybackState.STATE_PLAYING))
        runCurrent()
        assertEquals(1, other.songReads)
        assertTrue(other.songWanted)
        assertFalse(shown.songWanted)

        job.cancel()
        runCurrent()
        assertFalse(other.songWanted)
    }

    @Test
    fun aSessionThatStartsPlayingLaterIsShown() = runTest {
        // Active but with nothing to show yet (no state, no song): nothing playing.
        val waiting = session("a", PlaybackState.STATE_NONE, null)
        val sessions = FakeSessions().apply { list = listOf(waiting) }
        val states = mutableListOf<PlayerState>()
        collect(player(sessions), states)
        runCurrent()
        assertEquals(PlayerState.Idle, states.last())

        waiting.update(state(PlaybackState.STATE_PLAYING), song("First song"))
        runCurrent()
        assertEquals("First song", states.lastTrack.title)
    }

    @Test
    fun theTrackShowsAtOnceAndItsCoverFollows() = runTest {
        val session = session("a", PlaybackState.STATE_PLAYING, "Song").apply { picture = picture() }
        val sessions = FakeSessions().apply { list = listOf(session) }
        val states = mutableListOf<PlayerState>()
        collect(player(sessions), states)
        runCurrent()
        val shown = states.filterIsInstance<PlayerState.Track>()
        assertEquals(2, shown.size)
        assertNull(shown[0].cover)
        assertSame(session.picture, shown[1].cover)
        assertEquals("Song", shown[0].title)
    }

    @Test
    fun theCoverIsLoadedOncePerSong() = runTest {
        val session = session("a", PlaybackState.STATE_PLAYING, "Song")
        val sessions = FakeSessions().apply { list = listOf(session) }
        val states = mutableListOf<PlayerState>()
        val player = player(sessions)
        val job = collect(player, states)
        runCurrent()
        assertEquals(1, session.covers)

        // Paused, sought: the same song.
        session.update(state(PlaybackState.STATE_PAUSED, position = 30_000L))
        runCurrent()
        assertEquals(PlaybackStatus.PAUSED, states.lastTrack.status)
        assertEquals(1, session.covers)

        session.update(state(PlaybackState.STATE_PLAYING), song("Another song"))
        runCurrent()
        assertEquals(2, session.covers)

        // Shown again later (back on the screen): still known.
        job.cancel()
        collect(player, states)
        runCurrent()
        assertEquals(2, session.covers)
    }

    @Test
    fun aBetterPictureForTheSameSongLoadsAndTheOldOneStaysMeanwhile() = runTest {
        val small = picture()
        val large = picture()
        val session = FakeSession("a", state(PlaybackState.STATE_PLAYING), song("Song", art = "64x64")).apply { picture = small }
        val sessions = FakeSessions().apply { list = listOf(session) }
        val states = mutableListOf<PlayerState>()
        collect(player(sessions), states)
        runCurrent()
        assertSame(small, states.lastTrack.cover)

        session.picture = large
        val before = states.size
        session.update(song = song("Song", art = "512x512"))
        runCurrent()
        val after = states.drop(before).filterIsInstance<PlayerState.Track>()
        assertSame(small, after.first().cover)
        assertSame(large, after.last().cover)
        assertEquals(2, session.covers)
    }

    @Test
    fun aCoverOnItsWayForASessionNoLongerShownIsDropped() = runTest {
        // Covers load only when the test lets them (the cover job waits on this dispatcher).
        val covers = StandardTestDispatcher(testScheduler)
        val first = session("a", PlaybackState.STATE_PLAYING, "First").apply { picture = picture() }
        val second = session("b", PlaybackState.STATE_PAUSED, "Second").apply { picture = picture() }
        val sessions = FakeSessions().apply { list = listOf(first, second) }
        val states = mutableListOf<PlayerState>()
        collect(player(sessions, covers), states)
        assertEquals("First", states.lastTrack.title)

        // Before the first cover loads, the other session starts: its cover loads, not the first's.
        first.update(state(PlaybackState.STATE_PAUSED))
        second.update(state(PlaybackState.STATE_PLAYING))
        runCurrent()
        assertEquals("Second", states.lastTrack.title)
        assertSame(second.picture, states.lastTrack.cover)
        assertEquals(0, first.covers)

        // Back to the first: its cover was not left behind as "none".
        second.update(state(PlaybackState.STATE_PAUSED))
        first.update(state(PlaybackState.STATE_PLAYING))
        runCurrent()
        assertEquals("First", states.lastTrack.title)
        assertSame(first.picture, states.lastTrack.cover)
    }

    @Test
    fun aCoverOnItsWayWhenNothingPlaysAnyMoreIsDropped() = runTest {
        val covers = StandardTestDispatcher(testScheduler)
        val session = session("a", PlaybackState.STATE_PLAYING, "Song").apply { picture = picture() }
        val sessions = FakeSessions().apply { list = listOf(session) }
        val states = mutableListOf<PlayerState>()
        collect(player(sessions, covers), states)

        sessions.change(emptyList())
        runCurrent()
        assertEquals(PlayerState.Idle, states.last())
        assertEquals(0, session.covers)

        sessions.change(listOf(session))
        runCurrent()
        assertSame(session.picture, states.lastTrack.cover)
    }

    @Test
    fun aCoverThatFailsToLoadIsJustMissing() = runTest {
        val failing = object : MediaSessionHandle by session("a", PlaybackState.STATE_PLAYING, "Song") {
            override fun cover(): ImageBitmap = throw IllegalStateException("The provider crashed")
        }
        val sessions = object : MediaSessions {
            override fun accessGranted() = true
            override fun active(): List<MediaSessionHandle> = listOf(failing)
            override fun watch(onChange: () -> Unit) = AutoCloseable { }
        }
        val states = mutableListOf<PlayerState>()
        collect(player(sessions), states)
        runCurrent()
        assertEquals("Song", states.lastTrack.title)
        assertNull(states.lastTrack.cover)
        assertEquals("Music app", states.lastTrack.app)
    }

    @Test
    fun aSessionThatFailsToReadIsLeftOut() = runTest {
        // E.g. a BadParcelableException from a misbehaving app's extras.
        val broken = session("a", PlaybackState.STATE_PLAYING, "Broken").apply { failing = IllegalStateException("bad parcel") }
        val fine = session("b", PlaybackState.STATE_PLAYING, "Fine")
        val sessions = FakeSessions().apply { list = listOf(broken, fine) }
        val states = mutableListOf<PlayerState>()
        collect(player(sessions), states)
        runCurrent()
        assertEquals("Fine", states.lastTrack.title)

        // Readable again later: shown again, as the first one playing.
        broken.failing = null
        broken.update()
        runCurrent()
        assertEquals("Broken", states.lastTrack.title)
    }

    @Test
    fun aSessionThatCannotBeFollowedIsLeftOut() = runTest {
        val unfollowable = object : MediaSessionHandle by session("a", PlaybackState.STATE_PLAYING, "Unfollowable") {
            override fun watch(onChange: () -> Unit): AutoCloseable = throw IllegalStateException("dead session")
        }
        val fine = session("b", PlaybackState.STATE_PAUSED, "Fine")
        val sessions = object : MediaSessions {
            override fun accessGranted() = true
            override fun active(): List<MediaSessionHandle> = listOf(unfollowable, fine)
            override fun watch(onChange: () -> Unit) = AutoCloseable { }
        }
        val states = mutableListOf<PlayerState>()
        collect(player(sessions), states)
        runCurrent()
        assertEquals("Fine", states.lastTrack.title)
    }

    @Test
    fun anUnexpectedFailureShowsNothingPlayingAndIsRetried() = runTest {
        val sessions = FakeSessions().apply { failing = IllegalStateException("system service died") }
        val states = mutableListOf<PlayerState>()
        val player = player(sessions)
        collect(player, states)
        runCurrent()
        assertEquals(listOf(PlayerState.Idle), states)
        assertTrue(player.needsRetry)
    }

    @Test
    fun accessRevokedMeanwhileFallsBackToTheKeys() = runTest {
        val session = session("a", PlaybackState.STATE_PLAYING, "Song")
        val sessions = FakeSessions().apply { list = listOf(session) }
        val states = mutableListOf<PlayerState>()
        val player = player(sessions)
        collect(player, states)
        runCurrent()
        assertTrue(states.last() is PlayerState.Track)
        assertFalse(player.needsRetry)

        sessions.failing = SecurityException("Missing permission to control media.")
        sessions.change(listOf(session))
        runCurrent()
        assertEquals(PlayerState.Keys, states.last())
        assertTrue(player.needsRetry)
        // Nothing stays registered, and the buttons are media keys again.
        assertNull(session.listener)
        player.send(PlayerCommand.NEXT)
        assertEquals(listOf(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_UP to KeyEvent.KEYCODE_MEDIA_NEXT), keys)
        assertTrue(session.sent.isEmpty())
    }

    @Test
    fun accessRefusedFromTheStartIsTheKeysToo() = runTest {
        // Android says the listener is on but refuses the sessions (e.g. it was just turned off).
        val sessions = FakeSessions().apply { failing = SecurityException("Missing permission to control media.") }
        val states = mutableListOf<PlayerState>()
        collect(player(sessions), states)
        runCurrent()
        assertEquals(listOf(PlayerState.Keys), states)
    }

    @Test
    fun noCallbackOutlivesTheCollection() = runTest {
        val session = session("a", PlaybackState.STATE_PLAYING, "Song")
        val sessions = FakeSessions().apply { list = listOf(session) }
        val job = collect(player(sessions))
        runCurrent()
        assertNotNull(sessions.listener)
        assertNotNull(session.listener)
        job.cancel()
        runCurrent()
        assertNull(sessions.listener)
        assertNull(session.listener)
    }

    @Test
    fun theButtonsDriveTheSessionShownElseTheyAreMediaKeys() = runTest {
        val session = session("a", PlaybackState.STATE_PLAYING, "Song")
        val sessions = FakeSessions().apply { list = listOf(session) }
        val player = player(sessions)

        // Not on screen: media keys.
        player.send(PlayerCommand.PLAY_PAUSE)
        assertEquals(listOf(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_UP to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), keys)

        val job = collect(player)
        runCurrent()
        player.send(PlayerCommand.PLAY_PAUSE)
        player.send(PlayerCommand.NEXT)
        // Paused meanwhile, before any callback said so: play/pause asks the session at the tap.
        session.playback = state(PlaybackState.STATE_PAUSED)
        player.send(PlayerCommand.PLAY_PAUSE)
        player.send(PlayerCommand.PREVIOUS)
        assertEquals(listOf(Transport.PAUSE, Transport.NEXT, Transport.PLAY, Transport.PREVIOUS), session.sent)
        assertEquals(2, keys.size)

        // Nothing playing: the keys may wake the last player.
        sessions.change(emptyList())
        runCurrent()
        player.send(PlayerCommand.PLAY_PAUSE)
        assertEquals(4, keys.size)
        job.cancel()
    }

    @Test
    fun aSessionThatFailsToTakeTheCommandGetsTheKey() = runTest {
        val dead = object : MediaSessionHandle by session("a", PlaybackState.STATE_PLAYING, "Song") {
            override fun send(transport: Transport) = throw IllegalStateException("session gone")
        }
        val sessions = object : MediaSessions {
            override fun accessGranted() = true
            override fun active(): List<MediaSessionHandle> = listOf(dead)
            override fun watch(onChange: () -> Unit) = AutoCloseable { }
        }
        val player = player(sessions)
        collect(player)
        runCurrent()
        player.send(PlayerCommand.NEXT)
        assertEquals(listOf(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_UP to KeyEvent.KEYCODE_MEDIA_NEXT), keys)
    }

    @Test
    fun aStartThatFailsShowsNothingPlayingAndLaterChangesStillWork() = runTest {
        val controls = MutableStateFlow(true)
        val checks = MutableStateFlow(0)
        var granted = true
        var started = 0
        val states = mutableListOf<PlayerState>()
        val source: () -> Flow<PlayerState> = {
            started++
            if (started == 1) flow { throw IllegalStateException("unexpected") } else flowOf(PlayerState.Keys)
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            playerStates(controls, checks, { granted }, { false }, source).collect { states += it }
        }
        runCurrent()
        assertEquals(listOf(PlayerState.Idle), states)

        // The pipeline is still there: an access change starts it again, the setting still turns it off.
        granted = false
        checks.value = 1
        runCurrent()
        assertEquals(PlayerState.Keys, states.last())
        controls.value = false
        runCurrent()
        assertEquals(PlayerState.Off, states.last())
    }

    @Test
    fun checksStartThePlayerAgainOnlyWhenTheAccessChangesOrAReadFailed() = runTest {
        val controls = MutableStateFlow(true)
        val checks = MutableStateFlow(0)
        var granted = true
        var retry = false
        var started = 0
        val states = mutableListOf<PlayerState>()
        val source: () -> Flow<PlayerState> = {
            started++
            flowOf(PlayerState.Idle)
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            playerStates(controls, checks, { granted }, { retry }, source).collect { states += it }
        }
        runCurrent()
        assertEquals(listOf(PlayerState.Idle), states)
        assertEquals(1, started)

        // The first resume, and any other with the same access: no new start.
        checks.value = 1
        runCurrent()
        checks.value = 2
        runCurrent()
        assertEquals(1, started)

        // Back from Android's settings with the access changed.
        granted = false
        checks.value = 3
        runCurrent()
        assertEquals(2, started)

        // The last start ended on a refused read: every check tries again.
        retry = true
        checks.value = 4
        runCurrent()
        checks.value = 5
        runCurrent()
        assertEquals(4, started)
        retry = false

        controls.value = false
        runCurrent()
        assertEquals(PlayerState.Off, states.last())
        // Off: a check does not start listening.
        checks.value = 6
        runCurrent()
        assertEquals(4, started)
        assertEquals(PlayerState.Off, states.last())

        controls.value = true
        runCurrent()
        assertEquals(5, started)
        assertEquals(PlayerState.Idle, states.last())
    }
}
