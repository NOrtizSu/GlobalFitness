package com.enjoyyourtime.globalfitness.training.data

import com.enjoyyourtime.globalfitness.training.model.CustomExercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseNote
import com.enjoyyourtime.globalfitness.training.model.Routine
import com.enjoyyourtime.globalfitness.training.model.Tombstone
import com.enjoyyourtime.globalfitness.training.model.Workout

/** Blocking persistence of the training data ([TrainingDatabase] on the phone, a fake in tests). */
interface TrainingStore {
    fun loadWorkouts(): List<Workout>

    fun loadCustomExercises(): List<CustomExercise>

    fun loadNotes(): List<ExerciseNote>

    fun loadRoutines(): List<Routine>

    fun loadTombstones(): List<Tombstone>

    /** Inserts or fully replaces the workout with its entries and sets (one transaction). */
    fun upsertWorkout(workout: Workout)

    /** Removes the workout and records a tombstone (one transaction). */
    fun deleteWorkout(id: String, deletedAt: Long)

    fun upsertCustomExercise(exercise: CustomExercise)

    /** Removes the custom exercise and records a tombstone (only for exercises never used). */
    fun deleteCustomExercise(id: String, deletedAt: Long)

    fun upsertNote(note: ExerciseNote)

    /** Removes the note and records a tombstone. */
    fun deleteNote(exerciseId: String, deletedAt: Long)

    fun upsertRoutine(routine: Routine)

    /** Removes the routine and records a tombstone. */
    fun deleteRoutine(id: String, deletedAt: Long)

    /** Forgets tombstones once the backup no longer needs them. */
    fun dropTombstones(tombstones: Collection<Tombstone>)

    /**
     * Applies a batch from the cloud backup in one transaction: upserts, then removals. Removals
     * coming from the cloud leave no local tombstone (the cloud already has one).
     */
    fun applyBatch(batch: TrainingBatch)
}

/** A set of changes applied atomically (cloud restore / merge). */
data class TrainingBatch(
    val upsertWorkouts: List<Workout> = emptyList(),
    val removeWorkouts: List<String> = emptyList(),
    val upsertCustomExercises: List<CustomExercise> = emptyList(),
    val removeCustomExercises: List<String> = emptyList(),
    val upsertNotes: List<ExerciseNote> = emptyList(),
    val removeNotes: List<String> = emptyList(),
    val upsertRoutines: List<Routine> = emptyList(),
    val removeRoutines: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = upsertWorkouts.isEmpty() && removeWorkouts.isEmpty() && upsertCustomExercises.isEmpty() &&
            removeCustomExercises.isEmpty() && upsertNotes.isEmpty() && removeNotes.isEmpty() &&
            upsertRoutines.isEmpty() && removeRoutines.isEmpty()
}

/**
 * The `updatedAt` of every item when a reader (the cloud sync) took its snapshot; an id that is not
 * in a map did not exist then. See [TrainingRepository.applyRemoteIfUnchanged].
 */
data class TrainingVersions(
    val workouts: Map<String, Long> = emptyMap(),
    val customExercises: Map<String, Long> = emptyMap(),
    /** By exercise id. */
    val notes: Map<String, Long> = emptyMap(),
    val routines: Map<String, Long> = emptyMap(),
)
