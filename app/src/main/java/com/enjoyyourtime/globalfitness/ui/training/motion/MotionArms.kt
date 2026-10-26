package com.enjoyyourtime.globalfitness.ui.training.motion

// Arm patterns: curls, triceps extensions and wrist curls.

/**
 * A standing curl: upper arms by the sides, elbows flexing from nearly straight to fully bent
 * (drifting slightly forward at the top). [hammer] holds the dumbbells with a neutral grip.
 */
internal fun standingCurl(grip: Grip, hammer: Boolean = false) = Motion.rep(Tempo.liftFirst(), Camera(34f)) { p ->
    body.torso(v(0f, BodySize.STAND), nod = 4f)
    body.standOn(0f, width = 0.26f)
    val elbow = lerp(12f, 138f, p)
    for (side in Side.entries) {
        body.armAngles(side, limbDir(body.chest, side, lerp(2f, 12f, p), 7f), elbow, fold = body.chest.f)
    }
    val near = body.handOf(Side.NEAR)
    val far = body.handOf(Side.FAR)
    when (grip) {
        Grip.BARBELL, Grip.EZ_BAR -> barbell((near + far) * 0.5f, length = if (grip == Grip.EZ_BAR) 1.25f else 1.5f, plateRadius = 0.15f, plates = 1)
        Grip.CABLE -> {
            val handle = (near + far) * 0.5f
            handleBar(handle, 0.5f)
            cable(v(0.42f, 0.12f), handle)
            add(Block(v(0.5f, 1.0f), Basis.UPRIGHT, v(0.1f, 1.0f, 0.18f), Ink.FRAME, fit = false, bias = -0.6f))
        }
        else -> for (side in Side.entries) dumbbell(body.handOf(side), axis = if (hammer) curlHandle(body.armOf(side)) else V3.Z)
    }
}

/** The axis of a dumbbell held in a neutral grip: across the fist, in the plane the forearm bends in. */
private fun curlHandle(arm: ArmPose): V3 = orthogonal(arm.apex, (arm.hand - arm.elbow).unit())

/**
 * A preacher curl: seated with the upper arms resting on the sloped pad in front, elbows flexing
 * from almost straight to fully bent. MACHINE draws the machine's handle instead of an EZ bar.
 */
internal fun preacherCurl(grip: Grip) = Motion.rep(Tempo.liftFirst(), Camera(30f)) { p ->
    val seatTop = 0.55f
    seated(seatTop = seatTop, recline = -14f, footX = 0.38f, width = 0.32f, drawSeat = false, nod = 8f)
    machineSeat(-0.02f, seatTop)
    val elbow = lerp(16f, 132f, p)
    for (side in Side.entries) body.armAngles(side, limbDir(Basis.UPRIGHT, side, 44f, -2f), elbow, fold = V3.Y)
    // The pad under the upper arms, sloping down and away from the chest.
    val armDir = limbDir(Basis.UPRIGHT, Side.NEAR, 44f)
    val top = body.shoulders() + armDir * 0.06f - dirXY(44f) * 0.08f
    pad(top, top + armDir * 0.3f, width = 0.42f, thick = 0.06f, up = dirXY(44f), bias = -0.08f)
    add(Rod(v(top.x + 0.12f, 0.03f), top + armDir * 0.18f - dirXY(44f) * 0.05f, 0.03f, Ink.FRAME, bias = -0.3f))
    val hands = (body.handOf(Side.NEAR) + body.handOf(Side.FAR)) * 0.5f
    if (grip == Grip.MACHINE) {
        handleBar(hands, 0.42f)
        add(Rod(hands + V3.Z * 0.21f, top + V3.Z * 0.22f + armDir * 0.28f, 0.025f, Ink.FRAME, bias = -0.25f))
    } else {
        barbell(hands, length = 1.25f, plateRadius = 0.14f, plates = 1)
    }
}

/** Incline dumbbell curl: lying back on an incline bench, arms hanging straight down behind the trunk, curling up. */
internal fun inclineCurl() = Motion.rep(Tempo.liftFirst(), Camera(-26f)) { p ->
    val incline = 55f
    val basis = Basis.UPRIGHT.pitch(incline - 90f)
    val junction = v(0.05f, Kit.BENCH_TOP)
    body.torso(junction + basis.f * 0.115f - basis.u * 0.03f, basis, nod = 18f)
    bench(junction, incline)
    body.standOn(0.55f, width = 0.36f, out = 8f)
    val elbow = lerp(8f, 132f, p)
    for (side in Side.entries) {
        body.armAngles(side, limbDir(Basis.UPRIGHT, side, -4f, 8f), elbow, fold = V3.X)
        dumbbell(body.handOf(side))
    }
}

/**
 * Concentration curl: seated, leaning forward with the near elbow braced on the inside of the
 * thigh, curling one dumbbell towards the shoulder; the other hand rests on the far knee.
 */
internal fun concentrationCurl() = Motion.rep(Tempo.liftFirst(), Camera(40f)) { p ->
    val seatTop = Kit.BENCH_TOP
    body.torso(v(-0.1f, seatTop + SEAT_HIP), Basis.UPRIGHT.pitch(32f), nod = 10f)
    for (side in Side.entries) body.stand(side, 0.42f, z = side.sign * 0.3f, out = 18f, pole = v(1f, 0.3f, side.sign * 0.6f))
    flatBench(-0.8f, 0.1f, seatTop)
    val knee = body.legOf(Side.NEAR).knee
    val brace = lerp(body.hip(Side.NEAR), knee, 0.72f) + V3.Z * (-0.07f) + V3.Y * 0.06f
    val upperDir = (brace - body.shoulder(Side.NEAR)).unit()
    body.armAngles(Side.NEAR, upperDir, lerp(8f, 135f, p), fold = v(-0.3f, 1f, -0.1f))
    body.arm(Side.FAR, body.legOf(Side.FAR).knee + V3.Y * 0.07f, v(-0.2f, -0.3f, -1f))
    dumbbell(body.handOf(Side.NEAR))
}

/** Lying triceps extension: upper arms angled slightly back over the head, the EZ bar lowered towards the forehead. */
internal fun skullCrusher() = Motion.rep(Tempo.lowerFirst(), Camera(-24f)) { p ->
    val basis = Basis.SUPINE
    body.torso(v(-0.05f, Kit.BENCH_TOP + 0.115f), basis)
    flatBench(-0.62f, 0.55f)
    body.standOn(0.5f, width = 0.5f, out = 10f, pole = v(0.35f, 1f, 0.35f))
    for (side in Side.entries) body.armAngles(side, v(-0.2f, 0.98f, side.sign * 0.01f), lerp(6f, 112f, p), fold = -V3.X)
    barbell((body.handOf(Side.NEAR) + body.handOf(Side.FAR)) * 0.5f, length = 1.25f, plateRadius = 0.14f, plates = 1)
}

/**
 * Overhead triceps extension: upper arms up by the ears, the weight lowered behind the head and
 * pressed back up — seated with one dumbbell in both hands, or facing away from a pulley with a rope.
 */
internal fun overheadExtension(grip: Grip) = Motion.rep(Tempo.lowerFirst(), Camera(26f)) { p ->
    val cable = grip == Grip.CABLE
    if (cable) {
        body.torso(v(0.05f, BodySize.STAND - 0.04f), Basis.UPRIGHT.pitch(26f), nod = -14f)
        body.stand(Side.NEAR, 0.32f, z = 0.13f)
        body.stand(Side.FAR, -0.15f, z = -0.13f)
    } else {
        seated(seatTop = Kit.BENCH_TOP, footX = 0.42f, width = 0.32f, drawSeat = false)
        flatBench(-0.6f, 0.3f)
    }
    val upper = if (cable) 150f else 168f
    for (side in Side.entries) {
        body.armAngles(side, limbDir(Basis.UPRIGHT, side, upper, -7f), lerp(10f, 128f, p), fold = -V3.X)
    }
    val hands = (body.handOf(Side.NEAR) + body.handOf(Side.FAR)) * 0.5f
    if (cable) {
        val knot = hands - V3.X * 0.14f
        add(Cord(listOf(knot, body.handOf(Side.NEAR)), 0.026f, Ink.CABLE))
        add(Cord(listOf(knot, body.handOf(Side.FAR)), 0.026f, Ink.CABLE))
        cable(v(-0.85f, 1.75f), knot, fit = false)
        add(Block(v(-1.0f, 1.05f), Basis.UPRIGHT, v(0.1f, 1.05f, 0.2f), Ink.FRAME, fit = false, bias = -0.6f))
    } else {
        val fore = (hands - (body.armOf(Side.NEAR).elbow + body.armOf(Side.FAR).elbow) * 0.5f).unit()
        dumbbell(hands + fore * 0.09f, axis = fore, headRadius = 0.075f)
    }
}

/** Bent-over dumbbell kickback: upper arms along the trunk, forearms swing from hanging to straight back. */
internal fun kickback() = Motion.rep(Tempo.liftFirst(), Camera(26f)) { p ->
    val basis = Basis.UPRIGHT.pitch(62f)
    body.torso(v(-0.18f, 0.84f), basis, nod = -30f)
    body.standOn(0f, width = 0.3f, pole = v(1f, 0.1f, 0.15f))
    for (side in Side.entries) {
        body.armAngles(side, v(-0.95f, -0.3f, side.sign * 0.06f), lerp(92f, 6f, p), fold = -V3.Y)
        val arm = body.armOf(side)
        dumbbell(arm.hand, axis = orthogonal(V3.Y, (arm.hand - arm.elbow).unit()).let { orthogonal(it, V3.Z) })
    }
}

/** Seated triceps machine: upper arms on the sloped pad, forearms pushing the handles from bent to straight. */
internal fun machineTricepsExtension() = Motion.rep(Tempo.liftFirst(), Camera(30f)) { p ->
    val seatTop = 0.55f
    seated(seatTop = seatTop, recline = -6f, footX = 0.38f, width = 0.32f, drawSeat = false)
    machineSeat(-0.02f, seatTop)
    for (side in Side.entries) body.armAngles(side, limbDir(Basis.UPRIGHT, side, 62f, 2f), lerp(118f, 10f, p), fold = V3.Y)
    val armDir = limbDir(Basis.UPRIGHT, Side.NEAR, 62f)
    val top = body.shoulders() + armDir * 0.05f - dirXY(62f) * 0.08f
    pad(top, top + armDir * 0.28f, width = 0.42f, thick = 0.06f, up = dirXY(62f), bias = -0.08f)
    add(Rod(v(top.x + 0.1f, 0.03f), top + armDir * 0.15f - dirXY(62f) * 0.05f, 0.03f, Ink.FRAME, bias = -0.3f))
    for (side in Side.entries) {
        val hand = body.handOf(side)
        machineHandle(hand, pivot = body.armOf(side).elbow + V3.Z * (side.sign * 0.12f), grip = V3.Z)
    }
}

/**
 * Barbell wrist curl: seated, forearms along the thighs with the wrists past the knees; only the
 * hands move, curling the bar up and lowering it. [reverse] holds it palms down (the bar rises
 * higher with the knuckles).
 */
internal fun wristCurl(reverse: Boolean) = Motion.rep(Tempo.short(), Camera(34f)) { p ->
    val seatTop = Kit.BENCH_TOP
    body.torso(v(-0.15f, seatTop + SEAT_HIP), Basis.UPRIGHT.pitch(38f), nod = 18f)
    body.standOn(0.33f, width = 0.32f, out = 6f)
    flatBench(-0.6f, 0.05f, seatTop)
    val bend = if (reverse) lerp(-25f, 50f, p) else lerp(-55f, 62f, p)
    for (side in Side.entries) {
        val leg = body.legOf(side)
        val wrist = leg.knee + (leg.knee - leg.hip).unit() * 0.06f + V3.Y * 0.075f
        val fore = (leg.knee - leg.hip).unit()
        body.armWrist(side, wrist.withZ(side.sign * 0.13f), v(-0.3f, -1f, side.sign * 0.5f), dirXY(angleXY(fore) + bend))
    }
    barbell((body.handOf(Side.NEAR) + body.handOf(Side.FAR)) * 0.5f, length = 1.3f, plateRadius = 0.1f, plates = 1)
}

/**
 * A cable triceps pushdown facing a high pulley: elbows pinned by the ribs, forearms from above
 * horizontal down to straight arms. [rope] spreads the hands apart at the bottom.
 */
internal fun pushdown(rope: Boolean) = Motion.rep(Tempo.liftFirst(), Camera(24f)) { p ->
    val lean = 12f
    body.torso(v(-0.05f, BodySize.STAND - 0.01f), Basis.UPRIGHT.pitch(lean), nod = 4f)
    body.standOn(0f, width = 0.26f, out = 6f)
    val elbow = lerp(100f, 4f, p)
    val out = if (rope) lerp(-4f, 6f, p) else -4f
    for (side in Side.entries) body.armAngles(side, limbDir(Basis.UPRIGHT, side, 2f, out), elbow, fold = V3.X)
    val near = body.handOf(Side.NEAR)
    val far = body.handOf(Side.FAR)
    val pulley = v(0.62f, 2.05f)
    if (rope) {
        val knot = (near + far) * 0.5f + V3.Y * 0.16f
        add(Cord(listOf(knot, near), 0.026f, Ink.CABLE, bias = 0.02f))
        add(Cord(listOf(knot, far), 0.026f, Ink.CABLE, bias = -0.02f))
        cable(pulley, knot, fit = false)
    } else {
        val handle = (near + far) * 0.5f
        handleBar(handle, 0.5f)
        cable(pulley, handle, fit = false)
    }
    add(Block(v(0.72f, 1.1f), Basis.UPRIGHT, v(0.1f, 1.1f, 0.2f), Ink.FRAME, fit = false, bias = -0.6f))
}

/** The centre of the [side] fist as placed so far. */
internal fun Rig.handOf(side: Side): V3 = armOf(side).hand
