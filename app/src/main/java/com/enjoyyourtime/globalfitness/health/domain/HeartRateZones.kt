package com.enjoyyourtime.globalfitness.health.domain

import com.enjoyyourtime.globalfitness.health.HrSample
import kotlin.math.roundToInt

/** Zone [index] covers [minBpm, maxBpm) ; the top zone has no upper bound (maxBpm = null). */
data class HrZone(val index: Int, val label: String, val minBpm: Int, val maxBpm: Int?)

data class HrStats(val avg: Int, val min: Int, val max: Int, val count: Int)

/**
 * Heart-rate zones as a percentage of the estimated maximum (220 − age):
 * Z1 50–60 % "Muy suave", Z2 60–70 % "Suave", Z3 70–80 % "Moderada", Z4 80–90 % "Intensa",
 * Z5 ≥ 90 % "Máxima". Below 50 % is zone 0 ("Reposo").
 */
object HeartRateZones {
    private val PERCENTS = intArrayOf(50, 60, 70, 80, 90)
    private val LABELS = arrayOf("Muy suave", "Suave", "Moderada", "Intensa", "Máxima")
    const val REST_LABEL = "Reposo"

    /** Samples further apart than this are not joined when measuring time in zones. */
    const val MAX_SAMPLE_GAP_MS = 5 * 60_000L

    fun maxHeartRate(age: Int): Int = 220 - age.coerceIn(0, 120)

    fun zones(age: Int): List<HrZone> {
        val max = maxHeartRate(age)
        val bounds = PERCENTS.map { (max * it / 100.0).roundToInt() }
        return bounds.indices.map { i ->
            HrZone(index = i + 1, label = LABELS[i], minBpm = bounds[i], maxBpm = bounds.getOrNull(i + 1))
        }
    }

    /** 0 (below zone 1) to 5. */
    fun zoneOf(bpm: Int, age: Int): Int = zoneOf(bpm, zones(age))

    private fun zoneOf(bpm: Int, zones: List<HrZone>): Int = zones.lastOrNull { bpm >= it.minBpm }?.index ?: 0

    /**
     * Milliseconds spent in each zone (index 0..5) between [start] and [end]. Each sample counts
     * until the next one (or [end]), but never longer than [MAX_SAMPLE_GAP_MS].
     */
    fun timeInZones(samples: List<HrSample>, start: Long, end: Long, age: Int): LongArray {
        val result = LongArray(6)
        val zones = zones(age)
        val inRange = samples.filter { it.time in start..end }.sortedBy { it.time }
        inRange.forEachIndexed { i, s ->
            val next = inRange.getOrNull(i + 1)?.time ?: end
            val duration = (next - s.time).coerceIn(0L, MAX_SAMPLE_GAP_MS)
            result[zoneOf(s.bpm, zones)] += duration
        }
        return result
    }

    /** Average / min / max of the samples in [start, end]; null when there are none. */
    fun stats(samples: List<HrSample>, start: Long, end: Long): HrStats? {
        val values = samples.filter { it.time in start..end }.map { it.bpm }
        if (values.isEmpty()) return null
        return HrStats(values.average().roundToInt(), values.min(), values.max(), values.size)
    }
}
