package com.enjoyyourtime.globalfitness.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendsTest {
    private val day = Trends.DAY_MS
    private val t0 = 1_800_000_000_000L

    private fun p(dayIndex: Double, v: Double) = TimePoint(t0 + (dayIndex * day).toLong(), v)

    @Test
    fun movingAverageIsTimeBasedTrailingWindow() {
        val points = listOf(p(0.0, 70.0), p(1.0, 72.0), p(6.5, 74.0), p(7.0, 76.0), p(8.0, 78.0))
        val ma = Trends.movingAverage(points)
        assertEquals(points.map { it.t }, ma.map { it.t })
        assertEquals(70.0, ma[0].v, 1e-9)
        assertEquals(71.0, ma[1].v, 1e-9)
        assertEquals(72.0, ma[2].v, 1e-9) // days 0, 1, 6.5
        assertEquals(74.0, ma[3].v, 1e-9) // day 0 leaves the window at day 7: (72+74+76)/3
        assertEquals(76.0, ma[4].v, 1e-9) // day 1 leaves at day 8: (74+76+78)/3
        assertTrue(Trends.movingAverage(emptyList()).isEmpty())
    }

    @Test
    fun slopeIsKgPerWeekOverLast28Days() {
        val points = (0..14).map { p(it.toDouble(), 80.0 - 0.1 * it) }
        val slope = Trends.slopePerWeek(points, referenceTime = t0 + 14 * day)!!
        assertEquals(-0.7, slope, 1e-9)

        // Older points outside the 28-day window are ignored.
        val withOld = listOf(p(-60.0, 100.0)) + points
        assertEquals(-0.7, Trends.slopePerWeek(withOld, t0 + 14 * day)!!, 1e-9)
    }

    @Test
    fun slopeNeedsThreePointsSpanningAWeek() {
        assertNull(Trends.slopePerWeek(listOf(p(0.0, 80.0), p(10.0, 79.0)), t0 + 10 * day))
        assertNull(Trends.slopePerWeek(listOf(p(0.0, 80.0), p(2.0, 79.5), p(5.0, 79.0)), t0 + 5 * day))
        assertEquals(
            -0.7,
            Trends.slopePerWeek(listOf(p(0.0, 80.0), p(3.5, 79.65), p(7.0, 79.3)), t0 + 7 * day)!!,
            1e-9,
        )
    }

    @Test
    fun projectionTowardsTarget() {
        val now = t0
        val eta = Trends.projection(current = 80.0, target = 75.0, ratePerWeek = -0.5, now = now)
        assertEquals(Projection.Eta(now + 10 * Trends.WEEK_MS), eta)

        val gaining = Trends.projection(current = 70.0, target = 72.0, ratePerWeek = 0.25, now = now)
        assertEquals(Projection.Eta(now + 8 * Trends.WEEK_MS), gaining)

        assertEquals(Projection.NoTrend, Trends.projection(80.0, 75.0, 0.5, now))
        assertEquals(Projection.NoTrend, Trends.projection(80.0, 75.0, null, now))
        assertEquals(Projection.NoTrend, Trends.projection(80.0, 75.0, 0.0, now))
        assertEquals(Projection.NoTarget, Trends.projection(80.0, null, -0.5, now))
        assertEquals(Projection.Reached, Trends.projection(75.05, 75.0, -0.5, now))
        // Too far away (more than 5 years) is not a meaningful projection.
        assertEquals(Projection.NoTrend, Trends.projection(120.0, 70.0, -0.05, now))
    }

    @Test
    fun rangeStats() {
        val stats = Trends.rangeStats(listOf(p(2.0, 71.0), p(0.0, 73.0), p(1.0, 70.0), p(3.0, 72.0)))!!
        assertEquals(72.0, stats.current, 1e-9)
        assertEquals(70.0, stats.min, 1e-9)
        assertEquals(73.0, stats.max, 1e-9)
        assertEquals(71.5, stats.average, 1e-9)
        assertEquals(-1.0, stats.change, 1e-9)
        assertNull(Trends.rangeStats(emptyList()))
    }

    @Test
    fun weeklyTrendComparesMovingAverageAWeekApart() {
        val points = (0..10).map { p(it.toDouble(), 80.0 - 0.1 * it) }
        val ma = Trends.movingAverage(points)
        val trend = Trends.weeklyTrend(ma)!!
        assertEquals(ma.last().v - ma[3].v, trend, 1e-9)
        assertNull(Trends.weeklyTrend(Trends.movingAverage(points.take(3))))
    }
}
