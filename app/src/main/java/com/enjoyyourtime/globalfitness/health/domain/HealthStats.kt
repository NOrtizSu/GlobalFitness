package com.enjoyyourtime.globalfitness.health.domain

import com.enjoyyourtime.globalfitness.health.DailyActivity
import com.enjoyyourtime.globalfitness.health.HrSample
import com.enjoyyourtime.globalfitness.health.TimedValue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

data class DailyHrRange(val date: LocalDate, val min: Int, val max: Int, val avg: Int)

/** Everything shown for one calendar day. Null fields mean "no data". */
data class DaySummary(
    val date: LocalDate,
    val steps: Long?,
    val distanceM: Double?,
    val activeKcal: Double?,
    val totalKcal: Double?,
    val restingHr: Int?,
    val heartRate: HrStats?,
    /** Night ending on this day. */
    val sleep: NightSleep?,
    val spo2Avg: Double?,
    val spo2Min: Double?,
)

/** Mean over the days of a window that have data. */
data class WindowAverage(val average: Double, val daysWithData: Int, val days: Int)

data class Trend(val current: WindowAverage?, val previous: WindowAverage?) {
    /** Current window average minus the preceding window's; null if either is missing. */
    val change: Double? get() = if (current != null && previous != null) current.average - previous.average else null
}

object HealthStats {

    fun localDate(epochMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    /** [start, end) of a local day in epoch millis (handles DST-length days). */
    fun dayBounds(date: LocalDate, zone: ZoneId): Pair<Long, Long> =
        date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Min / max / average heart rate per local day, sorted by date. */
    fun dailyHrRanges(samples: List<HrSample>, zone: ZoneId): List<DailyHrRange> =
        samples.groupBy { localDate(it.time, zone) }
            .map { (date, list) ->
                DailyHrRange(date, list.minOf { it.bpm }, list.maxOf { it.bpm }, list.map { it.bpm }.average().roundToInt())
            }
            .sortedBy { it.date }

    /** Mean of each day's values (e.g. several resting-HR records from different sources). */
    fun dailyMeans(values: List<TimedValue>, zone: ZoneId): Map<LocalDate, Double> =
        values.groupBy { localDate(it.time, zone) }.mapValues { (_, list) -> list.map { it.value }.average() }

    fun daySummary(
        date: LocalDate,
        zone: ZoneId,
        activity: DailyActivity?,
        heartRate: List<HrSample>,
        restingHr: List<TimedValue>,
        nights: List<NightSleep>,
        spo2: List<TimedValue>,
    ): DaySummary {
        val (start, end) = dayBounds(date, zone)
        val restingToday = restingHr.filter { it.time >= start && it.time < end }
        val spo2Today = spo2.filter { it.time >= start && it.time < end }
        return DaySummary(
            date = date,
            steps = activity?.steps,
            distanceM = activity?.distanceM,
            activeKcal = activity?.activeKcal,
            totalKcal = activity?.totalKcal,
            restingHr = restingToday.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.roundToInt(),
            heartRate = HeartRateZones.stats(heartRate, start, end - 1),
            sleep = nights.firstOrNull { it.date == date },
            spo2Avg = spo2Today.takeIf { it.isNotEmpty() }?.map { it.value }?.average(),
            spo2Min = spo2Today.minOfOrNull { it.value },
        )
    }

    /** Average of the values dated in (end − days, end]; null when none of those days has data. */
    fun windowAverage(values: Map<LocalDate, Double>, end: LocalDate, days: Int): WindowAverage? {
        val inWindow = (0 until days).mapNotNull { values[end.minusDays(it.toLong())] }
        if (inWindow.isEmpty()) return null
        return WindowAverage(inWindow.average(), inWindow.size, days)
    }

    /** The [days]-day window ending on [end] versus the window right before it. */
    fun trend(values: Map<LocalDate, Double>, end: LocalDate, days: Int): Trend =
        Trend(windowAverage(values, end, days), windowAverage(values, end.minusDays(days.toLong()), days))

    /** One entry per day for the [days] days ending on [end] (oldest first), null where missing. */
    fun <T> filledSeries(values: Map<LocalDate, T>, end: LocalDate, days: Int): List<Pair<LocalDate, T?>> =
        (days - 1 downTo 0).map { offset ->
            val date = end.minusDays(offset.toLong())
            date to values[date]
        }
}
