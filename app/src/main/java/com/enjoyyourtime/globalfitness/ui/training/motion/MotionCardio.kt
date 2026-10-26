package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Cardio machines: treadmill, bikes, elliptical, rower, stair climber and ski erg. Steady cycles
// drive these patterns with a phase (0..1) rather than a repetition.

private const val TAU = 2 * PI.toFloat()

/** Running on a treadmill: two strides per loop with a flight phase, arms swinging bent; the belt moves. */
internal fun treadmillRun() = Motion.cycle(1.44f, cycles = 2, camera = Camera(22f)) { phase ->
    val deck = 0.18f
    val reach = 0.3f
    val stance = 0.38f
    body.torso(v(0f, deck + 0.85f + 0.025f * cos(phase * TAU * 2)), Basis.UPRIGHT.pitch(9f), nod = -8f)
    for (side in Side.entries) {
        val legPhase = (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f
        gaitLeg(side, legPhase, reach = reach, lift = 0.1f, stance = stance, floor = deck, heelKick = 0.22f)
        val swing = 34f * sin((legPhase + 0.5f) * TAU + 0.6f)
        body.armAngles(side, limbDir(body.chest, side, swing, 6f), 92f, fold = body.chest.f + body.chest.u * 0.2f)
    }
    // Deck with the belt's marks moving back at the speed of the feet in stance.
    box(v(-0.85f, 0.02f, -0.36f), v(0.8f, deck, 0.36f), Ink.STACK, fit = false, bias = -0.4f)
    val speed = 2 * reach / (stance * 0.72f)
    val spacing = 0.4f
    val shift = (phase * 1.44f * speed) % spacing
    var x = 0.8f - shift
    while (x > -0.85f) {
        add(Rod(v(x - 0.08f, deck - 0.03f, 0.37f), v(x, deck - 0.03f, 0.37f), 0.008f, Ink.PLATE_DARK, fit = false, bias = -0.3f))
        x -= spacing
    }
    add(Rod(v(0.72f, deck), v(0.86f, 1.15f), 0.03f, Ink.FRAME, fit = false, bias = -0.35f))
    add(Block(v(0.86f, 1.2f), Basis.UPRIGHT.pitch(-30f), v(0.04f, 0.1f, 0.28f), Ink.STACK, fit = false, bias = -0.35f))
}

/** Exercise bike variants. */
internal enum class Bike { UPRIGHT, SPIN, RECUMBENT, AIR }

/** Pedalling: feet on the pedals turning the cranks, knees tracking forward, hands on the bars (moving on the air bike). */
internal fun bike(kind: Bike) = Motion.cycle(2f, cycles = 2, camera = Camera(22f)) { phase ->
    val recumbent = kind == Bike.RECUMBENT
    val crank = if (recumbent) v(0.62f, 0.42f) else v(0.28f, 0.3f)
    val crankLength = 0.17f
    val seat = if (recumbent) v(-0.12f, 0.47f) else v(-0.08f, 0.88f)
    val hip = seat + V3.Y * SEAT_HIP
    val lean = when (kind) {
        Bike.UPRIGHT -> 20f
        Bike.SPIN -> 44f
        Bike.RECUMBENT -> -30f
        Bike.AIR -> 14f
    }
    body.torso(hip, Basis.UPRIGHT.pitch(lean), nod = -lean * 0.5f)
    val angle = -phase * TAU
    for (side in Side.entries) {
        val a = angle + if (side == Side.NEAR) 0f else PI.toFloat()
        val pedal = crank + v(cos(a), sin(a)) * crankLength + V3.Z * (side.sign * 0.12f)
        val foot = body.ballOn(side, pedal, if (recumbent) v(0.2f, 1f, side.sign * 0.1f) else v(1f, 0.3f, side.sign * 0.1f))
        add(Rod(crank + V3.Z * (side.sign * 0.08f), pedal, 0.016f, Ink.METAL, bias = 0.05f))
        add(Rod(pedal - foot.dir * 0.06f, pedal + foot.dir * 0.06f, 0.014f, Ink.METAL, bias = 0.05f))
    }
    // Frame, seat and the bars the hands hold.
    val bars = when (kind) {
        Bike.UPRIGHT -> v(0.48f, 1.12f)
        Bike.SPIN -> v(0.62f, 1.02f)
        Bike.RECUMBENT -> v(0.0f, 0.5f)
        Bike.AIR -> v(0.42f, 1.18f)
    }
    if (kind == Bike.AIR) {
        val swing = 13f * sin(angle + 0.4f)
        for (side in Side.entries) {
            val s = if (side == Side.NEAR) swing else -swing
            val hand = v(0.62f, 0.62f) + dirXY(108f + s) * 0.6f + V3.Z * (side.sign * 0.24f)
            body.arm(side, hand, v(-0.3f, -1f, side.sign * 0.6f))
            add(Rod(v(0.62f, 0.62f, side.sign * 0.24f), hand + V3.Y * 0.06f, 0.025f, Ink.METAL, bias = -0.1f))
        }
        add(Disc(v(0.78f, 0.55f), V3.Z, 0.34f, 0.12f, Ink.STACK, hub = 0.06f, bias = -0.3f))
    } else {
        for (side in Side.entries) {
            val hand = if (recumbent) bars + V3.Z * (side.sign * 0.3f) else bars + V3.Z * (side.sign * 0.22f)
            body.arm(side, hand, v(-0.3f, -1f, side.sign * 0.7f))
        }
        add(Rod(bars.withZ(-0.28f), bars.withZ(0.28f), 0.018f, Ink.METAL, bias = -0.1f))
    }
    if (recumbent) {
        pad(seat + v(-0.25f, -0.04f), seat + v(0.18f, -0.04f), width = 0.4f, bias = -0.2f)
        backRest(seat + v(-0.17f, 0f), 30f, length = 0.6f)
        add(Rod(v(seat.x, 0.02f), v(crank.x + 0.1f, 0.02f), 0.03f, Ink.FRAME, bias = -0.4f))
        add(Rod(v(crank.x + 0.1f, 0.02f), crank + v(0.08f, 0.08f), 0.035f, Ink.FRAME, bias = -0.4f))
        add(Block(crank + v(0.12f, 0.02f), Basis.UPRIGHT, v(0.14f, 0.16f, 0.1f), Ink.STACK, bias = -0.35f))
    } else {
        add(Block(seat + v(0f, -0.03f), Basis.UPRIGHT, v(0.12f, 0.03f, 0.08f), Ink.PAD, bias = -0.2f))
        add(Rod(seat + v(0.02f, -0.06f), crank + v(-0.06f, 0f), 0.03f, Ink.FRAME, bias = -0.4f))
        add(Rod(crank + v(-0.06f, 0f), v(crank.x + 0.4f, 0.03f), 0.03f, Ink.FRAME, bias = -0.4f))
        add(Rod(v(crank.x - 0.55f, 0.03f), v(crank.x + 0.5f, 0.03f), 0.03f, Ink.FRAME, bias = -0.4f))
        add(Rod(crank + v(0.06f, 0.06f), bars + v(-0.04f, -0.04f), 0.03f, Ink.FRAME, bias = -0.4f))
        if (kind == Bike.SPIN) add(Disc(v(0.6f, 0.4f), V3.Z, 0.24f, 0.05f, Ink.STACK, hub = 0.05f, bias = -0.3f))
        else if (kind == Bike.UPRIGHT) add(Block(crank + v(0.1f, 0.06f), Basis.UPRIGHT, v(0.16f, 0.18f, 0.1f), Ink.STACK, bias = -0.35f))
    }
    add(Disc(crank, V3.Z, 0.07f, 0.02f, Ink.METAL, bias = 0.02f))
}

/** Elliptical: feet on long pedals tracing an ellipse, hands on the moving arms, alternating. */
internal fun elliptical() = Motion.cycle(1.6f, cycles = 1, camera = Camera(22f)) { phase ->
    val center = v(0.02f, 0.3f)
    body.torso(v(-0.08f, 1.05f + 0.01f * cos(phase * TAU * 2)), Basis.UPRIGHT.pitch(6f), nod = -4f)
    val angle = -phase * TAU
    for (side in Side.entries) {
        val a = angle + if (side == Side.NEAR) 0f else PI.toFloat()
        val point = center + v(0.24f * cos(a), 0.07f * sin(a)) + V3.Z * (side.sign * 0.13f)
        val tilt = rad(7f * sin(a))
        val dir = v(cos(tilt), sin(tilt))
        val up = v(-sin(tilt), cos(tilt))
        val ankle = point + up * BodySize.ANKLE + dir * 0.02f
        body.leg(side, ankle, v(1f, 0.15f, 0f), Foot(dir, up))
        add(Block(point - up * 0.015f + dir * 0.03f, Basis(dir, up, V3.Z), v(0.2f, 0.015f, 0.07f), Ink.STACK, bias = 0.02f))
        add(Rod(point + dir * 0.2f, v(0.62f, 0.45f, side.sign * 0.13f), 0.022f, Ink.FRAME, bias = -0.2f))
        val swing = 14f * sin(a + PI.toFloat())
        val hand = v(0.45f, 0.62f) + dirXY(100f + swing) * 0.66f + V3.Z * (side.sign * 0.25f)
        body.arm(side, hand, v(-0.3f, -1f, side.sign * 0.6f))
        add(Rod(v(0.45f, 0.62f, side.sign * 0.25f), hand + V3.Y * 0.06f, 0.024f, Ink.METAL, bias = -0.1f))
    }
    add(Block(v(0.68f, 0.4f), Basis.UPRIGHT, v(0.14f, 0.38f, 0.16f), Ink.STACK, bias = -0.4f))
    add(Rod(v(-0.6f, 0.03f), v(0.8f, 0.03f), 0.03f, Ink.FRAME, bias = -0.4f))
}

/**
 * Rowing machine: the drive pushes with the legs, swings the trunk back and pulls the handle to the
 * ribs; the recovery reverses it, slower.
 */
internal fun rowingMachine() = Motion.rep(Track.rep(go = 0.8f, hold = 0.1f, back = 1.3f, rest = 0.15f), Camera(22f)) { r ->
    val legs = Ease.IN_OUT.apply((r / 0.6f).coerceIn(0f, 1f))
    val swing = Ease.IN_OUT.apply(((r - 0.3f) / 0.4f).coerceIn(0f, 1f))
    val arms = Ease.IN_OUT.apply(((r - 0.55f) / 0.45f).coerceIn(0f, 1f))
    val seatTop = 0.4f
    val seatX = lerp(0.1f, -0.19f, legs)
    body.torso(v(seatX, seatTop + SEAT_HIP), Basis.UPRIGHT.pitch(lerp(26f, -22f, swing)), nod = -6f)
    for (side in Side.entries) body.leg(side, v(0.62f, 0.38f, side.sign * 0.1f), v(0.3f, 1f, 0f), Foot(dirXY(58f), dirXY(148f)))
    val shoulders = body.shoulders()
    val reachHands = v(shoulders.x + armReach(0.1f, 0f) * 0.95f, shoulders.y - 0.2f)
    val ribs = body.onChest(0.2f, -0.3f)
    val handle = lerp(reachHands, ribs.withZ(0f), arms)
    for (side in Side.entries) body.arm(side, handle.withZ(side.sign * 0.2f), v(-1f, -0.3f, side.sign * 0.5f))
    handleBar(handle, 0.56f)
    add(Cord(listOf(handle, v(1.0f, 0.52f)), 0.01f, Ink.CABLE, bias = -0.1f))
    pad(v(seatX - 0.14f, seatTop - 0.04f), v(seatX + 0.14f, seatTop - 0.04f), width = 0.3f, bias = -0.2f)
    add(Rod(v(-0.95f, 0.32f), v(1.0f, 0.32f), 0.03f, Ink.FRAME, fit = false, bias = -0.3f))
    add(Rod(v(-0.95f, 0.02f), v(-0.95f, 0.32f), 0.03f, Ink.FRAME, fit = false, bias = -0.3f))
    add(Block(v(0.66f, 0.3f), Basis.UPRIGHT.pitch(-32f), v(0.02f, 0.13f, 0.2f), Ink.FRAME, bias = -0.25f))
    add(Disc(v(1.0f, 0.45f), V3.Z, 0.22f, 0.2f, Ink.STACK, hub = 0.05f, fit = false, bias = -0.35f))
}

/**
 * Stair climber: the steps roll down and back like an escalator while the figure climbs in place,
 * one step per leg each half loop, hands lightly on the rails.
 */
internal fun stairClimber() = Motion.cycle(1.6f, cycles = 1, camera = Camera(22f)) { phase ->
    val rise = 0.2f
    val depth = 0.26f
    val base = v(0.02f, 0.3f)
    fun level(l: Float) = base + v(depth * l, rise * l)
    body.torso(level(0.5f) + v(-0.2f, 0.78f), Basis.UPRIGHT.pitch(14f), nod = -8f)
    for (side in Side.entries) {
        val psi = (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f
        val foot = if (psi < 0.5f) {
            level(1f - 2 * psi)
        } else {
            val s = Ease.IN_OUT.apply(2 * psi - 1f)
            level(s) + V3.Y * (0.12f * sin(PI.toFloat() * s))
        }
        body.leg(side, foot + V3.Y * BodySize.ANKLE + V3.Z * (side.sign * 0.11f), v(1f, 0.2f, 0f), Foot.flat(side, 4f))
        body.arm(side, v(0.35f, 1.2f, side.sign * 0.32f), v(-0.3f, -1f, side.sign * 0.6f))
    }
    // Treads moving down one level per half loop; the low ones sink behind the base.
    val sigma = (2 * phase) % 1f
    for (j in -1..4) {
        val top = level(j - sigma)
        box(v(top.x - depth / 2, top.y - rise, -0.32f), v(top.x + depth / 2, top.y, 0.32f), Ink.STACK, fit = false, bias = -0.3f)
    }
    box(v(-0.6f, 0f, -0.4f), v(0.7f, base.y - 0.02f, 0.4f), Ink.FRAME, fit = false, bias = 0.4f)
    for (s in listOf(-1f, 1f)) add(Rod(v(0.55f, base.y, s * 0.32f), v(0.35f, 1.2f, s * 0.32f), 0.025f, Ink.METAL, fit = false, bias = -0.2f))
}

/** Ski erg: both handles pulled from overhead down past the thighs with a hip hinge and a knee bend. */
internal fun skiErg() = Motion.rep(Track.rep(go = 0.6f, hold = 0.08f, back = 0.95f, rest = 0.12f), Camera(26f)) { p ->
    val lean = lerp(2f, 42f, p)
    body.torso(v(lerp(0f, -0.14f, p), lerp(BodySize.STAND, 0.8f, p)), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.4f)
    body.standOn(0f, width = 0.3f, pole = v(1f, 0.1f, 0.1f))
    val top = v(0.3f, 1.82f)
    val bottom = v(0.3f, 0.72f)
    val hands = lerp(top, bottom, Ease.IN_OUT.apply(p))
    for (side in Side.entries) {
        val hand = hands.withZ(side.sign * 0.17f)
        body.arm(side, hand, v(-0.4f, -0.4f, side.sign * 0.6f))
        add(Rod(hand - V3.Y * 0.06f, hand + V3.Y * 0.06f, 0.02f, Ink.METAL))
        add(Cord(listOf(v(0.6f, 2.05f, side.sign * 0.12f), hand + V3.Y * 0.06f), 0.01f, Ink.CABLE, fit = false, bias = -0.1f))
    }
    add(Block(v(0.72f, 1.1f), Basis.UPRIGHT, v(0.1f, 1.1f, 0.22f), Ink.STACK, fit = false, bias = -0.6f))
}

/**
 * Puts the ball of the [side] foot on [contact] (a pedal), the knee towards [pole], the foot
 * roughly square to the shin and slightly pointed, as when pedalling. Returns the foot used.
 */
internal fun Rig.ballOn(side: Side, contact: V3, pole: V3): Foot {
    var foot = Foot(V3.X, V3.Y)
    // Two passes: the shin's direction sets the foot, which moves the ankle a little.
    repeat(2) {
        val ankle = contact + foot.up * (BodySize.SOLE + BodySize.FOOT_RADIUS) - foot.dir * BALL
        val ik = twoBone(hip(side), ankle, BodySize.THIGH, BodySize.SHIN, pole)
        val shin = (ik.end - ik.mid).unit()
        val dir = rotate(shin, V3.Z, rad(78f)).withZ(0f).unit()
        foot = Foot(dir, rotate(dir, V3.Z, rad(90f)))
    }
    leg(side, contact + foot.up * (BodySize.SOLE + BodySize.FOOT_RADIUS) - foot.dir * BALL, pole, foot)
    return foot
}

/** From the ankle to the ball of the foot, along the sole. */
private const val BALL = 0.08f
