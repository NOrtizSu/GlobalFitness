package com.enjoyyourtime.globalfitness.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisMathTest {

    @Test
    fun ticksCoverDataWithRoundSteps() {
        for ((lo, hi) in listOf(70.1 to 72.4, 0.0 to 100.0, 1450.0 to 1720.0, 2.91 to 3.02, 18.0 to 18.0)) {
            val t = AxisMath.niceTicks(lo, hi)
            assertTrue("min ${t.min} > $lo", t.min <= lo + 1e-9)
            assertTrue("max ${t.max} < $hi", t.max >= hi - 1e-9)
            assertTrue("count ${t.values.size}", t.values.size in 3..5)
        }
    }

    @Test
    fun decimalsMatchStep() {
        assertEquals(0, AxisMath.decimalsFor(5.0))
        assertEquals(1, AxisMath.decimalsFor(2.5))
        assertEquals(1, AxisMath.decimalsFor(0.5))
        assertEquals(2, AxisMath.decimalsFor(0.25))
        assertEquals(2, AxisMath.decimalsFor(0.05))
    }
}
