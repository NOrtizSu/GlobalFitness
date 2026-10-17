package com.enjoyyourtime.globalfitness.health

import java.time.LocalDate

/** Daily totals from the Health Connect aggregate API (de-duplicated across sources). */
data class DailyActivity(
    val date: LocalDate,
    val steps: Long?,
    val distanceM: Double?,
    val activeKcal: Double?,
    val totalKcal: Double?,
)

data class HrSample(val time: Long, val bpm: Int)

/** Instantaneous value with its source app package (resting HR, HRV, SpO2, respiration...). */
data class TimedValue(val time: Long, val value: Double, val origin: String)

/** Kinds stored in the generic `points` cache table. */
enum class PointKind(val code: String) {
    RESTING_HR("resting_hr"),
    HRV("hrv"),
    SPO2("spo2"),
    RESPIRATORY_RATE("resp"),
    WEIGHT("weight"),
    BODY_FAT("body_fat"),
}

/** Sleep stages; [UNKNOWN] is asleep without a specific stage. Names are mapped to resources by [labelRes]. */
enum class SleepStage { AWAKE, LIGHT, DEEP, REM, UNKNOWN }

data class SleepStageInterval(val start: Long, val end: Long, val stage: SleepStage)

data class SleepSession(
    val id: String,
    val start: Long,
    val end: Long,
    val title: String?,
    val origin: String,
    val stages: List<SleepStageInterval>,
)

data class ExerciseSession(
    val id: String,
    val start: Long,
    val end: Long,
    /** Health Connect `ExerciseSessionRecord.EXERCISE_TYPE_*`. */
    val type: Int,
    val title: String?,
    val activeKcal: Double?,
    val distanceM: Double?,
    val origin: String,
)
