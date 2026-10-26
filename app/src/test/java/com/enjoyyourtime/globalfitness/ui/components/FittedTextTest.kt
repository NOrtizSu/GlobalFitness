package com.enjoyyourtime.globalfitness.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FittedTextTest {
    @Test
    fun aBreakAtASpaceOrAHyphenIsBetweenWords() {
        // "Assistance (kg)" on two lines: the first line ends after the space.
        assertTrue(breaksBetweenWords("Assistance (kg)", listOf(11)))
        assertTrue(breaksBetweenWords("Thời gian", listOf(5)))
        assertTrue(breaksBetweenWords("Push-up", listOf(5)))
        assertTrue(breaksBetweenWords("Set", emptyList()))
    }

    @Test
    fun aBreakInsideAWordIsNot() {
        assertFalse(breaksBetweenWords("Подход", listOf(5)))
        assertFalse(breaksBetweenWords("Уровень", listOf(4)))
        assertFalse(breaksBetweenWords("المجموعة", listOf(4)))
        assertFalse(breaksBetweenWords("Assistance (kg)", listOf(4)))
    }

    @Test
    fun scriptsWithoutSpacesMayBreakBetweenCharacters() {
        assertTrue(breaksBetweenWords("アシスト（kg）", listOf(2)))
        assertTrue(breaksBetweenWords("辅助（kg）", listOf(1)))
        assertTrue(breaksBetweenWords("어시스트(kg)", listOf(2)))
    }
}
