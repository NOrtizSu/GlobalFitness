package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Shoulder and trap patterns: overhead presses, raises, rear-delt work, face pulls, upright rows, shrugs.

/**
 * Standing barbell overhead press: from the front of the shoulders to locked out over the crown,
 * the bar curving around the face while the head moves back out of its way.
 */
internal fun barbellOverheadPress() = Motion.rep(Tempo.liftFirst(), Camera(22f)) { p ->
    val dodge = sin(PI.toFloat() * p)
    body.torso(v(0f, BodySize.STAND), Basis.UPRIGHT.pitch(-3f), nod = -16f * dodge)
    body.standOn(0f, width = 0.3f)
    val shoulders = body.shoulders()
    val half = 0.27f
    val bottom = v(shoulders.x + 0.17f, shoulders.y + 0.03f)
    val top = v(shoulders.x - 0.02f, shoulders.y + armReach(half - BodySize.SHOULDER_HALF, -0.02f))
    val bar = lerp(bottom, top, p) + V3.X * (0.06f * dodge)
    barbell(bar)
    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * half), v(0.6f, -1f, side.sign * lerp(0.4f, 1f, p)))
}

/** Seated overhead press with dumbbells, a machine or a Smith bar; seen from the front except on the Smith machine. */
internal fun seatedPress(grip: Grip) = Motion.rep(Tempo.liftFirst(), Camera(if (grip == Grip.SMITH) 24f else 66f)) { p ->
    val seatTop = 0.47f
    seated(seatTop = seatTop, recline = 6f, footX = 0.42f, width = 0.36f, drawSeat = grip != Grip.MACHINE)
    if (grip == Grip.MACHINE) machineSeat(0f, seatTop)
    backRest(v(-0.13f, seatTop), 6f, length = 0.75f)
    val shoulders = body.shoulders()
    when (grip) {
        Grip.SMITH -> {
            val x = shoulders.x + 0.13f
            val bar = v(x, shoulders.y + lerp(0.04f, armReach(0.27f - BodySize.SHOULDER_HALF, 0.13f), p))
            barbell(bar)
            for (side in Side.entries) body.arm(side, bar.withZ(side.sign * 0.27f), v(0.4f, -1f, side.sign * 0.8f))
            smithRails(x, 2.1f)
        }
        else -> for (side in Side.entries) {
            val s = body.shoulder(side)
            val start = s + v(0.03f, 0.05f, side.sign * 0.14f)
            val end = s + v(0.0f, armReach(0.06f), -side.sign * 0.06f)
            val hand = lerp(start, end, p)
            body.arm(side, hand, v(0f, -1f, side.sign * 1.2f))
            if (grip == Grip.DUMBBELLS) dumbbell(hand) else machineHandle(hand, v(-0.25f, 0.9f, side.sign * 0.4f), grip = V3.X)
        }
    }
}

/**
 * Arnold press: dumbbells start in front of the face, palms in, elbows together; the elbows open
 * out to the sides while the dumbbells rise and press overhead. Seen from the front.
 */
internal fun arnoldPress() = Motion.rep(Track.rep(go = 1.2f, hold = 0.25f, back = 1.2f, rest = 0.35f), Camera(70f)) { p ->
    val seatTop = 0.47f
    seated(seatTop = seatTop, recline = 6f, footX = 0.42f, width = 0.36f)
    backRest(v(-0.13f, seatTop), 6f, length = 0.75f)
    val open = Ease.IN_OUT.apply((p / 0.55f).coerceIn(0f, 1f))
    val press = Ease.IN_OUT.apply(((p - 0.35f) / 0.65f).coerceIn(0f, 1f))
    val sweep = lerp(84f, 4f, open)
    val rise = lerp(lerp(-8f, 0f, open), 72f, press)
    val elbow = lerp(lerp(150f, 95f, open), 6f, press)
    for (side in Side.entries) {
        val upper = v(cos(rad(rise)) * sin(rad(sweep)), sin(rad(rise)), side.sign * cos(rad(rise)) * cos(rad(sweep)))
        body.armAngles(side, upper, elbow, fold = V3.Y)
        dumbbell(body.handOf(side), axis = lerp(V3.X, V3.Z, open).unit())
    }
}

/**
 * Lateral raise, seen from the front: arms rise out to the sides to shoulder height, elbows soft.
 * [grip] DUMBBELLS raises both arms; CABLE one arm across the body from a low pulley; MACHINE
 * seated with the pads on the upper arms.
 */
internal fun lateralRaise(grip: Grip) = Motion.rep(Tempo.liftFirst(), Camera(84f)) { p ->
    val raise = lerp(10f, 86f, p)
    when (grip) {
        Grip.MACHINE -> {
            val seatTop = 0.5f
            seated(seatTop = seatTop, recline = 0f, footX = 0.38f, width = 0.32f, drawSeat = false)
            machineSeat(0f, seatTop)
            add(Block(body.onChest(-0.2f, -0.2f), body.chest, v(0.04f, 0.28f, 0.17f), Ink.PAD, bias = -0.3f))
            for (side in Side.entries) {
                body.armAngles(side, limbDir(body.chest, side, 12f, raise), 88f, fold = body.chest.f)
                val arm = body.armOf(side)
                roller(arm.elbow + V3.Z * (side.sign * 0.07f) + V3.Y * 0.02f, length = 0.05f, radius = 0.05f)
                add(Rod(arm.elbow + V3.Z * (side.sign * 0.07f), body.shoulder(side) + V3.Z * (side.sign * 0.08f) - V3.X * 0.12f, 0.025f, Ink.FRAME, bias = -0.2f))
            }
        }
        Grip.CABLE -> {
            body.torso(v(0f, BodySize.STAND), Basis.UPRIGHT.roll(-4f))
            body.standOn(0f, width = 0.3f)
            body.armAngles(Side.NEAR, limbDir(body.chest, Side.NEAR, 14f, lerp(-14f, 84f, p)), 14f, fold = body.chest.f)
            body.arm(Side.FAR, v(0.02f, 1.05f, -0.42f), v(0f, -1f, -1f))
            val hand = body.handOf(Side.NEAR)
            add(Rod(hand - V3.Y * 0.05f, hand + V3.Y * 0.05f, 0.02f, Ink.METAL))
            cable(v(0f, 0.18f, -0.5f), hand)
            add(Block(v(-0.05f, 1f, -0.6f), Basis.UPRIGHT, v(0.12f, 1f, 0.08f), Ink.FRAME, fit = false, bias = -0.6f))
        }
        else -> {
            body.torso(v(0f, BodySize.STAND), Basis.UPRIGHT.pitch(4f))
            body.standOn(0f, width = 0.3f)
            for (side in Side.entries) {
                body.armAngles(side, limbDir(body.chest, side, 14f, raise), 16f, fold = body.chest.f)
                dumbbell(body.handOf(side), axis = V3.X)
            }
        }
    }
}

/** Dumbbell front raise: straight arms lift in front to shoulder height. */
internal fun frontRaise() = Motion.rep(Tempo.liftFirst(), Camera(24f)) { p ->
    body.torso(v(0f, BodySize.STAND))
    body.standOn(0f, width = 0.28f)
    for (side in Side.entries) {
        body.armAngles(side, limbDir(body.chest, side, lerp(8f, 88f, p), 6f), 8f, fold = V3.Y)
        dumbbell(body.handOf(side))
    }
}

/** What a rear-delt pattern holds and how the body is set up. */
internal enum class RearDelt { DUMBBELLS_BENT_OVER, MACHINE, BAND }

/**
 * Rear-delt fly: arms sweep from in front of the body out to the sides with soft elbows — bent
 * over with dumbbells, seated facing a reverse pec deck, or standing pulling a band apart.
 */
internal fun rearDeltFly(kind: RearDelt) = Motion.rep(Tempo.liftFirst(), if (kind == RearDelt.BAND) Camera(62f) else Camera(48f, 18f)) { p ->
    when (kind) {
        RearDelt.DUMBBELLS_BENT_OVER -> {
            val basis = Basis.UPRIGHT.pitch(72f)
            body.torso(v(-0.2f, 0.86f), basis, nod = -40f)
            body.standOn(0f, width = 0.3f, pole = v(1f, 0.1f, 0.15f))
            val open = lerp(8f, 82f, p)
            for (side in Side.entries) {
                val dir = v(0.06f, -cos(rad(open)), side.sign * sin(rad(open)))
                body.armAngles(side, dir, 18f, fold = V3.X)
                dumbbell(body.handOf(side), axis = V3.X)
            }
        }
        RearDelt.MACHINE -> {
            val seatTop = 0.5f
            seated(seatTop = seatTop, recline = -4f, footX = 0.4f, width = 0.32f, drawSeat = false)
            machineSeat(0f, seatTop)
            add(Block(body.onChest(0.2f, -0.2f), body.chest, v(0.04f, 0.26f, 0.15f), Ink.PAD, bias = -0.05f))
            val sweep = lerp(80f, -6f, p)
            for (side in Side.entries) {
                val dir = v(sin(rad(sweep)), -0.05f, side.sign * cos(rad(sweep)))
                body.armAngles(side, dir, 10f, fold = V3.Y)
                val hand = body.handOf(side)
                add(Rod(hand - V3.Y * 0.06f, hand + V3.Y * 0.06f, 0.02f, Ink.METAL))
                add(Rod(hand + V3.Y * 0.06f, v(0.45f, 1.8f, side.sign * 0.1f), 0.025f, Ink.FRAME, fit = false, bias = -0.3f))
            }
        }
        RearDelt.BAND -> {
            body.torso(v(0f, BodySize.STAND))
            body.standOn(0f, width = 0.3f)
            val sweep = lerp(78f, 2f, p)
            for (side in Side.entries) {
                val dir = v(sin(rad(sweep)), -0.02f, side.sign * cos(rad(sweep)))
                body.armAngles(side, dir, 6f, fold = V3.Y)
            }
            band(listOf(body.handOf(Side.NEAR), body.handOf(Side.FAR)))
        }
    }
}

/** Face pull: a rope from a pulley at head height pulled towards the face, elbows high and wide. */
internal fun facePull() = Motion.rep(Tempo.liftFirst(), Camera(36f)) { p ->
    body.torso(v(-0.06f, BodySize.STAND - 0.01f), Basis.UPRIGHT.pitch(-4f))
    body.stand(Side.NEAR, 0.1f, z = 0.13f)
    body.stand(Side.FAR, -0.2f, z = -0.13f)
    val pulley = v(1.05f, 1.68f)
    val shoulders = body.shoulders()
    for (side in Side.entries) {
        val start = shoulders + (pulley - shoulders).unit() * 0.56f + V3.Z * (side.sign * 0.06f)
        val end = body.onChest(0.12f, 0.2f, side.sign * 0.24f)
        body.arm(side, lerp(start, end, p), v(-0.4f, 0.5f, side.sign * 1f))
    }
    val near = body.handOf(Side.NEAR)
    val far = body.handOf(Side.FAR)
    val knot = (near + far) * 0.5f + (pulley - (near + far) * 0.5f).unit() * 0.18f
    add(Cord(listOf(knot, near), 0.026f, Ink.CABLE))
    add(Cord(listOf(knot, far), 0.026f, Ink.CABLE))
    cable(pulley, knot, fit = false)
    add(Block(v(1.2f, 1.05f), Basis.UPRIGHT, v(0.1f, 1.05f, 0.2f), Ink.FRAME, fit = false, bias = -0.6f))
}

/** Barbell upright row: from the thighs to the chin, elbows leading up and out. */
internal fun uprightRow() = Motion.rep(Tempo.liftFirst(), Camera(36f)) { p ->
    body.torso(v(0f, BodySize.STAND), nod = 4f)
    body.standOn(0f, width = 0.28f)
    val shoulders = body.shoulders()
    val half = 0.14f
    val bottom = v(shoulders.x + 0.08f, shoulders.y - armReach(abs(half - BodySize.SHOULDER_HALF), 0.08f))
    val top = v(shoulders.x + 0.13f, shoulders.y + 0.02f)
    val bar = lerp(bottom, top, p)
    barbell(bar, length = 1.6f, plateRadius = 0.16f, plates = 1)
    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * half), v(-0.2f, 1f, side.sign * 1f))
}

/** Shrug: straight arms hold the weight while the shoulders lift towards the ears and lower. */
internal fun shrug(grip: Grip) = Motion.rep(Tempo.short(), Camera(38f)) { p ->
    body.torso(v(0f, BodySize.STAND), shrug = lerp(0f, 0.075f, p))
    body.standOn(0f, width = 0.28f)
    for (side in Side.entries) {
        val s = body.shoulder(side)
        val out = if (grip == Grip.DUMBBELLS) 0.06f else 0.08f
        val hand = s + v(0.05f, -armReach(out, 0.05f, 0.99f), side.sign * out)
        body.arm(side, hand, v(-1f, 0f, side.sign * 0.4f))
        if (grip == Grip.DUMBBELLS) dumbbell(hand, axis = V3.X)
    }
    if (grip != Grip.DUMBBELLS) barbell((body.handOf(Side.NEAR) + body.handOf(Side.FAR)) * 0.5f)
}
