package com.enjoyyourtime.globalfitness.ui.training

import androidx.compose.ui.graphics.vector.addPathNodes
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingIconsTest {
    @Test
    fun everyGlyphPathParses() {
        for ((name, path) in TrainingIcons.paths) {
            assertTrue("$name has no path commands", addPathNodes(path).size > 2)
        }
    }
}
