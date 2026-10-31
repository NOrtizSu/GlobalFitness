package com.enjoyyourtime.globalfitness.ui.training.motion

import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.atan2

/**
 * Which way the elbows bend: a forearm folds towards the front of its upper arm, give or take how
 * far the upper arm can turn in or out, never backwards (an arm bent the wrong way reads at once,
 * like the concentration curl whose dumbbell rose into the trunk). And the curls bring the weight
 * up in front of the body.
 */
class MotionAnatomyTest {
    private val motions: Map<String, Motion> = ExerciseCatalog.all.associate { it.id to requireNotNull(MotionCatalog.forId(it.id)) }

    @Test
    fun elbowsNeverBendBackwards() {
        val problems = mutableListOf<String>()
        for ((id, m) in motions) {
            var worst = 0f
            var at = 0f
            for (i in 0 until SAMPLES) {
                val t = m.duration * i / SAMPLES
                val pose = m.frame(t).pose
                for (side in Side.entries) {
                    val off = elbowTurn(pose, pose.arm(side)) ?: continue
                    if (off > worst) {
                        worst = off
                        at = t
                    }
                }
            }
            if (worst > MAX_TURN) problems += "$id %.2f s: the elbow bends %.0f° away from the front of the upper arm".format(at, worst)
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun curlsBringTheWeightUpInFront() {
        val curls = mapOf(
            "barbell_curl" to Side.entries,
            "ez_bar_curl" to Side.entries,
            "dumbbell_curl" to Side.entries,
            "hammer_curl" to Side.entries,
            "cable_curl" to Side.entries,
            "incline_dumbbell_curl" to Side.entries,
            "ez_bar_preacher_curl" to Side.entries,
            "machine_biceps_curl" to Side.entries,
            "concentration_curl" to listOf(Side.NEAR),
        )
        for ((id, sides) in curls) {
            val m = motions.getValue(id)
            val top = requireNotNull(m.track).keys.first { it.value == 1f }.time
            val pose = m.frame(top).pose
            for (side in sides) {
                val a = pose.arm(side)
                assertTrue("$id: the $side hand is not above the elbow at the top", a.hand.y > a.elbow.y + 0.1f)
                // Every curl faces +X: the weight ends in front of the shoulder, not behind it.
                assertTrue("$id: the $side hand ends behind the shoulder", a.hand.x > a.shoulder.x + 0.05f)
                val elbow = 180f - angle(a.shoulder - a.elbow, a.wrist - a.elbow)
                assertTrue("$id: the $side elbow only bends $elbow° at the top", elbow > 100f)
            }
        }
    }

    /**
     * How far (degrees) the forearm of [arm] folds from where it would with the upper arm turned
     * neither in nor out: the front of the upper arm carried from hanging by the shortest swing (and,
     * for an arm raised past level, from overhead too, where the swing from hanging is unstable); null
     * when the elbow is almost straight, which has no direction.
     */
    private fun elbowTurn(pose: Pose, arm: ArmPose): Float? {
        val upper = (arm.elbow - arm.shoulder).unit()
        val fore = (arm.wrist - arm.elbow).unit()
        if (angle(upper, fore) < 20f) return null
        val fold = orthogonal(fore, upper)
        val chest = pose.upper
        var off = angle(fold, orthogonal(swing(-chest.u, upper, chest.f), upper))
        if (angle(upper, -chest.u) > 100f) off = minOf(off, angle(fold, orthogonal(swing(chest.u, upper, -chest.f), upper)))
        return off
    }

    /** [v] turned by the shortest rotation that takes [from] to [to] (all unit vectors). */
    private fun swing(from: V3, to: V3, v: V3): V3 {
        val axis = from cross to
        val s = axis.length
        val c = from dot to
        if (s < 1e-6f) return if (c > 0f) v else -v
        return rotate(v, axis * (1f / s), atan2(s, c))
    }

    private fun angle(a: V3, b: V3): Float = deg(acos((a.unit() dot b.unit()).coerceIn(-1f, 1f)))

    private companion object {
        const val SAMPLES = 48

        /**
         * The upper arm turns about 90° either way; the margin covers the approximate reference (a
         * bar held behind the neck or in the front rack reaches about 120°; the arms that bent
         * backwards were at 150° and more).
         */
        const val MAX_TURN = 130f
    }
}
