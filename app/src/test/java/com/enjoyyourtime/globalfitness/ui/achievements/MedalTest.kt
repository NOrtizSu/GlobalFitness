package com.enjoyyourtime.globalfitness.ui.achievements

import androidx.compose.ui.graphics.vector.addPathNodes
import com.enjoyyourtime.globalfitness.data.Sex
import com.enjoyyourtime.globalfitness.training.domain.Achievement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MedalTest {
    @Test
    fun everyGlyphPathParses() {
        for ((name, path) in MedalGlyphs.paths) {
            assertTrue("$name has no path commands", addPathNodes(path).size > 2)
        }
    }

    @Test
    fun everyLevelHasItsOwnColour() {
        for (a in Achievement.entries) {
            val colours = (1..a.levels).map { MedalColors.of(a, it) }
            assertEquals("${a.key} repeats a colour", colours.size, colours.toSet().size)
        }
        // A single level is gold, the third colour of every ladder.
        assertEquals(MedalColors.of(Achievement.WORKOUTS, 3), MedalColors.of(Achievement.GOAL_REACHED, 1))
    }

    @Test
    fun definitionsAreStableAndAscending() {
        assertEquals(Achievement.entries.size, Achievement.entries.map { it.key }.toSet().size)
        for (a in Achievement.entries) {
            assertEquals(a, Achievement.fromKey(a.key))
            for (sex in Sex.entries) {
                val t = a.thresholds(sex)
                assertEquals("${a.key}: same number of levels for everyone", a.levels, t.size)
                assertEquals("${a.key}: easiest first", t.sorted(), t)
                assertEquals("${a.key}: no repeated level", t.size, t.toSet().size)
            }
        }
    }
}
