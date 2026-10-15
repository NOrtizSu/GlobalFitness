package com.enjoyyourtime.globalfitness.domain

import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.data.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ProfileMatcherTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private var nextId = 1L

    private fun profile(id: Long) = Profile(id, "P$id", Sex.MALE, LocalDate.of(1990, 1, 1), 175.0, null, id.toInt(), 0)

    private fun m(profileId: Long?, weight: Double, daysAgo: Int) =
        Measurement(nextId++, profileId, now - daysAgo * day, weight, 500, null, MeasurementSource.SCALE)

    @Test
    fun noProfilesStaysPending() {
        assertNull(ProfileMatcher.match(72.0, now, emptyList(), emptyList(), 3.0))
    }

    @Test
    fun singleProfileAlwaysAssigned() {
        val profiles = listOf(profile(1))
        assertEquals(1L, ProfileMatcher.match(72.0, now, profiles, emptyList(), 3.0))
        assertEquals(1L, ProfileMatcher.match(140.0, now, profiles, listOf(m(1, 60.0, 1)), 3.0))
    }

    @Test
    fun singleCandidateWithinTolerance() {
        val profiles = listOf(profile(1), profile(2))
        val ms = listOf(m(1, 72.0, 2), m(2, 55.0, 1))
        assertEquals(1L, ProfileMatcher.match(72.3, now, profiles, ms, 3.0))
        assertEquals(2L, ProfileMatcher.match(56.5, now, profiles, ms, 3.0))
    }

    @Test
    fun noCandidateWithinToleranceStaysPending() {
        val profiles = listOf(profile(1), profile(2))
        val ms = listOf(m(1, 72.0, 2), m(2, 55.0, 1))
        assertNull(ProfileMatcher.match(64.0, now, profiles, ms, 3.0))
    }

    @Test
    fun ambiguousCandidatesStayPending() {
        val profiles = listOf(profile(1), profile(2))
        val ms = listOf(m(1, 72.0, 2), m(2, 73.0, 1))
        assertNull(ProfileMatcher.match(72.6, now, profiles, ms, 3.0))
    }

    @Test
    fun clearlyClosestWinsByOneKilo() {
        val profiles = listOf(profile(1), profile(2))
        val ms = listOf(m(1, 70.0, 2), m(2, 73.5, 1))
        // distances 1.0 and 2.5 -> margin 1.5 >= 1.0
        assertEquals(1L, ProfileMatcher.match(71.0, now, profiles, ms, 3.0))
        // distances 1.5 and 2.0 -> margin 0.5 -> pending
        assertNull(ProfileMatcher.match(71.5, now, profiles, ms, 3.0))
    }

    @Test
    fun usesMostRecentReferenceOfAnyAge() {
        val profiles = listOf(profile(1), profile(2))
        val ms = listOf(
            m(1, 90.0, 400), // occasional user: old, but still the reference
            m(2, 60.0, 10),
            m(2, 65.0, 3), // most recent for profile 2
        )
        assertEquals(2L, ProfileMatcher.match(66.0, now, profiles, ms, 3.0))
        assertEquals(1L, ProfileMatcher.match(89.0, now, profiles, ms, 3.0))
    }

    @Test
    fun profileWithoutAnyMeasurementMakesItPending() {
        val profiles = listOf(profile(1), profile(2), profile(3))
        val ms = listOf(m(1, 72.0, 2), m(2, 55.0, 1)) // profile 3 never weighed
        assertNull(ProfileMatcher.match(72.1, now, profiles, ms, 3.0))
        assertNull(ProfileMatcher.match(55.0, now, profiles, ms, 3.0))
    }

    @Test
    fun toleranceIsRespected() {
        val profiles = listOf(profile(1), profile(2))
        val ms = listOf(m(1, 72.0, 2), m(2, 50.0, 1))
        assertNull(ProfileMatcher.match(75.5, now, profiles, ms, 3.0))
        assertEquals(1L, ProfileMatcher.match(75.5, now, profiles, ms, 4.0))
    }
}
