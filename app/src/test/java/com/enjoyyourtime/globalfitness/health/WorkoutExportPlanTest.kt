package com.enjoyyourtime.globalfitness.health

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

class WorkoutExportPlanTest {

    private val strength = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
    private val other = ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    private val treadmillSession = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
    private val segOther = ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT
    private val segWeightlifting = ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING

    /** Catalog-like entries: the shapes ExerciseCatalog uses, plus custom ones (no Health Connect codes). */
    private val exercises = mapOf(
        "bench" to WorkoutExerciseInfo(cardio = false, hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS),
        "squat" to WorkoutExerciseInfo(cardio = false, hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT),
        "plank" to WorkoutExerciseInfo(cardio = false, hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PLANK),
        "push_up" to WorkoutExerciseInfo(cardio = false, hcSegment = null),
        "treadmill" to WorkoutExerciseInfo(cardio = true, ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL, treadmillSession),
        "bike" to WorkoutExerciseInfo(
            cardio = true,
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
        ),
        "ski_erg" to WorkoutExerciseInfo(cardio = true, segOther, other),
        "u:spinning" to WorkoutExerciseInfo(cardio = true),
        "u:machine" to WorkoutExerciseInfo(cardio = false),
    )
    private val lookup: (String) -> WorkoutExerciseInfo = { exercises[it] ?: WorkoutExerciseInfo(cardio = false) }

    private val start = 1_700_000_000_000L
    private val hour = 3_600_000L
    private val minute = 60_000L

    private fun set(reps: Int? = 10, seconds: Int? = null, doneAt: Long? = null, done: Boolean = true, warmup: Boolean = false) =
        WorkoutSet(id = "s${Random.nextInt()}", reps = reps, durationSec = seconds, done = done, doneAt = doneAt, warmup = warmup)

    private fun entry(exerciseId: String, vararg sets: WorkoutSet) = WorkoutEntry(id = "e-$exerciseId", exerciseId = exerciseId, sets = sets.toList())

    private fun workout(
        id: String = "w1",
        startedAt: Long = start,
        endedAt: Long? = start + hour,
        updatedAt: Long = 10,
        name: String? = null,
        note: String? = null,
        entries: List<WorkoutEntry> = emptyList(),
    ) = Workout(id, startedAt, endedAt, name, note, updatedAt, entries)

    private fun benchWorkout(id: String = "w1", updatedAt: Long = 10, endedAt: Long? = start + hour) =
        workout(id = id, updatedAt = updatedAt, endedAt = endedAt, entries = listOf(entry("bench", set(doneAt = start + 10 * minute))))

    /** "Now" for the plans: long after the workouts' default `updatedAt`, so their quiet period is over. */
    private val now = start + 2 * hour

    private fun plan(
        workouts: List<Workout>,
        stored: List<StoredSession>? = emptyList(),
        ledger: Map<String, WorkoutLedgerEntry> = emptyMap(),
        enabled: Boolean = true,
        at: Long = now,
    ) = WorkoutExportPlanner.plan(workouts, lookup, stored, ledger, enabled, at)

    private fun stored(workoutId: String, version: Long) = StoredSession(WorkoutExportPlanner.clientRecordId(workoutId), version)

    /** Written by this installation from the workout's [source] `updatedAt`. */
    private fun written(id: String, source: Long, version: Long = source, format: Int = WorkoutExportPlanner.FORMAT, pendingDelete: Boolean = false) =
        id to WorkoutLedgerEntry(id, version, source, format, pendingDelete)

    /** Seen disappearing, never written by this installation. */
    private fun deletionOnly(id: String) = id to WorkoutLedgerEntry(id, version = 0, sourceUpdatedAt = 0, format = 0, pendingDelete = true)

    // ---- What to write -------------------------------------------------------------------------

    @Test
    fun clientIdsAndVersions() {
        val p = plan(listOf(benchWorkout(updatedAt = 42)))
        val w = p.writes.single()
        assertEquals("gf-workout-w1", w.clientRecordId)
        assertEquals("w1", w.workoutId)
        assertEquals(42L, w.sourceUpdatedAt)
        assertEquals(now, w.version)
        assertEquals(WorkoutLedgerEntry("w1", now, 42, WorkoutExportPlanner.FORMAT), w.ledgerEntry())
        assertEquals("w1", WorkoutExportPlanner.workoutIdOf("gf-workout-w1"))
        assertEquals("1b2c-3d4e", WorkoutExportPlanner.workoutIdOf(WorkoutExportPlanner.clientRecordId("1b2c-3d4e")))
        assertNull(WorkoutExportPlanner.workoutIdOf("gf-workout-"))
        assertNull(WorkoutExportPlanner.workoutIdOf("m_1700000000000_80000-weight"))
        assertNull(WorkoutExportPlanner.workoutIdOf(null))
        assertEquals(1, p.savedCount)
    }

    @Test
    fun versionsOnlyGoUpEvenWhenUpdatedAtGoesBack() {
        assertEquals(now, WorkoutExportPlanner.exportVersion(now, null, null))
        assertEquals(now + 51, WorkoutExportPlanner.exportVersion(now, WorkoutLedgerEntry("w1", now + 50, 1, 1), null))
        assertEquals(now + 8, WorkoutExportPlanner.exportVersion(now, null, now + 7))
        // The cloud backup adopted an older copy: updatedAt went from 500 to 400.
        val p = plan(listOf(benchWorkout(updatedAt = 400)), stored = listOf(stored("w1", now + 100)), ledger = mapOf(written("w1", source = 500, version = now + 100)))
        val w = p.writes.single()
        assertEquals(now + 101, w.version)
        assertEquals(400L, w.sourceUpdatedAt)
    }

    @Test
    fun workoutsInProgressAreNotWritten() {
        val p = plan(listOf(benchWorkout(endedAt = null), benchWorkout(id = "w2")))
        assertEquals(listOf("w2"), p.writes.map { it.workoutId })
        // A workout written before and resumed keeps its record until it ends again.
        val resumed = plan(listOf(benchWorkout(endedAt = null, updatedAt = 20)), stored = listOf(stored("w1", 10)), ledger = mapOf(written("w1", 10)))
        assertTrue(resumed.isEmpty)
    }

    @Test
    fun upToDateSessionsAreLeftAlone() {
        val p = plan(listOf(benchWorkout(updatedAt = 10)), stored = listOf(stored("w1", 10)), ledger = mapOf(written("w1", 10)))
        assertTrue(p.isEmpty)
        assertEquals(1, p.savedCount)
        // Unknown to the ledger (reinstall): written once again, replacing what is there.
        val reinstalled = plan(listOf(benchWorkout(updatedAt = 10)), stored = listOf(stored("w1", now + 5)))
        assertEquals(now + 6, reinstalled.writes.single().version)
        assertEquals(1, reinstalled.savedCount)
    }

    @Test
    fun editedWorkoutIsWrittenAgain() {
        val p = plan(listOf(benchWorkout(updatedAt = 11)), stored = listOf(stored("w1", 10)), ledger = mapOf(written("w1", 10)))
        assertEquals(listOf(11L), p.writes.map { it.sourceUpdatedAt })
        assertTrue(p.writes.single().version > 10)
    }

    @Test
    fun sessionDeletedInHealthConnectIsNotWrittenBackUntilTheWorkoutChanges() {
        val ledger = mapOf(written("w1", 10))
        val same = plan(listOf(benchWorkout(updatedAt = 10)), stored = emptyList(), ledger = ledger)
        assertTrue(same.isEmpty)
        assertEquals(0, same.savedCount)
        assertEquals(1, plan(listOf(benchWorkout(updatedAt = 11)), stored = emptyList(), ledger = ledger).writes.size)
    }

    @Test
    fun aNewFormatRewritesTheSessionsStillThere() {
        val old = mapOf(written("w1", 10, format = WorkoutExportPlanner.FORMAT - 1))
        assertEquals(1, plan(listOf(benchWorkout()), stored = listOf(stored("w1", 10)), ledger = old).writes.size)
        // Deleted by the user in Health Connect, or not readable right now: left alone.
        assertTrue(plan(listOf(benchWorkout()), stored = emptyList(), ledger = old).isEmpty)
        assertTrue(plan(listOf(benchWorkout()), stored = null, ledger = old).isEmpty)
    }

    @Test
    fun finishedWorkoutsAreWrittenOnceTheyStopChanging() {
        val recent = benchWorkout(updatedAt = now - 10_000)
        val waiting = plan(listOf(recent, benchWorkout(id = "w2", updatedAt = now - 20_000)))
        assertTrue(waiting.writes.isEmpty())
        assertEquals(now - 20_000 + WorkoutExportPlanner.QUIET_MS, waiting.nextRunAt)
        val later = plan(listOf(recent), at = recent.updatedAt + WorkoutExportPlanner.QUIET_MS)
        assertEquals(1, later.writes.size)
        assertNull(later.nextRunAt)
        // Another phone's clock a few minutes ahead: waited for; a clock days ahead: not.
        val skewed = benchWorkout(updatedAt = now + 5 * minute)
        assertEquals(skewed.updatedAt + WorkoutExportPlanner.QUIET_MS, plan(listOf(skewed)).nextRunAt)
        assertEquals(1, plan(listOf(benchWorkout(updatedAt = now + 2 * WorkoutExportPlanner.MAX_CLOCK_SKEW_MS))).writes.size)
    }

    @Test
    fun workoutsWithoutCompletedSetsAreNeverWritten() {
        // "Log a past workout": created finished and empty, filled in afterwards.
        val logged = workout(updatedAt = now - 1_000)
        val p = plan(listOf(logged))
        assertTrue(p.isEmpty)
        assertNull(p.nextRunAt)
        assertTrue(plan(listOf(workout(entries = listOf(entry("bench", set(done = false)))))).isEmpty)
        // Emptied after it was written: its session is deleted, once the edits are over.
        val emptied = workout(updatedAt = 20, entries = listOf(entry("bench", set(done = false))))
        val deleted = plan(listOf(emptied), stored = listOf(stored("w1", 10)), ledger = mapOf(written("w1", 10)))
        assertEquals(listOf("gf-workout-w1"), deleted.deletes)
        assertEquals(setOf("w1"), deleted.ledgerRemove)
        assertEquals(0, deleted.savedCount)
        val editing = plan(listOf(emptied.copy(updatedAt = now - 1_000)), ledger = mapOf(written("w1", 10)))
        assertTrue(editing.deletes.isEmpty())
        assertEquals(now - 1_000 + WorkoutExportPlanner.QUIET_MS, editing.nextRunAt)
    }

    // ---- What to delete ------------------------------------------------------------------------

    @Test
    fun onlyWorkoutsSeenDisappearingAreDeleted() {
        val p = plan(
            listOf(benchWorkout(id = "kept")),
            stored = listOf(
                stored("kept", 10),
                // Written by an earlier installation, its workout never restored: kept.
                stored("earlier", 5),
                StoredSession("another-scheme", 1),
                StoredSession(null, 1),
                // "gone" is not in the list: Android 13 and lower may only return the last 30 days.
            ),
            ledger = mapOf(
                written("kept", 10),
                written("gone", 3, pendingDelete = true),
                deletionOnly("earlierGone"),
                // Missing from the training log without having been seen disappearing.
                written("missing", 4),
            ),
        )
        assertEquals(listOf("gf-workout-earlierGone", "gf-workout-gone"), p.deletes)
        assertEquals(setOf("gone", "earlierGone"), p.ledgerRemove)
        assertTrue(p.writes.isEmpty())
        assertEquals(2, p.savedCount) // kept + earlier
    }

    @Test
    fun anEmptyTrainingLogDeletesNothing() {
        // training.db recreated empty (or a reinstall): every ledger entry is missing, none was seen going.
        val ledger = (1..50).associate { written("w$it", it.toLong()) }
        val p = plan(emptyList(), stored = (1..50).map { stored("w$it", it.toLong()) }, ledger = ledger)
        assertTrue(p.isEmpty)
        assertEquals(50, p.savedCount)
    }

    @Test
    fun workoutThatCameBackIsNotDeleted() {
        val p = plan(listOf(benchWorkout(updatedAt = 10)), stored = listOf(stored("w1", 10)), ledger = mapOf(written("w1", 10, pendingDelete = true)))
        assertTrue(p.deletes.isEmpty())
        assertTrue(p.writes.isEmpty())
        assertEquals(setOf("w1"), p.ledgerUndelete)
        // Never written by this installation: written now, which also clears the mark.
        val restored = plan(listOf(benchWorkout(updatedAt = 10)), ledger = mapOf(deletionOnly("w1")))
        assertEquals(1, restored.writes.size)
        assertTrue(restored.ledgerUndelete.isEmpty())
    }

    @Test
    fun withoutReadingHealthConnectTheLedgerDecides() {
        val p = plan(
            listOf(benchWorkout(id = "known", updatedAt = 10), benchWorkout(id = "new")),
            stored = null,
            ledger = mapOf(written("known", 10), written("gone", 2, pendingDelete = true)),
        )
        assertEquals(listOf("new"), p.writes.map { it.workoutId })
        assertEquals(listOf("gf-workout-gone"), p.deletes)
        assertEquals(setOf("gone"), p.ledgerRemove)
        assertEquals(2, p.savedCount)
    }

    @Test
    fun switchOffWritesNothingButStillDeletes() {
        val emptied = workout(id = "emptied", updatedAt = 20)
        val p = plan(
            listOf(benchWorkout(id = "new"), emptied),
            stored = listOf(stored("emptied", 10)),
            ledger = mapOf(written("gone", 1, pendingDelete = true), written("emptied", 10)),
            enabled = false,
        )
        assertTrue(p.writes.isEmpty())
        assertEquals(listOf("gf-workout-emptied", "gf-workout-gone"), p.deletes)
        assertEquals(0, p.savedCount)
    }

    @Test
    fun disappearedWorkouts() {
        val before = WorkoutExportPlanner.snapshot(listOf(benchWorkout(id = "a"), benchWorkout(id = "b", endedAt = null), benchWorkout(id = "c")))
        assertEquals(mapOf("a" to true, "b" to false, "c" to true), before)
        val gone = WorkoutExportPlanner.disappeared(before, listOf(benchWorkout(id = "a"), benchWorkout(id = "new")))
        assertEquals(setOf("c"), gone.finished)
        assertEquals(setOf("b"), gone.inProgress)
        assertTrue(WorkoutExportPlanner.disappeared(before, listOf(benchWorkout(id = "a"), benchWorkout(id = "b"), benchWorkout(id = "c"))).isEmpty)
        assertTrue(WorkoutExportPlanner.disappeared(emptyMap(), emptyList()).isEmpty)
    }

    // ---- Failures ------------------------------------------------------------------------------

    @Test
    fun backOffGrowsAndIsLongerWhenRateLimited() {
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L), (1..8).map { WorkoutExporter.backoffMs(it, rateLimited = false) / minute })
        assertEquals(15L, WorkoutExporter.backoffMs(1, rateLimited = true) / minute)
        assertEquals(60L, WorkoutExporter.backoffMs(9, rateLimited = true) / minute)
    }

    @Test
    fun healthConnectFailuresAreNotBlamedOnARecord() {
        assertTrue(WorkoutExporter.isTransient(SecurityException("permission revoked")))
        assertTrue(WorkoutExporter.isTransient(java.io.IOException("no response")))
        assertTrue(WorkoutExporter.isTransient(IllegalStateException("Rate limited request quota has been exceeded")))
        assertTrue(WorkoutExporter.isRateLimit(IllegalStateException("API call quota exceeded")))
        assertTrue(WorkoutExporter.isTransient(IllegalStateException("Writing in the background is not allowed")))
        assertFalse(WorkoutExporter.isTransient(IllegalArgumentException("Invalid record")))
        assertFalse(WorkoutExporter.isRateLimit(IllegalArgumentException("Invalid record")))
    }

    // ---- Session type, titles ---------------------------------------------------------------------

    private fun typeOf(vararg exerciseIds: String): Int =
        WorkoutExportPlanner.session(workout(entries = exerciseIds.map { entry(it, set(doneAt = start + minute)) }), lookup).exerciseType

    @Test
    fun sessionTypeChoice() {
        assertEquals(strength, typeOf("bench", "squat"))
        assertEquals(strength, typeOf("push_up", "u:machine"))
        assertEquals(treadmillSession, typeOf("treadmill"))
        assertEquals(treadmillSession, typeOf("treadmill", "treadmill"))
        // Mixed lifting and cardio: strength training.
        assertEquals(strength, typeOf("bench", "treadmill"))
        // Cardio of different kinds, or one without a type: none of them is strength training.
        assertEquals(other, typeOf("treadmill", "bike"))
        assertEquals(other, typeOf("treadmill", "u:spinning"))
        assertEquals(other, typeOf("u:spinning"))
        assertEquals(other, typeOf("ski_erg"))
        assertEquals(strength, typeOf())
        // Only completed sets count: a planned bench press does not make a run a strength workout.
        val run = workout(entries = listOf(entry("treadmill", set(seconds = 600, doneAt = start + 20 * minute)), entry("bench", set(done = false))))
        assertEquals(treadmillSession, WorkoutExportPlanner.session(run, lookup).exerciseType)
    }

    @Test
    fun titlesAndNotes() {
        val named = WorkoutExportPlanner.session(workout(name = "  Leg day ", note = " felt strong\n", entries = listOf(entry("squat", set()))), lookup)
        assertEquals("Leg day", named.title)
        assertEquals("felt strong", named.notes)
        val unnamed = WorkoutExportPlanner.session(workout(name = "   ", note = "", entries = listOf(entry("squat", set()))), lookup)
        assertNull(unnamed.title)
        assertNull(unnamed.notes)
        // Fallback titles: the exercise type's name, "Workout" for mixed cardio.
        assertEquals(R.string.exercise_strength_training, WorkoutExporter.genericTitleRes(strength))
        assertEquals(R.string.exercise_running_treadmill, WorkoutExporter.genericTitleRes(treadmillSession))
        assertEquals(R.string.hc_workout_title, WorkoutExporter.genericTitleRes(other))
        val record = WorkoutExporter.buildRecord(unnamed, ZoneId.of("Europe/Madrid")) { "title of $it" }
        assertEquals("title of $strength", record.title)
        assertEquals("Leg day", WorkoutExporter.buildRecord(named, ZoneId.of("Europe/Madrid")) { "x" }.title)
    }

    // ---- Segments -------------------------------------------------------------------------------

    @Test
    fun segmentTypesFollowTheExerciseAndTheSessionType() {
        val mixed = WorkoutExportPlanner.session(
            workout(
                entries = listOf(
                    entry("treadmill", set(reps = null, seconds = 600, doneAt = start + 10 * minute)),
                    entry("bench", set(doneAt = start + 15 * minute)),
                    entry("push_up", set(doneAt = start + 20 * minute)),
                    entry("u:machine", set(doneAt = start + 25 * minute)),
                    entry("ski_erg", set(reps = null, seconds = 300, doneAt = start + 35 * minute)),
                ),
            ),
            lookup,
        )
        assertEquals(strength, mixed.exerciseType)
        assertEquals(
            listOf(segOther, ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS, segWeightlifting, segWeightlifting, segOther),
            mixed.segments.map { it.type },
        )
        // The treadmill warm-up keeps its time span in the strength session.
        assertEquals(start to start + 10 * minute, mixed.segments.first().let { it.start to it.end })

        val run = WorkoutExportPlanner.session(workout(entries = listOf(entry("treadmill", set(reps = null, seconds = 1200, doneAt = start + 30 * minute)))), lookup)
        assertEquals(listOf(ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL), run.segments.map { it.type })
        assertEquals(20 * minute, run.segments.single().let { it.end - it.start })

        val spinning = WorkoutExportPlanner.session(workout(entries = listOf(entry("u:spinning", set(reps = null, seconds = 900)))), lookup)
        assertEquals(listOf(segOther), spinning.segments.map { it.type })
    }

    @Test
    fun oneSegmentPerCompletedSetWithItsRepetitions() {
        val s = WorkoutExportPlanner.session(
            workout(
                entries = listOf(
                    entry(
                        "bench",
                        set(reps = 12, warmup = true, doneAt = start + 5 * minute),
                        set(reps = 8, doneAt = start + 8 * minute),
                        set(reps = null, doneAt = start + 11 * minute),
                        set(reps = -3, doneAt = start + 14 * minute),
                        set(reps = 8, done = false),
                    ),
                ),
            ),
            lookup,
        )
        assertEquals(listOf(12, 8, 0, 0), s.segments.map { it.repetitions })
        assertEquals(listOf(start + 5 * minute, start + 8 * minute, start + 11 * minute, start + 14 * minute), s.segments.map { it.end })
        // 12 reps ≈ 36 s, 8 reps ≈ 24 s, no reps or duration: 30 s.
        assertEquals(listOf(36_000L, 24_000L, 30_000L, 30_000L), s.segments.map { it.end - it.start })
    }

    @Test
    fun segmentsAreSortedClippedToTheSessionAndDoNotOverlap() {
        val s = WorkoutExportPlanner.session(
            workout(
                entries = listOf(
                    // Logged first, ticked last.
                    entry("squat", set(doneAt = start + 40 * minute)),
                    entry("bench", set(doneAt = start + 10 * minute)),
                    // Ticked before the start (start time edited) and after the end.
                    entry("plank", set(reps = null, seconds = 60, doneAt = start - 5 * minute), set(reps = null, seconds = 60, doneAt = start + 2 * hour)),
                ),
            ),
            lookup,
        )
        val ends = s.segments.map { it.end }
        assertEquals(ends.sorted(), ends)
        assertEquals(start to start + minute, s.segments.first().let { it.start to it.end })
        assertEquals(start + hour, s.segments.last().end)
        assertValid(s)
    }

    @Test
    fun setsTickedTogetherAreLaidOutOneAfterTheOther() {
        val t = start + 30 * minute
        val s = WorkoutExportPlanner.session(workout(entries = listOf(entry("bench", set(doneAt = t), set(doneAt = t), set(doneAt = t)))), lookup)
        assertEquals(listOf(t - 90_000, t - 60_000, t - 30_000), s.segments.map { it.start })
        assertEquals(listOf(t - 60_000, t - 30_000, t), s.segments.map { it.end })
        assertValid(s)
    }

    @Test
    fun setsWithoutTimeAreSpreadOverTheSession() {
        val s = WorkoutExportPlanner.session(workout(entries = listOf(entry("bench", set(), set(), set()))), lookup)
        assertEquals(listOf(start + 15 * minute, start + 30 * minute, start + 45 * minute), s.segments.map { it.end })
        // Between timed neighbours.
        val mixed = WorkoutExportPlanner.session(
            workout(entries = listOf(entry("bench", set(doneAt = start + 10 * minute), set(), set(doneAt = start + 20 * minute)))),
            lookup,
        )
        assertEquals(listOf(start + 10 * minute, start + 15 * minute, start + 20 * minute), mixed.segments.map { it.end })
    }

    @Test
    fun zeroLengthWorkoutsStillMakeValidSessions() {
        for (endedAt in listOf(start, start - minute)) {
            val s = WorkoutExportPlanner.session(
                workout(endedAt = endedAt, entries = listOf(entry("bench", *Array(10) { set(doneAt = start) }), entry("treadmill", set(reps = null, seconds = 3600)))),
                lookup,
            )
            assertEquals(start + WorkoutExportPlanner.MIN_SESSION_MS, s.end)
            assertTrue(s.segments.isNotEmpty())
            assertValid(s)
        }
        // Nothing completed: a session without segments.
        val empty = WorkoutExportPlanner.session(workout(entries = listOf(entry("bench", set(done = false)))), lookup)
        assertTrue(empty.segments.isEmpty())
        assertEquals(strength, empty.exerciseType)
        assertEquals(0, WorkoutExportPlanner.segments(emptyList(), start, start + hour).size)
        assertEquals(0, WorkoutExportPlanner.segments(listOf(WorkoutExportPlanner.LoggedSet(segOther, 0, 1_000, start)), start, start).size)
    }

    /** Within the session, sorted, no overlaps, at least the minimum length: what the library checks. */
    private fun assertValid(s: PlannedSession) {
        assertTrue(s.end > s.start)
        var previousEnd = s.start
        for (seg in s.segments) {
            assertTrue("starts before the previous end: $seg", seg.start >= previousEnd)
            assertTrue("too short: $seg", seg.end - seg.start >= WorkoutExportPlanner.MIN_SEGMENT_MS)
            assertTrue("after the session: $seg", seg.end <= s.end)
            assertTrue(seg.repetitions >= 0)
            assertTrue(WorkoutExportPlanner.compatible(seg.type, s.exerciseType))
            previousEnd = seg.end
        }
    }

    // ---- Against the Health Connect library -------------------------------------------------------

    private fun constants(type: Class<*>, prefix: String): List<Int> =
        type.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && it.name.startsWith(prefix) }
            .map { it.isAccessible = true; it.getInt(null) }

    @Test
    fun compatibilityMatchesTheLibraryForEveryPair() {
        val sessions = constants(ExerciseSessionRecord::class.java, "EXERCISE_TYPE_")
        val segments = constants(ExerciseSegment::class.java, "EXERCISE_SEGMENT_TYPE_")
        assertTrue(sessions.size > 50 && segments.size > 60)
        val t0 = Instant.ofEpochMilli(start)
        for (session in sessions) {
            for (segment in segments) {
                val accepted = try {
                    ExerciseSessionRecord(
                        startTime = t0,
                        startZoneOffset = null,
                        endTime = t0.plusSeconds(60),
                        endZoneOffset = null,
                        metadata = Metadata.manualEntry(),
                        exerciseType = session,
                        segments = listOf(ExerciseSegment(t0, t0.plusSeconds(30), segment, 1)),
                    )
                    true
                } catch (_: IllegalArgumentException) {
                    false
                }
                assertEquals("segment $segment in session $session", accepted, WorkoutExportPlanner.compatible(segment, session))
            }
        }
    }

    @Test
    fun randomWorkoutsAlwaysBuildRecordsWithAllTheirSegments() {
        val random = Random(7)
        val ids = exercises.keys.toList() + "unknown"
        val zone = ZoneId.of("America/New_York")
        repeat(500) { n ->
            val begin = start + random.nextLong(0, 1000L * hour)
            val length = if (random.nextInt(10) == 0) 0L else random.nextLong(1, 3 * hour)
            val entries = List(random.nextInt(0, 7)) { e ->
                val id = ids[random.nextInt(ids.size)]
                WorkoutEntry(
                    id = "e$e",
                    exerciseId = id,
                    sets = List(random.nextInt(0, 8)) { k ->
                        WorkoutSet(
                            id = "s$k",
                            reps = if (random.nextBoolean()) random.nextInt(-2, 40) else null,
                            durationSec = if (random.nextInt(3) == 0) random.nextInt(0, 7200) else null,
                            done = random.nextInt(5) != 0,
                            doneAt = when (random.nextInt(4)) {
                                0 -> null
                                1 -> begin + random.nextLong(-hour, length + hour) // outside too
                                else -> begin + random.nextLong(0, length + 1)
                            },
                        )
                    },
                )
            }
            val w = Workout("w$n", begin, begin + length, null, null, updatedAt = n.toLong(), entries = entries)
            val planned = WorkoutExportPlanner.session(w, lookup)
            assertValid(planned)
            val record = WorkoutExporter.buildRecord(planned, zone) { "Workout" }
            assertEquals(planned.segments.size, record.segments.size)
            assertEquals(planned.clientRecordId, record.metadata.clientRecordId)
            assertEquals(planned.version, record.metadata.clientRecordVersion)
            assertEquals(Metadata.RECORDING_METHOD_MANUAL_ENTRY, record.metadata.recordingMethod)
        }
    }

    @Test
    fun recordFallsBackToNoSegmentsWhenTheLibraryRejectsThem() {
        val bad = PlannedSession(
            workoutId = "w1",
            clientRecordId = "gf-workout-w1",
            version = 1,
            sourceUpdatedAt = 1,
            start = start,
            end = start + hour,
            exerciseType = treadmillSession,
            title = null,
            notes = null,
            segments = listOf(PlannedSegment(start, start + minute, ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS, 5)),
        )
        val record = WorkoutExporter.buildRecord(bad, ZoneId.of("UTC")) { "Run" }
        assertTrue(record.segments.isEmpty())
        assertEquals(treadmillSession, record.exerciseType)
        assertFalse(WorkoutExportPlanner.compatible(ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS, treadmillSession))
    }
}
