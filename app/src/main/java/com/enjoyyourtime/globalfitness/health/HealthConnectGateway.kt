package com.enjoyyourtime.globalfitness.health

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HcAvailability {
    AVAILABLE,

    /** Android 13 or lower without the Health Connect app. */
    NOT_INSTALLED,

    /** Installed but too old for this SDK. */
    UPDATE_REQUIRED,

    /** Android 8.x or a device where Health Connect cannot run. */
    NOT_SUPPORTED,
}

/** Health Connect availability, permissions and client access. */
class HealthConnectGateway(private val context: Context) {

    private val _availability = MutableStateFlow(HcAvailability.NOT_SUPPORTED)
    val availability: StateFlow<HcAvailability> = _availability.asStateFlow()

    private val _granted = MutableStateFlow<Set<String>>(emptySet())

    /** Health permissions currently granted (refreshed on resume and after each request). */
    val granted: StateFlow<Set<String>> = _granted.asStateFlow()

    private val _historyAvailable = MutableStateFlow(false)

    /** Whether this Health Connect version supports the "read history" permission. */
    val historyAvailable: StateFlow<Boolean> = _historyAvailable.asStateFlow()

    @Volatile
    private var cachedClient: HealthConnectClient? = null

    fun checkAvailability(): HcAvailability {
        val status = HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE)
        val result = when (status) {
            HealthConnectClient.SDK_AVAILABLE -> HcAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                if (isProviderInstalled()) HcAvailability.UPDATE_REQUIRED else HcAvailability.NOT_INSTALLED
            else -> HcAvailability.NOT_SUPPORTED
        }
        _availability.value = result
        return result
    }

    /** The client, or null when Health Connect is not usable right now. */
    fun client(): HealthConnectClient? {
        if (checkAvailability() != HcAvailability.AVAILABLE) return null
        return cachedClient ?: HealthConnectClient.getOrCreate(context, PROVIDER_PACKAGE).also { cachedClient = it }
    }

    /** Re-reads availability, feature support and granted permissions. Safe to call often. */
    suspend fun refresh(): Set<String> {
        val client = client()
        if (client == null) {
            _granted.value = emptySet()
            _historyAvailable.value = false
            return emptySet()
        }
        _historyAvailable.value = runCatching {
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }.getOrDefault(false)
        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptySet()
        }
        _granted.value = granted
        return granted
    }

    fun requestedPermissions(): Set<String> = HealthPermissions.requested(_historyAvailable.value)

    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract(PROVIDER_PACKAGE)

    /** Opens Health Connect's permission / data management screen for this app. */
    fun openSettings(): Boolean {
        val intents = buildList {
            add(HealthConnectClient.getHealthConnectManageDataIntent(context, PROVIDER_PACKAGE))
            add(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
        }
        return intents.any { launch(it) }
    }

    /** Play Store page to install or update Health Connect (Android 13 and lower). */
    fun openPlayStore(): Boolean {
        val market = Intent(Intent.ACTION_VIEW).apply {
            setPackage("com.android.vending")
            data = "market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding".toUri()
            putExtra("overlay", true)
            putExtra("callerId", context.packageName)
        }
        val web = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$PROVIDER_PACKAGE".toUri())
        return launch(market) || launch(web)
    }

    private fun launch(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun isProviderInstalled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(PROVIDER_PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(PROVIDER_PACKAGE, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Friendly name of a source app; uses the installed label when the package is visible. */
    fun originName(packageName: String): String? =
        HealthMappings.originName(packageName, context.packageName) { pkg ->
            try {
                val pm = context.packageManager
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkg, 0)
                }
                pm.getApplicationLabel(info).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
    }
}
