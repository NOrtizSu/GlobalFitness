package com.enjoyyourtime.globalfitness.ui.health

import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.ui.Fmt
import java.text.NumberFormat
import java.time.LocalDate
import kotlin.math.roundToLong

/** Formatting for Health Connect values in the current language. */
object HealthFormat {
    /** "10,234" (grouped thousands). */
    fun count(v: Long): String = Fmt.count(v)

    fun steps(v: Long): String = Fmt.plural(R.plurals.value_steps, v.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), count(v))

    fun km(meters: Double): String = Fmt.string(R.string.value_km, Fmt.num(meters / 1000.0, if (meters >= 10_000) 1 else 2))

    fun kcal(v: Double): String = Fmt.string(R.string.value_kcal, count(v.roundToLong()))

    fun bpm(v: Int): String = Fmt.string(R.string.value_bpm, count(v.toLong()))

    fun percent(v: Double, decimals: Int = 0): String = Fmt.percent(v, decimals)

    /** "7 h 05 min", "45 min". */
    fun minutes(total: Int): String {
        val h = total / 60
        val m = total % 60
        return if (h == 0) {
            Fmt.string(R.string.value_minutes, count(m.toLong()))
        } else {
            Fmt.string(R.string.value_hours_minutes, count(h.toLong()), twoDigits(m))
        }
    }

    /** "7:05" (hours and minutes, for the sleep ring). */
    fun hoursMinutes(total: Int): String = "${count((total / 60).toLong())}:${twoDigits(total % 60)}"

    /** "7,2 h" for chart axes. */
    fun hours(totalHours: Double, decimals: Int = 1): String = Fmt.string(R.string.value_hours, Fmt.num(totalHours, decimals))

    private fun twoDigits(v: Int): String = NumberFormat.getIntegerInstance(Fmt.locale).apply {
        minimumIntegerDigits = 2
        isGroupingUsed = false
    }.format(v)

    fun dayMonth(d: LocalDate): String = Fmt.dayMonth(d)

    /** "mié 15 oct". */
    fun weekdayDayMonth(d: LocalDate): String = Fmt.weekdayDayMonth(d)

    /** "Hoy", "Ayer" or "mié 15 oct". */
    fun relativeDay(d: LocalDate, today: LocalDate): String = Fmt.relativeDay(d, today)
}
