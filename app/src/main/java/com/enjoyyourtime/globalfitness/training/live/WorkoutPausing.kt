package com.enjoyyourtime.globalfitness.training.live

import com.enjoyyourtime.globalfitness.training.data.TrainingRepository

/**
 * Pauses and resumes the workout in progress together with its rest timer, from the workout's page,
 * the cards and the ongoing notification (whose buttons work with the app closed). The rest stands
 * still while the workout is paused and goes on with the time it had left.
 */
class WorkoutPausing(
    private val repository: TrainingRepository,
    private val rest: RestTimers,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun pause(workoutId: String) {
        val at = clock()
        val w = repository.pauseWorkout(workoutId, at) ?: return
        if (w.paused) rest.pause(workoutId, at)
    }

    suspend fun resume(workoutId: String) {
        val at = clock()
        val w = repository.resumeWorkout(workoutId, at) ?: return
        if (!w.paused) rest.resume(workoutId, at)
    }
}
