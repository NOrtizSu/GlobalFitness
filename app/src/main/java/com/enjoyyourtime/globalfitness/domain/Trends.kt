package com.enjoyyourtime.globalfitness.domain

import kotlin.math.abs
import kotlin.math.sign

data class TimePoint(val t: Long, val v: Double)

data class RangeStats(
    val current: Double,
    val min: Double,
    val max: Double,
    val average: Double,
    val change: Double,
)

sealed interface Projection {
    data object NoTarget : Projection
    data object Reached : Projection
    data object NoTrend : Projection
    data class Eta(val epochMillis: Long) : Projection
}

object Trends {
    const val DAY_MS = 86_400_000L
    const val WEEK_MS = 7 * DAY_MS

    /**
     * Time-based trailing moving average: for each point, the mean of all points within
     * (t - windowDays, t]. Input must be sorted by time; output keeps the same timestamps.
     */
    fun movingAverage(points: List<TimePoint>, windowDays: Int = 7): List<TimePoint> {
        if (points.isEmpty()) return emptyList()
        val window = windowDays * DAY_MS
        val result = ArrayList<TimePoint>(points.size)
        var start = 0
        var sum = 0.0
        for (i in points.indices) {
            sum += points[i].v
            while (points[start].t <= points[i].t - window) {
                sum -= points[start].v
                start++
            }
            result.add(TimePoint(points[i].t, sum / (i - start + 1)))
        }
        return result
    }

    /**
     * Least-squares slope in units per week over the [windowDays] days up to [referenceTime].
     * Needs at least 3 points spanning at least 7 days, otherwise null.
     */
    fun slopePerWeek(points: List<TimePoint>, referenceTime: Long, windowDays: Int = 28): Double? {
        val from = referenceTime - windowDays * DAY_MS
        val window = points.filter { it.t in from..referenceTime }
        if (window.size < 3) return null
        val span = window.maxOf { it.t } - window.minOf { it.t }
        if (span < WEEK_MS) return null
        val t0 = window.first().t
        val xs = window.map { (it.t - t0).toDouble() / DAY_MS }
        val ys = window.map { it.v }
        val meanX = xs.average()
        val meanY = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            num += dx * (ys[i] - meanY)
            den += dx * dx
        }
        if (den == 0.0) return null
        return num / den * 7.0
    }

    /** Estimated date to reach [target] from [current] at [ratePerWeek]. */
    fun projection(
        current: Double,
        target: Double?,
        ratePerWeek: Double?,
        now: Long,
        reachedToleranceKg: Double = 0.1,
        maxWeeks: Double = 52.0 * 5,
    ): Projection {
        if (target == null) return Projection.NoTarget
        val diff = target - current
        if (abs(diff) <= reachedToleranceKg) return Projection.Reached
        if (ratePerWeek == null || abs(ratePerWeek) < 0.01) return Projection.NoTrend
        if (sign(diff) != sign(ratePerWeek)) return Projection.NoTrend
        val weeks = diff / ratePerWeek
        if (weeks > maxWeeks) return Projection.NoTrend
        return Projection.Eta(now + (weeks * WEEK_MS).toLong())
    }

    fun rangeStats(points: List<TimePoint>): RangeStats? {
        if (points.isEmpty()) return null
        val sorted = points.sortedBy { it.t }
        return RangeStats(
            current = sorted.last().v,
            min = sorted.minOf { it.v },
            max = sorted.maxOf { it.v },
            average = sorted.map { it.v }.average(),
            change = sorted.last().v - sorted.first().v,
        )
    }

    /**
     * Change of the 7-day moving average over the last 7 days (latest MA minus the MA value
     * at or before latest - 7 days). Null when there is no history that old.
     */
    fun weeklyTrend(movingAverage: List<TimePoint>): Double? {
        val last = movingAverage.lastOrNull() ?: return null
        val before = movingAverage.lastOrNull { it.t <= last.t - WEEK_MS } ?: return null
        return last.v - before.v
    }
}
