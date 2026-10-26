package com.enjoyyourtime.globalfitness.ui.training

import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.training.data.WorkoutEdits
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.RoutineExercise
import com.enjoyyourtime.globalfitness.training.model.RoutineTemplate
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class RoutineLogicTest {
    private val en = Locale.US
    private val es = Locale.forLanguageTag("es-ES")

    private fun exercise(id: String, kind: ExerciseKind, primary: Set<Muscle>, secondary: Set<Muscle> = emptySet()) =
        Exercise(id, UiText.raw(id), kind, Equipment.OTHER, primary, secondary, builtIn = true)

    private val catalog = listOf(
        exercise("bench", ExerciseKind.WEIGHT_REPS, setOf(Muscle.CHEST), setOf(Muscle.TRICEPS, Muscle.SHOULDERS)),
        exercise("pushdown", ExerciseKind.WEIGHT_REPS, setOf(Muscle.TRICEPS)),
        exercise("plank", ExerciseKind.DURATION, setOf(Muscle.ABS)),
        exercise("treadmill", ExerciseKind.CARDIO, setOf(Muscle.QUADS)),
    ).associateBy { it.id }
    private val lookup: (String) -> Exercise = { catalog.getValue(it) }

    private fun template(id: String, vararg exercises: String, builtIn: Boolean = false) =
        RoutineTemplate(id, UiText.raw(id), exercises.map { RoutineExercise(it, 3, reps = 10) }, builtIn = builtIn)

    private fun set(reps: Int? = null, sec: Int? = null, warmup: Boolean = false, done: Boolean = true) =
        WorkoutSet("s${reps}_${sec}_$warmup", reps = reps, durationSec = sec, warmup = warmup, done = done)

    // ---- targets ---------------------------------------------------------------------------

    @Test
    fun targetsReadAsSetsTimesRepsOrTime() {
        assertEquals("3 × 10", RoutineFormat.target(RoutineExercise("bench", 3, reps = 10), ExerciseKind.WEIGHT_REPS, en))
        assertEquals("4 × 12", RoutineFormat.target(RoutineExercise("dip", 4, reps = 12), ExerciseKind.BODYWEIGHT_REPS, es))
        assertEquals("3 × 0:45", RoutineFormat.target(RoutineExercise("plank", 3, durationSec = 45), ExerciseKind.DURATION, en))
        assertEquals("1 × 10:00", RoutineFormat.target(RoutineExercise("treadmill", 1, durationSec = 600), ExerciseKind.CARDIO, es))
        assertEquals("1 × 1:00:00", RoutineFormat.target(RoutineExercise("treadmill", 1, durationSec = 3600), ExerciseKind.CARDIO, en))
    }

    @Test
    fun aTargetWithoutValueOrOfTheOtherKindHasNoText() {
        // The caller shows "3 sets": the sets take last time's reps.
        assertNull(RoutineFormat.target(RoutineExercise("bench", 3), ExerciseKind.WEIGHT_REPS, en))
        assertNull(RoutineFormat.target(RoutineExercise("bench", 3, reps = 0), ExerciseKind.WEIGHT_REPS, en))
        // Reps on a timed exercise (or a time on a rep exercise) are not what its sets log.
        assertNull(RoutineFormat.target(RoutineExercise("plank", 3, reps = 10), ExerciseKind.DURATION, en))
        assertNull(RoutineFormat.target(RoutineExercise("bench", 3, durationSec = 30), ExerciseKind.WEIGHT_REPS, en))
        assertEquals("10", RoutineFormat.targetValue(RoutineExercise("bench", 3, reps = 10), ExerciseKind.ASSISTED_REPS, en))
        assertEquals("0:30", RoutineFormat.targetValue(RoutineExercise("plank", 2, durationSec = 30), ExerciseKind.DURATION, en))
    }

    @Test
    fun typedRepsAreDigitsUpToThreeAndZeroIsNone() {
        assertEquals(10, RoutineFormat.parseReps("10"))
        assertEquals(8, RoutineFormat.parseReps(" 08 "))
        assertEquals(123, RoutineFormat.parseReps("1234"))
        // Digits of the language's own script count too.
        assertEquals(12, RoutineFormat.parseReps("١٢"))
        assertNull(RoutineFormat.parseReps(""))
        assertNull(RoutineFormat.parseReps("0"))
        assertNull(RoutineFormat.parseReps("abc"))
    }

    // ---- muscles and order -----------------------------------------------------------------

    @Test
    fun musclesCountPlannedSetsLikeTheWorkoutSummary() {
        val muscles = RoutineStats.muscles(
            listOf(
                RoutineExercise("bench", 4, reps = 8),
                RoutineExercise("pushdown", 3, reps = 12),
                RoutineExercise("plank", 2, durationSec = 45),
                RoutineExercise("treadmill", 1, durationSec = 600),
            ),
            lookup,
        )
        // Primary sets count whole, secondary ones half; cardio counts for no muscle.
        assertEquals(4.0, muscles.getValue(Muscle.CHEST), 1e-9)
        assertEquals(2.0 + 3.0, muscles.getValue(Muscle.TRICEPS), 1e-9)
        assertEquals(2.0, muscles.getValue(Muscle.SHOULDERS), 1e-9)
        assertEquals(2.0, muscles.getValue(Muscle.ABS), 1e-9)
        assertFalse(Muscle.QUADS in muscles)
        // Most sets first; ties keep the body order (chest before shoulders before abs).
        assertEquals(listOf(Muscle.TRICEPS, Muscle.CHEST), RoutineStats.topMuscles(muscles, max = 2))
        assertEquals(listOf(Muscle.TRICEPS, Muscle.CHEST, Muscle.SHOULDERS, Muscle.ABS), RoutineStats.topMuscles(muscles, max = 9))
    }

    @Test
    fun summaryCountsExercisesSetsAndTheTwoMainMuscles() {
        val t = RoutineTemplate("r", UiText.raw("r"), listOf(RoutineExercise("bench", 4, reps = 8), RoutineExercise("pushdown", 3, reps = 12)), builtIn = false)
        val s = RoutineStats.summary(t, lookup)
        assertEquals(2, s.exercises)
        assertEquals(7, s.sets)
        assertEquals(listOf(Muscle.TRICEPS, Muscle.CHEST), s.muscles)
        assertEquals("r", s.id)
    }

    @Test
    fun ownRoutinesComeFirstAndTheLastUsedLeadsItsGroup() {
        val own1 = template("own1")
        val own2 = template("own2")
        val legs = template("legs", builtIn = true)
        val push = template("push", builtIn = true)
        val pull = template("pull", builtIn = true)
        val all = listOf(own1, own2, legs, push, pull)
        assertEquals(all, RoutineStats.ordered(all, lastUsedId = null))
        assertEquals(all, RoutineStats.ordered(all, lastUsedId = "deleted"))
        assertEquals(listOf(own2, own1, legs, push, pull), RoutineStats.ordered(all, "own2"))
        // A built-in one used last leads the built-in ones, still after the user's own.
        assertEquals(listOf(own1, own2, pull, legs, push), RoutineStats.ordered(all, "pull"))
        // Whatever order they come in, the user's routines go first.
        assertEquals(listOf(own1, legs, push), RoutineStats.ordered(listOf(legs, own1, push), null))
    }

    // ---- new exercises -----------------------------------------------------------------------

    @Test
    fun aNewExerciseTakesItsLastSessionOrSensibleDefaults() {
        assertEquals(RoutineExercise("bench", 3, reps = 10), RoutineStats.defaultTarget("bench", ExerciseKind.WEIGHT_REPS, emptyList()))
        assertEquals(RoutineExercise("plank", 3, durationSec = 30), RoutineStats.defaultTarget("plank", ExerciseKind.DURATION, emptyList()))
        assertEquals(RoutineExercise("treadmill", 1, durationSec = 600), RoutineStats.defaultTarget("treadmill", ExerciseKind.CARDIO, emptyList()))

        // Warm-ups do not count; the reps done most often are the target.
        val last = listOf(set(reps = 15, warmup = true), set(reps = 8), set(reps = 8), set(reps = 6), set(reps = 8))
        assertEquals(RoutineExercise("bench", 4, reps = 8), RoutineStats.defaultTarget("bench", ExerciseKind.WEIGHT_REPS, last))
        assertEquals(RoutineExercise("plank", 2, durationSec = 60), RoutineStats.defaultTarget("plank", ExerciseKind.DURATION, listOf(set(sec = 60), set(sec = 60))))
        // Sets logged without reps keep their count and get the default reps.
        assertEquals(RoutineExercise("bench", 2, reps = 10), RoutineStats.defaultTarget("bench", ExerciseKind.WEIGHT_REPS, listOf(set(), set())))
        // Never more sets than a routine allows.
        val many = List(30) { set(reps = 5) }
        assertEquals(WorkoutEdits.MAX_ROUTINE_SETS, RoutineStats.defaultTarget("bench", ExerciseKind.WEIGHT_REPS, many).sets)
    }

    @Test
    fun aWorkoutThatIsARoutineAlreadyIsRecognised() {
        val routines = listOf(template("push", "bench", "pushdown"), template("core", "plank", builtIn = true))
        assertTrue(RoutineStats.matchesRoutine(listOf("bench", "pushdown"), routines))
        assertTrue(RoutineStats.matchesRoutine(listOf("plank"), routines))
        // An exercise done twice in the workout is once in a routine.
        assertTrue(RoutineStats.matchesRoutine(listOf("bench", "pushdown", "bench"), routines))
        assertFalse(RoutineStats.matchesRoutine(listOf("pushdown", "bench"), routines))
        assertFalse(RoutineStats.matchesRoutine(listOf("bench"), routines))
        assertFalse(RoutineStats.matchesRoutine(emptyList(), listOf(RoutineTemplate("empty", UiText.raw("e"), emptyList(), builtIn = false))))
    }

    @Test
    fun newExercisesAreTheOnesTheWorkoutLacks() {
        val push = template("push", "bench", "pushdown", "plank")
        assertEquals(3, RoutineStats.newExercises(push, emptySet()))
        assertEquals(1, RoutineStats.newExercises(push, setOf("bench", "pushdown", "squat")))
        assertEquals(0, RoutineStats.newExercises(push, setOf("bench", "pushdown", "plank")))
    }

    // ---- editor form -------------------------------------------------------------------------

    private val opened = RoutineForm(
        name = "Push",
        note = "",
        exercises = listOf(RoutineExercise("bench", 4, reps = 8), RoutineExercise("plank", 3, durationSec = 45)),
    )

    @Test
    fun onlyRealChangesAskBeforeLeaving() {
        assertFalse(opened.isChangedFrom(opened))
        assertFalse(opened.copy(name = " Push ", note = "  ").isChangedFrom(opened))
        // A zero target is saved as none: it is not a change from none.
        val noTarget = opened.copy(exercises = listOf(RoutineExercise("bench", 4), RoutineExercise("plank", 3, durationSec = 45)))
        assertFalse(noTarget.update("bench") { it.copy(reps = 0) }.isChangedFrom(noTarget))

        assertTrue(opened.copy(name = "Push day").isChangedFrom(opened))
        assertTrue(opened.copy(note = "Heavy").isChangedFrom(opened))
        assertTrue(opened.withSets("bench", 5).isChangedFrom(opened))
        assertTrue(opened.update("plank") { it.copy(durationSec = 60) }.isChangedFrom(opened))
        assertTrue(opened.move("plank", -1).isChangedFrom(opened))
        assertTrue(opened.remove("plank").isChangedFrom(opened))
        assertTrue(opened.add(listOf("pushdown")) { RoutineExercise(it, 3, reps = 12) }.isChangedFrom(opened))
    }

    @Test
    fun editsKeepEachExerciseOnceAndTheSetsInRange() {
        val target: (String) -> RoutineExercise = { RoutineExercise(it, 3, reps = 10) }
        // Picked exercises already in the routine (or picked twice) are not added again.
        val added = opened.add(listOf("pushdown", "bench", "pushdown"), target)
        assertEquals(listOf("bench", "plank", "pushdown"), added.exercises.map { it.exerciseId })
        assertEquals(RoutineExercise("pushdown", 3, reps = 10), added.exercises.last())
        assertSame(opened, opened.add(listOf("bench"), target))

        assertEquals(listOf("plank", "bench"), opened.move("bench", 1).exercises.map { it.exerciseId })
        assertSame(opened, opened.move("bench", -1))
        assertSame(opened, opened.move("plank", 1))
        assertSame(opened, opened.move("missing", 1))
        assertEquals(listOf("bench"), opened.remove("plank").exercises.map { it.exerciseId })

        assertEquals(1, opened.withSets("bench", 0).exercises.first().sets)
        assertEquals(WorkoutEdits.MAX_ROUTINE_SETS, opened.withSets("bench", 99).exercises.first().sets)
        // An edit never moves a target to another exercise.
        assertEquals("bench", opened.update("bench") { it.copy(exerciseId = "other", reps = 6) }.exercises.first().exerciseId)
    }

    @Test
    fun aFormNeedsANameAndAnExercise() {
        assertTrue(opened.isValid)
        assertTrue(opened.copy(name = "  ").nameMissing)
        assertFalse(opened.copy(name = "  ").isValid)
        assertTrue(opened.copy(exercises = emptyList()).exercisesMissing)
        assertFalse(RoutineForm.EMPTY.isValid)
        // A copy of a routine starts with each exercise once.
        val copy = RoutineForm.of("Legs", null, listOf(RoutineExercise("squat", 3, reps = 5), RoutineExercise("squat", 4, reps = 8)))
        assertEquals(listOf(RoutineExercise("squat", 3, reps = 5)), copy.exercises)
        assertEquals("", copy.note)
    }

    @Test
    fun theFormSurvivesAsPlainValues() {
        val form = opened.copy(note = "Heavy day").add(listOf("pushdown")) { RoutineExercise(it, 2) }
        assertEquals(form, RoutineForm.fromSaved(form.toSaved()))
        assertEquals(RoutineForm.EMPTY, RoutineForm.fromSaved(RoutineForm.EMPTY.toSaved()))
    }

    // ---- workout edits -----------------------------------------------------------------------

    @Test
    fun undoingARoutineKeepsWhatWasTrainedMeanwhile() {
        val w = Workout(
            id = "w",
            startedAt = 0,
            endedAt = null,
            name = null,
            note = null,
            updatedAt = 0,
            entries = listOf(
                WorkoutEntry("before", "squat", listOf(set(reps = 5))),
                WorkoutEntry("added1", "bench", listOf(set(reps = 8, done = false))),
                WorkoutEntry("added2", "pushdown", listOf(set(reps = 12, done = true))),
            ),
        )
        val undone = RoutineEdits.removeAdded(w, listOf("added1", "added2"))
        assertEquals(listOf("before", "added2"), undone.entries.map { it.id })
    }
}
