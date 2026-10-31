package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.Decoded
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutPause
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pauses of a workout in the cloud backup, and how older app versions see them. */
class TrainingCodecPausesTest {
    private val t0 = 1_700_000_000_000L
    private val min = 60_000L

    private val workout = Workout(
        id = "w-1",
        startedAt = t0,
        endedAt = t0 + 60 * min,
        name = "Legs",
        note = null,
        updatedAt = t0 + 61 * min,
        entries = listOf(WorkoutEntry("e-1", "barbell_squat", listOf(WorkoutSet(id = "s-1", weightKg = 100.0, reps = 5, done = true, doneAt = t0 + 5 * min)))),
        pauses = listOf(WorkoutPause(t0 + 10 * min, t0 + 15 * min), WorkoutPause(t0 + 30 * min, t0 + 40 * min)),
    )

    /** What Firestore hands back: every whole number as a Long. */
    private fun firestoreLike(value: Any?): Any? = when (value) {
        is Int -> value.toLong()
        is Map<*, *> -> value.entries.associate { (k, v) -> k to firestoreLike(v) }
        is List<*> -> value.map(::firestoreLike)
        else -> value
    }

    @Suppress("UNCHECKED_CAST")
    private fun firestoreLike(map: Map<String, Any?>): Map<String, Any?> = firestoreLike(map as Any?) as Map<String, Any?>

    private fun decode(map: Map<String, Any?>): Workout = (TrainingCodec.workoutFromMap("w-1", firestoreLike(map), updatedAtMillis = 5L) as Decoded.Live).record

    @Test
    fun pausesRoundTripWithTheWorkoutAndItsVersion() {
        val map = TrainingCodec.workoutToMap(workout)
        assertEquals(TrainingCodec.TRAINING_SCHEMA_VERSION, map[FirestoreCodec.F_SCHEMA])
        assertEquals(
            listOf(mapOf("start" to t0 + 10 * min, "end" to t0 + 15 * min), mapOf("start" to t0 + 30 * min, "end" to t0 + 40 * min)),
            map["pauses"],
        )
        val decoded = decode(map)
        assertEquals(workout, decoded)
        assertEquals(TrainingMerge.versionOf(workout), TrainingMerge.versionOf(decoded))
        // The open pause of a workout in progress has no end in the document.
        val live = workout.copy(endedAt = null, pauses = listOf(WorkoutPause(t0 + 10 * min, null)))
        assertEquals(listOf(mapOf("start" to t0 + 10 * min)), TrainingCodec.workoutToMap(live)["pauses"])
        assertEquals(live, decode(TrainingCodec.workoutToMap(live)))
    }

    @Test
    fun aWorkoutWithoutPausesWritesAnEmptyListAndKeepsItsOldVersion() {
        val plain = workout.copy(pauses = emptyList())
        // Written even when empty: a merged write must clear the pauses an earlier version had.
        val map = TrainingCodec.workoutToMap(plain)
        assertTrue(map.containsKey("pauses"))
        assertEquals(emptyList<Any>(), map["pauses"])
        // Documents of older versions have no field at all.
        assertEquals(plain, decode(map - "pauses"))
        // Pauses are part of the version (a pause changes the document), but only when there are any.
        assertNotEquals(TrainingMerge.versionOf(plain), TrainingMerge.versionOf(workout))
        assertFalse(TrainingCodec.isNewerSchema(map))
    }

    @Test
    fun pausesFromOutsideAreCleanedAndAnOpenOneEndsWithAFinishedWorkout() {
        val map = TrainingCodec.workoutToMap(workout) + (
            "pauses" to listOf(
                mapOf("start" to t0 + 30 * min, "end" to t0 + 40 * min),
                mapOf("start" to t0 + 35 * min, "end" to t0 + 38 * min), // inside the previous one
                mapOf("end" to t0 + 5 * min), // no start
                "junk",
                // Left open on a phone that paused the workout; an older app finished it.
                mapOf("start" to t0 + 50 * min),
            )
            )
        assertEquals(listOf(WorkoutPause(t0 + 30 * min, t0 + 40 * min), WorkoutPause(t0 + 50 * min, t0 + 60 * min)), decode(map).pauses)
    }

    @Test
    fun aMergedWriteOfAnOlderAppKeepsThePausesAndTheSyncAppliesBoth() {
        // 1.3.0 renames the workout: its document has no "pauses" field, and it is written merged
        // into the cloud's copy (FirestoreTrainingRemote), so the field written by this version stays.
        val renamed = workout.copy(name = "Legs (heavy)", updatedAt = workout.updatedAt + 10 * min, pauses = emptyList())
        val olderAppWrite = TrainingCodec.workoutToMap(renamed) - "pauses"
        val cloud = TrainingCodec.workoutToMap(workout) + olderAppWrite
        val read = decode(cloud)
        assertEquals("Legs (heavy)", read.name)
        assertEquals(workout.pauses, read.pauses)
        // This phone still has its own older copy: the newer cloud copy wins, pauses included.
        val plan = TrainingMerge.plan(
            TrainingMode.MERGE,
            TrainingLocal(listOf(workout), emptyList(), emptyList(), emptyList(), emptyList(), emptyMap()),
            TrainingCloudSnapshot(
                RemoteDocs(listOf(read), emptyList(), true, null),
                RemoteDocs(emptyList(), emptyList(), true, null),
                RemoteDocs(emptyList(), emptyList(), true, null),
                RemoteDocs(emptyList(), emptyList(), true, null),
            ),
            TrainingBase("ds", entries = mapOf(DocKey(TrainingKind.WORKOUT, workout.id) to TrainingMerge.versionOf(workout))),
            now = t0 + 100 * min,
        )
        assertEquals(listOf(read), plan.apply.upsertWorkouts)
        assertTrue(plan.docWrites.isEmpty())
    }
}
