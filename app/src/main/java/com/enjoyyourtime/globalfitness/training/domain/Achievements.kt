package com.enjoyyourtime.globalfitness.training.domain

import com.enjoyyourtime.globalfitness.data.Sex
import com.enjoyyourtime.globalfitness.domain.Classifier
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Sections of the achievements page. */
enum class AchievementCategory { TRAINING, STRENGTH, BODY }

/**
 * Every achievement, with the value each of its levels needs (easiest first). Nothing is stored:
 * [Achievements.evaluate] derives them from the workouts and the weighings each time, so a restored
 * backup or an edited workout always gives the same answer. [key] is stored with the levels already
 * celebrated (`"<key>:<level>"`, and `"<key>:<level>@<target in tenths of a kg>"` for the weight
 * goals, so a new target is celebrated again): never rename one, and only append levels.
 *
 * Units of the thresholds: counts for [WORKOUTS], [WEEK_STREAK] (weeks), [BALANCED_WEEKS],
 * [EXERCISE_VARIETY], [RECORDS] and [PULL_UPS] (repetitions); km for [CARDIO_DISTANCE]; kg for
 * [VOLUME], [GOAL_PROGRESS] and [MUSCLE_MASS]; times the body weight for the four lifts; percentage
 * points for [BODY_FAT]. [GOAL_REACHED] has one level whose value is the distance to the target.
 */
enum class Achievement(
    val key: String,
    val category: AchievementCategory,
    private val male: List<Double>,
    /** Strength standards differ between women and men; other achievements use the same values. */
    private val female: List<Double> = male,
) {
    WORKOUTS("workouts", AchievementCategory.TRAINING, listOf(1.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0)),
    WEEK_STREAK("week_streak", AchievementCategory.TRAINING, listOf(2.0, 4.0, 8.0, 12.0, 26.0, 52.0)),
    BALANCED_WEEKS("balanced_weeks", AchievementCategory.TRAINING, listOf(1.0, 4.0, 12.0, 26.0)),
    EXERCISE_VARIETY("variety", AchievementCategory.TRAINING, listOf(5.0, 10.0, 25.0, 50.0)),
    CARDIO_DISTANCE("cardio_km", AchievementCategory.TRAINING, listOf(10.0, 50.0, 100.0, 250.0, 500.0, 1000.0)),
    VOLUME("volume", AchievementCategory.STRENGTH, listOf(1_000.0, 10_000.0, 50_000.0, 100_000.0, 500_000.0, 1_000_000.0)),
    RECORDS("records", AchievementCategory.STRENGTH, listOf(1.0, 10.0, 50.0, 100.0, 250.0)),
    BENCH_PRESS("bench_bw", AchievementCategory.STRENGTH, listOf(0.75, 1.0, 1.25, 1.5), listOf(0.5, 0.75, 1.0, 1.25)),
    SQUAT("squat_bw", AchievementCategory.STRENGTH, listOf(1.0, 1.5, 2.0, 2.5), listOf(0.75, 1.0, 1.5, 2.0)),
    DEADLIFT("deadlift_bw", AchievementCategory.STRENGTH, listOf(1.25, 1.5, 2.0, 2.5), listOf(1.0, 1.25, 1.5, 2.0)),
    OVERHEAD_PRESS("ohp_bw", AchievementCategory.STRENGTH, listOf(0.5, 0.75, 1.0), listOf(0.4, 0.5, 0.75)),
    PULL_UPS("pull_ups", AchievementCategory.STRENGTH, listOf(1.0, 5.0, 10.0, 15.0, 20.0)),
    GOAL_REACHED("goal", AchievementCategory.BODY, listOf(0.0)),
    GOAL_PROGRESS("goal_progress", AchievementCategory.BODY, listOf(1.0, 3.0, 5.0, 10.0)),
    BODY_FAT("fat_down", AchievementCategory.BODY, listOf(1.0, 3.0, 5.0)),
    MUSCLE_MASS("muscle_up", AchievementCategory.BODY, listOf(1.0, 2.0, 3.0)),
    ;

    val levels: Int get() = male.size

    fun thresholds(sex: Sex): List<Double> = if (sex == Sex.FEMALE) female else male

    /** The four lifts compared with the body weight. */
    val isLift: Boolean get() = this == BENCH_PRESS || this == SQUAT || this == DEADLIFT || this == OVERHEAD_PRESS

    /** The levels belong to the profile's target weight (their keys name it). */
    val isWeightGoal: Boolean get() = this == GOAL_REACHED || this == GOAL_PROGRESS

    companion object {
        fun fromKey(key: String): Achievement? = entries.firstOrNull { it.key == key }
    }
}

/** Why an achievement cannot be measured yet (the page says what to do). */
enum class AchievementBlocker {
    /** The lifts compare with the body weight: the user never weighed in. */
    NEEDS_BODYWEIGHT,

    /**
     * Body goals need weighings: [Achievements.MIN_AVERAGE_WEIGHINGS] within a week to know where
     * they start (one weighing is no average).
     */
    NEEDS_WEIGHINGS,

    /** The profile has no target weight. */
    NEEDS_TARGET,

    /** The target is (within half a kilo) the starting weight: there is no distance to cover. */
    TARGET_IS_START,

    /** No weighing has a body fat / muscle estimate (manual weights, scales without impedance). */
    NEEDS_COMPOSITION,
}

/**
 * Level [level] (1-based) was reached at [at] (the time of the set, workout, weighing or week end
 * that crossed it). [touchedAt] is the latest change made on this phone of the data that led to it
 * (workouts' `updatedAt`, weighing times), [Long.MIN_VALUE] when it came only from the cloud backup
 * or an import: the celebration uses it to tell news from history arriving late (a restore,
 * another phone's workouts).
 */
data class TierUnlock(val level: Int, val at: Long, val touchedAt: Long)

/** One achievement for the user: the levels reached and where they stand towards the next one. */
data class AchievementState(
    val achievement: Achievement,
    /** What each level needs (see [Achievement] for the units), easiest first. */
    val thresholds: List<Double>,
    /** Levels reached, in order (always the first ones). */
    val unlocked: List<TierUnlock> = emptyList(),
    /** Current value in the thresholds' unit; null while [blocker] prevents measuring it. */
    val progress: Double? = null,
    val blocker: AchievementBlocker? = null,
    /**
     * Levels on offer (the first ones). Weight milestones beyond the distance to the target are not
     * (losing 10 kg is no goal for someone who wants to lose 4).
     */
    val available: Int = thresholds.size,
    /** Weight goals: the target is below the starting weight. */
    val losing: Boolean = false,
    /** Weight goals: the profile's target. */
    val targetKg: Double? = null,
) {
    val level: Int get() = unlocked.size

    /** What the next level needs, or null when every level on offer is reached. */
    val next: Double? get() = if (level < available) thresholds.getOrNull(level) else null

    /** Share of the way to [next] (0..1); null without a next level or a measurable value. */
    val fraction: Double?
        get() {
            val target = next ?: return null
            val value = progress ?: return null
            return if (target <= 0.0) null else (value / target).coerceIn(0.0, 1.0)
        }

    /** The stored key of [level] (see [Achievements.key]). */
    fun key(level: Int): String = Achievements.key(achievement, level, targetKg)
}

/** Every achievement, in display order. */
data class AchievementBoard(val states: List<AchievementState>) {
    val earned: Int get() = states.sumOf { it.level }
    val total: Int get() = states.sumOf { it.available }

    fun state(achievement: Achievement): AchievementState? = states.firstOrNull { it.achievement == achievement }

    /** The level not reached yet that is closest to being reached (for the Entreno tab's card). */
    val closest: AchievementState?
        get() = states.filter { it.blocker == null && it.next != null && (it.fraction ?: 0.0) > 0.0 }.maxByOrNull { it.fraction ?: 0.0 }

    /** The last level reached, if any. */
    val latest: Pair<AchievementState, TierUnlock>?
        get() = states.flatMap { s -> s.unlocked.map { s to it } }.maxByOrNull { it.second.at }
}

/**
 * A weighing with its composition estimate (from the scale or the formulas), for the body goals.
 * [imported]: it came with a CSV import or the cloud backup, it was not weighed on this phone.
 */
data class BodySample(
    val at: Long,
    val weightKg: Double,
    val fatPercent: Double? = null,
    val muscleKg: Double? = null,
    val imported: Boolean = false,
)

/** What the achievements are derived from. */
data class AchievementInput(
    val workouts: List<Workout>,
    val exercise: (String) -> Exercise,
    /** Weighings, any order. Their weights are also the body weight of the lifts and bodyweight sets. */
    val body: List<BodySample>,
    val sex: Sex,
    val ageYears: Int,
    val targetWeightKg: Double?,
    val zone: ZoneId,
    /** First day of the calendar week (the locale's), for streaks and balanced weeks. */
    val firstDayOfWeek: DayOfWeek,
    val now: Long,
    /** When [targetWeightKg] was set ([GoalAnchor]): the weight goals count from then. Null: from the first weighing. */
    val targetSetAt: Long? = null,
    /**
     * Workouts as they arrived from the cloud backup (id → `updatedAt`): what they reach is another
     * phone's news (celebrated there), so it is filed silently here.
     */
    val remoteVersions: Map<String, Long> = emptyMap(),
)

/**
 * Rules of every achievement, applied to the user's whole history. Sets count when they are working
 * sets (done, not warm-ups), including those of the workout in progress; "finished workouts" are
 * those that ended with at least one completed set. Loads come from [LoadModel] (body weight for
 * bodyweight exercises, body weight minus assistance on assisted machines), so the volume and the
 * records match the training screens.
 */
object Achievements {
    /** Barbell lifts compared with the body weight. Smith machine, machines and dumbbells do not count (a guided path or two loads are not comparable with the standards). */
    val BENCH_IDS = setOf("barbell_bench_press")
    val SQUAT_IDS = setOf("barbell_back_squat", "barbell_front_squat")
    val DEADLIFT_IDS = setOf("barbell_deadlift", "sumo_deadlift")
    val OVERHEAD_PRESS_IDS = setOf("barbell_overhead_press")

    /** Pull-ups: the free ones (with or without extra load), or the assisted machines at zero assistance. */
    val PULL_UP_IDS = setOf("pull_up", "chin_up")
    val ASSISTED_PULL_UP_IDS = setOf("assisted_pull_up_machine", "assisted_chin_up_machine")

    /** Muscles a balanced week must have within their weekly set range (the small ones get indirect work). */
    val BALANCE_MUSCLES = listOf(
        Muscle.CHEST, Muscle.SHOULDERS, Muscle.LATS, Muscle.BICEPS, Muscle.TRICEPS, Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES,
    )

    /** A week keeps the streak with this many finished workouts. */
    const val STREAK_WORKOUTS = 2

    /** Body goals compare 7-day averages, so one heavy or light weighing changes nothing. */
    const val AVERAGE_DAYS = 7

    /** Weighings a 7-day average needs: one alone is just that day's noise. */
    const val MIN_AVERAGE_WEIGHINGS = 2

    /** The target counts as reached this close (as the weight chart's projection). */
    const val TARGET_TOLERANCE_KG = 0.1

    /** A target closer than this to the starting weight is maintenance, not a goal to celebrate. */
    const val MIN_GOAL_KG = 0.5

    private const val EPS = 1e-9
    private val AVERAGE_MS = Duration.ofDays(AVERAGE_DAYS.toLong()).toMillis()

    /**
     * The stored key of a level. The weight goals' keys name their target ([targetKg]), so reaching
     * a new target is celebrated even when an old one was.
     */
    fun key(achievement: Achievement, level: Int, targetKg: Double? = null): String =
        if (achievement.isWeightGoal && targetKg != null) "${achievement.key}:$level@${tenthsKg(targetKg)}" else "${achievement.key}:$level"

    /** A target weight in tenths of a kilo, as keys and [GoalAnchor] compare it (the profile keeps one decimal). */
    fun tenthsKg(kg: Double): Long = (kg * 10).roundToLong()

    fun evaluate(input: AchievementInput): AchievementBoard {
        val body = input.body.filter { it.weightKg > 0.0 }.sortedBy { it.at }
        val timeline = BodyweightTimeline(body.map { it.at to it.weightKg })
        val training = TrainingPass(input, timeline).run()
        val states = Achievement.entries.map { a ->
            when (a) {
                Achievement.GOAL_REACHED, Achievement.GOAL_PROGRESS -> goal(a, body, input)
                Achievement.BODY_FAT -> composition(
                    a, input,
                    body.mapNotNull { s -> s.fatPercent?.let { Reading(s.at, it, s.imported) } },
                    // Points lost count down to the healthy minimum: getting leaner than that is no achievement.
                    gain = { start, value -> start - max(value, Classifier.fatThresholds(input.sex, input.ageYears)[0]) },
                )
                Achievement.MUSCLE_MASS -> composition(
                    a, input,
                    body.mapNotNull { s -> s.muscleKg?.let { Reading(s.at, it, s.imported) } },
                    gain = { start, value -> value - start },
                )
                else -> training.getValue(a)
            }
        }
        return AchievementBoard(states)
    }

    // ---- training ---------------------------------------------------------------------------

    /** Counts levels as values grow, in time order. */
    private class Ladder(val thresholds: List<Double>, val limit: Int = thresholds.size) {
        val unlocked = ArrayList<TierUnlock>()

        fun offer(value: Double, at: Long, touchedAt: Long) {
            while (unlocked.size < limit && value >= thresholds[unlocked.size] - EPS) {
                unlocked += TierUnlock(unlocked.size + 1, at, touchedAt)
            }
        }
    }

    /**
     * The running bests of one exercise: exactly what [ExerciseHistory.newRecords] compares a set
     * with (every earlier working set), kept as maxima so the whole history is replayed in one pass.
     */
    private class RecordBests {
        private var any = false
        private var bestE1rm: Double? = null
        private var heaviest: Double? = null
        private var leastWeightField: Double? = null
        private var mostBodyweightReps: Int? = null
        private var longest: Int? = null

        fun beats(s: WorkoutSet, kind: ExerciseKind, loadKg: Double?): Boolean {
            if (!any) return false
            val e1 = TrainingMath.e1rm(loadKg, s.reps)
            val best = bestE1rm
            if (e1 != null && best != null && e1 > best + RECORD_EPS) return true
            if (kind.isAssisted) {
                val assistance = s.weightKg
                val least = leastWeightField
                if ((s.reps ?: 0) > 0 && assistance != null && least != null && assistance < least - RECORD_EPS) return true
            } else {
                val top = heaviest
                if (kind.usesLoad && loadKg != null && top != null && loadKg > top + RECORD_EPS) return true
            }
            if (kind == ExerciseKind.BODYWEIGHT_REPS && (s.weightKg ?: 0.0) <= 0.0) {
                val reps = s.reps
                val most = mostBodyweightReps
                if (reps != null && most != null && reps > most) return true
            }
            if (kind == ExerciseKind.DURATION) {
                val d = s.durationSec
                val most = longest
                if (d != null && most != null && d > most) return true
            }
            return false
        }

        fun add(s: WorkoutSet, loadKg: Double?) {
            any = true
            TrainingMath.e1rm(loadKg, s.reps)?.let { bestE1rm = max(bestE1rm ?: it, it) }
            loadKg?.let { heaviest = max(heaviest ?: it, it) }
            s.weightKg?.let { leastWeightField = min(leastWeightField ?: it, it) }
            if ((s.weightKg ?: 0.0) <= 0.0) s.reps?.let { mostBodyweightReps = max(mostBodyweightReps ?: it, it) }
            s.durationSec?.let { longest = max(longest ?: it, it) }
        }

        private companion object {
            /** The same tolerance as [ExerciseHistory.newRecords]. */
            const val RECORD_EPS = 1e-6
        }
    }

    /** One pass over every working set, oldest workout first (the order of [HISTORY_ORDER]). */
    private class TrainingPass(private val input: AchievementInput, private val timeline: BodyweightTimeline) {
        private val sex = input.sex
        private val load = LoadModel(timeline)
        private fun ladder(a: Achievement) = Ladder(a.thresholds(sex))

        private val workouts = ladder(Achievement.WORKOUTS)
        private val volume = ladder(Achievement.VOLUME)
        private val records = ladder(Achievement.RECORDS)
        private val variety = ladder(Achievement.EXERCISE_VARIETY)
        private val cardio = ladder(Achievement.CARDIO_DISTANCE)
        private val pullUps = ladder(Achievement.PULL_UPS)
        private val lifts = LIFTS.associateWith { ladder(it) }
        private val bestRatio = HashMap<Achievement, Double>()

        private var finished = 0
        private var volumeKg = 0.0
        private var recordSets = 0
        private val seen = HashSet<String>()
        private var cardioKm = 0.0
        private var mostPullUps = 0
        private val bests = HashMap<String, RecordBests>()

        /** Finished workouts per calendar week: (end time, change stamp) of each, in order. */
        private val finishedByWeek = sortedMapOf<LocalDate, MutableList<Pair<Long, Long>>>()

        /** Hard sets per muscle per calendar week of the set, and the latest change stamp of its sets. */
        private val musclesByWeek = sortedMapOf<LocalDate, HashMap<Muscle, Double>>()
        private val touchedByWeek = HashMap<LocalDate, Long>()

        /**
         * Change stamps ([TierUnlock.touchedAt]): a workout's own is its `updatedAt`, or
         * [Long.MIN_VALUE] when that version came from the cloud backup. The running totals carry the
         * latest own change so far, so a workout logged today for last week makes the levels it
         * pushes later workouts over news; a level crossed inside another phone's workout never is.
         * The bests (lifts, pull-ups) are crossed by one set alone: they take its workout's own stamp.
         */
        fun run(): Map<Achievement, AchievementState> {
            var touched = Long.MIN_VALUE
            for (w in input.workouts.sortedWith(HISTORY_ORDER)) {
                val own = if (input.remoteVersions[w.id] == w.updatedAt) Long.MIN_VALUE else w.updatedAt
                touched = max(touched, own)
                val stamp = if (own == Long.MIN_VALUE) Long.MIN_VALUE else touched
                for (e in w.entries) {
                    val ex = input.exercise(e.exerciseId)
                    val exerciseBests = bests.getOrPut(e.exerciseId) { RecordBests() }
                    for (s in e.sets) {
                        if (!s.isWorking) continue
                        val at = s.doneAt ?: w.startedAt
                        set(e.exerciseId, ex, s, at, stamp, own, exerciseBests)
                    }
                }
                if (!w.inProgress && w.doneSets > 0) {
                    finished++
                    val end = w.endedAt ?: w.startedAt
                    workouts.offer(finished.toDouble(), end, stamp)
                    finishedByWeek.getOrPut(weekOf(w.startedAt)) { ArrayList() } += end to stamp
                }
            }
            return buildMap {
                put(Achievement.WORKOUTS, state(Achievement.WORKOUTS, workouts, finished.toDouble()))
                put(Achievement.VOLUME, state(Achievement.VOLUME, volume, volumeKg))
                put(Achievement.RECORDS, state(Achievement.RECORDS, records, recordSets.toDouble()))
                put(Achievement.EXERCISE_VARIETY, state(Achievement.EXERCISE_VARIETY, variety, seen.size.toDouble()))
                put(Achievement.CARDIO_DISTANCE, state(Achievement.CARDIO_DISTANCE, cardio, cardioKm))
                put(Achievement.PULL_UPS, state(Achievement.PULL_UPS, pullUps, mostPullUps.toDouble()))
                for ((a, l) in lifts) {
                    val blocked = timeline.isEmpty
                    put(a, state(a, l, if (blocked) null else bestRatio[a] ?: 0.0, if (blocked) AchievementBlocker.NEEDS_BODYWEIGHT else null))
                }
                put(Achievement.WEEK_STREAK, streak())
                put(Achievement.BALANCED_WEEKS, balancedWeeks())
            }
        }

        private fun set(id: String, ex: Exercise, s: WorkoutSet, at: Long, touched: Long, own: Long, exerciseBests: RecordBests) {
            val kg = load.loadKg(ex, s, at)
            val reps = (s.reps ?: 0).coerceAtLeast(0)
            if (kg != null && reps > 0) {
                volumeKg += kg * reps
                volume.offer(volumeKg, at, touched)
            }
            if (exerciseBests.beats(s, ex.kind, kg)) {
                recordSets++
                records.offer(recordSets.toDouble(), at, touched)
            }
            exerciseBests.add(s, kg)
            if (seen.add(id)) variety.offer(seen.size.toDouble(), at, touched)
            if (ex.kind == ExerciseKind.CARDIO) {
                val m = s.distanceM ?: 0.0
                if (m > 0.0) {
                    cardioKm += m / 1000.0
                    cardio.offer(cardioKm, at, touched)
                }
            }
            liftOf(id)?.let { a ->
                val e1 = TrainingMath.e1rm(kg, s.reps)
                val bw = timeline.at(at)
                if (ex.kind == ExerciseKind.WEIGHT_REPS && e1 != null && bw != null && bw > 0.0) {
                    val ratio = e1 / bw
                    if (ratio > (bestRatio[a] ?: 0.0)) bestRatio[a] = ratio
                    lifts.getValue(a).offer(ratio, at, own)
                }
            }
            val unassisted = id in PULL_UP_IDS || (id in ASSISTED_PULL_UP_IDS && s.weightKg != null && s.weightKg <= 0.0)
            if (unassisted && reps > 0) {
                mostPullUps = max(mostPullUps, reps)
                pullUps.offer(mostPullUps.toDouble(), at, own)
            }
            if (TrainingMath.countsForMuscles(ex.kind) && (ex.primary.isNotEmpty() || ex.secondary.isNotEmpty())) {
                val week = weekOf(at)
                val sets = musclesByWeek.getOrPut(week) { HashMap() }
                for (m in ex.primary) sets[m] = (sets[m] ?: 0.0) + TrainingMath.PRIMARY_SHARE
                for (m in ex.secondary - ex.primary) sets[m] = (sets[m] ?: 0.0) + TrainingMath.SECONDARY_SHARE
                touchedByWeek[week] = max(touched, touchedByWeek[week] ?: Long.MIN_VALUE)
            }
        }

        private fun liftOf(id: String): Achievement? = when (id) {
            in BENCH_IDS -> Achievement.BENCH_PRESS
            in SQUAT_IDS -> Achievement.SQUAT
            in DEADLIFT_IDS -> Achievement.DEADLIFT
            in OVERHEAD_PRESS_IDS -> Achievement.OVERHEAD_PRESS
            else -> null
        }

        private fun weekOf(ms: Long): LocalDate =
            Instant.ofEpochMilli(ms).atZone(input.zone).toLocalDate().with(TemporalAdjusters.previousOrSame(input.firstDayOfWeek))

        /**
         * Consecutive calendar weeks with at least [STREAK_WORKOUTS] finished workouts (by the day each
         * started). A level is reached when the week's second workout ends. The progress is the
         * current streak: it ends this week, or last week while this one is still going on.
         */
        private fun streak(): AchievementState {
            val l = ladder(Achievement.WEEK_STREAK)
            val qualifying = finishedByWeek.filterValues { it.size >= STREAK_WORKOUTS }.keys
            var run = 0
            var previous: LocalDate? = null
            for (week in qualifying) {
                run = if (previous != null && previous.plusWeeks(1) == week) run + 1 else 1
                previous = week
                val (at, touched) = finishedByWeek.getValue(week)[STREAK_WORKOUTS - 1]
                l.offer(run.toDouble(), at, touched)
            }
            val thisWeek = weekOf(input.now)
            var end = if (thisWeek in qualifying) thisWeek else thisWeek.minusWeeks(1)
            var current = 0
            while (end in qualifying) {
                current++
                end = end.minusWeeks(1)
            }
            return state(Achievement.WEEK_STREAK, l, current.toDouble())
        }

        /**
         * Calendar weeks in which every muscle of [BALANCE_MUSCLES] got a number of hard sets within
         * its [Muscle.weeklySets] range (not below, not above). Only weeks that are over count: one
         * in progress can still go above a range. A level is reached as its week ends, and it is news
         * when this phone logged some of the week's sets.
         */
        private fun balancedWeeks(): AchievementState {
            val l = ladder(Achievement.BALANCED_WEEKS)
            var count = 0
            for ((week, sets) in musclesByWeek) {
                val last = week.plusWeeks(1).atStartOfDay(input.zone).toInstant().toEpochMilli() - 1
                if (last >= input.now) break
                val balanced = BALANCE_MUSCLES.all { MuscleStats.level(it, sets[it] ?: 0.0) == VolumeLevel.OPTIMAL }
                if (!balanced) continue
                count++
                val touched = touchedByWeek.getValue(week)
                l.offer(count.toDouble(), last, if (touched == Long.MIN_VALUE) touched else max(last, touched))
            }
            return state(Achievement.BALANCED_WEEKS, l, count.toDouble())
        }

        private fun state(a: Achievement, l: Ladder, progress: Double?, blocker: AchievementBlocker? = null) =
            AchievementState(a, l.thresholds, l.unlocked.toList(), progress, blocker)

        private companion object {
            val LIFTS = listOf(Achievement.BENCH_PRESS, Achievement.SQUAT, Achievement.DEADLIFT, Achievement.OVERHEAD_PRESS)
        }
    }

    /** Oldest first; ties by id: the order the workout screen counts records in. */
    private val HISTORY_ORDER = compareBy<Workout>({ it.startedAt }, { it.id })

    // ---- body -------------------------------------------------------------------------------

    /** A body value at [t]; [imported]: not measured on this phone (see [BodySample.imported]). */
    private class Reading(val t: Long, val v: Double, val imported: Boolean)

    /** A 7-day trailing average at [t] (a reading's time); [touchedAt] as [TierUnlock.touchedAt]. */
    private class Average(val t: Long, val v: Double, val touchedAt: Long)

    /** Where a body value started and its 7-day averages since (oldest first). */
    private class Course(val start: Double, val averages: List<Average>)

    /**
     * The course of [readings] (oldest first) from [from] on. The start is the mean of the first
     * 7 days of readings, counted from a reading after [from], that hold at least
     * [MIN_AVERAGE_WEIGHINGS] of them; the averages are the trailing ones of every reading after that
     * week, again of at least [MIN_AVERAGE_WEIGHINGS] readings. So neither the start nor a milestone
     * hangs on a single weighing, and the noise of the first week never counts as progress (its
     * readings are what the start is the mean of). Null without such a start week.
     */
    private fun course(readings: List<Reading>, from: Long = Long.MIN_VALUE): Course? {
        val points = readings.filter { it.t > from }
        var start: Double? = null
        var startEnd = Long.MAX_VALUE
        for (i in points.indices) {
            val end = points[i].t + AVERAGE_MS
            var sum = 0.0
            var n = 0
            var j = i
            while (j < points.size && points[j].t < end) {
                sum += points[j].v
                n++
                j++
            }
            if (n >= MIN_AVERAGE_WEIGHINGS) {
                start = sum / n
                startEnd = end
                break
            }
        }
        if (start == null) return null
        val averages = ArrayList<Average>()
        var first = 0
        var sum = 0.0
        for (i in points.indices) {
            val p = points[i]
            sum += p.v
            while (points[first].t <= p.t - AVERAGE_MS) sum -= points[first++].v
            val n = i - first + 1
            if (p.t >= startEnd && n >= MIN_AVERAGE_WEIGHINGS) averages += Average(p.t, sum / n, if (p.imported) Long.MIN_VALUE else p.t)
        }
        return Course(start, averages)
    }

    /**
     * [Achievement.GOAL_PROGRESS]: kilos of the 7-day average moved from the starting weight towards
     * the profile's target (the direction is the target's side of the start); only the milestones
     * within the distance to the target are on offer. [Achievement.GOAL_REACHED]: the 7-day average
     * reaches the target (within [TARGET_TOLERANCE_KG]). The start is the average of the week the
     * target was set ([AchievementInput.targetSetAt]): an old weighing on the other side of a new
     * target must not turn a loss into a gain. The progress is the latest average's.
     */
    private fun goal(a: Achievement, body: List<BodySample>, input: AchievementInput): AchievementState {
        val fixed = a.thresholds(input.sex)
        val target = input.targetWeightKg
        if (target == null) return AchievementState(a, fixed, blocker = AchievementBlocker.NEEDS_TARGET)
        val from = input.targetSetAt?.let { it - AVERAGE_MS } ?: Long.MIN_VALUE
        val course = course(body.map { Reading(it.at, it.weightKg, it.imported) }, from)
            ?: return AchievementState(a, fixed, blocker = AchievementBlocker.NEEDS_WEIGHINGS, targetKg = target)
        val start = course.start
        val distance = abs(target - start)
        val losing = target < start
        if (distance < MIN_GOAL_KG) {
            return AchievementState(a, fixed, blocker = AchievementBlocker.TARGET_IS_START, losing = losing, targetKg = target)
        }
        val sign = if (losing) -1.0 else 1.0
        val moved = { p: Average -> (p.v - start) * sign }
        val current = course.averages.lastOrNull()?.let(moved)?.coerceAtLeast(0.0) ?: 0.0
        return if (a == Achievement.GOAL_REACHED) {
            val l = Ladder(listOf(distance - TARGET_TOLERANCE_KG))
            for (p in course.averages) l.offer(moved(p), p.t, p.touchedAt)
            AchievementState(a, listOf(distance), l.unlocked.toList(), current.coerceAtMost(distance), losing = losing, targetKg = target)
        } else {
            val within = fixed.count { it <= distance + EPS }
            val l = Ladder(fixed, limit = within)
            for (p in course.averages) l.offer(moved(p), p.t, p.touchedAt)
            AchievementState(a, fixed, l.unlocked.toList(), current, available = within, losing = losing, targetKg = target)
        }
    }

    /** Body fat points lost / muscle kilos gained: [gain] of the 7-day average over the first week's. */
    private fun composition(
        a: Achievement,
        input: AchievementInput,
        readings: List<Reading>,
        gain: (start: Double, value: Double) -> Double,
    ): AchievementState {
        val thresholds = a.thresholds(input.sex)
        if (readings.isEmpty()) return AchievementState(a, thresholds, blocker = AchievementBlocker.NEEDS_COMPOSITION)
        val course = course(readings) ?: return AchievementState(a, thresholds, blocker = AchievementBlocker.NEEDS_WEIGHINGS)
        val l = Ladder(thresholds)
        for (p in course.averages) l.offer(gain(course.start, p.v), p.t, p.touchedAt)
        val current = course.averages.lastOrNull()?.let { gain(course.start, it.v).coerceAtLeast(0.0) } ?: 0.0
        return AchievementState(a, thresholds, l.unlocked.toList(), current)
    }
}

/**
 * When the profile's current target weight ([targetTenthsKg], see [Achievements.tenthsKg]) was set:
 * the weight goals count from the average of then, so a target changed after years of weighings
 * inherits neither the first weighing as its start nor the direction that start gives. The profile
 * keeps no such time (nor does its backup): the celebration ledger notes it when it first sees a
 * target, which is the target's creation for a new profile.
 */
data class GoalAnchor(val targetTenthsKg: Long, val setAt: Long) {
    companion object {
        /** The anchor of [targetKg]: [current] while it is for the same target, else a new one at [now]. Without a target, [current]. */
        fun next(current: GoalAnchor?, targetKg: Double?, now: Long): GoalAnchor? {
            if (targetKg == null) return current
            val tenths = Achievements.tenthsKg(targetKg)
            return if (current?.targetTenthsKg == tenths) current else GoalAnchor(tenths, now)
        }
    }
}

/** A level to congratulate the user on. */
data class Celebration(
    val achievement: Achievement,
    val level: Int,
    /** What the level needed (see [Achievement] for the units). */
    val threshold: Double,
    val at: Long,
    /** Weight goals: losing (not gaining) weight. */
    val losing: Boolean = false,
    val targetKg: Double? = null,
) {
    val key: String get() = Achievements.key(achievement, level, targetKg)

    /** What showing it settles: its level and the lower ones of the achievement (always reached before it). */
    val keys: List<String> get() = (1..level).map { Achievements.key(achievement, it, targetKg) }
}

/**
 * Which new levels deserve a celebration. Only the keys of levels already handled are stored, plus
 * the baseline: the time of the first evaluation on this phone.
 *
 * - First evaluation (no baseline): everything already reached is filed silently, so updating the
 *   app or installing it on a new phone does not flood the user with old medals.
 * - Afterwards a level not handled before is celebrated only when the data behind it changed on this
 *   phone after the baseline and within [FRESH_MS] of now ([TierUnlock.touchedAt]). History that
 *   arrives late — a cloud restore, a CSV import, another phone's workouts — is filed silently; a
 *   workout logged today for last week is new and is celebrated.
 * - Several levels of one achievement at once: only the highest is shown, and showing it settles the
 *   lower ones ([Celebration.keys]). Until then they are neither shown nor filed, so a level that
 *   disappears before it is shown (a typo corrected) does not take them with it.
 * - More than [FLOOD_LIMIT] achievements at once is history arriving more often than news: then
 *   only the levels reached in the last [FRESH_MS] are shown, the others are filed silently. A first
 *   workout still celebrates everything it unlocks.
 */
object Celebrations {
    val FRESH_MS: Long = Duration.ofDays(2).toMillis()
    const val FLOOD_LIMIT = 5

    /** [baselineAt] to store (unchanged when one existed), keys to file without showing, levels to show. */
    data class Outcome(val baselineAt: Long, val silent: Set<String>, val show: List<Celebration>)

    fun reconcile(board: AchievementBoard, baselineAt: Long?, handled: Set<String>, now: Long): Outcome {
        val reached = board.states.flatMap { s -> s.unlocked.map { s to it } }.filter { (s, u) -> s.key(u.level) !in handled }
        if (baselineAt == null) {
            return Outcome(now, reached.mapTo(LinkedHashSet()) { (s, u) -> s.key(u.level) }, emptyList())
        }
        val (fresh, old) = reached.partition { (_, u) -> u.touchedAt >= baselineAt && u.touchedAt >= now - FRESH_MS }
        val highest = fresh.groupBy { it.first.achievement }.values.map { list -> list.maxBy { it.second.level } }
        val flooded = if (highest.size <= FLOOD_LIMIT) {
            emptySet()
        } else {
            highest.filter { (_, u) -> u.at < now - FRESH_MS }.mapTo(HashSet()) { it.first.achievement }
        }
        val silent = (old + fresh.filter { it.first.achievement in flooded }).mapTo(LinkedHashSet()) { (s, u) -> s.key(u.level) }
        val show = highest.filter { it.first.achievement !in flooded }.sortedBy { it.first.achievement.ordinal }.map { (s, u) ->
            Celebration(s.achievement, u.level, s.thresholds[u.level - 1], u.at, s.losing, s.targetKg)
        }
        return Outcome(baselineAt, silent, show)
    }
}
