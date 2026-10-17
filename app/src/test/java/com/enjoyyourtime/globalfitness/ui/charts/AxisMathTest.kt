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
    fun labelsThatFitAreAllShown() {
        // Five 40 px labels over 400 px ("00:00" ... "24:00"): first left-aligned, last right-aligned.
        val starts = listOf(0f, 80f, 180f, 280f, 360f)
        assertEquals(listOf(0, 1, 2, 3, 4), AxisMath.nonOverlapping(starts, List(5) { 40f }, 8f))
    }

    @Test
    fun overlappingLabelsSkipEveryOther() {
        // Five 120 px labels ("오전 12:00") over 400 px: every inner neighbour overlaps.
        val starts = listOf(0f, 40f, 140f, 240f, 280f)
        assertEquals(listOf(0, 2, 4), AxisMath.nonOverlapping(starts, List(5) { 120f }, 8f))
    }

    @Test
    fun lastLabelWinsOverInnerOne() {
        // The 3rd label fits after the 1st but the last one overlaps it: the last one is kept.
        val starts = listOf(0f, 50f, 100f, 150f, 180f)
        assertEquals(listOf(0, 4), AxisMath.nonOverlapping(starts, List(5) { 90f }, 8f))
    }

    @Test
    fun firstLabelKeptWhenOnlyTwoCollide() {
        assertEquals(listOf(0), AxisMath.nonOverlapping(listOf(0f, 20f), listOf(40f, 40f), 8f))
        assertEquals(listOf(0), AxisMath.nonOverlapping(listOf(0f), listOf(40f), 8f))
        assertEquals(emptyList<Int>(), AxisMath.nonOverlapping(emptyList(), emptyList(), 8f))
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
