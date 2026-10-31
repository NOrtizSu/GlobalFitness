package com.enjoyyourtime.globalfitness.health

import com.enjoyyourtime.globalfitness.training.domain.WorkoutTime
import com.enjoyyourtime.globalfitness.training.model.WorkoutPause
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
    /** Time inside the session marked as PAUSE segments (paused, not exercising). */
    val pausedMs: Long = 0,
) {
    /** How long it was exercised: the session's span minus its pauses. */
    val activeMs: Long get() = (end - start - pausedMs).coerceAtLeast(0)

    companion object {
        /** Time [pauses] (start to end, epoch ms) cover inside [start, end], each moment once. */
        fun pausedMs(pauses: List<Pair<Long, Long>>, start: Long, end: Long): Long =
            WorkoutTime.pausedMs(start, end, WorkoutTime.normalize(pauses.map { (from, to) -> WorkoutPause(from, to) }, start, end))
    }
}
