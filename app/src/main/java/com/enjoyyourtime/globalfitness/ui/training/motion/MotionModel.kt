package com.enjoyyourtime.globalfitness.ui.training.motion

import com.enjoyyourtime.globalfitness.training.model.Muscle
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Colour roles of a scene; the composable maps them to the theme (see `motionPalette`), so the
 * engine stays free of Android types and the figure follows light, dark and dynamic colour.
 */
internal enum class Ink {
    BODY,
    BODY_FAR,
    MUSCLE,
    MUSCLE_FAR,

    /** Bars, handles, rails: thin and strong. */
    METAL,
    PLATE,
    PLATE_DARK,

    /** Bench and seat upholstery, rollers. */
    PAD,
    PAD_DARK,

    /** Machine frames and posts. */
    FRAME,
    FRAME_DARK,

    /** Weight stacks, flywheels, machine housings. */
    STACK,
    STACK_DARK,
    CABLE,
    BAND,

    /** The background colour, drawn around shapes to separate them from what is behind. */
    HALO,
    ;

    /** The shaded variant of a prop colour (side faces of blocks, back faces of discs). */
    val dark: Ink
        get() = when (this) {
            PLATE -> PLATE_DARK
            PAD -> PAD_DARK
            FRAME -> FRAME_DARK
            STACK -> STACK_DARK
            BODY -> BODY_FAR
            MUSCLE -> MUSCLE_FAR
            else -> this
        }
}

/** What a [DrawOp] draws. */
internal enum class OpType {
    /** Filled closed polygon; points are x0, y0, x1, y1... */
    FILL,

    /** Stroke along a closed polygon with round joins ([DrawOp.width] wide): the halo of a filled shape. */
    OUTLINE,

    /** Filled circle; points are cx, cy, r. */
    CIRCLE,

    /** Open polyline with round caps and joins, [DrawOp.width] wide. */
    LINE,
}

/**
 * One drawing operation of a [Scene], in world units on the picture plane (metres, y up). [fit]
 * false marks context (machine frames, rails) that may be cropped when the view is fitted.
 */
internal class DrawOp(val type: OpType, val points: FloatArray, val ink: Ink, val width: Float = 0f, val fit: Boolean = true)

/** The drawing operations of one frame, back to front. */
internal class Scene(val ops: List<DrawOp>)

/** A square region of the picture plane shown by the animation; [bottom] is its lowest y. */
internal data class ViewBox(val left: Float, val bottom: Float, val size: Float) {
    val right: Float get() = left + size
    val top: Float get() = bottom + size
}

/**
 * Orthographic camera turned [yaw] degrees from a pure side view towards the figure's front (0:
 * profile, 90: facing the viewer) and tilted [pitch] degrees to look down. A small yaw lets both
 * arms and both ends of a bar show; frontal movements (lateral raises, flyes) use a front view.
 */
internal class Camera(val yaw: Float = 20f, val pitch: Float = 0f) {
    private val cy = cos(rad(yaw))
    private val sy = sin(rad(yaw))
    private val cp = cos(rad(pitch))
    private val sp = sin(rad(pitch))

    /** World direction of the picture's x and y axes and towards the viewer. */
    val right = V3(cy, 0f, -sy)
    val up = V3(-sy * sp, cp, -cy * sp)
    val toViewer = V3(sy * cp, sp, cy * cp)

    fun x(p: V3): Float = x(p.x, p.z)

    fun y(p: V3): Float = y(p.x, p.y, p.z)

    /** [x] of the point ([px], ·, [pz]), for scene code that projects without building a [V3]. */
    fun x(px: Float, pz: Float): Float = px * cy - pz * sy

    fun y(px: Float, py: Float, pz: Float): Float = py * cp - (px * sy + pz * cy) * sp

    /** Larger is closer to the viewer. */
    fun depth(p: V3): Float = (p.x * sy + p.z * cy) * cp + p.y * sp

    companion object {
        /** Profile, slightly turned so the far arm and both ends of a bar show. */
        val SIDE = Camera(20f)
    }
}

/** The mannequin and its equipment in one frame; [maxMiss] is the worst unreachable hand or foot target. */
internal class Frame(val pose: Pose, val props: List<Prop>, val maxMiss: Float)

/** Collects a frame: patterns place the body through [body] and add equipment with [add]. */
internal class FrameBuilder {
    val body = Rig()
    private val props = ArrayList<Prop>()

    fun add(prop: Prop) {
        props += prop
    }

    fun build(): Frame = Frame(body.build(), props, body.maxMiss)
}

/**
 * An exercise animation: a pattern (code that builds a [Frame] from a driver value) and the timing
 * of that driver over one loop. A repetition [track] drives the pattern with 0 at the start
 * position and 1 at the other end; a steady [cycle] (cardio, holds) drives it with the phase 0..1.
 */
internal class Motion private constructor(
    val camera: Camera,
    val track: Track?,
    val cycle: CycleTrack?,
    private val pattern: FrameBuilder.(Float) -> Unit,
) {
    val duration: Float = track?.duration ?: cycle!!.duration

    /** The driver value at [time] seconds. */
    fun driver(time: Float): Float = track?.value(time) ?: cycle!!.phase(time)

    fun frame(time: Float): Frame = FrameBuilder().apply { pattern(driver(time)) }.build()

    fun scene(time: Float, muscles: Set<Muscle>): Scene = SceneBuilder(camera, muscles).build(frame(time))

    /**
     * The square that shows the whole loop: the bounds of every frame's figure and equipment
     * (context like rails may be cropped), with a margin, at least [MIN_VIEW] wide, its floor
     * kept near the bottom edge. Fitting builds [FIT_SAMPLES] scenes, so the first read belongs
     * off the main thread (see [fittedView]); the result is kept (thread-safe).
     */
    val view: ViewBox get() = fitted.value

    /** [view] when it has been computed already, else null. */
    val fittedView: ViewBox? get() = if (fitted.isInitialized()) fitted.value else null

    private val fitted = lazy {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until FIT_SAMPLES) {
            val scene = scene(duration * i / FIT_SAMPLES, emptySet())
            for (op in scene.ops) {
                if (!op.fit || op.ink == Ink.HALO) continue
                val r = if (op.type == OpType.CIRCLE) op.points[2] else op.width / 2
                if (op.type == OpType.CIRCLE) {
                    minX = min(minX, op.points[0] - r)
                    maxX = max(maxX, op.points[0] + r)
                    minY = min(minY, op.points[1] - r)
                    maxY = max(maxY, op.points[1] + r)
                } else {
                    var k = 0
                    while (k < op.points.size) {
                        minX = min(minX, op.points[k] - r)
                        maxX = max(maxX, op.points[k] + r)
                        minY = min(minY, op.points[k + 1] - r)
                        maxY = max(maxY, op.points[k + 1] + r)
                        k += 2
                    }
                }
            }
        }
        // The floor line shows whenever something rests near it.
        if (minY < FLOOR_SNAP) minY = min(minY, 0f)
        val size = max(MIN_VIEW, max(maxX - minX, maxY - minY) * (1 + 2 * MARGIN))
        val cx = (minX + maxX) / 2
        // A figure on the floor stands near the bottom edge; a low, wide one (lying, planks)
        // rises towards the middle, so the square is not half empty above it.
        val slack = size - (maxY - minY)
        val bottom = if (minY <= 0.001f) minY - max(size * FLOOR_MARGIN, slack * 0.4f) else (minY + maxY) / 2 - size / 2
        ViewBox(cx - size / 2, bottom, size)
    }

    companion object {
        private const val FIT_SAMPLES = 24
        private const val MARGIN = 0.06f
        private const val FLOOR_MARGIN = 0.07f
        private const val FLOOR_SNAP = 0.25f
        const val MIN_VIEW = 1.25f

        fun rep(track: Track, camera: Camera = Camera.SIDE, pattern: FrameBuilder.(Float) -> Unit) =
            Motion(camera, track, null, pattern)

        fun cycle(seconds: Float, cycles: Int = 1, camera: Camera = Camera.SIDE, pattern: FrameBuilder.(Float) -> Unit) =
            Motion(camera, null, Track.cycle(seconds, cycles), pattern)
    }
}
