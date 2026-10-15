package com.enjoyyourtime.globalfitness.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ImportPlannerTest {
    private val t0 = 1_800_000_000_000L
    private val ana = Profile(1, "Ana", Sex.FEMALE, LocalDate.of(1990, 1, 1), 165.0, null, 0, 0)

    private fun row(
        name: String?,
        t: Long,
        w: Double,
        key: String? = null,
        withProfileData: Boolean = false,
    ) = CsvImportRow(
        profileName = name,
        sex = if (withProfileData) Sex.MALE else null,
        birthDate = if (withProfileData) LocalDate.of(1985, 3, 3) else null,
        heightCm = if (withProfileData) 180.0 else null,
        targetWeightKg = null,
        timestamp = t,
        weightKg = w,
        impedance = 500,
        source = MeasurementSource.SCALE,
        scaleKey = key,
    )

    @Test
    fun skipsDuplicatesByKeyAndBySameReadingWithinAMinute() {
        val existing = listOf(Measurement(10, 1, t0, 61.35, 500, "k1", MeasurementSource.SCALE))
        val plan = ImportPlanner.plan(
            rows = listOf(
                row("Ana", t0 + 3_600_000, 61.0, key = "k1"), // same key
                row("Ana", t0 + 30_000, 61.37), // 20 g away, 30 s later
                row("Ana", t0 + 30_000, 61.40), // 50 g away: different reading
                row("Ana", t0 + 120_000, 61.35), // 2 min later: different weighing
                row("Ana", t0 + 120_000, 61.35), // duplicate inside the file itself
            ),
            profile = ana,
            existing = existing,
            now = t0,
        )
        assertEquals(3, plan.duplicates)
        assertEquals(0, plan.skippedOtherProfiles)
        assertEquals(2, plan.measurements.size)
        assertEquals(listOf(1L, 1L), plan.measurements.map { it.profileId })
        assertNull(plan.newProfile)
    }

    @Test
    fun multiProfileFileImportsOnlyTheLocalProfileRows() {
        val plan = ImportPlanner.plan(
            rows = listOf(
                row("luis", t0, 80.0, withProfileData = true),
                row("Luis ", t0 + 86_400_000, 79.5, withProfileData = true),
                row("Desconocido", t0 + 2 * 86_400_000, 70.0),
                row("ANA", t0 + 3 * 86_400_000, 60.0),
                row(null, t0 + 4 * 86_400_000, 65.0), // someone's unassigned weighing: skipped
            ),
            profile = ana,
            existing = emptyList(),
            now = t0,
        )
        assertNull(plan.newProfile)
        assertEquals("ANA", plan.sourceProfileName)
        assertEquals(listOf(60.0), plan.measurements.map { it.weightKg })
        assertEquals(listOf(1L), plan.measurements.map { it.profileId })
        assertEquals(4, plan.skippedOtherProfiles)
        assertEquals(0, plan.duplicates)
    }

    @Test
    fun withoutNameMatchTheProfileWithMostRowsWins() {
        val plan = ImportPlanner.plan(
            rows = listOf(
                row("Marta", t0, 58.0),
                row("luis", t0 + 1_000_000, 80.0, withProfileData = true),
                row("Luis ", t0 + 86_400_000, 79.5, withProfileData = true),
            ),
            profile = null,
            existing = emptyList(),
            now = t0,
        )
        assertNotNull(plan.newProfile)
        val created = plan.newProfile!!
        assertEquals("luis", created.name)
        assertEquals(180.0, created.heightCm, 1e-9)
        assertEquals(listOf(80.0, 79.5), plan.measurements.map { it.weightKg })
        assertEquals(1, plan.skippedOtherProfiles)
    }

    @Test
    fun singleProfileFileKeepsRowsWithoutName() {
        val plan = ImportPlanner.plan(
            rows = listOf(
                row("Ana", t0, 61.0),
                row(null, t0 + 86_400_000, 61.5),
            ),
            profile = ana,
            existing = emptyList(),
            now = t0,
        )
        assertEquals(2, plan.measurements.size)
        assertEquals(0, plan.skippedOtherProfiles)
    }

    @Test
    fun colorSlotsFollowFixedOrderAndReuseFreedSlots() {
        fun p(id: Long, color: Int) = ana.copy(id = id, colorIndex = color)
        assertEquals(0, ProfileColors.nextColorIndex(emptyList()))
        assertEquals(1, ProfileColors.nextColorIndex(listOf(p(1, 0))))
        assertEquals(1, ProfileColors.nextColorIndex(listOf(p(1, 0), p(3, 2))))
        // All 8 slots taken: cycle by count.
        assertEquals(0, ProfileColors.nextColorIndex((0 until 8).map { p(it.toLong(), it) }))
    }
}
