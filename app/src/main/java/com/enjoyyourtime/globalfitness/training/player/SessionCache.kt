package com.enjoyyourtime.globalfitness.training.player

import java.util.concurrent.atomic.AtomicReference

/** Not read from Android yet. */
private val UNREAD = Any()

/** Not wanted: the song of a session that is not shown. */
private val UNWANTED = Any()

/** Wanted and being read from Android for the first time. */
private val READING = Any()

/**
 * A session's playback state as Fortia last heard it: read from Android the first time it is
 * asked, then from the session's callbacks. A callback that arrives while that first read is on
 * its way wins over it (the read may be older). A pause is stamped with Fortia's own clock
 * ([clock], SystemClock.elapsedRealtime()) when it is seen happening; re-posted pauses keep that
 * stamp. Only the first read trusts the app's stamp, never beyond now.
 */
internal class SessionStateCache(private val clock: () -> Long) {
    private val ref = AtomicReference<Any?>(UNREAD)

    fun get(read: () -> SessionState): SessionState {
        val known = ref.get()
        if (known !== UNREAD) return known as SessionState
        val first = read().let { it.copy(pausedAt = if (it.status == PlaybackStatus.PAUSED) firstPause(it.positionUpdatedAt) else null) }
        ref.compareAndSet(UNREAD, first)
        return ref.get() as SessionState
    }

    /** From a callback. */
    fun update(state: SessionState) {
        while (true) {
            val previous = ref.get()
            val known = previous as? SessionState
            val pausedAt = when {
                state.status != PlaybackStatus.PAUSED -> null
                known?.status == PlaybackStatus.PAUSED -> known.pausedAt ?: clock()
                // From playing (or before anything was known: a callback says something just changed).
                else -> clock()
            }
            if (ref.compareAndSet(previous, state.copy(pausedAt = pausedAt))) return
        }
    }

    private fun firstPause(stamp: Long): Long {
        val now = clock()
        return if (stamp in 1..now) stamp else now
    }
}

/**
 * The song of a session, kept only while it is wanted (the session shown): read from Android the
 * first time it is asked, then from the session's callbacks; dropped by [forget]. A callback that
 * arrives while the first read is on its way wins over it; callbacks while unwanted are dropped.
 */
internal class SongCache<T : Any> {
    private val ref = AtomicReference<Any?>(UNWANTED)

    fun get(read: () -> T?): T? {
        if (ref.compareAndSet(UNWANTED, READING)) {
            val first = read()
            ref.compareAndSet(READING, first)
        }
        return current()
    }

    /** The song kept now, without asking Android (null when none, or not wanted). */
    @Suppress("UNCHECKED_CAST")
    fun current(): T? = ref.get().takeUnless { it === UNWANTED || it === READING } as T?

    /** From a callback; false (nothing kept) while the song is not wanted. */
    fun update(song: T?): Boolean {
        while (true) {
            val previous = ref.get()
            if (previous === UNWANTED) return false
            if (ref.compareAndSet(previous, song)) return true
        }
    }

    fun forget() {
        ref.set(UNWANTED)
    }
}
