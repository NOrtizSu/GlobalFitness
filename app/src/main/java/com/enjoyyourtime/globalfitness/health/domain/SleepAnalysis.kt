package com.enjoyyourtime.globalfitness.health.domain

import com.enjoyyourtime.globalfitness.domain.Status
import com.enjoyyourtime.globalfitness.health.SleepSession
import com.enjoyyourtime.globalfitness.health.SleepStage
import com.enjoyyourtime.globalfitness.health.SleepStageInterval
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Sleep of one "night" (all sessions that end on [date]). Durations in minutes. */
data class NightSleep(
    val date: LocalDate,
    /** Earliest start / latest end of the night's sessions. */
    val start: Long,
    val end: Long,
    /** Sum of session durations (time in bed). */
    val inBedMinutes: Int,
    val asleepMinutes: Int,
    val awakeMinutes: Int,
    val lightMinutes: Int,
    val deepMinutes: Int,
    val remMinutes: Int,
    /** Asleep without a specific stage ("sleeping" / unknown). */
    val unknownMinutes: Int,
    /** True when the source reported light/deep/REM stages. */
    val hasStages: Boolean,
    /** Asleep / (asleep + awake) within the recorded stages, 0–100; null without stage data. */
    val efficiencyPercent: Double?,
    /** Longest session of the night (used for the hypnogram). */
    val mainSession: SleepSession,
    val sessionCount: Int,
) {
    fun minutesOf(stage: SleepStage): Int = when (stage) {
        SleepStage.AWAKE -> awakeMinutes
        SleepStage.LIGHT -> lightMinutes
        SleepStage.DEEP -> deepMinutes
        SleepStage.REM -> remMinutes
        SleepStage.UNKNOWN -> unknownMinutes
    }
}

/** Sleep quality bands: ≥ 80 good, 60–79 fair, < 60 could be better. */
enum class SleepRating { GOOD, FAIR, POOR }

data class SleepQuality(
    val score: Int,
    val rating: SleepRating,
    val status: Status,
    val durationPoints: Int,
    /** Null when the night has no stage data. */
    val restorativePoints: Int?,
    /** (deep + REM) / asleep, 0–1; null without stage data. */
    val restorativeShare: Double?,
)

object SleepAnalysis {
    private const val MINUTE_MS = 60_000L

    /** Night attribution rule: a session that ends on local day D belongs to night D. */
    fun nightOf(session: SleepSession, zone: ZoneId): LocalDate = Instant.ofEpochMilli(session.end).atZone(zone).toLocalDate()

    /**
     * Stages sorted, clipped to the session bounds and made non-overlapping (a stage that starts
     * before the previous one ends is trimmed). A session without stages becomes one UNKNOWN
     * (asleep, unspecified) interval covering the whole session.
     */
    fun normalizedStages(session: SleepSession): List<SleepStageInterval> {
        if (session.end <= session.start) return emptyList()
        if (session.stages.isEmpty()) return listOf(SleepStageInterval(session.start, session.end, SleepStage.UNKNOWN))
        val result = ArrayList<SleepStageInterval>(session.stages.size)
        var cursor = session.start
        for (s in session.stages.sortedWith(compareBy({ it.start }, { it.end }))) {
            val start = maxOf(s.start, cursor, session.start)
            val end = minOf(s.end, session.end)
            if (end > start) {
                result += SleepStageInterval(start, end, s.stage)
                cursor = end
            }
        }
        return result
    }

    /** Summary of the sessions of one night; null when there are none. */
    fun summarize(date: LocalDate, sessions: List<SleepSession>): NightSleep? {
        val valid = sessions.filter { it.end > it.start }
        if (valid.isEmpty()) return null
        val ms = LongArray(SleepStage.entries.size)
        var hasStages = false
        var anyStageData = false
        for (session in valid) {
            if (session.stages.isNotEmpty()) anyStageData = true
            for (interval in normalizedStages(session)) {
                ms[interval.stage.ordinal] += interval.end - interval.start
                if (interval.stage == SleepStage.LIGHT || interval.stage == SleepStage.DEEP || interval.stage == SleepStage.REM) {
                    hasStages = true
                }
            }
        }
        fun min(stage: SleepStage) = (ms[stage.ordinal] / MINUTE_MS).toInt()
        val awakeMs = ms[SleepStage.AWAKE.ordinal]
        val asleepMs = ms.sum() - awakeMs
        val inBedMs = valid.sumOf { it.end - it.start }
        return NightSleep(
            date = date,
            start = valid.minOf { it.start },
            end = valid.maxOf { it.end },
            inBedMinutes = (inBedMs / MINUTE_MS).toInt(),
            asleepMinutes = (asleepMs / MINUTE_MS).toInt(),
            awakeMinutes = min(SleepStage.AWAKE),
            lightMinutes = min(SleepStage.LIGHT),
            deepMinutes = min(SleepStage.DEEP),
            remMinutes = min(SleepStage.REM),
            unknownMinutes = min(SleepStage.UNKNOWN),
            hasStages = hasStages,
            efficiencyPercent = if (anyStageData && asleepMs + awakeMs > 0) asleepMs * 100.0 / (asleepMs + awakeMs) else null,
            mainSession = valid.maxBy { it.end - it.start },
            sessionCount = valid.size,
        )
    }

    /** Groups sessions by night (end date) and summarizes each, sorted by date. */
    fun nights(sessions: List<SleepSession>, zone: ZoneId): List<NightSleep> =
        sessions.groupBy { nightOf(it, zone) }
            .mapNotNull { (date, list) -> summarize(date, list) }
            .sortedBy { it.date }

    /**
     * Simple, transparent sleep-quality indicator (an orientation, not a medical assessment):
     *
     * - Duration, up to 60 points: 60 when asleep 7–9 h. Below 7 h it loses 20 points per hour
     *   (0 at 4 h or less); above 9 h it loses 20 points per hour (0 at 12 h or more).
     * - Restorative sleep, up to 40 points: share of deep + REM over the time asleep.
     *   A share of 40 % or more scores 40; below that it is proportional (20 % → 20 points).
     * - Without stage data only the duration counts, rescaled to 100.
     *
     * Ratings: ≥ 80 good, 60–79 fair, < 60 poor ("could be better").
     */
    fun quality(night: NightSleep): SleepQuality {
        val hours = night.asleepMinutes / 60.0
        val durationFraction = when {
            hours < TARGET_MIN_HOURS -> ((hours - 4.0) / (TARGET_MIN_HOURS - 4.0)).coerceIn(0.0, 1.0)
            hours > TARGET_MAX_HOURS -> (1.0 - (hours - TARGET_MAX_HOURS) / 3.0).coerceIn(0.0, 1.0)
            else -> 1.0
        }
        val durationPoints = (durationFraction * DURATION_POINTS).roundToInt()
        val share = if (night.hasStages && night.asleepMinutes > 0) {
            (night.deepMinutes + night.remMinutes).toDouble() / night.asleepMinutes
        } else {
            null
        }
        val restorativePoints = share?.let { (it / RESTORATIVE_TARGET_SHARE).coerceAtMost(1.0) * RESTORATIVE_POINTS }?.roundToInt()
        val score = if (restorativePoints != null) {
            durationPoints + restorativePoints
        } else {
            (durationFraction * 100).roundToInt()
        }
        val (rating, status) = when {
            score >= 80 -> SleepRating.GOOD to Status.GOOD
            score >= 60 -> SleepRating.FAIR to Status.WARNING
            else -> SleepRating.POOR to Status.SERIOUS
        }
        return SleepQuality(score, rating, status, durationPoints, restorativePoints, share)
    }

    const val TARGET_MIN_HOURS = 7.0
    const val TARGET_MAX_HOURS = 9.0
    private const val DURATION_POINTS = 60
    private const val RESTORATIVE_POINTS = 40
    private const val RESTORATIVE_TARGET_SHARE = 0.40
}
