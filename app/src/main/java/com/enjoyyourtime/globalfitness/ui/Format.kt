package com.enjoyyourtime.globalfitness.ui

import com.enjoyyourtime.globalfitness.domain.Metric
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs

/** Spanish (es-ES) formatting helpers: comma decimals, Spanish month names. */
object Fmt {
    val LOCALE: Locale = Locale.forLanguageTag("es-ES")
    private const val MINUS = "−"

    private val MONTHS_SHORT = arrayOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    private val MONTHS_LONG = arrayOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )

    fun num(v: Double, decimals: Int): String {
        val s = String.format(LOCALE, "%.${decimals}f", v)
        // Avoid "-0,0".
        return if (s.startsWith("-") && s.drop(1).all { it == '0' || it == ',' }) s.drop(1) else s.replace("-", MINUS)
    }

    fun signed(v: Double, decimals: Int): String {
        val magnitude = String.format(LOCALE, "%.${decimals}f", abs(v))
        val zero = magnitude.all { it == '0' || it == ',' }
        return when {
            zero -> "±$magnitude"
            v > 0 -> "+$magnitude"
            else -> "$MINUS$magnitude"
        }
    }

    fun withUnit(value: String, unit: String): String = if (unit.isEmpty()) value else "$value $unit"

    fun metric(metric: Metric, v: Double): String = withUnit(num(v, metric.decimals), metric.unit)

    fun kg(v: Double, decimals: Int = 2): String = "${num(v, decimals)} kg"

    private fun zoned(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

    fun dayMonth(ms: Long): String = zoned(ms).let { "${it.dayOfMonth} ${MONTHS_SHORT[it.monthValue - 1]}" }

    fun dayMonthYear(ms: Long): String = zoned(ms).let { "${it.dayOfMonth} ${MONTHS_SHORT[it.monthValue - 1]} ${it.year}" }

    fun date(d: LocalDate): String = "${d.dayOfMonth} ${MONTHS_SHORT[d.monthValue - 1]} ${d.year}"

    fun monthShortYear(ms: Long): String = zoned(ms).let { "${MONTHS_SHORT[it.monthValue - 1]} ${it.year}" }

    fun time(ms: Long): String = zoned(ms).let { String.format(LOCALE, "%02d:%02d", it.hour, it.minute) }

    fun dateTime(ms: Long): String = "${dayMonthYear(ms)}, ${time(ms)}"

    fun monthYear(ms: Long): String = zoned(ms).let {
        MONTHS_LONG[it.monthValue - 1].replaceFirstChar { c -> c.uppercase(LOCALE) } + " ${it.year}"
    }

    fun weekday(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "lun"
        DayOfWeek.TUESDAY -> "mar"
        DayOfWeek.WEDNESDAY -> "mié"
        DayOfWeek.THURSDAY -> "jue"
        DayOfWeek.FRIDAY -> "vie"
        DayOfWeek.SATURDAY -> "sáb"
        DayOfWeek.SUNDAY -> "dom"
    }

    /** "Hoy, 08:30", "Ayer, 21:10" or "mié 15 oct, 08:30". */
    fun relative(ms: Long, now: Long = System.currentTimeMillis()): String {
        val d = zoned(ms).toLocalDate()
        val today = zoned(now).toLocalDate()
        return when (d) {
            today -> "Hoy, ${time(ms)}"
            today.minusDays(1) -> "Ayer, ${time(ms)}"
            else -> {
                val base = "${weekday(d.dayOfWeek)} ${d.dayOfMonth} ${MONTHS_SHORT[d.monthValue - 1]}"
                if (d.year == today.year) "$base, ${time(ms)}" else "$base ${d.year}, ${time(ms)}"
            }
        }
    }

    /** Row label inside a month group: "mié 15, 08:30". */
    fun dayTime(ms: Long): String = zoned(ms).let { "${weekday(it.dayOfWeek)} ${it.dayOfMonth}, ${time(ms)}" }

    /** Parses "72,5" or "72.5". */
    fun parseDecimal(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
}
