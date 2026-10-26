package com.enjoyyourtime.globalfitness.ui.training

import androidx.compose.ui.geometry.Rect
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.model.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseThumbnailTest {
    private fun side(vararg primary: Muscle) = thumbnailSide(primary.toSet())

    @Test
    fun noMusclesMeansNoFigure() {
        assertNull(thumbnailSide(emptySet()))
        assertNull(thumbnailSide(emptySet(), emptySet()))
    }

    @Test
    fun theSideHoldingThePrimaryMusclesIsShown() {
        assertEquals(BodySide.FRONT, side(Muscle.CHEST))
        assertEquals(BodySide.FRONT, side(Muscle.BICEPS))
        assertEquals(BodySide.FRONT, side(Muscle.ABS, Muscle.OBLIQUES))
        assertEquals(BodySide.BACK, side(Muscle.LATS))
        assertEquals(BodySide.BACK, side(Muscle.GLUTES, Muscle.HAMSTRINGS, Muscle.LOWER_BACK))
        // Traps are on both sides, lats only on the back: the back shows both.
        assertEquals(BodySide.BACK, side(Muscle.LATS, Muscle.TRAPS))
        // Chest is only on the front; the front also shows the triceps.
        assertEquals(BodySide.FRONT, side(Muscle.CHEST, Muscle.TRICEPS))
    }

    @Test
    fun whenBothSidesShowThemTheUsualViewDecidesThenTheFront() {
        assertEquals(BodySide.FRONT, side(Muscle.SHOULDERS))
        assertEquals(BodySide.BACK, side(Muscle.TRICEPS))
        assertEquals(BodySide.BACK, side(Muscle.CALVES))
        assertEquals(BodySide.BACK, side(Muscle.TRAPS))
        // One muscle on each side: the front.
        assertEquals(BodySide.FRONT, side(Muscle.QUADS, Muscle.GLUTES))
        assertEquals(BodySide.FRONT, side(Muscle.LATS, Muscle.BICEPS))
        assertEquals(BodySide.FRONT, side(Muscle.SHOULDERS, Muscle.TRAPS))
    }

    @Test
    fun secondaryMusclesPickTheSideWhenThereAreNoPrimaryOnes() {
        assertEquals(BodySide.BACK, thumbnailSide(emptySet(), setOf(Muscle.HAMSTRINGS)))
        assertEquals(BodySide.FRONT, thumbnailSide(setOf(Muscle.QUADS), setOf(Muscle.HAMSTRINGS, Muscle.GLUTES)))
    }

    @Test
    fun theChosenSideShowsAPrimaryMuscleOfEveryCatalogExercise() {
        for (ex in ExerciseCatalog.all) {
            val side = thumbnailSide(ex.primary, ex.secondary)
            if (ex.primary.isEmpty() && ex.secondary.isEmpty()) {
                assertNull(ex.id, side)
            } else {
                val focus = ex.primary.ifEmpty { ex.secondary }
                assertTrue(ex.id, focus.any { it in side!!.muscles() })
            }
        }
    }

    private val frame = Rect(0f, 0f, 50f, 100f)

    @Test
    fun cropIsASquareAroundTheMusclesOnTheMidline() {
        val abs = Rect(18f, 40f, 32f, 52f)
        val crop = thumbnailCrop(frame, abs)
        assertEquals(crop.width, crop.height, 1e-3f)
        assertEquals(frame.center.x, crop.center.x, 1e-3f)
        assertEquals(abs.center.y, crop.center.y, 1e-3f)
        assertTrue(crop.left <= abs.left && crop.right >= abs.right && crop.top <= abs.top && crop.bottom >= abs.bottom)
        // Small muscles still show a recognisable part of the body.
        assertEquals(58f, crop.height, 1e-3f)
    }

    @Test
    fun cropStaysInsideTheFigureFromHeadToFeet() {
        val calves = Rect(10f, 80f, 40f, 95f)
        val low = thumbnailCrop(frame, calves)
        assertEquals(frame.bottom, low.bottom, 1e-3f)
        val neck = Rect(20f, 5f, 30f, 10f)
        val high = thumbnailCrop(frame, neck)
        assertEquals(frame.top, high.top, 1e-3f)
    }

    @Test
    fun cropNeverExceedsTheWholeFigure() {
        val everything = Rect(0f, 0f, 50f, 100f)
        val crop = thumbnailCrop(frame, everything)
        assertEquals(frame.height, crop.height, 1e-3f)
        assertEquals(frame.center, crop.center)
        val tall = Rect(10f, 20f, 40f, 80f)
        val wide = thumbnailCrop(frame, tall)
        assertEquals(75f, wide.height, 1e-3f)
        assertTrue(wide.top >= frame.top && wide.bottom <= frame.bottom)
    }
}
