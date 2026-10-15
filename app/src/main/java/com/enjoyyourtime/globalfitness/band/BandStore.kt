package com.enjoyyourtime.globalfitness.band

import android.content.Context
import android.util.AtomicFile
import androidx.core.content.edit
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKey
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKeyResult
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The band picked by the user. */
data class SelectedBand(val address: String, val name: String)

/** An app whose notifications were seen by the listener, for the forwarding allow list. */
data class SeenApp(val packageName: String, val label: String)

/**
 * Settings of the direct band mode, separate from the rest of the app:
 *
 * - the auth key lives in a file in [Context.getNoBackupFilesDir] (app-private and excluded from cloud
 *   and device-to-device backups, unlike shared preferences while `allowBackup` is on);
 * - everything else in the private `band_prefs` shared preferences.
 */
class BandStore(context: Context) {
    private val prefs = context.getSharedPreferences("band_prefs", Context.MODE_PRIVATE)
    private val keyFile = AtomicFile(File(context.noBackupFilesDir, "band_auth_key"))

    private val _hasAuthKey = MutableStateFlow(loadAuthKey() != null)
    val hasAuthKey: StateFlow<Boolean> = _hasAuthKey.asStateFlow()

    private val _selected = MutableStateFlow(readSelected())
    val selected: StateFlow<SelectedBand?> = _selected.asStateFlow()

    private val _syncTimeOnConnect = MutableStateFlow(prefs.getBoolean(KEY_SYNC_TIME, true))
    val syncTimeOnConnect: StateFlow<Boolean> = _syncTimeOnConnect.asStateFlow()

    private val _forwardingEnabled = MutableStateFlow(prefs.getBoolean(KEY_FORWARDING, false))
    val forwardingEnabled: StateFlow<Boolean> = _forwardingEnabled.asStateFlow()

    private val _allowedPackages = MutableStateFlow(prefs.getStringSet(KEY_ALLOWED, emptySet()).orEmpty().toSet())
    val allowedPackages: StateFlow<Set<String>> = _allowedPackages.asStateFlow()

    private val _seenApps = MutableStateFlow(readSeenApps())
    val seenApps: StateFlow<List<SeenApp>> = _seenApps.asStateFlow()

    @Synchronized
    fun loadAuthKey(): AuthKey? {
        val text = try {
            keyFile.readFully().toString(Charsets.US_ASCII)
        } catch (_: IOException) {
            return null
        }
        return (AuthKey.parse(text) as? AuthKeyResult.Valid)?.key
    }

    @Synchronized
    fun saveAuthKey(key: AuthKey) {
        val out = keyFile.startWrite()
        try {
            out.write(key.toHex().toByteArray(Charsets.US_ASCII))
            keyFile.finishWrite(out)
        } catch (e: IOException) {
            keyFile.failWrite(out)
            throw e
        }
        _hasAuthKey.value = true
    }

    @Synchronized
    fun clearAuthKey() {
        keyFile.delete()
        _hasAuthKey.value = false
    }

    fun selectBand(address: String, name: String) {
        prefs.edit { putString(KEY_ADDRESS, address).putString(KEY_NAME, name) }
        _selected.value = SelectedBand(address, name)
    }

    fun setSyncTimeOnConnect(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SYNC_TIME, enabled) }
        _syncTimeOnConnect.value = enabled
    }

    fun setForwardingEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_FORWARDING, enabled) }
        _forwardingEnabled.value = enabled
    }

    @Synchronized
    fun setPackageAllowed(packageName: String, allowed: Boolean) {
        val updated = if (allowed) _allowedPackages.value + packageName else _allowedPackages.value - packageName
        prefs.edit { putStringSet(KEY_ALLOWED, updated) }
        _allowedPackages.value = updated
    }

    /** Remembers an app that posted a notification (label only; never the notification content). */
    @Synchronized
    fun recordSeenApp(packageName: String, label: String) {
        val current = _seenApps.value
        if (current.any { it.packageName == packageName && it.label == label }) return
        val updated = (current.filterNot { it.packageName == packageName } + SeenApp(packageName, label))
            .sortedBy { it.label.lowercase() }
            .takeLast(MAX_SEEN_APPS)
        prefs.edit { putStringSet(KEY_SEEN, updated.map { "${it.packageName}\t${it.label}" }.toSet()) }
        _seenApps.value = updated
    }

    private fun readSelected(): SelectedBand? {
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        return SelectedBand(address, prefs.getString(KEY_NAME, null) ?: address)
    }

    private fun readSeenApps(): List<SeenApp> =
        prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val parts = entry.split('\t', limit = 2)
                if (parts.size == 2) SeenApp(parts[0], parts[1]) else null
            }
            .sortedBy { it.label.lowercase() }

    private companion object {
        const val KEY_ADDRESS = "device_address"
        const val KEY_NAME = "device_name"
        const val KEY_SYNC_TIME = "sync_time_on_connect"
        const val KEY_FORWARDING = "notification_forwarding"
        const val KEY_ALLOWED = "forwarding_allowed_packages"
        const val KEY_SEEN = "forwarding_seen_apps"
        const val MAX_SEEN_APPS = 80
    }
}
