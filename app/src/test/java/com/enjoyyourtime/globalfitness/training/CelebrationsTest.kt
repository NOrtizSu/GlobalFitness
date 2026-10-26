package com.enjoyyourtime.globalfitness.training

import com.enjoyyourtime.globalfitness.data.Sex
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.data.CelebrationLedger
import com.enjoyyourtime.globalfitness.training.data.CelebrationTracker
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.domain.Achievement
import com.enjoyyourtime.globalfitness.training.domain.AchievementBoard
import com.enjoyyourtime.globalfitness.training.domain.AchievementCategory
import com.enjoyyourtime.globalfitness.training.domain.AchievementInput
import com.enjoyyourtime.globalfitness.training.domain.AchievementState
import com.enjoyyourtime.globalfitness.training.domain.Achievements
import com.enjoyyourtime.globalfitness.training.domain.Celebration
import com.enjoyyourtime.globalfitness.training.domain.Celebrations
import com.enjoyyourtime.globalfitness.training.domain.GoalAnchor
import com.enjoyyourtime.globalfitness.training.domain.TierUnlock
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneOffset

class CelebrationsTest {
    private val day = 86_400_000L
    private val now = 400 * day

    private class FakeLedger : CelebrationLedger {
        var baseline: Long? = null
        val keys = LinkedHashSet<String>()
        var anchor: GoalAnchor? = null

        override fun baselineAt(): Long? = baseline

        override fun handled(): Set<String> = keys.toSet()

        override fun setBaseline(at: Long) {
            if (baseline == null) baseline = at
        }

        override fun addHandled(keys: Collection<String>) {
            this.keys += keys
        }

        override fun goalAnchor(): GoalAnchor? = anchor

        override fun setGoalAnchor(anchor: GoalAnchor) {
            this.anchor = anchor
        }
    }

    private fun board(vararg states: AchievementState) = AchievementBoard(states.toList())

    /** [a] with its first [level] levels reached at [at] by data changed at [touched]. */
    private fun reached(a: Achievement, level: Int, at: Long, touched: Long = at) =
        AchievementState(
            a,
            a.thresholds(Sex.MALE),
            (1..level).map { TierUnlock(it, at, touched) },
            progress = if (level == 0) 0.0 else a.thresholds(Sex.MALE)[level - 1],
        )

    private fun workouts(level: Int, at: Long, touched: Long) = reached(Achievement.WORKOUTS, level, at, touched)

    private val CelebrationTracker.keys get() = pending.value.map { it.key }

    // ---- reconcile -------------------------------------------------------------------------

    @Test
    fun theFirstEvaluationFilesEverythingSilently() {
        val outcome = Celebrations.reconcile(board(workouts(3, now - day, now - day)), baselineAt = null, handled = emptySet(), now = now)
        assertEquals(now, outcome.baselineAt)
        assertTrue(outcome.show.isEmpty())
        assertEquals(setOf("workouts:1", "workouts:2", "workouts:3"), outcome.silent)
    }

    @Test
    fun aFreshLevelIsCelebratedOnce() {
        val handled = setOf("workouts:1", "workouts:2")
        val b = board(workouts(3, now - 60_000, now - 60_000))
        val first = Celebrations.reconcile(b, baselineAt = now - 30 * day, handled = handled, now = now)
        assertEquals(listOf("workouts:3"), first.show.map { it.key })
        assertEquals(10.0, first.show.single().threshold, 0.0)
        assertTrue(first.silent.isEmpty())
        // Once handled it never comes back, whatever happens to the data.
        val again = Celebrations.reconcile(b, baselineAt = now - 30 * day, handled = handled + first.show.map { it.key }, now = now + 1)
        assertTrue(again.show.isEmpty())
        assertTrue(again.silent.isEmpty())
    }

    @Test
    fun historyArrivingLateIsFiledSilently() {
        val baseline = now - day
        // Restored workouts keep the change times of the phone they come from: before the baseline.
        val restored = Celebrations.reconcile(board(workouts(2, now - 90 * day, baseline - 1)), baseline, emptySet(), now)
        assertTrue(restored.show.isEmpty())
        assertEquals(setOf("workouts:1", "workouts:2"), restored.silent)
        // After the baseline but older than the fresh window (e.g. a phone left off for days): silent too.
        val stale = Celebrations.reconcile(board(workouts(1, now - 10 * day, now - 3 * day)), now - 30 * day, emptySet(), now)
        assertTrue(stale.show.isEmpty())
        assertEquals(setOf("workouts:1"), stale.silent)
    }

    @Test
    fun severalLevelsOfOneAchievementShowTheHighest() {
        val volume = AchievementState(
            Achievement.VOLUME,
            Achievement.VOLUME.thresholds(Sex.MALE),
            listOf(TierUnlock(1, now - 10, now - 10), TierUnlock(2, now - 5, now - 5)),
            progress = 12_000.0,
        )
        val outcome = Celebrations.reconcile(board(workouts(1, now - 3, now - 3), volume), now - day, emptySet(), now)
        assertEquals(listOf("workouts:1", "volume:2"), outcome.show.map { it.key })
        // The lower level is settled when the higher one is shown, not before.
        assertTrue(outcome.silent.isEmpty())
        assertEquals(listOf("volume:1", "volume:2"), outcome.show.last().keys)
    }

    @Test
    fun aFloodOfOldLevelsIsFiledSilently() {
        val baseline = now - 30 * day
        // Six achievements at once, reached months ago, with a recent change time (e.g. a history
        // brought in by an edit): history, not news. Only the level reached today is shown.
        val old = listOf(Achievement.WORKOUTS, Achievement.VOLUME, Achievement.RECORDS, Achievement.EXERCISE_VARIETY, Achievement.CARDIO_DISTANCE, Achievement.PULL_UPS)
            .map { reached(it, 1, at = now - 90 * day, touched = now - 60_000) }
        val today = reached(Achievement.WEEK_STREAK, 1, at = now - 60_000)
        val flood = Celebrations.reconcile(AchievementBoard(old + today), baseline, emptySet(), now)
        assertEquals(listOf("week_streak:1"), flood.show.map { it.key })
        assertEquals(old.map { it.key(1) }.toSet(), flood.silent)
        // A first workout that unlocks as many is celebrated whole.
        val first = Achievement.entries.filter { it.category != AchievementCategory.BODY }.take(Celebrations.FLOOD_LIMIT + 2)
            .map { reached(it, 1, at = now - 60_000) }
        assertEquals(first.size, Celebrations.reconcile(AchievementBoard(first), baseline, emptySet(), now).show.size)
    }

    @Test
    fun weightGoalLevelsAreKeyedOnTheTarget() {
        val c = Celebration(Achievement.GOAL_PROGRESS, 2, 3.0, now, losing = true, targetKg = 78.5)
        assertEquals("goal_progress:2@785", c.key)
        assertEquals(listOf("goal_progress:1@785", "goal_progress:2@785"), c.keys)
        assertEquals("goal:1@750", Achievements.key(Achievement.GOAL_REACHED, 1, 75.0))
        // Other achievements ignore it.
        assertEquals("workouts:1", Achievements.key(Achievement.WORKOUTS, 1, 75.0))
    }

    // ---- tracker ---------------------------------------------------------------------------

    @Test
    fun aHigherLevelReplacesTheOneWaiting() {
        val ledger = FakeLedger().apply { baseline = now - 30 * day }
        val tracker = CelebrationTracker(ledger) { now }
        tracker.onBoard(board(reached(Achievement.RECORDS, 1, now - 120_000)))
        assertEquals(listOf("records:1"), tracker.keys)
        // Another record while it waits (e.g. behind a workout): one card, with the highest.
        tracker.onBoard(board(reached(Achievement.RECORDS, 2, now - 60_000)))
        assertEquals(listOf("records:2"), tracker.keys)
        assertTrue(ledger.keys.isEmpty())
        tracker.shown(tracker.pending.value)
        assertEquals(setOf("records:1", "records:2"), ledger.keys)
        tracker.dismiss(tracker.pending.value)
        assertTrue(tracker.pending.value.isEmpty())
        // The lower level never comes back on its own, after a restart either.
        val restarted = CelebrationTracker(ledger) { now + 60_000 }
        restarted.onBoard(board(reached(Achievement.RECORDS, 2, now - 60_000)))
        assertTrue(restarted.pending.value.isEmpty())
    }

    @Test
    fun aLevelThatDisappearsBeforeItShowsIsNeitherShownNorFiled() {
        val ledger = FakeLedger().apply { baseline = now - 30 * day }
        val tracker = CelebrationTracker(ledger) { now }
        // 1 000 kg typed instead of 100 on the bench press: four levels at once, held during the workout.
        tracker.onBoard(board(reached(Achievement.BENCH_PRESS, 4, now - 60_000)))
        assertEquals(listOf("bench_bw:4"), tracker.keys)
        // Corrected: the first level only. It waits instead; nothing was filed.
        tracker.onBoard(board(reached(Achievement.BENCH_PRESS, 1, now - 30_000)))
        assertEquals(listOf("bench_bw:1"), tracker.keys)
        assertTrue(ledger.keys.isEmpty())
        // The set deleted: nothing left to celebrate, and the levels can still be earned later.
        tracker.onBoard(board(reached(Achievement.BENCH_PRESS, 0, now)))
        assertTrue(tracker.pending.value.isEmpty())
        assertTrue(ledger.keys.isEmpty())
        tracker.onBoard(board(reached(Achievement.BENCH_PRESS, 2, now)))
        assertEquals(listOf("bench_bw:2"), tracker.keys)
    }

    // ---- with the real rules ---------------------------------------------------------------

    private val catalog = TrainingRepository.buildExercises(ExerciseCatalog.all, emptyList(), emptyMap())
    private val lookup: (String) -> Exercise = { catalog[it] ?: TrainingRepository.unknownExercise(it) }
    private var ids = 0

    private fun workout(start: Long, updatedAt: Long = start + 3_600_000) = Workout(
        "w${ids++}", start, start + 3_600_000, null, null, updatedAt,
        listOf(WorkoutEntry("e${ids++}", "barbell_bench_press", listOf(WorkoutSet("s${ids++}", 40.0, 10, done = true, doneAt = start + 60_000)))),
    )

    private fun evaluate(workouts: List<Workout>, at: Long = now, remote: List<Workout> = emptyList()) = Achievements.evaluate(
        AchievementInput(
            workouts, lookup, emptyList(), Sex.MALE, 30, null, ZoneOffset.UTC, DayOfWeek.MONDAY, at,
            remoteVersions = remote.associate { it.id to it.updatedAt },
        ),
    )

    @Test
    fun anotherPhonesWorkoutIsNotCelebratedHere() {
        val ledger = FakeLedger()
        val tracker = CelebrationTracker(ledger) { now }
        val three = (0 until 3).map { workout(now - (20 - it) * day) }
        tracker.onBoard(evaluate(three))
        // The 4th and 5th were logged on the other phone today and arrive with the backup: it celebrated them.
        val synced = (0 until 2).map { workout(now - (3 - it) * 3_600_000, updatedAt = now - 60_000) }
        tracker.onBoard(evaluate(three + synced, remote = synced))
        assertTrue(tracker.pending.value.isEmpty())
        assertTrue("workouts:2" in ledger.keys)
        // This phone's own next workouts are news again: the 10th workout.
        val mine = (0 until 5).map { workout(now - 60 * 60_000 + it, updatedAt = now) }
        tracker.onBoard(evaluate(three + synced + mine, remote = synced))
        assertEquals(listOf("workouts:3"), tracker.keys)
    }

    @Test
    fun aRestoreDoesNotCelebrateButTheNextWorkoutDoes() {
        val ledger = FakeLedger()
        var clock = now
        val tracker = CelebrationTracker(ledger) { clock }
        // A new phone: nothing yet when the app first evaluates.
        tracker.onBoard(evaluate(emptyList()))
        assertEquals(now, ledger.baseline)
        // The backup brings 24 old workouts a few minutes later.
        val old = (0 until 24).map { workout(now - (100 - it) * day) }
        clock += 5 * 60_000
        tracker.onBoard(evaluate(old, clock))
        assertTrue(tracker.pending.value.isEmpty())
        assertTrue("workouts:3" in ledger.keys && "volume:1" in ledger.keys)
        // The 25th, finished today, is news: 25 workouts and 10 t lifted.
        clock += day
        tracker.onBoard(evaluate(old + workout(clock - 3_600_000, updatedAt = clock), clock))
        assertEquals(listOf("workouts:4", "volume:2"), tracker.pending.value.map { it.key })
    }

    @Test
    fun aWorkoutLoggedForAnEarlierDayIsNews() {
        val ledger = FakeLedger()
        val tracker = CelebrationTracker(ledger) { now }
        val four = (0 until 4).map { workout(now - (20 - it) * day) }
        tracker.onBoard(evaluate(four))
        // Logged today for a day before the others: it is not the 5th by date, but it made the 5th.
        tracker.onBoard(evaluate(four + workout(now - 30 * day, updatedAt = now)))
        assertEquals(listOf("workouts:2"), tracker.pending.value.map { it.key })
    }

    @Test
    fun aLevelWaitingToBeShownComesBackAfterTheAppIsKilled() {
        val ledger = FakeLedger().apply { baseline = now - 30 * day }
        val board = board(workouts(1, now - 60_000, now - 60_000))
        val tracker = CelebrationTracker(ledger) { now }
        tracker.onBoard(board)
        tracker.onBoard(board)
        // Queued once, however many boards arrive.
        assertEquals(listOf("workouts:1"), tracker.pending.value.map { it.key })
        // Killed before it showed: the next process queues it again.
        val restarted = CelebrationTracker(ledger) { now + 60_000 }
        restarted.onBoard(board)
        assertEquals(listOf("workouts:1"), restarted.pending.value.map { it.key })
        // Shown and closed: never again.
        restarted.shown(restarted.pending.value)
        restarted.dismiss(restarted.pending.value)
        assertTrue(restarted.pending.value.isEmpty())
        val later = CelebrationTracker(ledger) { now + 120_000 }
        later.onBoard(board)
        assertTrue(later.pending.value.isEmpty())
    }

    @Test
    fun levelsLostAndEarnedAgainAreNotCelebratedTwice() {
        val ledger = FakeLedger().apply { baseline = now - 30 * day }
        val tracker = CelebrationTracker(ledger) { now }
        tracker.onBoard(board(workouts(1, now - 60_000, now - 60_000)))
        tracker.dismiss(tracker.pending.value)
        // The workout is deleted (no level), then logged again.
        tracker.onBoard(board(AchievementState(Achievement.WORKOUTS, Achievement.WORKOUTS.thresholds(Sex.MALE), progress = 0.0)))
        tracker.onBoard(board(workouts(1, now, now)))
        assertTrue(tracker.pending.value.isEmpty())
    }
}
