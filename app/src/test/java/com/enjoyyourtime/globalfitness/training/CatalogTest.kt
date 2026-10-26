package com.enjoyyourtime.globalfitness.training

import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invariants of the built-in catalog that the statistics, the backup and the names rely on. */
class CatalogTest {
    private val all = ExerciseCatalog.all

    @Test
    fun idsAreUniqueAndStable() {
        val ids = all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val bad = ids.filterNot { ID.matches(it) }
        assertTrue("ids outside [a-z0-9_]: $bad", bad.isEmpty())
    }

    @Test
    fun foundationIdsAreStillThere() {
        // Stored in workouts and backups made before the catalog grew.
        for (id in ORIGINAL_IDS) assertNotNull(id, ExerciseCatalog[id])
        for (e in all) assertSame(e, ExerciseCatalog[e.id])
    }

    @Test
    fun everyNameIsTheStringNamedAfterTheId() {
        val string = R.string::class.java
        for (e in all) {
            val field = runCatching { string.getField("ex_${e.id}") }.getOrNull()
            assertNotNull("missing string ex_${e.id}", field)
            assertEquals("ex_${e.id} is not the nameRes of ${e.id}", field!!.getInt(null), e.nameRes)
        }
    }

    @Test
    fun cardioIsExactlyTheCardioMachines() {
        for (e in all) {
            assertEquals(e.id, e.kind == ExerciseKind.CARDIO, e.equipment == Equipment.CARDIO_MACHINE)
            if (e.kind == ExerciseKind.CARDIO) {
                assertTrue("${e.id} trains no muscle group", e.primary.isEmpty() && e.secondary.isEmpty())
                assertNotNull("${e.id} needs a Health Connect session type", e.hcSession)
            }
        }
    }

    @Test
    fun strengthExercisesHaveConsistentMuscles() {
        for (e in all.filter { it.kind != ExerciseKind.CARDIO }) {
            assertTrue("${e.id} has no primary muscle", e.primary.isNotEmpty())
            assertTrue("${e.id} repeats a primary muscle as secondary", (e.primary intersect e.secondary).isEmpty())
            assertNull("${e.id} is not cardio: no session type", e.hcSession)
        }
    }

    @Test
    fun bodyweightSharesAreOnlyWhereTheyMakeSense() {
        for (e in all) {
            val f = e.bodyweightFactor
            when (e.kind) {
                // Assisted machines need the share: their load is body weight minus assistance.
                ExerciseKind.ASSISTED_REPS -> assertTrue("${e.id} needs a body-weight share", f != null && f > 0.0 && f <= 1.0)
                ExerciseKind.BODYWEIGHT_REPS -> assertTrue("${e.id} share out of range", f == null || (f > 0.0 && f <= 1.0))
                else -> assertNull("${e.id} is not a bodyweight exercise", f)
            }
        }
        // The assisted machine continues the free exercise's curve.
        assertEquals(ExerciseCatalog["pull_up"]!!.bodyweightFactor, ExerciseCatalog["assisted_pull_up_machine"]!!.bodyweightFactor)
        assertEquals(ExerciseCatalog["parallel_bar_dip"]!!.bodyweightFactor, ExerciseCatalog["assisted_dip_machine"]!!.bodyweightFactor)
    }

    @Test
    fun everyMuscleCanBeTrainedFromTheCatalog() {
        val primaryCount = all.flatMap { it.primary }.groupingBy { it }.eachCount()
        for (m in Muscle.entries) {
            val need = if (m == Muscle.NECK) 1 else 2
            val have = primaryCount[m] ?: 0
            assertTrue("$m is the primary muscle of $have exercises, needs $need", have >= need)
        }
    }

    private companion object {
        val ID = Regex("[a-z0-9_]+")
        val ORIGINAL_IDS = listOf(
            "barbell_bench_press",
            "barbell_back_squat",
            "lat_pulldown_machine",
            "leg_press_machine",
            "pull_up",
            "plank",
            "treadmill",
        )
    }
}
