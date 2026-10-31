package com.enjoyyourtime.globalfitness.ui.training.motion

import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.model.Muscle
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Key frames of every catalog animation, for reviewing them offline: each exercise at the start,
 * ¼, ½, ¾ and the end of every moving stretch of its repetition (a few points of the loop for
 * cycles), drawn with the very [SceneBuilder] ops the app paints (picture-plane coordinates, the
 * fitted view and the motion's own camera), plus the same frame seen from the front or the side
 * and from above, and the joints in world space.
 *
 * The frames are always built (so this also checks every key frame can be drawn); they are written
 * only when the environment variable MOTION_DUMP_DIR names a folder, one JSON file per exercise
 * plus `index.json`, for `tools/motion/render_motion.py` to turn into contact sheets:
 *
 *     MOTION_DUMP_DIR=<dir> ./gradlew :app:testDebugUnitTest --tests '*MotionFramesDumpTest*' --rerun
 *     python tools/motion/render_motion.py <dir>
 *
 * Coordinates are written in millimetres (integers) to keep the files small.
 */
class MotionFramesDumpTest {
    @Test
    fun keyFramesOfEveryAnimation() {
        val dir = System.getenv("MOTION_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        dir?.mkdirs()
        val index = StringBuilder("[")
        ExerciseCatalog.all.forEachIndexed { n, e ->
            val motion = requireNotNull(MotionCatalog.forId(e.id)) { "no motion for ${e.id}" }
            val json = dump(e.id, n, motion, e.primary)
            assertTrue("${e.id}: no frames", json.length > 100)
            if (dir != null) {
                File(dir, "${e.id}.json").writeText(json)
                if (n > 0) index.append(',')
                index.append('"').append(e.id).append('"')
            }
        }
        if (dir != null) File(dir, "index.json").writeText(index.append(']').toString())
    }

    private fun dump(id: String, order: Int, m: Motion, muscles: Set<Muscle>): String {
        val views = listOf("app" to m.camera, "other" to otherCamera(m.camera), "top" to Camera(0f, 88f))
        val sb = StringBuilder(1 shl 16)
        val view = m.view
        sb.append("{\"id\":\"").append(id).append("\",\"order\":").append(order)
        sb.append(",\"duration\":").append(((m.duration * 1000f).roundToInt() / 1000f))
        sb.append(",\"cycle\":").append(m.cycle != null)
        sb.append(",\"view\":[").append(mm(view.left)).append(',').append(mm(view.bottom)).append(',').append(mm(view.size)).append(']')
        sb.append(",\"cameras\":{")
        views.forEachIndexed { i, (name, cam) ->
            if (i > 0) sb.append(',')
            sb.append('"').append(name).append("\":[").append(cam.yaw).append(',').append(cam.pitch).append(']')
        }
        sb.append("},\"frames\":[")
        keyTimes(m).forEachIndexed { i, (t, label) ->
            if (i > 0) sb.append(',')
            val frame = m.frame(t)
            sb.append("{\"t\":").append(((t * 1000f).roundToInt() / 1000f))
            sb.append(",\"label\":\"").append(label).append('"')
            sb.append(",\"driver\":").append(((m.driver(t) * 1000f).roundToInt() / 1000f))
            sb.append(",\"miss\":").append(mm(frame.maxMiss))
            sb.append(",\"joints\":")
            joints(sb, frame.pose)
            sb.append(",\"scenes\":{")
            views.forEachIndexed { j, (name, cam) ->
                if (j > 0) sb.append(',')
                sb.append('"').append(name).append("\":")
                ops(sb, SceneBuilder(cam, muscles).build(frame))
            }
            sb.append("}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /** A view across the app's: the front for a side view, the side for a front view. */
    private fun otherCamera(c: Camera): Camera = if (abs(c.yaw) < 50f) Camera(90f, 8f) else Camera(0f, 8f)

    /**
     * Times of the key frames: for a repetition, every key plus ¼, ½ and ¾ of the way through the
     * first moving stretch and halfway through each later one; for a cycle, eight points of one turn.
     */
    private fun keyTimes(m: Motion): List<Pair<Float, String>> {
        val cycle = m.cycle
        if (cycle != null) return List(8) { k -> Pair(cycle.duration * k / 8f / cycle.cycles, "phase ${k}/8") }
        val keys = requireNotNull(m.track).keys
        val out = mutableListOf(Pair(0f, "start"))
        var first = true
        for (i in 1 until keys.size) {
            val a = keys[i - 1]
            val b = keys[i]
            if (a.value == b.value) continue
            val fractions = if (first) listOf(0.25f, 0.5f, 0.75f, 1f) else listOf(0.5f, 1f)
            first = false
            for (f in fractions) {
                val t = solveEase(a, b, f)
                if (t < m.duration - 1e-3f) out += Pair(t, "%.2f→%.2f %d%%".format(Locale.ROOT, a.value, b.value, (f * 100).roundToInt()))
            }
        }
        return out
    }

    /** The time between keys [a] and [b] when the value has covered [fraction] of the way. */
    private fun solveEase(a: Key, b: Key, fraction: Float): Float {
        if (fraction >= 1f) return b.time
        var lo = 0f
        var hi = 1f
        repeat(40) {
            val mid = (lo + hi) / 2
            if (b.ease.apply(mid) < fraction) lo = mid else hi = mid
        }
        return lerp(a.time, b.time, (lo + hi) / 2)
    }

    private fun joints(sb: StringBuilder, p: Pose) {
        val list = buildList {
            add("pelvis" to p.pelvis)
            add("waist" to p.waist)
            add("neck" to p.neck)
            add("head" to p.head)
            for (side in Side.entries) {
                val s = side.name.lowercase()
                val a = p.arm(side)
                val l = p.leg(side)
                add("${s}_shoulder" to a.shoulder)
                add("${s}_elbow" to a.elbow)
                add("${s}_wrist" to a.wrist)
                add("${s}_hand" to a.hand)
                add("${s}_elbow_apex" to a.apex)
                add("${s}_hip" to l.hip)
                add("${s}_knee" to l.knee)
                add("${s}_ankle" to l.ankle)
                add("${s}_heel" to l.heel)
                add("${s}_toe" to l.toe)
                add("${s}_knee_apex" to l.apex)
            }
            add("chest_f" to p.upper.f)
            add("chest_u" to p.upper.u)
            add("hips_f" to p.lower.f)
            add("hips_u" to p.lower.u)
        }
        sb.append('{')
        list.forEachIndexed { i, (name, v) ->
            if (i > 0) sb.append(',')
            sb.append('"').append(name).append("\":[").append(mm(v.x)).append(',').append(mm(v.y)).append(',').append(mm(v.z)).append(']')
        }
        sb.append('}')
    }

    private fun ops(sb: StringBuilder, scene: Scene) {
        sb.append('[')
        scene.ops.forEachIndexed { i, op ->
            if (i > 0) sb.append(',')
            sb.append("[\"").append(op.type.name[0]).append("\",\"").append(op.ink.name).append("\",").append(mm(op.width)).append(',').append(if (op.fit) 1 else 0).append(",[")
            for (k in op.points.indices) {
                if (k > 0) sb.append(',')
                sb.append(mm(op.points[k]))
            }
            sb.append("]]")
        }
        sb.append(']')
    }

    private fun mm(v: Float): Int = (v * 1000f).roundToInt()
}
