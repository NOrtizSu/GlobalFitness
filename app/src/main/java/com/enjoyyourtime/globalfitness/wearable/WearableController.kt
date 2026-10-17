package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.band.HeartRateRecorder
import com.enjoyyourtime.globalfitness.band.HeartRateSample
import com.enjoyyourtime.globalfitness.band.NotificationForwarder
import com.enjoyyourtime.globalfitness.band.PhoneNotification
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandAlarm
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class WearableConnection { DISCONNECTED, CONNECTING, READY }

/** Live readings of the current session. */
data class WearableLive(
    /** Null while the device is still measuring. */
    val heartRate: Int? = null,
    val steps: Int? = null,
    val calories: Int? = null,
    val standingHours: Int? = null,
    val rrIntervalsMs: List<Int> = emptyList(),
    val updatedAtMs: Long = 0L,
)

/** Everything the per-device screen shows; text comes from resources in the UI. */
data class WearableState(
    val connection: WearableConnection = WearableConnection.DISCONNECTED,
    val protocol: String? = null,
    val failure: WearableFailure? = null,
    /** The failure looks like "another app holds the device" (vendor app connected). */
    val busyElsewhere: Boolean = false,
    val info: WearableDeviceInfo? = null,
    val batteryLevel: Int? = null,
    val charging: Boolean? = null,
    val worn: Boolean? = null,
    val finding: Boolean = false,
    val liveActive: Boolean = false,
    val live: WearableLive? = null,
    val alarms: List<BandAlarm>? = null,
    val maxAlarms: Int? = null,
    val alarmsBusy: Boolean = false,
    val lastTimeSyncMs: Long? = null,
    /** After a disconnect: the vendor app can take the device back. */
    val releasedForVendorApp: Boolean = false,
)

/** User actions, for [WearableMessage.ActionFailed]. */
enum class WearableAction { FIND, SYNC_TIME, REFRESH, LIVE_HEART_RATE, ALARMS, SAVE_ALARM, DELETE_ALARM, TEST_NOTIFICATION }

/** Short confirmations and errors for a snackbar; the UI picks the string resource. */
sealed interface WearableMessage {
    data object TimeSynced : WearableMessage
    data object TestNotificationSent : WearableMessage

    /** The user pressed "found it" on the device. */
    data object FoundIt : WearableMessage

    /** The device asks the phone to ring. */
    data object FindPhoneRequested : WearableMessage
    data object ConnectFirst : WearableMessage
    data class ActionFailed(val action: WearableAction, val failure: WearableFailure) : WearableMessage
}

/** What the controller needs from Android; replaced by fakes in tests. */
interface WearablePlatform {
    /** Why connecting over [transport] is impossible right now (Bluetooth off, permission...), or null. */
    fun preconditionFailure(transport: WearableTransport): WearableFailure?

    /** The stored key of [address] (normalized), or null. */
    fun authKey(address: String): String?

    /** A new, unconnected session of [driver] for [device]. */
    fun createSession(driver: WearableDriver, device: ConfiguredWearable, authKey: String?): WearableSession

    /** True when another app holds a connection to the device. */
    fun isBusyElsewhere(address: String): Boolean

    fun syncTimeOnConnect(device: ConfiguredWearable): Boolean

    fun is24HourFormat(): Boolean

    fun saveHeartRate(sample: HeartRateSample, address: String, driverId: String)

    /** Called right before connecting (e.g. to stop a scan). */
    fun beforeConnect()

    /** The localized test notification. */
    fun testNotification(nowMillis: Long): BandNotification
}

data class WearableTiming(
    val idleTimeoutMs: Long = 2 * 60_000L,
    val leaveGraceMs: Long = 5_000L,
    val stopTimeoutMs: Long = 1_500L,
)

/**
 * The on-demand connection to one configured wearable, for any driver: connect / authenticate /
 * disconnect, the driver's functions and the timers that hand the device back to its vendor app
 * (most accept a single connection): the link closes after [WearableTiming.idleTimeoutMs] without
 * user actions (live heart rate on a visible screen keeps it), [WearableTiming.leaveGraceMs] after
 * leaving the screen, or on request. It replaces the former Smart Band 9-only controller with the same behaviour.
 *
 * [scope] must be single-threaded (the main thread in the app): all mutable state is only touched
 * from coroutines on it.
 */
class WearableController(
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val platform: WearablePlatform,
    val device: ConfiguredWearable,
    val driver: WearableDriver,
    private val timing: WearableTiming = WearableTiming(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val heartRateRecorder = HeartRateRecorder()
    private val forwarder = NotificationForwarder()

    private val _state = MutableStateFlow(WearableState())
    val state: StateFlow<WearableState> = _state.asStateFlow()

    private val _heartRate = MutableStateFlow<List<HeartRateSample>>(emptyList())

    /** Heart rate samples of the current live session (for the live chart). */
    val heartRate: StateFlow<List<HeartRateSample>> = _heartRate.asStateFlow()

    private val _messages = MutableSharedFlow<WearableMessage>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<WearableMessage> = _messages.asSharedFlow()

    private var session: WearableSession? = null
    private var connectJob: Job? = null
    private var initJob: Job? = null
    private var idleJob: Job? = null
    private var leaveJob: Job? = null
    private var visibleScreens = 0
    private val uiVisible: Boolean get() = visibleScreens > 0
    private var sessionSteps = 0

    // ---- screen lifecycle ----

    /** Each visible screen of this device calls this once, and [onUiHidden] once when it stops being visible. */
    fun onUiVisible() {
        scope.launch {
            visibleScreens++
            leaveJob?.cancel()
            leaveJob = null
        }
    }

    fun onUiHidden() {
        scope.launch {
            visibleScreens = (visibleScreens - 1).coerceAtLeast(0)
            // Another screen of this device (e.g. opened twice) still shows it.
            if (uiVisible) return@launch
            if (_state.value.connection == WearableConnection.DISCONNECTED) return@launch
            leaveJob?.cancel()
            leaveJob = scope.launch {
                delay(timing.leaveGraceMs)
                disconnectNow("Disconnected after leaving the screen")
            }
        }
    }

    // ---- connection ----

    fun connect() {
        scope.launch {
            if (connectJob?.isActive == true || session?.isOpen == true) return@launch
            leaveJob?.cancel()
            val previous = connectJob
            connectJob = scope.launch {
                // A cancelled attempt must finish its cleanup before a new one starts.
                previous?.cancelAndJoin()
                runConnect()
            }
        }
    }

    fun disconnect() {
        scope.launch { disconnectNow("Disconnected") }
    }

    private suspend fun runConnect() {
        platform.preconditionFailure(driver.transport)?.let { return onFailure(it, null) }
        val key = platform.authKey(device.address)
        if (driver.needsAuthKey && key == null) return onFailure(WearableFailure.NO_KEY, null)
        platform.beforeConnect()
        _state.value = WearableState(connection = WearableConnection.CONNECTING)
        _heartRate.value = emptyList()
        log("Connecting to ${device.name} (${device.address}), driver ${driver.id}, ${driver.transport}")
        if (platform.isBusyElsewhere(device.address)) log("Note: another app has a connection to this device")

        val newSession = try {
            platform.createSession(driver, device, key)
        } catch (e: WearableException) {
            onFailure(e.failure, device.address)
            return
        } catch (e: Exception) {
            log("Error creating the session: ${e.javaClass.simpleName}")
            onFailure(WearableFailure.CONNECT_FAILED, device.address)
            return
        }
        session = newSession
        try {
            newSession.open()
        } catch (e: CancellationException) {
            newSession.close()
            if (session === newSession) {
                session = null
                _state.value = WearableState(releasedForVendorApp = true)
            }
            throw e
        } catch (e: WearableException) {
            if (session === newSession) session = null
            onFailure(e.failure, device.address)
            return
        } catch (e: Exception) {
            // A driver bug or a platform surprise (SecurityException, a malformed frame...) must not
            // leave a half-open session holding the device: close it and report a failed connection.
            log("Error while connecting: ${e.javaClass.simpleName}")
            newSession.close()
            if (session === newSession) session = null
            onFailure(WearableFailure.CONNECT_FAILED, device.address)
            return
        }
        _state.update { it.copy(connection = WearableConnection.READY, protocol = newSession.label) }
        log("Connected (${newSession.label})")
        watch(newSession)
        touch()
        // Its own job: the connection attempt is over, a disconnect must not have to cancel it.
        initJob = scope.launch { initialRequests(newSession) }
    }

    private fun watch(s: WearableSession) {
        val collector = scope.launch { s.events.collect { handleEvent(it) } }
        scope.launch {
            val reason = s.awaitClosed()
            collector.cancel()
            if (session !== s) return@launch
            session = null
            idleJob?.cancel()
            initJob?.cancel()
            forwarder.clear()
            val failure = reason.failure.takeIf { it != WearableFailure.CLOSED }
            _state.update {
                WearableState(
                    failure = failure,
                    info = it.info,
                    batteryLevel = it.batteryLevel,
                    charging = it.charging,
                    alarms = it.alarms,
                    maxAlarms = it.maxAlarms,
                    releasedForVendorApp = true,
                )
            }
            log("Connection closed: the device is free for its vendor app")
        }
    }

    private suspend fun disconnectNow(reason: String) {
        val current = currentCoroutineContext()[Job]
        if (idleJob !== current) idleJob?.cancel()
        if (leaveJob !== current) leaveJob?.cancel()
        // Once started, a disconnect always completes (the device must be released).
        withContext(NonCancellable) {
            initJob?.cancel()
            val pending = connectJob
            if (pending != null && pending !== current && !pending.isCompleted) {
                pending.cancelAndJoin()
                log("Connection attempt cancelled")
            }
            // A connect() requested while we were waiting is newer than this disconnect.
            if (connectJob !== pending) return@withContext
            val s = session ?: return@withContext
            // Leave the device as its vendor app expects it: no live stream, no ringing.
            if (_state.value.liveActive) withTimeoutOrNull(timing.stopTimeoutMs) { runCatching { s.setLiveHeartRate(false) } }
            if (_state.value.finding) withTimeoutOrNull(timing.stopTimeoutMs) { runCatching { s.findDevice(false) } }
            log(reason)
            s.close()
        }
    }

    private fun onFailure(failure: WearableFailure, address: String?) {
        val busy = address != null && failure in BUSY_SYMPTOMS && platform.isBusyElsewhere(address)
        log("Error: $failure${if (busy) " (device busy with another app)" else ""}")
        _state.value = WearableState(failure = failure, busyElsewhere = busy, releasedForVendorApp = address != null)
    }

    /** Time (optional), status with device info, then alarms where supported. */
    private suspend fun initialRequests(s: WearableSession) {
        try {
            if (driver.supports(WearableFeature.TIME_SYNC) && platform.syncTimeOnConnect(device)) sendTime(s)
            tolerate { s.refreshStatus(includeInfo = true) }
            if (driver.supports(WearableFeature.ALARMS)) loadAlarms(s)
        } catch (e: WearableException) {
            // A closed session is reported by watch().
            log("Initial requests stopped: ${e.failure}")
        }
    }

    /** Runs [block], ignoring a missing answer or an unsupported function. */
    private suspend fun tolerate(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: WearableException) {
            if (e.failure != WearableFailure.REQUEST_TIMEOUT && e.failure != WearableFailure.NOT_SUPPORTED) throw e
            log("No answer (${e.failure})")
        }
    }

    // ---- actions ----

    fun findDevice(start: Boolean) = command(WearableAction.FIND) { s ->
        // Updated first, so the device's own answer to "stop" is not taken for a "found it" press.
        _state.update { it.copy(finding = start) }
        try {
            s.findDevice(start)
        } catch (e: WearableException) {
            if (start) _state.update { it.copy(finding = false) }
            throw e
        }
    }

    fun syncTime() = command(WearableAction.SYNC_TIME) { s ->
        sendTime(s)
        _messages.tryEmit(WearableMessage.TimeSynced)
    }

    fun refreshStatus() = command(WearableAction.REFRESH) { s -> s.refreshStatus(includeInfo = false) }

    fun setLiveHeartRate(enabled: Boolean) = command(WearableAction.LIVE_HEART_RATE) { s ->
        if (enabled) {
            heartRateRecorder.reset()
            _heartRate.value = emptyList()
            sessionSteps = 0
        }
        // Set before sending: the first reading can arrive before the call returns.
        _state.update { it.copy(liveActive = enabled, live = if (enabled) null else it.live) }
        try {
            s.setLiveHeartRate(enabled)
        } catch (e: WearableException) {
            if (enabled) _state.update { it.copy(liveActive = false) }
            throw e
        }
    }

    fun refreshAlarms() = command(WearableAction.ALARMS) { s -> loadAlarms(s) }

    /** Creates ([id] null) or edits an alarm, then reloads the list from the device. */
    fun saveAlarm(id: Int?, spec: AlarmSpec) = command(WearableAction.SAVE_ALARM) { s ->
        tolerate { s.saveAlarm(id, spec) }
        loadAlarms(s)
    }

    fun deleteAlarm(id: Int) = command(WearableAction.DELETE_ALARM) { s ->
        s.deleteAlarm(id)
        loadAlarms(s)
    }

    fun sendTestNotification() = command(WearableAction.TEST_NOTIFICATION) { s ->
        s.sendNotification(platform.testNotification(clock()))
        _messages.tryEmit(WearableMessage.TestNotificationSent)
    }

    // ---- notification forwarding ----

    fun onPhoneNotification(n: PhoneNotification, forwardingEnabled: Boolean, allowedPackages: Set<String>) {
        scope.launch {
            if (!driver.supports(WearableFeature.NOTIFICATIONS)) return@launch
            val s = readySession()
            val notification = forwarder.onPosted(n, forwardingEnabled, allowedPackages, connected = s != null) ?: return@launch
            try {
                s?.sendNotification(notification)
                log("Notification forwarded (${n.appName})")
            } catch (e: WearableException) {
                log("Could not forward the notification: ${e.failure}")
            }
        }
    }

    fun onPhoneNotificationRemoved(key: String) {
        scope.launch {
            val s = readySession()
            val (id, packageName) = forwarder.onRemoved(key, connected = s != null) ?: return@launch
            try {
                s?.dismissNotification(id, packageName)
            } catch (e: WearableException) {
                log("Could not remove the notification: ${e.failure}")
            }
        }
    }

    fun clearForwarded() {
        scope.launch { forwarder.clear() }
    }

    // ---- events ----

    private fun handleEvent(event: WearableEvent) {
        when (event) {
            is WearableEvent.Battery -> _state.update {
                it.copy(batteryLevel = event.level ?: it.batteryLevel, charging = event.charging ?: it.charging)
            }
            is WearableEvent.DeviceState -> _state.update {
                it.copy(
                    worn = event.worn ?: it.worn,
                    charging = event.charging ?: it.charging,
                    batteryLevel = event.batteryLevel ?: it.batteryLevel,
                )
            }
            is WearableEvent.Info -> {
                _state.update { it.copy(info = merge(it.info, event.info)) }
                log("Model ${event.info.model ?: "?"}, firmware ${event.info.firmware ?: "?"}")
            }
            is WearableEvent.HeartRate -> onHeartRate(event)
            is WearableEvent.Activity -> onActivity(event)
            WearableEvent.FindStopped -> {
                val wasFinding = _state.value.finding
                _state.update { it.copy(finding = false) }
                if (wasFinding) _messages.tryEmit(WearableMessage.FoundIt)
            }
            WearableEvent.FindPhone -> _messages.tryEmit(WearableMessage.FindPhoneRequested)
            is WearableEvent.Alarms -> _state.update { it.copy(alarms = event.alarms, maxAlarms = event.maxAlarms ?: it.maxAlarms) }
        }
    }

    private fun onHeartRate(event: WearableEvent.HeartRate) {
        if (!_state.value.liveActive) return
        val now = clock()
        val bpm = event.bpm.takeIf { HeartRateRecorder.isValid(it) }
        _state.update {
            val live = it.live ?: WearableLive()
            it.copy(live = live.copy(heartRate = bpm, rrIntervalsMs = event.rrIntervalsMs, updatedAtMs = now))
        }
        touch()
        val sample = heartRateRecorder.accept(event.bpm, now) ?: return
        _heartRate.update { points -> (points + sample).filter { it.timestampMs >= now - LIVE_WINDOW_MS } }
        platform.saveHeartRate(sample, device.address, driver.id)
    }

    private fun onActivity(event: WearableEvent.Activity) {
        val now = clock()
        event.stepsIncrement?.let { sessionSteps += it }
        _state.update {
            val live = it.live ?: WearableLive()
            it.copy(
                live = live.copy(
                    steps = event.steps ?: if (event.stepsIncrement != null) sessionSteps else live.steps,
                    calories = event.calories ?: live.calories,
                    standingHours = event.standingHours ?: live.standingHours,
                    updatedAtMs = now,
                ),
            )
        }
    }

    private suspend fun loadAlarms(s: WearableSession) {
        _state.update { it.copy(alarmsBusy = true) }
        try {
            val result = s.loadAlarms()
            _state.update { it.copy(alarms = result.alarms, maxAlarms = result.maxAlarms ?: it.maxAlarms) }
        } catch (e: WearableException) {
            if (e.failure != WearableFailure.REQUEST_TIMEOUT && e.failure != WearableFailure.NOT_SUPPORTED) throw e
            log("Alarms not available (${e.failure})")
        } finally {
            _state.update { it.copy(alarmsBusy = false) }
        }
    }

    private suspend fun sendTime(s: WearableSession) {
        val now = clock()
        s.setTime(now, TimeZone.getDefault(), platform.is24HourFormat())
        _state.update { it.copy(lastTimeSyncMs = now) }
        log("Time sent to the device")
    }

    // ---- helpers ----

    private fun readySession(): WearableSession? =
        session?.takeIf { it.isOpen && _state.value.connection == WearableConnection.READY }

    /** Runs a user action on the open session; any action keeps the connection alive. */
    private fun command(action: WearableAction, block: suspend (WearableSession) -> Unit) {
        scope.launch {
            val s = readySession()
            if (s == null) {
                _messages.tryEmit(WearableMessage.ConnectFirst)
                return@launch
            }
            touch()
            try {
                block(s)
            } catch (e: WearableException) {
                log("$action: ${e.failure}")
                _messages.tryEmit(WearableMessage.ActionFailed(action, e.failure))
            }
        }
    }

    /** (Re)starts the idle timer. Live heart rate keeps the link up while the screen is visible. */
    private fun touch() {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (true) {
                delay(timing.idleTimeoutMs)
                if (!(_state.value.liveActive && uiVisible)) break
            }
            disconnectNow("Disconnected after inactivity")
        }
    }

    private fun merge(old: WearableDeviceInfo?, new: WearableDeviceInfo): WearableDeviceInfo = WearableDeviceInfo(
        model = new.model ?: old?.model,
        firmware = new.firmware ?: old?.firmware,
        serial = new.serial ?: old?.serial,
        hardware = new.hardware ?: old?.hardware,
        manufacturer = new.manufacturer ?: old?.manufacturer,
    )

    companion object {
        private const val LIVE_WINDOW_MS = 5 * 60_000L
        private val BUSY_SYMPTOMS = setOf(WearableFailure.CONNECT_FAILED, WearableFailure.NO_RESPONSE)
    }
}
