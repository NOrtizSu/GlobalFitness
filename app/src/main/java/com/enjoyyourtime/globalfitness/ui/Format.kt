package com.enjoyyourtime.globalfitness.ui

import android.content.Context
import android.content.res.Resources
import android.icu.text.DateTimePatternGenerator
import android.icu.text.ListFormatter
import android.icu.text.SimpleDateFormat
import android.view.View
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.core.text.layoutDirection
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.domain.Metric
import com.enjoyyourtime.globalfitness.domain.MetricUnit
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Formatting for the UI in the current locale: decimal separator and digits, month and weekday
 * names, date order, 12/24-hour clock. Unit words ("kg", "years") come from the string resources.
 */
object Fmt {
    private const val MINUS = "−"

    @Volatile
    private var resources: Resources? = null

    /** The system 24-hour setting (refreshed on every activity start); null: the locale's default. */
    @Volatile
    var hour24: Boolean? = null

    private val patterns = ConcurrentHashMap<String, String>()

    /** Called by the application (resources) and each activity start (24-hour setting). */
    fun init(context: Context) {
        resources = context.applicationContext.resources
        hour24 = android.text.format.DateFormat.is24HourFormat(context)
    }

    val locale: Locale get() = Locale.getDefault()

    /**
     * A string resource outside composition (chart drawing, formatting helpers). The application
     * resources follow the app language; a language change recreates the activities.
     */
    fun string(@StringRes id: Int, vararg args: Any): String = checkNotNull(resources).getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        checkNotNull(resources).getQuantityString(id, count, *args)

    // ---- numbers ---------------------------------------------------------------------------

    private fun format(v: Double, decimals: Int, grouping: Boolean = false): String =
        NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = grouping
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
            roundingMode = RoundingMode.HALF_UP
        }.format(v)

    private fun roundsToZero(v: Double, decimals: Int): Boolean =
        !v.isFinite() || BigDecimal(abs(v)).setScale(decimals, RoundingMode.HALF_UP).signum() == 0

    /**
     * A sign before a number. In right-to-left languages a left-to-right mark keeps it on the left of
     * the digits (otherwise Arabic shows "2.85−"), as ICU does for its own Arabic minus sign.
     */
    private fun sign(s: String): String =
        if (locale.layoutDirection == View.LAYOUT_DIRECTION_RTL) "‎$s" else s

    /** [v] with [decimals] decimals; a typographic minus, never "-0". */
    fun num(v: Double, decimals: Int): String {
        val magnitude = format(abs(v), decimals)
        return if (v < 0 && !roundsToZero(v, decimals)) sign(MINUS) + magnitude else magnitude
    }

    /** "+0,5", "−1,2" or "±0,0". */
    fun signed(v: Double, decimals: Int): String {
        val magnitude = format(abs(v), decimals)
        return when {
            roundsToZero(v, decimals) -> sign("±") + magnitude
            v > 0 -> sign("+") + magnitude
            else -> sign(MINUS) + magnitude
        }
    }

    /** Whole number with the locale's thousands separator ("10,234"). */
    fun count(v: Long): String = NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = true }.format(v)

    fun kg(v: Double, decimals: Int = 2): String = string(R.string.value_kg, num(v, decimals))

    fun percent(v: Double, decimals: Int = 0): String = string(R.string.value_percent, num(v, decimals))

    /** An already formatted number with the unit of [metric] ("72,50 kg", "+0,5 %", "32 años"). */
    fun withUnit(metric: Metric, value: String, amount: Double): String = when (metric.unit) {
        MetricUnit.KG -> string(R.string.value_kg, value)
        MetricUnit.PERCENT -> string(R.string.value_percent, value)
        MetricUnit.KCAL -> string(R.string.value_kcal, value)
        MetricUnit.YEARS -> plural(R.plurals.value_years, abs(amount).roundToInt(), value)
        MetricUnit.NONE -> value
    }

    fun metric(metric: Metric, v: Double): String = withUnit(metric, num(v, metric.decimals), v)

    /** The unit alone, for axis titles ("kg", "%"), or null for unitless metrics. */
    fun unit(metric: Metric): String? = when (metric.unit) {
        MetricUnit.KG -> string(R.string.unit_kg)
        MetricUnit.PERCENT -> string(R.string.unit_percent)
        MetricUnit.KCAL -> string(R.string.unit_kcal)
        MetricUnit.YEARS -> string(R.string.unit_years)
        MetricUnit.NONE -> null
    }

    /** "a, b and c" in the current language. */
    fun list(items: List<String>): String = ListFormatter.getInstance(locale).format(items)

    /** Parses "72,5", "72.5" or the same typed with the locale's own digits ("٧٢٫٥"). */
    fun parseDecimal(s: String): Double? {
        val ascii = buildString {
            for (ch in s.trim()) {
                when {
                    ch in '0'..'9' -> append(ch)
                    Character.isDigit(ch) -> append('0' + Character.getNumericValue(ch))
                    ch == ',' || ch == '٫' || ch == '.' -> append('.')
                    ch == '-' || ch == MINUS[0] -> append('-')
                    Character.getType(ch) == Character.FORMAT.toInt() -> Unit // bidi marks such as U+200E
                    else -> append(ch)
                }
            }
        }
        return ascii.toDoubleOrNull()
    }

    // ---- dates -----------------------------------------------------------------------------

    private fun timeSkeleton(): String = when (hour24) {
        true -> "Hm"
        false -> "hm"
        null -> "jm"
    }

    /** Formats [ms] with the locale's best pattern for an ICU [skeleton] ("dMMM", "yMMMd"...). */
    private fun skeleton(ms: Long, skeleton: String): String {
        val l = locale
        val pattern = patterns.getOrPut("${l.toLanguageTag()}|$skeleton") {
            DateTimePatternGenerator.getInstance(l).getBestPattern(skeleton)
        }
        return SimpleDateFormat(pattern, l).format(Date(ms))
    }

    private fun noon(d: LocalDate): Long = d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun capitalized(s: String): String = s.replaceFirstChar { it.titlecase(locale) }

    /** "15 oct" / "Oct 15". */
    fun dayMonth(ms: Long): String = skeleton(ms, "dMMM")

    fun dayMonth(d: LocalDate): String = dayMonth(noon(d))

    /** "15 oct 2025". */
    fun dayMonthYear(ms: Long): String = skeleton(ms, "yMMMd")

    fun date(d: LocalDate): String = dayMonthYear(noon(d))

    /** "oct 2025" (chart axes over long spans). */
    fun monthShortYear(ms: Long): String = skeleton(ms, "yMMM")

    /** "Octubre de 2025" (month headers). */
    fun monthYear(ms: Long): String = capitalized(skeleton(ms, "yMMMM"))

    /** "Miércoles, 15 de octubre". */
    fun longDate(d: LocalDate): String = capitalized(skeleton(noon(d), "MMMMEEEEd"))

    fun time(ms: Long): String = skeleton(ms, timeSkeleton())

    /**
     * A time on a chart axis. On a 12-hour clock a time on the hour shows the hour alone ("6 AM",
     * "오전 6시"), which "오전 6:00" is too wide for; otherwise the same as [time] ("06:00", "5:45 AM").
     */
    fun axisTime(ms: Long): String {
        val onTheHour = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).let { it.minute == 0 && it.second == 0 }
        return if (hour24 == false && onTheHour) skeleton(ms, "h") else time(ms)
    }

    /** A time of day ("07:30" / "7:30 AM"). */
    fun time(t: LocalTime): String = time(t.atDate(LocalDate.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())

    /** Time with seconds (diagnostics). */
    fun timeWithSeconds(ms: Long): String = skeleton(ms, if (hour24 == false) "hms" else if (hour24 == true) "Hms" else "jms")

    /** "15 oct 2025, 08:30". */
    fun dateTime(ms: Long): String = skeleton(ms, "yMMMd" + timeSkeleton())

    /** "mié 15 oct". */
    fun weekdayDayMonth(ms: Long): String = skeleton(ms, "MMMEd")

    fun weekdayDayMonth(d: LocalDate): String = weekdayDayMonth(noon(d))

    /** "Hoy, 08:30", "Ayer, 21:10", "mié 15 oct, 08:30" or with the year when it is not this year's. */
    fun relative(ms: Long, now: Long = System.currentTimeMillis()): String {
        val zone = ZoneId.systemDefault()
        val d = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (d) {
            today -> string(R.string.time_today_at, time(ms))
            today.minusDays(1) -> string(R.string.time_yesterday_at, time(ms))
            else -> skeleton(ms, (if (d.year == today.year) "MMMEd" else "yMMMEd") + timeSkeleton())
        }
    }

    /** "Hoy", "Ayer" or "mié 15 oct". */
    fun relativeDay(d: LocalDate, today: LocalDate): String = when (d) {
        today -> string(R.string.time_today)
        today.minusDays(1) -> string(R.string.time_yesterday)
        else -> weekdayDayMonth(d)
    }

    /** Row label inside a month group: "mié 15, 08:30". */
    fun dayTime(ms: Long): String = skeleton(ms, "Ed" + timeSkeleton())
}
