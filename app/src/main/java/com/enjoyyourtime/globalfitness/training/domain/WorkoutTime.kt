package com.enjoyyourtime.globalfitness.training.domain

import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutPause

/**
 * The time actually trained: a workout's span minus its pauses. Every duration the app shows or
 * uses goes through here (the clock of the workout in progress, the cards, the history, the finish
 * summary, Health Connect, the music's "hard day"), so a pause never counts as training.
 *
 * Pauses are kept as intervals, not as a total: the active time is exact at any moment, edits that
 * move or shorten a workout keep them where they happened, and Health Connect gets them as pause
 * segments. Stored pauses are [normalize]d.
 */
object WorkoutTime {
    /** Time paused inside [start, end]; an open pause lasts until [end]. */
    fun pausedMs(start: Long, end: Long, pauses: List<WorkoutPause>): Long {
        var total = 0L
        for (p in pauses) {
            val from = maxOf(p.start, start)
            val to = minOf(p.end ?: end, end)
            if (to > from) total += to - from
        }
        return total
    }

    /** Active time from [start] to [end]. */
    fun activeMs(start: Long, end: Long, pauses: List<WorkoutPause>): Long =
        (end - start - pausedMs(start, end, pauses)).coerceAtLeast(0)

    /** Active time of [w]: until its end, or [now] while in progress (it stands still while paused). */
    fun activeMs(w: Workout, now: Long): Long = activeMs(w.startedAt, maxOf(w.endedAt ?: now, w.startedAt), w.pauses)

    /** Active duration of a finished workout, null while it is in progress. */
    fun durationMs(w: Workout): Long? = w.endedAt?.let { activeMs(w.startedAt, maxOf(it, w.startedAt), w.pauses) }

    /**
     * When a clock counting [w]'s active time would have started: its start plus every pause so
     * far. For a running workout, `now - base` is the active time (a chronometer's base); it does
     * not move while the workout runs.
     */
    fun runningBase(w: Workout): Long = w.startedAt + w.pauses.sumOf { p -> p.end?.let { (it - p.start).coerceAtLeast(0) } ?: 0L }

    /** [w] paused at [at]; one that is finished or already paused is returned as it is. */
    fun pause(w: Workout, at: Long): Workout {
        if (!w.inProgress || w.paused) return w
        val from = maxOf(at, w.startedAt, w.pauses.lastOrNull()?.end ?: Long.MIN_VALUE)
        return w.copy(pauses = w.pauses + WorkoutPause(from, null))
    }

    /** [w] running again from [at]; one that is not paused is returned as it is. */
    fun resume(w: Workout, at: Long): Workout = if (w.paused) w.copy(pauses = closed(w.pauses, at)) else w

    /**
     * [pauses] with the open one (if any) ended at [at]: resuming, or finishing while paused (the
     * paused stretch until the finish does not count). A pause that lasted no time is dropped.
     */
    fun closed(pauses: List<WorkoutPause>, at: Long): List<WorkoutPause> {
        val last = pauses.lastOrNull()
        if (last == null || last.end != null) return pauses
        return if (at > last.start) pauses.dropLast(1) + last.copy(end = at) else pauses.dropLast(1)
    }

    /**
     * Pauses as stored: sorted, clipped to the workout ([start] to [end], null while in progress),
     * overlapping or touching ones merged, empty ones dropped. Only the last can be open, and only
     * while the workout is in progress (an open pause of a finished workout ends with it). Used for
     * whatever comes from outside (the cloud backup, an older or newer app) and after edits that
     * move the workout.
     */
    fun normalize(pauses: List<WorkoutPause>, start: Long, end: Long?): List<WorkoutPause> {
        if (pauses.isEmpty()) return pauses
        val out = ArrayList<WorkoutPause>(pauses.size)
        for (p in pauses.sortedWith(compareBy<WorkoutPause> { it.start }.thenBy { it.end ?: Long.MAX_VALUE })) {
            val from = maxOf(p.start, start)
            val to = if (end == null) p.end else minOf(p.end ?: end, end)
            if (to != null && to <= from) continue
            val previous = out.lastOrNull()
            val previousEnd = previous?.end
            if (previous != null && (previousEnd == null || from <= previousEnd)) {
                // An open pause swallows everything after it: it lasts until now.
                out[out.lastIndex] = previous.copy(end = if (previousEnd == null || to == null) null else maxOf(previousEnd, to))
            } else {
                out += WorkoutPause(from, to)
            }
        }
        return out
    }

    /** [pauses] moved by [deltaMs] (the whole workout was moved in time). */
    fun shifted(pauses: List<WorkoutPause>, deltaMs: Long): List<WorkoutPause> =
        if (deltaMs == 0L) pauses else pauses.map { WorkoutPause(it.start + deltaMs, it.end?.plus(deltaMs)) }

    /**
     * Time logged in [w]'s completed timed sets ([timed]: an exercise logged by time, i.e. cardio
     * machines, sports and activities, holds and stretches): what they say was trained, whatever the
     * clock says.
     */
    fun loggedMs(w: Workout, timed: (exerciseId: String) -> Boolean): Long =
        w.entries.filter { timed(it.exerciseId) }.sumOf { e -> e.sets.filter { it.done }.sumOf { (it.durationSec ?: 0).coerceAtLeast(0) * 1000L } }

    /**
     * [w] lasting at least [loggedMs] of active time: a sport or a cardio session logged after it was
     * done ("football, 1:30:00" ticked in a workout started a minute ago) is not cut to the clock, so
     * the history, Health Connect and the music's "hard day" get the time played. In progress, its
     * start moves back, never before [notBefore] (the end of the workout before it); its pauses stay
     * where they were. Finished (logged afterwards), its end moves on, never past [now], and then its
     * start back as far as still needed; the sets ticked at its old end (see the workout screen:
     * a past workout's sets are done at its end) move to the new end. Returned as it is when it
     * already lasts that long.
     */
    fun fitToLogged(w: Workout, loggedMs: Long, now: Long, notBefore: Long?): Workout {
        val active = activeMs(w, now)
        if (loggedMs <= active) return w
        val floor = notBefore ?: Long.MIN_VALUE
        fun startBack(missing: Long): Long = maxOf(w.startedAt - missing, floor).coerceAtMost(w.startedAt)
        val oldEnd = w.endedAt
        if (oldEnd == null) {
            val start = startBack(loggedMs - active)
            return if (start == w.startedAt) w else w.copy(startedAt = start)
        }
        val end = endFor(w.startedAt, loggedMs, w.pauses).coerceAtMost(now).coerceAtLeast(oldEnd)
        val missing = loggedMs - activeMs(w.startedAt, end, w.pauses)
        val start = if (missing > 0) startBack(missing) else w.startedAt
        if (start == w.startedAt && end == oldEnd) return w
        return w.copy(
            startedAt = start,
            endedAt = end,
            entries = w.entries.map { e -> e.copy(sets = e.sets.map { s -> if (s.doneAt == oldEnd) s.copy(doneAt = end) else s }) },
        )
    }

    /**
     * [current] with the times it had [before] [fitToLogged] made it [fitted] ("Undo"): its start,
     * and for a finished one its end, the sets that moved with the end going back (any set ticked
     * since stays inside it). Returned as it is when its start or end changed since.
     */
    fun unfit(current: Workout, before: Workout, fitted: Workout): Workout {
        if (current.startedAt != fitted.startedAt || current.endedAt != fitted.endedAt) return current
        val end = before.endedAt ?: return current.copy(startedAt = before.startedAt)
        val doneBefore = before.entries.flatMap { it.sets }.associate { it.id to it.doneAt }
        return current.copy(
            startedAt = before.startedAt,
            endedAt = end,
            entries = current.entries.map { e ->
                e.copy(
                    sets = e.sets.map { s ->
                        val at = s.doneAt ?: return@map s
                        val was = doneBefore[s.id]?.takeIf { at == fitted.endedAt } ?: at
                        s.copy(doneAt = was.coerceIn(before.startedAt, end))
                    },
                )
            },
            pauses = normalize(current.pauses, before.startedAt, end),
        )
    }

    /** The end of the last workout that started before [w] (null: none): [fitToLogged] never moves [w] over it. */
    fun previousEnd(workouts: List<Workout>, w: Workout): Long? =
        workouts.filter { it.id != w.id && it.startedAt < w.startedAt }.mapNotNull { it.endedAt }.maxOrNull()

    /**
     * The end of a finished workout starting at [start] that lasts [activeMs] of active time with
     * these (closed) [pauses]: every pause that begins before that end lengthens it, so the end
     * never falls inside a pause. Pauses after it no longer belong to the workout.
     */
    fun endFor(start: Long, activeMs: Long, pauses: List<WorkoutPause>): Long {
        var end = start + activeMs.coerceAtLeast(0)
        for (p in pauses.sortedBy { it.start }) {
            val pauseEnd = p.end ?: continue
            if (p.start >= end) break
            end += (pauseEnd - maxOf(p.start, start)).coerceAtLeast(0)
        }
        return end
    }
}
