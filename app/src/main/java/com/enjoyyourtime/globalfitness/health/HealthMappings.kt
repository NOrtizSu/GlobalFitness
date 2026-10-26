package com.enjoyyourtime.globalfitness.health

import androidx.annotation.StringRes
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import com.enjoyyourtime.globalfitness.R

/** Pure mappings from Health Connect codes to app concepts (names are string resources). */
object HealthMappings {

    fun sleepStage(hcStage: Int): SleepStage = when (hcStage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
        -> SleepStage.AWAKE
        SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepStage.LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStage.DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> SleepStage.REM
        // "Sleeping" (asleep, stage not specified) and unknown codes.
        else -> SleepStage.UNKNOWN
    }

    private val exerciseNames: Map<Int, Int> = mapOf(
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to R.string.exercise_other_workout,
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to R.string.exercise_badminton,
        ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL to R.string.exercise_baseball,
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL to R.string.exercise_basketball,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING to R.string.exercise_biking,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to R.string.exercise_biking_stationary,
        ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP to R.string.exercise_boot_camp,
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING to R.string.exercise_boxing,
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS to R.string.exercise_calisthenics,
        ExerciseSessionRecord.EXERCISE_TYPE_CRICKET to R.string.exercise_cricket,
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING to R.string.exercise_dancing,
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to R.string.exercise_elliptical,
        ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS to R.string.exercise_exercise_class,
        ExerciseSessionRecord.EXERCISE_TYPE_FENCING to R.string.exercise_fencing,
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN to R.string.exercise_football_american,
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN to R.string.exercise_football_australian,
        ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC to R.string.exercise_frisbee,
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF to R.string.exercise_golf,
        ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING to R.string.exercise_guided_breathing,
        ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS to R.string.exercise_gymnastics,
        ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL to R.string.exercise_handball,
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to R.string.exercise_hiit,
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING to R.string.exercise_hiking,
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY to R.string.exercise_ice_hockey,
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING to R.string.exercise_ice_skating,
        ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS to R.string.exercise_martial_arts,
        ExerciseSessionRecord.EXERCISE_TYPE_PADDLING to R.string.exercise_paddling,
        ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING to R.string.exercise_paragliding,
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES to R.string.exercise_pilates,
        ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL to R.string.exercise_racquetball,
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING to R.string.exercise_rock_climbing,
        ExerciseSessionRecord.EXERCISE_TYPE_ROLLER_HOCKEY to R.string.exercise_roller_hockey,
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING to R.string.exercise_rowing,
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to R.string.exercise_rowing_machine,
        ExerciseSessionRecord.EXERCISE_TYPE_RUGBY to R.string.exercise_rugby,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to R.string.exercise_running,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to R.string.exercise_running_treadmill,
        ExerciseSessionRecord.EXERCISE_TYPE_SAILING to R.string.exercise_sailing,
        ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING to R.string.exercise_scuba_diving,
        ExerciseSessionRecord.EXERCISE_TYPE_SKATING to R.string.exercise_skating,
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING to R.string.exercise_skiing,
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING to R.string.exercise_snowboarding,
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING to R.string.exercise_snowshoeing,
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to R.string.exercise_soccer,
        ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL to R.string.exercise_softball,
        ExerciseSessionRecord.EXERCISE_TYPE_SQUASH to R.string.exercise_squash,
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING to R.string.exercise_stair_climbing,
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to R.string.exercise_stair_climbing_machine,
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to R.string.exercise_strength_training,
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING to R.string.exercise_stretching,
        ExerciseSessionRecord.EXERCISE_TYPE_SURFING to R.string.exercise_surfing,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to R.string.exercise_swimming_open_water,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to R.string.exercise_swimming_pool,
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS to R.string.exercise_table_tennis,
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to R.string.exercise_tennis,
        ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL to R.string.exercise_volleyball,
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING to R.string.exercise_walking,
        ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO to R.string.exercise_water_polo,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to R.string.exercise_weightlifting,
        ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR to R.string.exercise_wheelchair,
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA to R.string.exercise_yoga,
    )

    /** Name of a Health Connect exercise type; unknown codes fall back to "Exercise". */
    @StringRes
    fun exerciseName(type: Int): Int = exerciseNames[type] ?: R.string.exercise_generic

    /** All mapped exercise codes (for tests). */
    val knownExerciseTypes: Set<Int> get() = exerciseNames.keys

    private val originNames: Map<String, String> = mapOf(
        "com.xiaomi.wearable" to "Mi Fitness",
        "com.mi.health" to "Mi Fitness",
        "com.xiaomi.hm.health" to "Zepp Life",
        "com.huami.watch.hmwatchmanager" to "Zepp",
        "com.google.android.apps.fitness" to "Google Fit",
        "com.google.android.apps.healthdata" to "Health Connect",
        "com.android.healthconnect.controller" to "Health Connect",
        "com.sec.android.app.shealth" to "Samsung Health",
        "com.fitbit.FitbitMobile" to "Fitbit",
        "com.garmin.android.apps.connectmobile" to "Garmin Connect",
        "com.huawei.health" to "Huawei Health",
        "com.withings.wiscale2" to "Withings",
        "com.ouraring.oura" to "Oura",
        "fi.polar.polarflow" to "Polar Flow",
        "com.stt.android.suunto" to "Suunto",
        "com.strava" to "Strava",
        "com.whoop.android" to "WHOOP",
    )

    /**
     * Friendly name for a record's `dataOrigin` package. Our own records are [ownName] (the app's
     * name); unknown packages use [fallback] (e.g. the installed app label) or the package name. Null
     * when the package is blank (the UI shows "Unknown").
     */
    fun originName(
        packageName: String,
        ownPackage: String,
        ownName: String,
        fallback: (String) -> String? = { null },
    ): String? = when {
        packageName == ownPackage -> ownName
        packageName.isBlank() -> null
        else -> originNames[packageName] ?: fallback(packageName)?.takeIf { it.isNotBlank() } ?: packageName
    }
}
