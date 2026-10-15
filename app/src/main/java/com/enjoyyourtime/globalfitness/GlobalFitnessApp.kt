package com.enjoyyourtime.globalfitness

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.enjoyyourtime.globalfitness.ble.ScaleMonitor
import com.enjoyyourtime.globalfitness.ble.ScaleScanner
import com.enjoyyourtime.globalfitness.data.AppDatabase
import com.enjoyyourtime.globalfitness.data.Repository
import com.enjoyyourtime.globalfitness.data.SettingsStore
import com.enjoyyourtime.globalfitness.domain.MeasurementRecorder
import com.enjoyyourtime.globalfitness.scale.GattSession
import com.enjoyyourtime.globalfitness.scale.ScaleContext
import com.enjoyyourtime.globalfitness.scale.drivers.AllDrivers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container (no DI library). */
class AppContainer(context: Context) {
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.e("GlobalFitness", "Unhandled error in application scope", e)
    }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashGuard)
    val settings = SettingsStore(context)
    val database = AppDatabase(context)
    val repository = Repository(database, appScope, preferredProfileId = { settings.selectedProfileId.value })
    val scanner = ScaleScanner(context, settings, appScope)
    val recorder = MeasurementRecorder(repository)
    val scaleRegistry = AllDrivers.registry()
    val scaleMonitor = ScaleMonitor(
        advertisements = scanner.advertisements,
        registry = scaleRegistry,
        settings = settings,
        recorder = recorder,
        contextProvider = {
            val last = repository.measurements.value.lastOrNull()
            ScaleContext.from(repository.profile.value, last?.weightKg, last?.impedance)
        },
        linkFactory = { address -> GattSession(context.applicationContext, address) },
        scope = appScope,
        wallClock = System::currentTimeMillis,
        elapsedClock = SystemClock::elapsedRealtime,
    )
}

class GlobalFitnessApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
