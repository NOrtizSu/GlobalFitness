package com.enjoyyourtime.globalfitness.ui.training.motion

import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The animations of the larger gym catalog and the defaults a custom exercise borrows from it. */
class MotionGymCatalogTest {
    @Test
    fun theGymMotionsAreCatalogExercisesNotAlreadyMapped() {
        val ids = ExerciseCatalog.all.map { it.id }.toSet()
        assertTrue(gymMotions.keys - ids == emptySet<String>())
        assertEquals(gymMotions.keys, gymMotions.keys intersect MotionCatalog.mappedIds)
        // Joined with "+", a repeated id would silently replace the original animation.
        assertEquals(emptySet<String>(), gymMotions.keys intersect MotionCatalog.baseIds)
        assertEquals(emptySet<String>(), sportsMotions.keys intersect MotionCatalog.baseIds)
        assertEquals(emptySet<String>(), gymMotions.keys intersect sportsMotions.keys)
    }

    @Test
    fun customExercisesBorrowTheNewTypicalMovements() {
        assertEquals("dumbbell_wrist_curl", source(Equipment.DUMBBELL, Muscle.FOREARMS))
        assertEquals("dumbbell_calf_raise", source(Equipment.DUMBBELL, Muscle.CALVES))
        assertEquals("single_leg_calf_raise", source(Equipment.BODYWEIGHT, Muscle.CALVES))
        assertEquals("cable_hip_adduction", source(Equipment.CABLE, Muscle.ADDUCTORS))
        assertEquals("lying_neck_flexion", source(Equipment.BODYWEIGHT, Muscle.NECK))
        assertEquals("kettlebell_row", source(Equipment.KETTLEBELL, Muscle.LATS))
        assertEquals("band_curl", source(Equipment.BAND, Muscle.BICEPS))
        // Unchanged: no band movement for the calves.
        assertEquals(null, source(Equipment.BAND, Muscle.CALVES))
    }

    @Test
    fun aCustomExerciseNeverBorrowsAStretch() {
        val stretches = ExerciseCatalog.all.filter { it.mobility }.map { it.id }.toSet()
        for (m in Muscle.entries) {
            for (e in Equipment.entries) {
                val id = source(e, m) ?: continue
                assertFalse("$m with $e borrows the stretch $id", id in stretches)
            }
        }
    }

    private fun source(equipment: Equipment, muscle: Muscle): String? = MotionCatalog.sourceId(
        Exercise("u:test", UiText.Raw("Mine"), ExerciseKind.WEIGHT_REPS, equipment, setOf(muscle), emptySet(), builtIn = false),
    )
}
