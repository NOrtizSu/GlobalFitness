package com.enjoyyourtime.globalfitness.health

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Health Connect preferences (own SharedPreferences file, independent of the app settings). */
class HealthSettings(context: Context) {
    private val prefs = context.getSharedPreferences("health_connect", Context.MODE_PRIVATE)

    private val _exportChoice = MutableStateFlow(if (prefs.contains(KEY_EXPORT)) prefs.getBoolean(KEY_EXPORT, true) else null)

    /**
     * "Enviar mediciones de la báscula a Health Connect": null until the user touches the
     * switch, which means "on" as soon as the write permission is granted.
     */
    val exportChoice: StateFlow<Boolean?> = _exportChoice.asStateFlow()

    private val _lastSyncAt = MutableStateFlow(prefs.getLong(KEY_LAST_SYNC, -1L).takeIf { it > 0 })
    val lastSyncAt: StateFlow<Long?> = _lastSyncAt.asStateFlow()

    /** Measurements with a larger id than this are written automatically (-1 = not set yet). */
    val exportAfterId: Long get() = prefs.getLong(KEY_EXPORT_AFTER, -1L)

    fun setExportChoice(enabled: Boolean) {
        _exportChoice.value = enabled
        prefs.edit { putBoolean(KEY_EXPORT, enabled) }
    }

    fun setExportAfterId(id: Long) {
        prefs.edit { putLong(KEY_EXPORT_AFTER, id) }
    }

    fun setLastSyncAt(time: Long) {
        _lastSyncAt.value = time
        prefs.edit { putLong(KEY_LAST_SYNC, time) }
    }

    companion object {
        private const val KEY_EXPORT = "export_scale_enabled"
        private const val KEY_EXPORT_AFTER = "export_after_measurement_id"
        private const val KEY_LAST_SYNC = "last_sync_at"

        /** The switch is on by default once permissions allow writing. */
        fun exportEnabled(choice: Boolean?, canExport: Boolean): Boolean = canExport && (choice ?: true)
    }
}
