package com.enjoyyourtime.globalfitness.domain

import kotlin.math.abs
import kotlin.math.roundToLong

/** Weight comparisons done on integer grams to avoid floating-point edge cases. */
object WeightMath {
    /** Half of the scale's 50 g display step: two readings within this are the same reading. */
    const val SAME_READING_TOLERANCE_G = 25L

    fun grams(kg: Double): Long = (kg * 1000.0).roundToLong()

    /** True when both weights would show the same 0.05 kg reading (|Δ| ≤ 25 g). */
    fun sameReading(a: Double, b: Double): Boolean = abs(grams(a) - grams(b)) <= SAME_READING_TOLERANCE_G
}
