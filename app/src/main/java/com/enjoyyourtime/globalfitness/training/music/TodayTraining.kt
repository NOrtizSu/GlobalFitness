package com.enjoyyourtime.globalfitness.training.music

import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Workout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What kind of day today is for the music (see [TodayTraining.classify]). */
enum class TrainingDay {
    /** A workout today, mostly strength. */
    STRENGTH,

    /** A strength workout today that works mostly the legs. */
    LEGS,

    /** A workout today, mostly cardio. */
    CARDIO,

    /** No workout yet today, and nothing says it is a rest day. */
    TRAINING_DAY,

    /** No workout today, after a hard one yesterday. */
    RECOVERY,

    /** No workout today, a weekday the user usually rests. */
    REST,
}

/** Tells what today's training is from the workouts, by a few rules a user can follow. */
object TodayTraining {
    /** Working sets or minutes of training in a day that make the next one a recovery day. */
    const val HARD_DAY_SETS = 20
    const val HARD_DAY_MINUTES = 90

    /** The user's pattern: days trained in the last [PATTERN_DAYS] that make them a regular. */
    const val PATTERN_DAYS = 28L
    const val REGULAR_DAYS = 6

    /** A workout in progress with nothing ticked for this long was left open: the user is not at it. */
    const val LIVE_HOURS = 12L

    private val LEGS = setOf(Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES, Muscle.CALVES, Muscle.ADDUCTORS)

    /** Whether the user is still at [workout]: in progress, started or a set ticked in the last [LIVE_HOURS]. */
    fun isLive(workout: Workout, now: Long): Boolean = workout.inProgress && now - workout.lastActivityAt < LIVE_HOURS * 3_600_000L

    /** The workout in progress the user is still at, if any (see [isLive]). */
    fun liveWorkout(workouts: List<Workout>, now: Long): Workout? = workouts.lastOrNull { it.inProgress }?.takeIf { isLive(it, now) }

    /**
     * The day the music is chosen for: the start day of a live workout (one going on past midnight
     * keeps its playlists), else the calendar day of [now].
     */
    fun musicDate(workouts: List<Workout>, now: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(liveWorkout(workouts, now)?.startedAt ?: now).atZone(zone).toLocalDate()

    /**
     * - A live workout in progress ([isLive]) and [today]'s finished ones decide: CARDIO when more
     *   than half of their exercises are cardio, else LEGS when more than half of the strength
     *   exercises work the legs, else STRENGTH (a workout just started, still empty: TRAINING_DAY).
     *   The exercises of a workout in progress count before their sets are ticked, so starting a
     *   routine sets the day at once.
     * - No workout today: RECOVERY after a hard day yesterday ([HARD_DAY_SETS] working sets, or
     *   [HARD_DAY_MINUTES] minutes from the start to the last ticked set); REST for a regular
     *   ([REGULAR_DAYS] days trained in the last four weeks) who trained on none of the last four
     *   of today's weekday; otherwise TRAINING_DAY.
     */
    fun classify(workouts: List<Workout>, today: LocalDate, zone: ZoneId, now: Long, exercise: (String) -> Exercise): TrainingDay {
        val active = liveWorkout(workouts, now)
        val finished = workouts.filter { !it.inProgress && it.doneSets > 0 }
        val todays = finished.filter { it.date(zone) == today }
        if (active != null || todays.isNotEmpty()) {
            val entries = active?.entries.orEmpty() + todays.flatMap { w -> w.entries.filter { e -> e.sets.any { it.done } } }
            if (entries.isEmpty()) return TrainingDay.TRAINING_DAY
            val (cardio, strength) = entries.map { exercise(it.exerciseId) }.partition { it.kind == ExerciseKind.CARDIO }
            return when {
                cardio.size > strength.size -> TrainingDay.CARDIO
                strength.count { ex -> ex.primary.any { it in LEGS } } * 2 > strength.size -> TrainingDay.LEGS
                else -> TrainingDay.STRENGTH
            }
        }
        val yesterday = finished.filter { it.date(zone) == today.minusDays(1) }
        val sets = yesterday.sumOf { w -> w.entries.sumOf { e -> e.sets.count { it.isWorking } } }
        // Up to the last ticked set: a workout closed hours later (left open) was not that long.
        val minutes = yesterday.sumOf { w -> (w.lastActivityAt - w.startedAt).coerceAtLeast(0L) / 60_000L }
        if (sets >= HARD_DAY_SETS || minutes >= HARD_DAY_MINUTES) return TrainingDay.RECOVERY
        val from = today.minusDays(PATTERN_DAYS)
        val days = finished.map { it.date(zone) }.filterTo(HashSet()) { it >= from && it < today }
        if (days.size >= REGULAR_DAYS && days.none { it.dayOfWeek == today.dayOfWeek }) return TrainingDay.REST
        return TrainingDay.TRAINING_DAY
    }
}
