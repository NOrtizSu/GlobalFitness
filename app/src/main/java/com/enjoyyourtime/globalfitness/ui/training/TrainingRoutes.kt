package com.enjoyyourtime.globalfitness.ui.training

import android.net.Uri

/**
 * Sub-pages of the training feature (registered by [trainingGraph]). Other sections may open the
 * ones with a builder here; the training UI may add more internal routes.
 */
object TrainingRoutes {
    const val ARG_EXERCISE_ID = "exerciseId"
    const val ARG_WORKOUT_ID = "workoutId"
    const val ARG_ROUTINE_ID = "routineId"
    internal const val ARG_FROM = "from"
    internal const val ARG_PICKER = "picker"
    internal const val ARG_SPORT = "sport"

    /** One exercise or machine: progress chart, records, history, notes and photo. */
    const val EXERCISE = "entreno/ejercicio/{$ARG_EXERCISE_ID}"

    fun exercise(id: String) = "entreno/ejercicio/${Uri.encode(id)}"

    /** A workout (in progress or past). */
    const val WORKOUT = "entreno/sesion/{$ARG_WORKOUT_ID}"

    fun workout(id: String) = "entreno/sesion/${Uri.encode(id)}"

    /** The exercise and machine catalog (browse, create, edit). */
    const val CATALOG = "entreno/catalogo"

    /** Workout history. */
    const val HISTORY = "entreno/historial"

    /** Training preferences (load unit, rest timer, Health Connect). */
    const val SETTINGS = "entreno/ajustes"

    /** Medals earned in training and body goals, and the next ones with their progress. */
    const val ACHIEVEMENTS = "entreno/logros"

    /** A routine (built-in or the user's): its exercises and targets; start it or add it to the workout. */
    const val ROUTINE = "entreno/rutinas/{$ARG_ROUTINE_ID}"

    fun routine(id: String) = "entreno/rutinas/${Uri.encode(id)}"

    /**
     * Internal: create a routine (no id), edit one of the user's ([ARG_ROUTINE_ID]) or start a new one
     * as a copy of any routine ([ARG_FROM], e.g. a built-in one).
     */
    internal const val ROUTINE_EDITOR = "entreno/rutina-editor?$ARG_ROUTINE_ID={$ARG_ROUTINE_ID}&$ARG_FROM={$ARG_FROM}"

    internal fun routineEditor(routineId: String? = null, from: String? = null): String {
        val query = listOfNotNull(
            routineId?.let { "$ARG_ROUTINE_ID=${Uri.encode(it)}" },
            from?.let { "$ARG_FROM=${Uri.encode(it)}" },
        )
        return if (query.isEmpty()) "entreno/rutina-editor" else "entreno/rutina-editor?" + query.joinToString("&")
    }

    /** Internal: choose exercises for the routine editor below it ([RESULT_PICKED_EXERCISES]). */
    internal const val ROUTINE_PICKER = "entreno/rutina-editor/anadir"

    /** Internal: choose exercises to add to a workout. */
    internal const val PICKER = "entreno/sesion/{$ARG_WORKOUT_ID}/anadir"

    internal fun picker(workoutId: String) = "entreno/sesion/${Uri.encode(workoutId)}/anadir"

    /**
     * Internal: create (no exercise id) or edit a custom exercise or machine. [ARG_PICKER]: it was
     * opened from an exercise picker (of a workout or of the routine editor), which gets the created
     * one back ticked ([RESULT_CREATED_EXERCISE] in the picker's saved state). [ARG_SPORT]: a new
     * one starts as a sport or activity (the catalog showed the sports).
     */
    internal const val EDITOR = "entreno/editor?$ARG_EXERCISE_ID={$ARG_EXERCISE_ID}&$ARG_PICKER={$ARG_PICKER}&$ARG_SPORT={$ARG_SPORT}"

    internal fun editor(exerciseId: String? = null, forPicker: Boolean = false, sport: Boolean = false): String {
        val query = listOfNotNull(
            exerciseId?.let { "$ARG_EXERCISE_ID=${Uri.encode(it)}" },
            "$ARG_PICKER=true".takeIf { forPicker },
            "$ARG_SPORT=true".takeIf { sport },
        )
        return if (query.isEmpty()) "entreno/editor" else "entreno/editor?" + query.joinToString("&")
    }

    /** Saved-state key: the exercise the editor created for the picker below it. */
    internal const val RESULT_CREATED_EXERCISE = "createdExerciseId"

    /** Saved-state key: the exercises the routine picker chose, for the routine editor below it. */
    internal const val RESULT_PICKED_EXERCISES = "pickedExerciseIds"
}
