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
import com.enjoyyourtime.globalfitness.band.BandDatabase
import com.enjoyyourtime.globalfitness.band.BandLog
import com.enjoyyourtime.globalfitness.band.BandPermissions
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
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Android wiring of the wearables: the driver registry, detection (advertisements and bonded
 * devices), configured devices and their keys, one [WearableController] per configured device,
 * heart rate storage in band.db and notification forwarding. UI-free; the screens observe it.
 *
 * [scope] is the app scope (main thread), as [WearableController] requires.
 */
class WearableManager(
    private val context: Context,
    private val scope: CoroutineScope,
    val registry: WearableRegistry = AllWearables.registry(),
    /** The localized test notification (supplied by the UI layer). */
    private val testNotification: (Long) -> BandNotification = { now ->
        BandNotification(TEST_NOTIFICATION_ID, context.packageName, APP_NAME, APP_NAME, "", now)
    },
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
        keys.keyedAddresses.value.forEach { address -> keys.load(address)?.let { log.registerSecret(it) } }
    }

    // ---- configured devices ----

    fun driverFor(device: ConfiguredWearable): WearableDriver? = registry.find(device.driverId)

    /** Adds (or replaces, same address) a configured wearable. */
    fun addDevice(driver: WearableDriver, address: String, name: String, fromSuggestion: Boolean): ConfiguredWearable {
        val device = ConfiguredWearable(
            id = UUID.randomUUID().toString(),
            driverId = driver.id,
            address = address.uppercase(Locale.ROOT),
            name = name,
            addedAt = System.currentTimeMillis(),
            fromSuggestion = fromSuggestion,
        )
        settings.updateConfigured { list -> list.filterNot { it.matchesAddress(address) } + device }
        _suggestion.update { if (it?.address.equals(address, ignoreCase = true)) null else it }
        log.add("Wearable added: ${driver.id} (${device.address})")
        return device
    }

    fun updateDevice(device: ConfiguredWearable) = settings.updateConfigured { list -> list.map { if (it.id == device.id) device else it } }

    /** Removes the device, its key and its stored state; it is not suggested again. */
    fun removeDevice(id: String) {
        val device = settings.configured.value.firstOrNull { it.id == id } ?: return
        controllers.remove(id)?.disconnect()
        stateWatchers.remove(id)?.cancel()
        setLive(id, false)
        settings.updateConfigured { list -> list.filterNot { it.id == id } }
        settings.clearDriverStore(id)
        keys.clear(device.address)
        settings.dismiss(device.address)
        detector.forget(device.address)
        log.add("Wearable removed (${device.address})")
    }

    /** Validates and stores the key of [device]; the key is never logged. */
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

    /** The controller of a configured device (created on first use). */
    fun controller(device: ConfiguredWearable): WearableController? {
        controllers[device.id]?.let { if (it.device == device) return it }
        val driver = driverFor(device) ?: return null
        controllers.remove(device.id)?.disconnect()
        stateWatchers.remove(device.id)?.cancel()
        val controller = WearableController(scope, log::add, AndroidPlatform(), device, driver)
        controllers[device.id] = controller
        stateWatchers[device.id] = scope.launch {
            controller.state.collect { state -> setLive(device.id, state.liveActive) }
        }
        return controller
    }

    private fun setLive(id: String, active: Boolean) {
        liveIds.update { if (active) it + id else it - id }
        _liveHeartRateActive.value = liveIds.value.isNotEmpty()
    }

    /** Recorded heart rate (band.db) between [fromMs] and [toMs]. Blocking: call on IO. */
    fun recordedHeartRate(fromMs: Long, toMs: Long): List<HeartRateSample> = database.heartRateSamples(fromMs, toMs)

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

    fun dismissSuggestion(address: String) {
        settings.dismiss(address)
        _suggestion.update { if (it?.address.equals(address, ignoreCase = true)) null else it }
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
        val enabled = settings.forwardingEnabled.value
        val allowed = settings.allowedPackages.value
        controllers.values.forEach { it.onPhoneNotification(n, enabled, allowed) }
    }

    fun onPhoneNotificationRemoved(key: String) = controllers.values.forEach { it.onPhoneNotificationRemoved(key) }

    // ---- platform ----

    private inner class AndroidPlatform : WearablePlatform {
        override fun preconditionFailure(transport: WearableTransport): WearableFailure? {
            val adapter = bluetooth?.adapter ?: return WearableFailure.BLUETOOTH_UNAVAILABLE
            if (!BandPermissions.hasConnect(context)) return WearableFailure.MISSING_PERMISSION
            if (!adapter.isEnabled) return WearableFailure.BLUETOOTH_OFF
            return null
        }

        override fun authKey(address: String): String? = keys.load(address)

        override fun createSession(driver: WearableDriver, device: ConfiguredWearable, authKey: String?): WearableSession =
            driver.createSession(
                WearableSessionEnv(
                    address = device.address,
                    name = device.name,
                    authKey = authKey,
                    config = device.config,
                    store = settings.driverStore(device.id),
                    companion = companionInfo(),
                    scope = scope,
                    links = links,
                    log = log::add,
                ),
            )

        @SuppressLint("MissingPermission") // checked with BandPermissions.hasConnect
        override fun isBusyElsewhere(address: String): Boolean {
            if (!BandPermissions.hasConnect(context)) return false
            return try {
                bluetooth?.getConnectedDevices(BluetoothProfile.GATT).orEmpty().any { it.address.equals(address, ignoreCase = true) }
            } catch (_: SecurityException) {
                false
            }
        }

        override fun syncTimeOnConnect(device: ConfiguredWearable): Boolean = device.config[ConfiguredWearable.CONFIG_SYNC_TIME] != "0"

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

        override fun testNotification(nowMillis: Long): BandNotification = testNotification.invoke(nowMillis)
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
    }
}
