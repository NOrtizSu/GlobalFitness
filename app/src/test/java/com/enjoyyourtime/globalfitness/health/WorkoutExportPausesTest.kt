package com.enjoyyourtime.globalfitness.health

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.enjoyyourtime.globalfitness.training.domain.WorkoutTime
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutPause
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/** The pauses of a workout in its Health Connect session: PAUSE segments that no set overlaps. */
class WorkoutExportPausesTest {
    private val t0 = 1_700_000_000_000L
    private val min = 60_000L
    private val pause = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE
    private val bench = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS
    private val lookup: (String) -> WorkoutExerciseInfo = { WorkoutExerciseInfo(cardio = false, hcSegment = bench) }

    private fun set(doneAt: Long?, reps: Int = 10) = WorkoutSet(id = "s${Random.nextInt()}", reps = reps, done = true, doneAt = doneAt)

    private fun workout(sets: List<WorkoutSet>, pauses: List<WorkoutPause>, end: Long = t0 + 60 * min) =
        Workout("w", t0, end, null, null, 10, listOf(WorkoutEntry("e", "bench", sets)), pauses)

    /** Health Connect's own checks (the record's constructor throws on overlaps or segments outside). */
    private fun accepted(s: PlannedSession) {
        ExerciseSessionRecord(
            startTime = Instant.ofEpochMilli(s.start),
            startZoneOffset = null,
            endTime = Instant.ofEpochMilli(s.end),
            endZoneOffset = null,
            metadata = Metadata.manualEntry(),
            exerciseType = s.exerciseType,
            segments = s.segments.map { ExerciseSegment(Instant.ofEpochMilli(it.start), Instant.ofEpochMilli(it.end), it.type, it.repetitions) },
        )
    }

    private fun overlaps(a: PlannedSegment, b: PlannedSegment) = a.start < b.end && b.start < a.end

    @Test
    fun theSessionSpansStartToEndWithItsPausesAsSegments() {
        val pauses = listOf(WorkoutPause(t0 + 10 * min, t0 + 15 * min), WorkoutPause(t0 + 30 * min, t0 + 40 * min))
        val s = WorkoutExportPlanner.session(workout(listOf(set(t0 + 5 * min), set(t0 + 25 * min), set(t0 + 50 * min)), pauses), lookup)
        assertEquals(t0, s.start)
        assertEquals(t0 + 60 * min, s.end)
        assertEquals(pauses.map { it.start to it.end }, s.segments.filter { it.type == pause }.map { it.start to it.end })
        assertEquals(3, s.segments.count { it.type == bench })
        assertEquals(s.segments.sortedBy { it.start }, s.segments)
        accepted(s)
    }

    @Test
    fun noSetOverlapsAPause() {
        val pauses = listOf(WorkoutPause(t0 + 10 * min, t0 + 20 * min), WorkoutPause(t0 + 20 * min + 30_000, t0 + 25 * min))
        val sets = listOf(
            set(t0 + 10 * min), // ticked, then paused
            set(t0 + 20 * min), // ticked to resume: done before the pause
            set(t0 + 25 * min + 10_000), // ten seconds after resuming: shortened, never into the pause
            set(t0 + 22 * min), // inside the second pause
            set(null), // logged afterwards: spread between its neighbours
        )
        val s = WorkoutExportPlanner.session(workout(sets, pauses), lookup)
        val pauseSegments = s.segments.filter { it.type == pause }
        val setSegments = s.segments.filter { it.type == bench }
        assertEquals(2, pauseSegments.size)
        for (p in pauseSegments) for (x in setSegments) assertFalse("$x overlaps $p", overlaps(p, x))
        for (i in 0 until s.segments.lastIndex) assertTrue(s.segments[i].end <= s.segments[i + 1].start)
        // The set ticked to resume ends where the first pause began.
        assertTrue(setSegments.any { it.end == t0 + 10 * min })
        accepted(s)
    }

    @Test
    fun aPauseAtTheEndOfAFinishedWorkoutStaysInsideTheSession() {
        // Finished while paused (the pause closes at the end), or one left open by an older app.
        val s = WorkoutExportPlanner.session(workout(listOf(set(t0 + 20 * min)), listOf(WorkoutPause(t0 + 45 * min, null))), lookup)
        assertEquals(WorkoutPause(t0 + 45 * min, t0 + 60 * min).let { it.start to it.end }, s.segments.last().let { it.start to it.end })
        assertEquals(pause, s.segments.last().type)
        accepted(s)
    }

    @Test
    fun withoutPausesTheSegmentsAreTheSameAsBefore() {
        val sets = listOf(set(t0 + 5 * min), set(t0 + 6 * min), set(null))
        val logged = sets.map { WorkoutExportPlanner.LoggedSet(bench, it.reps ?: 0, WorkoutExportPlanner.nominalMs(it), it.doneAt) }
        assertEquals(WorkoutExportPlanner.segments(logged, t0, t0 + 60 * min), WorkoutExportPlanner.session(workout(sets, emptyList()), lookup).segments)
        // Pauses too short to be a segment are only left out.
        val short = WorkoutExportPlanner.session(workout(sets, listOf(WorkoutPause(t0 + 30 * min, t0 + 30 * min + 500))), lookup)
        assertTrue(short.segments.none { it.type == pause })
        accepted(short)
    }

    @Test
    fun aSessionReadBackShowsItsTimeWithoutThePauseSegments() {
        // Health → Activity: a session of 60 minutes with 15 paused shows 45.
        val pauses = listOf(t0 + 10 * min to t0 + 20 * min, t0 + 40 * min to t0 + 45 * min)
        val paused = ExerciseSession.pausedMs(pauses, t0, t0 + 60 * min)
        assertEquals(15 * min, paused)
        val session = ExerciseSession("id", t0, t0 + 60 * min, ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, null, null, null, "app", pausedMs = paused)
        assertEquals(45 * min, session.activeMs)
        // Overlapping or outside the session: each moment once, only inside it.
        assertEquals(10 * min, ExerciseSession.pausedMs(listOf(t0 - 5 * min to t0 + 5 * min, t0 + 2 * min to t0 + 10 * min), t0, t0 + 60 * min))
        assertEquals(0L, ExerciseSession.pausedMs(emptyList(), t0, t0 + 60 * min))
        // As exported: the planner's PAUSE segments read back give the active time of the workout.
        val w = workout(listOf(set(t0 + 5 * min)), listOf(WorkoutPause(t0 + 10 * min, t0 + 20 * min)))
        val s = WorkoutExportPlanner.session(w, lookup)
        val readBack = ExerciseSession.pausedMs(s.segments.filter { it.type == pause }.map { it.start to it.end }, s.start, s.end)
        assertEquals(WorkoutTime.durationMs(w), s.end - s.start - readBack)
    }
}
