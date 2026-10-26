package com.enjoyyourtime.globalfitness.ui.training

import com.enjoyyourtime.globalfitness.training.domain.BodyShape
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.sign

/**
 * One side of the figure as cubic contours, one list per path of each part (upstream draws most
 * muscles as a left and a right path, and some as several pieces), ready to warp.
 */
internal class ParsedPart(val art: ArtPart, val pieces: List<List<Contour>>)

internal fun parseParts(art: List<ArtPart>): List<ParsedPart> = art.map { part -> ParsedPart(part, part.paths.map { outlineOf(it) }) }

/**
 * Proportions of one side of the figure, measured once from its neutral outline: the vertical
 * centre line, the top of the shoulders and the soles (the height every warp profile is laid along)
 * and, every [BIN] units of height, the half-width of the trunk and legs (arms left out), smoothed.
 * The half-width is the scale of the width warp: the trunk widens in proportion to itself.
 */
internal class BodyProportions(
    val centerX: Float,
    val shoulderY: Float,
    val soleY: Float,
    /** Height of the first bin of [halfWidths]. */
    val top: Float,
    val halfWidths: FloatArray,
) {
    /** 0 at the top of the shoulders, 1 at the soles; negative on the neck and head. */
    fun bodyHeight(y: Float): Float = (y - shoulderY) / (soleY - shoulderY)

    /** Height of bin [i]. */
    fun binY(i: Int): Float = top + i * BIN

    fun halfWidthAt(y: Float): Float = interpolate(halfWidths, y)

    /** Linear interpolation of a per-bin array at height [y] (held flat past both ends). */
    fun interpolate(values: FloatArray, y: Float): Float {
        val f = ((y - top) / BIN).coerceIn(0f, (values.size - 1).toFloat())
        val i = f.toInt().coerceAtMost(values.size - 2).coerceAtLeast(0)
        if (values.size == 1) return values[0]
        val w = f - i
        return values[i] * (1 - w) + values[i + 1] * w
    }

    companion object {
        const val BIN = 4f

        /** Smoothing of the measured widths and of the warp profiles along the height (figure units). */
        const val SMOOTHING = 22f

        /** Parts that make up the trunk and the legs: the arms swing out and are not part of the width. */
        private val TRUNK = setOf(
            "neck", "trapezius", "chest", "abs", "obliques", "upper-back", "lower-back", "gluteal",
            "adductors", "quadriceps", "hamstring", "knees", "tibialis", "calves", "ankles", "feet",
        )

        fun measure(parts: List<ParsedPart>): BodyProportions {
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (part in parts) forEachPoint(part.pieces.flatten()) { x, y ->
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
            }
            val centerX = (minX + maxX) / 2
            val shoulderY = parts.first { it.art.slug == "deltoids" }.pieces.flatten().minOf { c -> c.minY() }
            val soleY = parts.first { it.art.slug == "feet" }.pieces.flatten().maxOf { c -> c.maxY() }
            val bins = ((maxY - minY) / BIN).toInt() + 2
            val raw = FloatArray(bins) { Float.NaN }
            for (part in parts) {
                if (part.art.slug !in TRUNK) continue
                forEachPoint(part.pieces.flatten()) { x, y ->
                    val i = ((y - minY) / BIN).toInt().coerceIn(0, bins - 1)
                    val v = abs(x - centerX)
                    if (raw[i].isNaN() || v > raw[i]) raw[i] = v
                }
            }
            fillGaps(raw)
            val widest = raw.max()
            val smooth = smooth(raw, SMOOTHING / BIN)
            for (i in smooth.indices) smooth[i] = max(smooth[i], widest * MIN_HALF_WIDTH)
            return BodyProportions(centerX, shoulderY, soleY, minY, smooth)
        }

        /** The narrowest half-width the warp scales by (the neck), as a share of the widest. */
        private const val MIN_HALF_WIDTH = 0.3f

        private inline fun forEachPoint(contours: List<Contour>, block: (Float, Float) -> Unit) {
            for (c in contours) {
                val p = c.points
                var i = 0
                while (i < p.size) {
                    block(p[i], p[i + 1])
                    i += 2
                }
            }
        }

        private fun Contour.minY(): Float {
            var m = Float.MAX_VALUE
            var i = 1
            while (i < points.size) {
                m = min(m, points[i])
                i += 2
            }
            return m
        }

        private fun Contour.maxY(): Float {
            var m = -Float.MAX_VALUE
            var i = 1
            while (i < points.size) {
                m = max(m, points[i])
                i += 2
            }
            return m
        }

        /** Bins without trunk points (above the neck) take the nearest measured value. */
        private fun fillGaps(a: FloatArray) {
            val first = a.indexOfFirst { !it.isNaN() }
            if (first < 0) {
                a.fill(1f)
                return
            }
            for (i in 0 until first) a[i] = a[first]
            for (i in first + 1 until a.size) if (a[i].isNaN()) a[i] = a[i - 1]
        }
    }
}

/** Gaussian smoothing of [a] with a standard deviation of [sigma] bins (ends held flat). */
internal fun smooth(a: FloatArray, sigma: Float): FloatArray {
    val r = (sigma * 3).toInt().coerceAtLeast(1)
    val kernel = FloatArray(2 * r + 1) { k -> exp(-((k - r) * (k - r)) / (2 * sigma * sigma)) }
    val out = FloatArray(a.size)
    for (i in a.indices) {
        var sum = 0f
        var weight = 0f
        for (k in -r..r) {
            val j = (i + k).coerceIn(0, a.size - 1)
            sum += a[j] * kernel[k + r]
            weight += kernel[k + r]
        }
        out[i] = sum / weight
    }
    return out
}

/**
 * How the figure changes with a [BodyShape]: [fat] and [muscle] go from −1 to 1 (0 = the figure as
 * drawn, the average body). Three layers, all symmetric about the centre line and bounded:
 *
 * 1. A smooth width warp of the whole figure ([field]): at each height the trunk and legs widen by
 *    an amount that follows a profile along the body (fat: strongest at the belly, then the hips,
 *    thighs and chest, little at the shoulders; muscle: shoulders, upper back and chest, thighs and
 *    calves, the waist kept narrow for the V-taper). Inside the trunk the width scales; past its
 *    edge the displacement levels off ([saturate], [saturateFat]), so the arms move out as a whole
 *    instead of stretching. The map is monotonic in x at every height (no fold), heights are kept.
 * 2. Limbs thicken: each side's upper arm, forearm and shoulder widens about a line near its inner
 *    edge, so it grows outwards rather than into the trunk (fat adds most to the upper arms, muscle
 *    to the arms and shoulders).
 * 3. Each muscle grows or shrinks about its centre (muscle), and every piece swells a little with fat
 *    so the thin gaps between muscles close (less definition) or open a little when lean.
 *
 * The head, hair, hands and feet keep their size: they only follow the warp as a whole.
 */
internal class BodyWarp(val fat: Float, val muscle: Float, private val body: BodyProportions, female: Boolean) {
    val isIdentity: Boolean = fat == 0f && muscle == 0f

    /** Women's figure gains less visible bulk from the same muscularity (and stays recognisable). */
    private val muscleLevel = muscle * if (female) FEMALE_MUSCLE_SHARE else 1f

    /** Horizontal displacement scales per bin (figure units): the half-width times each profile. */
    private val fatAmplitude: FloatArray = amplitude(if (female) FEMALE_FAT_WIDTH else MALE_FAT_WIDTH, fat, LEAN_SHARE)
    private val muscleAmplitude: FloatArray = amplitude(MUSCLE_WIDTH, muscleLevel, THIN_SHARE)

    private fun amplitude(profile: HeightProfile, level: Float, downShare: Float): FloatArray {
        val a = FloatArray(body.halfWidths.size) { i ->
            body.halfWidths[i] * gain(level, profile.at(body.bodyHeight(body.binY(i))), downShare)
        }
        return smooth(a, BodyProportions.SMOOTHING / BodyProportions.BIN)
    }

    /** The width warp at ([x], [y]), written into [out] (x, y). */
    fun field(x: Float, y: Float, out: FloatArray) {
        if (isIdentity) {
            out[0] = x
            out[1] = y
            return
        }
        val u = x - body.centerX
        val v = abs(u)
        val s = v / body.halfWidthAt(y)
        val d = body.interpolate(fatAmplitude, y) * saturateFat(s) + body.interpolate(muscleAmplitude, y) * saturate(s)
        out[0] = body.centerX + sign(u) * (v + d)
        out[1] = y
    }

    /** Extra width of the limbs about their own centre (1 = unchanged). */
    fun limbScale(slug: String): Float {
        val (fatGain, muscleGain) = LIMB_GAIN[slug] ?: return 1f
        return 1f + gain(fat, fatGain, LEAN_SHARE) + gain(muscleLevel, muscleGain, THIN_SHARE)
    }

    /** Growth of one piece about its centre (1 = unchanged); rigid parts never grow. */
    fun pieceScale(slug: String): Float {
        if (slug in RIGID) return 1f
        val swell = if (fat >= 0) fat * FAT_SWELL else fat * LEAN_TIGHTEN
        return 1f + swell + gain(muscleLevel, MUSCLE_GROWTH[slug] ?: 0f, THIN_SHARE)
    }

    private fun gain(level: Float, full: Float, downShare: Float) = if (level >= 0) level * full else level * downShare * full

    /**
     * The parts of one side warped: every piece through [field], then limbs widened about their
     * group's centre (each side's upper arm, forearm, shoulder) and pieces scaled about their own
     * centre. Rigid parts (head, hair, hands, feet) move by the field at their centre, unscaled.
     */
    fun apply(parts: List<ParsedPart>): List<List<Contour>> {
        if (isIdentity) return parts.map { it.pieces.flatten() }
        val tmp = FloatArray(2)
        return parts.map { part ->
            val slug = part.art.slug
            if (slug in RIGID) {
                // Each side's group (both hands' fingers, one foot...) moves as one, so it stays whole.
                part.pieces.groupBy { side(centre(it)[0]) }.flatMap { (_, pieces) ->
                    val c = centre(pieces.flatten())
                    field(c[0], c[1], tmp)
                    val dx = tmp[0] - c[0]
                    val dy = tmp[1] - c[1]
                    pieces.flatten().map { contour -> contour.map { x, y, o -> o[0] = x + dx; o[1] = y + dy } }
                }
            } else {
                val warped = part.pieces.map { piece -> piece.map { c -> c.map(::field) } }
                val limb = limbScale(slug)
                val widened = if (limb == 1f) {
                    warped
                } else {
                    // All the pieces of this part on one side widen together, about a line near the
                    // limb's inner edge: it thickens mostly outwards instead of into the trunk.
                    val groups = warped.indices.groupBy { side(centre(part.pieces[it])[0]) }
                    val result = warped.toMutableList()
                    for ((sideSign, members) in groups) {
                        // Pieces without points (an empty path) have nothing to widen.
                        val (inner, outer) = extent(members.flatMap { warped[it] }) ?: continue
                        val pivot = body.centerX + sideSign * (inner + LIMB_PIVOT * (outer - inner))
                        for (i in members) result[i] = warped[i].map { c -> c.map { x, y, o -> o[0] = pivot + (x - pivot) * limb; o[1] = y } }
                    }
                    result
                }
                val grow = pieceScale(slug)
                if (grow == 1f) {
                    widened.flatten()
                } else {
                    widened.flatMap { piece ->
                        val c = centre(piece)
                        piece.map { contour -> contour.map { x, y, o -> o[0] = c[0] + (x - c[0]) * grow; o[1] = c[1] + (y - c[1]) * grow } }
                    }
                }
            }
        }
    }

    /** Nearest and farthest distance of [contours]' points from the centre line; null without any point. */
    internal fun extent(contours: List<Contour>): Pair<Float, Float>? {
        if (contours.all { it.points.isEmpty() }) return null
        var inner = Float.MAX_VALUE
        var outer = 0f
        for (c in contours) {
            val p = c.points
            var i = 0
            while (i < p.size) {
                val v = abs(p[i] - body.centerX)
                inner = min(inner, v)
                outer = max(outer, v)
                i += 2
            }
        }
        return inner to outer
    }

    /** −1, 0 or 1: left of, on or right of the centre line (with a little tolerance for the middle). */
    private fun side(x: Float): Int {
        val u = x - body.centerX
        return if (abs(u) < CENTRE_TOLERANCE) 0 else sign(u).toInt()
    }

    companion object {
        fun of(shape: BodyShape, body: BodyProportions, female: Boolean) =
            BodyWarp(response(shape.fatness), response(shape.muscularity), body, female)

        /**
         * A shape axis (0..1, 0.5 average) as a warp level (−1..1, 0 average), eased out: twice as
         * responsive around the average as at the ends, so the few-tenths changes of a real
         * evolution are visible while the extremes stay as tasteful as the full range allows.
         */
        fun response(value: Float): Float {
            val x = ((value - 0.5f) * 2).coerceIn(-1f, 1f)
            val rest = 1f - abs(x)
            return sign(x) * (1f - rest * rest)
        }

        /**
         * 0 at 0, slope 1 there, levelling off at 1 well past the trunk's edge (0.84 at the edge):
         * the trunk widens while what is beyond it (the arms) shifts by about the same amount.
         */
        fun saturate(s: Float): Float {
            val s2 = s * s
            return s / sqrt(sqrt(1f + s2 * s2))
        }

        /**
         * Like [saturate], then easing back to [FAT_ARM_SHARE] past the trunk: a fuller trunk pushes
         * the arms out by less than it widens, so they rest closer to the body. The slope never goes
         * below −0.5, so with displacements under the half-width the map cannot fold.
         */
        fun saturateFat(s: Float): Float {
            val k = ((s - 1f) / 1.2f).coerceIn(0f, 1f)
            return saturate(s) - (1f - FAT_ARM_SHARE) * k * k * (3 - 2 * k)
        }

        private const val FAT_ARM_SHARE = 0.65f

        private const val FEMALE_MUSCLE_SHARE = 0.75f

        /** Where across a limb (0 = inner edge, 1 = outer edge) it widens about. */
        private const val LIMB_PIVOT = 0.25f

        /** Lean (fat < 0) narrows less than fat widens, thin (muscle < 0) less than muscle broadens. */
        private const val LEAN_SHARE = 0.45f
        private const val THIN_SHARE = 0.6f

        /** Fat swells every piece a little (closing the gaps between muscles); lean tightens them. */
        private const val FAT_SWELL = 0.035f
        private const val LEAN_TIGHTEN = 0.02f

        private const val CENTRE_TOLERANCE = 2f

        private val RIGID = setOf("head", "hair", "hands", "feet")

        /**
         * Width added to the trunk and legs by full fat, as a share of their half-width, along the
         * body (0 = top of the shoulders, 1 = soles; the neck is just below 0, the head further).
         * Men gain it mostly at the belly; women at the hips and thighs (the usual android and
         * gynoid patterns).
         */
        val MALE_FAT_WIDTH = HeightProfile(
            -0.12f to 0f, -0.04f to 0.06f, 0f to 0.04f, 0.08f to 0.12f, 0.16f to 0.26f,
            0.24f to 0.46f, 0.30f to 0.45f, 0.36f to 0.32f, 0.45f to 0.22f, 0.55f to 0.15f,
            0.64f to 0.06f, 0.70f to 0.05f, 0.78f to 0.07f, 0.88f to 0.02f, 0.95f to 0f,
        )
        val FEMALE_FAT_WIDTH = HeightProfile(
            -0.12f to 0f, -0.04f to 0.05f, 0f to 0.04f, 0.08f to 0.10f, 0.16f to 0.18f,
            0.24f to 0.26f, 0.30f to 0.32f, 0.37f to 0.36f, 0.45f to 0.30f, 0.55f to 0.20f,
            0.64f to 0.08f, 0.70f to 0.05f, 0.78f to 0.07f, 0.88f to 0.02f, 0.95f to 0f,
        )

        /** Width added by full muscle: shoulders, upper back and chest, thighs and calves; the waist stays. */
        val MUSCLE_WIDTH = HeightProfile(
            -0.12f to 0f, -0.04f to 0.10f, 0f to 0.20f, 0.06f to 0.17f, 0.13f to 0.12f,
            0.20f to 0.04f, 0.27f to 0f, 0.34f to 0.02f, 0.42f to 0.09f, 0.52f to 0.11f,
            0.62f to 0.06f, 0.68f to 0.02f, 0.76f to 0.10f, 0.85f to 0.05f, 0.95f to 0f,
        )

        /** Limbs widened about their own centre by full fat and full muscle. */
        private val LIMB_GAIN = mapOf(
            "biceps" to (0.22f to 0.14f),
            "triceps" to (0.22f to 0.14f),
            "forearm" to (0.10f to 0.10f),
            "deltoids" to (0.06f to 0.10f),
        )

        /** Growth of each muscle about its own centre with full muscle. */
        private val MUSCLE_GROWTH = mapOf(
            "deltoids" to 0.08f,
            "chest" to 0.06f,
            "biceps" to 0.08f,
            "triceps" to 0.06f,
            "forearm" to 0.04f,
            "trapezius" to 0.06f,
            "upper-back" to 0.05f,
            "neck" to 0.04f,
            "quadriceps" to 0.05f,
            "hamstring" to 0.04f,
            "gluteal" to 0.04f,
            "calves" to 0.07f,
            "adductors" to 0.02f,
        )

        /** Centre of a piece (middle of its bounding box), as (x, y). */
        fun centre(contours: List<Contour>): FloatArray {
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (c in contours) {
                val p = c.points
                var i = 0
                while (i < p.size) {
                    minX = min(minX, p[i])
                    maxX = max(maxX, p[i])
                    minY = min(minY, p[i + 1])
                    maxY = max(maxY, p[i + 1])
                    i += 2
                }
            }
            return floatArrayOf((minX + maxX) / 2, (minY + maxY) / 2)
        }
    }
}

/** A value along the body: linear between the given (height, value) keys, flat past the ends. */
internal class HeightProfile(vararg keys: Pair<Float, Float>) {
    private val t = keys.map { it.first }.toFloatArray()
    private val v = keys.map { it.second }.toFloatArray()

    fun at(x: Float): Float {
        if (x <= t.first()) return v.first()
        if (x >= t.last()) return v.last()
        var i = 1
        while (t[i] < x) i++
        val w = (x - t[i - 1]) / (t[i] - t[i - 1])
        return v[i - 1] + (v[i] - v[i - 1]) * w
    }
}
