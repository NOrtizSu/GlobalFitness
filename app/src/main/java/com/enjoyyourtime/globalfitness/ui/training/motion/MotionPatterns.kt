package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// Shared pieces of the movement patterns (MotionPress, MotionPull, MotionArms, MotionLegs,
// MotionCore, MotionCardio). A pattern is a function returning a Motion: the timing of one
// repetition plus code that builds the frame for a driver value p (0 = start position, 1 = the
// other end). Patterns place the pelvis and the spine, then hands and feet where the equipment
// needs them; inverse kinematics bends elbows and knees, so bones keep their length and hands
// stay on the bar.

/** What the hands hold in a free-weight pattern. */
internal enum class Grip { BARBELL, EZ_BAR, DUMBBELLS, SMITH, CABLE, MACHINE, NONE }

/** Typical timings: lowering first (presses, squats) or lifting first (pulls, curls, deadlifts). */
internal object Tempo {
    /** Start at the top, lower in 1.1 s, pause, press back up in 0.9 s. */
    fun lowerFirst() = Track.rep(go = 1.1f, hold = 0.15f, back = 0.9f, rest = 0.45f)

    /** Start at the bottom, lift in 0.9 s, squeeze, lower in 1.1 s. */
    fun liftFirst() = Track.rep(go = 0.9f, hold = 0.3f, back = 1.1f, rest = 0.4f)

    /** Short movements (calf raises, shrugs, wrist curls). */
    fun short() = Track.rep(go = 0.6f, hold = 0.35f, back = 0.75f, rest = 0.35f)
}

/**
 * Direction of a limb hanging from its root in [basis], swung [forward] degrees towards the basis'
 * forward axis (90: horizontal in front, 180: overhead) and [out] degrees away from the midline
 * towards [side] (negative: across the body).
 */
internal fun limbDir(basis: Basis, side: Side, forward: Float, out: Float = 0f): V3 {
    val f = rad(forward)
    val o = rad(out)
    return basis.dir(sin(f) * cos(o), -cos(f) * cos(o), side.sign * sin(o)).unit()
}

/**
 * The vertical distance at which a hand [lateral] metres to the side of its shoulder (and [ahead]
 * in front of it) is just within reach of a nearly straight arm.
 */
internal fun armReach(lateral: Float, ahead: Float = 0f, straightness: Float = 0.975f): Float {
    val r = (BodySize.UPPER_ARM + BodySize.FOREARM) * straightness
    return sqrt(max(0.01f, r * r - lateral * lateral - ahead * ahead))
}

/** The midpoint of the two shoulder joints. */
internal fun Rig.shoulders(): V3 = (shoulder(Side.NEAR) + shoulder(Side.FAR)) * 0.5f

/** Both feet flat on the floor at ankle position [x], [width] apart (centre to centre), turned out [out] degrees. */
internal fun Rig.standOn(x: Float, width: Float = 0.24f, out: Float = 8f, floor: Float = 0f, lift: Float = 0f, pole: V3? = null) {
    for (side in Side.entries) {
        stand(side, x, side.sign * width / 2, floor, lift, out, pole?.let { V3(it.x, it.y, it.z * side.sign) })
    }
}

/**
 * Seated on a seat whose top is at [seatTop] (hip joints a little above it at x = [hipX]), the
 * lower spine leaning back [recline] degrees, feet flat on the floor at [footX], [width] apart.
 */
internal fun FrameBuilder.seated(
    seatTop: Float = 0.45f,
    hipX: Float = 0f,
    recline: Float = 0f,
    footX: Float = 0.45f,
    width: Float = 0.26f,
    bend: Float = 0f,
    nod: Float = 0f,
    shrug: Float = 0f,
    drawSeat: Boolean = true,
) {
    body.torso(v(hipX, seatTop + SEAT_HIP), Basis.UPRIGHT.pitch(-recline), bend = bend, nod = nod, shrug = shrug)
    body.standOn(footX, width, out = 6f, pole = v(1f, 0.25f, 0.15f))
    if (drawSeat) pad(v(hipX - 0.2f, seatTop - 0.04f), v(hipX + 0.22f, seatTop - 0.04f), width = 0.34f, bias = -0.15f)
}

/** Height of the hip joints above the seat they sit on. */
internal const val SEAT_HIP = 0.085f

/** A seat on a single post (machines): the pad top at [top], centred at [x]. */
internal fun FrameBuilder.machineSeat(x: Float, top: Float, length: Float = 0.42f) {
    pad(v(x - length / 2, top - 0.045f), v(x + length / 2, top - 0.045f), width = 0.36f, thick = 0.09f, bias = -0.15f)
    add(Rod(v(x, 0.02f), v(x, top - 0.09f), 0.032f, Ink.FRAME, bias = -0.2f))
    add(Rod(v(x - 0.25f, 0.02f), v(x + 0.25f, 0.02f), 0.025f, Ink.FRAME, bias = -0.2f))
}

/** A back rest whose front face starts at [base] (seat level) and rises [length] metres, leaning back [recline] degrees. */
internal fun FrameBuilder.backRest(base: V3, recline: Float, length: Float = 0.6f) {
    val up = dirXY(90f + recline)
    val toward = dirXY(recline)
    val start = base - toward * 0.045f
    pad(start, start + up * length, width = 0.34f, thick = 0.09f, up = toward, bias = -0.18f)
}
