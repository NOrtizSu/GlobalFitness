package com.enjoyyourtime.globalfitness.health

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.enjoyyourtime.globalfitness.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Health Connect wiring for the app container. Registered as activity lifecycle callbacks so
 * every resume refreshes permissions and runs a throttled sync (at most every 5 minutes).
 */
class HealthModule(context: Context, repository: Repository, private val scope: CoroutineScope) :
    Application.ActivityLifecycleCallbacks {

    val cache = HealthCache(context)
    val settings = HealthSettings(context)
    val gateway = HealthConnectGateway(context)
    val sync = HealthSync(gateway, cache, settings, scope, ownPackage = context.packageName)
    val exporter = ScaleExporter(gateway, cache, settings, repository, scope)

    init {
        exporter.start()
    }

    /** Refreshes availability / permissions, then syncs if the last attempt is old enough. */
    fun onForeground() {
        scope.launch(Dispatchers.IO) {
            gateway.refresh()
            sync.syncIfStale()
        }
    }

    override fun onActivityResumed(activity: Activity) = onForeground()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
