package com.enjoyyourtime.globalfitness.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.database.SQLException
import android.os.Build
import android.text.format.DateFormat
import android.util.Log
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKey
import com.enjoyyourtime.globalfitness.band.xiaomi.BandException
import com.enjoyyourtime.globalfitness.band.xiaomi.BandFailure
import com.enjoyyourtime.globalfitness.band.xiaomi.BandLink
import com.enjoyyourtime.globalfitness.band.xiaomi.CompanionInfo
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Experimental direct mode for the Xiaomi Smart Band 9: Android wiring (Bluetooth, permissions, settings,
 * band.db) around [BandController], used by the band screen and the notification listener.
 *
 * [scope] is the app scope (main thread), as [BandController] requires.
 */
class BandManager(private val context: Context, private val scope: CoroutineScope) {
    val store = BandStore(context)
    val log = BandLog()
    val scanner = BandScanner(context, scope)
    private val database = BandDatabase(context)
    private val bluetooth: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val controller = BandController(scope, log, AndroidPlatform())

    val state: StateFlow<BandState> = controller.state

    /** Heart rate samples received during the current live session (for the live chart). */
    val heartRate: StateFlow<List<HeartRateSample>> = controller.heartRate

    /** Short user-facing confirmations and errors (snackbar). */
    val messages: SharedFlow<BandMessage> = controller.messages

    init {
        store.loadAuthKey()?.let { log.registerSecret(it.toHex()) }
    }

    // ---- setup ----

    fun saveAuthKey(key: AuthKey) {
        log.registerSecret(key.toHex())
        store.saveAuthKey(key)
        log.add("Authentication key saved")
    }

    fun clearAuthKey() {
        controller.disconnect()
        store.clearAuthKey()
        log.add("Authentication key deleted")
    }

    fun selectBand(candidate: BandCandidate) {
        if (store.selected.value?.address != candidate.address) controller.disconnect()
        store.selectBand(candidate.address, candidate.name)
        log.add("Band selected: ${candidate.name} (${candidate.address})")
    }

    /** Heart rate recorded in direct mode (band.db) between [fromMs] and [toMs]. Blocking: call on IO. */
    fun recordedHeartRate(fromMs: Long, toMs: Long): List<HeartRateSample> = database.heartRateSamples(fromMs, toMs)

    // ---- screen lifecycle and actions ----

    fun onUiVisible() = controller.onUiVisible()

    fun onUiHidden() {
        scanner.stopScan()
        controller.onUiHidden()
    }

    fun connect() = controller.connect()

    fun disconnect() = controller.disconnect()

    fun findDevice(start: Boolean) = controller.findDevice(start)

    fun syncTime() = controller.syncTime()

    fun refreshStatus() = controller.refreshStatus()

    fun setLiveHeartRate(enabled: Boolean) = controller.setLiveHeartRate(enabled)

    fun refreshAlarms() = controller.refreshAlarms()

    fun saveAlarm(id: Int?, spec: AlarmSpec) = controller.saveAlarm(id, spec)

    fun deleteAlarm(id: Int) = controller.deleteAlarm(id)

    fun sendTestNotification() = controller.sendTestNotification(context.getString(R.string.band_test_notification_body))

    fun setSyncTimeOnConnect(enabled: Boolean) = store.setSyncTimeOnConnect(enabled)

    fun setForwardingEnabled(enabled: Boolean) {
        store.setForwardingEnabled(enabled)
        if (!enabled) controller.clearForwarded()
    }

    fun setPackageAllowed(packageName: String, allowed: Boolean) = store.setPackageAllowed(packageName, allowed)

    // ---- notification forwarding (called by BandNotificationListener) ----

    fun onPhoneNotification(n: PhoneNotification) {
        store.recordSeenApp(n.packageName, n.appName)
        controller.onPhoneNotification(n, store.forwardingEnabled.value, store.allowedPackages.value)
    }

    fun onPhoneNotificationRemoved(key: String) = controller.onPhoneNotificationRemoved(key)

    private inner class AndroidPlatform : BandPlatform {
        override val appPackageName: String = context.packageName

        override fun preconditionFailure(): BandFailure? {
            val adapter = bluetooth?.adapter ?: return BandFailure.BLUETOOTH_UNAVAILABLE
            if (!BandPermissions.hasConnect(context)) return BandFailure.MISSING_PERMISSION
            if (!adapter.isEnabled) return BandFailure.BLUETOOTH_OFF
            return null
        }

        override fun authKey(): AuthKey? = store.loadAuthKey()

        override fun selectedBand(): SelectedBand? = store.selected.value

        override suspend fun openLink(address: String): BandLink {
            val adapter = bluetooth?.adapter ?: throw BandException(BandFailure.BLUETOOTH_UNAVAILABLE)
            return RfcommLink.connect(context, adapter, address, CONNECT_TIMEOUT_MS)
        }

        @SuppressLint("MissingPermission") // checked with BandPermissions.hasConnect
        override fun isBusyElsewhere(address: String): Boolean {
            if (!BandPermissions.hasConnect(context)) return false
            return try {
                bluetooth?.getConnectedDevices(BluetoothProfile.GATT).orEmpty().any { it.address.equals(address, ignoreCase = true) }
            } catch (_: SecurityException) {
                false
            }
        }

        override fun companionInfo(): CompanionInfo {
            val language = Locale.getDefault().language.take(2).uppercase(Locale.ROOT)
            return CompanionInfo(Build.VERSION.SDK_INT, Build.MODEL ?: "Android", language.ifEmpty { "ES" })
        }

        override fun syncTimeOnConnect(): Boolean = store.syncTimeOnConnect.value

        override fun is24HourFormat(): Boolean = DateFormat.is24HourFormat(context)

        override fun saveHeartRate(sample: HeartRateSample, address: String?) {
            scope.launch(Dispatchers.IO) {
                try {
                    database.insertHeartRate(listOf(sample), address)
                } catch (e: SQLException) {
                    Log.w(TAG, "Could not store heart rate sample", e)
                }
            }
        }

        override fun beforeConnect() = scanner.stopScan()
    }

    private companion object {
        const val TAG = "BandDirect"
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
