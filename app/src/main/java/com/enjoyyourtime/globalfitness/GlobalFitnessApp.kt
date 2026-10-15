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
import com.enjoyyourtime.globalfitness.scale.SessionStatus
import com.enjoyyourtime.globalfitness.domain.RecordOutcome
import com.enjoyyourtime.globalfitness.scale.GattSession
import com.enjoyyourtime.globalfitness.scale.ScaleContext
import com.enjoyyourtime.globalfitness.scale.drivers.AllDrivers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

    /** Experimental direct Xiaomi Smart Band 9 mode; created on first use. */
    val band by lazy {
        BandManager(context.applicationContext, appScope).also { manager ->
            // No interstitials while the band streams live heart rate.
            appScope.launch {
                manager.state.map { it.liveActive }.distinctUntilChanged()
                    .collect { adSuppression.set(AdSuppression.BAND_LIVE_HEART_RATE, it) }
            }
        }
    }
    val health = HealthModule(context, repository, appScope)
    // ---- Optional Google account + cloud backup, ads and the "Quitar anuncios" purchase --------
    val adSuppression = AdSuppression()
    val billing = BillingRepository(context, appScope, adSuppression)
    val account = AccountManager(context)
    val cloudSync = CloudSyncEngine(context, appScope, account, RepositoryBackupAdapter(repository, settings), billing.isPremium)
    val onboarding = OnboardingStore(context)
    val ads = AdsManager(
        application = context.applicationContext as Application,
        scope = appScope,
        premium = billing.isPremium,
        entitlementKnown = billing.entitlementKnown,
        suppression = adSuppression,
        measurementSaved = scaleMonitor.outcomes.filter { it !is RecordOutcome.Duplicate }.map { },
        // A passive weighing in progress or any GATT scale session that has not finished.
        scaleBusy = combine(scaleMonitor.live, scaleMonitor.sessions) { live, sessions ->
            live is LiveScale.Connecting || live is LiveScale.Measuring || live is LiveScale.Stabilized ||
                sessions.values.any { it.status in BUSY_SESSION_STATES }
        },
        onboardingCompleted = onboarding.completed,
    )

    private companion object {
        val BUSY_SESSION_STATES = setOf(SessionStatus.CONNECTING, SessionStatus.CONNECTED, SessionStatus.MEASURING)
    }
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
