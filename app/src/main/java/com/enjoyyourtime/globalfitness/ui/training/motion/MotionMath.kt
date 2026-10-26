package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Plain 3D math for the exercise animations (no Android dependencies, so it runs in JVM tests).
//
// World axes, in metres: X forward (where a standing figure faces), Y up, Z towards the viewer of a
// side view, which is the figure's right side (the "near" side). The floor is Y = 0.

/** A point or direction in world space. */
internal data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)

    operator fun times(s: Float) = V3(x * s, y * s, z * s)

    operator fun unaryMinus() = V3(-x, -y, -z)

    infix fun dot(o: V3): Float = x * o.x + y * o.y + z * o.z

    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    val length: Float get() = sqrt(x * x + y * y + z * z)

    /** Unit vector; a (near) zero vector stays as it is. */
    fun unit(): V3 {
        val l = length
        return if (l < 1e-6f) this else V3(x / l, y / l, z / l)
    }

    fun distanceTo(o: V3): Float = (this - o).length

    /** The same point with another height or depth. */
    fun withY(value: Float) = V3(x, value, z)

    fun withZ(value: Float) = V3(x, y, value)

    companion object {
        val ZERO = V3(0f, 0f, 0f)
        val X = V3(1f, 0f, 0f)
        val Y = V3(0f, 1f, 0f)
        val Z = V3(0f, 0f, 1f)
    }
}

/** Shorthand for literal points in patterns. */
internal fun v(x: Float, y: Float, z: Float = 0f) = V3(x, y, z)

internal fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

internal fun lerp(a: V3, b: V3, t: Float): V3 = V3(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.z, b.z, t))

internal fun rad(degrees: Float): Float = degrees * (PI.toFloat() / 180f)

internal fun deg(radians: Float): Float = radians * (180f / PI.toFloat())

/** A unit vector in the X-Y plane, [degrees] counter-clockwise from +X (90° = up). */
internal fun dirXY(degrees: Float): V3 = V3(cos(rad(degrees)), sin(rad(degrees)), 0f)

/** The direction of [v] in the X-Y plane, in degrees counter-clockwise from +X (the inverse of [dirXY]). */
internal fun angleXY(v: V3): Float = deg(atan2(v.y, v.x))

/** Rotates [v] about the unit [axis] by [angle] radians (right-hand rule, Rodrigues' formula). */
internal fun rotate(v: V3, axis: V3, angle: Float): V3 {
    val c = cos(angle)
    val s = sin(angle)
    return v * c + (axis cross v) * s + axis * ((axis dot v) * (1 - c))
}

/**
 * An orientation as three unit axes: [f] forward (where the chest faces), [u] up (along the spine)
 * and [l] lateral, towards the figure's near (right) side. Rotations are about the basis' own axes.
 */
internal data class Basis(val f: V3, val u: V3, val l: V3) {
    /** A direction given in this basis' coordinates (forward, up, lateral) in world space. */
    fun dir(forward: Float, up: Float, lateral: Float): V3 = f * forward + u * up + l * lateral

    /** Leans forward by [degrees] (the up axis tips towards forward); negative leans back. */
    fun pitch(degrees: Float): Basis {
        if (degrees == 0f) return this
        val a = -rad(degrees)
        return Basis(rotate(f, l, a), rotate(u, l, a), l)
    }

    /** Bends sideways by [degrees]: the up axis tips towards the near side. */
    fun roll(degrees: Float): Basis {
        if (degrees == 0f) return this
        val a = rad(degrees)
        return Basis(f, rotate(u, f, a), rotate(l, f, a))
    }

    /** Turns about the up axis by [degrees]: forward swings towards the near side. */
    fun turn(degrees: Float): Basis {
        if (degrees == 0f) return this
        val a = -rad(degrees)
        return Basis(rotate(f, u, a), u, rotate(l, u, a))
    }

    companion object {
        /** Standing, facing +X. */
        val UPRIGHT = Basis(V3.X, V3.Y, V3.Z)

        /** Lying on the back, head towards -X, chest up. */
        val SUPINE = UPRIGHT.pitch(-90f)

        /** Lying face down, head towards +X. */
        val PRONE = UPRIGHT.pitch(90f)
    }
}

/** Result of [twoBone]: the middle joint, the end actually reached and the middle joint's apex direction. */
internal class TwoBone(val mid: V3, val end: V3, val apex: V3)

/**
 * Two-bone inverse kinematics (shoulder-elbow-hand, hip-knee-ankle): places the middle joint so the
 * bones keep their lengths [l1] and [l2] and the chain ends at [target], bending towards [pole] (the
 * direction the elbow or knee points). An unreachable target is approached as far as the bones allow
 * (the limb straightens towards it), so lengths never change.
 */
internal fun twoBone(root: V3, target: V3, l1: Float, l2: Float, pole: V3): TwoBone {
    val toTarget = target - root
    val d = toTarget.length
    val e = if (d < 1e-5f) perpendicular(pole) else toTarget * (1f / d)
    val reach = d.coerceIn(abs(l1 - l2) + 1e-3f, l1 + l2 - 1e-4f)
    val cosA = ((l1 * l1 + reach * reach - l2 * l2) / (2 * l1 * reach)).coerceIn(-1f, 1f)
    val sinA = sqrt(1 - cosA * cosA)
    var bend = pole - e * (pole dot e)
    if (bend.length < 1e-4f) bend = perpendicular(e)
    bend = bend.unit()
    return TwoBone(mid = root + e * (l1 * cosA) + bend * (l1 * sinA), end = root + e * reach, apex = bend)
}

/** Some unit vector perpendicular to [v] (used when a pole is degenerate). */
internal fun perpendicular(v: V3): V3 {
    val other = if (abs(v.y) < 0.9f) V3.Y else V3.X
    return (v cross other).unit().let { if (it.length < 0.5f) V3.Z else it }
}

/** Component of [v] perpendicular to the unit [axis], as a unit vector ([fallback] when parallel). */
internal fun orthogonal(v: V3, axis: V3, fallback: V3 = perpendicular(axis)): V3 {
    val p = v - axis * (v dot axis)
    return if (p.length < 1e-4f) fallback else p.unit()
}
