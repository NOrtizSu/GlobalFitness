package com.enjoyyourtime.globalfitness.training

import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.data.Sex
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.domain.Achievement
import com.enjoyyourtime.globalfitness.training.domain.AchievementBlocker
import com.enjoyyourtime.globalfitness.training.domain.AchievementBoard
import com.enjoyyourtime.globalfitness.training.domain.AchievementInput
import com.enjoyyourtime.globalfitness.training.domain.Achievements
import com.enjoyyourtime.globalfitness.training.domain.BodySample
import com.enjoyyourtime.globalfitness.training.domain.BodyweightTimeline
import com.enjoyyourtime.globalfitness.training.domain.GoalAnchor
import com.enjoyyourtime.globalfitness.training.domain.LoadModel
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import com.enjoyyourtime.globalfitness.ui.training.WorkoutRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

class AchievementsTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val day = 86_400_000L
    private val hour = 3_600_000L
    private val catalog = TrainingRepository.buildExercises(ExerciseCatalog.all, emptyList(), emptyMap())

    /** Catalog exercises, plus "m:<muscle>": a made-up exercise training only that muscle. */
    private val lookup: (String) -> Exercise = { id ->
        catalog[id] ?: Muscle.fromCode(id.removePrefix("m:"))?.let { m ->
            Exercise(id, UiText.raw(id), ExerciseKind.WEIGHT_REPS, Equipment.MACHINE, setOf(m), emptySet(), builtIn = false)
        } ?: TrainingRepository.unknownExercise(id)
    }
    private var ids = 0

    private fun at(date: String, time: String = "18:00"): Long = LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()

    private fun set(kg: Double?, reps: Int?, at: Long, warmup: Boolean = false, done: Boolean = true, distanceM: Double? = null, durationSec: Int? = null) =
        WorkoutSet("s${ids++}", weightKg = kg, reps = reps, durationSec = durationSec, distanceM = distanceM, warmup = warmup, done = done, doneAt = at)

    private fun workout(
        start: Long,
        vararg entries: Pair<String, List<WorkoutSet>>,
        end: Long? = start + hour,
        updatedAt: Long = end ?: start,
        id: String = "w${ids++}",
    ) = Workout(id, start, end, null, null, updatedAt, entries.map { (ex, sets) -> WorkoutEntry("e${ids++}", ex, sets) })

    /** A finished workout with one working set of bench press at [start]. */
    private fun simple(start: Long, updatedAt: Long = start + hour) =
        workout(start, "barbell_bench_press" to listOf(set(40.0, 10, start + 60_000)), updatedAt = updatedAt)

    private fun board(
        workouts: List<Workout> = emptyList(),
        body: List<BodySample> = emptyList(),
        sex: Sex = Sex.MALE,
        age: Int = 30,
        target: Double? = null,
        now: Long = at("2026-06-01"),
        firstDay: DayOfWeek = DayOfWeek.MONDAY,
        targetSetAt: Long? = null,
        remote: List<Workout> = emptyList(),
    ): AchievementBoard = Achievements.evaluate(
        AchievementInput(workouts, lookup, body, sex, age, target, zone, firstDay, now, targetSetAt, remote.associate { it.id to it.updatedAt }),
    )

    private fun AchievementBoard.of(a: Achievement) = checkNotNull(state(a))

    // ---- workouts -------------------------------------------------------------------------

    @Test
    fun nothingLoggedEarnsNothing() {
        val b = board()
        assertEquals(0, b.earned)
        assertEquals(Achievement.entries.size, b.states.size)
        assertEquals(0.0, b.of(Achievement.WORKOUTS).progress!!, 0.0)
        assertEquals(AchievementBlocker.NEEDS_BODYWEIGHT, b.of(Achievement.BENCH_PRESS).blocker)
        assertEquals(AchievementBlocker.NEEDS_TARGET, b.of(Achievement.GOAL_REACHED).blocker)
        assertEquals(AchievementBlocker.NEEDS_COMPOSITION, b.of(Achievement.BODY_FAT).blocker)
    }

    @Test
    fun finishedWorkoutsClimbTheLevelsWhenTheyEnd() {
        val starts = (0 until 6).map { at("2026-03-02") + it * day }
        val finished = starts.map { simple(it) }
        // Neither the workout in progress nor one that ended without a completed set counts.
        val live = workout(at("2026-03-10"), "barbell_bench_press" to listOf(set(40.0, 10, at("2026-03-10", "18:05"))), end = null)
        val empty = workout(at("2026-03-11"), "barbell_bench_press" to listOf(set(40.0, 10, at("2026-03-11"), done = false)))
        val s = board(finished + live + empty).of(Achievement.WORKOUTS)
        assertEquals(6.0, s.progress!!, 0.0)
        assertEquals(2, s.level)
        assertEquals(starts[0] + hour, s.unlocked[0].at)
        assertEquals(starts[4] + hour, s.unlocked[1].at)
        assertEquals(10.0, s.next!!, 0.0)
        assertEquals(0.6, s.fraction!!, 1e-9)
    }

    // ---- weekly streak ---------------------------------------------------------------------

    private val streakWeeks = listOf(
        simple(at("2026-03-02")), // Monday
        simple(at("2026-03-08", "23:30")), // Sunday night
        simple(at("2026-03-09", "00:30")), // Monday, just after midnight
        simple(at("2026-03-11")), // Wednesday
    )

    @Test
    fun streakCountsCalendarWeeksInTheUsersZone() {
        val s = board(streakWeeks, now = at("2026-03-12")).of(Achievement.WEEK_STREAK)
        assertEquals(1, s.level)
        assertEquals(at("2026-03-11") + hour, s.unlocked[0].at)
        assertEquals(2.0, s.progress!!, 0.0)
        // Weeks starting on Sunday put Sunday night with the next week: one qualifying week only.
        val sunday = board(streakWeeks, now = at("2026-03-12"), firstDay = DayOfWeek.SUNDAY).of(Achievement.WEEK_STREAK)
        assertEquals(0, sunday.level)
        assertEquals(1.0, sunday.progress!!, 0.0)
    }

    @Test
    fun theCurrentWeekDoesNotBreakTheStreakUntilItEnds() {
        // Next week, nothing yet: still a 2-week streak.
        assertEquals(2.0, board(streakWeeks, now = at("2026-03-17")).of(Achievement.WEEK_STREAK).progress!!, 0.0)
        // A whole week without training breaks it; the level stays earned.
        val broken = board(streakWeeks, now = at("2026-03-24")).of(Achievement.WEEK_STREAK)
        assertEquals(0.0, broken.progress!!, 0.0)
        assertEquals(1, broken.level)
        // After a gap the streak starts again from one.
        val again = streakWeeks + simple(at("2026-03-23")) + simple(at("2026-03-25"))
        assertEquals(1.0, board(again, now = at("2026-03-26")).of(Achievement.WEEK_STREAK).progress!!, 0.0)
    }

    @Test
    fun aWeekWithOneWorkoutDoesNotCount() {
        val weeks = listOf(simple(at("2026-03-02")), simple(at("2026-03-03")), simple(at("2026-03-10")), simple(at("2026-03-16")), simple(at("2026-03-17")))
        val s = board(weeks, now = at("2026-03-18")).of(Achievement.WEEK_STREAK)
        assertEquals(0, s.level)
        assertEquals(1.0, s.progress!!, 0.0)
    }

    // ---- volume ----------------------------------------------------------------------------

    @Test
    fun volumeCountsTheWeightReallyMoved() {
        val t = at("2026-03-02")
        val body = listOf(BodySample(t - day, 80.0))
        val w = workout(
            t,
            // Assisted: 80 kg body − 30 kg assistance = 50 kg × 10.
            "assisted_pull_up_machine" to listOf(set(30.0, 10, t + 60_000), set(30.0, 10, t + 90_000, warmup = true)),
            // Bodyweight pull-up: 80 kg × 10; a set not ticked does not count.
            "pull_up" to listOf(set(null, 10, t + 120_000), set(null, 10, t + 150_000, done = false)),
        )
        val s = board(listOf(w), body).of(Achievement.VOLUME)
        assertEquals(1_300.0, s.progress!!, 1e-9)
        assertEquals(1, s.level)
        assertEquals(t + 120_000, s.unlocked[0].at)
        // Without any weighing those exercises have no load.
        assertEquals(0.0, board(listOf(w)).of(Achievement.VOLUME).progress!!, 0.0)
    }

    @Test
    fun workoutInProgressCountsForVolume() {
        val t = at("2026-03-02")
        val live = workout(t, "barbell_back_squat" to listOf(set(100.0, 10, t + 60_000)), end = null, updatedAt = t + 60_000)
        val s = board(listOf(live)).of(Achievement.VOLUME)
        assertEquals(1, s.level)
        assertEquals(t + 60_000, s.unlocked[0].touchedAt)
    }

    // ---- records ---------------------------------------------------------------------------

    @Test
    fun recordsMatchWhatTheWorkoutScreenCounts() {
        val random = Random(11)
        val exercises = listOf("barbell_bench_press", "assisted_pull_up_machine", "pull_up", "plank", "dumbbell_curl")
        val body = listOf(BodySample(at("2026-01-01"), 82.0), BodySample(at("2026-02-15"), 80.5))
        var t = at("2026-01-05")
        val workouts = (0 until 40).map { i ->
            t += day + random.nextLong(hour)
            var setTime = t
            val entries = exercises.shuffled(random).take(3).map { id ->
                id to (0 until 3).map {
                    setTime += 120_000
                    when (id) {
                        "plank" -> set(null, null, setTime, durationSec = 30 + random.nextInt(60))
                        "pull_up" -> set(if (random.nextBoolean()) 0.0 else random.nextInt(0, 3) * 5.0, 3 + random.nextInt(8), setTime)
                        "assisted_pull_up_machine" -> set(50.0 - random.nextInt(10) * 2.5, 6 + random.nextInt(4), setTime)
                        else -> set(20.0 + random.nextInt(20) * 2.5, 3 + random.nextInt(10), setTime, warmup = random.nextInt(6) == 0)
                    }
                }
            }
            workout(t, *entries.toTypedArray(), end = if (i == 39) null else setTime + 60_000)
        }
        val load = LoadModel(BodyweightTimeline(body.map { it.at to it.weightKg }))
        val expected = workouts.sumOf { WorkoutRecords.forWorkout(workouts, it, lookup, load).size }
        val s = board(workouts.shuffled(random), body).of(Achievement.RECORDS)
        assertTrue("the history should beat some records", expected >= 10)
        assertEquals(expected.toDouble(), s.progress!!, 0.0)
        assertEquals(Achievement.RECORDS.thresholds(Sex.MALE).count { it <= expected }, s.level)
    }

    @Test
    fun theFirstTimeIsNoRecordAndTiesAreNotRecords() {
        val t = at("2026-03-02")
        val w1 = workout(t, "barbell_bench_press" to listOf(set(60.0, 5, t + 1), set(60.0, 5, t + 2)))
        val w2 = workout(t + day, "barbell_bench_press" to listOf(set(62.5, 5, t + day + 1)))
        val s = board(listOf(w1, w2)).of(Achievement.RECORDS)
        assertEquals(1.0, s.progress!!, 0.0)
        assertEquals(t + day + 1, s.unlocked.single().at)
    }

    // ---- strength --------------------------------------------------------------------------

    @Test
    fun liftsCompareTheBestEstimateWithTheBodyWeightOfThatDay() {
        val t = at("2026-03-02")
        val body = listOf(BodySample(t - day, 80.0), BodySample(t + day, 100.0))
        val w = workout(
            t,
            "barbell_bench_press" to listOf(set(80.0, 1, t + 1)),
            // The Smith machine is not the barbell.
            "smith_bench_press" to listOf(set(200.0, 1, t + 2)),
        )
        val male = board(listOf(w), body).of(Achievement.BENCH_PRESS)
        assertEquals(1.0, male.progress!!, 1e-9)
        assertEquals(2, male.level)
        assertEquals(1.25, male.next!!, 0.0)
        // Women's levels are lower: 1× is the third.
        assertEquals(3, board(listOf(w), body, sex = Sex.FEMALE).of(Achievement.BENCH_PRESS).level)
        // Without any weighing the lifts cannot be compared.
        val unknown = board(listOf(w)).of(Achievement.BENCH_PRESS)
        assertEquals(AchievementBlocker.NEEDS_BODYWEIGHT, unknown.blocker)
        assertNull(unknown.progress)
        assertEquals(0, unknown.level)
    }

    @Test
    fun squatAndDeadliftVariantsCount() {
        val t = at("2026-03-02")
        val body = listOf(BodySample(t - day, 70.0))
        val w = workout(
            t,
            // Epley: 100 × (1 + 5 / 30) ≈ 116.7 kg = 1.67× body weight.
            "barbell_front_squat" to listOf(set(100.0, 5, t + 1)),
            "sumo_deadlift" to listOf(set(140.0, 1, t + 2)),
        )
        val b = board(listOf(w), body)
        assertEquals(2, b.of(Achievement.SQUAT).level)
        assertEquals(116.666 / 70.0, b.of(Achievement.SQUAT).progress!!, 1e-3)
        assertEquals(3, b.of(Achievement.DEADLIFT).level)
    }

    @Test
    fun pullUpsCountOnlyWithoutAssistance() {
        val t = at("2026-03-02")
        val body = listOf(BodySample(t - day, 80.0))
        val w = workout(
            t,
            "assisted_pull_up_machine" to listOf(set(10.0, 8, t + 1), set(0.0, 3, t + 2), set(null, 9, t + 3)),
            "pull_up" to listOf(set(5.0, 6, t + 4)),
            "chin_up" to listOf(set(null, 4, t + 5)),
        )
        val s = board(listOf(w), body).of(Achievement.PULL_UPS)
        assertEquals(6.0, s.progress!!, 0.0)
        assertEquals(2, s.level)
        assertEquals(t + 2, s.unlocked[0].at)
        assertEquals(t + 4, s.unlocked[1].at)
    }

    // ---- balanced weeks --------------------------------------------------------------------

    @Test
    fun aBalancedWeekHasEveryMainMuscleWithinItsRange() {
        val monday = at("2026-03-02")
        fun week(start: Long, extraChest: Int = 0) = workout(
            start,
            *Achievements.BALANCE_MUSCLES.mapIndexed { i, m ->
                "m:${m.code}" to (0 until 12 + if (m == Muscle.CHEST) extraChest else 0).map { set(20.0, 10, start + i * 1_000L + it) }
            }.toTypedArray(),
        )
        val balanced = week(monday)
        // 22 chest sets: above its 10–20 range.
        val tooMuchChest = week(monday + 7 * day, extraChest = 10)
        val s = board(listOf(balanced, tooMuchChest)).of(Achievement.BALANCED_WEEKS)
        assertEquals(1.0, s.progress!!, 0.0)
        assertEquals(1, s.level)
        // A week missing one muscle is not balanced either.
        val missing = workout(monday, "m:chest" to (0 until 12).map { set(20.0, 10, monday + it) })
        assertEquals(0, board(listOf(missing)).of(Achievement.BALANCED_WEEKS).level)
    }

    @Test
    fun aBalancedWeekCountsOnceItIsOver() {
        val monday = at("2026-03-02")
        val w = workout(
            monday,
            *Achievements.BALANCE_MUSCLES.mapIndexed { i, m -> "m:${m.code}" to (0 until 12).map { set(20.0, 10, monday + i * 1_000L + it) } }.toTypedArray(),
        )
        // On Thursday the week could still go above a range: not yet.
        val during = board(listOf(w), now = at("2026-03-05")).of(Achievement.BALANCED_WEEKS)
        assertEquals(0, during.level)
        assertEquals(0.0, during.progress!!, 0.0)
        // At midnight on Sunday it is reached, as news (the week's end is its change time).
        val weekEnd = at("2026-03-09", "00:00") - 1
        val after = board(listOf(w), now = at("2026-03-09", "00:00")).of(Achievement.BALANCED_WEEKS)
        assertEquals(1, after.level)
        assertEquals(weekEnd, after.unlocked.single().at)
        assertEquals(weekEnd, after.unlocked.single().touchedAt)
        // A week logged on another phone only is that phone's news.
        assertEquals(Long.MIN_VALUE, board(listOf(w), now = at("2026-03-10"), remote = listOf(w)).of(Achievement.BALANCED_WEEKS).unlocked.single().touchedAt)
    }

    // ---- where the data comes from ---------------------------------------------------------

    @Test
    fun levelsCrossedInAnotherPhonesWorkoutAreNotNews() {
        val body = listOf(BodySample(at("2026-02-01"), 80.0))
        val starts = (0 until 5).map { at("2026-03-02") + it * day }
        val local = starts.take(4).map { simple(it) }
        // The 5th, synced from the other phone yesterday, with a heavy bench press and 5 pull-ups.
        val synced = workout(
            starts[4],
            "barbell_bench_press" to listOf(set(100.0, 1, starts[4] + 1)),
            "pull_up" to listOf(set(null, 5, starts[4] + 2)),
            updatedAt = at("2026-05-31"),
        )
        val b = board(local + synced, body, remote = listOf(synced))
        assertEquals(Long.MIN_VALUE, b.of(Achievement.WORKOUTS).unlocked[1].touchedAt)
        assertEquals(Long.MIN_VALUE, b.of(Achievement.BENCH_PRESS).unlocked.last().touchedAt)
        assertEquals(Long.MIN_VALUE, b.of(Achievement.PULL_UPS).unlocked.last().touchedAt)
        // Edited here afterwards it is another version, this phone's.
        val edited = synced.copy(name = "Push", updatedAt = at("2026-06-01"))
        assertEquals(edited.updatedAt, board(local + edited, body, remote = listOf(synced)).of(Achievement.WORKOUTS).unlocked[1].touchedAt)
        // A workout logged here for an earlier day makes this phone's later levels news; another phone's stay its own.
        val earlier = simple(at("2026-02-20"), updatedAt = at("2026-06-01"))
        val mixed = board(local + synced + earlier, body, remote = listOf(synced))
        assertEquals(at("2026-06-01"), mixed.of(Achievement.VOLUME).unlocked.single().touchedAt)
        assertEquals(Long.MIN_VALUE, mixed.of(Achievement.PULL_UPS).unlocked.last().touchedAt)
    }

    @Test
    fun importedWeighingsAreNotNews() {
        val body = daily(0, List(7) { 90.0 }) + daily(7, List(10) { 86.0 }).map { it.copy(imported = true) }
        val s = board(body = body, target = 80.0).of(Achievement.GOAL_PROGRESS)
        assertEquals(2, s.level)
        assertTrue(s.unlocked.all { it.touchedAt == Long.MIN_VALUE })
    }

    @Test
    fun everyLevelOnOffer() {
        // The page's "12 of 70 medals" is computed: every level, less the weight milestones beyond the target.
        assertEquals(70, Achievement.entries.sumOf { it.levels })
        assertEquals(70, board().total)
        assertEquals(70 - 3, board(body = daily(0, List(7) { 70.0 }), target = 68.0).total)
    }

    // ---- variety and cardio ----------------------------------------------------------------

    @Test
    fun varietyAndCardioDistance() {
        val t = at("2026-03-02")
        val ids = listOf("barbell_bench_press", "dumbbell_curl", "pull_up", "plank", "treadmill")
        val w = workout(
            t,
            *ids.map { id ->
                id to listOf(if (id == "treadmill") set(null, null, t + 1, distanceM = 6_000.0, durationSec = 1_800) else set(20.0, 10, t + 2))
            }.toTypedArray(),
            "stationary_bike" to listOf(set(null, null, t + 3, distanceM = 5_000.0, durationSec = 1_200)),
        )
        val b = board(listOf(w))
        assertEquals(6.0, b.of(Achievement.EXERCISE_VARIETY).progress!!, 0.0)
        assertEquals(1, b.of(Achievement.EXERCISE_VARIETY).level)
        assertEquals(11.0, b.of(Achievement.CARDIO_DISTANCE).progress!!, 1e-9)
        assertEquals(t + 3, b.of(Achievement.CARDIO_DISTANCE).unlocked.single().at)
    }

    // ---- body goals ------------------------------------------------------------------------

    private fun daily(fromDay: Int, values: List<Double>, start: Long = at("2026-01-01", "08:00")) =
        values.mapIndexed { i, v -> BodySample(start + (fromDay + i) * day, v) }

    @Test
    fun weightMilestonesUseTheSevenDayAverage() {
        // A week at 90 kg, then one light weighing: one day at 88 kg moves the average by 0.3 kg only.
        val start = daily(0, List(7) { 90.0 }) + daily(7, listOf(88.0))
        val dip = board(body = start, target = 84.0)
        assertEquals(0, dip.of(Achievement.GOAL_PROGRESS).level)
        assertEquals(2.0 / 7.0, dip.of(Achievement.GOAL_PROGRESS).progress!!, 1e-9)
        // Then a steady loss with ±0.5 kg of noise, down to 83 kg.
        val losing = start + daily(8, (0 until 40).map { 89.8 - it * 0.17 + if (it % 2 == 0) 0.5 else -0.5 })
        val b = board(body = losing, target = 84.0)
        val progress = b.of(Achievement.GOAL_PROGRESS)
        assertTrue(progress.losing)
        // 6 kg to lose: the 10 kg milestone is not on offer.
        assertEquals(3, progress.available)
        assertEquals(3, progress.level)
        assertNull(progress.next)
        val reached = b.of(Achievement.GOAL_REACHED)
        assertEquals(1, reached.level)
        assertEquals(6.0, reached.thresholds.single(), 1e-9)
    }

    @Test
    fun weightMilestonesFollowAGainTarget() {
        val body = daily(0, List(7) { 70.0 }) + daily(7, (1..30).map { 70.0 + it * 0.12 })
        val b = board(body = body, target = 80.0)
        val s = b.of(Achievement.GOAL_PROGRESS)
        assertEquals(false, s.losing)
        assertEquals(4, s.available)
        assertEquals(2, s.level)
        assertTrue(s.progress!! > 3.0 && s.progress!! < 3.6)
        assertEquals(0, b.of(Achievement.GOAL_REACHED).level)
        // Losing weight while the target is above does not count as progress.
        val wrongWay = daily(0, List(7) { 70.0 }) + daily(7, List(10) { 66.0 })
        assertEquals(0, board(body = wrongWay, target = 80.0).of(Achievement.GOAL_PROGRESS).level)
        assertEquals(0.0, board(body = wrongWay, target = 80.0).of(Achievement.GOAL_PROGRESS).progress!!, 0.0)
    }

    @Test
    fun weightGoalsNeedATargetAwayFromTheStart() {
        val body = daily(0, List(7) { 70.0 })
        assertEquals(AchievementBlocker.NEEDS_TARGET, board(body = body).of(Achievement.GOAL_PROGRESS).blocker)
        assertEquals(AchievementBlocker.TARGET_IS_START, board(body = body, target = 70.3).of(Achievement.GOAL_REACHED).blocker)
        assertEquals(AchievementBlocker.NEEDS_WEIGHINGS, board(target = 70.0).of(Achievement.GOAL_REACHED).blocker)
        // Less than a kilo to go: no milestone on offer, only the target itself.
        val close = board(body = body, target = 69.2)
        assertEquals(0, close.of(Achievement.GOAL_PROGRESS).available)
        assertEquals(1, close.of(Achievement.GOAL_REACHED).available)
    }

    @Test
    fun bodyFatPointsCountDownToTheHealthyMinimum() {
        fun fat(values: List<Double>) = values.mapIndexed { i, v -> BodySample(at("2026-01-01", "08:00") + i * day, 80.0, fatPercent = v) }
        val dropping = fat(List(7) { 25.0 } + (1..20).map { 25.0 - it * 0.2 })
        val s = board(body = dropping).of(Achievement.BODY_FAT)
        assertEquals(2, s.level)
        assertTrue(s.progress!! > 3.0 && s.progress!! < 4.0)
        // Men of 30: below 11 % is no achievement. From 13 % to 9 % only 2 points count.
        val lean = fat(List(7) { 13.0 } + List(10) { 9.0 })
        val leanState = board(body = lean, age = 30).of(Achievement.BODY_FAT)
        assertEquals(1, leanState.level)
        assertEquals(2.0, leanState.progress!!, 1e-9)
    }

    @Test
    fun muscleMassGain() {
        // The last week averages 62.55 kg: 2.55 kg over the first week's 60 kg.
        val body = (List(7) { 60.0 } + (1..20).map { 60.0 + it * 0.15 }).mapIndexed { i, v ->
            BodySample(at("2026-01-01", "08:00") + i * day, 80.0, fatPercent = 20.0, muscleKg = v)
        }
        val s = board(body = body).of(Achievement.MUSCLE_MASS)
        assertEquals(2, s.level)
        assertEquals(2.55, s.progress!!, 1e-9)
        assertEquals(3.0, s.next!!, 0.0)
    }

    @Test
    fun aNoisyFirstWeekEarnsNothing() {
        // 79 kg on the first day, then 81 kg: the start is the week's mean, so the first day is no loss.
        val weight = daily(0, listOf(79.0) + List(6) { 81.0 })
        val w = board(body = weight, target = 75.0)
        assertEquals(0, w.of(Achievement.GOAL_PROGRESS).level)
        assertEquals(0, w.of(Achievement.GOAL_REACHED).level)
        assertEquals(0.0, w.of(Achievement.GOAL_PROGRESS).progress!!, 0.0)
        // Body fat 20 % then 22 %, muscle 61.5 kg then 60 kg: no point lost, no kilo gained.
        fun composition(days: Int) = (0 until days).map { i ->
            BodySample(at("2026-01-01", "08:00") + i * day, 80.0, fatPercent = if (i == 0) 20.0 else 22.0, muscleKg = if (i == 0) 61.5 else 60.0)
        }
        for (days in listOf(7, 14)) {
            val c = board(body = composition(days))
            assertEquals(0, c.of(Achievement.BODY_FAT).level)
            assertEquals(0, c.of(Achievement.MUSCLE_MASS).level)
            assertEquals(0.0, c.of(Achievement.MUSCLE_MASS).progress!!, 0.0)
        }
        // The same steady weight a week on: still nothing.
        assertEquals(0, board(body = weight + daily(7, List(7) { 81.0 }), target = 75.0).of(Achievement.GOAL_PROGRESS).level)
    }

    @Test
    fun anAverageNeedsTwoWeighings() {
        // Once a week: no week holds two weighings, so there is no start to count from.
        val weekly = (0 until 6).map { BodySample(at("2026-01-01", "08:00") + it * 7 * day, 90.0 - it, fatPercent = 25.0 - it) }
        assertEquals(AchievementBlocker.NEEDS_WEIGHINGS, board(body = weekly, target = 80.0).of(Achievement.GOAL_PROGRESS).blocker)
        assertEquals(AchievementBlocker.NEEDS_WEIGHINGS, board(body = weekly).of(Achievement.BODY_FAT).blocker)
        // A week of weighings, then a lone one 5 kg lighter: one weighing is no average.
        val lone = daily(0, List(7) { 90.0 }) + daily(20, listOf(85.0))
        val s = board(body = lone, target = 80.0).of(Achievement.GOAL_PROGRESS)
        assertEquals(0, s.level)
        assertEquals(0.0, s.progress!!, 0.0)
        // A second one within the week makes it one.
        val two = board(body = lone + daily(22, listOf(85.0)), target = 80.0).of(Achievement.GOAL_PROGRESS)
        assertEquals(3, two.level)
        assertEquals(5.0, two.progress!!, 1e-9)
    }

    @Test
    fun weightGoalsCountFromWhenTheTargetWasSet() {
        // A week at 70 kg three years ago, 85 kg this month, and a target of 78 kg set today.
        val old = daily(0, List(7) { 70.0 }, start = at("2023-05-01", "08:00"))
        val recent = daily(0, List(30) { 85.0 }, start = at("2026-05-02", "08:00"))
        val set = at("2026-06-01")
        val b = board(body = old + recent, target = 78.0, targetSetAt = set)
        val progress = b.of(Achievement.GOAL_PROGRESS)
        assertTrue(progress.losing)
        assertEquals(0, progress.level)
        assertEquals(3, progress.available)
        assertEquals(0, b.of(Achievement.GOAL_REACHED).level)
        assertEquals(7.0, b.of(Achievement.GOAL_REACHED).thresholds.single(), 1e-9)
        // From the first weighing it would be a gain, already reached 7 kg above the target.
        val fromFirst = board(body = old + recent, target = 78.0)
        assertEquals(false, fromFirst.of(Achievement.GOAL_PROGRESS).losing)
        assertEquals(1, fromFirst.of(Achievement.GOAL_REACHED).level)
        // Then two weeks at 81 kg: 4 kg lost, two milestones.
        val after = daily(0, List(14) { 81.0 }, start = at("2026-06-02", "08:00"))
        val later = board(body = old + recent + after, target = 78.0, targetSetAt = set, now = at("2026-06-20"))
        assertEquals(2, later.of(Achievement.GOAL_PROGRESS).level)
        // Each target has its own keys: a new one is celebrated even if an old one was.
        assertEquals("goal_progress:2@780", later.of(Achievement.GOAL_PROGRESS).key(2))
        assertEquals("goal:1@780", later.of(Achievement.GOAL_REACHED).key(1))
    }

    @Test
    fun theGoalAnchorMovesOnlyWithTheTarget() {
        val first = GoalAnchor.next(null, 78.0, 100L)
        assertEquals(GoalAnchor(780, 100L), first)
        // The profile keeps one decimal: the same target.
        assertEquals(first, GoalAnchor.next(first, 78.04, 200L))
        // Removed and set again: the same goal goes on.
        assertEquals(first, GoalAnchor.next(first, null, 300L))
        assertEquals(GoalAnchor(750, 400L), GoalAnchor.next(first, 75.0, 400L))
        assertNull(GoalAnchor.next(null, null, 500L))
    }

    @Test
    fun boardSummaries() {
        val starts = (0 until 3).map { at("2026-03-02") + it * day }
        val b = board(starts.map { simple(it) })
        assertEquals(b.states.sumOf { it.level }, b.earned)
        // 1 200 kg lifted in the third workout: the volume level came last.
        assertEquals(Achievement.VOLUME, b.latest!!.first.achievement)
        // 3 of 5 workouts is the closest level (variety: 1 of 5; records: none yet).
        assertEquals(Achievement.WORKOUTS, b.closest!!.achievement)
    }
}
