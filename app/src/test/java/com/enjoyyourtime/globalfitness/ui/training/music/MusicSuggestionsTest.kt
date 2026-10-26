package com.enjoyyourtime.globalfitness.ui.training.music

import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import com.enjoyyourtime.globalfitness.training.music.DailyPlaylists
import com.enjoyyourtime.globalfitness.training.music.TrainingDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The suggestions of [MusicViewModel] as the days and the workouts go by. */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicSuggestionsTest {
    private val zone: ZoneId = ZoneId.of("Europe/Madrid")
    private val monday = LocalDate.of(2026, 10, 26)
    private val tuesday = monday.plusDays(1)

    private val enabled = MutableStateFlow(true)
    private val loaded = MutableStateFlow(true)
    private val workouts = MutableStateFlow<List<Workout>>(emptyList())
    private val exercises = MutableStateFlow(TrainingRepository.buildExercises(ExerciseCatalog.all, emptyList(), emptyMap()))
    private val clock = MutableStateFlow(at(monday, 20))

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** An upper-body workout in progress, started at [start], a set ticked [minutes] later. */
    private fun liveWorkout(start: Long, minutes: Int): Workout = Workout(
        id = "w",
        startedAt = start,
        endedAt = null,
        name = null,
        note = null,
        updatedAt = start,
        entries = listOf("barbell_bench_press", "lat_pulldown_machine").map { id ->
            WorkoutEntry("e$id", id, listOf(WorkoutSet("s$id", weightKg = 50.0, reps = 10, done = true, doneAt = start + minutes * 60_000L)))
        },
    )

    private fun TestScope.collect(): MutableList<MusicSuggestions?> {
        val out = mutableListOf<MusicSuggestions?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            suggestionsFlow(enabled, loaded, workouts, exercises, clock) { zone }.toList(out)
        }
        return out
    }

    @Test
    fun aNewDayBringsItsFiveOnlyOnce() = runTest {
        val out = collect()
        runCurrent()
        val first = out.single()!!
        assertEquals(monday, first.shownOn)
        assertEquals(TrainingDay.TRAINING_DAY, first.day)
        assertEquals(DailyPlaylists.pick(monday.toEpochDay(), TrainingDay.TRAINING_DAY), first.playlists)
        // The hourly ticks of the same day choose nothing again.
        clock.value = at(monday, 21)
        clock.value = at(monday, 23, 59)
        runCurrent()
        assertEquals(1, out.size)
        // Past midnight (Madrid, not UTC): Tuesday's five, none of Monday's.
        clock.value = at(tuesday, 0, 1)
        runCurrent()
        val second = out.last()!!
        assertEquals(2, out.size)
        assertEquals(tuesday, second.shownOn)
        assertEquals(DailyPlaylists.pick(tuesday.toEpochDay(), TrainingDay.TRAINING_DAY), second.playlists)
        assertTrue(second.playlists.none { it in first.playlists })
    }

    @Test
    fun aWorkoutPastMidnightKeepsItsPlaylists() = runTest {
        workouts.value = listOf(liveWorkout(start = at(monday, 23), minutes = 20))
        clock.value = at(monday, 23, 30)
        val out = collect()
        runCurrent()
        val before = out.last()!!
        assertEquals(TrainingDay.STRENGTH, before.day)
        clock.value = at(tuesday, 0, 30)
        runCurrent()
        val after = out.last()!!
        // Shown on Tuesday (no longer hidden as yesterday's), still Monday's strength five.
        assertEquals(tuesday, after.shownOn)
        assertEquals(TrainingDay.STRENGTH, after.day)
        assertEquals(before.playlists, after.playlists)
        assertEquals(DailyPlaylists.pick(monday.toEpochDay(), TrainingDay.STRENGTH), after.playlists)
    }

    @Test
    fun aWorkoutLeftOpenForDaysDecidesNothing() = runTest {
        workouts.value = listOf(liveWorkout(start = at(monday.minusDays(3), 18), minutes = 30))
        val out = collect()
        runCurrent()
        val today = out.single()!!
        assertEquals(TrainingDay.TRAINING_DAY, today.day)
        assertEquals(DailyPlaylists.pick(monday.toEpochDay(), TrainingDay.TRAINING_DAY), today.playlists)
    }

    @Test
    fun nothingWhileTheWorkoutsLoadOrWithTheSuggestionsOff() = runTest {
        loaded.value = false
        val out = collect()
        runCurrent()
        assertNull(out.single())
        loaded.value = true
        runCurrent()
        assertEquals(monday, out.last()?.shownOn)
        enabled.value = false
        runCurrent()
        assertNull(out.last())
    }
}
