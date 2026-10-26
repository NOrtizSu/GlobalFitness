package com.enjoyyourtime.globalfitness.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.training.domain.Achievement
import kotlinx.coroutines.launch

/**
 * Debug-only test helper: shows the celebration of an achievement the user already reached, e.g. for
 * the store screenshots, through the real path (no rule of the app is bypassed):
 *
 * 1. every workout is saved again on this phone (a new `updatedAt`), so the levels count as news of
 *    this phone ([com.enjoyyourtime.globalfitness.training.domain.Celebrations]);
 * 2. the achievement's levels are taken out of the celebration ledger (the preferences of
 *    [com.enjoyyourtime.globalfitness.training.data.CelebrationStore]), and the ledger's baseline is
 *    moved before those saves (a clock put back for the screenshots may be earlier than the stored one).
 *
 * The open app then celebrates the highest level reached of that achievement (every other level was
 * handled already) — once no workout is in progress, which holds celebrations.
 *
 *     adb shell am broadcast -n com.enjoyyourtime.globalfitness/.debug.DebugCelebrationReceiver --es achievement bench_bw
 */
class DebugCelebrationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val achievement = Achievement.fromKey(intent.getStringExtra("achievement").orEmpty())
        if (achievement == null) {
            Log.w(TAG, "Unknown achievement: use one of ${Achievement.entries.joinToString { it.key }}")
            return
        }
        val pending = goAsync()
        val container = (context.applicationContext as GlobalFitnessApp).container
        val repo = container.training.repository
        container.appScope.launch {
            try {
                repo.awaitLoaded()
                val prefs = context.getSharedPreferences(LEDGER, Context.MODE_PRIVATE)
                if (!prefs.contains(BASELINE)) {
                    // Without a first evaluation every level reached would be celebrated at once.
                    Log.w(TAG, "Open the app once first (the first evaluation files every level silently)")
                    return@launch
                }
                val start = System.currentTimeMillis()
                val workouts = repo.workouts.value.sortedBy { it.startedAt }
                if (workouts.isEmpty()) {
                    Log.w(TAG, "No workouts: seed a training history first")
                    return@launch
                }
                // Touched while the levels are still handled (nothing shows yet), then released at once
                // by one more change, so the celebration starts when this receiver logs.
                // (The repository stamps a changed workout with the time of the save.)
                workouts.forEach { w -> repo.updateWorkout(w.id) { it.copy(updatedAt = it.updatedAt + 1) } }
                val handled = prefs.getStringSet(HANDLED, null).orEmpty().filterNot { it.startsWith("${achievement.key}:") }.toSet()
                prefs.edit {
                    putStringSet(HANDLED, handled)
                    putLong(BASELINE, minOf(prefs.getLong(BASELINE, start), start - MINUTE_MS))
                }
                repo.updateWorkout(workouts.last().id) { it.copy(updatedAt = it.updatedAt + 1) }
                Log.i(TAG, "Celebrating ${achievement.key} (${workouts.size} workouts touched)")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugCelebration"
        const val MINUTE_MS = 60_000L

        // The preferences of CelebrationStore (private there).
        const val LEDGER = "achievements"
        const val HANDLED = "celebrated"
        const val BASELINE = "baseline_at"
    }
}
