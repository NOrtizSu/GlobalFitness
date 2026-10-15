package com.enjoyyourtime.globalfitness.ui.health

import com.enjoyyourtime.globalfitness.ui.Fmt
import java.time.LocalDate
import kotlin.math.roundToLong

/** Spanish formatting for Health Connect values. */
object HealthFormat {
    private val MONTHS_SHORT = arrayOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

    /** "10.234" (grouped thousands). */
    fun count(v: Long): String = String.format(Fmt.LOCALE, "%,d", v)

    fun steps(v: Long): String = "${count(v)} pasos"

    fun km(meters: Double): String = "${Fmt.num(meters / 1000.0, if (meters >= 10_000) 1 else 2)} km"

    fun kcal(v: Double): String = "${count(v.roundToLong())} kcal"

    fun bpm(v: Int): String = "$v lpm"

    fun percent(v: Double, decimals: Int = 0): String = "${Fmt.num(v, decimals)} %"

    /** "7 h 05 min", "45 min". */
    fun minutes(total: Int): String {
        val h = total / 60
        val m = total % 60
        return if (h == 0) "$m min" else String.format(Fmt.LOCALE, "%d h %02d min", h, m)
    }

    /** "7,2 h" for chart axes. */
    fun hours(totalMinutes: Double, decimals: Int = 1): String = "${Fmt.num(totalMinutes / 60.0, decimals)} h"

    fun dayMonth(d: LocalDate): String = "${d.dayOfMonth} ${MONTHS_SHORT[d.monthValue - 1]}"

    /** "mié 15 oct". */
    fun weekdayDayMonth(d: LocalDate): String = "${Fmt.weekday(d.dayOfWeek)} ${dayMonth(d)}"

    /** "Hoy", "Ayer" or "mié 15 oct". */
    fun relativeDay(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "Hoy"
        today.minusDays(1) -> "Ayer"
        else -> weekdayDayMonth(d)
    }
}
