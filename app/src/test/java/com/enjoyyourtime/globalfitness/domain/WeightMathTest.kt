package com.enjoyyourtime.globalfitness.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeightMathTest {

    @Test
    fun sameReadingUsesIntegerGramsWithinHalfAStep() {
        assertEquals(72_350L, WeightMath.grams(72.35))
        assertTrue(WeightMath.sameReading(72.35, 72.35))
        assertTrue(WeightMath.sameReading(72.35, 72.375))
        assertTrue(WeightMath.sameReading(72.35, 72.325))
        assertFalse(WeightMath.sameReading(72.35, 72.376))
        // Exactly one display step (50 g) apart is a different reading, with no rounding ambiguity.
        assertFalse(WeightMath.sameReading(72.30, 72.35))
        assertFalse(WeightMath.sameReading(0.1 + 0.2, 0.35))
        assertTrue(WeightMath.sameReading(0.1 + 0.2, 0.3))
    }
}
