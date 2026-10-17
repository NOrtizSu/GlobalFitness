package com.enjoyyourtime.globalfitness.cloud

import kotlin.math.roundToLong

/**
 * Stable natural ids for measurements: `m_<epochMillis>_<weightGrams>`. They depend only on the
 * reading itself, so the same measurement gets the same cloud document after a reinstall or on a
 * new phone, and an impedance upgrade or an edit upserts that same document.
 */
object MeasurementIds {
    private const val PREFIX = "m_"
    private val PATTERN = Regex("""m_(-?\d+)_(\d+)""")

    fun idOf(timestamp: Long, weightGrams: Long): String = "$PREFIX${timestamp}_$weightGrams"

    fun idOf(timestamp: Long, weightKg: Double): String = idOf(timestamp, grams(weightKg))

    fun grams(weightKg: Double): Long = (weightKg * 1000.0).roundToLong()

    /** (timestamp, grams) of a well-formed id, or null. */
    fun parse(id: String): Pair<Long, Long>? {
        val match = PATTERN.matchEntire(id) ?: return null
        val timestamp = match.groupValues[1].toLongOrNull() ?: return null
        val grams = match.groupValues[2].toLongOrNull() ?: return null
        return timestamp to grams
    }
}
