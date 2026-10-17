package com.enjoyyourtime.globalfitness.health.domain

import com.enjoyyourtime.globalfitness.health.DailyActivity
import com.enjoyyourtime.globalfitness.health.HrSample
import com.enjoyyourtime.globalfitness.health.SleepSession
import com.enjoyyourtime.globalfitness.health.TimedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class HealthStatsTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val day = LocalDate.of(2026, 10, 15)

    private fun at(d: LocalDate, h: Int, m: Int = 0) = LocalDateTime.of(d, java.time.LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun dayBoundsHandleDaylightSavingDays() {
        val (start, end) = HealthStats.dayBounds(LocalDate.of(2026, 10, 25), zone) // clocks go back: 25 h
        assertEquals(25 * 3_600_000L, end - start)
        val (s2, e2) = HealthStats.dayBounds(LocalDate.of(2026, 3, 29), zone) // clocks go forward: 23 h
        assertEquals(23 * 3_600_000L, e2 - s2)
    }

    @Test
    fun dailyHeartRateRangesUseLocalDays() {
        val samples = listOf(
            HrSample(at(day, 0, 10), 55),
            HrSample(at(day, 12), 120),
            HrSample(at(day, 23, 59), 65),
            HrSample(at(day.plusDays(1), 0, 5), 50),
        )
        val ranges = HealthStats.dailyHrRanges(samples, zone)
        assertEquals(listOf(DailyHrRange(day, 55, 120, 80), DailyHrRange(day.plusDays(1), 50, 50, 50)), ranges)
    }

    @Test
    fun daySummaryCombinesAllSources() {
        val activity = DailyActivity(day, 8_000, 6_100.0, 420.0, 2_300.0)
        val hr = listOf(HrSample(at(day.minusDays(1), 23), 200), HrSample(at(day, 8), 70), HrSample(at(day, 18), 110))
        val resting = listOf(TimedValue(at(day, 7), 58.0, "x"), TimedValue(at(day, 9), 61.0, "y"), TimedValue(at(day.minusDays(1), 7), 70.0, "x"))
        val spo2 = listOf(TimedValue(at(day, 3), 95.0, "x"), TimedValue(at(day, 4), 98.0, "x"))
        val sleep = SleepAnalysis.nights(
            listOf(SleepSession("s", at(day.minusDays(1), 23), at(day, 7), null, "x", emptyList())),
            zone,
        )
        val s = HealthStats.daySummary(day, zone, activity, hr, resting, sleep, spo2)
        assertEquals(8_000L, s.steps)
        assertEquals(6_100.0, s.distanceM!!, 1e-9)
        assertEquals(420.0, s.activeKcal!!, 1e-9)
        assertEquals(2_300.0, s.totalKcal!!, 1e-9)
        assertEquals(60, s.restingHr) // mean of 58 and 61, rounded
        assertEquals(90, s.heartRate!!.avg)
        assertEquals(70, s.heartRate!!.min)
        assertEquals(110, s.heartRate!!.max)
        assertEquals(480, s.sleep!!.asleepMinutes)
        assertEquals(96.5, s.spo2Avg!!, 1e-9)
        assertEquals(95.0, s.spo2Min!!, 1e-9)
    }

    @Test
    fun emptyDaySummaryHasNoValues() {
        val s = HealthStats.daySummary(day, zone, null, emptyList(), emptyList(), emptyList(), emptyList())
        assertNull(s.steps)
        assertNull(s.restingHr)
        assertNull(s.heartRate)
        assertNull(s.sleep)
        assertNull(s.spo2Avg)
    }

    @Test
    fun windowAverageCountsOnlyDaysWithData() {
        val values = mapOf(day to 10.0, day.minusDays(1) to 20.0, day.minusDays(6) to 30.0, day.minusDays(7) to 1000.0)
        val avg = HealthStats.windowAverage(values, day, 7)!!
        assertEquals(20.0, avg.average, 1e-9)
        assertEquals(3, avg.daysWithData)
        assertEquals(7, avg.days)
        assertNull(HealthStats.windowAverage(values, day.minusDays(30), 7))
    }

    @Test
    fun trendComparesWithThePreviousWindow() {
        val values = (0 until 14).associate { day.minusDays(it.toLong()) to if (it < 7) 60.0 else 64.0 }
        val trend = HealthStats.trend(values, day, 7)
        assertEquals(60.0, trend.current!!.average, 1e-9)
        assertEquals(64.0, trend.previous!!.average, 1e-9)
        assertEquals(-4.0, trend.change!!, 1e-9)
        assertNull(HealthStats.trend(mapOf(day to 1.0), day, 7).change)
    }

    @Test
    fun filledSeriesHasOneEntryPerDay() {
        val series = HealthStats.filledSeries(mapOf(day to 5L, day.minusDays(2) to 3L), day, 4)
        assertEquals(
            listOf(day.minusDays(3) to null, day.minusDays(2) to 3L, day.minusDays(1) to null, day to 5L),
            series,
        )
    }

    @Test
    fun dailyMeansAverageSeveralSources() {
        val means = HealthStats.dailyMeans(listOf(TimedValue(at(day, 6), 60.0, "a"), TimedValue(at(day, 7), 62.0, "b")), zone)
        assertEquals(mapOf(day to 61.0), means)
    }
}
