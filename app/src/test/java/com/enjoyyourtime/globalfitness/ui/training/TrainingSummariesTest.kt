package com.enjoyyourtime.globalfitness.ui.training

import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.domain.BodyweightTimeline
import com.enjoyyourtime.globalfitness.training.domain.LoadModel
import com.enjoyyourtime.globalfitness.training.domain.RecordKind
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

class TrainingSummariesTest {
    private val zone = ZoneOffset.UTC
    private val exercises = TrainingRepository.buildExercises(ExerciseCatalog.all, emptyList(), emptyMap())
    private val lookup: (String) -> Exercise = { exercises[it] ?: TrainingRepository.unknownExercise(it) }
    private val kindOf: (String) -> ExerciseKind = { lookup(it).kind }
    private var ids = 0

    private fun at(date: LocalDate, hour: Int = 18) = date.atTime(hour, 0).toInstant(zone).toEpochMilli()

    private fun set(kg: Double?, reps: Int?, done: Boolean = true, warmup: Boolean = false, doneAt: Long? = null) =
        WorkoutSet("s${ids++}", weightKg = kg, reps = reps, done = done, warmup = warmup, doneAt = if (done) doneAt else null)

    private fun workout(start: Long, vararg entries: Pair<String, List<WorkoutSet>>, end: Long? = start + 3_600_000, name: String? = null) = Workout(
        id = "w${ids++}",
        startedAt = start,
        endedAt = end,
        name = name,
        note = null,
        updatedAt = start,
        entries = entries.map { (id, sets) -> WorkoutEntry("e${ids++}", id, sets) },
    )

    @Test
    fun setTableColumnsFollowTheKind() {
        assertEquals(SetColumns(true, listOf(SetField.LOAD, SetField.REPS)), SetTable.columns(ExerciseKind.WEIGHT_REPS))
        assertEquals(SetColumns(true, listOf(SetField.EXTRA_LOAD, SetField.REPS)), SetTable.columns(ExerciseKind.BODYWEIGHT_REPS))
        assertEquals(SetColumns(true, listOf(SetField.ASSISTANCE, SetField.REPS)), SetTable.columns(ExerciseKind.ASSISTED_REPS))
        assertEquals(SetColumns(true, listOf(SetField.TIME, SetField.LOAD)), SetTable.columns(ExerciseKind.DURATION))
        assertEquals(
            SetColumns(false, listOf(SetField.TIME, SetField.DISTANCE, SetField.LEVEL, SetField.KCAL)),
            SetTable.columns(ExerciseKind.CARDIO),
        )
        // Every kind has a table.
        ExerciseKind.entries.forEach { assertTrue(SetTable.columns(it).fields.isNotEmpty()) }
    }

    @Test
    fun aSetIsFilledInByItsDefiningValue() {
        assertTrue(SetTable.hasValues(WorkoutSet("a", reps = 8), ExerciseKind.WEIGHT_REPS))
        assertFalse(SetTable.hasValues(WorkoutSet("a", weightKg = 80.0), ExerciseKind.WEIGHT_REPS))
        assertTrue(SetTable.hasValues(WorkoutSet("a", weightKg = 30.0, reps = 6), ExerciseKind.ASSISTED_REPS))
        assertTrue(SetTable.hasValues(WorkoutSet("a", durationSec = 60), ExerciseKind.DURATION))
        assertTrue(SetTable.hasValues(WorkoutSet("a", distanceM = 1000.0), ExerciseKind.CARDIO))
        assertFalse(SetTable.hasValues(WorkoutSet("a", level = 5), ExerciseKind.CARDIO))
    }

    @Test
    fun previousSetsMatchByPositionWithinWarmupsAndWorkingSets() {
        val previous = listOf(set(40.0, 10, warmup = true), set(80.0, 8), set(80.0, 7))
        val now = listOf(set(null, null, done = false, warmup = true), set(null, null, done = false), set(null, null, done = false), set(null, null, done = false))
        val map = SetTable.previousFor(now, previous)
        assertEquals(40.0, map.getValue(now[0].id).weightKg!!, 0.0)
        assertEquals(8, map.getValue(now[1].id).reps)
        assertEquals(7, map.getValue(now[2].id).reps)
        assertNull(map[now[3].id])
        assertEquals(mapOf(now[1].id to 1, now[2].id to 2, now[3].id to 3), SetTable.workingNumbers(now))
    }

    @Test
    fun tickingAPartlyFilledSetKeepsWhatWasTyped() {
        val previous = WorkoutSet("p", weightKg = 80.0, reps = 8)
        // Only the new, heavier weight was typed: last time's reps fill the gap, the weight stays.
        val filled = SetTable.fillMissing(WorkoutSet("s", weightKg = 85.0), previous)
        assertEquals(85.0, filled.weightKg!!, 0.0)
        assertEquals(8, filled.reps)
        assertEquals("s", filled.id)
        // "Use last time's values" copies everything.
        assertEquals(80.0, SetTable.copyValues(WorkoutSet("s", weightKg = 85.0), previous).weightKg!!, 0.0)
    }

    @Test
    fun lateEchoesOfEarlierKeystrokesDoNotResetTheText() {
        val sent = SentValues<Double?> { a, b -> a == b }
        // Typed 1, 10, 100; the stored value then catches up one keystroke at a time.
        listOf(1.0, 10.0, 100.0).forEach(sent::sent)
        assertFalse(sent.shouldReset(1.0, textMeansStored = false))
        assertFalse(sent.shouldReset(10.0, textMeansStored = false))
        assertFalse(sent.shouldReset(100.0, textMeansStored = true))
        // Afterwards a value from elsewhere (copied from last time) replaces the text.
        assertTrue(sent.shouldReset(80.0, textMeansStored = false))
        // Even one this field once sent, now that every echo arrived.
        sent.sent(5.0)
        assertFalse(sent.shouldReset(5.0, textMeansStored = true))
        assertTrue(sent.shouldReset(5.0, textMeansStored = false))
    }

    @Test
    fun entriesMoveByIdNotByStaleIndex() {
        val start = at(LocalDate.of(2026, 10, 20))
        val w = workout(start, "barbell_bench_press" to listOf(set(80.0, 8)), "barbell_back_squat" to listOf(set(100.0, 5)), "plank" to emptyList<WorkoutSet>())
        val squat = w.entries[1].id
        assertEquals(squat, WorkoutUiEdits.moveEntry(w, squat, -1).entries[0].id)
        assertEquals(squat, WorkoutUiEdits.moveEntry(w, squat, 1).entries[2].id)
        // Past either end, or an entry that is gone: nothing changes.
        assertEquals(w, WorkoutUiEdits.moveEntry(w, w.entries[0].id, -1))
        assertEquals(w, WorkoutUiEdits.moveEntry(w, "gone", 1))
    }

    @Test
    fun undoPutsRemovedExercisesAndSetsBackWhereTheyWere() {
        val start = at(LocalDate.of(2026, 10, 20))
        val w = workout(start, "barbell_bench_press" to listOf(set(80.0, 8), set(80.0, 7)), "barbell_back_squat" to listOf(set(100.0, 5)))
        val bench = w.entries[0]
        val without = w.copy(entries = w.entries.drop(1))
        assertEquals(w, WorkoutUiEdits.insertEntry(without, bench, 0))
        // Already back (a second undo tap): unchanged; an index past the end goes last.
        assertEquals(w, WorkoutUiEdits.insertEntry(w, bench, 0))
        assertEquals(bench.id, WorkoutUiEdits.insertEntry(without, bench, 9).entries.last().id)

        val second = bench.sets[1]
        val oneSet = w.copy(entries = listOf(bench.copy(sets = listOf(bench.sets[0])), w.entries[1]))
        assertEquals(w, WorkoutUiEdits.insertSet(oneSet, bench.id, second, 1))
        assertEquals(w, WorkoutUiEdits.insertSet(w, bench.id, second, 1))
        // Its exercise was removed meanwhile: nothing to put it in.
        assertEquals(without, WorkoutUiEdits.insertSet(without, bench.id, second, 1))
    }

    @Test
    fun onlyOldEmptyPastLogsAreAbandoned() {
        val now = at(LocalDate.of(2026, 10, 20))
        val grace = 10 * 60_000L
        val old = workout(now - 3_600_000).copy(updatedAt = now - 3_600_000)
        val fresh = workout(now - 60_000).copy(updatedAt = now - 60_000)
        val withExercise = workout(now - 3_600_000, "plank" to emptyList<WorkoutSet>()).copy(updatedAt = now - 3_600_000)
        val live = workout(now - 3_600_000, end = null).copy(updatedAt = now - 3_600_000)
        assertEquals(listOf(old.id), TrainingSummaries.abandonedLogs(listOf(old, fresh, withExercise, live), now, grace).map { it.id })
    }

    @Test
    fun theEditorAsksOnlyWhenSomethingWouldBeLost() {
        val opened = ExerciseForm(
            name = "Chest press",
            equipment = Equipment.MACHINE,
            kind = ExerciseKind.WEIGHT_REPS,
            primary = setOf(Muscle.CHEST),
            secondary = emptySet(),
            settings = "Seat 4",
            photo = null,
        )
        assertFalse(opened.copy(name = " Chest press ", settings = "Seat 4 ").isChangedFrom(opened))
        // A secondary muscle that is also primary is dropped on save: no change.
        assertFalse(opened.copy(secondary = opened.primary).isChangedFrom(opened))
        assertTrue(opened.copy(settings = "Seat 5").isChangedFrom(opened))
        assertTrue(opened.copy(photo = "draft_1.jpg").isChangedFrom(opened))
        assertTrue(opened.copy(kind = ExerciseKind.ASSISTED_REPS).isChangedFrom(opened))
    }

    @Test
    fun editorSavesSurviveTheEditorAndRunOnce() {
        val saves = EditorSaves()
        assertTrue(saves.start("a"))
        // A second tap, or the editor recreated after a rotation, does not save again.
        assertFalse(saves.start("a"))
        assertFalse(saves.finish("a", "u:1"))
        assertEquals(EditorSave.Saved("u:1"), saves.state.value["a"])
        assertFalse(saves.start("a"))
        // The editor leaves after navigating: nothing to clean up.
        assertFalse(saves.close("a"))
        assertNull(saves.state.value["a"])

        // A failure re-enables the form once shown; closing then deletes the unsaved photos.
        assertTrue(saves.start("b"))
        saves.finish("b", null)
        assertEquals(EditorSave.Failed, saves.state.value["b"])
        saves.consume("b")
        assertNull(saves.state.value["b"])
        assertTrue(saves.close("b"))

        // Closed while saving: the photos wait for the save, whose late result is dropped.
        assertTrue(saves.start("c"))
        saves.consume("c")
        assertEquals(EditorSave.Saving, saves.state.value["c"])
        assertFalse(saves.close("c"))
        assertTrue(saves.finish("c", "u:2"))
        assertNull(saves.state.value["c"])
    }

    @Test
    fun warmupsStayBeforeTheWorkingSets() {
        val w = workout(at(LocalDate.of(2026, 10, 20)), "barbell_bench_press" to listOf(set(60.0, 8), set(80.0, 8)))
        val entryId = w.entries[0].id
        val withWarmup = WorkoutUiEdits.addWarmup(w, entryId) { "warm" }
        assertEquals(listOf(true, false, false), withWarmup.entries[0].sets.map { it.warmup })
        assertEquals("warm", withWarmup.entries[0].sets[0].id)
        // Marking the second working set as a warm-up moves it up with the other warm-ups.
        val secondWorking = withWarmup.entries[0].sets[2].id
        val moved = WorkoutUiEdits.setWarmup(withWarmup, entryId, secondWorking, true)
        assertEquals(listOf("warm", secondWorking), moved.entries[0].sets.filter { it.warmup }.map { it.id })
        assertTrue(moved.entries[0].sets.dropWhile { it.warmup }.none { it.warmup })
    }

    @Test
    fun finishingCompletesOrDropsUnfinishedSets() {
        val start = at(LocalDate.of(2026, 10, 20))
        val w = workout(
            start,
            "barbell_bench_press" to listOf(set(80.0, 8, doneAt = start + 60_000), set(80.0, 8, done = false), set(null, null, done = false)),
            "plank" to listOf(set(null, null, done = false)),
        )
        assertEquals(1, WorkoutUiEdits.unfinishedFilled(w, kindOf))
        val completed = WorkoutUiEdits.dropUnfinished(WorkoutUiEdits.completeFilled(w, kindOf, now = start + 10 * 3_600_000))
        assertEquals(1, completed.entries.size)
        assertEquals(2, completed.entries[0].sets.size)
        // Ticked after the fact in a finished workout: done when the workout ended, not now.
        assertEquals(w.endedAt, completed.entries[0].sets[1].doneAt)
        val dropped = WorkoutUiEdits.dropUnfinished(w)
        assertEquals(1, dropped.entries.single().sets.size)
    }

    @Test
    fun retimingAFinishedWorkoutMovesItsSets() {
        val start = at(LocalDate.of(2026, 10, 20))
        val w = workout(start, "barbell_bench_press" to listOf(set(80.0, 8, doneAt = start + 30 * 60_000)))
        val dayBefore = start - 24 * 3_600_000L
        val moved = WorkoutUiEdits.retime(w, dayBefore, dayBefore + 3_600_000)
        assertEquals(dayBefore, moved.startedAt)
        assertEquals(dayBefore + 3_600_000, moved.endedAt)
        assertEquals(dayBefore + 30 * 60_000, moved.entries[0].sets[0].doneAt)
        // Shortened to 10 minutes: the set stays inside the workout.
        val short = WorkoutUiEdits.retime(w, start, start + 10 * 60_000)
        assertEquals(start + 10 * 60_000, short.entries[0].sets[0].doneAt)
    }

    @Test
    fun weekSummaryCountsTheCalendarWeekOfTheLocale() {
        val wednesday = LocalDate.of(2026, 10, 21)
        val workouts = listOf(
            // Sunday before: last week for a Monday-first locale.
            workout(at(LocalDate.of(2026, 10, 18)), "barbell_bench_press" to listOf(set(80.0, 8))),
            workout(at(LocalDate.of(2026, 10, 19)), "barbell_bench_press" to listOf(set(80.0, 8), set(80.0, 8), set(40.0, 10, warmup = true))),
            workout(at(wednesday), "barbell_back_squat" to listOf(set(100.0, 5))),
            // Planned only: not trained.
            workout(at(wednesday, 20), "barbell_back_squat" to listOf(set(100.0, 5, done = false))),
        )
        val monday = TrainingSummaries.week(workouts, wednesday, DayOfWeek.MONDAY, zone, lookup, LoadModel.WITHOUT_BODYWEIGHT)
        assertEquals(LocalDate.of(2026, 10, 19), monday.start)
        assertEquals(2, monday.workouts)
        assertEquals(3, monday.workingSets)
        assertEquals(80.0 * 8 * 2 + 100.0 * 5, monday.volumeKg, 1e-9)
        assertEquals(setOf(LocalDate.of(2026, 10, 19), wednesday), monday.days)
        val sunday = TrainingSummaries.week(workouts, wednesday, DayOfWeek.SUNDAY, zone, lookup, LoadModel.WITHOUT_BODYWEIGHT)
        assertEquals(3, sunday.workouts)
    }

    @Test
    fun bodyweightVolumeUsesTheLoadModel() {
        val day = LocalDate.of(2026, 10, 20)
        val pullUps = workout(at(day), "pull_up" to listOf(set(null, 10)))
        val load = LoadModel(BodyweightTimeline(listOf(0L to 80.0)))
        assertEquals(800.0, TrainingSummaries.summary(pullUps, lookup, load).volumeKg, 1e-9)
        assertEquals(0.0, TrainingSummaries.summary(pullUps, lookup, LoadModel.WITHOUT_BODYWEIGHT).volumeKg, 1e-9)
    }

    @Test
    fun templatesAreOnePerNameNewestFirst() {
        val d = LocalDate.of(2026, 10, 1)
        val workouts = listOf(
            workout(at(d), "barbell_back_squat" to listOf(set(100.0, 5)), name = "Pierna"),
            workout(at(d.plusDays(2)), "barbell_bench_press" to listOf(set(80.0, 8)), name = "Pecho"),
            workout(at(d.plusDays(4)), "barbell_back_squat" to listOf(set(105.0, 5)), name = "pierna "),
            workout(at(d.plusDays(5)), "barbell_bench_press" to listOf(set(80.0, 8, done = false)), end = null),
        )
        val templates = TrainingSummaries.templates(workouts)
        assertEquals(listOf(workouts[2].id, workouts[1].id), templates.map { it.id })
    }

    @Test
    fun recordsUseTheWeightReallyMoved() {
        val d = LocalDate.of(2026, 10, 1)
        val load = LoadModel(BodyweightTimeline(listOf(0L to 80.0)))
        val first = workout(at(d), "assisted_pull_up_machine" to listOf(set(40.0, 8, doneAt = at(d))))
        val second = workout(at(d.plusDays(3)), "assisted_pull_up_machine" to listOf(set(30.0, 8, doneAt = at(d.plusDays(3)))))
        val records = WorkoutRecords.forWorkout(listOf(first, second), second, lookup, load)
        val beaten = records.getValue(second.entries[0].sets[0].id)
        // Less assistance: a stronger set and the least assistance so far.
        assertTrue(RecordKind.ASSISTANCE in beaten)
        assertTrue(RecordKind.E1RM in beaten)
        // The first time is never a record.
        assertTrue(WorkoutRecords.forWorkout(listOf(first, second), first, lookup, load).isEmpty())
        // More assistance is not a record.
        val third = workout(at(d.plusDays(6)), "assisted_pull_up_machine" to listOf(set(50.0, 8, doneAt = at(d.plusDays(6)))))
        assertTrue(WorkoutRecords.forWorkout(listOf(first, second, third), third, lookup, load).isEmpty())
    }

    @Test
    fun progressMetricsSuitTheExercise() {
        assertEquals(ProgressMetric.E1RM, ProgressMetric.forKind(ExerciseKind.WEIGHT_REPS, false).first())
        assertEquals(ProgressMetric.LEAST_ASSISTANCE, ProgressMetric.forKind(ExerciseKind.ASSISTED_REPS, true)[1])
        assertTrue(ProgressMetric.E1RM in ProgressMetric.forKind(ExerciseKind.BODYWEIGHT_REPS, true))
        assertFalse(ProgressMetric.E1RM in ProgressMetric.forKind(ExerciseKind.BODYWEIGHT_REPS, false))
        assertEquals(listOf(ProgressMetric.TOTAL_TIME, ProgressMetric.DISTANCE), ProgressMetric.forKind(ExerciseKind.CARDIO, false))
        assertTrue(ProgressMetric.LEAST_ASSISTANCE.isLoad)
        assertFalse(ProgressMetric.LEAST_ASSISTANCE.usesMovedLoad)
    }
}
