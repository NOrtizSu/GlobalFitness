package com.enjoyyourtime.globalfitness.training.player

import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.compose.ui.graphics.ImageBitmap

/** The mini player's three buttons. */
enum class PlayerCommand { PREVIOUS, PLAY_PAUSE, NEXT }

/** What a session's transport controls are asked to do for a [PlayerCommand]. */
enum class Transport { PREVIOUS, PLAY, PAUSE, NEXT }

/** A session's [PlaybackState] state folded into what the player shows. */
enum class PlaybackStatus {
    PLAYING,

    /** About to play: buffering, connecting or skipping (shown like playing: the button pauses). */
    BUFFERING,
    PAUSED,

    /** Stopped, never started or failed. */
    STOPPED,
    ;

    val active: Boolean get() = this == PLAYING || this == BUFFERING

    companion object {
        fun of(state: Int): PlaybackStatus = when (state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> PLAYING
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING, PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
            -> BUFFERING
            PlaybackState.STATE_PAUSED -> PAUSED
            else -> STOPPED
        }
    }
}

/** The song of a session, from its MediaMetadata (blank texts are missing ones). */
data class TrackInfo(
    val title: String?,
    val artist: String?,
    val durationMs: Long?,
    /** Tells pictures apart without keeping them ("512x512", a picture's address); null without one. */
    val artKey: String? = null,
) {
    /** The same song, whatever its picture (apps often send the picture a moment after the title). */
    fun sameSong(other: TrackInfo?): Boolean = other != null && copy(artKey = null) == other.copy(artKey = null)

    companion object {
        /**
         * The texts music apps fill in differently: the display title before the plain one, the
         * artist, else the album's artist, else the display subtitle (when it is not the title
         * again). A duration of 0 or less is unknown.
         */
        fun of(
            title: String?,
            displayTitle: String?,
            artist: String?,
            albumArtist: String?,
            displaySubtitle: String?,
            durationMs: Long,
            artKey: String? = null,
        ): TrackInfo {
            val name = clean(displayTitle) ?: clean(title)
            val by = clean(artist) ?: clean(albumArtist) ?: clean(displaySubtitle)?.takeIf { it != name }
            return TrackInfo(name, by, durationMs.takeIf { it > 0 }, artKey)
        }

        private fun clean(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** A media session's app and PlaybackState as plain values (no song: that is only read for the one shown). */
data class SessionState(
    val packageName: String,
    /** A PlaybackState.STATE_* value (STATE_NONE when the app gave none). */
    val state: Int,
    /** PlaybackState.ACTION_* flags; 0 when the app declares none. */
    val actions: Long,
    /** At [positionUpdatedAt]; PlaybackState.PLAYBACK_POSITION_UNKNOWN (-1) when unknown. */
    val positionMs: Long,
    /** SystemClock.elapsedRealtime() of [positionMs], as the app stamped it. */
    val positionUpdatedAt: Long,
    val speed: Float,
    /** When paused: since when, on Fortia's own clock (SystemClock.elapsedRealtime(); see [SessionStateCache]). */
    val pausedAt: Long? = null,
) {
    val status: PlaybackStatus get() = PlaybackStatus.of(state)
}

/** What the mini player shows. */
sealed interface PlayerState {
    /** The music controls are turned off in the training settings: nothing shows, nothing listens. */
    data object Off : PlayerState

    /**
     * No notification access: only Android's media keys, sent to whatever app is playing. Nothing
     * is known about what plays (one play/pause button, no title).
     */
    data object Keys : PlayerState

    /** Access is on and no app has a media session: nothing is playing. */
    data object Idle : PlayerState

    /** The session shown: its song, state and position. */
    data class Track(
        val title: String?,
        val artist: String?,
        /** The playing app's name ("Spotify"); null when Android does not let Fortia see it. */
        val app: String?,
        val status: PlaybackStatus,
        val durationMs: Long?,
        val positionMs: Long,
        val positionUpdatedAt: Long,
        val speed: Float,
        val canPrevious: Boolean,
        val canNext: Boolean,
        /** Already scaled down to what the screen shows. */
        val cover: ImageBitmap?,
        /** When paused: since when (SystemClock.elapsedRealtime(), Fortia's clock). */
        val pausedAt: Long? = null,
    ) : PlayerState {
        /** The position at [elapsedNow] (SystemClock.elapsedRealtime()), moving while playing; null when unknown. */
        fun positionAt(elapsedNow: Long): Long? {
            if (positionMs < 0) return null
            val moved = if (status == PlaybackStatus.PLAYING && positionUpdatedAt > 0) ((elapsedNow - positionUpdatedAt) * speed).toLong() else 0L
            val position = positionMs + moved.coerceAtLeast(0L)
            return durationMs?.let { position.coerceIn(0L, it) } ?: position
        }

        /** Played share of the song at [elapsedNow] (0–1); null when the duration or the position is unknown. */
        fun progressAt(elapsedNow: Long): Float? {
            val duration = durationMs ?: return null
            val position = positionAt(elapsedNow) ?: return null
            return (position.toFloat() / duration).coerceIn(0f, 1f)
        }
    }

    companion object {
        /** The chosen session's [state] and [song] as the player shows them, with its [cover] and [app] name. */
        fun track(state: SessionState, song: TrackInfo?, cover: ImageBitmap?, app: String?): Track = Track(
            title = song?.title,
            artist = song?.artist,
            app = app,
            status = state.status,
            durationMs = song?.durationMs,
            positionMs = state.positionMs,
            positionUpdatedAt = state.positionUpdatedAt,
            speed = state.speed,
            canPrevious = allows(state.actions, PlaybackState.ACTION_SKIP_TO_PREVIOUS),
            canNext = allows(state.actions, PlaybackState.ACTION_SKIP_TO_NEXT),
            cover = cover,
            pausedAt = state.pausedAt.takeIf { state.status == PlaybackStatus.PAUSED },
        )

        /** Apps that declare no actions at all get every button; otherwise only what they declare. */
        private fun allows(actions: Long, action: Long): Boolean = actions == 0L || actions and action != 0L
    }
}

/** How long the workout's strip stays after the music pauses (to resume it from there). */
const val STRIP_AFTER_PAUSE_MS = 2L * 60_000

/**
 * The workout's strip at [elapsedNow] (SystemClock.elapsedRealtime()): while music plays, and for
 * [STRIP_AFTER_PAUSE_MS] after it paused (by Fortia's own clock, [PlayerState.Track.pausedAt]).
 * Then it hides; the sheet, from the top bar's music button, still has the player.
 */
fun PlayerState.showsStrip(elapsedNow: Long): Boolean {
    if (this !is PlayerState.Track) return false
    if (status.active) return true
    val since = pausedAt ?: return false
    return status == PlaybackStatus.PAUSED && elapsedNow - since < STRIP_AFTER_PAUSE_MS
}

/** When a shown strip hides by itself (elapsedRealtime), or null when it does not. */
fun PlayerState.stripHidesAt(): Long? =
    (this as? PlayerState.Track)?.takeIf { it.status == PlaybackStatus.PAUSED }?.pausedAt?.plus(STRIP_AFTER_PAUSE_MS)

/** Which session the player shows. */
object SessionChoice {
    /**
     * The index in [statuses] (most important first, as Android lists the sessions) of the one
     * that plays, else the first paused one, else the first; null without sessions. Only the
     * chosen one's song is read: a stopped one without a song shows as nothing playing.
     */
    fun pick(statuses: List<PlaybackStatus>): Int? =
        statuses.indexOfFirst { it.active }.takeIf { it >= 0 }
            ?: statuses.indexOfFirst { it == PlaybackStatus.PAUSED }.takeIf { it >= 0 }
            ?: 0.takeIf { statuses.isNotEmpty() }

    /** Play/pause pauses what plays (or is about to) and plays anything else. */
    fun transport(command: PlayerCommand, status: PlaybackStatus): Transport = when (command) {
        PlayerCommand.PREVIOUS -> Transport.PREVIOUS
        PlayerCommand.NEXT -> Transport.NEXT
        PlayerCommand.PLAY_PAUSE -> if (status.active) Transport.PAUSE else Transport.PLAY
    }
}

/**
 * Android's media buttons, which reach the app playing (or the last one that played) with no
 * permission at all: a press and a release, as a headset sends them. [dispatch] hands one key
 * event (KeyEvent.ACTION_*, KeyEvent.KEYCODE_*) to AudioManager. Both carry the press's time from
 * [clock] (SystemClock.uptimeMillis): Android holds back a play/pause press to detect a long one
 * and only lets it through with the release whose down time matches.
 */
class MediaKeys(private val clock: () -> Long, private val dispatch: (downTime: Long, action: Int, keyCode: Int) -> Unit) {
    fun press(command: PlayerCommand) {
        val code = keyCode(command)
        val down = clock()
        dispatch(down, KeyEvent.ACTION_DOWN, code)
        dispatch(down, KeyEvent.ACTION_UP, code)
    }

    companion object {
        fun keyCode(command: PlayerCommand): Int = when (command) {
            PlayerCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            PlayerCommand.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            PlayerCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        }
    }
}
