package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/** How a [Track] moves from one key to the next. */
internal enum class Ease {
    /** Starts and ends slowly, like a controlled repetition. */
    IN_OUT,

    /** Starts slowly, arrives fast: a weight falling back. */
    IN,

    /** Starts fast, settles slowly: a hip snap floating the weight up. */
    OUT,
    ;

    fun apply(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (this) {
            IN_OUT -> 0.5f - 0.5f * cos(PI.toFloat() * x)
            IN -> x * x
            OUT -> 1 - (1 - x) * (1 - x)
        }
    }
}

/** A key of a [Track]: at [time] seconds the value is [value]; [ease] shapes the way from the previous key. */
internal data class Key(val time: Float, val value: Float, val ease: Ease = Ease.IN_OUT)

/**
 * A looping scalar channel made of keys: the value of a pattern's driver (0 = start position, 1 =
 * the other end of the repetition) over one loop. The first key is at time 0, the last one at
 * [duration] and has the first one's value, so the loop closes without a jump. Two keys with the
 * same value make a hold.
 */
internal class Track(keys: List<Key>) {
    val keys: List<Key> = keys.toList()
    val duration: Float = this.keys.last().time

    init {
        require(this.keys.size >= 2) { "a track needs at least two keys" }
        require(this.keys.first().time == 0f) { "the first key must be at time 0" }
        require(this.keys.zipWithNext().all { (a, b) -> b.time > a.time }) { "key times must increase" }
        require(this.keys.first().value == this.keys.last().value) { "the loop must close" }
    }

    /** The value at [time] seconds, wrapping around the loop. */
    fun value(time: Float): Float {
        val t = wrap(time, duration)
        var i = 1
        while (i < keys.size - 1 && keys[i].time < t) i++
        val a = keys[i - 1]
        val b = keys[i]
        val f = (t - a.time) / (b.time - a.time)
        return lerp(a.value, b.value, b.ease.apply(f))
    }

    companion object {
        /**
         * One repetition from the start position (0) to the other end (1) and back: [rest] at the
         * start, [go] seconds there ([goEase]), [hold] at the end, [back] seconds back ([backEase]).
         */
        fun rep(go: Float = 1.0f, hold: Float = 0.2f, back: Float = 1.0f, rest: Float = 0.45f, goEase: Ease = Ease.IN_OUT, backEase: Ease = Ease.IN_OUT): Track {
            val keys = mutableListOf(Key(0f, 0f))
            var t = 0f
            if (rest > 0f) {
                t += rest
                keys += Key(t, 0f)
            }
            t += go
            keys += Key(t, 1f, goEase)
            if (hold > 0f) {
                t += hold
                keys += Key(t, 1f)
            }
            t += back
            keys += Key(t, 0f, backEase)
            return Track(keys)
        }

        /** A steady cycle (running, pedalling, holds): [cycles] turns at constant speed in [seconds]. */
        fun cycle(seconds: Float, cycles: Int = 1): CycleTrack = CycleTrack(seconds, cycles)

        /** Keys from (time, value) pairs, all eased in and out. */
        fun keys(vararg points: Pair<Float, Float>): Track = Track(points.map { (t, v) -> Key(t, v) })
    }
}

/** Phase of a steady cycle: [cycles] turns over [duration] seconds; the value is the fraction of a turn (0..1). */
internal class CycleTrack(val duration: Float, val cycles: Int) {
    fun phase(time: Float): Float {
        val turns = wrap(time, duration) / duration * cycles
        return turns - floor(turns)
    }
}

/** [time] wrapped into [0, period). */
internal fun wrap(time: Float, period: Float): Float {
    val r = time % period
    return if (r < 0f) r + period else r
}
