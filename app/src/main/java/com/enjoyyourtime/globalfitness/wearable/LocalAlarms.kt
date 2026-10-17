package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmRepeat
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandAlarm
import com.enjoyyourtime.globalfitness.scale.DriverStore

/**
 * Alarms of devices that can be written but not read back (classic Huami slots, Bangle.js): the
 * app's copy, persisted per device in the [DriverStore]. Ids are 1..[maxAlarms] (slot = id - 1).
 */
class LocalAlarms(private val store: DriverStore, val maxAlarms: Int) {

    fun list(): List<BandAlarm> = decode(store.getString(KEY)).filter { it.id in 1..maxAlarms }
        .sortedWith(compareBy({ it.spec.hour }, { it.spec.minute }, { it.id }))

    /** Stores [spec] under [id], or the first free id when [id] is null; null when every slot is used. */
    fun put(id: Int?, spec: AlarmSpec): BandAlarm? {
        val current = list()
        val target = id?.takeIf { it in 1..maxAlarms }
            ?: (1..maxAlarms).firstOrNull { candidate -> current.none { it.id == candidate } }
            ?: return null
        val alarm = BandAlarm(target, spec)
        store.putString(KEY, encode(current.filterNot { it.id == target } + alarm))
        return alarm
    }

    fun remove(id: Int): BandAlarm? {
        val current = list()
        val removed = current.firstOrNull { it.id == id } ?: return null
        store.putString(KEY, encode(current - removed))
        return removed
    }

    fun get(id: Int): BandAlarm? = list().firstOrNull { it.id == id }

    companion object {
        private const val KEY = "local_alarms"

        /** Day mask Monday = 1 ... Sunday = 64 (Gadgetbridge Alarm), 0 = once. */
        fun dayMask(repeat: AlarmRepeat): Int = when (repeat) {
            AlarmRepeat.Once -> 0
            AlarmRepeat.Daily -> AlarmRepeat.ALL_DAYS
            is AlarmRepeat.Weekly -> repeat.days and AlarmRepeat.ALL_DAYS
            is AlarmRepeat.Other -> (repeat.flags ?: 0) and AlarmRepeat.ALL_DAYS
        }

        fun encode(alarms: List<BandAlarm>): String = alarms.joinToString(";") { a ->
            val s = a.spec
            listOf(a.id, s.hour, s.minute, if (s.enabled) 1 else 0, if (s.smart) 1 else 0, dayMask(s.repeat)).joinToString(",")
        }

        fun decode(text: String?): List<BandAlarm> {
            if (text.isNullOrBlank()) return emptyList()
            return text.split(';').mapNotNull { entry ->
                val f = entry.split(',').map { it.trim().toIntOrNull() }
                if (f.size != 6 || f.any { it == null }) return@mapNotNull null
                val (id, hour, minute) = Triple(f[0]!!, f[1]!!, f[2]!!)
                if (hour !in 0..23 || minute !in 0..59) return@mapNotNull null
                BandAlarm(id, AlarmSpec(hour, minute, enabled = f[3] == 1, smart = f[4] == 1, repeat = AlarmRepeat.fromDays(f[5]!!)))
            }
        }
    }
}
