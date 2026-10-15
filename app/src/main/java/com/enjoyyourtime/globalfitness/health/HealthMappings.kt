package com.enjoyyourtime.globalfitness.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord

/** Pure mappings from Health Connect codes to app concepts (Spanish labels). */
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

    private val exerciseNames: Map<Int, String> = mapOf(
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "Otro entrenamiento",
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to "Bádminton",
        ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL to "Béisbol",
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL to "Baloncesto",
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "Ciclismo",
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "Bicicleta estática",
        ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP to "Boot camp",
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING to "Boxeo",
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS to "Calistenia",
        ExerciseSessionRecord.EXERCISE_TYPE_CRICKET to "Críquet",
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING to "Baile",
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to "Elíptica",
        ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS to "Clase de ejercicio",
        ExerciseSessionRecord.EXERCISE_TYPE_FENCING to "Esgrima",
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN to "Fútbol americano",
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN to "Fútbol australiano",
        ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC to "Frisbee",
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF to "Golf",
        ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING to "Respiración guiada",
        ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS to "Gimnasia",
        ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL to "Balonmano",
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "HIIT",
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "Senderismo",
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY to "Hockey sobre hielo",
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING to "Patinaje sobre hielo",
        ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS to "Artes marciales",
        ExerciseSessionRecord.EXERCISE_TYPE_PADDLING to "Remo en kayak",
        ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING to "Parapente",
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES to "Pilates",
        ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL to "Ráquetbol",
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING to "Escalada",
        ExerciseSessionRecord.EXERCISE_TYPE_ROLLER_HOCKEY to "Hockey sobre patines",
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING to "Remo",
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to "Máquina de remo",
        ExerciseSessionRecord.EXERCISE_TYPE_RUGBY to "Rugby",
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "Carrera",
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "Cinta de correr",
        ExerciseSessionRecord.EXERCISE_TYPE_SAILING to "Vela",
        ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING to "Submarinismo",
        ExerciseSessionRecord.EXERCISE_TYPE_SKATING to "Patinaje",
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING to "Esquí",
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING to "Snowboard",
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING to "Raquetas de nieve",
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to "Fútbol",
        ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL to "Sóftbol",
        ExerciseSessionRecord.EXERCISE_TYPE_SQUASH to "Squash",
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING to "Subir escaleras",
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to "Máquina de escaleras",
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "Entrenamiento de fuerza",
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING to "Estiramientos",
        ExerciseSessionRecord.EXERCISE_TYPE_SURFING to "Surf",
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "Natación en aguas abiertas",
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "Natación en piscina",
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS to "Tenis de mesa",
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to "Tenis",
        ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL to "Voleibol",
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "Caminata",
        ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO to "Waterpolo",
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "Halterofilia",
        ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR to "Silla de ruedas",
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "Yoga",
    )

    /** Spanish name of a Health Connect exercise type; unknown codes fall back to "Ejercicio". */
    fun exerciseName(type: Int): String = exerciseNames[type] ?: "Ejercicio"

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
        "com.huawei.health" to "Huawei Salud",
        "com.withings.wiscale2" to "Withings",
        "com.ouraring.oura" to "Oura",
        "fi.polar.polarflow" to "Polar Flow",
        "com.stt.android.suunto" to "Suunto",
        "com.strava" to "Strava",
        "com.whoop.android" to "WHOOP",
    )

    /**
     * Friendly name for a record's `dataOrigin` package. Our own records are "GlobalFitness";
     * unknown packages use [fallback] (e.g. the installed app label) or the package name.
     */
    fun originName(packageName: String, ownPackage: String, fallback: (String) -> String? = { null }): String = when {
        packageName == ownPackage -> "GlobalFitness"
        packageName.isBlank() -> "Desconocido"
        else -> originNames[packageName] ?: fallback(packageName)?.takeIf { it.isNotBlank() } ?: packageName
    }
}
