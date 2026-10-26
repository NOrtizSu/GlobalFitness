package com.enjoyyourtime.globalfitness.training.catalog

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.model.CatalogExercise
import com.enjoyyourtime.globalfitness.training.model.Equipment.BAND
import com.enjoyyourtime.globalfitness.training.model.Equipment.BARBELL
import com.enjoyyourtime.globalfitness.training.model.Equipment.BODYWEIGHT
import com.enjoyyourtime.globalfitness.training.model.Equipment.CABLE
import com.enjoyyourtime.globalfitness.training.model.Equipment.CARDIO_MACHINE
import com.enjoyyourtime.globalfitness.training.model.Equipment.DUMBBELL
import com.enjoyyourtime.globalfitness.training.model.Equipment.KETTLEBELL
import com.enjoyyourtime.globalfitness.training.model.Equipment.MACHINE
import com.enjoyyourtime.globalfitness.training.model.Equipment.SMITH_MACHINE
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind.ASSISTED_REPS
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind.BODYWEIGHT_REPS
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind.CARDIO
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind.DURATION
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind.WEIGHT_REPS
import com.enjoyyourtime.globalfitness.training.model.Muscle.ABS
import com.enjoyyourtime.globalfitness.training.model.Muscle.ADDUCTORS
import com.enjoyyourtime.globalfitness.training.model.Muscle.BICEPS
import com.enjoyyourtime.globalfitness.training.model.Muscle.CALVES
import com.enjoyyourtime.globalfitness.training.model.Muscle.CHEST
import com.enjoyyourtime.globalfitness.training.model.Muscle.FOREARMS
import com.enjoyyourtime.globalfitness.training.model.Muscle.GLUTES
import com.enjoyyourtime.globalfitness.training.model.Muscle.HAMSTRINGS
import com.enjoyyourtime.globalfitness.training.model.Muscle.LATS
import com.enjoyyourtime.globalfitness.training.model.Muscle.LOWER_BACK
import com.enjoyyourtime.globalfitness.training.model.Muscle.NECK
import com.enjoyyourtime.globalfitness.training.model.Muscle.OBLIQUES
import com.enjoyyourtime.globalfitness.training.model.Muscle.QUADS
import com.enjoyyourtime.globalfitness.training.model.Muscle.SHOULDERS
import com.enjoyyourtime.globalfitness.training.model.Muscle.TRAPS
import com.enjoyyourtime.globalfitness.training.model.Muscle.TRICEPS

/**
 * Built-in exercises and machines. Ids are stored in workouts and in the cloud backup: never
 * rename or remove one (mark it hidden in a later version if needed). Names are string resources
 * `ex_<id>` (tools/i18n/src/<locale>/97_exercises.txt).
 *
 * Muscles follow the usual hard-set counting: [CatalogExercise.primary] are the prime movers (one
 * set each), [CatalogExercise.secondary] the synergists that get real work (half a set); stabilizers
 * are left out so the weekly volume is not inflated. Every muscle of the body map is the primary of
 * at least two entries, so each one can be trained from the catalog alone.
 *
 * Health Connect: [CatalogExercise.hcSegment] is set only when a segment type really names the
 * movement (null exports as generic weightlifting). Every strength segment used here is accepted by
 * the STRENGTH_TRAINING, WEIGHTLIFTING and CALISTHENICS session types; a cardio segment is accepted
 * only by its own session type ([CatalogExercise.hcSession]).
 */
object ExerciseCatalog {
    val all: List<CatalogExercise> = listOf(
        // Selectorized and plate-loaded machines.
        CatalogExercise(
            "machine_chest_press", R.string.ex_machine_chest_press, WEIGHT_REPS, MACHINE,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "machine_incline_chest_press", R.string.ex_machine_incline_chest_press, WEIGHT_REPS, MACHINE,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS, TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "pec_deck", R.string.ex_pec_deck, WEIGHT_REPS, MACHINE,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS),
        ),
        CatalogExercise(
            "machine_shoulder_press", R.string.ex_machine_shoulder_press, WEIGHT_REPS, MACHINE,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS,
        ),
        CatalogExercise(
            "machine_lateral_raise", R.string.ex_machine_lateral_raise, WEIGHT_REPS, MACHINE,
            primary = setOf(SHOULDERS), secondary = setOf(TRAPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LATERAL_RAISE,
        ),
        CatalogExercise(
            "reverse_pec_deck", R.string.ex_reverse_pec_deck, WEIGHT_REPS, MACHINE,
            primary = setOf(SHOULDERS), secondary = setOf(TRAPS),
        ),
        CatalogExercise(
            "machine_row", R.string.ex_machine_row, WEIGHT_REPS, MACHINE,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "pullover_machine", R.string.ex_pullover_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(LATS), secondary = setOf(CHEST, TRICEPS),
        ),
        CatalogExercise(
            "leg_press_machine", R.string.ex_leg_press_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(QUADS), secondary = setOf(GLUTES, ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_PRESS,
        ),
        CatalogExercise(
            "horizontal_leg_press", R.string.ex_horizontal_leg_press, WEIGHT_REPS, MACHINE,
            primary = setOf(QUADS), secondary = setOf(GLUTES, ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_PRESS,
        ),
        CatalogExercise(
            "hack_squat_machine", R.string.ex_hack_squat_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(QUADS), secondary = setOf(GLUTES, ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "pendulum_squat_machine", R.string.ex_pendulum_squat_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(QUADS), secondary = setOf(GLUTES, ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "leg_extension_machine", R.string.ex_leg_extension_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(QUADS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_EXTENSION,
        ),
        CatalogExercise(
            "lying_leg_curl_machine", R.string.ex_lying_leg_curl_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_CURL,
        ),
        CatalogExercise(
            "seated_leg_curl_machine", R.string.ex_seated_leg_curl_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_CURL,
        ),
        CatalogExercise(
            "standing_calf_raise_machine", R.string.ex_standing_calf_raise_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(CALVES),
        ),
        CatalogExercise(
            "seated_calf_raise_machine", R.string.ex_seated_calf_raise_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(CALVES),
        ),
        CatalogExercise(
            "leg_press_calf_raise", R.string.ex_leg_press_calf_raise, WEIGHT_REPS, MACHINE,
            primary = setOf(CALVES),
        ),
        CatalogExercise(
            "hip_abductor_machine", R.string.ex_hip_abductor_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(GLUTES),
        ),
        CatalogExercise(
            "hip_adductor_machine", R.string.ex_hip_adductor_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(ADDUCTORS),
        ),
        CatalogExercise(
            "glute_kickback_machine", R.string.ex_glute_kickback_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(GLUTES), secondary = setOf(HAMSTRINGS),
        ),
        CatalogExercise(
            "hip_thrust_machine", R.string.ex_hip_thrust_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(GLUTES), secondary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST,
        ),
        CatalogExercise(
            "back_extension_machine", R.string.ex_back_extension_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(LOWER_BACK), secondary = setOf(GLUTES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BACK_EXTENSION,
        ),
        CatalogExercise(
            "ab_crunch_machine", R.string.ex_ab_crunch_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(ABS), secondary = setOf(OBLIQUES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_CRUNCH,
        ),
        CatalogExercise(
            "rotary_torso_machine", R.string.ex_rotary_torso_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(OBLIQUES), secondary = setOf(ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_UPPER_TWIST,
        ),
        CatalogExercise(
            "machine_biceps_curl", R.string.ex_machine_biceps_curl, WEIGHT_REPS, MACHINE,
            primary = setOf(BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "machine_triceps_extension", R.string.ex_machine_triceps_extension, WEIGHT_REPS, MACHINE,
            primary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION,
        ),
        CatalogExercise(
            "seated_dip_machine", R.string.ex_seated_dip_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(TRICEPS), secondary = setOf(CHEST, SHOULDERS),
        ),
        // Assisted machines: the logged weight is the ASSISTANCE (counterweight). The load model turns it into
        // body weight minus assistance, with the same body-weight share as the free exercise (pull-up / dip
        // 1.0), so moving from the machine to the bar continues the same strength curve.
        CatalogExercise(
            "assisted_pull_up_machine", R.string.ex_assisted_pull_up_machine, ASSISTED_REPS, MACHINE,
            primary = setOf(LATS), secondary = setOf(BICEPS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP,
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "assisted_chin_up_machine", R.string.ex_assisted_chin_up_machine, ASSISTED_REPS, MACHINE,
            primary = setOf(LATS, BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP,
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "assisted_dip_machine", R.string.ex_assisted_dip_machine, ASSISTED_REPS, MACHINE,
            primary = setOf(CHEST, TRICEPS), secondary = setOf(SHOULDERS),
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "neck_machine", R.string.ex_neck_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(NECK), secondary = setOf(TRAPS),
        ),

        // Cable stations
        CatalogExercise(
            "lat_pulldown_machine", R.string.ex_lat_pulldown_machine, WEIGHT_REPS, CABLE,
            primary = setOf(LATS), secondary = setOf(BICEPS, FOREARMS, TRAPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN,
        ),
        CatalogExercise(
            "close_grip_lat_pulldown", R.string.ex_close_grip_lat_pulldown, WEIGHT_REPS, CABLE,
            primary = setOf(LATS), secondary = setOf(BICEPS, FOREARMS, TRAPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN,
        ),
        CatalogExercise(
            "seated_cable_row", R.string.ex_seated_cable_row, WEIGHT_REPS, CABLE,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "single_arm_cable_row", R.string.ex_single_arm_cable_row, WEIGHT_REPS, CABLE,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "straight_arm_pulldown", R.string.ex_straight_arm_pulldown, WEIGHT_REPS, CABLE,
            primary = setOf(LATS), secondary = setOf(TRICEPS),
        ),
        CatalogExercise(
            "cable_crossover", R.string.ex_cable_crossover, WEIGHT_REPS, CABLE,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS),
        ),
        CatalogExercise(
            "low_cable_crossover", R.string.ex_low_cable_crossover, WEIGHT_REPS, CABLE,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS),
        ),
        CatalogExercise(
            "cable_fly", R.string.ex_cable_fly, WEIGHT_REPS, CABLE,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS),
        ),
        CatalogExercise(
            "face_pull", R.string.ex_face_pull, WEIGHT_REPS, CABLE,
            primary = setOf(SHOULDERS, TRAPS),
        ),
        CatalogExercise(
            "cable_lateral_raise", R.string.ex_cable_lateral_raise, WEIGHT_REPS, CABLE,
            primary = setOf(SHOULDERS), secondary = setOf(TRAPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LATERAL_RAISE,
        ),
        CatalogExercise(
            "cable_triceps_pushdown", R.string.ex_cable_triceps_pushdown, WEIGHT_REPS, CABLE,
            primary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION,
        ),
        CatalogExercise(
            "rope_triceps_pushdown", R.string.ex_rope_triceps_pushdown, WEIGHT_REPS, CABLE,
            primary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION,
        ),
        CatalogExercise(
            "cable_overhead_triceps_extension", R.string.ex_cable_overhead_triceps_extension, WEIGHT_REPS, CABLE,
            primary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION,
        ),
        CatalogExercise(
            "cable_curl", R.string.ex_cable_curl, WEIGHT_REPS, CABLE,
            primary = setOf(BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "cable_crunch", R.string.ex_cable_crunch, WEIGHT_REPS, CABLE,
            primary = setOf(ABS), secondary = setOf(OBLIQUES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_CRUNCH,
        ),
        CatalogExercise(
            "cable_woodchop", R.string.ex_cable_woodchop, WEIGHT_REPS, CABLE,
            primary = setOf(OBLIQUES), secondary = setOf(ABS, SHOULDERS),
        ),
        CatalogExercise(
            "cable_pull_through", R.string.ex_cable_pull_through, WEIGHT_REPS, CABLE,
            primary = setOf(GLUTES, HAMSTRINGS), secondary = setOf(LOWER_BACK),
        ),
        CatalogExercise(
            "cable_glute_kickback", R.string.ex_cable_glute_kickback, WEIGHT_REPS, CABLE,
            primary = setOf(GLUTES), secondary = setOf(HAMSTRINGS),
        ),

        // Smith machine
        CatalogExercise(
            "smith_squat", R.string.ex_smith_squat, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "smith_bench_press", R.string.ex_smith_bench_press, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "smith_incline_bench_press", R.string.ex_smith_incline_bench_press, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS, TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "smith_shoulder_press", R.string.ex_smith_shoulder_press, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS,
        ),
        CatalogExercise(
            "smith_split_squat", R.string.ex_smith_split_squat, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE,
        ),
        CatalogExercise(
            "smith_hip_thrust", R.string.ex_smith_hip_thrust, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(GLUTES), secondary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST,
        ),
        CatalogExercise(
            "smith_calf_raise", R.string.ex_smith_calf_raise, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(CALVES),
        ),

        // Barbell and EZ bar
        CatalogExercise(
            "barbell_bench_press", R.string.ex_barbell_bench_press, WEIGHT_REPS, BARBELL,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "barbell_incline_bench_press", R.string.ex_barbell_incline_bench_press, WEIGHT_REPS, BARBELL,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS, TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "barbell_decline_bench_press", R.string.ex_barbell_decline_bench_press, WEIGHT_REPS, BARBELL,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "close_grip_bench_press", R.string.ex_close_grip_bench_press, WEIGHT_REPS, BARBELL,
            primary = setOf(TRICEPS, CHEST), secondary = setOf(SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "barbell_overhead_press", R.string.ex_barbell_overhead_press, WEIGHT_REPS, BARBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS, TRAPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BARBELL_SHOULDER_PRESS,
        ),
        CatalogExercise(
            "barbell_back_squat", R.string.ex_barbell_back_squat, WEIGHT_REPS, BARBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS, LOWER_BACK),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "barbell_front_squat", R.string.ex_barbell_front_squat, WEIGHT_REPS, BARBELL,
            primary = setOf(QUADS), secondary = setOf(GLUTES, ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "barbell_deadlift", R.string.ex_barbell_deadlift, WEIGHT_REPS, BARBELL,
            primary = setOf(GLUTES, HAMSTRINGS, LOWER_BACK), secondary = setOf(QUADS, TRAPS, FOREARMS, ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT,
        ),
        CatalogExercise(
            "sumo_deadlift", R.string.ex_sumo_deadlift, WEIGHT_REPS, BARBELL,
            primary = setOf(GLUTES, QUADS, ADDUCTORS), secondary = setOf(HAMSTRINGS, LOWER_BACK, TRAPS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT,
        ),
        CatalogExercise(
            "barbell_romanian_deadlift", R.string.ex_barbell_romanian_deadlift, WEIGHT_REPS, BARBELL,
            primary = setOf(HAMSTRINGS, GLUTES), secondary = setOf(LOWER_BACK, ADDUCTORS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT,
        ),
        CatalogExercise(
            "good_morning", R.string.ex_good_morning, WEIGHT_REPS, BARBELL,
            primary = setOf(HAMSTRINGS, LOWER_BACK), secondary = setOf(GLUTES),
        ),
        CatalogExercise(
            "barbell_lunge", R.string.ex_barbell_lunge, WEIGHT_REPS, BARBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE,
        ),
        CatalogExercise(
            "barbell_hip_thrust", R.string.ex_barbell_hip_thrust, WEIGHT_REPS, BARBELL,
            primary = setOf(GLUTES), secondary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST,
        ),
        CatalogExercise(
            "barbell_glute_bridge", R.string.ex_barbell_glute_bridge, WEIGHT_REPS, BARBELL,
            primary = setOf(GLUTES), secondary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST,
        ),
        CatalogExercise(
            "barbell_row", R.string.ex_barbell_row, WEIGHT_REPS, BARBELL,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS, LOWER_BACK),
        ),
        CatalogExercise(
            "pendlay_row", R.string.ex_pendlay_row, WEIGHT_REPS, BARBELL,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS, LOWER_BACK),
        ),
        CatalogExercise(
            "t_bar_row", R.string.ex_t_bar_row, WEIGHT_REPS, BARBELL,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS, LOWER_BACK),
        ),
        CatalogExercise(
            "barbell_upright_row", R.string.ex_barbell_upright_row, WEIGHT_REPS, BARBELL,
            primary = setOf(SHOULDERS, TRAPS), secondary = setOf(BICEPS),
        ),
        CatalogExercise(
            "barbell_shrug", R.string.ex_barbell_shrug, WEIGHT_REPS, BARBELL,
            primary = setOf(TRAPS), secondary = setOf(FOREARMS),
        ),
        CatalogExercise(
            "barbell_curl", R.string.ex_barbell_curl, WEIGHT_REPS, BARBELL,
            primary = setOf(BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "ez_bar_curl", R.string.ex_ez_bar_curl, WEIGHT_REPS, BARBELL,
            primary = setOf(BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "ez_bar_preacher_curl", R.string.ex_ez_bar_preacher_curl, WEIGHT_REPS, BARBELL,
            primary = setOf(BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "skull_crusher", R.string.ex_skull_crusher, WEIGHT_REPS, BARBELL,
            primary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION,
        ),
        CatalogExercise(
            "barbell_wrist_curl", R.string.ex_barbell_wrist_curl, WEIGHT_REPS, BARBELL,
            primary = setOf(FOREARMS),
        ),
        CatalogExercise(
            "barbell_reverse_wrist_curl", R.string.ex_barbell_reverse_wrist_curl, WEIGHT_REPS, BARBELL,
            primary = setOf(FOREARMS),
        ),

        // Dumbbells
        CatalogExercise(
            "dumbbell_bench_press", R.string.ex_dumbbell_bench_press, WEIGHT_REPS, DUMBBELL,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "dumbbell_incline_bench_press", R.string.ex_dumbbell_incline_bench_press, WEIGHT_REPS, DUMBBELL,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS, TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "dumbbell_fly", R.string.ex_dumbbell_fly, WEIGHT_REPS, DUMBBELL,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS),
        ),
        CatalogExercise(
            "dumbbell_pullover", R.string.ex_dumbbell_pullover, WEIGHT_REPS, DUMBBELL,
            primary = setOf(CHEST, LATS), secondary = setOf(TRICEPS),
        ),
        CatalogExercise(
            "dumbbell_shoulder_press", R.string.ex_dumbbell_shoulder_press, WEIGHT_REPS, DUMBBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS,
        ),
        CatalogExercise(
            "arnold_press", R.string.ex_arnold_press, WEIGHT_REPS, DUMBBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS,
        ),
        CatalogExercise(
            "dumbbell_lateral_raise", R.string.ex_dumbbell_lateral_raise, WEIGHT_REPS, DUMBBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRAPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_LATERAL_RAISE,
        ),
        CatalogExercise(
            "dumbbell_front_raise", R.string.ex_dumbbell_front_raise, WEIGHT_REPS, DUMBBELL,
            primary = setOf(SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_FRONT_RAISE,
        ),
        CatalogExercise(
            "dumbbell_rear_delt_fly", R.string.ex_dumbbell_rear_delt_fly, WEIGHT_REPS, DUMBBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRAPS),
        ),
        CatalogExercise(
            "dumbbell_row", R.string.ex_dumbbell_row, WEIGHT_REPS, DUMBBELL,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_ROW,
        ),
        CatalogExercise(
            "dumbbell_shrug", R.string.ex_dumbbell_shrug, WEIGHT_REPS, DUMBBELL,
            primary = setOf(TRAPS), secondary = setOf(FOREARMS),
        ),
        CatalogExercise(
            "dumbbell_curl", R.string.ex_dumbbell_curl, WEIGHT_REPS, DUMBBELL,
            primary = setOf(BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "hammer_curl", R.string.ex_hammer_curl, WEIGHT_REPS, DUMBBELL,
            primary = setOf(BICEPS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "incline_dumbbell_curl", R.string.ex_incline_dumbbell_curl, WEIGHT_REPS, DUMBBELL,
            primary = setOf(BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "concentration_curl", R.string.ex_concentration_curl, WEIGHT_REPS, DUMBBELL,
            primary = setOf(BICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "dumbbell_overhead_extension", R.string.ex_dumbbell_overhead_extension, WEIGHT_REPS, DUMBBELL,
            primary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_TWO_ARM,
        ),
        CatalogExercise(
            "dumbbell_triceps_kickback", R.string.ex_dumbbell_triceps_kickback, WEIGHT_REPS, DUMBBELL,
            primary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SINGLE_ARM_TRICEPS_EXTENSION,
        ),
        CatalogExercise(
            "dumbbell_goblet_squat", R.string.ex_dumbbell_goblet_squat, WEIGHT_REPS, DUMBBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "bulgarian_split_squat", R.string.ex_bulgarian_split_squat, WEIGHT_REPS, DUMBBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE,
        ),
        CatalogExercise(
            "dumbbell_lunge", R.string.ex_dumbbell_lunge, WEIGHT_REPS, DUMBBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE,
        ),
        CatalogExercise(
            "dumbbell_step_up", R.string.ex_dumbbell_step_up, WEIGHT_REPS, DUMBBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(HAMSTRINGS),
        ),
        CatalogExercise(
            "dumbbell_romanian_deadlift", R.string.ex_dumbbell_romanian_deadlift, WEIGHT_REPS, DUMBBELL,
            primary = setOf(HAMSTRINGS, GLUTES), secondary = setOf(LOWER_BACK, ADDUCTORS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT,
        ),
        CatalogExercise(
            "dumbbell_side_bend", R.string.ex_dumbbell_side_bend, WEIGHT_REPS, DUMBBELL,
            primary = setOf(OBLIQUES), secondary = setOf(LOWER_BACK),
        ),

        // Kettlebells
        CatalogExercise(
            "kettlebell_swing", R.string.ex_kettlebell_swing, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(GLUTES, HAMSTRINGS), secondary = setOf(LOWER_BACK, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_KETTLEBELL_SWING,
        ),
        CatalogExercise(
            "kettlebell_goblet_squat", R.string.ex_kettlebell_goblet_squat, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "kettlebell_clean_and_press", R.string.ex_kettlebell_clean_and_press, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS, GLUTES, HAMSTRINGS, TRAPS),
        ),
        CatalogExercise(
            "turkish_get_up", R.string.ex_turkish_get_up, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(SHOULDERS, ABS), secondary = setOf(OBLIQUES, GLUTES, TRICEPS),
        ),

        // Bodyweight: repetitions, optionally with extra load (vest, belt, dumbbell). bodyweightFactor is the share
        // of the body weight lifted where it is well established (pull-up, dip ~ all of it; push-up ~ 65 %); the
        // others (core, jumps, lunges, bench dips...) only count their extra load.
        CatalogExercise(
            "push_up", R.string.ex_push_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
            bodyweightFactor = 0.65,
        ),
        CatalogExercise(
            "parallel_bar_dip", R.string.ex_parallel_bar_dip, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(CHEST, TRICEPS), secondary = setOf(SHOULDERS),
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "bench_dip", R.string.ex_bench_dip, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(TRICEPS), secondary = setOf(CHEST, SHOULDERS),
        ),
        CatalogExercise(
            "pull_up", R.string.ex_pull_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(LATS), secondary = setOf(BICEPS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP,
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "chin_up", R.string.ex_chin_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(LATS, BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP,
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "inverted_row", R.string.ex_inverted_row, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "bodyweight_squat", R.string.ex_bodyweight_squat, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "bodyweight_lunge", R.string.ex_bodyweight_lunge, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE,
        ),
        CatalogExercise(
            "glute_bridge", R.string.ex_glute_bridge, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(GLUTES), secondary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST,
        ),
        CatalogExercise(
            "back_extension", R.string.ex_back_extension, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(LOWER_BACK), secondary = setOf(GLUTES, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BACK_EXTENSION,
        ),
        CatalogExercise(
            "nordic_curl", R.string.ex_nordic_curl, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_CURL,
        ),
        CatalogExercise(
            "crunch", R.string.ex_crunch, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_CRUNCH,
        ),
        CatalogExercise(
            "sit_up", R.string.ex_sit_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SIT_UP,
        ),
        CatalogExercise(
            "hanging_leg_raise", R.string.ex_hanging_leg_raise, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(OBLIQUES, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_RAISE,
        ),
        CatalogExercise(
            "lying_leg_raise", R.string.ex_lying_leg_raise, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_RAISE,
        ),
        CatalogExercise(
            "russian_twist", R.string.ex_russian_twist, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(OBLIQUES), secondary = setOf(ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_UPPER_TWIST,
        ),
        CatalogExercise(
            "mountain_climber", R.string.ex_mountain_climber, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(SHOULDERS, OBLIQUES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_MOUNTAIN_CLIMBER,
        ),
        CatalogExercise(
            "burpee", R.string.ex_burpee, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(QUADS, CHEST), secondary = setOf(GLUTES, SHOULDERS, TRICEPS, ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BURPEE,
        ),

        // Timed holds and carries: seconds, optionally with load
        CatalogExercise(
            "plank", R.string.ex_plank, DURATION, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(OBLIQUES, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PLANK,
        ),
        CatalogExercise(
            "side_plank", R.string.ex_side_plank, DURATION, BODYWEIGHT,
            primary = setOf(OBLIQUES), secondary = setOf(ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PLANK,
        ),
        CatalogExercise(
            "copenhagen_plank", R.string.ex_copenhagen_plank, DURATION, BODYWEIGHT,
            primary = setOf(ADDUCTORS), secondary = setOf(OBLIQUES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PLANK,
        ),
        CatalogExercise(
            "wall_sit", R.string.ex_wall_sit, DURATION, BODYWEIGHT,
            primary = setOf(QUADS), secondary = setOf(GLUTES),
        ),
        CatalogExercise(
            "dead_hang", R.string.ex_dead_hang, DURATION, BODYWEIGHT,
            primary = setOf(FOREARMS),
        ),
        CatalogExercise(
            "farmers_carry", R.string.ex_farmers_carry, DURATION, DUMBBELL,
            primary = setOf(FOREARMS, TRAPS), secondary = setOf(ABS, OBLIQUES),
        ),

        // Resistance bands: the band's pull cannot be weighed, so they log repetitions only
        CatalogExercise(
            "band_pull_apart", R.string.ex_band_pull_apart, BODYWEIGHT_REPS, BAND,
            primary = setOf(SHOULDERS, TRAPS),
        ),
        CatalogExercise(
            "banded_lateral_walk", R.string.ex_banded_lateral_walk, BODYWEIGHT_REPS, BAND,
            primary = setOf(GLUTES),
        ),

        // Cardio machines: no muscles, they train the heart (see TrainingMath.countsForMuscles)
        CatalogExercise(
            "treadmill", R.string.ex_treadmill, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
        ),
        CatalogExercise(
            "stationary_bike", R.string.ex_stationary_bike, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
        ),
        CatalogExercise(
            "spin_bike", R.string.ex_spin_bike, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
        ),
        CatalogExercise(
            "recumbent_bike", R.string.ex_recumbent_bike, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
        ),
        CatalogExercise(
            "air_bike", R.string.ex_air_bike, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
        ),
        CatalogExercise(
            "elliptical", R.string.ex_elliptical, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ELLIPTICAL,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL,
        ),
        CatalogExercise(
            "rowing_machine", R.string.ex_rowing_machine, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
        ),
        CatalogExercise(
            "stair_climber", R.string.ex_stair_climber, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING_MACHINE,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE,
        ),
        CatalogExercise(
            "ski_erg", R.string.ex_ski_erg, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,
        ),
    )

    private val byId: Map<String, CatalogExercise> = all.associateBy { it.id }

    operator fun get(id: String): CatalogExercise? = byId[id]
}
