package com.enjoyyourtime.globalfitness.training

import com.enjoyyourtime.globalfitness.training.data.TrainingBatch
import com.enjoyyourtime.globalfitness.training.data.TrainingStore
import com.enjoyyourtime.globalfitness.training.model.CustomExercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseNote
import com.enjoyyourtime.globalfitness.training.model.Routine
import com.enjoyyourtime.globalfitness.training.model.Tombstone
import com.enjoyyourtime.globalfitness.training.model.Workout

/** In-memory [TrainingStore] with the same semantics as the SQLite one. */
class FakeTrainingStore(
    workouts: List<Workout> = emptyList(),
    custom: List<CustomExercise> = emptyList(),
    notes: List<ExerciseNote> = emptyList(),
) : TrainingStore {
    val workouts = LinkedHashMap<String, Workout>().apply { workouts.forEach { put(it.id, it) } }
    val custom = LinkedHashMap<String, CustomExercise>().apply { custom.forEach { put(it.id, it) } }
    val notes = LinkedHashMap<String, ExerciseNote>().apply { notes.forEach { put(it.exerciseId, it) } }
    val routines = LinkedHashMap<String, Routine>()
    val tombstones = LinkedHashMap<Pair<String, String>, Tombstone>()

    override fun loadWorkouts() = workouts.values.sortedWith(compareBy({ it.startedAt }, { it.id }))

    override fun loadCustomExercises() = custom.values.toList()

    override fun loadNotes() = notes.values.toList()

    override fun loadRoutines() = routines.values.toList()

    override fun loadTombstones() = tombstones.values.toList()

    override fun upsertWorkout(workout: Workout) {
        workouts[workout.id] = workout
        tombstones.remove(Tombstone.WORKOUT to workout.id)
    }

    override fun deleteWorkout(id: String, deletedAt: Long) {
        workouts.remove(id)
        tombstones[Tombstone.WORKOUT to id] = Tombstone(Tombstone.WORKOUT, id, deletedAt)
    }

    override fun upsertCustomExercise(exercise: CustomExercise) {
        custom[exercise.id] = exercise
        tombstones.remove(Tombstone.CUSTOM_EXERCISE to exercise.id)
    }

    override fun deleteCustomExercise(id: String, deletedAt: Long) {
        custom.remove(id)
        tombstones[Tombstone.CUSTOM_EXERCISE to id] = Tombstone(Tombstone.CUSTOM_EXERCISE, id, deletedAt)
    }

    override fun upsertNote(note: ExerciseNote) {
        notes[note.exerciseId] = note
        tombstones.remove(Tombstone.NOTE to note.exerciseId)
    }

    override fun deleteNote(exerciseId: String, deletedAt: Long) {
        notes.remove(exerciseId)
        tombstones[Tombstone.NOTE to exerciseId] = Tombstone(Tombstone.NOTE, exerciseId, deletedAt)
    }

    override fun upsertRoutine(routine: Routine) {
        routines[routine.id] = routine
        tombstones.remove(Tombstone.ROUTINE to routine.id)
    }

    override fun deleteRoutine(id: String, deletedAt: Long) {
        routines.remove(id)
        tombstones[Tombstone.ROUTINE to id] = Tombstone(Tombstone.ROUTINE, id, deletedAt)
    }

    override fun dropTombstones(tombstones: Collection<Tombstone>) {
        for (t in tombstones) {
            val key = t.kind to t.id
            if ((this.tombstones[key]?.deletedAt ?: Long.MAX_VALUE) <= t.deletedAt) this.tombstones.remove(key)
        }
    }

    override fun applyBatch(batch: TrainingBatch) {
        batch.upsertCustomExercises.forEach { custom[it.id] = it }
        batch.upsertNotes.forEach { notes[it.exerciseId] = it }
        batch.upsertWorkouts.forEach { workouts[it.id] = it }
        batch.removeWorkouts.forEach { workouts.remove(it) }
        batch.removeCustomExercises.forEach { custom.remove(it) }
        batch.removeNotes.forEach { notes.remove(it) }
        batch.upsertRoutines.forEach { routines[it.id] = it }
        batch.removeRoutines.forEach { routines.remove(it) }
    }
}
