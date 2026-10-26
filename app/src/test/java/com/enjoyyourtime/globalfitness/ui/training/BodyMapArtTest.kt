package com.enjoyyourtime.globalfitness.ui.training

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import com.enjoyyourtime.globalfitness.training.model.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyMapArtTest {
    private val figures = BodySide.entries.flatMap { side -> listOf(false, true).map { female -> Triple(side, female, bodyArt(side, female)) } }

    /** Upstream parts that are not muscle groups of the app (drawn neutral, not tappable). */
    private val nonMuscleSlugs = setOf("head", "hair", "hands", "knees", "tibialis", "ankles", "feet")

    @Test
    fun everyMuscleIsDrawnOnSomeSideForBothSexes() {
        for (female in listOf(false, true)) {
            val drawn = BodySide.entries.flatMap { musclesOf(bodyArt(it, female)) }.toSet()
            assertEquals("female=$female", Muscle.entries.toSet(), drawn)
        }
    }

    @Test
    fun sideMusclesMatchTheArtOfBothSexes() {
        for ((side, female, parts) in figures) {
            assertEquals("$side female=$female", side.muscles(), musclesOf(parts))
        }
        assertEquals(Muscle.entries.toSet(), BodySide.FRONT.muscles() + BodySide.BACK.muscles())
        assertTrue(Muscle.CHEST in BodySide.FRONT.muscles() && Muscle.CHEST !in BodySide.BACK.muscles())
        assertTrue(Muscle.LATS in BodySide.BACK.muscles() && Muscle.LATS !in BodySide.FRONT.muscles())
    }

    @Test
    fun sideMusclesAreInMuscleOrder() {
        for (side in BodySide.entries) {
            assertEquals(side.muscles().sortedBy { it.ordinal }, side.muscles().toList())
        }
    }

    @Test
    fun everySlugIsAMuscleOrAKnownBodyPart() {
        for ((side, female, parts) in figures) {
            for (part in parts) {
                assertTrue("$side female=$female: unknown slug ${part.slug}", part.muscle != null || part.slug in nonMuscleSlugs)
                assertTrue("$side female=$female: ${part.slug} has no paths", part.paths.isNotEmpty())
            }
            assertEquals("$side female=$female: repeated slug", parts.size, parts.map { it.slug }.toSet().size)
        }
    }

    @Test
    fun everyPathParses() {
        var count = 0
        for ((side, female, parts) in figures) {
            for (part in parts) {
                for (d in part.paths) {
                    val where = "$side female=$female ${part.slug}: ${d.take(40)}"
                    assertTrue(where, d.isNotBlank())
                    val nodes = PathParser().parsePathString(d).toNodes()
                    assertTrue(where, nodes.size > 1)
                    assertTrue(where, nodes.first() is PathNode.MoveTo || nodes.first() is PathNode.RelativeMoveTo)
                    count++
                }
            }
        }
        assertEquals(317, count)
    }

    @Test
    fun compactArcFlagsAreReadAsFlags() {
        // The artwork writes arc flags without separators ("0 012.89" = flags 0, 1 and x 2.89).
        val arc = PathParser().parsePathString("M0 0a1.71 1.71 0 012.89 1.12").toNodes()[1] as PathNode.RelativeArcTo
        assertFalse(arc.isMoreThanHalf)
        assertTrue(arc.isPositiveArc)
        assertEquals(2.89f, arc.arcStartDx, 1e-4f)
        assertEquals(1.12f, arc.arcStartDy, 1e-4f)
    }
}
