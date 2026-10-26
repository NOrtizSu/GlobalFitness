package com.enjoyyourtime.globalfitness.training.music

/**
 * The day's [COUNT] playlists. Each training day takes a fixed number of each mood ([mix]); within
 * a mood the playlists rotate:
 *
 * - every mood has a daily window: the most any day takes of it ([window]);
 * - its pool is shuffled once per cycle (as many days as whole windows fit in the pool), and each
 *   day of the cycle takes the next window (as many as the day needs, from its start);
 * - a new cycle's first window never repeats the previous cycle's last one (when the pool holds
 *   at least three windows), so two consecutive days never share a playlist.
 *
 * Everything follows from the epoch day (no stored state): the same day and training give the same
 * five, which only change when the training does (e.g. a cardio workout is started). A mood with
 * too few playlists is completed with other ones, in an order of the day.
 */
object DailyPlaylists {
    const val COUNT = 5

    /** How many playlists of each mood a day gets, in the order they are shown. */
    fun mix(day: TrainingDay): Map<PlaylistMood, Int> = when (day) {
        TrainingDay.STRENGTH, TrainingDay.LEGS -> linkedMapOf(PlaylistMood.INTENSE to 4, PlaylistMood.GENERAL to 1)
        TrainingDay.CARDIO -> linkedMapOf(PlaylistMood.CARDIO to 4, PlaylistMood.GENERAL to 1)
        TrainingDay.TRAINING_DAY -> linkedMapOf(PlaylistMood.INTENSE to 3, PlaylistMood.CARDIO to 1, PlaylistMood.GENERAL to 1)
        // An easy walk or ride fits a rest day too.
        TrainingDay.RECOVERY, TrainingDay.REST -> linkedMapOf(PlaylistMood.CALM to 4, PlaylistMood.CARDIO to 1)
    }

    /** The most playlists of [mood] any day takes. */
    fun window(mood: PlaylistMood): Int = TrainingDay.entries.maxOf { mix(it)[mood] ?: 0 }

    fun pick(epochDay: Long, day: TrainingDay, catalogue: List<WorkoutPlaylist> = WorkoutPlaylists.all): List<WorkoutPlaylist> {
        val picked = LinkedHashSet<WorkoutPlaylist>()
        for ((mood, count) in mix(day)) {
            picked += dayWindow(catalogue.filter { it.mood == mood }, mood, epochDay).take(count)
        }
        if (picked.size < COUNT) {
            picked += shuffled(catalogue.distinct(), seed(FILL_SALT, epochDay)).filterNot { it in picked }.take(COUNT - picked.size)
        }
        return picked.toList()
    }

    /** The [epochDay]'s window of [pool]: up to [window] playlists, never the previous day's when the pool allows. */
    private fun dayWindow(pool: List<WorkoutPlaylist>, mood: PlaylistMood, epochDay: Long): List<WorkoutPlaylist> {
        if (pool.isEmpty()) return emptyList()
        val width = minOf(window(mood), pool.size).coerceAtLeast(1)
        val days = pool.size / width
        val slot = Math.floorMod(epochDay, days.toLong()).toInt()
        val order = cycleOrder(pool, mood, Math.floorDiv(epochDay, days.toLong()), width, days)
        return order.subList(slot * width, slot * width + width)
    }

    /**
     * [pool] shuffled for [cycle]. Playlists of its first window that the previous cycle's last
     * window has are swapped with others from neither window. Swaps never touch the last window, so
     * the previous cycle's last window is its plain shuffle (nothing recursive).
     */
    private fun cycleOrder(pool: List<WorkoutPlaylist>, mood: PlaylistMood, cycle: Long, width: Int, days: Int): List<WorkoutPlaylist> {
        val order = shuffled(pool, seed(mood.ordinal, cycle))
        if (days < 2) return order
        val lastStart = (days - 1) * width
        val previousLast = shuffled(pool, seed(mood.ordinal, cycle - 1)).subList(lastStart, lastStart + width).toSet()
        val spare = (width until order.size).filter { it < lastStart || it >= lastStart + width }
        for (i in 0 until width) {
            if (order[i] !in previousLast) continue
            val j = spare.firstOrNull { order[it] !in previousLast } ?: break
            order[j] = order[i].also { order[i] = order[j] }
        }
        return order
    }

    private const val FILL_SALT = 7

    private fun seed(salt: Int, n: Long): Long = n * 16 + salt

    /** Fisher–Yates with SplitMix64: the same order on every phone and Kotlin version. */
    private fun <T> shuffled(list: List<T>, seed: Long): MutableList<T> {
        val out = list.toMutableList()
        var state = seed
        for (i in out.lastIndex downTo 1) {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            val j = Math.floorMod(z, (i + 1).toLong()).toInt()
            out[i] = out[j].also { out[j] = out[i] }
        }
        return out
    }
}
