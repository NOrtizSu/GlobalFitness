package com.enjoyyourtime.globalfitness.health.domain

import com.enjoyyourtime.globalfitness.health.HrSample
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateZonesTest {
    private val min = 60_000L

    @Test
    fun zonesUse220MinusAge() {
        assertEquals(180, HeartRateZones.maxHeartRate(40))
        val zones = HeartRateZones.zones(40)
        assertEquals(listOf(90, 108, 126, 144, 162), zones.map { it.minBpm })
        assertEquals(listOf(108, 126, 144, 162, null), zones.map { it.maxBpm })
        assertEquals(listOf("Muy suave", "Suave", "Moderada", "Intensa", "Máxima"), zones.map { it.label })
    }

    @Test
    fun zoneOfBoundaries() {
        assertEquals(0, HeartRateZones.zoneOf(89, 40))
        assertEquals(1, HeartRateZones.zoneOf(90, 40))
        assertEquals(1, HeartRateZones.zoneOf(107, 40))
        assertEquals(2, HeartRateZones.zoneOf(108, 40))
        assertEquals(4, HeartRateZones.zoneOf(161, 40))
        assertEquals(5, HeartRateZones.zoneOf(162, 40))
        assertEquals(5, HeartRateZones.zoneOf(200, 40))
    }

    @Test
    fun timeInZonesCountsUntilNextSampleWithGapCap() {
        val start = 0L
        val samples = listOf(
            HrSample(0, 100), // Z1 for 2 min
            HrSample(2 * min, 130), // Z3 for 1 min
            HrSample(3 * min, 170), // Z5: gap of 20 min capped at 5 min
            HrSample(23 * min, 80), // zone 0 until the end (2 min)
        )
        val ms = HeartRateZones.timeInZones(samples, start, 25 * min, age = 40)
        assertArrayEquals(longArrayOf(2 * min, 2 * min, 0, 1 * min, 0, 5 * min), ms)
    }

    @Test
    fun samplesOutsideTheRangeAreIgnored() {
        val samples = listOf(HrSample(-min, 150), HrSample(0, 100), HrSample(10 * min, 150))
        val ms = HeartRateZones.timeInZones(samples, 0, 5 * min, age = 40)
        assertEquals(5 * min, ms.sum())
        assertEquals(5 * min, ms[1])
    }

    @Test
    fun statsOfRange() {
        val samples = listOf(HrSample(0, 60), HrSample(min, 90), HrSample(2 * min, 120), HrSample(10 * min, 200))
        val s = HeartRateZones.stats(samples, 0, 2 * min)!!
        assertEquals(90, s.avg)
        assertEquals(60, s.min)
        assertEquals(120, s.max)
        assertEquals(3, s.count)
        assertNull(HeartRateZones.stats(samples, 3 * min, 4 * min))
    }
}
