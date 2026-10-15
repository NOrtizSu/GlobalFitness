package com.enjoyyourtime.globalfitness.data

import android.content.Context
import androidx.core.content.edit
import com.enjoyyourtime.globalfitness.scale.ConfiguredScale
import com.enjoyyourtime.globalfitness.scale.ConfiguredScaleCodec
import com.enjoyyourtime.globalfitness.scale.drivers.MiScale2Driver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/** Small key/value settings backed by SharedPreferences and mirrored in StateFlows. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _selectedProfileId = MutableStateFlow(prefs.getLong(KEY_SELECTED, -1L).takeIf { it >= 0 })

    /**
     * The profile chosen in multi-profile versions. The app now has a single profile; this only
     * decides which one is kept when upgrading a database that still has several.
     */
    val selectedProfileId: StateFlow<Long?> = _selectedProfileId.asStateFlow()

    private val _configuredScales = MutableStateFlow(loadConfiguredScales())

    /** Scales set up by the user or by auto-detection (empty = auto-detect the first one). */
    val configuredScales: StateFlow<List<ConfiguredScale>> = _configuredScales.asStateFlow()

    private val _dismissedAutoConfig = MutableStateFlow(prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty().toSet())

    /** Addresses the user removed after auto-configuration: never auto-configured again. */
    val dismissedAutoConfig: StateFlow<Set<String>> = _dismissedAutoConfig.asStateFlow()

    private val _permissionRationaleSeen = MutableStateFlow(prefs.getBoolean(KEY_RATIONALE_SEEN, false))

    /** True once Android has asked us to show a rationale for the Bluetooth permission. */
    val permissionRationaleSeen: StateFlow<Boolean> = _permissionRationaleSeen.asStateFlow()

    fun markPermissionRationaleSeen() {
        if (_permissionRationaleSeen.value) return
        _permissionRationaleSeen.value = true
        prefs.edit { putBoolean(KEY_RATIONALE_SEEN, true) }
    }

    fun setSelectedProfileId(id: Long?) {
        _selectedProfileId.value = id
        prefs.edit { if (id == null) remove(KEY_SELECTED) else putLong(KEY_SELECTED, id) }
    }

    fun setConfiguredScales(list: List<ConfiguredScale>) {
        _configuredScales.value = list
        prefs.edit { putString(KEY_SCALES, ConfiguredScaleCodec.encode(list)) }
    }

    /** Applies [transform] to the current list atomically (single-threaded callers). */
    fun updateConfiguredScales(transform: (List<ConfiguredScale>) -> List<ConfiguredScale>) {
        setConfiguredScales(transform(_configuredScales.value))
    }

    fun dismissAutoConfig(address: String) {
        val updated = _dismissedAutoConfig.value + address.uppercase(Locale.ROOT)
        _dismissedAutoConfig.value = updated
        prefs.edit { putStringSet(KEY_DISMISSED, updated) }
    }

    /**
     * Configured scales; on first run after the multi-scale update a scale pinned in the old
     * "Báscula vinculada" setting becomes a configured Mi Body Composition Scale 2.
     */
    private fun loadConfiguredScales(): List<ConfiguredScale> {
        val stored = prefs.getString(KEY_SCALES, null)
        if (stored != null) return ConfiguredScaleCodec.decode(stored)
        val pinned = prefs.getString(KEY_LEGACY_PINNED_MAC, null) ?: return emptyList()
        val migrated = listOf(
            ConfiguredScale(
                id = UUID.randomUUID().toString(),
                driverId = MiScale2Driver.ID,
                address = pinned,
                name = "Mi Body Composition Scale 2",
                addedAt = System.currentTimeMillis(),
            ),
        )
        prefs.edit {
            putString(KEY_SCALES, ConfiguredScaleCodec.encode(migrated))
            remove(KEY_LEGACY_PINNED_MAC)
        }
        return migrated
    }

    companion object {
        private const val KEY_SELECTED = "selected_profile_id"
        private const val KEY_LEGACY_PINNED_MAC = "pinned_scale_mac"
        private const val KEY_SCALES = "configured_scales"
        private const val KEY_DISMISSED = "dismissed_auto_config"
        private const val KEY_RATIONALE_SEEN = "ble_permission_rationale_seen"
    }
}
