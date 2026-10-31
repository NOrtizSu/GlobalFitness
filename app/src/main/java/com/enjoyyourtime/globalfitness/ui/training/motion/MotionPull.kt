package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Pulling patterns: pulldowns, pull-ups and hangs, rows, pullovers.

/**
 * A seated lat pulldown, thighs under the pads: the bar comes from arm's length overhead to the
 * upper chest while the trunk leans back a little. [close] uses a V handle with the elbows driving
 * down by the sides; the wide bar is seen from the front so the elbows' sweep shows.
 */
internal fun pulldown(close: Boolean) = Motion.rep(Tempo.liftFirst(), Camera(if (close) 24f else 58f)) { p ->
    val seatTop = 0.44f
    seated(seatTop = seatTop, recline = lerp(8f, 20f, p), footX = 0.42f, width = 0.3f, nod = -6f)
    roller(v(0.3f, seatTop + SEAT_HIP + 0.135f), length = 0.42f)
    val shoulders = body.shoulders()
    val handZ = if (close) 0.07f else 0.4f
    val lateral = handZ - BodySize.SHOULDER_HALF
    val top = v(shoulders.x + 0.06f, shoulders.y + armReach(abs(lateral), 0.06f))
    val bottom = body.onChest(if (close) 0.2f else 0.17f, if (close) -0.12f else 0.02f)
    val bar = lerp(top, bottom.withZ(0f), p)
    for (side in Side.entries) {
        val pole = if (close) v(-0.6f, -1f, side.sign * 0.25f) else v(-0.15f, -1f, side.sign * 1.1f)
        body.arm(side, bar.withZ(side.sign * handZ), pole)
    }
    val attach = if (close) bar + V3.Y * 0.1f else bar + V3.Y * 0.02f
    if (close) {
        add(Rod(bar.withZ(-0.09f), bar.withZ(0.09f), 0.018f, Ink.METAL, bias = -0.05f))
        add(Rod(attach, bar, 0.015f, Ink.METAL, bias = -0.05f))
    } else {
        handleBar(attach, 1.2f)
    }
    val pulley = v(top.x, 2.3f)
    cableStack(-0.5f, pulley, attach, lift = p * 0.22f)
    add(Rod(v(-0.5f, 2.3f), pulley, 0.035f, Ink.FRAME, fit = false, bias = -0.5f))
}

/**
 * Pull-up or chin-up from a dead hang until the chin clears the bar, knees slightly bent. [chin]
 * grips close with palms facing in (seen from the side); the wide pull-up is seen from the front.
 * [assisted] kneels on the machine's counterweighted pad.
 */
internal fun pullUp(chin: Boolean, assisted: Boolean) = Motion.rep(Tempo.liftFirst(), Camera(if (chin) 26f else 56f)) { p ->
    val barY = if (assisted) 2.12f else 2.3f
    val barX = 0.02f
    val half = if (chin) 0.17f else 0.38f
    val lateral = abs(half - BodySize.SHOULDER_HALF)
    val shoulder = v(barX + lerp(-0.03f, -0.1f, p), barY - lerp(armReach(lateral, 0.03f, 0.985f), 0.2f, p))
    hangFrom(shoulder, lean = lerp(2f, -10f, p), nod = lerp(0f, -12f, p))
    for (side in Side.entries) {
        val pole = if (chin) v(0.8f, -1f, side.sign * 0.25f) else v(-0.1f, -1f, side.sign * 1.2f)
        body.arm(side, v(barX, barY, side.sign * half), pole)
    }
    if (assisted) {
        for (side in Side.entries) body.legAngles(side, limbDir(Basis.UPRIGHT, side, 2f), 90f, fold = -V3.X, foot = Foot(-V3.Y, V3.X))
        val knee = body.legOf(Side.NEAR).knee
        pad(v(knee.x - 0.32f, knee.y - 0.1f), v(knee.x + 0.12f, knee.y - 0.1f), width = 0.42f, bias = -0.2f)
        add(Rod(v(knee.x + 0.15f, knee.y - 0.12f), v(0.6f, 0.45f), 0.03f, Ink.FRAME, bias = -0.3f))
        weightColumn(-0.8f, lift = (1 - p) * 0.25f)
    } else {
        for (side in Side.entries) body.legAngles(side, limbDir(body.hips, side, 10f, 2f), lerp(25f, 45f, p), fold = -body.hips.f)
    }
    pullUpBar(barX, barY)
}

/** Places the trunk hanging below the shoulder line at [shoulder], leaning [lean] degrees. */
internal fun FrameBuilder.hangFrom(shoulder: V3, lean: Float, nod: Float = 0f, shrug: Float = 0f) {
    val basis = Basis.UPRIGHT.pitch(lean)
    val toShoulder = basis.u * (BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP + shrug)
    body.torso(shoulder - toShoulder, basis, nod = nod, shrug = shrug)
}

/** A pull-up bar across the picture at height [y], with its two posts (context). */
internal fun FrameBuilder.pullUpBar(x: Float, y: Float) {
    add(Rod(v(x, y, -0.7f), v(x, y, 0.7f), 0.02f, Ink.METAL, bias = -0.05f))
    for (s in listOf(-1f, 1f)) post(x, y + 0.05f, z = s * 0.72f, fit = false)
}

/** Dead hang: hanging still from the bar on straight arms, breathing, the legs relaxed. */
internal fun deadHang() = Motion.cycle(3.2f, camera = Camera(50f)) { phase ->
    val breath = sin(phase * 2 * PI.toFloat())
    val barY = 2.3f
    val shoulder = v(0f, barY - armReach(0.38f - BodySize.SHOULDER_HALF, 0f, 0.985f) + 0.04f)
    hangFrom(shoulder, lean = 1.5f * breath, shrug = 0.04f)
    for (side in Side.entries) body.arm(side, v(0f, barY, side.sign * 0.38f), v(-0.1f, -1f, side.sign * 1f))
    for (side in Side.entries) body.legAngles(side, limbDir(body.hips, side, 4f + 2f * breath, 3f), 12f, fold = -body.hips.f)
    pullUpBar(0f, barY)
}

/**
 * Seated cable row, feet on the platform: the handle comes from arm's length to the lower ribs as
 * the trunk moves from a slight forward lean to upright. [single] pulls with the near arm only.
 */
internal fun seatedCableRow(single: Boolean) = Motion.rep(Tempo.liftFirst(), Camera(24f)) { p ->
    val seatTop = 0.32f
    body.torso(v(0f, seatTop + SEAT_HIP), Basis.UPRIGHT.pitch(lerp(18f, -4f, p)), nod = lerp(-10f, 0f, p))
    for (side in Side.entries) body.leg(side, v(0.66f, 0.3f, side.sign * 0.12f), v(0.2f, 1f, 0f), Foot(dirXY(70f), dirXY(160f)))
    pad(v(-0.35f, seatTop - 0.04f), v(0.35f, seatTop - 0.04f), width = 0.3f, bias = -0.15f)
    add(Block(v(0.78f, 0.3f), Basis.UPRIGHT.pitch(-20f), v(0.03f, 0.2f, 0.22f), Ink.FRAME, bias = -0.2f))
    val shoulders = body.shoulders()
    val start = v(shoulders.x + armReach(0.1f) * 0.97f, shoulders.y - 0.12f)
    val end = body.onChest(0.2f, -0.3f)
    val handle = lerp(start, end.withZ(0f), p)
    if (single) {
        body.arm(Side.NEAR, handle.withZ(0.12f), v(-1f, -0.2f, 0.3f))
        body.arm(Side.FAR, body.legOf(Side.FAR).knee + V3.Y * 0.07f - V3.X * 0.04f, v(-0.3f, -1f, -0.5f))
        val hand = body.handOf(Side.NEAR)
        add(Rod(hand - V3.Y * 0.05f, hand + V3.Y * 0.05f, 0.02f, Ink.METAL))
        cable(v(0.92f, 0.38f), hand, fit = false)
    } else {
        for (side in Side.entries) body.arm(side, handle.withZ(side.sign * 0.07f), v(-1f, -0.3f, side.sign * 0.3f))
        add(Rod(handle.withZ(-0.09f), handle.withZ(0.09f), 0.018f, Ink.METAL))
        cable(v(0.92f, 0.38f), handle, fit = false)
    }
    add(Block(v(1.05f, 0.9f), Basis.UPRIGHT, v(0.1f, 0.9f, 0.2f), Ink.FRAME, fit = false, bias = -0.6f))
}

/** Chest-supported machine row: seated against the chest pad, handles pulled from arm's length to the ribs. */
internal fun machineRow() = Motion.rep(Tempo.liftFirst(), Camera(30f)) { p ->
    val seatTop = 0.5f
    seated(seatTop = seatTop, recline = -8f, footX = 0.45f, width = 0.32f, drawSeat = false)
    machineSeat(0f, seatTop)
    add(Block(body.onChest(0.2f, -0.22f), body.chest, v(0.04f, 0.2f, 0.15f), Ink.PAD, bias = -0.05f))
    val shoulders = body.shoulders()
    for (side in Side.entries) {
        val start = v(shoulders.x + armReach(0.04f) * 0.98f, shoulders.y - 0.1f, side.sign * 0.21f)
        val end = body.onChest(0.04f, -0.22f, side.sign * 0.26f)
        val hand = lerp(start, end, p)
        body.arm(side, hand, v(-1f, 0f, side.sign * 0.5f))
        machineHandle(hand, pivot = v(0.75f, 1.6f, side.sign * 0.3f))
    }
    add(Rod(v(0.75f, 0f), v(0.75f, 1.7f), 0.04f, Ink.FRAME, fit = false, bias = -0.5f))
    add(Rod(v(0.75f, body.onChest(0.2f, -0.22f).y), body.onChest(0.24f, -0.22f), 0.03f, Ink.FRAME, bias = -0.3f))
}

/** Free-weight row set-ups. */
internal enum class Row { BENT_OVER, PENDLAY, T_BAR }

/**
 * Barbell rows, hinged with a flat back: the bar rises from arm's length to the lower ribs.
 * PENDLAY starts each rep from the floor with the trunk near horizontal; T_BAR pulls one end of a
 * bar anchored to the floor behind.
 */
internal fun barbellRow(kind: Row) = Motion.rep(Tempo.liftFirst(), Camera(24f)) { p ->
    val lean = when (kind) {
        Row.PENDLAY -> 80f
        Row.T_BAR -> 48f
        Row.BENT_OVER -> 52f
    }
    val basis = Basis.UPRIGHT.pitch(lean)
    val pelvis = when (kind) {
        Row.PENDLAY -> v(-0.3f, 0.74f)
        else -> v(-0.2f, 0.83f)
    }
    body.torso(pelvis, basis, nod = -lean * 0.45f, protract = lerp(0.03f, -0.03f, p))
    body.standOn(-0.02f, width = if (kind == Row.T_BAR) 0.38f else 0.28f, out = 10f, pole = v(1f, 0.1f, 0.2f))
    val shoulders = body.shoulders()
    val half = if (kind == Row.T_BAR) 0.06f else 0.27f
    val lateral = abs(half - BodySize.SHOULDER_HALF)
    val hang = if (kind == Row.PENDLAY) v(shoulders.x + 0.02f, 0.225f) else v(shoulders.x + 0.03f, shoulders.y - armReach(lateral, 0.03f))
    val chest = body.onChest(0.14f + Kit.BAR_RADIUS, -0.3f)
    val bar = lerp(hang, v(chest.x, chest.y), p)
    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * half), v(-1f, 0.5f, side.sign * 0.35f))
    if (kind == Row.T_BAR) {
        val pivot = v(-1.15f, 0.05f)
        val end = bar + V3.Y * 0.06f
        val along = (end - pivot).unit()
        add(Rod(pivot, end + along * 0.18f, 0.022f, Ink.METAL, bias = -0.2f))
        add(Disc(end + along * 0.08f, along, 0.2f, 0.045f, Ink.PLATE, hub = 0.035f, bias = -0.3f))
        add(Rod(bar.withZ(-0.09f), bar.withZ(0.09f), 0.018f, Ink.METAL))
        add(Rod(bar, end, 0.015f, Ink.METAL))
    } else {
        barbell(bar, plateRadius = if (kind == Row.PENDLAY) 0.225f else 0.2f)
    }
}

/**
 * One-arm dumbbell row: far knee and hand on a bench, trunk flat; the near arm rows the dumbbell
 * from straight down to the hip.
 */
internal fun dumbbellRow() = Motion.rep(Tempo.liftFirst(), Camera(26f)) { p ->
    val basis = Basis.UPRIGHT.pitch(80f)
    body.torso(v(-0.12f, 0.9f), basis, nod = -30f, protract = lerp(0.02f, -0.03f, p))
    val benchZ = -0.16f
    body.leg(Side.FAR, v(-0.7f, 0.53f, -0.14f), v(0f, -1f, 0f), Foot(dirXY(205f), dirXY(295f)))
    body.stand(Side.NEAR, -0.06f, z = 0.24f, out = 4f)
    body.arm(Side.FAR, v(0.45f, 0.5f, -0.2f), v(-1f, 0f, -0.3f))
    val s = body.shoulder(Side.NEAR)
    val hang = s + v(0.02f, -armReach(0.03f, 0.02f), 0.03f)
    val end = body.onHips(0.12f, 0.15f, 0.24f)
    val hand = lerp(hang, end, p)
    body.arm(Side.NEAR, hand, v(-1f, 1f, 0.2f))
    dumbbell(hand, axis = V3.X)
    add(Block(v(0.05f, Kit.BENCH_TOP - 0.04f, benchZ), Basis.UPRIGHT, v(0.62f, 0.04f, 0.15f), Ink.PAD, bias = -0.4f))
    for (x in listOf(-0.42f, 0.52f)) add(Rod(v(x, 0.03f, benchZ), v(x, Kit.BENCH_TOP - 0.08f, benchZ), 0.028f, Ink.FRAME, bias = -0.45f))
}

/**
 * Inverted row: heels on the floor, body straight under a bar at hip height, pulling the chest up
 * to the bar; the body pivots on the heels.
 */
internal fun invertedRow() = Motion.rep(Tempo.liftFirst(), Camera(-24f)) { p ->
    val bar = v(0f, 1.0f)
    val half = 0.36f
    val line = BodySize.THIGH + BodySize.SHIN - 0.01f + BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP
    // Heel position so the chest meets the bar at the top; the bottom angle keeps the arms just straight.
    val topAngle = 34f
    val ankle = v(bar.x - 0.12f + line * cos(rad(topAngle)), 0.1f)
    val bottomAngle = solve(2f, topAngle) { a ->
        val shoulder = ankle + dirXY(180f - a) * line
        val d = (bar.withZ(half) - shoulder.withZ(BodySize.SHOULDER_HALF)).length
        d - (BodySize.UPPER_ARM + BodySize.FOREARM) * 0.975f
    }
    val angle = lerp(bottomAngle, topAngle, p)
    val dir = dirXY(180f - angle)
    body.torso(ankle + dir * (BodySize.THIGH + BodySize.SHIN - 0.01f), Basis.UPRIGHT.pitch(angle - 90f), nod = 10f)
    for (side in Side.entries) body.leg(side, ankle.withZ(side.sign * 0.1f), v(0f, 1f, 0f), Foot(dirXY(90f - angle), dirXY(180f - angle)))
    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * half), v(1f, -0.6f, side.sign * 0.4f))
    add(Rod(bar.withZ(-0.75f), bar.withZ(0.75f), 0.02f, Ink.METAL, bias = -0.05f))
    for (s in listOf(-1f, 1f)) post(bar.x, 1.9f, z = s * 0.72f)
}

/**
 * Finds x in [lo]..[hi] where [f] changes sign (bisection), for poses defined by a reach
 * condition. Returns the end closest to zero when there is no sign change.
 */
internal fun solve(lo: Float, hi: Float, f: (Float) -> Float): Float {
    var a = lo
    var b = hi
    var fa = f(a)
    val fb = f(b)
    if (fa * fb > 0f) return if (abs(fa) < abs(fb)) a else b
    repeat(30) {
        val m = (a + b) / 2
        val fm = f(m)
        if (fa * fm <= 0f) {
            b = m
        } else {
            a = m
            fa = fm
        }
    }
    return (a + b) / 2
}

/** What a pullover is done with. */
internal enum class Pullover { DUMBBELL, MACHINE, CABLE_STANDING }

/**
 * Pullover: arms nearly straight sweep in an arc between overhead and in front of the body —
 * lying on a bench with one dumbbell, seated in the pullover machine with the elbows on its pads, or
 * standing at a high pulley (straight-arm pulldown).
 */
internal fun pullover(kind: Pullover) = Motion.rep(if (kind == Pullover.DUMBBELL) Tempo.lowerFirst() else Tempo.liftFirst(), Camera(if (kind == Pullover.DUMBBELL) -24f else 26f)) { p ->
    when (kind) {
        Pullover.DUMBBELL -> {
            val basis = Basis.SUPINE
            body.torso(v(-0.05f, Kit.BENCH_TOP + 0.115f), basis)
            flatBench(-0.62f, 0.55f)
            body.standOn(0.5f, width = 0.5f, out = 10f, pole = v(0.35f, 1f, 0.35f))
            val sweep = lerp(92f, 182f, p)
            // The soft elbows bend the way the arm swings (towards the head over the chest, towards the floor overhead).
            for (side in Side.entries) {
                val upper = limbDir(basis, side, sweep, -13f)
                body.armAngles(side, upper, 16f, fold = basis.l cross upper)
            }
            val hands = (body.handOf(Side.NEAR) + body.handOf(Side.FAR)) * 0.5f
            val armDir = limbDir(basis, Side.NEAR, sweep).withZ(0f).unit()
            dumbbell(hands + armDir * 0.08f, axis = armDir, headRadius = 0.08f)
        }
        Pullover.MACHINE -> {
            val seatTop = 0.5f
            seated(seatTop = seatTop, recline = 8f, footX = 0.42f, width = 0.32f, drawSeat = false)
            machineSeat(0f, seatTop)
            backRest(v(-0.13f, seatTop), 8f, length = 0.8f)
            val sweep = lerp(168f, 25f, p)
            for (side in Side.entries) {
                val upper = limbDir(body.chest, side, sweep, 8f)
                body.armAngles(side, upper, 105f, fold = body.chest.l cross upper)
                val arm = body.armOf(side)
                roller(arm.elbow + (arm.elbow - arm.shoulder).unit() * 0.02f + body.chest.f * 0.06f, length = 0.1f)
            }
            val hands = (body.handOf(Side.NEAR) + body.handOf(Side.FAR)) * 0.5f
            handleBar(hands, 0.55f)
        }
        Pullover.CABLE_STANDING -> {
            body.torso(v(-0.08f, BodySize.STAND - 0.02f), Basis.UPRIGHT.pitch(22f), nod = -8f)
            body.standOn(0f, width = 0.3f)
            val sweep = lerp(118f, 8f, p)
            for (side in Side.entries) body.armAngles(side, limbDir(Basis.UPRIGHT, side, sweep, -4f), 8f, fold = V3.Y)
            val bar = (body.handOf(Side.NEAR) + body.handOf(Side.FAR)) * 0.5f
            handleBar(bar, 0.6f)
            cable(v(0.95f, 2.05f), bar, fit = false)
            add(Block(v(1.08f, 1.08f), Basis.UPRIGHT, v(0.1f, 1.08f, 0.2f), Ink.FRAME, fit = false, bias = -0.6f))
        }
    }
}
