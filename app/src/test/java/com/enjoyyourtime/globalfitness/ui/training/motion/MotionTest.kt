package com.enjoyyourtime.globalfitness.ui.training.motion

import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.model.CatalogExercise
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The exercise animations: catalog coverage, timing and the anatomy of every sampled frame. */
class MotionTest {
    private val motions: Map<String, Motion> = ExerciseCatalog.all.associate { it.id to requireNotNull(MotionCatalog.forId(it.id)) { "no motion for ${it.id}" } }

    @Test
    fun everyBuiltInExerciseHasItsOwnAnimation() {
        for (e in ExerciseCatalog.all) {
            assertTrue("${e.id} has no explicit motion", e.id in MotionCatalog.mappedIds)
            assertTrue("${e.id} has no animation", hasAnimation(e.toExercise()))
        }
        val unknown = MotionCatalog.mappedIds - ExerciseCatalog.all.map { it.id }.toSet()
        assertTrue("motions for ids not in the catalog: $unknown", unknown.isEmpty())
    }

    @Test
    fun customExercisesBorrowTheTypicalMovementOrNone() {
        assertEquals("dumbbell_curl", MotionCatalog.sourceId(custom(Equipment.DUMBBELL, Muscle.BICEPS)))
        assertEquals("leg_press_machine", MotionCatalog.sourceId(custom(Equipment.MACHINE, Muscle.QUADS)))
        assertEquals("lat_pulldown_machine", MotionCatalog.sourceId(custom(Equipment.CABLE, Muscle.LATS)))
        // Several muscles: the first one with a typical movement wins.
        assertEquals("barbell_bench_press", MotionCatalog.sourceId(custom(Equipment.BARBELL, Muscle.CHEST, Muscle.TRICEPS)))
        assertNull(MotionCatalog.sourceId(custom(Equipment.OTHER, Muscle.CHEST)))
        assertNull(MotionCatalog.sourceId(custom(Equipment.CARDIO_MACHINE, kind = ExerciseKind.CARDIO)))
        assertNull(MotionCatalog.sourceId(custom(Equipment.BODYWEIGHT, Muscle.ABS, kind = ExerciseKind.DURATION)))
        assertFalse(hasAnimation(custom(Equipment.BAND, Muscle.CALVES)))
        assertTrue(hasAnimation(custom(Equipment.SMITH_MACHINE, Muscle.GLUTES)))
        // A built-in id is never borrowed by a custom exercise with the same muscles.
        assertNotNull(MotionCatalog.forExercise(custom(Equipment.DUMBBELL, Muscle.BICEPS)))
    }

    @Test
    fun easingsStartAtZeroEndAtOneAndNeverGoBack() {
        for (ease in Ease.entries) {
            assertEquals(0f, ease.apply(0f), 1e-6f)
            assertEquals(1f, ease.apply(1f), 1e-6f)
            var last = 0f
            for (i in 1..100) {
                val v = ease.apply(i / 100f)
                assertTrue("$ease goes back at ${i / 100f}", v >= last - 1e-6f)
                last = v
            }
        }
        assertEquals(0.5f, Ease.IN_OUT.apply(0.5f), 1e-6f)
    }

    @Test
    fun tracksInterpolateBetweenKeysAndWrap() {
        val track = Track.keys(0f to 0f, 1f to 10f, 1.5f to 10f, 2f to 0f)
        assertEquals(2f, track.duration, 0f)
        assertEquals(0f, track.value(0f), 1e-5f)
        assertEquals(10f, track.value(1f), 1e-5f)
        assertEquals(5f, track.value(0.5f), 1e-4f)
        assertEquals(10f, track.value(1.25f), 1e-5f)
        assertEquals(track.value(0.3f), track.value(2.3f), 1e-5f)
        assertEquals(track.value(1.7f), track.value(-0.3f), 1e-5f)
        for (i in 0..200) {
            val v = track.value(i / 100f)
            assertTrue(v in 0f..10f)
        }
        val rep = Track.rep(go = 1f, hold = 0.2f, back = 0.8f, rest = 0.5f)
        assertEquals(2.5f, rep.duration, 1e-6f)
        assertEquals(1f, rep.value(1.6f), 1e-6f)
        assertEquals(0f, rep.value(0.25f), 1e-6f)
        val cycle = Track.cycle(2f, cycles = 2)
        assertEquals(0.5f, cycle.phase(0.5f), 1e-6f)
        assertEquals(0.5f, cycle.phase(1.5f), 1e-6f)
    }

    @Test
    fun timelinesAreWellFormed() {
        for ((id, m) in motions) {
            assertTrue("$id: loop of ${m.duration} s", m.duration in 1f..6f)
            m.track?.let { track ->
                val times = track.keys.map { it.time }
                assertEquals("$id starts at 0", 0f, times.first())
                assertEquals("$id: keys out of order", times.sorted(), times)
                assertEquals("$id: the loop does not close", track.keys.first().value, track.keys.last().value)
            }
            assertTrue("$id has neither a track nor a cycle", (m.track != null) != (m.cycle != null))
        }
    }

    @Test
    fun loopsCloseWithoutAJump() {
        for ((id, m) in motions) {
            val first = joints(m.frame(0f).pose)
            val last = joints(m.frame(m.duration - 1e-4f).pose)
            for ((name, p) in first) assertTrue("$id: $name jumps at the loop", p.distanceTo(last.getValue(name)) < 0.02f)
        }
    }

    @Test
    fun bonesKeepTheirLength() {
        forEachFrame { id, t, f ->
            for (side in Side.entries) {
                val a = f.pose.arm(side)
                val l = f.pose.leg(side)
                assertLength(id, t, "$side upper arm", a.shoulder, a.elbow, BodySize.UPPER_ARM)
                assertLength(id, t, "$side forearm", a.elbow, a.wrist, BodySize.WRIST)
                assertLength(id, t, "$side hand", a.wrist, a.hand, BodySize.HAND)
                assertLength(id, t, "$side thigh", l.hip, l.knee, BodySize.THIGH)
                assertLength(id, t, "$side shin", l.knee, l.ankle, BodySize.SHIN)
                assertLength(id, t, "$side foot", l.heel, l.toe, BodySize.HEEL + BodySize.TOE)
            }
            assertLength(id, t, "lower spine", f.pose.pelvis, f.pose.waist, BodySize.LOWER_SPINE)
            assertLength(id, t, "upper spine", f.pose.waist, f.pose.neck, BodySize.UPPER_SPINE)
        }
    }

    @Test
    fun handsAndFeetReachWhatTheyHold() {
        val misses = mutableSetOf<String>()
        forEachFrame { id, t, f -> if (f.maxMiss > 0.005f) misses += "$id at %.2f s: %.3f m".format(t, f.maxMiss) }
        assertTrue("hands or feet off their targets:\n" + firstPerMotion(misses), misses.isEmpty())
    }

    @Test
    fun jointsStayWithinAnatomicalLimits() {
        val problems = mutableListOf<String>()
        forEachFrame { id, t, f ->
            for (side in Side.entries) {
                val a = f.pose.arm(side)
                val elbow = 180f - angleBetween(a.shoulder - a.elbow, a.wrist - a.elbow)
                if (elbow !in -1f..155f) problems += "$id %.2f $side elbow %.0f°".format(t, elbow)
                val wrist = angleBetween(a.wrist - a.elbow, a.hand - a.wrist)
                if (wrist > 80f) problems += "$id %.2f $side wrist %.0f°".format(t, wrist)
                val l = f.pose.leg(side)
                val knee = 180f - angleBetween(l.hip - l.knee, l.ankle - l.knee)
                if (knee !in -1f..160f) problems += "$id %.2f $side knee %.0f°".format(t, knee)
                // The knee cap faces the front of the shin, which the foot continues: the toes point
                // that way, or (a pointed foot) its top does. Otherwise the knee bends backwards.
                val footDir = (l.toe - l.heel).unit()
                val sole = l.heel + (l.toe - l.heel) * (BodySize.HEEL / (BodySize.HEEL + BodySize.TOE))
                val dorsum = (l.ankle - sole).unit()
                val front = orthogonal(l.apex, (l.ankle - l.knee).unit())
                if (knee > 15f && (footDir dot front) < -0.2f && (dorsum dot front) < 0.4f) problems += "$id %.2f $side knee bends backwards".format(t)
                // Toes forward of the shin: at most a deep squat's dorsiflexion, at most pointed toes.
                val ankle = angleBetween(l.knee - l.ankle, footDir)
                if (ankle !in 35f..160f) problems += "$id %.2f $side ankle %.0f°".format(t, ankle)
                if ((dorsum dot (l.knee - l.ankle).unit()) < -0.5f) problems += "$id %.2f $side foot upside down".format(t)
            }
            val spine = angleBetween(f.pose.lower.u, f.pose.upper.u)
            if (spine > 60f) problems += "$id %.2f spine bent %.0f°".format(t, spine)
        }
        assertTrue(firstPerMotion(problems), problems.isEmpty())
    }

    @Test
    fun nothingSinksIntoTheFloor() {
        val problems = mutableSetOf<String>()
        forEachFrame { id, t, f ->
            val p = f.pose
            val low = buildList {
                add("head" to p.head.y - BodySize.HEAD_RADIUS)
                for (side in Side.entries) {
                    add("$side hand" to p.arm(side).hand.y - BodySize.FIST_RADIUS)
                    add("$side elbow" to p.arm(side).elbow.y - BodySize.ELBOW_RADIUS)
                    add("$side knee" to p.leg(side).knee.y - BodySize.KNEE_RADIUS)
                    add("$side heel" to p.leg(side).heel.y - BodySize.FOOT_RADIUS)
                    add("$side toe" to p.leg(side).toe.y - BodySize.FOOT_RADIUS)
                }
            }
            for ((name, y) in low) if (y < -0.012f) problems += "$id %.2f $name %.3f".format(t, y)
        }
        assertTrue(firstPerMotion(problems), problems.isEmpty())
    }

    @Test
    fun movementIsSmooth() {
        val problems = mutableSetOf<String>()
        for ((id, m) in motions) {
            val steps = (m.duration * 60).toInt()
            var previous = joints(m.frame(0f).pose)
            for (i in 1..steps) {
                val t = m.duration * i / steps
                val now = joints(m.frame(t).pose)
                for ((name, p) in now) {
                    val jump = p.distanceTo(previous.getValue(name))
                    // 7 m/s: a fast swing of the hands, never a pose flipping between frames.
                    if (jump > 0.12f) problems += "$id %.2f $name moves %.2f m in one frame".format(t, jump)
                }
                previous = now
            }
        }
        assertTrue(firstPerMotion(problems), problems.isEmpty())
    }

    @Test
    fun theFittedViewShowsTheWholeFigure() {
        for ((id, m) in motions) {
            val view = m.view
            assertTrue("$id view ${view.size}", view.size in Motion.MIN_VIEW..3.2f)
            for (i in 0 until 12) {
                val f = m.frame(m.duration * i / 12)
                for ((name, p) in joints(f.pose)) {
                    val x = m.camera.x(p)
                    val y = m.camera.y(p)
                    assertTrue("$id: $name outside the view", x in view.left..view.right && y in view.bottom..view.top)
                }
            }
        }
    }

    @Test
    fun scenesDrawTheMusclesOfTheExercise() {
        val bench = motions.getValue("barbell_bench_press")
        val plain = bench.scene(0f, emptySet()).ops.count { it.ink == Ink.MUSCLE || it.ink == Ink.MUSCLE_FAR }
        val chest = bench.scene(0f, setOf(Muscle.CHEST)).ops.count { it.ink == Ink.MUSCLE || it.ink == Ink.MUSCLE_FAR }
        assertEquals(0, plain)
        assertTrue(chest > 0)
        // Every muscle shows somewhere in a front or side view of a standing figure.
        for (muscle in Muscle.entries) {
            val visible = listOf(Camera.SIDE, Camera(90f), Camera(160f)).any { cam ->
                SceneBuilder(cam, setOf(muscle)).build(standing()).ops.any { it.ink == Ink.MUSCLE || it.ink == Ink.MUSCLE_FAR }
            }
            assertTrue("$muscle never shows", visible)
        }
    }

    @Test
    fun paletteKeepsTheFigureReadableInBothThemes() {
        val light = MotionPalette.of(0xFFE7E8EE.toInt(), 0xFF191C20.toInt(), 0xFF43474E.toInt(), 0xFF74777F.toInt(), 0xFF3F5F90.toInt(), 0xFF555F71.toInt(), 0xFF6F5675.toInt())
        val dark = MotionPalette.of(0xFF282A2F.toInt(), 0xFFE1E2E9.toInt(), 0xFFC4C6CF.toInt(), 0xFF8E9099.toInt(), 0xFFA8C8FF.toInt(), 0xFFBDC7DC.toInt(), 0xFFDBBCE1.toInt())
        for (p in listOf(light, dark)) {
            assertEquals(p.background, p[Ink.HALO])
            assertTrue(contrast(p[Ink.BODY], p.background) > 1.6f)
            assertTrue(contrast(p[Ink.MUSCLE], p[Ink.BODY]) > 1.5f)
            assertTrue(contrast(p[Ink.METAL], p.background) > 3f)
            assertTrue(p[Ink.BODY] != p[Ink.BODY_FAR])
        }
    }

    @Test
    fun twoBoneSolverKeepsLengthsAndBendsTowardsThePole() {
        val root = V3.ZERO
        val target = v(0.3f, -0.4f)
        val ik = twoBone(root, target, 0.3f, 0.3f, V3.X)
        assertEquals(0.3f, ik.mid.distanceTo(root), 1e-4f)
        assertEquals(0.3f, ik.mid.distanceTo(ik.end), 1e-4f)
        assertEquals(0f, ik.end.distanceTo(target), 1e-4f)
        assertTrue("bends towards the pole", (ik.mid - (root + target) * 0.5f) dot V3.X > 0f)
        // Out of reach: straightens towards the target without stretching.
        val far = twoBone(root, v(0f, -2f), 0.3f, 0.3f, V3.X)
        assertEquals(0.6f, far.end.distanceTo(root), 1e-3f)
    }

    /** One line per motion (its first problem), so a report names every motion that fails. */
    private fun firstPerMotion(problems: Collection<String>): String =
        problems.groupBy { it.substringBefore(' ') }.values.joinToString("\n") { "${it.first()} (${it.size} frames)" }

    private fun forEachFrame(block: (String, Float, Frame) -> Unit) {
        for ((id, m) in motions) {
            for (i in 0 until SAMPLES) {
                val t = m.duration * i / SAMPLES
                block(id, t, m.frame(t))
            }
        }
    }

    private fun assertLength(id: String, t: Float, what: String, a: V3, b: V3, length: Float) {
        assertEquals("$id at $t: $what", length, a.distanceTo(b), 2e-3f)
    }

    private fun joints(p: Pose): Map<String, V3> = buildMap {
        put("pelvis", p.pelvis)
        put("neck", p.neck)
        put("head", p.head)
        for (side in Side.entries) {
            put("$side shoulder", p.arm(side).shoulder)
            put("$side elbow", p.arm(side).elbow)
            put("$side hand", p.arm(side).hand)
            put("$side knee", p.leg(side).knee)
            put("$side ankle", p.leg(side).ankle)
            put("$side toe", p.leg(side).toe)
        }
    }

    private fun standing(): Frame = FrameBuilder().apply {
        body.torso(v(0f, BodySize.STAND))
        body.standOn(0f)
    }.build()

    private fun contrast(a: Int, b: Int): Float {
        val la = MotionPalette.luminance(a) + 0.05f
        val lb = MotionPalette.luminance(b) + 0.05f
        return if (la > lb) la / lb else lb / la
    }

    private fun CatalogExercise.toExercise() =
        Exercise(id, UiText.Raw(id), kind, equipment, primary, secondary, builtIn = true)

    private fun custom(equipment: Equipment, vararg muscles: Muscle, kind: ExerciseKind = ExerciseKind.WEIGHT_REPS) =
        Exercise("u:test", UiText.Raw("Mine"), kind, equipment, muscles.toSet(), emptySet(), builtIn = false)

    private companion object {
        const val SAMPLES = 48
    }
}

/** Angle between two directions, in degrees (0..180). */
private fun angleBetween(a: V3, b: V3): Float = deg(kotlin.math.acos((a.unit() dot b.unit()).coerceIn(-1f, 1f)))
