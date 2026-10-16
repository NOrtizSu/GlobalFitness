package com.enjoyyourtime.globalfitness.wearable

import android.content.Context
import androidx.core.content.edit
import com.enjoyyourtime.globalfitness.scale.DriverStore
import com.enjoyyourtime.globalfitness.scale.MapDriverStore
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Non-secret wearable settings in the private `wearable_prefs` preferences: configured devices,
 * dismissed suggestions, notification forwarding and each device's small driver store. Keys are
 * in [WearableKeyStore], never here.
 */
class WearableConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("wearable_prefs", Context.MODE_PRIVATE)

    private val _configured = MutableStateFlow(ConfiguredWearableCodec.decode(prefs.getString(KEY_CONFIGURED, null)))
    val configured: StateFlow<List<ConfiguredWearable>> = _configured.asStateFlow()

    private val _dismissed = MutableStateFlow(prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty().toSet())
    val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()

    private val _forwardingEnabled = MutableStateFlow(prefs.getBoolean(KEY_FORWARDING, false))
    val forwardingEnabled: StateFlow<Boolean> = _forwardingEnabled.asStateFlow()

    private val _allowedPackages = MutableStateFlow(prefs.getStringSet(KEY_ALLOWED, emptySet()).orEmpty().toSet())
    val allowedPackages: StateFlow<Set<String>> = _allowedPackages.asStateFlow()

    @Synchronized
    fun updateConfigured(transform: (List<ConfiguredWearable>) -> List<ConfiguredWearable>) {
        val updated = transform(_configured.value)
        prefs.edit { putString(KEY_CONFIGURED, ConfiguredWearableCodec.encode(updated)) }
        _configured.value = updated
    }

    @Synchronized
    fun dismiss(address: String) {
        val updated = _dismissed.value + address.uppercase(Locale.ROOT)
        prefs.edit { putStringSet(KEY_DISMISSED, updated) }
        _dismissed.value = updated
    }

    @Synchronized
    fun undismiss(address: String) {
        val updated = _dismissed.value - address.uppercase(Locale.ROOT)
        prefs.edit { putStringSet(KEY_DISMISSED, updated) }
        _dismissed.value = updated
    }

    fun setForwardingEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_FORWARDING, enabled) }
        _forwardingEnabled.value = enabled
    }

    @Synchronized
    fun setPackageAllowed(packageName: String, allowed: Boolean) {
        _allowedPackages.update { if (allowed) it + packageName else it - packageName }
        prefs.edit { putStringSet(KEY_ALLOWED, _allowedPackages.value) }
    }

    /** The persisted small store of device [deviceId] (alarm copies...). */
    fun driverStore(deviceId: String): DriverStore {
        val key = KEY_STORE_PREFIX + deviceId
        return MapDriverStore(decodeMap(prefs.getString(key, null))) { snapshot ->
            prefs.edit { putString(key, encodeMap(snapshot)) }
        }
    }

    fun clearDriverStore(deviceId: String) = prefs.edit { remove(KEY_STORE_PREFIX + deviceId) }

    companion object {
        private const val KEY_CONFIGURED = "configured_wearables"
        private const val KEY_DISMISSED = "dismissed_suggestions"
        private const val KEY_FORWARDING = "notification_forwarding"
        private const val KEY_ALLOWED = "forwarding_allowed_packages"
        private const val KEY_STORE_PREFIX = "driver_store_"

        fun encodeMap(map: Map<String, String>): String =
            map.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

        fun decodeMap(text: String?): Map<String, String> {
            if (text.isNullOrEmpty()) return emptyMap()
            return text.split('&').mapNotNull { pair ->
                val i = pair.indexOf('=')
                if (i <= 0) {
                    null
                } else {
                    runCatching { URLDecoder.decode(pair.substring(0, i), "UTF-8") to URLDecoder.decode(pair.substring(i + 1), "UTF-8") }.getOrNull()
                }
            }.toMap()
        }
    }
}
