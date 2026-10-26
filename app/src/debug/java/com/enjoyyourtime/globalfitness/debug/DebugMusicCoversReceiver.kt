package com.enjoyyourtime.globalfitness.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.training.music.DailyPlaylists
import com.enjoyyourtime.globalfitness.training.music.TrainingDay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * Debug-only test helper: loads (into the app's cover cache) the names and covers of the playlists
 * the music suggestions pick for a given day, through the app's own loader, e.g. for the store
 * screenshots. They run at a fixed clock in the past, before the start of Spotify's current TLS
 * certificate, where no cover can load: this receiver runs at the real time first, and the app then
 * shows the cached covers at the fixed clock. Needs the covers allowed (training preferences).
 *
 *     adb shell am broadcast -n com.enjoyyourtime.globalfitness/.debug.DebugMusicCoversReceiver --es date 2026-10-17 --es day STRENGTH
 *
 * Logs "Covers ready n/5".
 */
class DebugMusicCoversReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val date = intent.getStringExtra("date")?.let(LocalDate::parse)
        val day = TrainingDay.entries.firstOrNull { it.name == intent.getStringExtra("day") }
        if (date == null || day == null) {
            Log.w(TAG, "Use --es date yyyy-mm-dd --es day ${TrainingDay.entries.joinToString("|")}")
            return
        }
        val pending = goAsync()
        val container = (context.applicationContext as GlobalFitnessApp).container
        val covers = container.training.playlistCovers
        val ids = DailyPlaylists.pick(date.toEpochDay(), day).map { it.id }
        // The loader is used from the main thread (the app scope), as the screens do.
        container.appScope.launch {
            try {
                covers.request(ids)
                val art = withTimeoutOrNull(TIMEOUT_MS) { covers.art.first { m -> ids.all { m[it]?.cover != null } } } ?: covers.art.value
                Log.i(TAG, "Covers ready ${ids.count { art[it]?.cover != null }}/${ids.size}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugMusicCovers"
        const val TIMEOUT_MS = 45_000L
    }
}
