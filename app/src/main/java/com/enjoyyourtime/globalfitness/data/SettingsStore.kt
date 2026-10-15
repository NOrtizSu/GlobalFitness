package com.enjoyyourtime.globalfitness.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small key/value settings backed by SharedPreferences and mirrored in StateFlows. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _selectedProfileId = MutableStateFlow(prefs.getLong(KEY_SELECTED, -1L).takeIf { it >= 0 })
    val selectedProfileId: StateFlow<Long?> = _selectedProfileId.asStateFlow()

    private val _pinnedMac = MutableStateFlow(prefs.getString(KEY_PINNED_MAC, null))
    val pinnedMac: StateFlow<String?> = _pinnedMac.asStateFlow()

    private val _toleranceKg = MutableStateFlow(prefs.getFloat(KEY_TOLERANCE, DEFAULT_TOLERANCE_KG).toDouble())
    val toleranceKg: StateFlow<Double> = _toleranceKg.asStateFlow()

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

    fun setPinnedMac(mac: String?) {
        _pinnedMac.value = mac
        prefs.edit { if (mac == null) remove(KEY_PINNED_MAC) else putString(KEY_PINNED_MAC, mac) }
    }

    fun setToleranceKg(value: Double) {
        val v = value.coerceIn(1.0, 6.0)
        _toleranceKg.value = v
        prefs.edit { putFloat(KEY_TOLERANCE, v.toFloat()) }
    }

    companion object {
        private const val KEY_SELECTED = "selected_profile_id"
        private const val KEY_PINNED_MAC = "pinned_scale_mac"
        private const val KEY_TOLERANCE = "assignment_tolerance_kg"
        private const val KEY_RATIONALE_SEEN = "ble_permission_rationale_seen"
        const val DEFAULT_TOLERANCE_KG = 3.0f
    }
}
