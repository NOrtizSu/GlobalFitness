package com.enjoyyourtime.globalfitness.training.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * The rest countdown after a completed set. Times are wall-clock epoch ms. While the workout is
 * paused the countdown stands still: [pausedLeftMs] holds the rest that was left.
 */
data class RestTimer(val id: Long, val workoutId: String, val endsAt: Long, val totalSec: Int, val pausedLeftMs: Long? = null) {
    val paused: Boolean get() = pausedLeftMs != null

    /** Rest left at [now], in ms (0 once it ended). */
    fun leftMs(now: Long): Long = pausedLeftMs ?: (endsAt - now).coerceAtLeast(0)
}

/**
 * The rest timer of the workout in progress, one for the whole app: the workout's page, the cards
 * and the ongoing notification show it, and pausing the workout (in the app or from the
 * notification) pauses it too. Kept in memory, as before: a process that dies loses it.
 */
class RestTimers(private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow<RestTimer?>(null)
    val state: StateFlow<RestTimer?> = _state.asStateFlow()
    private val seq = AtomicLong()

    /** Starts [seconds] of rest (none for 0), replacing any rest running. */
    fun start(workoutId: String, seconds: Int) {
        if (seconds <= 0) return
        _state.value = RestTimer(seq.incrementAndGet(), workoutId, clock() + seconds * 1000L, seconds)
    }

    /** Adds (or with a negative [deltaSec] removes) rest time; the timer ends when none is left. */
    fun adjust(deltaSec: Int) {
        _state.update { r ->
            r ?: return@update null
            val total = (r.totalSec + deltaSec).coerceAtLeast(1)
            val pausedLeft = r.pausedLeftMs
            if (pausedLeft != null) {
                val left = pausedLeft + deltaSec * 1000L
                if (left <= 0) null else r.copy(pausedLeftMs = left, totalSec = total)
            } else {
                val end = r.endsAt + deltaSec * 1000L
                if (end <= clock()) null else r.copy(endsAt = end, totalSec = total)
            }
        }
    }

    fun skip() {
        _state.value = null
    }

    /** Drops the rest of [workoutId] (finished or discarded). */
    fun clear(workoutId: String) {
        _state.update { if (it?.workoutId == workoutId) null else it }
    }

    /** Clears the ended timer [id]; true only for the first caller, which signals the end. */
    fun consumeEnd(id: Long): Boolean {
        val r = _state.value ?: return false
        return r.id == id && _state.compareAndSet(r, null)
    }

    /** The workout [workoutId] paused at [at]: its rest, if still running, stands still. */
    fun pause(workoutId: String, at: Long = clock()) {
        _state.update { r ->
            if (r == null || r.workoutId != workoutId || r.paused || r.endsAt <= at) r else r.copy(pausedLeftMs = r.endsAt - at)
        }
    }

    /** The workout [workoutId] resumed at [at]: a rest that stood still goes on with the time it had left. */
    fun resume(workoutId: String, at: Long = clock()) {
        _state.update { r ->
            val left = r?.pausedLeftMs
            if (r == null || r.workoutId != workoutId || left == null) r else r.copy(endsAt = at + left, pausedLeftMs = null)
        }
    }
}
