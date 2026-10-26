package com.enjoyyourtime.globalfitness.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.training.data.TrainingBatch
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max
import kotlin.random.Random

/**
 * Debug-only test helper: adds a deterministic push / pull / legs history (six evening workouts a
 * week, every seventh day back from today a rest day, so each muscle group gets two sessions a week
 * and the weekly sets reach their ranges; slow progression, an assisted pull-up machine whose
 * assistance goes down, abs and a treadmill finish on leg days, and a workout yesterday and today so
 * recovery shows), to check the training screens and the body map on an emulator.
 *
 *     adb shell am broadcast -n com.enjoyyourtime.globalfitness/.debug.DebugTrainingSeedReceiver --ei weeks 8
 *
 * Options for the store screenshots (docs/store-assets): `--ez replace true` deletes every workout
 * first; `--ez live true` leaves today's push workout in progress (see [live]); `--ez named false`
 * leaves the workouts unnamed, so the app titles them by their muscles in the user's language.
 */
class DebugTrainingSeedReceiver : BroadcastReceiver() {

    private data class Plan(val exerciseId: String, val sets: Int, val reps: Int, val startKg: Double?, val stepKgPerWeek: Double)

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = (context.applicationContext as GlobalFitnessApp).container
        val repo = container.training.repository
        val weeks = intent.getIntExtra("weeks", 8).coerceIn(1, 52)
        val replace = intent.getBooleanExtra("replace", false)
        val liveToday = intent.getBooleanExtra("live", false)
        val named = intent.getBooleanExtra("named", true)
        run {
            container.appScope.launch {
                try {
                    repo.awaitLoaded()
                    val random = Random(7)
                    val now = System.currentTimeMillis()
                    val days = weeks * 7
                    val workouts = ArrayList<Workout>()
                    // Back from today: push, pull, legs in turn, skipping every seventh day (rest).
                    val trainingDays = (0..days).filter { it % 7 != 6 }
                    for ((index, d) in trainingDays.withIndex().reversed()) {
                        val dayStart = now - d * DAY_MS - 2 * HOUR_MS + random.nextLong(HOUR_MS)
                        val week = (days - d) / 7.0
                        val dayKind = index % 3
                        val plans = when (dayKind) {
                            PUSH -> PUSH_DAY
                            PULL -> PULL_DAY
                            else -> LEGS_DAY
                        }
                        val name = NAMES[dayKind].takeIf { named }
                        workouts += if (d == 0 && liveToday) live(now, name, plans, week) else workout(dayStart, name, plans, week, random)
                    }
                    val removed = if (replace) repo.workouts.value.map { it.id } else emptyList()
                    repo.applyRemote(TrainingBatch(upsertWorkouts = workouts, removeWorkouts = removed))
                    Log.i(TAG, "Seeded ${workouts.size} workouts, removed ${removed.size}")
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private fun workout(start: Long, name: String?, plans: List<Plan>, week: Double, random: Random): Workout {
        var t = start
        val entries = plans.map { p ->
            // A good or a bad day: a repetition more or less than planned, so the charts are not straight lines.
            val form = random.nextInt(-1, 2)
            val sets = (0 until p.sets).map { i ->
                t += 2 * MINUTE_MS + random.nextLong(MINUTE_MS)
                if (p.exerciseId == "treadmill") {
                    WorkoutSet(id(), durationSec = 900, distanceM = 2_000.0 + week * 40, level = 6, kcal = 160, done = true, doneAt = t)
                } else {
                    WorkoutSet(id(), weightKg = load(p, week), reps = p.reps + form - (if (i == p.sets - 1) 1 else 0), done = true, doneAt = t)
                }
            }
            WorkoutEntry(id(), p.exerciseId, sets)
        }
        return Workout(id(), start, t + 5 * MINUTE_MS, name, null, t + 5 * MINUTE_MS, entries)
    }

    /**
     * Today's workout in progress, started 35 minutes ago, a step heavier than last time (personal
     * records): the first two exercises done, the third with one set done and the next one's
     * weight typed but not its repetitions (marking it done fills them from last time and starts
     * the rest timer), the other sets empty (their fields show last time's values).
     */
    private fun live(now: Long, name: String?, plans: List<Plan>, week: Double): Workout {
        val start = now - 35 * MINUTE_MS
        var t = start
        val entries = plans.mapIndexed { index, p ->
            val sets = (0 until p.sets).map { i ->
                when {
                    index < 2 || (index == 2 && i == 0) -> {
                        t += 3 * MINUTE_MS
                        WorkoutSet(id(), weightKg = load(p, week + 1), reps = p.reps, done = true, doneAt = t)
                    }
                    index == 2 && i == 1 -> WorkoutSet(id(), weightKg = load(p, week + 1))
                    else -> WorkoutSet(id())
                }
            }
            WorkoutEntry(id(), p.exerciseId, sets)
        }
        return Workout(id(), start, null, name, null, t, entries)
    }

    /** The plan's weight after [week] weeks, rounded to the 1.25 kg plates. */
    private fun load(p: Plan, week: Double): Double? = p.startKg?.let { max(0.0, Math.round((it + p.stepKgPerWeek * week) * 0.8) / 0.8) }

    private fun id() = UUID.randomUUID().toString()

    private companion object {
        const val TAG = "DebugTrainingSeed"
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
        const val PUSH = 0
        const val PULL = 1
        val NAMES = listOf("Push", "Pull", "Legs")

        val PUSH_DAY = listOf(
            Plan("barbell_bench_press", 4, 8, 60.0, 1.25),
            Plan("machine_shoulder_press", 3, 10, 35.0, 1.0),
            Plan("pec_deck", 3, 12, 40.0, 0.75),
            Plan("machine_lateral_raise", 3, 12, 20.0, 0.5),
            Plan("rope_triceps_pushdown", 3, 12, 22.5, 0.5),
        )
        val PULL_DAY = listOf(
            Plan("assisted_pull_up_machine", 3, 8, 40.0, -2.5),
            Plan("lat_pulldown_machine", 3, 10, 50.0, 1.25),
            Plan("seated_cable_row", 3, 10, 45.0, 1.25),
            Plan("face_pull", 3, 15, 15.0, 0.5),
            Plan("barbell_curl", 3, 10, 25.0, 0.5),
        )
        val LEGS_DAY = listOf(
            Plan("barbell_back_squat", 4, 6, 70.0, 2.0),
            Plan("leg_press_machine", 3, 10, 120.0, 5.0),
            Plan("leg_extension_machine", 3, 12, 40.0, 1.0),
            Plan("lying_leg_curl_machine", 3, 12, 35.0, 1.0),
            Plan("standing_calf_raise_machine", 3, 15, 50.0, 1.5),
            Plan("cable_crunch", 3, 15, 30.0, 0.5),
            Plan("treadmill", 1, 0, null, 0.0),
        )
    }
}
