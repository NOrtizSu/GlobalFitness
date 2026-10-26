package com.enjoyyourtime.globalfitness.ui.training

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideTextTest {
    @Test
    fun stepsAreSplitPerLineWithoutTheirNumbers() {
        val text = "1. Lie on the bench, feet flat.\n2. Lower the bar to the chest.\n3. Press it back up."
        assertEquals(
            listOf("Lie on the bench, feet flat.", "Lower the bar to the chest.", "Press it back up."),
            GuideText.steps(text),
        )
    }

    @Test
    fun blankLinesAndSurroundingSpacesAreDropped() {
        assertEquals(listOf("Stand tall.", "Squat down."), GuideText.steps("  1. Stand tall.  \r\n\n\n 2) Squat down.\n  "))
        assertEquals(emptyList<String>(), GuideText.steps(""))
        assertEquals(emptyList<String>(), GuideText.tips(" \n \n"))
    }

    @Test
    fun numbersInOtherScriptsAndPunctuationAreRemoved() {
        assertEquals(listOf("استلقِ على المقعد", "أنزل البار"), GuideText.steps("١. استلقِ على المقعد\n٢) أنزل البار"))
        assertEquals(listOf("躺在凳子上", "放下杠铃", "推起"), GuideText.steps("1、躺在凳子上\n2．放下杠铃\n3.推起"))
        assertEquals(listOf("बेंच पर लेटें"), GuideText.steps("१. बेंच पर लेटें"))
        assertEquals(listOf("Twelfth step"), GuideText.steps("12. Twelfth step"))
    }

    @Test
    fun numbersThatBelongToTheTextAreKept() {
        assertEquals(
            listOf("90° bend at the knees", "2-3 s on the way down", "1.5 times your bodyweight", "3 sets of 10", "Keep it slow"),
            GuideText.steps("90° bend at the knees\n2-3 s on the way down\n1.5 times your bodyweight\n3 sets of 10\nKeep it slow"),
        )
        // Only a number: nothing would be left, so the line stays as written.
        assertEquals(listOf("4."), GuideText.steps("4."))
    }

    @Test
    fun tipsLoseTheirBullets() {
        assertEquals(
            listOf("Keep your back flat.", "Do not lock the knees.", "Breathe out on the way up.", "No bullet here."),
            GuideText.tips("• Keep your back flat.\n•Do not lock the knees.\n- Breathe out on the way up.\nNo bullet here."),
        )
        assertEquals(listOf("膝をつま先の方向へ"), GuideText.tips("・膝をつま先の方向へ"))
        assertEquals(listOf("•"), GuideText.tips("•"))
    }
}
