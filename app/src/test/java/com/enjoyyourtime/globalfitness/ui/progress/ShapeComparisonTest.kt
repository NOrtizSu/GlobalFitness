package com.enjoyyourtime.globalfitness.ui.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The moment Progreso → Músculos compares the body figure with. */
class ShapeComparisonTest {
    private val zone = ZoneId.of("Europe/Madrid")

    private fun at(date: String, time: String) = LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()

    private fun midnight(date: String) = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun theFullComparisonStartsAtMidnightAndStaysPutThroughTheDay() {
        val first = at("2025-01-01", "10:00")
        // 25 October 2026 (the day the clocks go back): three months of 30 days earlier is 27 July.
        val morning = MusclesViewModel.pastAt(first, at("2026-10-25", "00:30"), zone)
        val evening = MusclesViewModel.pastAt(first, at("2026-10-25", "23:59"), zone)
        assertEquals(midnight("2026-07-27"), morning)
        assertEquals(morning, evening)
        assertEquals(midnight("2026-07-27"), MusclesViewModel.compareFrom(at("2026-10-25", "12:00"), zone))
        // The next day it moves on by one day.
        assertEquals(midnight("2026-07-28"), MusclesViewModel.pastAt(first, at("2026-10-26", "08:00"), zone))
    }

    @Test
    fun newerDataStartsTheComparisonAndTooLittleHasNone() {
        val now = at("2026-10-25", "12:00")
        val first = at("2026-09-01", "18:30")
        assertEquals(first, MusclesViewModel.pastAt(first, now, zone))
        assertNull(MusclesViewModel.pastAt(at("2026-10-20", "09:00"), now, zone))
        assertNull(MusclesViewModel.pastAt(null, now, zone))
    }
}
