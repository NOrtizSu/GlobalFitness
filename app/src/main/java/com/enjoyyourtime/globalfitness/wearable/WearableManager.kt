package com.enjoyyourtime.globalfitness.wearable

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.database.SQLException
import android.os.Build
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.band.BandDatabase
import com.enjoyyourtime.globalfitness.band.BandLog
import com.enjoyyourtime.globalfitness.band.BandPermissions
import com.enjoyyourtime.globalfitness.band.BandStore
import com.enjoyyourtime.globalfitness.band.HeartRateSample
import com.enjoyyourtime.globalfitness.band.PhoneNotification
import com.enjoyyourtime.globalfitness.band.RfcommLink
import com.enjoyyourtime.globalfitness.band.xiaomi.BandException
import com.enjoyyourtime.globalfitness.band.xiaomi.BandLink
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import com.enjoyyourtime.globalfitness.band.xiaomi.CompanionInfo
import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.GattLink
import com.enjoyyourtime.globalfitness.scale.GattSession
import com.enjoyyourtime.globalfitness.wearable.xiaomi.toWearable
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A configured wearable with what the lists and the Today chip show about it. */
data class WearableSummary(
    val device: ConfiguredWearable,
    /** Null when the driver no longer exists in this version. */
    val driver: WearableDriver?,
    val hasKey: Boolean,
    val state: WearableState,
) {
    val needsKey: Boolean get() = driver?.needsAuthKey == true && !hasKey
}

/**
 * Android wiring of the wearables: the driver registry, detection (advertisements and bonded
 * devices), configured devices and their keys, one [WearableController] per configured device,
 * heart rate storage in band.db and notification forwarding. UI-free; the screens observe it.
 *
 * [scope] is the app scope (main thread), as [WearableController] requires.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearableManager(
    private val context: Context,
    private val scope: CoroutineScope,
    val registry: WearableRegistry = AllWearables.registry(),
) {
    val log = BandLog()
    val keys = WearableKeyStore(context)
    val settings = WearableConfigStore(context)
    private val database by lazy { BandDatabase(context) }
    private val bluetooth: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val detector = WearableDetector(registry)
    private val controllers = LinkedHashMap<String, WearableController>()
    private val stateWatchers = HashMap<String, Job>()

    private val _detected = MutableStateFlow<List<DetectedWearable>>(emptyList())
    val detected: StateFlow<List<DetectedWearable>> = _detected.asStateFlow()

    private val _suggestion = MutableStateFlow<WearableDetection.Suggest?>(null)

    /** A device to offer with the confirmation card ("¿Es tu pulsera?"), or null. */
    val suggestion: StateFlow<WearableDetection.Suggest?> = _suggestion.asStateFlow()

    private val liveIds = MutableStateFlow<Set<String>>(emptySet())
    private val _liveHeartRateActive = MutableStateFlow(false)

    /** True while any wearable streams live heart rate (ads are held off meanwhile). */
    val liveHeartRateActive: StateFlow<Boolean> = _liveHeartRateActive.asStateFlow()

    val configured: StateFlow<List<ConfiguredWearable>> get() = settings.configured

    init {
        importLegacyBand()
        keys.keyedAddresses.value.forEach { address -> keys.load(address)?.let { log.registerSecret(it) } }
    }

    /** Every configured wearable with its key status and connection state, in setup order. */
    val summaries: StateFlow<List<WearableSummary>> = settings.configured
        .flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyList())
            } else {
                val states = list.map { device -> controller(device)?.state ?: flowOf(WearableState()) }
                combine(states) { array -> list.zip(array.toList()) }
            }
        }
        .combine(keys.keyedAddresses) { pairs, keyed ->
            pairs.map { (device, state) ->
                WearableSummary(device, registry.find(device.driverId), WearableKeyStore.normalize(device.address) in keyed, state)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // ---- configured devices ----

    fun driverFor(device: ConfiguredWearable): WearableDriver? = registry.find(device.driverId)

    fun device(id: String): ConfiguredWearable? = settings.configured.value.firstOrNull { it.id == id }

    /** Adds (or replaces, same address) a configured wearable. */
    fun addDevice(driver: WearableDriver, address: String, name: String, fromSuggestion: Boolean): ConfiguredWearable {
        val device = ConfiguredWearable(
            id = UUID.randomUUID().toString(),
            driverId = driver.id,
            address = address.uppercase(Locale.ROOT),
            name = name.trim().ifEmpty { driver.displayName }.take(MAX_NAME),
            addedAt = System.currentTimeMillis(),
            fromSuggestion = fromSuggestion,
        )
        settings.configured.value.filter { it.matchesAddress(address) }.forEach { dropController(it.id) }
        settings.updateConfigured { list -> list.filterNot { it.matchesAddress(address) } + device }
        settings.undismiss(address)
        _suggestion.update { if (it?.address.equals(address, ignoreCase = true)) null else it }
        log.add("Wearable added: ${driver.id} (${device.address})")
        return device
    }

    fun rename(id: String, name: String) {
        val clean = name.trim().take(MAX_NAME)
        if (clean.isEmpty()) return
        settings.updateConfigured { list -> list.map { if (it.id == id) it.copy(name = clean) else it } }
    }

    fun setSyncTimeOnConnect(id: String, enabled: Boolean) {
        settings.updateConfigured { list ->
            list.map {
                if (it.id != id) it else it.copy(config = if (enabled) it.config - ConfiguredWearable.CONFIG_SYNC_TIME else it.config + (ConfiguredWearable.CONFIG_SYNC_TIME to "0"))
            }
        }
    }

    fun setConfigValue(id: String, key: String, value: String?) {
        settings.updateConfigured { list ->
            list.map { if (it.id != id) it else it.copy(config = if (value.isNullOrBlank()) it.config - key else it.config + (key to value.trim())) }
        }
    }

    /** Removes the device, its key and its stored state; it is not suggested again. */
    fun removeDevice(id: String) {
        val device = device(id) ?: return
        dropController(id)
        settings.updateConfigured { list -> list.filterNot { it.id == id } }
        settings.clearDriverStore(id)
        keys.clear(device.address)
        settings.dismiss(device.address)
        detector.forget(device.address)
        log.add("Wearable removed (${device.address})")
    }

    /** Validates and stores the key of [device]; the key is never logged. Throws [IOException] when it cannot be written. */
    fun saveKey(device: ConfiguredWearable, input: String): KeyCheck {
        val driver = driverFor(device) ?: return KeyCheck.Invalid(KeyProblem.EMPTY)
        val check = WearableKeys.validate(driver.authKey, input)
        if (check is KeyCheck.Valid && driver.needsAuthKey) {
            log.registerSecret(check.normalized)
            keys.save(device.address, check.normalized)
            log.add("Key saved for ${device.address}")
        }
        return check
    }

    fun clearKey(device: ConfiguredWearable) {
        controllers[device.id]?.disconnect()
        keys.clear(device.address)
        log.add("Key deleted for ${device.address}")
    }

    /**
     * The controller of a configured device (one per device id, kept while its driver and address
     * stay the same, so renaming or changing a setting never drops the connection).
     */
    fun controller(device: ConfiguredWearable): WearableController? {
        controllers[device.id]?.let { if (it.driver.id == device.driverId && it.device.matchesAddress(device.address)) return it }
        val driver = driverFor(device) ?: return null
        dropController(device.id)
        val controller = WearableController(scope, log::add, AndroidPlatform(), device, driver)
        controllers[device.id] = controller
        stateWatchers[device.id] = scope.launch {
            controller.state.collect { state -> setLive(device.id, state.liveActive) }
        }
        return controller
    }

    private fun dropController(id: String) {
        controllers.remove(id)?.disconnect()
        stateWatchers.remove(id)?.cancel()
        setLive(id, false)
    }

    private fun setLive(id: String, active: Boolean) {
        liveIds.update { if (active) it + id else it - id }
        _liveHeartRateActive.value = liveIds.value.isNotEmpty()
    }

    /** Live heart rate recorded in band.db between [fromMs] and [toMs], per device. Blocking: call on IO. */
    fun recordedHeartRateByDevice(fromMs: Long, toMs: Long): List<DeviceHeartRate> =
        WearableHeartRate.groupByDevice(database.heartRateSamplesWithDevice(fromMs, toMs), settings.configured.value)

    // ---- notification forwarding settings ----

    fun setForwardingEnabled(enabled: Boolean) {
        settings.setForwardingEnabled(enabled)
        if (!enabled) controllers.values.forEach { it.clearForwarded() }
    }

    fun setPackageAllowed(packageName: String, allowed: Boolean) = settings.setPackageAllowed(packageName, allowed)

    // ---- detection ----

    fun onAdvertisement(adv: AdvertisementInfo) = onSighting(WearableSighting.fromAdvertisement(adv))

    /** Lists the bonded devices (vendor apps bond their wearables; Classic devices only show up here). */
    @SuppressLint("MissingPermission") // checked with BandPermissions.hasConnect
    fun refreshBonded() {
        val adapter = bluetooth?.adapter ?: return
        if (!BandPermissions.hasConnect(context)) return
        val bonded = try {
            adapter.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            emptySet()
        }
        for (device in bonded) {
            val sighting = try {
                WearableSighting.bonded(
                    address = device.address,
                    name = device.name,
                    type = when (device.type) {
                        BluetoothDevice.DEVICE_TYPE_CLASSIC -> BtDeviceType.CLASSIC
                        BluetoothDevice.DEVICE_TYPE_LE -> BtDeviceType.LE
                        BluetoothDevice.DEVICE_TYPE_DUAL -> BtDeviceType.DUAL
                        else -> BtDeviceType.UNKNOWN
                    },
                    uuids = device.uuids?.map { it.uuid }.orEmpty(),
                )
            } catch (_: SecurityException) {
                continue
            }
            onSighting(sighting)
        }
    }

    /** Re-reads the detected list so stale devices drop out. */
    fun refreshDetected() {
        _detected.value = detector.detected(SystemClock.elapsedRealtime())
    }

    /** "Yes, it's mine": sets up the suggested device and returns it. */
    fun acceptSuggestion(): ConfiguredWearable? {
        val s = _suggestion.value ?: return null
        _suggestion.value = null
        return addDevice(s.driver, s.address, s.name ?: s.driver.displayName, fromSuggestion = true)
    }

    /** "No": not offered again. */
    fun declineSuggestion() {
        val s = _suggestion.value ?: return
        settings.dismiss(s.address)
        _suggestion.value = null
    }

    private fun onSighting(sighting: WearableSighting) {
        val now = SystemClock.elapsedRealtime()
        val configured = settings.configured.value.map { it.addressKey }.toSet()
        val result = detector.onSighting(sighting, now, configured, settings.dismissed.value)
        if (result == WearableDetection.Ignored) return
        _detected.value = detector.detected(now)
        if (result is WearableDetection.Suggest && _suggestion.value == null) _suggestion.value = result
    }

    // ---- notification forwarding (from the notification listener) ----

    fun onPhoneNotification(n: PhoneNotification) {
        settings.recordSeenApp(n.packageName, n.appName)
        val enabled = settings.forwardingEnabled.value
        val allowed = settings.allowedPackages.value
        controllers.values.forEach { it.onPhoneNotification(n, enabled, allowed) }
    }

    fun onPhoneNotificationRemoved(key: String) = controllers.values.forEach { it.onPhoneNotificationRemoved(key) }

    // ---- the original Smart Band 9 mode ----

    /**
     * Moves the band, key and settings chosen in the original direct band mode here, once. Every step
     * is idempotent, so an import interrupted by a write error simply runs again on the next start:
     * the device is added only if missing, the key is copied only if this store has none for that
     * address, and the old key file is deleted only once the copy exists.
     */
    private fun importLegacyBand() {
        if (settings.legacyBandImported) return
        try {
            val old = BandStore(context)
            val selected = old.selected.value
            if (selected != null) {
                if (settings.configured.value.none { it.matchesAddress(selected.address) }) {
                    val device = LegacyBandImport.configuredFor(registry, selected.address, selected.name, old.syncTimeOnConnect.value, System.currentTimeMillis())
                    settings.updateConfigured { it + device }
                    log.add("Imported the band of the original direct mode (${device.address})")
                }
                if (!keys.has(selected.address)) old.loadAuthKey()?.let { key -> keys.save(selected.address, key.toHex()) }
                if (keys.has(selected.address)) old.clearAuthKey()
            }
            if (old.forwardingEnabled.value) settings.setForwardingEnabled(true)
            if (old.allowedPackages.value.isNotEmpty()) settings.setPackagesAllowed(settings.allowedPackages.value + old.allowedPackages.value)
            if (old.seenApps.value.isNotEmpty()) settings.setSeenApps(settings.seenApps.value + old.seenApps.value)
        } catch (e: IOException) {
            Log.w(TAG, "Could not import the original band settings", e)
            return
        }
        settings.markLegacyBandImported()
    }

    // ---- platform ----

    private inner class AndroidPlatform : WearablePlatform {
        override fun preconditionFailure(transport: WearableTransport): WearableFailure? {
            val adapter = bluetooth?.adapter ?: return WearableFailure.BLUETOOTH_UNAVAILABLE
            if (!BandPermissions.hasConnect(context)) return WearableFailure.MISSING_PERMISSION
            if (!adapter.isEnabled) return WearableFailure.BLUETOOTH_OFF
            return null
        }

        override fun authKey(address: String): String? = keys.load(address)

        override fun createSession(driver: WearableDriver, device: ConfiguredWearable, authKey: String?): WearableSession {
            val current = device(device.id) ?: device
            return driver.createSession(
                WearableSessionEnv(
                    address = current.address,
                    name = current.name,
                    authKey = authKey,
                    config = current.config,
                    store = settings.driverStore(current.id),
                    companion = companionInfo(),
                    scope = scope,
                    links = links,
                    log = log::add,
                ),
            )
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

        override fun syncTimeOnConnect(device: ConfiguredWearable): Boolean =
            (device(device.id) ?: device).config[ConfiguredWearable.CONFIG_SYNC_TIME] != "0"

        override fun is24HourFormat(): Boolean = DateFormat.is24HourFormat(context)

        override fun saveHeartRate(sample: HeartRateSample, address: String, driverId: String) {
            scope.launch(Dispatchers.IO) {
                try {
                    database.insertHeartRate(listOf(sample), address, driverId)
                } catch (e: SQLException) {
                    Log.w(TAG, "Could not store heart rate sample", e)
                }
            }
        }

        override fun beforeConnect() = Unit

        override fun testNotification(nowMillis: Long): BandNotification = BandNotification(
            id = TEST_NOTIFICATION_ID,
            packageName = context.packageName,
            appName = APP_NAME,
            title = APP_NAME,
            body = context.getString(R.string.band_test_notification_body),
            whenMillis = nowMillis,
        )
    }

    private fun companionInfo(): CompanionInfo {
        val language = Locale.getDefault().language.take(2).uppercase(Locale.ROOT)
        return CompanionInfo(Build.VERSION.SDK_INT, Build.MODEL ?: "Android", language.ifEmpty { "EN" })
    }

    private val links = object : WearableLinkFactory {
        override fun gatt(address: String): GattLink = GattSession(context.applicationContext, address)

        override suspend fun spp(address: String): BandLink {
            val adapter = bluetooth?.adapter ?: throw WearableException(WearableFailure.BLUETOOTH_UNAVAILABLE)
            return try {
                RfcommLink.connect(context, adapter, address, SPP_CONNECT_TIMEOUT_MS)
            } catch (e: BandException) {
                throw e.toWearable()
            }
        }
    }

    private companion object {
        const val TAG = "Wearables"
        const val APP_NAME = "GlobalFitness"
        const val TEST_NOTIFICATION_ID = 0x6766
        const val SPP_CONNECT_TIMEOUT_MS = 15_000L
        const val MAX_NAME = 40
    }
}
