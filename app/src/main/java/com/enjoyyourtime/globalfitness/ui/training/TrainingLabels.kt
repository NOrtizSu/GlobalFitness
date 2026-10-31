package com.enjoyyourtime.globalfitness.ui.training

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle

/** String resources for the training enums (the model stays free of Android resources). */

@get:StringRes
val Muscle.labelRes: Int
    get() = when (this) {
        Muscle.CHEST -> R.string.muscle_chest
        Muscle.SHOULDERS -> R.string.muscle_shoulders
        Muscle.BICEPS -> R.string.muscle_biceps
        Muscle.TRICEPS -> R.string.muscle_triceps
        Muscle.FOREARMS -> R.string.muscle_forearms
        Muscle.ABS -> R.string.muscle_abs
        Muscle.OBLIQUES -> R.string.muscle_obliques
        Muscle.TRAPS -> R.string.muscle_traps
        Muscle.LATS -> R.string.muscle_lats
        Muscle.LOWER_BACK -> R.string.muscle_lower_back
        Muscle.GLUTES -> R.string.muscle_glutes
        Muscle.QUADS -> R.string.muscle_quads
        Muscle.HAMSTRINGS -> R.string.muscle_hamstrings
        Muscle.ADDUCTORS -> R.string.muscle_adductors
        Muscle.CALVES -> R.string.muscle_calves
        Muscle.NECK -> R.string.muscle_neck
    }

@Composable
fun Muscle.label(): String = stringResource(labelRes)

@get:StringRes
val Equipment.labelRes: Int
    get() = when (this) {
        Equipment.BARBELL -> R.string.equipment_barbell
        Equipment.DUMBBELL -> R.string.equipment_dumbbell
        Equipment.KETTLEBELL -> R.string.equipment_kettlebell
        Equipment.MACHINE -> R.string.equipment_machine
        Equipment.CABLE -> R.string.equipment_cable
        Equipment.SMITH_MACHINE -> R.string.equipment_smith
        Equipment.BODYWEIGHT -> R.string.equipment_bodyweight
        Equipment.BAND -> R.string.equipment_band
        Equipment.CARDIO_MACHINE -> R.string.equipment_cardio
        Equipment.SPORT -> R.string.equipment_sport
        Equipment.OTHER -> R.string.equipment_other
    }

@Composable
fun Equipment.label(): String = stringResource(labelRes)

/** Heading of the user's own text about an exercise: machine settings, plain notes for a sport or activity. */
@StringRes
fun notesTitleRes(equipment: Equipment): Int = if (equipment == Equipment.SPORT) R.string.training_sport_notes else R.string.training_machine_settings

/** The example in that field ("Seat 4, back rest 2"; for a sport "Court 3, Tuesday league"). */
@StringRes
fun notesHintRes(equipment: Equipment): Int = if (equipment == Equipment.SPORT) R.string.training_sport_notes_hint else R.string.training_machine_settings_hint

@get:StringRes
val ExerciseKind.labelRes: Int
    get() = when (this) {
        ExerciseKind.WEIGHT_REPS -> R.string.exercise_kind_weight_reps
        ExerciseKind.BODYWEIGHT_REPS -> R.string.exercise_kind_bodyweight_reps
        ExerciseKind.ASSISTED_REPS -> R.string.exercise_kind_assisted_reps
        ExerciseKind.DURATION -> R.string.exercise_kind_duration
        ExerciseKind.CARDIO -> R.string.exercise_kind_cardio
    }

@Composable
fun ExerciseKind.label(): String = stringResource(labelRes)

/** The exercise's name in the current language. */
@Composable
fun Exercise.displayName(): String = name.resolve(LocalContext.current)
