package com.enjoyyourtime.globalfitness.ui.training

import com.enjoyyourtime.globalfitness.training.domain.BodyShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class BodyWarpTest {
    private class Side(val female: Boolean, val side: BodySide) {
        val parts = parseParts(bodyArt(side, female))
        val body = BodyProportions.measure(parts)

        fun warp(fatness: Float, muscularity: Float) = BodyWarp.of(BodyShape(fatness, muscularity), body, female)

        override fun toString() = "$side female=$female"
    }

    private val sides = listOf(false, true).flatMap { female -> BodySide.entries.map { Side(female, it) } }
    private val levels = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    private val out = FloatArray(2)

    private fun field(warp: BodyWarp, x: Float, y: Float): Pair<Float, Float> {
        warp.field(x, y, out)
        return out[0] to out[1]
    }

    /** Points across the figure's box, a little beyond it on each side. */
    private fun grid(s: Side): List<Pair<Float, Float>> {
        val half = s.body.halfWidths.max() * 3.2f
        val bottom = s.body.binY(s.body.halfWidths.size - 1)
        return (0..40).flatMap { i -> (0..40).map { j -> (s.body.centerX - half + 2 * half * i / 40) to (s.body.top + (bottom - s.body.top) * j / 40) } }
    }

    @Test
    fun theAverageShapeLeavesTheFigureAsDrawn() {
        for (s in sides) {
            val warp = s.warp(0.5f, 0.5f)
            for ((x, y) in grid(s)) assertEquals(x to y, field(warp, x, y))
            val warped = warp.apply(s.parts)
            for ((part, contours) in s.parts.zip(warped)) {
                val original = part.pieces.flatten()
                assertEquals(original.size, contours.size)
                for ((a, b) in original.zip(contours)) assertTrue("$s ${part.art.slug}", a.points.contentEquals(b.points))
            }
        }
    }

    @Test
    fun theWarpIsSymmetricAboutTheCentreLineAndKeepsHeights() {
        for (s in sides) for (f in levels) for (m in levels) {
            val warp = s.warp(f, m)
            val cx = s.body.centerX
            for ((x, y) in grid(s)) {
                val (rx, ry) = field(warp, x, y)
                val (lx, ly) = field(warp, 2 * cx - x, y)
                assertEquals("$s f=$f m=$m at ($x, $y)", rx - cx, -(lx - cx), 1e-2f)
                assertEquals(y, ry)
                assertEquals(y, ly)
            }
        }
    }

    @Test
    fun theWarpIsBoundedAndNeverFolds() {
        for (s in sides) {
            val widest = s.body.halfWidths.max()
            for (f in levels) for (m in levels) {
                val warp = s.warp(f, m)
                val cx = s.body.centerX
                for (j in 0..60) {
                    val y = s.body.top + (s.body.soleY - s.body.top) * j / 60
                    var previous = -Float.MAX_VALUE
                    for (i in 0..200) {
                        val x = cx - 3 * widest + 6 * widest * i / 200
                        val (wx, _) = field(warp, x, y)
                        assertTrue("$s f=$f m=$m: moved ${wx - x} at ($x, $y)", abs(wx - x) <= 0.6f * widest)
                        assertTrue("$s f=$f m=$m: folds at ($x, $y)", wx > previous)
                        previous = wx
                    }
                }
            }
        }
    }

    @Test
    fun fatWidensTheWaistAndMuscleTheShoulders() {
        for (s in sides) {
            val body = s.body
            val span = body.soleY - body.shoulderY
            val waistY = body.shoulderY + 0.27f * span
            val shoulderY = body.shoulderY + 0.02f * span
            fun widening(warp: BodyWarp, y: Float): Float {
                val edge = body.halfWidthAt(y)
                return field(warp, body.centerX + edge, y).first - (body.centerX + edge)
            }
            val fat = s.warp(1f, 0.5f)
            val muscle = s.warp(0.5f, 1f)
            val lean = s.warp(0f, 0.5f)
            assertTrue("$s", widening(fat, waistY) > 0.2f * body.halfWidthAt(waistY))
            assertTrue("$s", widening(lean, waistY) < 0f)
            assertTrue("$s", widening(muscle, shoulderY) > widening(muscle, waistY))
            assertTrue("$s", widening(fat, waistY) > widening(fat, shoulderY))
            // The head's height is (all but) untouched: well under a unit of the figure's ~1300.
            val headY = body.shoulderY - 0.15f * span
            assertEquals(0f, widening(fat, headY) + widening(muscle, headY), 0.5f)
        }
    }

    @Test
    fun headHandsAndFeetKeepTheirSize() {
        for (s in sides) {
            for ((f, m) in listOf(1f to 1f, 0f to 0f, 1f to 0f, 0f to 1f)) {
                val warped = s.warp(f, m).apply(s.parts)
                for ((part, contours) in s.parts.zip(warped)) {
                    if (part.art.slug !in setOf("head", "hair", "hands", "feet")) continue
                    // Each side's pieces (the head's in the middle) move as one: every point by one offset.
                    val offsets = HashMap<Int, Pair<Float, Float>>()
                    var k = 0
                    for (piece in part.pieces) {
                        val centre = BodyWarp.centre(piece)[0] - s.body.centerX
                        val group = if (abs(centre) < 2f) 0 else if (centre > 0) 1 else -1
                        for (a in piece) {
                            val b = contours[k++]
                            for (i in a.points.indices step 2) {
                                val d = (b.points[i] - a.points[i]) to (b.points[i + 1] - a.points[i + 1])
                                val expected = offsets.getOrPut(group) { d }
                                val where = "$s f=$f m=$m ${part.art.slug}"
                                assertEquals(where, expected.first, d.first, 1e-2f)
                                assertEquals(where, expected.second, d.second, 1e-2f)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun fullerAndMoreMuscularFiguresAreWider() {
        for (s in sides) {
            fun width(f: Float, m: Float): Float {
                var lo = Float.MAX_VALUE
                var hi = -Float.MAX_VALUE
                for (c in s.warp(f, m).apply(s.parts).flatten()) {
                    for (i in c.points.indices step 2) {
                        lo = min(lo, c.points[i])
                        hi = max(hi, c.points[i])
                    }
                }
                return hi - lo
            }
            assertTrue("$s", width(1f, 0.5f) > width(0.5f, 0.5f))
            assertTrue("$s", width(0.5f, 1f) > width(0.5f, 0.5f))
            assertTrue("$s", width(0f, 0f) < width(0.5f, 0.5f))
            // Even the widest shape stays within a sensible margin of the drawn figure.
            assertTrue("$s", width(1f, 1f) < 1.25f * width(0.5f, 0.5f))
        }
    }

    @Test
    fun shapeAxesMapToWarpLevelsMonotonically() {
        assertEquals(0f, BodyWarp.response(0.5f))
        assertEquals(-1f, BodyWarp.response(0f))
        assertEquals(1f, BodyWarp.response(1f))
        var previous = -2f
        for (i in 0..100) {
            val level = BodyWarp.response(i / 100f)
            assertTrue(level > previous)
            assertEquals(-level, BodyWarp.response(1f - i / 100f), 1e-6f)
            previous = level
        }
    }

    @Test
    fun theShapedFrameHoldsEveryShapeTightly() {
        // The frame is a measured constant: every shape of both sides fits in it, and it is no more
        // than the outward rounding (a unit) larger than the widest and tallest of them.
        for (female in listOf(false, true)) {
            val frame = shapedFrame(female)
            var half = 0f
            var top = Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (s in sides.filter { it.female == female }) {
                for (fatness in levels) {
                    for (muscularity in levels) {
                        for (contour in s.warp(fatness, muscularity).apply(s.parts).flatten()) {
                            val p = contour.points
                            for (i in p.indices step 2) {
                                half = max(half, abs(p[i] - s.body.centerX))
                                top = min(top, p[i + 1])
                                bottom = max(bottom, p[i + 1])
                            }
                        }
                    }
                }
            }
            assertTrue("female=$female half $half", half <= frame.halfWidth && frame.halfWidth - half <= 1f)
            assertTrue("female=$female top $top", top >= frame.top && top - frame.top <= 1f)
            assertTrue("female=$female bottom $bottom", bottom <= frame.bottom && frame.bottom - bottom <= 1f)
        }
    }

    @Test
    fun aLimbWithoutPointsIsLeftAsItIs() {
        val s = sides.first()
        val warp = s.warp(1f, 1f)
        assertEquals(null, warp.extent(emptyList()))
        assertEquals(null, warp.extent(listOf(Contour(FloatArray(0), closed = true))))
        val empty = ParsedPart(ArtPart("biceps", emptyList()), listOf(emptyList(), listOf(Contour(FloatArray(0), closed = true))))
        for (contour in warp.apply(listOf(empty)).flatten()) assertTrue(contour.points.all { it.isFinite() })
    }

    @Test
    fun separationLinesFadeWithFatAndSharpenWithMuscle() {
        val average = separationMix(BodyShape.NEUTRAL)
        assertEquals(separationMix(null), average)
        assertTrue(separationMix(BodyShape(1f, 0.5f)) < average)
        assertTrue(separationMix(BodyShape(0.5f, 1f)) > average)
    }

    @Test
    fun arcsBecomeCubicsOnTheSameCircle() {
        // A circle of radius 10 around (10, 0) drawn as two half arcs.
        val circle = outlineOf("M0 0a10 10 0 1 0 20 0a10 10 0 1 0-20 0z")
        assertEquals(1, circle.size)
        val c = circle.single()
        assertTrue(c.closed)
        for (i in 0 until c.segments) {
            val x = c.points[2 + 6 * i + 4]
            val y = c.points[2 + 6 * i + 5]
            assertEquals(10f, hypot(x - 10f, y), 1e-3f)
        }
        // Lines keep their end points; long segments are split for the warp.
        val line = outlineOf("M0 0L100 0L100 10z").single()
        assertTrue(line.segments > 3)
        assertEquals(0f, line.points[0])
        assertEquals(0f, line.points[line.points.size - 2], 1e-4f)
        assertTrue((0 until line.points.size / 2).all { k -> line.points[2 * k + 1] in -1e-4f..10.0001f })
    }

    @Test
    fun relativeAndReflectedCommandsFollowTheCurrentPoint() {
        val c = outlineOf("M10 10h5v5H10V10m20 0q5 -5 10 0t10 0s5 5 10 0c1 1 2 2 3 3", maxSegment = 1000f)
        assertEquals(2, c.size)
        val square = c[0].points
        // M10 10 → h5 → v5 → H10 → V10: ends where it started.
        assertEquals(10f, square[square.size - 2])
        assertEquals(10f, square[square.size - 1])
        val curve = c[1].points
        assertEquals(30f, curve[0])
        assertEquals(10f, curve[1])
        // q to (40, 10), t to (50, 10), s to (60, 10), c to (63, 13).
        assertEquals(63f, curve[curve.size - 2], 1e-4f)
        assertEquals(13f, curve[curve.size - 1], 1e-4f)
        // The reflected quadratic's control point mirrors the previous one about (40, 10): (45, 15) → cubic controls below the line.
        assertTrue(curve[2 + 6 + 1] > 10f)
    }
}
