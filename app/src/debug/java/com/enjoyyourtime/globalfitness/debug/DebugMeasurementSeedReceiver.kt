package com.enjoyyourtime.globalfitness.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/**
 * Debug-only test helper: adds a deterministic weight history (Mi Scale 2 readings with
 * impedance, plus a few manual weights) to the current profile, to check the UI on an emulator.
 *
 *     adb shell am broadcast -n com.enjoyyourtime.globalfitness/.debug.DebugMeasurementSeedReceiver --ei days 120
 */
class DebugMeasurementSeedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = (context.applicationContext as GlobalFitnessApp).container
        val days = intent.getIntExtra("days", 120).coerceIn(1, 3650)
        container.appScope.launch {
            try {
                container.repository.awaitLoaded()
                if (container.repository.profile.value == null) {
                    Log.w(TAG, "No profile yet: finish the onboarding first")
                    return@launch
                }
                val random = Random(42)
                val now = System.currentTimeMillis()
                var added = 0
                for (d in days downTo 0 step 2) {
                    val t = now - d * DAY_MS - random.nextLong(6 * HOUR_MS)
                    val weight = 82.0 - (days - d) * 0.035 + sin(d / 9.0) * 0.6 + random.nextDouble(-0.25, 0.25)
                    val manual = d % 23 == 0
                    container.repository.insertMeasurement(
                        Measurement(
                            id = 0,
                            profileId = null,
                            timestamp = t,
                            weightKg = Math.round(weight * 20) / 20.0,
                            impedance = if (manual) null else 480 + random.nextInt(-25, 25),
                            scaleKey = if (manual) null else "debug-$d",
                            source = if (manual) MeasurementSource.MANUAL else MeasurementSource.SCALE,
                        ),
                    )
                    added++
                }
                Log.i(TAG, "Seeded $added measurements")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugMeasurementSeed"
        const val DAY_MS = 86_400_000L
        const val HOUR_MS = 3_600_000L
    }
}
