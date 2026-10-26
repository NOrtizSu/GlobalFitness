package com.enjoyyourtime.globalfitness.training.music

import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class TodayTrainingTest {
    private val zone: ZoneId = ZoneOffset.UTC

    /** A Monday. */
    private val today = LocalDate.of(2026, 10, 26)
    private val exercises = TrainingRepository.buildExercises(ExerciseCatalog.all, emptyList(), emptyMap())
    private val lookup = { id: String -> exercises[id] ?: TrainingRepository.unknownExercise(id) }
    private var next = 0

    private val legs = listOf("leg_press_machine", "leg_extension_machine")
    private val upper = listOf("barbell_bench_press", "lat_pulldown_machine", "barbell_curl")

    private fun at(day: LocalDate, hour: Int = 18, minute: Int = 0, zone: ZoneId = this.zone): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Every done set is ticked [minutes] after the [start]; a finished workout ends [endedAfter] minutes after it. */
    private fun workout(
        day: LocalDate,
        exerciseIds: List<String>,
        sets: Int = 3,
        minutes: Int = 60,
        inProgress: Boolean = false,
        done: Boolean = true,
        warmup: Boolean = false,
        start: Long = at(day),
        endedAfter: Int = minutes,
    ): Workout = Workout(
        id = "w${next++}",
        startedAt = start,
        endedAt = if (inProgress) null else start + endedAfter * 60_000L,
        name = null,
        note = null,
        updatedAt = start,
        entries = exerciseIds.map { id ->
            WorkoutEntry(
                id = "e${next++}",
                exerciseId = id,
                sets = List(sets) {
                    WorkoutSet("s${next++}", weightKg = 50.0, reps = 10, warmup = warmup, done = done, doneAt = if (done) start + minutes * 60_000L else null)
                },
            )
        },
    )

    /** Late on [today], after every workout of these tests. */
    private val evening = at(today, 22)

    private fun classify(vararg workouts: Workout, now: Long = evening) =
        TodayTraining.classify(workouts.sortedBy { it.startedAt }, today, zone, now, lookup)

    @Test
    fun aLegWorkoutInProgressIsLegDayBeforeAnySetIsTicked() {
        assertEquals(TrainingDay.LEGS, classify(workout(today, legs + "barbell_curl", inProgress = true, done = false), now = at(today, 18, 5)))
    }

    @Test
    fun anUpperBodyWorkoutIsStrength() {
        assertEquals(TrainingDay.STRENGTH, classify(workout(today, upper)))
    }

    @Test
    fun legsMustBeMoreThanHalfOfTheStrengthExercises() {
        assertEquals(TrainingDay.STRENGTH, classify(workout(today, legs + upper.take(2))))
        assertEquals(TrainingDay.LEGS, classify(workout(today, legs + upper.take(1))))
    }

    @Test
    fun cardioCountsWhenItIsMoreThanHalfOfTheExercises() {
        assertEquals(TrainingDay.CARDIO, classify(workout(today, listOf("treadmill", "stationary_bike"))))
        assertEquals(TrainingDay.CARDIO, classify(workout(today, listOf("treadmill", "stationary_bike", "plank"))))
        // A tie is not a cardio day: a run and a strength exercise keep the strength music.
        assertEquals(TrainingDay.STRENGTH, classify(workout(today, listOf("treadmill", "plank"))))
        // A warm-up on the treadmill does not make a leg day a cardio one.
        assertEquals(TrainingDay.LEGS, classify(workout(today, listOf("treadmill") + legs + "leg_press_calf_raise")))
    }

    @Test
    fun anEmptyWorkoutJustStartedIsATrainingDay() {
        assertEquals(TrainingDay.TRAINING_DAY, classify(workout(today, emptyList(), inProgress = true), now = at(today, 18, 1)))
    }

    @Test
    fun todaysFinishedWorkoutsAndTheOneInProgressAreReadTogether() {
        val morningRun = workout(today, listOf("treadmill"), start = at(today, 8))
        val evening = workout(today, upper, inProgress = true, done = false)
        assertEquals(TrainingDay.STRENGTH, classify(morningRun, evening, now = at(today, 18, 10)))
    }

    @Test
    fun aWorkoutGoingOnPastMidnightStillCounts() {
        // Started at 23:00 yesterday, a set ticked at 23:40, still at it at 00:30.
        val late = workout(today.minusDays(1), listOf("rowing_machine"), minutes = 40, inProgress = true, start = at(today.minusDays(1), 23))
        val now = at(today, 0, 30)
        assertEquals(TrainingDay.CARDIO, classify(late, now = now))
        assertEquals(late, TodayTraining.liveWorkout(listOf(late), now))
        // The music stays the one of the day it started on.
        assertEquals(today.minusDays(1), TodayTraining.musicDate(listOf(late), now, zone))
    }

    @Test
    fun aWorkoutLeftOpenIsNotTodaysTraining() {
        // Started yesterday at 18:00, last set at 19:00, never finished: 27 hours later it says nothing.
        val forgotten = workout(today.minusDays(1), listOf("rowing_machine"), inProgress = true)
        assertEquals(TrainingDay.TRAINING_DAY, classify(forgotten))
        assertNull(TodayTraining.liveWorkout(listOf(forgotten), evening))
        assertEquals(today, TodayTraining.musicDate(listOf(forgotten), evening, zone))
        // Still live 11 hours after its last set, not 12.
        val lastSet = forgotten.lastActivityAt
        assertTrue(TodayTraining.isLive(forgotten, lastSet + 11 * 3_600_000L))
        assertNull(TodayTraining.liveWorkout(listOf(forgotten), lastSet + 12 * 3_600_000L))
    }

    @Test
    fun nothingLoggedIsATrainingDay() {
        assertEquals(TrainingDay.TRAINING_DAY, classify())
    }

    @Test
    fun aHardDayYesterdayMakesTodayARecoveryDay() {
        val yesterday = today.minusDays(1)
        assertEquals(TrainingDay.RECOVERY, classify(workout(yesterday, upper + legs, sets = 4)))
        assertEquals(TrainingDay.RECOVERY, classify(workout(yesterday, listOf("treadmill"), sets = 1, minutes = 95)))
        assertEquals(TrainingDay.TRAINING_DAY, classify(workout(yesterday, upper, sets = 6, minutes = 70)))
        // Warm-up sets are not working sets.
        assertEquals(TrainingDay.TRAINING_DAY, classify(workout(yesterday, upper + legs, sets = 4, warmup = true)))
    }

    @Test
    fun aWorkoutFinishedHoursLaterLastedUntilItsLastSet() {
        // Last set an hour in, "Finish" tapped ten hours later: an hour of training, not a hard day.
        assertEquals(TrainingDay.TRAINING_DAY, classify(workout(today.minusDays(1), upper, sets = 6, minutes = 60, endedAfter = 600)))
    }

    @Test
    fun aRegularWhoNeverTrainsOnThisWeekdayRests() {
        // Tuesdays, Thursdays and Saturdays for four weeks: 12 days, never a Monday.
        val days = (1L..28L).map { today.minusDays(it) }.filter { it.dayOfWeek in setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY) }
        val pattern = days.map { workout(it, upper) }.toTypedArray()
        assertEquals(TrainingDay.REST, classify(*pattern))
        // One Monday in the last four weeks: a day they may train.
        assertEquals(TrainingDay.TRAINING_DAY, classify(*pattern, workout(today.minusDays(14), upper)))
        // Starting a workout decides the day anyway.
        assertEquals(TrainingDay.CARDIO, classify(*pattern, workout(today, listOf("elliptical"), inProgress = true, done = false), now = at(today, 18, 5)))
    }

    @Test
    fun anOccasionalTrainerIsNotToldToRest() {
        val days = listOf(2L, 6L, 9L, 13L, 20L).map { today.minusDays(it) }
        assertEquals(TrainingDay.TRAINING_DAY, classify(*days.map { workout(it, upper) }.toTypedArray()))
    }

    @Test
    fun theDaysAreThoseOfThePhonesTimeZone() {
        val la = ZoneId.of("America/Los_Angeles")
        // 22:00 in Los Angeles on Monday is 05:00 UTC on Tuesday.
        val lateLegs = workout(today, legs, start = at(today, 22, zone = la))
        val now = at(today, 23, 30, zone = la)
        assertEquals(TrainingDay.LEGS, TodayTraining.classify(listOf(lateLegs), today, la, now, lookup))
        assertEquals(TrainingDay.TRAINING_DAY, TodayTraining.classify(listOf(lateLegs), today, zone, now, lookup))
        assertEquals(today, TodayTraining.musicDate(emptyList(), now, la))
        assertEquals(today.plusDays(1), TodayTraining.musicDate(emptyList(), now, zone))
    }
}
