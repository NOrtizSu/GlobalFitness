package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Leg and glute machines and isolation patterns: hack and pendulum squats, leg presses, leg
// extensions and curls, calf raises, hip thrusts and bridges, kickbacks, abduction, back extensions.

/**
 * Hack squat: back against the sled pad, shoulders under its pads, sliding down along the rails
 * towards the foot platform. [pendulum] is more upright and swings on an arc.
 */
internal fun hackSquat(pendulum: Boolean) = Motion.rep(Tempo.lowerFirst(), Camera(24f)) { p ->
    val recline = if (pendulum) 16f else 36f
    val basis = Basis.UPRIGHT.pitch(-recline)
    val ankle = v(0.2f, 0.2f)
    val top = ankle + dirXY(90f + recline + (if (pendulum) 10f else 4f)) * 0.84f
    val travel = if (pendulum) 0.46f else 0.44f
    val pelvis = top - basis.u * (travel * p) - basis.f * (if (pendulum) 0.08f * sin(PI.toFloat() * p) else 0f)
    body.torso(pelvis, basis, nod = 8f)
    val foot = Foot(dirXY(18f), dirXY(108f))
    for (side in Side.entries) body.leg(side, ankle.withZ(side.sign * 0.14f), v(1f, 0.25f, side.sign * 0.25f), foot)
    // Foot platform, sled pad along the back, shoulder pads, rails.
    val sole = ankle - foot.up * (BodySize.SOLE + BodySize.FOOT_RADIUS + 0.025f)
    add(Block(sole + foot.dir * 0.05f, Basis(foot.dir, foot.up, V3.Z), v(0.26f, 0.025f, 0.3f), Ink.FRAME, bias = -0.3f))
    val back = pelvis - basis.f * 0.17f
    pad(back - basis.u * 0.08f, back + basis.u * 0.55f, width = 0.36f, thick = 0.08f, up = basis.f, bias = -0.2f)
    for (side in Side.entries) roller(body.shoulder(side) + basis.u * 0.08f - basis.f * 0.02f, length = 0.12f, radius = 0.05f)
    val rail = top - basis.f * 0.3f
    add(Rod(rail - basis.u * 0.9f, rail + basis.u * 0.75f, 0.03f, Ink.FRAME, fit = false, bias = -0.5f))
    for (side in Side.entries) body.arm(side, body.onHips(0.12f, 0.06f, side.sign * 0.32f), v(-0.3f, -1f, side.sign * 0.6f))
}

/** Leg press variants. */
internal enum class LegPress {
    /** 45° sled: lying back, pressing the platform up along the rails. */
    ANGLED,

    /** Seated, pressing a vertical platform straight forward. */
    HORIZONTAL,

    /** Legs nearly straight on the 45° sled, pushing with the ankles only. */
    CALF,
}

/** Leg press: feet on the platform, knees bending towards the chest and pressing back to nearly straight. */
internal fun legPress(kind: LegPress) = Motion.rep(if (kind == LegPress.CALF) Tempo.short() else Tempo.lowerFirst(), Camera(22f)) { p ->
    val horizontal = kind == LegPress.HORIZONTAL
    val hip = if (horizontal) v(0f, 0.7f) else v(0f, 0.52f)
    val recline = if (horizontal) 24f else 52f
    val basis = Basis.UPRIGHT.pitch(-recline)
    body.torso(hip, basis, nod = if (horizontal) 6f else 22f)
    val rail = if (horizontal) dirXY(4f) else dirXY(42f)
    val toes = dirXY(if (horizontal) 94f else 132f)
    val ankle: V3
    val foot: Foot
    if (kind == LegPress.CALF) {
        ankle = hip + rail * 0.8f + toes * 0.05f
        val point = lerp(18f, -32f, p)
        val dir = rotate(toes, V3.Z, rad(point))
        foot = Foot(dir, rotate(-rail, V3.Z, rad(point)))
    } else {
        ankle = hip + rail * lerp(0.82f, 0.5f, p) + toes * 0.05f
        foot = Foot(toes, -rail)
    }
    for (side in Side.entries) body.leg(side, ankle.withZ(side.sign * 0.14f), toes + V3.Z * (side.sign * 0.2f), foot)
    // The platform rests against the balls of the feet.
    val leg = body.legOf(Side.NEAR)
    val sole = ((leg.heel + leg.toe) * 0.5f).withZ(0f)
    val plate = sole + rail * (BodySize.FOOT_RADIUS + 0.025f) + toes * 0.04f
    add(Block(plate, Basis(rail, toes, V3.Z), v(0.025f, 0.26f, 0.24f), Ink.FRAME, bias = -0.1f))
    // Seat and back pad, rails, handles.
    pad(hip + basis.dir(-0.12f, -0.2f, 0f), hip + basis.dir(-0.14f, 0.62f, 0f), width = 0.38f, thick = 0.08f, up = basis.f, bias = -0.25f)
    pad(hip - V3.Y * 0.13f - rail * 0.2f, hip - V3.Y * 0.13f + rail * 0.15f, width = 0.38f, thick = 0.08f, up = toes, bias = -0.25f)
    for (s in listOf(-1f, 1f)) add(Rod(hip + rail * 0.3f + V3.Z * (s * 0.38f) - toes * 0.1f, hip + rail * 1.5f + V3.Z * (s * 0.38f) - toes * 0.1f, 0.025f, Ink.FRAME, fit = false, bias = -0.4f))
    add(Rod(v(hip.x - 0.3f, 0.02f), v(hip.x + 1.2f, 0.02f), 0.03f, Ink.FRAME, fit = false, bias = -0.4f))
    for (side in Side.entries) body.arm(side, hip + v(0.12f, 0.02f, side.sign * 0.3f), v(-0.3f, -1f, side.sign * 0.6f))
}

/** Leg extension: seated, shins straightening from hanging under the knees to level, against the roller. */
internal fun legExtension() = Motion.rep(Tempo.liftFirst(), Camera(24f)) { p ->
    val seatTop = 0.52f
    body.torso(v(0f, seatTop + SEAT_HIP), Basis.UPRIGHT.pitch(-10f))
    pad(v(-0.12f, seatTop - 0.045f), v(0.4f, seatTop - 0.045f), width = 0.38f, thick = 0.09f, bias = -0.2f)
    backRest(v(-0.14f, seatTop), 10f, length = 0.68f)
    add(Rod(v(0.1f, 0.02f), v(0.1f, seatTop - 0.09f), 0.032f, Ink.FRAME, bias = -0.3f))
    for (side in Side.entries) body.legAngles(side, limbDir(Basis.UPRIGHT, side, 90f, 3f), lerp(94f, 6f, p), fold = -V3.Y)
    val leg = body.legOf(Side.NEAR)
    val shin = (leg.ankle - leg.knee).unit()
    val front = orthogonal(leg.apex, shin)
    val roller = leg.ankle - shin * 0.04f + front * 0.08f
    add(Rod(roller.withZ(-0.2f), roller.withZ(0.2f), 0.05f, Ink.PAD))
    add(Rod(leg.knee.withZ(0.27f), roller.withZ(0.27f), 0.025f, Ink.FRAME, bias = 0.1f))
    for (side in Side.entries) body.arm(side, body.onHips(0.05f, 0.0f, side.sign * 0.3f), v(-0.4f, -1f, side.sign * 0.6f))
}

/** Leg curl variants. */
internal enum class LegCurl { LYING, SEATED, NORDIC }

/** Hamstring curls: lying face down or seated against the roller, or the bodyweight Nordic lowering. */
internal fun legCurl(kind: LegCurl) = when (kind) {
    LegCurl.LYING -> Motion.rep(Tempo.liftFirst(), Camera(22f)) { p ->
        val top = 0.6f
        body.torso(v(0f, top + 0.1f), Basis.PRONE, nod = -12f)
        pad(v(-0.2f, top - 0.04f), v(0.95f, top - 0.04f), width = 0.36f, bias = -0.2f)
        for (x in listOf(0.0f, 0.8f)) add(Rod(v(x, 0.02f), v(x, top - 0.08f), 0.03f, Ink.FRAME, bias = -0.3f))
        for (side in Side.entries) body.legAngles(side, v(-1f, -0.12f, side.sign * 0.03f), lerp(6f, 112f, p), fold = V3.Y)
        val leg = body.legOf(Side.NEAR)
        val shin = (leg.ankle - leg.knee).unit()
        val roller = leg.ankle - shin * 0.05f - orthogonal(leg.apex, shin) * 0.075f
        add(Rod(roller.withZ(-0.2f), roller.withZ(0.2f), 0.05f, Ink.PAD))
        add(Rod(leg.knee.withZ(0.27f), roller.withZ(0.27f), 0.025f, Ink.FRAME, bias = 0.1f))
        for (side in Side.entries) body.arm(side, v(0.92f, top - 0.06f, side.sign * 0.22f), v(-0.2f, -1f, side.sign * 0.6f))
    }
    LegCurl.SEATED -> Motion.rep(Tempo.liftFirst(), Camera(24f)) { p ->
        val seatTop = 0.52f
        body.torso(v(0f, seatTop + SEAT_HIP), Basis.UPRIGHT.pitch(-14f))
        pad(v(-0.12f, seatTop - 0.045f), v(0.4f, seatTop - 0.045f), width = 0.38f, thick = 0.09f, bias = -0.2f)
        backRest(v(-0.14f, seatTop), 14f, length = 0.68f)
        add(Rod(v(0.1f, 0.02f), v(0.1f, seatTop - 0.09f), 0.032f, Ink.FRAME, bias = -0.3f))
        for (side in Side.entries) body.legAngles(side, limbDir(Basis.UPRIGHT, side, 92f, 3f), lerp(8f, 98f, p), fold = -V3.Y)
        val leg = body.legOf(Side.NEAR)
        val shin = (leg.ankle - leg.knee).unit()
        val roller = leg.ankle - shin * 0.05f - orthogonal(leg.apex, shin) * 0.075f
        add(Rod(roller.withZ(-0.2f), roller.withZ(0.2f), 0.05f, Ink.PAD))
        add(Rod(leg.knee.withZ(0.27f), roller.withZ(0.27f), 0.025f, Ink.FRAME, bias = 0.1f))
        val thighPad = lerp(leg.hip, leg.knee, 0.8f) + V3.Y * 0.11f
        add(Rod(thighPad.withZ(-0.2f), thighPad.withZ(0.2f), 0.05f, Ink.PAD))
        for (side in Side.entries) body.arm(side, body.onHips(0.1f, 0.0f, side.sign * 0.3f), v(-0.4f, -1f, side.sign * 0.6f))
    }
    LegCurl.NORDIC -> Motion.rep(Track.rep(go = 1.8f, hold = 0.2f, back = 0.9f, rest = 0.5f), Camera(24f)) { p ->
        val knee = v(0f, 0.11f)
        val lean = lerp(0f, 66f, p)
        val basis = Basis.UPRIGHT.pitch(lean)
        body.torso(knee + dirXY(90f - lean) * BodySize.THIGH, basis, nod = 6f)
        for (side in Side.entries) body.leg(side, v(-0.42f, 0.17f, side.sign * 0.1f), v(1f, -1.2f, 0f), Foot(dirXY(250f), dirXY(340f)))
        for (side in Side.entries) body.armAngles(side, limbDir(basis, side, lerp(30f, 70f, p), 6f), 95f, fold = basis.u)
        pad(v(-0.15f, 0.04f), v(0.15f, 0.04f), width = 0.4f, thick = 0.06f, bias = -0.3f)
        add(Rod(v(-0.45f, 0.18f, -0.2f), v(-0.45f, 0.18f, 0.2f), 0.04f, Ink.PAD, bias = 0.2f))
    }
}

/** The ankle of a foot whose toes rest at ([x] + toe length) on a surface at [floor], heel raised [lift] degrees. */
internal fun liftedAnkle(x: Float, floor: Float, lift: Float): V3 {
    val toe = v(x + BodySize.TOE, floor + BodySize.FOOT_RADIUS)
    val a = rad(lift)
    val dir = v(cos(a), -sin(a))
    val up = v(sin(a), cos(a))
    return toe + up * BodySize.SOLE - dir * BodySize.TOE
}

/** Calf raise variants. */
internal enum class CalfRaise { STANDING_MACHINE, SMITH, SEATED }

/** Calf raises: balls of the feet on a step, heels dropping below it and rising high. */
internal fun calfRaise(kind: CalfRaise) = Motion.rep(Tempo.short(), Camera(24f)) { p ->
    val step = 0.12f
    val lift = lerp(-16f, 34f, p)
    val footX = if (kind == CalfRaise.SEATED) 0.3f else -0.07f
    box(v(footX + 0.08f, 0f, -0.35f), v(footX + 0.5f, step, 0.35f), Ink.PAD, bias = -0.25f)
    if (kind == CalfRaise.SEATED) {
        val seatTop = 0.56f
        body.torso(v(-0.1f, seatTop + SEAT_HIP), Basis.UPRIGHT.pitch(6f))
        machineSeat(-0.1f, seatTop)
        for (side in Side.entries) body.stand(side, footX, z = side.sign * 0.13f, floor = step, lift = lift, out = 4f, pole = v(1f, 0.2f, 0f))
        val knee = body.legOf(Side.NEAR).knee
        val padAt = knee + v(-0.06f, 0.1f)
        add(Rod(padAt.withZ(-0.24f), padAt.withZ(0.24f), 0.05f, Ink.PAD))
        add(Rod(padAt.withZ(0.27f), v(-0.35f, 0.75f, 0.27f), 0.025f, Ink.FRAME, bias = -0.2f))
        for (side in Side.entries) body.arm(side, padAt + v(0.02f, 0.06f, side.sign * 0.22f), v(-0.3f, -1f, side.sign * 0.5f))
        return@rep
    }
    val ankle = liftedAnkle(footX, step, lift)
    body.torso(v(ankle.x - 0.01f, ankle.y + 0.85f))
    body.standOn(footX, width = 0.26f, out = 4f, floor = step, lift = lift)
    if (kind == CalfRaise.SMITH) {
        val bar = body.onChest(-0.13f, -0.06f)
        barbell(bar)
        for (side in Side.entries) body.arm(side, bar.withZ(side.sign * 0.44f), body.chest.dir(-0.7f, -1f, side.sign * 0.4f))
        smithRails(bar.x, 2.1f)
    } else {
        for (side in Side.entries) {
            val s = body.shoulder(side)
            roller(s + v(0f, 0.08f, side.sign * 0.02f), length = 0.14f, radius = 0.05f)
            body.hang(side, forward = 6f, out = 8f)
        }
        add(Rod(body.shoulder(Side.FAR) + v(-0.15f, 0.08f, -0.1f), v(-0.35f, 1.6f, -0.1f), 0.03f, Ink.FRAME, fit = false, bias = -0.4f))
        add(Rod(v(-0.35f, 0f, -0.1f), v(-0.35f, 2.0f, -0.1f), 0.04f, Ink.FRAME, fit = false, bias = -0.5f))
    }
}

/** Hip thrust and glute bridge set-ups. */
internal enum class Thrust { BARBELL, SMITH, MACHINE, BRIDGE, BARBELL_BRIDGE }

/**
 * Hip thrust (upper back on a bench) or glute bridge (on the floor): the hips rise until the body
 * is straight from shoulders to knees, pivoting on the shoulder blades, chin tucked.
 */
internal fun hipThrust(kind: Thrust) = Motion.rep(Tempo.liftFirst(), Camera(-24f)) { p ->
    val floor = kind == Thrust.BRIDGE || kind == Thrust.BARBELL_BRIDGE
    val shoulders = if (floor) v(-0.47f, 0.145f) else v(-0.52f, 0.47f)
    val angle = if (floor) lerp(2f, 30f, p) else lerp(-40f, 2f, p)
    val spine = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP
    val pelvis = shoulders + dirXY(angle) * spine
    val basis = Basis.UPRIGHT.pitch(-90f - angle)
    body.torso(pelvis, basis, nod = if (floor) lerp(12f, 40f, p) else 26f)
    body.standOn(if (floor) 0.3f else 0.36f, width = 0.34f, out = 8f, pole = v(0.3f, 1f, 0.2f))
    if (!floor) box(v(-0.84f, 0f, -0.32f), v(-0.55f, 0.43f, 0.32f), Ink.PAD, bias = -0.3f)
    val bar = pelvis + basis.f * 0.15f + basis.u * 0.02f
    when (kind) {
        Thrust.BRIDGE -> for (side in Side.entries) body.arm(side, v(-0.05f, BodySize.FIST_RADIUS, side.sign * 0.3f), v(0f, 1f, side.sign * 0.4f))
        Thrust.MACHINE -> {
            add(Rod(bar.withZ(-0.24f), bar.withZ(0.24f), 0.055f, Ink.PAD))
            add(Rod(bar.withZ(0.28f), v(-0.3f, 0.95f, 0.28f), 0.03f, Ink.FRAME, bias = -0.2f))
            for (side in Side.entries) body.arm(side, bar + v(-0.02f, 0.06f, side.sign * 0.22f), v(0f, -1f, side.sign * 0.8f))
        }
        else -> {
            barbell(bar, plateRadius = 0.2f)
            for (side in Side.entries) body.arm(side, bar.withZ(side.sign * 0.4f), v(0f, -1f, side.sign * 0.6f))
            if (kind == Thrust.SMITH) smithRails(bar.x, 1.7f)
        }
    }
}

/**
 * Glute kickback: standing on one leg, leaning on the machine, the near leg drives back — against
 * the machine's foot plate, or with a cable from a low pulley strapped to the ankle.
 */
internal fun gluteKickback(machine: Boolean) = Motion.rep(Tempo.liftFirst(), Camera(24f)) { p ->
    val lean = if (machine) 48f else 32f
    val basis = Basis.UPRIGHT.pitch(lean)
    // The machine's platform lifts the standing foot so the working leg clears the floor.
    val platform = if (machine) 0.16f else 0f
    body.torso(v(-0.05f, BodySize.STAND - 0.03f + platform), basis, nod = -lean * 0.5f)
    body.stand(Side.FAR, 0f, z = -0.1f, floor = platform, out = 4f)
    if (machine) {
        box(v(-0.3f, 0f, -0.3f), v(0.3f, platform, 0.05f), Ink.FRAME, bias = -0.3f)
        val hipAngle = lerp(70f, -22f, p)
        body.legAngles(Side.NEAR, limbDir(Basis.UPRIGHT, Side.NEAR, hipAngle, 2f), lerp(100f, 25f, p), fold = -V3.X)
        val leg = body.legOf(Side.NEAR)
        val sole = (leg.heel + leg.toe) * 0.5f
        val shin = (leg.ankle - leg.knee).unit()
        add(Block(sole + shin * 0.04f, Basis(shin, orthogonal(leg.toe - leg.heel, shin), V3.Z), v(0.02f, 0.14f, 0.13f), Ink.FRAME, bias = 0.1f))
        val chestPad = body.onChest(0.2f, -0.18f)
        add(Block(chestPad, body.chest, v(0.04f, 0.18f, 0.16f), Ink.PAD, bias = -0.1f))
        for (side in Side.entries) body.arm(side, chestPad + v(0.1f, -0.2f, side.sign * 0.2f), v(0f, -1f, side.sign * 0.5f))
        add(Rod(v(0.45f, 0f), chestPad + v(0.12f, 0f), 0.04f, Ink.FRAME, bias = -0.3f))
    } else {
        body.legAngles(Side.NEAR, limbDir(Basis.UPRIGHT, Side.NEAR, lerp(20f, -36f, p), 4f), lerp(72f, 14f, p), fold = -V3.X)
        for (side in Side.entries) body.arm(side, v(0.62f, 1.12f, side.sign * 0.18f), v(0f, -1f, side.sign * 0.6f))
        cable(v(0.62f, 0.12f), body.legOf(Side.NEAR).ankle)
        add(Block(v(0.75f, 1.05f), Basis.UPRIGHT, v(0.1f, 1.05f, 0.24f), Ink.FRAME, fit = false, bias = -0.6f))
    }
}

/**
 * Hip abductor / adductor machine, seen from the front: seated with the knees bent, the thighs
 * open against the outer pads or squeeze together against the inner ones.
 */
internal fun hipAbduction(outward: Boolean) = Motion.rep(Tempo.liftFirst(), Camera(80f, 10f)) { p ->
    val seatTop = 0.52f
    body.torso(v(0f, seatTop + SEAT_HIP), Basis.UPRIGHT.pitch(-16f))
    pad(v(-0.15f, seatTop - 0.045f), v(0.3f, seatTop - 0.045f), width = 0.4f, thick = 0.09f, bias = -0.3f)
    backRest(v(-0.15f, seatTop), 16f, length = 0.7f)
    val open = if (outward) lerp(5f, 34f, p) else lerp(36f, 5f, p)
    for (side in Side.entries) {
        val dir = v(cos(rad(open)), -0.04f, side.sign * sin(rad(open)))
        body.legAngles(side, dir, 88f, fold = -V3.Y, foot = Foot(v(cos(rad(open)), 0f, side.sign * sin(rad(open))), V3.Y))
        val knee = body.legOf(side).knee
        val outside = v(-sin(rad(open)), 0f, side.sign * cos(rad(open)))
        val padAt = knee + outside * (if (outward) 0.09f else -0.09f) - dir * 0.05f
        add(Block(padAt, Basis(dir, V3.Y, outside), v(0.12f, 0.08f, 0.025f), Ink.PAD, bias = 0.02f))
        body.arm(side, body.onHips(0.12f, -0.02f, side.sign * 0.3f), v(-0.3f, -1f, side.sign * 0.6f))
    }
}

/**
 * Back extension: hips on the pad of the 45° bench with the feet under the roller, the trunk
 * lowering towards the floor and rising in line with the legs; [machine] is the seated version,
 * leaning back against its pad.
 */
internal fun backExtension(machine: Boolean) = Motion.rep(Tempo.liftFirst(), Camera(24f)) { p ->
    if (machine) {
        val seatTop = 0.5f
        val lean = lerp(36f, -8f, p)
        body.torso(v(0f, seatTop + SEAT_HIP), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.3f)
        body.standOn(0.42f, width = 0.3f, out = 6f)
        machineSeat(0f, seatTop)
        val padAt = body.onChest(-0.16f, -0.12f)
        add(Block(padAt, body.chest, v(0.04f, 0.15f, 0.17f), Ink.PAD, bias = -0.2f))
        add(Rod(padAt - body.chest.f * 0.05f, v(0f, seatTop - 0.05f) - V3.X * 0.25f, 0.03f, Ink.FRAME, bias = -0.3f))
        for (side in Side.entries) body.arm(side, body.onChest(0.13f, -0.12f, side.sign * 0.06f), v(0f, -1f, side.sign * 1f))
    } else {
        val ankle = v(-0.55f, 0.32f)
        val legDir = dirXY(45f)
        val hip = ankle + legDir * 0.84f
        val angle = lerp(-70f, 45f, p)
        body.torso(hip, Basis.UPRIGHT.pitch(90f - angle), nod = lerp(-10f, -30f, p))
        for (side in Side.entries) body.leg(side, ankle.withZ(side.sign * 0.1f), dirXY(-45f), Foot(dirXY(-45f), dirXY(45f)))
        for (side in Side.entries) body.arm(side, body.onChest(0.14f, -0.1f, side.sign * 0.05f), body.chest.dir(0f, -1f, side.sign * 1f))
        // Frame: hip pad just below the hips, foot plate and ankle roller, base.
        val padAt = hip + legDir * 0.02f - dirXY(135f) * 0.14f
        pad(padAt - legDir * 0.16f, padAt + legDir * 0.14f, width = 0.38f, thick = 0.08f, up = dirXY(135f), bias = -0.2f)
        add(Block(ankle - legDir * (BodySize.SOLE + BodySize.FOOT_RADIUS + 0.02f) + dirXY(-45f) * 0.05f, Basis(legDir, dirXY(135f), V3.Z), v(0.02f, 0.13f, 0.2f), Ink.FRAME, bias = -0.2f))
        roller(ankle + dirXY(135f) * 0.08f + legDir * 0.02f, length = 0.36f, radius = 0.045f)
        add(Rod(v(-0.75f, 0.03f), v(0.4f, 0.03f), 0.03f, Ink.FRAME, bias = -0.4f))
        add(Rod(v(0.15f, 0.03f), padAt - dirXY(135f) * 0.06f, 0.03f, Ink.FRAME, bias = -0.4f))
    }
}

/**
 * Banded lateral walk, seen from the front: half squat with a band around the knees, two steps to
 * one side and two back.
 */
internal fun lateralWalk() = Motion.cycle(3.2f, camera = Camera(82f)) { phase ->
    // Four steps of a quarter cycle each: near foot out, far foot follows, far foot out, near follows.
    val q = phase * 4
    val i = q.toInt().coerceAtMost(3)
    val f = Ease.IN_OUT.apply(q - i)
    val stride = 0.2f
    val nearZ = 0.15f + stride * when (i) {
        0 -> f
        1, 2 -> 1f
        else -> 1 - f
    }
    val farZ = -0.15f + stride * when (i) {
        0 -> 0f
        1 -> f
        2 -> 1 - f
        else -> 0f
    }
    val lift = sin(PI.toFloat() * f) * 0.06f
    body.torso(v(-0.08f, 0.8f, (nearZ + farZ) / 2), Basis.UPRIGHT.pitch(20f), nod = -10f)
    body.stand(Side.NEAR, 0.02f, z = nearZ, floor = if (i == 0 || i == 3) lift else 0f, out = 10f, pole = v(1f, 0.1f, 0.3f))
    body.stand(Side.FAR, 0.02f, z = farZ, floor = if (i == 1 || i == 2) lift else 0f, out = 10f, pole = v(1f, 0.1f, -0.3f))
    for (side in Side.entries) body.arm(side, body.onHips(0.05f, 0.1f, side.sign * 0.2f), v(-0.6f, 0f, side.sign * 1f))
    val kn = body.legOf(Side.NEAR).knee
    val kf = body.legOf(Side.FAR).knee
    band(listOf(kn + v(0.04f, -0.06f, 0.03f), kf + v(0.04f, -0.06f, -0.03f)), bias = 0.1f)
}
