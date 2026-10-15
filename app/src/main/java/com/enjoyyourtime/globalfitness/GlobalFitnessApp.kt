package com.enjoyyourtime.globalfitness

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.enjoyyourtime.globalfitness.band.BandManager
import com.enjoyyourtime.globalfitness.account.AccountManager
import com.enjoyyourtime.globalfitness.ads.AdSuppression
import com.enjoyyourtime.globalfitness.ads.AdsManager
import com.enjoyyourtime.globalfitness.billing.BillingRepository
import com.enjoyyourtime.globalfitness.ble.LiveScale
import com.enjoyyourtime.globalfitness.ble.ScaleMonitor
import com.enjoyyourtime.globalfitness.ble.ScaleScanner
import com.enjoyyourtime.globalfitness.cloud.CloudSyncEngine
import com.enjoyyourtime.globalfitness.cloud.RepositoryBackupAdapter
import com.enjoyyourtime.globalfitness.data.AppDatabase
import com.enjoyyourtime.globalfitness.data.Repository
import com.enjoyyourtime.globalfitness.data.SettingsStore
import com.enjoyyourtime.globalfitness.domain.MeasurementRecorder
import com.enjoyyourtime.globalfitness.health.HealthModule
import com.enjoyyourtime.globalfitness.domain.RecordOutcome
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** Manual dependency container (no DI library). */
class AppContainer(context: Context) {
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.e("GlobalFitness", "Unhandled error in application scope", e)
    }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashGuard)
    val settings = SettingsStore(context)
    val database = AppDatabase(context)
    val repository = Repository(database, appScope)
    val scanner = ScaleScanner(context, settings, appScope)
    val recorder = MeasurementRecorder(repository, settings)
    val scaleMonitor = ScaleMonitor(
        scanner = scanner,
        recorder = recorder,
        scope = appScope,
        wallClock = System::currentTimeMillis,
        elapsedClock = SystemClock::elapsedRealtime,
    )

    /** Experimental direct Xiaomi Smart Band 9 mode; created on first use. */
    val band by lazy { BandManager(context.applicationContext, appScope) }
    val health = HealthModule(context, repository, appScope)
    // ---- Optional Google account + cloud backup, ads and the "Quitar anuncios" purchase --------
    val adSuppression = AdSuppression()
    val billing = BillingRepository(context, appScope, adSuppression)
    val account = AccountManager(context)
    val cloudSync = CloudSyncEngine(context, appScope, account, RepositoryBackupAdapter(repository, settings), billing.isPremium)
    val ads = AdsManager(
        application = context.applicationContext as Application,
        scope = appScope,
        isPremium = billing.isPremium,
        suppression = adSuppression,
        measurementSaved = scaleMonitor.outcomes.filter { it !is RecordOutcome.Duplicate }.map { },
        scaleBusy = scaleMonitor.live.map { it is LiveScale.Measuring || it is LiveScale.Stabilized },
    )
}

class GlobalFitnessApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(container.health)
    }
}
