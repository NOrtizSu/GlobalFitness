package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.cos
import kotlin.math.sin

/** A side of the body: [NEAR] is the figure's right side (towards the viewer of a side view), [FAR] its left. */
internal enum class Side(val sign: Float) {
    NEAR(1f),
    FAR(-1f),
    ;

    val other: Side get() = if (this == NEAR) FAR else NEAR
}

/**
 * Proportions of the mannequin, in metres, for a figure about 1.75 m tall: bone lengths (constant in
 * every frame), joint offsets and the radii the segments are drawn with. Slightly stylised (bigger
 * head, thicker limbs) so the figure reads at the size of a bottom sheet.
 */
internal object BodySize {
    const val THIGH = 0.43f
    const val SHIN = 0.43f
    const val UPPER_ARM = 0.29f

    /** Elbow to the centre of the fist, of which elbow to wrist is [WRIST]. */
    const val FOREARM = 0.30f
    const val WRIST = 0.23f
    const val HAND = FOREARM - WRIST

    /** Hip joints to the waist, and waist to the base of the neck. */
    const val LOWER_SPINE = 0.22f
    const val UPPER_SPINE = 0.30f

    /** Base of the neck to the centre of the head. */
    const val NECK = 0.16f
    const val HEAD_RADIUS = 0.105f

    /** Half the distance between the hip joints, and between the shoulder joints. */
    const val HIP_HALF = 0.09f
    const val SHOULDER_HALF = 0.175f

    /** How far the shoulder joints sit below the base of the neck. */
    const val SHOULDER_DROP = 0.055f

    /** Ankle above the sole line, heel behind it and toes in front of it, along the foot. */
    const val SOLE = 0.04f
    const val HEEL = 0.05f
    const val TOE = 0.15f
    const val FOOT_RADIUS = 0.035f

    /** Ankle height of a foot standing flat on the floor. */
    const val ANKLE = SOLE + FOOT_RADIUS

    /** Pelvis height of a figure standing tall (knees just short of locked, so feet stay planted). */
    const val STAND = 0.925f

    const val THIGH_RADIUS = 0.075f
    const val KNEE_RADIUS = 0.056f
    const val ANKLE_RADIUS = 0.04f
    const val SHOULDER_RADIUS = 0.052f
    const val ELBOW_RADIUS = 0.041f
    const val WRIST_RADIUS = 0.034f
    const val FIST_RADIUS = 0.046f
    const val NECK_RADIUS = 0.047f

    /** Far limbs are drawn this much thinner, a depth cue. */
    const val FAR_SCALE = 0.93f
}

/**
 * One arm of a [Pose]: joints in world space ([hand] is the centre of the fist) and the direction
 * the elbow points ([apex]). The wrist is straight unless the pattern bends it (wrist curls).
 */
internal class ArmPose(val shoulder: V3, val elbow: V3, val hand: V3, val apex: V3, wrist: V3? = null) {
    val wrist: V3 = wrist ?: (elbow + (hand - elbow) * (BodySize.WRIST / BodySize.FOREARM))
}

/** One leg of a [Pose]: joints in world space and the direction the knee points ([apex]). */
internal class LegPose(val hip: V3, val knee: V3, val ankle: V3, val heel: V3, val toe: V3, val apex: V3)

/**
 * The mannequin in one frame. The spine has two segments, [lower] (hips to waist) and [upper]
 * (waist to the base of the neck), each with its own orientation, so crunches curl and squats
 * keep a straight back.
 */
internal class Pose(
    val pelvis: V3,
    val waist: V3,
    val neck: V3,
    val head: V3,
    val lower: Basis,
    val upper: Basis,
    val headBasis: Basis,
    private val arms: List<ArmPose>,
    private val legs: List<LegPose>,
) {
    fun arm(side: Side): ArmPose = arms[side.ordinal]

    fun leg(side: Side): LegPose = legs[side.ordinal]
}

/** How a foot is oriented: [dir] from heel to toes and [up], the normal of the sole. */
internal class Foot(val dir: V3, val up: V3) {
    companion object {
        /** Flat on the floor, pointing forward and turned out by [out] degrees towards [side]. */
        fun flat(side: Side, out: Float = 6f): Foot = Foot(V3(cos(rad(out)), 0f, side.sign * sin(rad(out))), V3.Y)
    }
}

/**
 * Builds a [Pose]: the pattern places the pelvis and orients the spine, then puts each hand and foot
 * where the exercise needs it (inverse kinematics keeps every bone length) or bends a limb by angles.
 * Every inverse-kinematics target that the bones cannot reach is recorded in [maxMiss], which the
 * tests require to stay at zero (hands stay on the bar, feet stay planted).
 */
internal class Rig {
    private var pelvis = V3(0f, BodySize.STAND, 0f)
    private var lower = Basis.UPRIGHT
    private var upper = Basis.UPRIGHT
    private var headBasis = Basis.UPRIGHT
    private var shrug = 0f
    private var reach = 0f
    private val arms = arrayOfNulls<ArmPose>(2)
    private val legs = arrayOfNulls<LegPose>(2)

    /** Largest distance between an inverse-kinematics target and what the limb reached. */
    var maxMiss = 0f
        private set

    val waist: V3 get() = pelvis + lower.u * BodySize.LOWER_SPINE
    val neck: V3 get() = waist + upper.u * BodySize.UPPER_SPINE
    val chest: Basis get() = upper
    val hips: Basis get() = lower

    /**
     * Places the pelvis (centre of the hip joints) at [at] with the lower spine oriented as [basis];
     * the upper spine [bend]s forward (negative: arches back), [twist]s towards the near side and
     * bends [sideways] relative to it; the head [nod]s forward and [tilt]s towards the near shoulder.
     * [shrug] lifts and [protract] moves the shoulder joints forward (shrugs, scapular retraction in
     * rows), in metres.
     */
    fun torso(
        at: V3,
        basis: Basis = Basis.UPRIGHT,
        bend: Float = 0f,
        twist: Float = 0f,
        sideways: Float = 0f,
        nod: Float = 0f,
        shrug: Float = 0f,
        protract: Float = 0f,
        tilt: Float = 0f,
    ) {
        pelvis = at
        lower = basis
        upper = basis.pitch(bend).roll(sideways).turn(twist)
        headBasis = upper.pitch(nod).roll(tilt)
        this.shrug = shrug
        reach = protract
    }

    fun shoulder(side: Side): V3 =
        neck + upper.u * (shrug - BodySize.SHOULDER_DROP) + upper.f * reach + upper.l * (side.sign * BodySize.SHOULDER_HALF)

    fun hip(side: Side): V3 = pelvis + lower.l * (side.sign * BodySize.HIP_HALF)

    /** A point on the body in the chest's own axes, measured from the base of the neck. */
    fun onChest(forward: Float, up: Float, lateral: Float = 0f): V3 = neck + upper.dir(forward, up, lateral)

    /** A point in the hips' own axes, measured from the pelvis. */
    fun onHips(forward: Float, up: Float, lateral: Float = 0f): V3 = pelvis + lower.dir(forward, up, lateral)

    /** Puts the centre of the [side] fist at [hand], the elbow pointing towards [pole]. */
    fun arm(side: Side, hand: V3, pole: V3) {
        val s = shoulder(side)
        val ik = twoBone(s, hand, BodySize.UPPER_ARM, BodySize.FOREARM, pole)
        record(ik.end, hand)
        arms[side.ordinal] = ArmPose(s, ik.mid, ik.end, ik.apex)
    }

    /** Puts the [side] wrist at [wrist] (elbow towards [pole]) with the hand bent along [handDir]: wrist curls. */
    fun armWrist(side: Side, wrist: V3, pole: V3, handDir: V3) {
        val s = shoulder(side)
        val ik = twoBone(s, wrist, BodySize.UPPER_ARM, BodySize.WRIST, pole)
        record(ik.end, wrist)
        arms[side.ordinal] = ArmPose(s, ik.mid, ik.end + handDir.unit() * BodySize.HAND, ik.apex, wrist = ik.end)
    }

    /**
     * Bends the [side] arm by angles: the upper arm points along [dir] (world), the elbow flexes by
     * [elbow] degrees with the forearm folding towards [fold].
     */
    fun armAngles(side: Side, dir: V3, elbow: Float, fold: V3) {
        val s = shoulder(side)
        val upperDir = dir.unit()
        val e = s + upperDir * BodySize.UPPER_ARM
        val foldDir = orthogonal(fold, upperDir)
        val fore = upperDir * cos(rad(elbow)) + foldDir * sin(rad(elbow))
        val hand = e + fore * BodySize.FOREARM
        arms[side.ordinal] = ArmPose(s, e, hand, apexOf(s, e, hand, -foldDir))
    }

    /** The [side] arm hanging relaxed, [forward] degrees in front of the body. */
    fun hang(side: Side, forward: Float = 4f, out: Float = 6f) {
        val dir = upper.dir(sin(rad(forward)), -cos(rad(forward)), 0f) * cos(rad(out)) + upper.l * (side.sign * sin(rad(out)))
        armAngles(side, dir, elbow = 10f, fold = upper.f)
    }

    /**
     * Puts the [side] ankle at [ankle] with the knee pointing towards [pole] and the foot oriented as
     * [foot] (by default relaxed, about square to the shin).
     */
    fun leg(side: Side, ankle: V3, pole: V3, foot: Foot? = null) {
        val h = hip(side)
        val ik = twoBone(h, ankle, BodySize.THIGH, BodySize.SHIN, pole)
        record(ik.end, ankle)
        legs[side.ordinal] = legPose(h, ik.mid, ik.end, ik.apex, foot)
    }

    /** Bends the [side] leg by angles: the thigh along [dir], the knee flexing by [knee] degrees towards [fold]. */
    fun legAngles(side: Side, dir: V3, knee: Float, fold: V3, foot: Foot? = null) {
        val h = hip(side)
        val thighDir = dir.unit()
        val k = h + thighDir * BodySize.THIGH
        val foldDir = orthogonal(fold, thighDir)
        val shin = thighDir * cos(rad(knee)) + foldDir * sin(rad(knee))
        val ankle = k + shin * BodySize.SHIN
        legs[side.ordinal] = legPose(h, k, ankle, apexOf(h, k, ankle, -foldDir), foot)
    }

    /**
     * Stands the [side] foot on the floor (or on a surface at height [floor]) with the ankle above
     * ([x], [z]) when flat; [lift] raises the heel by that many degrees, pivoting on the toes (calf
     * raises, running). The knee points where the foot points.
     */
    fun stand(side: Side, x: Float, z: Float = side.sign * 0.12f, floor: Float = 0f, lift: Float = 0f, out: Float = 6f, pole: V3? = null) {
        val flat = Foot.flat(side, out)
        val ankleFlat = V3(x, floor + BodySize.ANKLE, z)
        val toe = ankleFlat - V3.Y * BodySize.SOLE + flat.dir * BodySize.TOE
        val a = rad(lift)
        val dir = flat.dir * cos(a) - V3.Y * sin(a)
        val up = V3.Y * cos(a) + flat.dir * sin(a)
        val ankle = toe + up * BodySize.SOLE - dir * BodySize.TOE
        leg(side, ankle, pole ?: (flat.dir + V3.Y * 0.15f), Foot(dir, up))
    }

    /** The [side] arm as placed so far (props are often attached to the hands after the arms are bent). */
    fun armOf(side: Side): ArmPose = requireNotNull(arms[side.ordinal]) { "the $side arm is not placed yet" }

    fun legOf(side: Side): LegPose = requireNotNull(legs[side.ordinal]) { "the $side leg is not placed yet" }

    fun build(): Pose {
        for (side in Side.entries) if (arms[side.ordinal] == null) hang(side)
        val legList = Side.entries.map { requireNotNull(legs[it.ordinal]) { "the pattern did not place the $it leg" } }
        val n = neck
        return Pose(
            pelvis = pelvis,
            waist = waist,
            neck = n,
            head = n + headBasis.u * BodySize.NECK + headBasis.f * 0.015f,
            lower = lower,
            upper = upper,
            headBasis = headBasis,
            arms = Side.entries.map { arms[it.ordinal]!! },
            legs = legList,
        )
    }

    /**
     * Where the middle joint of a limb bent by angles points, measured like the inverse kinematics
     * does (away from the line from [root] to [end]), so both kinds of limbs agree; [straight] when
     * the limb is straight.
     */
    private fun apexOf(root: V3, mid: V3, end: V3, straight: V3): V3 = orthogonal(mid - root, (end - root).unit(), straight)

    private fun record(reached: V3, target: V3) {
        val miss = reached.distanceTo(target)
        if (miss > maxMiss) maxMiss = miss
    }

    private fun legPose(hip: V3, knee: V3, ankle: V3, apex: V3, foot: Foot?): LegPose {
        val shinAxis = (ankle - knee).unit()
        val f = foot ?: run {
            // Relaxed: toes point the way the knee cap does (outward from the bend, or the apex
            // while the leg is straight), tipped 15° away from the shin.
            val bend = (knee - hip).unit() - shinAxis
            val forward = orthogonal(bend + apex * 0.3f, shinAxis, apex)
            val dir = (forward * cos(rad(15f)) + shinAxis * sin(rad(15f))).unit()
            Foot(dir, orthogonal(-shinAxis, dir))
        }
        val sole = ankle - f.up * BodySize.SOLE
        return LegPose(hip, knee, ankle, heel = sole - f.dir * BodySize.HEEL, toe = sole + f.dir * BodySize.TOE, apex = apex)
    }
}
