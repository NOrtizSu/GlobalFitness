package com.enjoyyourtime.globalfitness.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiBand9
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A band the user can pick: bonded with this phone and/or seen advertising. */
data class BandCandidate(
    val address: String,
    val name: String,
    val bonded: Boolean,
    /** Last advertisement RSSI (dBm), null if not seen in this scan. */
    val rssi: Int?,
    /** Matches the Smart Band 9 name pattern; other Xiaomi bands are listed but untested. */
    val isBand9: Boolean,
)

/**
 * Finds the band: the bonded devices (Mi Fitness bonds it with the phone) plus a short BLE scan that
 * matches advertised names, to show signal strength. The band itself is then reached over RFCOMM.
 */
class BandScanner(private val context: Context, private val scope: CoroutineScope) {
    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)

    private val _candidates = MutableStateFlow<List<BandCandidate>>(emptyList())
    val candidates: StateFlow<List<BandCandidate>> = _candidates.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var callback: ScanCallback? = null
    private var stopJob: Job? = null

    /** Lists bonded Xiaomi bands (needs the connect permission on Android 12+). */
    @SuppressLint("MissingPermission") // checked with BandPermissions.hasConnect
    fun refreshBonded() {
        val adapter = manager?.adapter ?: return
        if (!BandPermissions.hasConnect(context)) return
        val bonded = try {
            adapter.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            emptySet()
        }
        val found = bonded.mapNotNull { device ->
            val name = deviceName(device) ?: return@mapNotNull null
            if (!XiaomiBand9.isXiaomiBand(name)) return@mapNotNull null
            BandCandidate(device.address, name, bonded = true, rssi = null, isBand9 = XiaomiBand9.isBand9(name))
        }
        _candidates.update { current -> merge(current.filter { !it.bonded || it.rssi != null }, found) }
    }

    /** BLE scan for [durationMs]; results are merged into [candidates]. */
    @SuppressLint("MissingPermission") // checked with BandPermissions.hasScan
    fun startScan(durationMs: Long = SCAN_DURATION_MS) {
        val scanner = manager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        if (!BandPermissions.hasScan(context) || callback != null) return
        refreshBonded()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::onResult)

            override fun onScanFailed(errorCode: Int) {
                scope.launch { stopScan() }
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, cb)
        } catch (_: SecurityException) {
            return
        } catch (_: IllegalStateException) {
            return
        }
        callback = cb
        _scanning.value = true
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(durationMs)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        stopJob?.cancel()
        stopJob = null
        val cb = callback ?: return
        callback = null
        _scanning.value = false
        try {
            manager?.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun onResult(result: ScanResult) {
        val name = result.scanRecord?.deviceName ?: deviceName(result.device) ?: return
        if (!XiaomiBand9.isXiaomiBand(name)) return
        val address = result.device.address
        scope.launch {
            _candidates.update { current ->
                val bonded = current.any { it.address == address && it.bonded }
                merge(current, listOf(BandCandidate(address, name, bonded, result.rssi, XiaomiBand9.isBand9(name))))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun deviceName(device: BluetoothDevice): String? {
        if (!BandPermissions.hasConnect(context)) return null
        return try {
            device.name
        } catch (_: SecurityException) {
            null
        }
    }

    private fun merge(current: List<BandCandidate>, updates: List<BandCandidate>): List<BandCandidate> {
        val byAddress = LinkedHashMap<String, BandCandidate>()
        current.forEach { byAddress[it.address] = it }
        updates.forEach { u ->
            val old = byAddress[u.address]
            byAddress[u.address] = if (old == null) u else old.copy(name = u.name, bonded = old.bonded || u.bonded, rssi = u.rssi ?: old.rssi)
        }
        return byAddress.values.sortedWith(compareByDescending<BandCandidate> { it.isBand9 }.thenByDescending { it.bonded }.thenBy { it.name })
    }

    private companion object {
        const val SCAN_DURATION_MS = 12_000L
    }
}
