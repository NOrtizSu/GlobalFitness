package com.enjoyyourtime.globalfitness.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.enjoyyourtime.globalfitness.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Scanner status exposed to the UI. */
sealed interface ScanStatus {
    data object Stopped : ScanStatus
    data object NoBluetoothLe : ScanStatus
    data object MissingPermission : ScanStatus
    data object BluetoothOff : ScanStatus

    /** A (re)start is scheduled, e.g. waiting for Android's 5-starts-per-30-s limit. */
    data object Starting : ScanStatus

    /** [locationOff] is only relevant on Android 11 and lower, where scans need location on. */
    data class Scanning(val locationOff: Boolean) : ScanStatus
    data class Failed(val code: Int, val message: String) : ScanStatus
}

/** A parsed scale advertisement plus radio metadata. */
data class ScaleAdvertisement(
    val frame: ScaleFrame,
    val address: String,
    val rssi: Int,
    val receivedAtMillis: Long,
)

/** Last frame seen, for the diagnostics panel. */
data class ScanDebugInfo(
    val rawHex: String,
    val address: String,
    val deviceName: String?,
    val rssi: Int,
    val receivedAtMillis: Long,
    val frame: ScaleFrame?,
    val ignoredByPin: Boolean,
)

/**
 * Passive BLE listener for the Mi Body Composition Scale 2.
 *
 * Uses an unfiltered low-latency scan and filters in software on the 0x181B service data,
 * because hardware service-data filters are unreliable on some phones. Only runs while the
 * app is in the foreground ([onForegroundStart] / [onForegroundStop]).
 */
class ScaleScanner(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    /** Monotonic clock for rate limiting. */
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val bluetoothManager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _status = MutableStateFlow<ScanStatus>(ScanStatus.Stopped)
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private val _frames = MutableSharedFlow<ScaleAdvertisement>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<ScaleAdvertisement> = _frames.asSharedFlow()

    private val _debug = MutableStateFlow<ScanDebugInfo?>(null)
    val debug: StateFlow<ScanDebugInfo?> = _debug.asStateFlow()

    private val _lastScaleAddress = MutableStateFlow<String?>(null)

    /** Address of the last device whose frame parsed correctly and was not ignored by the pin. */
    val lastScaleAddress: StateFlow<String?> = _lastScaleAddress.asStateFlow()

    /** Number of visible activities; scanning runs while > 0 (robust to overlapping instances). */
    private var foregroundCount = 0
    private val foreground: Boolean get() = foregroundCount > 0
    private var activeScanner: BluetoothLeScanner? = null

    /** A new callback instance is used for every start so stale registrations never collide. */
    private var activeCallback: ScanCallback? = null
    private var restartJob: Job? = null
    private var retryJob: Job? = null
    private val startTimes = ArrayDeque<Long>()
    private var receiverRegistered = false

    private val serviceUuid = ParcelUuid(MiScaleParser.SERVICE_UUID)

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> refresh()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    stopScan()
                    if (foreground) _status.value = ScanStatus.BluetoothOff
                }
            }
        }
    }

    private fun newCallback(): ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onResult(this, result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onResult(this, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            val failed = this
            scope.launch { handleFailure(failed, errorCode) }
        }
    }

    /** Called from the Activity lifecycle (ON_START). */
    fun onForegroundStart() {
        foregroundCount++
        if (foregroundCount == 1) {
            registerReceiver()
            refresh()
        }
    }

    /** Called from the Activity lifecycle (ON_STOP). */
    fun onForegroundStop() {
        if (foregroundCount == 0) return
        foregroundCount--
        if (foregroundCount == 0) {
            stopScan()
            unregisterReceiver()
            retryJob?.cancel()
            _status.value = ScanStatus.Stopped
        }
    }

    /** Re-evaluates preconditions (permission granted, Bluetooth enabled...) and starts scanning. */
    fun refresh() {
        if (!foreground) return
        val bt = adapter
        when {
            bt == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) ->
                _status.value = ScanStatus.NoBluetoothLe
            !hasScanPermission(context) -> {
                stopScan()
                _status.value = ScanStatus.MissingPermission
            }
            !bt.isEnabled -> {
                stopScan()
                _status.value = ScanStatus.BluetoothOff
            }
            activeCallback != null -> _status.value = ScanStatus.Scanning(isLocationOffForScan())
            else -> startScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!foreground || activeCallback != null) return
        val now = elapsedRealtime()
        while (startTimes.isNotEmpty() && now - startTimes.first() > START_LIMIT_WINDOW_MS) startTimes.removeFirst()
        if (startTimes.size >= MAX_STARTS_PER_WINDOW) {
            // Android blocks apps that start more than 5 scans in 30 s: wait for the window.
            scheduleRetry(startTimes.first() + START_LIMIT_WINDOW_MS - now + 500)
            return
        }
        val bt = adapter ?: return
        val scanner = bt.bluetoothLeScanner ?: run {
            _status.value = ScanStatus.BluetoothOff
            return
        }
        if (!hasScanPermission(context)) {
            _status.value = ScanStatus.MissingPermission
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .setLegacy(true)
            .build()
        val callback = newCallback()
        // Set before starting: results may arrive before startScan returns.
        activeCallback = callback
        activeScanner = scanner
        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            activeCallback = null
            activeScanner = null
            _status.value = ScanStatus.MissingPermission
            return
        } catch (e: IllegalStateException) {
            activeCallback = null
            activeScanner = null
            _status.value = ScanStatus.BluetoothOff
            return
        }
        startTimes.addLast(now)
        _status.value = ScanStatus.Scanning(isLocationOffForScan())
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(PERIODIC_RESTART_MS)
            restartScan()
        }
    }

    private fun stopScan() {
        restartJob?.cancel()
        restartJob = null
        stopActiveScan()
    }

    /** Always tries to stop, even if the adapter reports disabled (BLE_ON keeps scans alive). */
    private fun stopActiveScan() {
        val callback = activeCallback ?: return
        val scanner = activeScanner
        activeCallback = null
        activeScanner = null
        stopQuietly(scanner, callback)
    }

    @SuppressLint("MissingPermission")
    private fun stopQuietly(scanner: BluetoothLeScanner?, callback: ScanCallback) {
        try {
            (scanner ?: adapter?.bluetoothLeScanner)?.stopScan(callback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        } catch (_: NullPointerException) {
            // Some vendor stacks throw while the adapter is going down.
        }
    }

    private fun onResult(callback: ScanCallback, result: ScanResult) {
        if (callback !== activeCallback) {
            // Leaked registration from an earlier start: stop it and drop the result.
            stopQuietly(null, callback)
            return
        }
        handleResult(result)
    }

    private fun handleFailure(callback: ScanCallback, errorCode: Int) {
        if (callback !== activeCallback) {
            stopQuietly(null, callback)
            return
        }
        activeCallback = null
        activeScanner = null
        restartJob?.cancel()
        if (errorCode == SCAN_FAILED_ALREADY_STARTED) {
            // A registration for this callback is still alive in the stack: stop it explicitly,
            // then start fresh (with a new callback instance) after a short gap.
            stopQuietly(adapter?.bluetoothLeScanner, callback)
            scheduleRetry(RESTART_GAP_MS)
            return
        }
        _status.value = ScanStatus.Failed(errorCode, scanErrorMessage(errorCode))
        if (errorCode in RETRYABLE_ERRORS) scheduleRetry(RETRY_DELAY_MS, showStarting = false)
    }

    /** Android silently degrades long-running unfiltered scans: restart periodically. */
    private suspend fun restartScan() {
        stopActiveScan()
        delay(RESTART_GAP_MS)
        if (foreground) refresh()
    }

    private fun scheduleRetry(delayMs: Long, showStarting: Boolean = true) {
        if (showStarting) _status.value = ScanStatus.Starting
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs.coerceAtLeast(0))
            if (foreground) refresh()
        }
    }

    private fun handleResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val data = record.getServiceData(serviceUuid) ?: return
        val address = result.device?.address ?: return
        val now = System.currentTimeMillis()
        val frame = MiScaleParser.parse(data)
        val pinned = settings.pinnedMac.value
        val ignored = pinned != null && !pinned.equals(address, ignoreCase = true)
        _debug.value = ScanDebugInfo(
            rawHex = MiScaleParser.toHex(data),
            address = address,
            deviceName = record.deviceName,
            rssi = result.rssi,
            receivedAtMillis = now,
            frame = frame,
            ignoredByPin = ignored,
        )
        if (frame == null || ignored) return
        _lastScaleAddress.value = address
        _frames.tryEmit(ScaleAdvertisement(frame, address, result.rssi, now))
    }

    private fun isLocationOffForScan(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return !LocationManagerCompat.isLocationEnabled(lm)
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(bluetoothStateReceiver) }
        receiverRegistered = false
    }

    companion object {
        private const val PERIODIC_RESTART_MS = 25 * 60_000L
        private const val RESTART_GAP_MS = 1_000L
        private const val RETRY_DELAY_MS = 30_000L
        private const val START_LIMIT_WINDOW_MS = 30_000L
        private const val MAX_STARTS_PER_WINDOW = 5

        private const val SCAN_FAILED_ALREADY_STARTED = 1
        private const val SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2
        private const val SCAN_FAILED_INTERNAL_ERROR = 3
        private const val SCAN_FAILED_FEATURE_UNSUPPORTED = 4
        private const val SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES = 5
        private const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6

        private val RETRYABLE_ERRORS = setOf(
            SCAN_FAILED_APPLICATION_REGISTRATION_FAILED,
            SCAN_FAILED_INTERNAL_ERROR,
            SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES,
            SCAN_FAILED_SCANNING_TOO_FREQUENTLY,
        )

        /** Runtime permissions needed for scanning (and for asking to enable Bluetooth). */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasScanPermission(context: Context): Boolean {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Manifest.permission.BLUETOOTH_SCAN
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
            return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

        fun hasConnectPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        fun scanErrorMessage(code: Int): String = when (code) {
            SCAN_FAILED_ALREADY_STARTED -> "El escaneo ya estaba en marcha."
            SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                "Android no pudo registrar el escaneo. Reintentando en 30 s; si persiste, apaga y enciende el Bluetooth."
            SCAN_FAILED_INTERNAL_ERROR -> "Error interno del Bluetooth. Reintentando en 30 s."
            SCAN_FAILED_FEATURE_UNSUPPORTED -> "Este teléfono no admite el escaneo Bluetooth LE necesario."
            SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES ->
                "El Bluetooth no tiene recursos libres (demasiadas apps escaneando). Reintentando en 30 s."
            SCAN_FAILED_SCANNING_TOO_FREQUENTLY ->
                "Android limitó el escaneo por iniciarlo demasiadas veces. Reintentando en 30 s."
            else -> "Error de escaneo Bluetooth (código $code)."
        }
    }
}
