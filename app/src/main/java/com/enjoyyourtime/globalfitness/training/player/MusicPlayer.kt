package com.enjoyyourtime.globalfitness.training.player

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** The phone's media sessions, as far as the player needs them ([AndroidMediaSessions] on a phone). */
interface MediaSessions {
    /** Whether the user gave Fortia's [MediaAccessListener] notification access; read anew on every call. */
    fun accessGranted(): Boolean

    /** The active sessions, most important first. Throws SecurityException without access. */
    fun active(): List<MediaSessionHandle>

    /** Calls [onChange] whenever sessions come or go, until closed. Throws SecurityException without access. */
    fun watch(onChange: () -> Unit): AutoCloseable
}

/** One app's media session. It keeps what it last heard from Android, so a change costs no new reads. */
interface MediaSessionHandle {
    /** Equal for two handles of the same session (Android hands out a new controller every time). */
    val id: Any

    /** Its app and playback state as last known: read from Android once, then from its callbacks. */
    fun state(): SessionState

    /** Its playback state read from Android now (play/pause decides from it). */
    fun liveStatus(): PlaybackStatus

    /**
     * Its song: only asked of the session shown, read from Android the first time and then kept
     * current by its callbacks, until [forgetTrack] (another session is shown).
     */
    fun track(): TrackInfo?

    fun forgetTrack()

    /** Calls [onChange] when its state changes, when its song changes while it is shown, or when it ends; until closed. */
    fun watch(onChange: () -> Unit): AutoCloseable

    fun send(transport: Transport)

    /** The cover of the song [track] last gave, scaled down for the screen, or null. Blocking: called off the main thread. */
    fun cover(): ImageBitmap?

    /** The app's name ("Spotify"), or null when Android does not let Fortia see it. */
    fun appLabel(): String?
}

/**
 * The mini player: what plays and its buttons. With notification access it follows the chosen
 * session ([SessionChoice]) and drives its transport controls; without it, or with nothing
 * playing, the buttons are Android's media keys for whatever app plays ([keys]).
 */
class MusicPlayer(
    private val sessions: MediaSessions,
    private val keys: MediaKeys,
    scope: CoroutineScope,
    coverContext: CoroutineContext = Dispatchers.IO,
) {
    /**
     * Covers load here, apart from [states]: a music app's stuck provider never holds up the
     * player's restarts. A child of [scope], so it ends with it.
     */
    private val coverScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + coverContext)

    /** The session shown, while [states] is collected. */
    @Volatile
    private var target: MediaSessionHandle? = null

    /** The last song's cover, by session and song (with its picture): a pause or a new collection does not load it again. */
    @Volatile
    private var lastCover: Pair<Pair<Any, TrackInfo?>, ImageBitmap?>? = null

    /** The cover being loaded (its job and what it is for); only that one may fill [lastCover]. */
    private val coverLock = Any()
    private var coverJob: Job? = null
    private var coverFor: Pair<Any, TrackInfo?>? = null

    /** The last [states] ended on an error (access refused, a failing read): the next access check tries again. */
    @Volatile
    var needsRetry: Boolean = false
        private set

    fun accessGranted(): Boolean = sessions.accessGranted()

    /**
     * To the session shown, deciding play or pause from its state at the tap; else (or when it
     * fails) as a media key: with access and nothing playing, the key may wake the last player.
     */
    fun send(command: PlayerCommand) {
        val shown = target
        val sent = shown != null && try {
            shown.send(SessionChoice.transport(command, shown.liveStatus()))
            true
        } catch (e: RuntimeException) {
            // The session just ended, or access was revoked: the key still reaches the player.
            false
        }
        if (!sent) keys.press(command)
    }

    /**
     * [PlayerState.Keys] without access; else [PlayerState.Idle] or the chosen session's
     * [PlayerState.Track], again on every change, while collected. Every active session's state is
     * followed (Android reports sessions that come and go, not a paused one that starts playing);
     * only the shown one's song is read. A track is shown at once and its cover follows when
     * loaded. Access refused on the way (revoked meanwhile) ends it with [PlayerState.Keys], any
     * other failure with [PlayerState.Idle]; a session that fails to read is left out. Every
     * callback is removed when the collection stops.
     */
    fun states(): Flow<PlayerState> = channelFlow {
        needsRetry = false
        if (!sessions.accessGranted()) {
            send(PlayerState.Keys)
            return@channelFlow
        }
        val changes = Channel<Unit>(Channel.CONFLATED)
        val poke = { changes.trySend(Unit); Unit }
        val listChanged = AtomicBoolean(true)
        var listRegistration: AutoCloseable? = null
        // The sessions in Android's order (read again only when the list changes), and each one's callback by id.
        var order = emptyList<MediaSessionHandle>()
        val followed = HashMap<Any, Pair<MediaSessionHandle, AutoCloseable>>()
        var shown: MediaSessionHandle? = null
        try {
            listRegistration = sessions.watch {
                listChanged.set(true)
                poke()
            }
            poke()
            for (change in changes) {
                if (listChanged.getAndSet(false)) {
                    val listed = sessions.active()
                    val ids = listed.mapTo(HashSet()) { it.id }
                    followed.keys.filter { it !in ids }.forEach { id -> followed.remove(id)?.let { skipFailing { it.second.close() } } }
                    // Followed before reading, so a change in between is not missed; one that cannot be is left out.
                    order = listed.mapNotNull { handle ->
                        followed[handle.id]?.first ?: skipFailing { handle to handle.watch(poke) }?.also { followed[handle.id] = it }?.first
                    }
                }
                val readable = order.mapNotNull { handle -> skipFailing { handle.state() }?.let { handle to it } }
                val chosen = SessionChoice.pick(readable.map { it.second.status })?.let { readable[it] }
                if (chosen?.first !== shown) {
                    // The cover on its way is the previous session's: dropped, it would come without its song.
                    dropCover()
                    shown?.let { skipFailing { it.forgetTrack() } }
                    shown = chosen?.first
                }
                val song = chosen?.let { skipFailing { it.first.track() } }
                if (chosen == null || song == null && chosen.second.status == PlaybackStatus.STOPPED) {
                    dropCover()
                    target = null
                    send(PlayerState.Idle)
                    continue
                }
                val (handle, state) = chosen
                target = handle
                val key = handle.id to song
                val loaded = lastCover?.takeIf { it.first == key }
                // Until the cover loads: the one already shown for this song (a better picture coming), else none.
                val meanwhile = loaded?.second ?: lastCover?.takeIf { it.first.first == handle.id && song?.sameSong(it.first.second) == true }?.second
                send(PlayerState.track(state, song, meanwhile, skipFailing { handle.appLabel() }))
                if (loaded == null) loadCover(key, handle, poke)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            needsRetry = true
            send(PlayerState.Keys)
        } catch (e: RuntimeException) {
            // Some app's session data Android could not read: nothing playing until the next check.
            needsRetry = true
            send(PlayerState.Idle)
        } finally {
            dropCover()
            shown?.let { skipFailing { it.forgetTrack() } }
            // Another collection may have taken over the buttons meanwhile: only this one's are let go.
            if (target === shown) target = null
            followed.values.forEach { skipFailing { it.second.close() } }
            listRegistration?.let { skipFailing { it.close() } }
        }
    }

    /**
     * Starts loading [key]'s cover unless it is on its way already, off [states] and with a time
     * limit; it is kept and [poke]d only while it is still the one wanted ([dropCover] since).
     */
    private fun loadCover(key: Pair<Any, TrackInfo?>, handle: MediaSessionHandle, poke: () -> Unit) {
        val job = synchronized(coverLock) {
            if (coverFor == key && coverJob?.isActive == true) return
            coverJob?.cancel()
            coverFor = key
            coverScope.launch(start = CoroutineStart.LAZY) {
                val cover = withTimeoutOrNull(COVER_TIMEOUT_MS) { runInterruptible { skipFailing { handle.cover() } } }
                val wanted = synchronized(coverLock) { (coverFor == key).also { if (it) lastCover = key to cover } }
                if (wanted) poke()
            }.also { coverJob = it }
        }
        job.start()
    }

    /** No cover wanted any more (another session shown, nothing playing, the screen gone): the one on its way is dropped. */
    private fun dropCover() = synchronized(coverLock) {
        coverJob?.cancel()
        coverJob = null
        coverFor = null
    }

    companion object {
        /** The largest cover on screen (the Entreno card), in dp: covers are never kept larger. */
        const val LARGEST_COVER_DP = 64

        /** A cover that takes longer than this to load is not shown. */
        const val COVER_TIMEOUT_MS = 2_000L

        /**
         * What one music app's session could not give (bad data, a broken provider): nothing,
         * not a crash. Access refused is not swallowed: it ends the player's states.
         */
        private inline fun <T> skipFailing(read: () -> T): T? = try {
            read()
        } catch (e: SecurityException) {
            throw e
        } catch (e: RuntimeException) {
            null
        }
    }
}

/**
 * The player's states for the screens: [PlayerState.Off] while the training settings turn the
 * controls off, else [states], started again when the access changes. Access is read on every
 * check (the screens ask on resume, back from Android's settings); a check after [needsRetry]
 * (access refused, a failing read) starts it again even when the access looks the same. Anything
 * unexpected from one start shows as nothing playing and ends that start only: the next change
 * of the setting or the access still starts it again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun playerStates(
    controls: Flow<Boolean>,
    checks: Flow<Int>,
    access: () -> Boolean,
    needsRetry: () -> Boolean,
    states: () -> Flow<PlayerState>,
): Flow<PlayerState> =
    combine(controls, checks) { on, check -> if (on) Triple(true, access(), if (needsRetry()) check else 0) else Triple(false, false, 0) }
        .distinctUntilChanged()
        .flatMapLatest { (on) -> if (on) states().catch { emit(PlayerState.Idle) } else flowOf(PlayerState.Off) }
