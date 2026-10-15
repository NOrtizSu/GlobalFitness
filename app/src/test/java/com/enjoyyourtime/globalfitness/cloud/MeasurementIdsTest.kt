package com.enjoyyourtime.globalfitness.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeasurementIdsTest {

    @Test
    fun idIsTimestampAndGrams() {
        assertEquals("m_1700000000000_72350", MeasurementIds.idOf(1_700_000_000_000L, 72_350L))
    }

    @Test
    fun weightIsRoundedToWholeGrams() {
        // Floating-point noise from parsing / arithmetic must not change the id.
        assertEquals(MeasurementIds.idOf(1L, 72.35), MeasurementIds.idOf(1L, 72.35000000001))
        assertEquals(MeasurementIds.idOf(1L, 72.35), MeasurementIds.idOf(1L, 72.3499999999))
        assertEquals(72_350L, MeasurementIds.grams(72.35))
        assertEquals(100_050L, MeasurementIds.grams(100.05))
    }

    @Test
    fun sameReadingGetsSameIdAfterReinstall() {
        val before = MeasurementRecord(1_700_000_000_000L, 80_100L, null, "scale", "k1", 0L)
        val restored = before.copy(impedance = 512, scaleKey = null, updatedAt = 99L)
        assertEquals(before.id, restored.id)
    }

    @Test
    fun parseRoundTrips() {
        val id = MeasurementIds.idOf(1_712_345_678_901L, 65_400L)
        assertEquals(1_712_345_678_901L to 65_400L, MeasurementIds.parse(id))
    }

    @Test
    fun parseRejectsMalformedIds() {
        assertNull(MeasurementIds.parse("m_abc_123"))
        assertNull(MeasurementIds.parse("x_1_2"))
        assertNull(MeasurementIds.parse("m_1_2_3"))
        assertNull(MeasurementIds.parse(""))
    }
}
