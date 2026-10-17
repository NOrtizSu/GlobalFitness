package com.enjoyyourtime.globalfitness.ui.charts

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

data class AxisTicks(val min: Double, val max: Double, val step: Double, val values: List<Double>)

/** "Nice" axis tick computation (pure Kotlin). */
object AxisMath {
    private val MULTIPLIERS = doubleArrayOf(1.0, 2.0, 2.5, 5.0)

    /** Returns 4–5 (occasionally 3) evenly spaced round ticks covering [dataMin, dataMax]. */
    fun niceTicks(dataMin: Double, dataMax: Double, maxTicks: Int = 5): AxisTicks {
        var lo = minOf(dataMin, dataMax)
        var hi = maxOf(dataMin, dataMax)
        if (hi - lo < 1e-9) {
            val pad = max(abs(lo) * 0.02, 0.5)
            lo -= pad
            hi += pad
        }
        val rough = (hi - lo) / (maxTicks - 1)
        val magnitude = 10.0.pow(floor(log10(rough)) - 1)
        val candidates = buildList {
            var m = magnitude
            repeat(4) {
                for (k in MULTIPLIERS) add(k * m)
                m *= 10
            }
        }.sorted()
        for (step in candidates) {
            val niceLo = floor(lo / step + 1e-9) * step
            val niceHi = ceil(hi / step - 1e-9) * step
            val count = ((niceHi - niceLo) / step).roundToInt() + 1
            if (count <= maxTicks) {
                val values = (0 until count).map { niceLo + it * step }
                return AxisTicks(niceLo, niceHi, step, values)
            }
        }
        return AxisTicks(lo, hi, hi - lo, listOf(lo, hi))
    }

    /** Number of decimals needed to print ticks with [step] without noise. */
    fun decimalsFor(step: Double): Int {
        for (d in 0..3) {
            val scaled = step * 10.0.pow(d)
            if (abs(scaled - scaled.roundToInt()) < 1e-6) return d
        }
        return 3
    }

    /**
     * Indices of the axis labels to draw so that none touches the previous one: each label starts at
     * [starts] and is [widths] wide (sorted left to right), with at least [gap] between two. A label
     * that would overlap is skipped; the last one is kept over the inner ones before it.
     */
    fun nonOverlapping(starts: List<Float>, widths: List<Float>, gap: Float): List<Int> {
        fun overlaps(a: Int, b: Int) = starts[b] < starts[a] + widths[a] + gap
        val shown = mutableListOf<Int>()
        for (i in starts.indices) {
            if (i == starts.lastIndex) {
                while (shown.size > 1 && overlaps(shown.last(), i)) shown.removeAt(shown.lastIndex)
            }
            if (shown.isEmpty() || !overlaps(shown.last(), i)) shown += i
        }
        return shown
    }

    /** [count] evenly spaced timestamps from [start] to [end] (inclusive). */
    fun timeTicks(start: Long, end: Long, count: Int = 4): List<Long> {
        if (end <= start) return listOf(start)
        return (0 until count).map { start + (end - start) * it / (count - 1) }
    }
}
