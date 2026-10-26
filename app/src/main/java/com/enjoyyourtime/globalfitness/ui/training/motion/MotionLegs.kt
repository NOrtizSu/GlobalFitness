package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

// Lower-body patterns: squats and hinges.

/** Where a squat's load sits. */
internal enum class SquatLoad { BACK_BAR, FRONT_BAR, SMITH, GOBLET_DUMBBELL, GOBLET_KETTLEBELL, BODYWEIGHT }

/** Middle of the feet: where a balanced load stays above. */
private const val MID_FOOT = 0.05f

/**
 * A squat: hips back and down to thighs about parallel, feet planted shoulder-width and turned
 * out, knees tracking the toes. The pelvis is placed so the load (or the trunk, without one) stays
 * over the middle of the feet, which sets how far the hips travel back.
 */
internal fun squat(load: SquatLoad) = Motion.rep(Tempo.lowerFirst(), Camera(22f)) { p ->
    val upright = load == SquatLoad.FRONT_BAR || load == SquatLoad.GOBLET_DUMBBELL || load == SquatLoad.GOBLET_KETTLEBELL
    val lean = lerp(4f, if (upright) 24f else if (load == SquatLoad.BODYWEIGHT) 36f else 44f, p)
    val basis = Basis.UPRIGHT.pitch(lean)
    val spine = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE
    val loadAt = when (load) {
        SquatLoad.BACK_BAR, SquatLoad.SMITH -> basis.u * spine + basis.dir(-0.13f, -0.06f, 0f)
        SquatLoad.FRONT_BAR -> basis.u * spine + basis.dir(0.14f, -0.07f, 0f)
        SquatLoad.GOBLET_DUMBBELL, SquatLoad.GOBLET_KETTLEBELL -> basis.u * (spine - 0.17f) + basis.dir(0.25f, 0f, 0f)
        SquatLoad.BODYWEIGHT -> basis.u * 0.36f
    }
    val goblet = load == SquatLoad.GOBLET_DUMBBELL || load == SquatLoad.GOBLET_KETTLEBELL
    val pelvis = v(MID_FOOT - loadAt.x * (if (goblet) 0.55f else 1f), lerp(0.915f, if (upright) 0.47f else 0.5f, p))
    body.torso(pelvis, basis, nod = -lean * 0.45f)
    body.standOn(0f, width = 0.34f, out = 18f, pole = v(1f, 0.1f, 0.3f))
    val load3 = pelvis + loadAt
    when (load) {
        SquatLoad.BACK_BAR, SquatLoad.SMITH -> {
            barbell(load3)
            for (side in Side.entries) body.arm(side, load3.withZ(side.sign * 0.44f), basis.dir(-0.7f, -1f, side.sign * 0.4f))
            if (load == SquatLoad.SMITH) smithRails(load3.x, 2.1f)
        }
        SquatLoad.FRONT_BAR -> {
            barbell(load3)
            for (side in Side.entries) body.arm(side, load3.withZ(side.sign * 0.24f), basis.dir(1f, 0.4f, side.sign * 0.3f))
        }
        SquatLoad.GOBLET_DUMBBELL, SquatLoad.GOBLET_KETTLEBELL -> {
            for (side in Side.entries) body.arm(side, load3 + basis.u * 0.05f + V3.Z * (side.sign * 0.06f), basis.dir(0.1f, -1f, side.sign * 0.6f))
            if (load == SquatLoad.GOBLET_DUMBBELL) dumbbell(load3 - basis.u * 0.03f, axis = basis.u, headRadius = 0.075f)
            else kettlebell(load3 + basis.u * 0.05f, down = -basis.u, radius = 0.105f)
        }
        SquatLoad.BODYWEIGHT -> for (side in Side.entries) {
            body.armAngles(side, limbDir(Basis.UPRIGHT, side, lerp(25f, 88f, p), 4f), elbow = 6f, fold = V3.Y)
        }
    }
}

/**
 * A deadlift from the floor: the bar travels straight up over the middle of the feet, shoulders
 * just in front of it until lockout; [sumo] stands wide with the hands inside the knees.
 */
internal fun deadlift(sumo: Boolean) = Motion.rep(Track.rep(go = 1.0f, hold = 0.35f, back = 1.05f, rest = 0.45f), Camera(if (sumo) 42f else 22f)) { p ->
    val handZ = if (sumo) 0.16f else 0.27f
    val barX = if (sumo) 0.06f else 0.12f
    val plate = 0.225f
    val barY = lerp(plate, if (sumo) 0.74f else 0.8f, Ease.IN_OUT.apply(p))
    val lean = lerp(if (sumo) 40f else 62f, -2f, p)
    hingeOverBar(v(barX, barY), lean, ahead = lerp(if (sumo) 0.05f else 0.07f, -0.03f, p), handZ = handZ)
    body.standOn(if (sumo) 0.03f else -0.03f, width = if (sumo) 0.72f else 0.26f, out = if (sumo) 35f else 6f, pole = v(1f, 0.1f, if (sumo) 0.9f else 0.12f))
    barbell(v(barX, barY), plateRadius = plate)
    for (side in Side.entries) body.arm(side, v(barX, barY, side.sign * handZ), v(-0.3f, 0.2f, side.sign * 1f))
}

/**
 * A Romanian deadlift: knees soft, hips pushed back until the weight reaches mid-shin, back flat.
 * With dumbbells they hang in front of the thighs.
 */
internal fun romanianDeadlift(grip: Grip) = Motion.rep(Tempo.lowerFirst(), Camera(22f)) { p ->
    val handZ = if (grip == Grip.DUMBBELLS) 0.2f else 0.25f
    val barX = 0.13f
    val barY = lerp(0.8f, 0.38f, p)
    hingeOverBar(v(barX, barY), lerp(-2f, 72f, p), ahead = lerp(-0.03f, 0.06f, p), handZ = handZ)
    body.standOn(-0.03f, width = 0.24f, out = 4f, pole = v(1f, 0.1f, 0.1f))
    if (grip == Grip.DUMBBELLS) {
        for (side in Side.entries) {
            val hand = v(barX, barY, side.sign * handZ)
            body.arm(side, hand, v(-0.3f, 0.2f, side.sign * 1f))
            dumbbell(hand)
        }
    } else {
        barbell(v(barX, barY))
        for (side in Side.entries) body.arm(side, v(barX, barY, side.sign * handZ), v(-0.3f, 0.2f, side.sign * 1f))
    }
}

/**
 * Places the torso for a hinge holding a weight at [bar] with nearly straight arms: the spine
 * leans [lean] degrees and the shoulders sit [ahead] metres in front of the hands.
 */
private fun FrameBuilder.hingeOverBar(bar: V3, lean: Float, ahead: Float, handZ: Float) {
    val basis = Basis.UPRIGHT.pitch(lean)
    val lateral = abs(handZ - BodySize.SHOULDER_HALF)
    val shoulderY = bar.y + armReach(lateral, ahead, straightness = 0.985f)
    val toShoulder = basis.u * (BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP)
    val pelvis = v(bar.x + ahead, shoulderY) - toShoulder
    body.torso(pelvis.withZ(0f), basis, nod = -lean * 0.5f)
}

/** A good morning: bar on the upper back, knees soft, hinging until the trunk is near horizontal. */
internal fun goodMorning() = Motion.rep(Tempo.lowerFirst(), Camera(22f)) { p ->
    val lean = lerp(0f, 78f, p)
    val basis = Basis.UPRIGHT.pitch(lean)
    val ankleX = -0.02f
    val hipX = lerp(0.0f, -0.22f, p)
    val legReach = 0.84f
    val dx = hipX - ankleX
    val pelvis = v(hipX, BodySize.ANKLE + sqrt(legReach * legReach - dx * dx))
    body.torso(pelvis, basis, nod = -lean * 0.5f)
    body.standOn(ankleX, width = 0.28f, out = 8f)
    val bar = body.onChest(-0.13f, -0.06f)
    barbell(bar)
    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * 0.44f), basis.dir(-0.7f, -1f, side.sign * 0.4f))
}

/**
 * A cable pull-through: facing away from a low pulley, the rope between the legs; hinge back,
 * then drive the hips forward to stand tall.
 */
internal fun pullThrough() = Motion.rep(Tempo.lowerFirst(), Camera(22f)) { p ->
    val lean = lerp(2f, 70f, p)
    val basis = Basis.UPRIGHT.pitch(lean)
    val ankleX = 0f
    val hipX = lerp(0.02f, -0.2f, p)
    val legReach = lerp(0.85f, 0.78f, p)
    val dx = hipX - ankleX
    body.torso(v(hipX, BodySize.ANKLE + sqrt(legReach * legReach - dx * dx)), basis, nod = -lean * 0.4f)
    body.standOn(ankleX, width = 0.42f, out = 15f, pole = v(1f, 0.1f, 0.4f))
    // Straight arms hang towards the pulley behind, hands together on the rope between the legs.
    val pulley = v(-0.95f, 0.2f)
    val shoulders = body.shoulders()
    val arms = lerp(-V3.Y, (pulley - shoulders).unit(), lerp(0.25f, 0.45f, p)).unit()
    for (side in Side.entries) body.armAngles(side, (arms + V3.Z * (-side.sign * 0.12f)).unit(), 6f, fold = V3.Y)
    val hands = (body.handOf(Side.NEAR) + body.handOf(Side.FAR)) * 0.5f
    cable(pulley, hands)
    add(Rod(v(-0.95f, 0f), v(-0.95f, 1.9f), 0.05f, Ink.FRAME, fit = false, bias = -0.5f))
}

/**
 * A two-hand kettlebell swing: hike the bell back between the legs with a flat back, then snap
 * the hips forward so it floats to chest height on straight arms.
 */
internal fun kettlebellSwing() = Motion.rep(Track.rep(go = 0.55f, hold = 0.12f, back = 0.6f, rest = 0.1f, goEase = Ease.OUT, backEase = Ease.IN), Camera(22f)) { p ->
    val lean = lerp(55f, -3f, p)
    val basis = Basis.UPRIGHT.pitch(lean)
    body.torso(v(lerp(-0.24f, 0.01f, p), lerp(0.79f, 0.915f, p)), basis, nod = -lean * 0.5f)
    body.standOn(0f, width = 0.42f, out = 14f, pole = v(1f, 0.1f, 0.35f))
    val swing = lerp(-28f, 92f, p)
    val dir = dirXY(swing - 90f)
    val grip = body.shoulders() + dir * 0.56f
    val elbows = dirXY(swing + 180f)
    for (side in Side.entries) body.arm(side, grip.withZ(side.sign * 0.045f), elbows + V3.Z * (side.sign * 0.3f))
    kettlebell(grip, down = dir)
}

/** Lunge set-ups. */
internal enum class Lunge {
    /** Step forward into the lunge and push back to standing. */
    FORWARD,

    /** Split squat: feet stay in a split stance, the body goes straight down. */
    SPLIT,

    /** Rear foot on a bench behind. */
    BULGARIAN,
}

/**
 * Lunges and split squats: the front (near) knee bends to about 90° while the rear knee drops
 * towards the floor, trunk upright. [grip] sets the load: dumbbells at the sides, a bar on the back
 * (SMITH adds the rails), or hands on the hips.
 */
internal fun lunge(kind: Lunge, grip: Grip) = Motion.rep(
    if (kind == Lunge.FORWARD) Track.rep(go = 1.3f, hold = 0.2f, back = 1.1f, rest = 0.4f) else Tempo.lowerFirst(),
    Camera(24f),
) { p ->
    val front: Float
    val pelvis: V3
    when (kind) {
        Lunge.FORWARD -> {
            val step = Ease.IN_OUT.apply((p / 0.42f).coerceIn(0f, 1f))
            val sink = Ease.IN_OUT.apply(((p - 0.3f) / 0.7f).coerceIn(0f, 1f))
            front = lerp(0.02f, 0.64f, step)
            pelvis = v(lerp(0f, 0.27f, step), lerp(lerp(BodySize.STAND, 0.86f, step), 0.48f, sink))
            body.torso(pelvis, Basis.UPRIGHT.pitch(4f))
            body.stand(Side.NEAR, front, z = 0.11f, floor = 0.08f * sin(PI.toFloat() * step) * (1 - sink))
            body.stand(Side.FAR, 0f, z = -0.11f, lift = lerp(0f, 52f, Ease.IN_OUT.apply(((p - 0.15f) / 0.85f).coerceIn(0f, 1f))), pole = v(0.4f, -1f, 0f))
        }
        Lunge.SPLIT -> {
            front = 0.3f
            pelvis = v(-0.03f, lerp(0.83f, 0.47f, p))
            body.torso(pelvis, Basis.UPRIGHT.pitch(4f))
            body.stand(Side.NEAR, front, z = 0.11f)
            body.stand(Side.FAR, -0.38f, z = -0.11f, lift = lerp(42f, 58f, p), pole = v(0.4f, -1f, 0f))
        }
        Lunge.BULGARIAN -> {
            front = 0.3f
            pelvis = v(-0.05f, lerp(0.84f, 0.5f, p))
            body.torso(pelvis, Basis.UPRIGHT.pitch(lerp(4f, 12f, p)))
            body.stand(Side.NEAR, front, z = 0.11f)
            body.leg(Side.FAR, v(-0.55f, Kit.BENCH_TOP + 0.06f, -0.1f), v(0.3f, -1f, 0f), Foot(dirXY(192f), dirXY(282f)))
            flatBench(-1.3f, -0.45f)
        }
    }
    when (grip) {
        Grip.DUMBBELLS -> for (side in Side.entries) {
            body.hang(side, forward = 0f, out = 8f)
            dumbbell(body.handOf(side), axis = V3.X)
        }
        Grip.BARBELL, Grip.SMITH -> {
            val bar = body.onChest(-0.13f, -0.06f)
            barbell(bar)
            for (side in Side.entries) body.arm(side, bar.withZ(side.sign * 0.44f), body.chest.dir(-0.7f, -1f, side.sign * 0.4f))
            if (grip == Grip.SMITH) smithRails(bar.x, 2.1f)
        }
        else -> for (side in Side.entries) body.arm(side, body.onHips(0.02f, 0.12f, side.sign * 0.2f), v(-0.6f, 0f, side.sign * 1f))
    }
}

/**
 * Dumbbell step-up: the near foot on a box drives the body up until standing on it, the far knee
 * rising; then back down.
 */
internal fun stepUp() = Motion.rep(Track.rep(go = 1.1f, hold = 0.25f, back = 1.1f, rest = 0.35f), Camera(24f)) { p ->
    val boxTop = 0.45f
    box(v(0.1f, 0f, -0.3f), v(0.56f, boxTop, 0.3f), Ink.PAD, bias = -0.2f)
    val pelvis = v(lerp(-0.08f, 0.27f, Ease.IN_OUT.apply(p)), lerp(0.85f, boxTop + 0.88f, Ease.IN_OUT.apply(((p - 0.1f) / 0.9f).coerceIn(0f, 1f))))
    body.torso(pelvis, Basis.UPRIGHT.pitch(lerp(16f, 3f, p)))
    body.stand(Side.NEAR, 0.3f, z = 0.11f, floor = boxTop)
    // The far foot pushes off, then its knee drives up beside the box.
    val lift = Ease.IN_OUT.apply(((p - 0.12f) / 0.7f).coerceIn(0f, 1f))
    val floorAnkle = v(-0.2f, BodySize.ANKLE, -0.11f)
    val kneeUp = pelvis + v(0.16f, -0.6f, -0.11f)
    val ankle = lerp(floorAnkle, kneeUp, lift) + V3.Y * (0.08f * sin(PI.toFloat() * lift))
    body.leg(Side.FAR, ankle, v(1f, 0.2f, 0f), Foot(dirXY(lerp(0f, -35f, lift)), dirXY(lerp(90f, 55f, lift))))
    for (side in Side.entries) {
        body.hang(side, forward = 2f, out = 8f)
        dumbbell(body.handOf(side), axis = V3.X)
    }
}

/** Wall sit: back flat against a wall, thighs level, breathing while holding. */
internal fun wallSit() = Motion.cycle(3.2f, camera = Camera(24f)) { phase ->
    val breath = sin(phase * 2 * PI.toFloat())
    box(v(-0.48f, 0f, -0.6f), v(-0.36f, 1.9f, 0.6f), Ink.FRAME, fit = false, bias = -0.6f)
    body.torso(v(-0.23f, 0.505f), bend = -1.5f * breath, shrug = 0.006f * breath)
    for (side in Side.entries) body.leg(side, v(0.2f, BodySize.ANKLE, side.sign * 0.13f), v(1f, 0.2f, 0f), Foot.flat(side, 6f))
    for (side in Side.entries) body.arm(side, body.legOf(side).knee + v(-0.14f, 0.075f, side.sign * 0.03f), v(-0.3f, -1f, side.sign * 0.6f))
}
