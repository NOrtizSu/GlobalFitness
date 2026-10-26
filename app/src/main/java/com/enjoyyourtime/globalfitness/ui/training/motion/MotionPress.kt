package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

// Chest patterns: bench presses, machine presses, push-ups, dips and flyes.

/**
 * Lying on a bench pressing up: [incline] degrees of back rest (0 flat, 30 incline, negative
 * decline with the legs hooked), hands [width] from the midline. The bar travels from over the
 * shoulders down to the chest (straight down on a Smith machine, whose rails are drawn). Seen from
 * the head side, so the near plate sits over the trunk rather than the face.
 */
internal fun benchPress(grip: Grip, incline: Float = 0f, width: Float = 0.4f) = Motion.rep(Tempo.lowerFirst(), Camera(-32f)) { p ->
    val basis = Basis.UPRIGHT.pitch(incline - 90f)
    val decline = incline < 0f
    // Where the seat meets the back pad; the body lies on the pad above it.
    val junction = v(0.05f, if (decline) 0.62f else Kit.BENCH_TOP)
    val pelvis = junction + basis.f * 0.115f - basis.u * 0.03f
    body.torso(pelvis, basis, nod = if (incline > 10f) 10f else 0f)
    bench(junction, incline)
    if (decline) {
        // Knees over the pad, ankles under the roller.
        for (side in Side.entries) body.leg(side, v(0.5f, 0.42f, side.sign * 0.1f), v(0.3f, 1f, 0f))
        roller(v(0.52f, 0.36f))
        roller(v(0.36f, 0.72f))
    } else {
        body.standOn(pelvis.x + 0.52f, width = 0.5f, out = 10f, pole = v(0.35f, 1f, 0.35f))
    }
    val shoulders = body.shoulders()
    val dumbbells = grip == Grip.DUMBBELLS
    val half = if (dumbbells) lerp(0.21f, 0.3f, p) else width
    val chest = body.onChest(0.13f + Kit.BAR_RADIUS + (if (dumbbells) 0.02f else 0f), if (incline > 10f) -0.08f else -0.14f)
    val lateral = half - BodySize.SHOULDER_HALF
    val top = if (grip == Grip.SMITH) {
        v(chest.x, shoulders.y + armReach(lateral, ahead = chest.x - shoulders.x))
    } else {
        v(shoulders.x + 0.03f, shoulders.y + armReach(lateral, ahead = 0.03f))
    }
    val bar = lerp(top, v(chest.x, chest.y), p)
    if (dumbbells) {
        for (side in Side.entries) {
            val hand = bar.withZ(side.sign * half)
            body.arm(side, hand, v(0.3f, -1f, side.sign * 0.8f))
            dumbbell(hand)
        }
    } else {
        barbell(bar, plateRadius = 0.18f)
        for (side in Side.entries) body.arm(side, bar.withZ(side.sign * half), v(0.35f, -1f, side.sign * (if (width < 0.3f) 0.3f else 0.7f)))
        if (grip == Grip.SMITH) smithRails(bar.x, top.y + 0.35f) else rack(shoulders.x - 0.12f, top.y - 0.05f)
    }
}

/** A bench with a flat, inclined or declined back pad meeting the seat at [junction]. */
internal fun FrameBuilder.bench(junction: V3, incline: Float) {
    if (incline == 0f) {
        flatBench(junction.x - 0.85f, junction.x + 0.4f, junction.y)
        return
    }
    val along = dirXY(180f - incline)
    val normal = dirXY(90f - incline)
    val backStart = junction - normal * Kit.PAD_HALF_THICK
    pad(backStart, backStart + along * 0.82f, up = normal, bias = -0.1f)
    val seatEnd = junction + V3.X * 0.4f
    pad(junction - V3.Y * Kit.PAD_HALF_THICK, seatEnd - V3.Y * Kit.PAD_HALF_THICK, bias = -0.1f)
    // Frame: a post under the seat, a strut under the back pad, a floor rail.
    add(Rod(v(junction.x + 0.15f, 0.03f), v(junction.x + 0.15f, junction.y - 0.08f), 0.03f, Ink.FRAME, bias = -0.12f))
    val under = backStart + along * 0.55f - normal * 0.06f
    add(Rod(v(under.x, 0.03f), under, 0.028f, Ink.FRAME, bias = -0.12f))
    add(Rod(v(under.x - 0.1f, 0.03f), v(seatEnd.x, 0.03f), 0.025f, Ink.FRAME, bias = -0.12f))
}

/** Bench press uprights with J-hooks behind the bar, at [x] up to [height]. */
internal fun FrameBuilder.rack(x: Float, height: Float) {
    for (s in listOf(-1f, 1f)) {
        post(x, height, z = s * 0.55f, radius = 0.028f)
        add(Rod(v(x, height - 0.04f, s * 0.55f), v(x + 0.07f, height - 0.02f, s * 0.55f), 0.02f, Ink.FRAME, fit = false, bias = BEHIND))
    }
}

/** The two vertical rails of a Smith machine at [x], up to [height]. */
internal fun FrameBuilder.smithRails(x: Float, height: Float) {
    for (s in listOf(-1f, 1f)) post(x - 0.03f, height, z = s * 0.62f, radius = 0.026f)
}

/**
 * A seated chest press machine: back against the pad, handles pushed from beside the chest to
 * arm's length ([incline]: forwards and up).
 */
internal fun machineChestPress(incline: Boolean) = Motion.rep(Tempo.liftFirst(), Camera(28f)) { p ->
    val seatTop = 0.48f
    seated(seatTop = seatTop, recline = 12f, footX = 0.42f, width = 0.3f, drawSeat = false)
    machineSeat(0f, seatTop)
    backRest(v(-0.14f, seatTop), 12f, length = 0.72f)
    val shoulders = body.shoulders()
    val reach = dirXY(if (incline) 28f else 2f) * 0.55f
    for (side in Side.entries) {
        val start = body.onChest(0.16f, -0.12f, side.sign * 0.3f)
        val end = (shoulders + reach).withZ(side.sign * 0.2f)
        val hand = lerp(start, end, p)
        body.arm(side, hand, v(-0.4f, -1f, side.sign * 0.9f))
        machineHandle(hand, pivot = v(-0.05f, 0.62f, side.sign * 0.4f))
    }
    weightColumn(-0.62f, lift = p * 0.18f)
}

/** A vertical machine grip in the fist at [hand] on a lever arm from [pivot]. */
internal fun FrameBuilder.machineHandle(hand: V3, pivot: V3, grip: V3 = V3.Y) {
    val g = grip.unit() * 0.08f
    add(Rod(hand - g, hand + g, 0.02f, Ink.METAL, bias = -0.02f))
    add(Rod(pivot, hand + g, 0.026f, Ink.FRAME, bias = -0.25f))
}

/** A weight-stack column standing at [x] (machine context), its top plates raised by [lift]. */
internal fun FrameBuilder.weightColumn(x: Float, lift: Float, height: Float = 1.9f) {
    add(Block(v(x, height / 2), Basis.UPRIGHT, v(0.09f, height / 2, 0.2f), Ink.FRAME, fit = false, bias = -0.6f))
    add(Block(v(x + 0.1f, 0.3f), Basis.UPRIGHT, v(0.05f, 0.25f, 0.14f), Ink.STACK, fit = false, bias = -0.55f))
    add(Block(v(x + 0.1f, 0.62f + lift), Basis.UPRIGHT, v(0.05f, 0.06f, 0.14f), Ink.STACK, fit = false, bias = -0.55f))
}

/**
 * Push-up: a straight line from the toes to the head, pivoting on the toes, hands planted under the
 * shoulders; the chest lowers to just above the floor.
 */
internal fun pushUp() = Motion.rep(Tempo.lowerFirst(), Camera(22f)) { p ->
    val ankle = v(-0.85f, 0.185f)
    val line = BodySize.THIGH + BodySize.SHIN - 0.01f + BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP
    val top = asin((0.62f - ankle.y) / line)
    val bottom = asin((0.25f - ankle.y) / line)
    val angle = deg(lerp(top, bottom, p))
    val hands = ankle.x + line * cos(top)
    plankBody(ankle, angle, nod = 8f)
    for (side in Side.entries) body.arm(side, v(hands, BodySize.FIST_RADIUS, side.sign * 0.27f), v(-1f, 0.2f, side.sign * 0.8f))
}

/**
 * A straight body from the ankles (on the toes at [ankle]) rising at [angle] degrees, face down:
 * push-ups, planks, mountain climbers.
 */
internal fun FrameBuilder.plankBody(ankle: V3, angle: Float, nod: Float = 0f, legs: Boolean = true) {
    val dir = dirXY(angle)
    val pelvis = ankle + dir * (BodySize.THIGH + BodySize.SHIN - 0.01f)
    body.torso(pelvis, Basis.UPRIGHT.pitch(90f - angle), nod = nod)
    if (legs) for (side in Side.entries) body.leg(side, ankle.withZ(side.sign * 0.1f), v(0f, -1f, 0f), onToes(angle))
}

/** A foot standing on its toes behind a body leaning at [angle] degrees: the sole faces back. */
internal fun onToes(angle: Float): Foot = Foot(dirXY(angle - 85f), dirXY(angle + 5f))

/**
 * Dips: hands on two parallel bars, the body lowering until the upper arms are about level, leaning
 * forward a little; knees bent, feet behind. [assisted] kneels on the machine's pad instead.
 */
internal fun dip(assisted: Boolean) = Motion.rep(Tempo.lowerFirst(), Camera(24f)) { p ->
    val barY = if (assisted) 1.2f else 1.25f
    val handX = 0.05f
    val lateral = 0.28f - BodySize.SHOULDER_HALF
    val lean = lerp(6f, 22f, p)
    val shoulder = v(handX + lerp(-0.02f, -0.1f, p), barY + lerp(armReach(lateral, 0.02f), 0.09f, p))
    val basis = Basis.UPRIGHT.pitch(lean)
    val toShoulder = basis.u * (BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP)
    body.torso(shoulder - toShoulder, basis, nod = -lean * 0.4f)
    if (assisted) {
        for (side in Side.entries) body.legAngles(side, limbDir(Basis.UPRIGHT, side, 4f), 92f, fold = -V3.X, foot = Foot(-V3.Y, V3.X))
        val knee = body.legOf(Side.NEAR).knee
        pad(v(knee.x - 0.3f, knee.y - 0.1f), v(knee.x + 0.12f, knee.y - 0.1f), width = 0.42f, bias = -0.2f)
        add(Rod(v(knee.x + 0.15f, knee.y - 0.12f), v(0.55f, 0.5f), 0.03f, Ink.FRAME, bias = -0.3f))
        weightColumn(-0.75f, lift = (1 - p) * 0.25f)
    } else {
        for (side in Side.entries) body.legAngles(side, limbDir(body.hips, side, 18f, 3f), 78f, fold = -body.hips.f)
    }
    for (side in Side.entries) {
        val hand = v(handX, barY, side.sign * 0.28f)
        body.arm(side, hand, v(-1f, 0f, side.sign * 0.25f))
        add(Rod(v(handX - 0.32f, barY, side.sign * 0.28f), v(handX + 0.28f, barY, side.sign * 0.28f), 0.022f, Ink.METAL, bias = -0.05f))
    }
    for (s in listOf(-1f, 1f)) post(handX + 0.22f, barY, z = s * 0.28f, fit = false)
}

/** Seated dip machine: handles beside the chest pushed down until the arms are straight by the hips. */
internal fun seatedDipMachine() = Motion.rep(Tempo.liftFirst(), Camera(26f)) { p ->
    val seatTop = 0.5f
    seated(seatTop = seatTop, recline = 6f, footX = 0.42f, width = 0.3f, drawSeat = false)
    machineSeat(0f, seatTop)
    backRest(v(-0.13f, seatTop), 6f, length = 0.7f)
    for (side in Side.entries) {
        val start = body.onChest(-0.02f, -0.2f, side.sign * 0.29f)
        val end = body.onHips(0.02f, 0.06f, side.sign * 0.3f)
        val hand = lerp(start, end, p)
        body.arm(side, hand, v(-1f, 0.1f, side.sign * 0.3f))
        machineHandle(hand, pivot = v(0.4f, 0.5f, side.sign * 0.34f), grip = V3.X)
    }
    weightColumn(-0.62f, lift = p * 0.2f)
}

/**
 * Bench dip: hands on the edge of a bench behind, heels on the floor in front; the hips sink in
 * front of the bench by bending the elbows straight back.
 */
internal fun benchDip() = Motion.rep(Tempo.lowerFirst(), Camera(24f)) { p ->
    val hand = v(-0.3f, Kit.BENCH_TOP + 0.03f)
    val lateral = 0.22f - BodySize.SHOULDER_HALF
    val shoulder = v(hand.x + 0.06f, hand.y + lerp(armReach(lateral, 0.06f), 0.2f, p))
    val basis = Basis.UPRIGHT.pitch(lerp(2f, 10f, p))
    body.torso(shoulder - basis.u * (BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP), basis)
    body.standOn(0.38f, width = 0.24f, out = 4f)
    for (side in Side.entries) body.arm(side, hand.withZ(side.sign * 0.22f), v(-1f, 0f, side.sign * 0.2f))
    flatBench(-1.2f, -0.25f)
}

/**
 * Lying dumbbell fly: arms open in an arc from above the chest to level with the bench, elbows
 * softly bent. Seen from the feet, a little above, so the arc spreads across the picture.
 */
internal fun dumbbellFly() = Motion.rep(Tempo.lowerFirst(), Camera(70f, 32f)) { p ->
    val junction = v(0.05f, Kit.BENCH_TOP)
    val basis = Basis.SUPINE
    body.torso(junction + basis.f * 0.115f - basis.u * 0.03f, basis)
    flatBench(junction.x - 0.85f, junction.x + 0.4f)
    body.standOn(junction.x + 0.55f, width = 0.5f, out = 10f, pole = v(0.35f, 1f, 0.35f))
    val open = lerp(8f, 80f, p)
    for (side in Side.entries) {
        val s = body.shoulder(side)
        val hand = s + v(0.02f, cos(rad(open)), side.sign * sin(rad(open))) * 0.54f
        body.arm(side, hand, v(0.5f, -1f, side.sign * 0.2f))
        dumbbell(hand, axis = V3.X)
    }
}

/** Height of a cable crossover's pulleys. */
internal enum class Pulley(val y: Float, val start: Float, val end: Float) {
    /** High pulleys: hands sweep down and together in front of the hips. */
    HIGH(2.0f, 22f, -38f),

    /** Low pulleys: hands sweep up to meet at chest height. */
    LOW(0.25f, -40f, 12f),

    /** Pulleys at chest height: a horizontal fly. */
    MID(1.35f, 4f, -6f),
}

/**
 * Standing cable fly between two towers: arms sweep from out to the sides to meet in front, at an
 * angle set by the [pulley] height. Seen from the front.
 */
internal fun cableFly(pulley: Pulley) = Motion.rep(Tempo.liftFirst(), Camera(76f)) { p ->
    val basis = Basis.UPRIGHT.pitch(12f)
    body.torso(v(-0.04f, BodySize.STAND - 0.015f), basis)
    body.stand(Side.NEAR, 0.16f, z = 0.13f, out = 6f)
    body.stand(Side.FAR, -0.16f, z = -0.13f, out = 6f)
    val sweep = lerp(8f, 84f, p)
    val rise = lerp(pulley.start, pulley.end, p)
    for (side in Side.entries) {
        val s = body.shoulder(side)
        val dir = v(cos(rad(rise)) * sin(rad(sweep)), sin(rad(rise)), side.sign * cos(rad(rise)) * cos(rad(sweep)))
        val hand = s + dir * 0.54f
        body.arm(side, hand, v(-1f, 0.4f, side.sign * 0.5f))
        add(Rod(hand - V3.Y * 0.06f, hand + V3.Y * 0.06f, 0.02f, Ink.METAL))
        add(Cord(listOf(v(-0.1f, pulley.y, side.sign * 1.1f), hand), 0.012f, Ink.CABLE, fit = false, bias = -0.1f))
    }
}

/**
 * Pec deck (butterfly): seated, arms out to the sides at shoulder height with the elbows soft,
 * hands on the machine's handles (its lever arms are left out: they would cross the face); the
 * arms sweep together in front of the chest. Seen from the front and a little above, so the sweep
 * reads.
 */
internal fun pecDeck() = Motion.rep(Tempo.liftFirst(), Camera(84f, 18f)) { p ->
    val seatTop = 0.5f
    seated(seatTop = seatTop, recline = 4f, footX = 0.36f, width = 0.32f, drawSeat = false)
    machineSeat(0f, seatTop)
    backRest(v(-0.13f, seatTop), 4f, length = 0.75f)
    val sweep = lerp(-6f, 74f, p)
    for (side in Side.entries) {
        val upper = v(sin(rad(sweep)), -0.06f, side.sign * cos(rad(sweep)))
        body.armAngles(side, upper, 22f, fold = v(cos(rad(sweep)), 0f, -side.sign * sin(rad(sweep))))
        val hand = body.handOf(side)
        add(Rod(hand - V3.Y * 0.07f, hand + V3.Y * 0.07f, 0.02f, Ink.METAL))
    }
}
