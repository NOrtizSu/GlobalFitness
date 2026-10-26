package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Core and full-body patterns: crunches, leg raises, planks, twists, side bends, mountain
// climbers, burpees, Turkish get-ups, clean and press, carries and the neck machine.

private const val TAU = 2 * PI.toFloat()

/** Crunch variants. */
internal enum class Crunch { FLOOR, SIT_UP, CABLE, MACHINE }

/**
 * Crunches curl the upper back off the floor (the lower back stays down); sit-ups bring the whole
 * trunk up; the cable crunch kneels and curls down against a rope; the machine curls seated.
 */
internal fun crunch(kind: Crunch) = Motion.rep(Tempo.liftFirst(), Camera(if (kind == Crunch.CABLE || kind == Crunch.MACHINE) 26f else -24f)) { p ->
    when (kind) {
        Crunch.FLOOR, Crunch.SIT_UP -> {
            val sitUp = kind == Crunch.SIT_UP
            val pelvis = v(0f, 0.115f)
            body.torso(
                pelvis,
                if (sitUp) Basis.SUPINE.pitch(lerp(0f, 70f, p)) else Basis.SUPINE,
                bend = if (sitUp) lerp(0f, 12f, p) else lerp(0f, 40f, p),
                nod = lerp(8f, 20f, p),
            )
            body.standOn(0.42f, width = 0.26f, out = 6f, pole = v(0.3f, 1f, 0.1f))
            mat()
            if (sitUp) {
                for (side in Side.entries) body.arm(side, body.onChest(0.14f, -0.12f, side.sign * 0.06f), body.chest.dir(0f, -1f, side.sign * 1f))
            } else {
                behindHead()
            }
        }
        Crunch.CABLE -> {
            val knee = v(0f, BodySize.KNEE_RADIUS)
            val hip = knee + dirXY(76f) * BodySize.THIGH
            body.torso(hip, Basis.UPRIGHT.pitch(lerp(28f, 42f, p)), bend = lerp(4f, 52f, p), nod = lerp(0f, 20f, p))
            for (side in Side.entries) body.leg(side, v(-0.42f, 0.17f, side.sign * 0.11f), v(1f, -1.5f, 0f), Foot(dirXY(250f), dirXY(340f)))
            val pulley = v(0.62f, 2.0f)
            for (side in Side.entries) body.arm(side, body.onChest(0.13f, 0.2f, side.sign * 0.08f), body.chest.dir(0.2f, -1f, side.sign * 0.6f))
            val knot = (body.handOf(Side.NEAR) + body.handOf(Side.FAR)) * 0.5f + (pulley - body.neck).unit() * 0.12f
            add(Cord(listOf(knot, body.handOf(Side.NEAR)), 0.026f, Ink.CABLE))
            add(Cord(listOf(knot, body.handOf(Side.FAR)), 0.026f, Ink.CABLE))
            cable(pulley, knot, fit = false)
            add(Block(v(0.75f, 1.05f), Basis.UPRIGHT, v(0.1f, 1.05f, 0.24f), Ink.FRAME, fit = false, bias = -0.6f))
            mat()
        }
        Crunch.MACHINE -> {
            val seatTop = 0.5f
            body.torso(v(0f, seatTop + SEAT_HIP), Basis.UPRIGHT.pitch(lerp(0f, 16f, p)), bend = lerp(0f, 34f, p), nod = lerp(0f, 14f, p))
            machineSeat(0f, seatTop)
            body.standOn(0.38f, width = 0.28f, out = 6f)
            for (side in Side.entries) {
                val hand = body.onChest(0.08f, 0.1f, side.sign * 0.2f)
                body.arm(side, hand, body.chest.dir(0.3f, -1f, side.sign * 0.4f))
                add(Rod(hand, body.onChest(-0.15f, 0.12f, side.sign * 0.22f), 0.025f, Ink.METAL))
                roller(body.shoulder(side) + body.chest.dir(0.04f, 0.06f, 0f), length = 0.12f, radius = 0.045f)
            }
            add(Rod(body.onChest(-0.15f, 0.12f, 0.24f), v(-0.35f, 1.0f, 0.24f), 0.03f, Ink.FRAME, bias = -0.2f))
            add(Rod(v(-0.35f, 0f, 0.24f), v(-0.35f, 1.05f, 0.24f), 0.04f, Ink.FRAME, bias = -0.4f))
        }
    }
}

/** A thin exercise mat under a figure lying on the floor. */
internal fun FrameBuilder.mat() {
    box(v(-0.95f, 0f, -0.35f), v(0.7f, 0.012f, 0.35f), Ink.PAD, fit = false, bias = -0.8f)
}

/** Hands behind the head, elbows out. */
private fun FrameBuilder.behindHead() {
    val pose = body
    for (side in Side.entries) {
        val target = pose.onChest(-0.07f, 0.2f, side.sign * 0.07f)
        pose.arm(side, target, pose.chest.dir(0.3f, 0.2f, side.sign * 1f))
    }
}

/** Leg raises: hanging from a bar, or lying on the floor; straight legs rise to level / vertical. */
internal fun legRaise(hanging: Boolean) = Motion.rep(Tempo.liftFirst(), Camera(if (hanging) 26f else -24f)) { p ->
    if (hanging) {
        val barY = 2.3f
        val shoulder = v(-0.02f, barY - armReach(0.3f - BodySize.SHOULDER_HALF, 0.02f, 0.985f))
        hangFrom(shoulder, lean = lerp(0f, -10f, p))
        for (side in Side.entries) body.arm(side, v(0f, barY, side.sign * 0.3f), v(-0.1f, -1f, side.sign * 1f))
        val raise = lerp(4f, 90f, p)
        for (side in Side.entries) body.legAngles(side, limbDir(Basis.UPRIGHT, side, raise, 2f), lerp(6f, 4f, p), fold = dirXY(raise - 180f))
        pullUpBar(0f, barY)
    } else {
        body.torso(v(0f, 0.115f), Basis.SUPINE, nod = 6f)
        val raise = lerp(4f, 84f, p)
        for (side in Side.entries) body.legAngles(side, v(cos(rad(raise)), sin(rad(raise)), side.sign * 0.02f), 4f, fold = dirXY(raise - 90f))
        for (side in Side.entries) body.arm(side, v(-0.08f, BodySize.FIST_RADIUS, side.sign * 0.27f), v(0f, 1f, side.sign * 0.3f))
        mat()
    }
}

/** Plank variants. */
internal enum class Plank { FRONT, SIDE, COPENHAGEN }

/**
 * Planks: a still hold with slow breathing — the forearm plank seen from the side; the side plank
 * and the Copenhagen plank (top leg on a bench) seen from the front.
 */
internal fun plank(kind: Plank) = Motion.cycle(3.6f, camera = if (kind == Plank.FRONT) Camera(22f) else Camera(84f, 6f)) { phase ->
    val breath = sin(phase * TAU)
    if (kind == Plank.FRONT) {
        val ankle = v(-0.86f, 0.185f)
        val shoulderY = BodySize.UPPER_ARM + BodySize.ELBOW_RADIUS + 0.004f
        val line = BodySize.THIGH + BodySize.SHIN - 0.01f + BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP
        val angle = deg(asin((shoulderY - ankle.y) / line)) + 0.35f * breath
        plankBody(ankle, angle, nod = 6f)
        val shoulders = body.shoulders()
        for (side in Side.entries) {
            val elbow = v(shoulders.x, BodySize.ELBOW_RADIUS, side.sign * BodySize.SHOULDER_HALF)
            val hand = elbow + v(sqrt(BodySize.FOREARM * BodySize.FOREARM - 0.06f * 0.06f), 0f, -side.sign * 0.06f)
            body.arm(side, hand.withY(BodySize.FIST_RADIUS), v(-0.3f, -1f, side.sign * 0.1f))
        }
        mat()
        return@cycle
    }
    sidePlank(kind == Plank.COPENHAGEN, breath)
}

/**
 * Side-lying plank on the far forearm, seen from the front: the body is a straight line from the
 * feet (on the floor, or the top foot on a bench for the Copenhagen plank) to the head.
 */
private fun FrameBuilder.sidePlank(copenhagen: Boolean, breath: Float) {
    val feet = if (copenhagen) v(0f, Kit.BENCH_TOP + 0.05f, 0.62f) else v(0f, 0.07f, 0.62f)
    val legLine = BodySize.THIGH + BodySize.SHIN - 0.01f
    val spine = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP
    // Body angle that puts the far shoulder an upper arm above its elbow on the floor.
    val shoulderY = BodySize.UPPER_ARM + BodySize.ELBOW_RADIUS + 0.004f
    val angle = solve(-30f, 40f) { a ->
        val basis = Basis.UPRIGHT.roll(a - 90f)
        val pelvis = feet + basis.u * legLine - basis.l * (if (copenhagen) BodySize.HIP_HALF else 0f)
        (pelvis + basis.u * spine - basis.l * BodySize.SHOULDER_HALF).y - shoulderY
    } + 0.3f * breath
    val basis = Basis.UPRIGHT.roll(angle - 90f)
    val pelvis = feet + basis.u * legLine - basis.l * (if (copenhagen) BodySize.HIP_HALF else 0f)
    body.torso(pelvis, basis, sideways = 4f, nod = -6f)
    if (copenhagen) {
        body.leg(Side.NEAR, feet, basis.f, Foot(basis.f, basis.u))
        body.leg(Side.FAR, feet - basis.l * 0.13f, basis.f, Foot(basis.f, basis.u))
        box(v(-0.25f, 0f, 0.42f), v(0.25f, Kit.BENCH_TOP, 0.95f), Ink.PAD, bias = -0.4f)
    } else {
        for (side in Side.entries) body.leg(side, feet + basis.l * (side.sign * 0.06f + 0.03f), basis.f, Foot(basis.f, basis.u))
    }
    val far = body.shoulder(Side.FAR)
    val elbow = v(far.x, BodySize.ELBOW_RADIUS, far.z)
    body.arm(Side.FAR, elbow + v(BodySize.FOREARM, 0f, 0f).withY(BodySize.FIST_RADIUS - BodySize.ELBOW_RADIUS), v(-0.3f, -1f, 0f))
    body.arm(Side.NEAR, pelvis + basis.l * 0.19f + basis.u * 0.05f, basis.l + basis.u * 0.6f - basis.f * 0.3f)
}

/** Russian twist: sitting back in a V with the feet up, rotating the trunk from side to side, hands together. */
internal fun russianTwist() = Motion.cycle(2.6f, camera = Camera(62f, 16f)) { phase ->
    val twist = 34f * sin(phase * TAU)
    body.torso(v(0f, 0.12f), Basis.UPRIGHT.pitch(-38f), twist = twist, nod = 10f)
    for (side in Side.entries) body.leg(side, v(0.48f, 0.2f, side.sign * 0.09f), v(0.2f, 1f, 0f))
    val hands = body.onChest(0.36f, -0.2f)
    for (side in Side.entries) body.arm(side, hands + body.chest.l * (side.sign * 0.05f), body.chest.dir(0f, -1f, side.sign * 1f))
}

/** Cable woodchop, seen from the front: a diagonal pull from high on the near side to low past the far knee, the trunk turning with it. */
internal fun woodchop() = Motion.rep(Tempo.liftFirst(), Camera(70f)) { p ->
    val turn = lerp(42f, -34f, p)
    body.torso(v(-0.03f, lerp(0.9f, 0.78f, p)), Basis.UPRIGHT.pitch(lerp(6f, 22f, p)), twist = turn)
    body.standOn(0f, width = 0.5f, out = 14f, pole = v(1f, 0.1f, 0.3f))
    val hands = lerp(v(0.18f, 1.62f, 0.42f), v(0.3f, 0.76f, -0.2f), p)
    for (side in Side.entries) body.arm(side, hands + V3.Z * (side.sign * 0.04f), v(-0.3f, -1f, side.sign * 0.5f))
    add(Rod(hands - V3.Y * 0.05f, hands + V3.Y * 0.05f, 0.02f, Ink.METAL))
    cable(v(-0.1f, 2.05f, 0.95f), hands, fit = false)
}

/** Rotary torso machine: seated with the legs fixed, the trunk turns against the chest pad; seen from above the front. */
internal fun rotaryTorso() = Motion.rep(Tempo.liftFirst(), Camera(60f, 42f)) { p ->
    val seatTop = 0.5f
    body.torso(v(0f, seatTop + SEAT_HIP), twist = lerp(-4f, 40f, p))
    machineSeat(0f, seatTop)
    body.standOn(0.4f, width = 0.24f, out = 4f)
    val padAt = body.onChest(0.24f, -0.16f)
    add(Block(padAt, body.chest, v(0.03f, 0.13f, 0.17f), Ink.PAD, bias = 0.15f))
    for (side in Side.entries) {
        val hand = body.onChest(0.3f, -0.1f, side.sign * 0.2f)
        body.arm(side, hand, body.chest.dir(-0.2f, -1f, side.sign * 1f))
        add(Rod(hand - body.chest.u * 0.06f, hand + body.chest.u * 0.06f, 0.02f, Ink.METAL))
    }
}

/** Dumbbell side bend, seen from the front: bending sideways towards the dumbbell and back up. */
internal fun sideBend() = Motion.rep(Tempo.lowerFirst(), Camera(84f)) { p ->
    body.torso(v(0f, BodySize.STAND), sideways = lerp(-6f, 26f, p))
    body.standOn(0f, width = 0.32f)
    body.armAngles(Side.NEAR, v(0.03f, -1f, 0.08f), 6f, fold = V3.X)
    dumbbell(body.handOf(Side.NEAR), axis = V3.X)
    body.arm(Side.FAR, body.head(-0.04f, 0.02f, -0.08f), body.chest.dir(0f, 0.4f, -1f))
}

/** A point by the head in the head's own axes, from the head's centre. */
internal fun Rig.head(forward: Float, up: Float, lateral: Float): V3 =
    neck + chest.u * BodySize.NECK + chest.dir(forward, up, lateral)

/**
 * Mountain climbers: from the top of a push-up, the knees drive in to the chest in turn. The
 * shoulders stay over the hands while the hips rise a little with each drive, so the knee clears
 * the floor as the thigh swings under the body.
 */
internal fun mountainClimber() = Motion.cycle(1.3f, camera = Camera(22f)) { phase ->
    val ankle = v(-0.86f, 0.185f)
    val line = BodySize.THIGH + BodySize.SHIN - 0.01f + BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP
    val angle = deg(asin((0.55f - ankle.y) / line))
    val shoulders = ankle + dirXY(angle) * line
    val drives = Side.entries.map { side ->
        val local = (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f
        sin(local * PI.toFloat()).let { it * it }
    }
    val torso = angle - 8f * drives.max()
    val spine = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP
    body.torso(shoulders - dirXY(torso) * spine, Basis.UPRIGHT.pitch(90f - torso), nod = 10f)
    for (side in Side.entries) {
        val drive = drives[side.ordinal]
        val planted = ankle.withZ(side.sign * 0.1f)
        val onToes = onToes(angle)
        if (drive < 0.01f) {
            body.leg(side, planted, v(0f, -1f, 0f), onToes)
        } else {
            // The thigh swings from the line of the planted leg to forward under the chest; the knee
            // bends first, kicking the foot up behind, so it clears the floor as it comes through.
            val back = planted - body.hip(side)
            val from = angleXY(back).let { if (it < 0f) it + 360f else it }
            val thigh = lerp(from, 335f, drive)
            val flex = 135f * sin(PI.toFloat() / 2 * (drive / 0.6f).coerceAtMost(1f))
            val point = lerp(angle - 85f + 360f, thigh - flex + 40f, Ease.IN_OUT.apply((drive / 0.5f).coerceAtMost(1f)))
            // A little out to the side as it passes under the hips.
            val out = rad(22f * sin(PI.toFloat() * drive))
            val dir = v(cos(rad(thigh)) * cos(out), sin(rad(thigh)) * cos(out), side.sign * sin(out))
            body.legAngles(side, dir, flex, fold = dirXY(thigh - 90f), foot = Foot(dirXY(point), dirXY(point + 90f)))
        }
        body.arm(side, v(shoulders.x, BodySize.FIST_RADIUS, side.sign * 0.27f), v(-1f, 0.2f, side.sign * 0.8f))
    }
}

/** [ankle] raised just enough that a foot oriented as [foot] stays on or above the floor. */
internal fun footAbove(ankle: V3, foot: Foot): V3 {
    val sole = ankle.y - foot.up.y * BodySize.SOLE
    val lowest = minOf(sole + foot.dir.y * BodySize.TOE, sole - foot.dir.y * BodySize.HEEL)
    return if (lowest >= BodySize.FOOT_RADIUS) ankle else ankle.withY(ankle.y + BodySize.FOOT_RADIUS - lowest)
}

/**
 * A key pose of the burpee: pelvis, trunk lean, ankle placement and the hands — planted on the
 * floor ([hands] in world space when [onFloor]) or held relative to the shoulders.
 */
private class KeyPose(
    val time: Float,
    val pelvis: V3,
    val lean: Float,
    val hands: V3,
    val ankle: V3,
    val onFloor: Boolean = false,
    /** 0: feet flat; 1: on the toes behind a horizontal body. */
    val toes: Float = 0f,
)

/** Shoulder line of a key pose. */
private fun KeyPose.shoulders(): V3 = pelvis + Basis.UPRIGHT.pitch(lean).u * (BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP)

/**
 * The pose at [time] between [keys] (times increasing, the last equal in pose to the first). Hands
 * planted in both keys stay put; otherwise they swing around the shoulders (direction and reach
 * blended separately), so the arms never fold through the body on the way.
 */
private fun blendKeys(keys: List<KeyPose>, time: Float): Pair<KeyPose, V3> {
    var i = 1
    while (i < keys.size - 1 && keys[i].time < time) i++
    val a = keys[i - 1]
    val b = keys[i]
    val t = Ease.IN_OUT.apply(((time - a.time) / (b.time - a.time)).coerceIn(0f, 1f))
    val k = KeyPose(time, lerp(a.pelvis, b.pelvis, t), lerp(a.lean, b.lean, t), V3.ZERO, lerp(a.ankle, b.ankle, t), toes = lerp(a.toes, b.toes, t))
    val hands = if (a.onFloor && b.onFloor) {
        lerp(a.hands, b.hands, t)
    } else {
        val from = if (a.onFloor) a.hands - a.shoulders() else a.hands
        val to = if (b.onFloor) b.hands - b.shoulders() else b.hands
        val angle = lerp(angleXY(from), angleXY(to), t)
        k.shoulders() + dirXY(angle) * lerp(from.length, to.length, t)
    }
    return Pair(k, hands)
}

/**
 * Burpee: squat down with the hands on the floor, jump the feet back to a plank, jump them in again
 * and jump up with the arms overhead.
 */
internal fun burpee(): Motion {
    val stand = v(0f, BodySize.STAND)
    val down = v(0.06f, -0.5f)
    val up = v(0.02f, 0.56f)
    val squat = v(-0.2f, 0.48f)
    val floorHands = v(0.3f, BodySize.FIST_RADIUS)
    val plankAnkle = v(-0.86f, 0.185f)
    val plankPelvis = plankAnkle + dirXY(17f) * (BodySize.THIGH + BodySize.SHIN - 0.01f)
    val flat = v(0f, BodySize.ANKLE)
    val keys = listOf(
        KeyPose(0f, stand, 3f, down, flat),
        KeyPose(0.45f, squat, 78f, floorHands, flat, onFloor = true),
        KeyPose(0.85f, plankPelvis, 73f, floorHands, plankAnkle, onFloor = true, toes = 1f),
        KeyPose(1.15f, plankPelvis, 73f, floorHands, plankAnkle, onFloor = true, toes = 1f),
        KeyPose(1.55f, squat, 78f, floorHands, flat, onFloor = true),
        KeyPose(2.25f, v(0f, 1.1f), 0f, up, v(0f, 0.26f)),
        KeyPose(2.8f, v(0f, 0.86f), 6f, down, flat),
        KeyPose(3.15f, stand, 3f, down, flat),
    )
    return Motion.cycle(3.15f, camera = Camera(22f)) { phase ->
        val (k, hands) = blendKeys(keys, phase * 3.15f)
        body.torso(k.pelvis, Basis.UPRIGHT.pitch(k.lean), nod = -k.lean * 0.4f)
        for (side in Side.entries) {
            // The feet jump between the squat and the plank: they leave the floor on the way, toes
            // pointed, then land flat or on the toes behind the plank; never below the floor.
            val inAir = 4 * k.toes * (1 - k.toes)
            val point = lerp(lerp(0f, 17f - 85f, k.toes), -75f, inAir)
            val foot = Foot(dirXY(point), dirXY(point + 90f))
            body.leg(side, footAbove(k.ankle + V3.Y * (0.1f * inAir), foot).withZ(side.sign * 0.12f), v(1f, 0.2f, side.sign * 0.2f), foot)
            body.arm(side, hands.withZ(side.sign * 0.26f), v(-1f, -0.2f, side.sign * 0.5f))
        }
    }
}

/**
 * Turkish get-up, simplified to its first half and back: from lying with the kettlebell pressed up,
 * onto the far elbow, then the far hand, hips up into a bridge, and back down; the kettlebell arm
 * stays vertical throughout.
 */
internal fun turkishGetUp() = Motion.rep(Track.rep(go = 2.2f, hold = 0.4f, back = 2.0f, rest = 0.5f), Camera(40f, 8f)) { p ->
    val sit = Ease.IN_OUT.apply((p / 0.7f).coerceIn(0f, 1f))
    val bridge = Ease.IN_OUT.apply(((p - 0.65f) / 0.35f).coerceIn(0f, 1f))
    val basis = Basis.SUPINE.pitch(lerp(0f, 62f, sit) - 14f * bridge).roll(lerp(0f, -22f, sit))
    val pelvis = v(0f, lerp(0.115f, 0.3f, bridge), 0f)
    body.torso(pelvis, basis, nod = lerp(10f, 0f, sit))
    body.stand(Side.NEAR, 0.38f, z = 0.14f, pole = v(0.3f, 1f, 0.2f))
    val farAnkle = v(lerp(0.82f, 0.68f, bridge), 0.09f, -0.26f)
    val legLine = farAnkle - body.hip(Side.FAR)
    val toes = dirXY(angleXY(legLine) + 90f)
    body.leg(Side.FAR, farAnkle, v(0.2f, 1f, 0f), Foot(toes, rotate(toes, V3.Z, rad(90f))))
    val support = body.shoulder(Side.FAR)
    body.arm(Side.FAR, v(support.x - 0.06f, BodySize.FIST_RADIUS, support.z - lerp(0.36f, 0.12f, sit)), v(0.3f, 0.6f, -1f))
    val top = body.shoulder(Side.NEAR) + V3.Y * 0.565f
    body.arm(Side.NEAR, top, v(0f, -0.2f, 1f))
    kettlebell(top, down = v(-0.3f, -0.4f, 0.1f), radius = 0.1f)
    mat()
}

/**
 * Kettlebell clean and press with one arm: from between the legs the bell is cleaned to the rack
 * at the shoulder, pressed overhead, lowered back to the rack and swung back down.
 */
internal fun cleanAndPress() = Motion.rep(Track.keys(0f to 0f, 0.35f to 0f, 0.95f to 1f, 1.25f to 1f, 1.95f to 2f, 2.3f to 2f, 3.0f to 1f, 3.3f to 1f, 4.0f to 0f), Camera(26f)) { stage ->
    val clean = stage.coerceIn(0f, 1f)
    val press = (stage - 1f).coerceIn(0f, 1f)
    val lean = lerp(30f, 2f, clean)
    body.torso(v(lerp(-0.12f, 0f, clean), lerp(0.8f, BodySize.STAND, clean)), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.5f)
    body.standOn(0f, width = 0.4f, out = 12f, pole = v(1f, 0.1f, 0.3f))
    val s = body.shoulder(Side.NEAR)
    val hang = s + v(0.06f, -0.56f, -0.1f)
    val rack = body.onChest(0.13f, -0.03f, 0.1f)
    val overhead = s + v(-0.02f, 0.565f, 0.02f)
    val hand = if (stage <= 1f) {
        lerp(hang, rack, clean) + V3.X * (0.12f * sin(PI.toFloat() * clean))
    } else {
        lerp(rack, overhead, press)
    }
    body.arm(Side.NEAR, hand, if (stage <= 1f) v(-0.4f, -1f, 0.3f) else v(0.2f, -1f, lerp(0.3f, 1f, press)))
    body.armAngles(Side.FAR, limbDir(body.chest, Side.FAR, 8f, 24f), 12f, fold = body.chest.f)
    val down = when {
        stage <= 1f -> lerp(-V3.Y, v(-0.6f, -0.6f, 0f), clean)
        else -> lerp(v(-0.6f, -0.6f, 0f), v(-0.35f, -0.6f, 0f), press)
    }
    kettlebell(hand, down = down)
}

/**
 * A walking step for the near or far leg at [phase] (0..1) of the stride: stance with the foot on
 * the floor moving back from [reach] to -[reach], then swing forward with the foot lifted [lift].
 */
internal fun FrameBuilder.gaitLeg(side: Side, phase: Float, reach: Float, lift: Float, stance: Float, floor: Float = 0f, heelKick: Float = 0f) {
    val z = side.sign * 0.1f
    if (phase < stance) {
        val s = phase / stance
        val x = lerp(reach, -reach, s)
        // Heel strike with the toes up, flat, then rolling onto the toes.
        val angle = when {
            s < 0.15f -> lerp(-10f, 0f, s / 0.15f)
            s > 0.45f -> lerp(0f, 32f, (s - 0.45f) / 0.55f)
            else -> 0f
        }
        val ankle = if (angle >= 0f) liftedAnkle(x, floor, angle) else v(x, floor + BodySize.ANKLE)
        val a = rad(angle)
        body.leg(side, ankle.withZ(z), v(1f, 0.1f, 0f), Foot(v(cos(a), -sin(a)), v(sin(a), cos(a))))
    } else {
        // From the toe-off position back to the next heel strike, lifted on the way (and kicked up
        // behind when running).
        val s = (phase - stance) / (1 - stance)
        val from = liftedAnkle(-reach, floor, 32f)
        val to = v(reach, floor + BodySize.ANKLE)
        val arc = sin(PI.toFloat() * s)
        val ankle = lerp(from, to, Ease.IN_OUT.apply(s)) + V3.Y * (lift * arc + heelKick * arc * arc * (1 - s)) - V3.X * (heelKick * 0.4f * arc)
        val a = rad(lerp(32f, -10f, s))
        body.leg(side, ankle.withZ(z), v(1f, 0.1f, 0f), Foot(v(cos(a), -sin(a)), v(sin(a), cos(a))))
    }
}

/** Farmer's carry: walking tall with a heavy dumbbell in each hand, shoulders set. */
internal fun farmersCarry() = Motion.cycle(2.2f, cycles = 2, camera = Camera(26f)) { phase ->
    val bob = cos(phase * TAU * 2)
    body.torso(v(0f, 0.875f + 0.012f * bob), Basis.UPRIGHT.pitch(2f), shrug = 0.02f)
    for (side in Side.entries) {
        gaitLeg(side, (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f, reach = 0.25f, lift = 0.07f, stance = 0.6f)
        body.hang(side, forward = 2f * sin((phase + if (side == Side.NEAR) 0f else 0.5f) * TAU), out = 9f)
        dumbbell(body.handOf(side), axis = V3.X, headRadius = 0.08f)
    }
}

/** Neck machine: seated with the forehead on the pad, the neck bending forward against it and back. */
internal fun neckMachine() = Motion.rep(Tempo.liftFirst(), Camera(26f)) { p ->
    val seatTop = 0.5f
    seated(seatTop = seatTop, recline = 0f, footX = 0.4f, width = 0.3f, nod = lerp(-16f, 30f, p), drawSeat = false)
    machineSeat(0f, seatTop)
    val pose = body
    val padAt = pose.onChest(0f, 0f) + Basis.UPRIGHT.pitch(lerp(-16f, 30f, p)).let { it.u * BodySize.NECK + it.f * (BodySize.HEAD_RADIUS + 0.05f) }
    val headBasis = Basis.UPRIGHT.pitch(lerp(-16f, 30f, p))
    add(Block(padAt, headBasis, v(0.025f, 0.065f, 0.09f), Ink.PAD, bias = 0.2f))
    add(Rod(padAt + headBasis.f * 0.03f, v(0.05f, 1.75f), 0.025f, Ink.FRAME, fit = false, bias = -0.2f))
    for (side in Side.entries) body.arm(side, body.onHips(0.12f, 0.02f, side.sign * 0.28f), v(-0.3f, -1f, side.sign * 0.6f))
}
