package com.enjoyyourtime.globalfitness.band

import com.enjoyyourtime.globalfitness.band.proto.XiaomiProto
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKey
import com.enjoyyourtime.globalfitness.band.xiaomi.BandAlarm
import com.enjoyyourtime.globalfitness.band.xiaomi.BandException
import com.enjoyyourtime.globalfitness.band.xiaomi.BandFailure
import com.enjoyyourtime.globalfitness.band.xiaomi.BandLink
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import com.enjoyyourtime.globalfitness.band.xiaomi.CompanionInfo
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiAuth
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiCommands
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiSession
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

enum class BandConnectionState { DISCONNECTED, CONNECTING, AUTHENTICATING, READY }

data class BandDeviceInfo(val model: String, val firmware: String, val serial: String)

data class LiveStats(
    /** Null while the band is still measuring. */
    val heartRate: Int?,
    val steps: Int,
    val calories: Int,
    val standingHours: Int,
    val updatedAtMs: Long,
)

data class BandState(
    val connection: BandConnectionState = BandConnectionState.DISCONNECTED,
    val protocol: String? = null,
    val failure: BandFailure? = null,
    val failureMessage: String? = null,
    val info: BandDeviceInfo? = null,
    val batteryLevel: Int? = null,
    val charging: Boolean? = null,
    val worn: Boolean? = null,
    val finding: Boolean = false,
    val liveActive: Boolean = false,
    val live: LiveStats? = null,
    val alarms: List<BandAlarm>? = null,
    val maxAlarms: Int? = null,
    val alarmsBusy: Boolean = false,
    val lastTimeSyncMs: Long? = null,
    /** After a disconnect: Mi Fitness can take the band back. */
    val releasedForMiFitness: Boolean = false,
)

/** What the controller needs from Android (Bluetooth, settings, storage). Replaced by fakes in tests. */
interface BandPlatform {
    /** Why connecting is impossible right now (Bluetooth, permission, key, band), or null. */
    fun preconditionFailure(): BandFailure?

    fun authKey(): AuthKey?

    fun selectedBand(): SelectedBand?

    /** Opens the byte link to the band; throws [BandException]. */
    suspend fun openLink(address: String): BandLink

    /** True when another app holds a connection to the band. */
    fun isBusyElsewhere(address: String): Boolean

    fun companionInfo(): CompanionInfo

    fun syncTimeOnConnect(): Boolean

    fun is24HourFormat(): Boolean

    fun saveHeartRate(sample: HeartRateSample, address: String?)

    /** Called right before connecting (e.g. to stop a BLE scan). */
    fun beforeConnect()

    val appPackageName: String
}

data class BandTiming(
    val idleTimeoutMs: Long = 2 * 60_000L,
    val leaveGraceMs: Long = 5_000L,
    val stopTimeoutMs: Long = 1_500L,
    val session: XiaomiSession.Timeouts = XiaomiSession.Timeouts(),
)

/**
 * The on-demand direct connection: connect / authenticate / disconnect, the band's functions and the
 * timers that hand the band back to Mi Fitness (it accepts a single connection, and Mi Fitness keeps
 * syncing to Health Connect): the link closes after [BandTiming.idleTimeoutMs] without user actions,
 * [BandTiming.leaveGraceMs] after leaving the screen, or on request.
 *
 * [scope] must be single-threaded (the main thread in the app): all mutable state is only touched from
 * coroutines on it, which is why every public entry point launches into it.
 */
class BandController(
    private val scope: CoroutineScope,
    private val log: BandLog,
    private val platform: BandPlatform,
    private val timing: BandTiming = BandTiming(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val heartRateRecorder = HeartRateRecorder()
    private val forwarder = NotificationForwarder()

    private val _state = MutableStateFlow(BandState())
    val state: StateFlow<BandState> = _state.asStateFlow()

    private val _heartRate = MutableStateFlow<List<HeartRateSample>>(emptyList())

    /** Heart rate samples received during the current live session (for the live chart). */
    val heartRate: StateFlow<List<HeartRateSample>> = _heartRate.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Short user-facing confirmations and errors (snackbar). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var session: XiaomiSession? = null
    private var connectJob: Job? = null
    private var initJob: Job? = null
    private var idleJob: Job? = null
    private var leaveJob: Job? = null
    private var uiVisible = false

    // ---- screen lifecycle ----

    fun onUiVisible() {
        scope.launch {
            uiVisible = true
            leaveJob?.cancel()
            leaveJob = null
        }
    }

    fun onUiHidden() {
        scope.launch {
            uiVisible = false
            if (_state.value.connection == BandConnectionState.DISCONNECTED) return@launch
            leaveJob?.cancel()
            leaveJob = scope.launch {
                delay(timing.leaveGraceMs)
                disconnectNow("Desconectada al salir de la pantalla de la pulsera")
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
        scope.launch { disconnectNow("Desconectada") }
    }

    private suspend fun runConnect() {
        val precondition = platform.preconditionFailure()
        if (precondition != null) {
            onFailure(precondition, null)
            return
        }
        val key = platform.authKey() ?: return onFailure(BandFailure.NO_KEY, null)
        val band = platform.selectedBand() ?: return onFailure(BandFailure.NO_DEVICE, null)
        platform.beforeConnect()
        _state.value = BandState(connection = BandConnectionState.CONNECTING)
        _heartRate.value = emptyList()
        log.add("Conectando con ${band.name} (${band.address}) por Bluetooth clásico (SPP)")
        if (platform.isBusyElsewhere(band.address)) log.add("Aviso: la pulsera ya tiene una conexión abierta por otra app")

        var link: BandLink? = null
        var newSession: XiaomiSession? = null
        try {
            link = platform.openLink(band.address)
            _state.update { it.copy(connection = BandConnectionState.AUTHENTICATING) }
            log.add("Socket RFCOMM abierto")
            newSession = XiaomiSession(link, XiaomiAuth(key), platform.companionInfo(), scope, log::add, timing.session)
            session = newSession
            newSession.handshake()
        } catch (e: CancellationException) {
            newSession?.close() ?: link?.close()
            if (session === newSession) {
                session = null
                _state.value = BandState(releasedForMiFitness = link != null)
            }
            throw e
        } catch (e: BandException) {
            if (session === newSession) session = null
            onFailure(e.failure, band.address)
            return
        }

        val ready = requireNotNull(newSession)
        _state.update { it.copy(connection = BandConnectionState.READY, protocol = ready.protocolLabel) }
        log.add("Conectada (${ready.protocolLabel})")
        watch(ready)
        touch()
        // Its own job: the connection attempt is over, a disconnect must not have to cancel it.
        initJob = scope.launch { initialRequests(ready) }
    }

    private fun watch(s: XiaomiSession) {
        val collector = scope.launch { s.commands.collect { handleCommand(s, it) } }
        scope.launch {
            val reason = s.awaitClosed()
            collector.cancel()
            if (session !== s) return@launch
            session = null
            idleJob?.cancel()
            initJob?.cancel()
            forwarder.clear()
            val failure = reason.failure.takeIf { it != BandFailure.CLOSED }
            _state.update {
                BandState(
                    failure = failure,
                    failureMessage = failure?.let { reason.message },
                    info = it.info,
                    batteryLevel = it.batteryLevel,
                    charging = it.charging,
                    releasedForMiFitness = true,
                )
            }
            log.add("Conexión cerrada: la pulsera queda libre para Mi Fitness")
        }
    }

    private suspend fun disconnectNow(reason: String) {
        // The idle and leave timers end up here themselves: do not cancel the running one.
        val current = currentCoroutineContext()[Job]
        if (idleJob !== current) idleJob?.cancel()
        if (leaveJob !== current) leaveJob?.cancel()
        // Once started, a disconnect always completes (the band must be released).
        withContext(NonCancellable) {
            initJob?.cancel()
            val pending = connectJob
            if (pending != null && pending !== current && !pending.isCompleted) {
                pending.cancelAndJoin()
                log.add("Conexión cancelada")
            }
            // A connect() requested while we were waiting is newer than this disconnect: leave it alone.
            if (connectJob !== pending) return@withContext
            val s = session ?: return@withContext
            // Leave the band as Mi Fitness expects it: no realtime stream, no vibration.
            if (_state.value.liveActive) withTimeoutOrNull(timing.stopTimeoutMs) { runCatching { s.send(XiaomiCommands.realtimeStats(false)) } }
            if (_state.value.finding) withTimeoutOrNull(timing.stopTimeoutMs) { runCatching { s.send(XiaomiCommands.findWatch(false)) } }
            log.add(reason)
            s.close()
        }
    }

    private fun onFailure(failure: BandFailure, address: String?) {
        // A refused or silent connection while another app holds a link is the "busy" case.
        val busy = address != null && failure in BUSY_SYMPTOMS && platform.isBusyElsewhere(address)
        val message = if (busy) BUSY_MESSAGE else failure.message
        log.add("Error: $message")
        _state.value = BandState(failure = failure, failureMessage = message, releasedForMiFitness = address != null)
    }

    /** As Gadgetbridge on auth success: time (optional), device info, device state, battery; then alarms. */
    private suspend fun initialRequests(s: XiaomiSession) {
        try {
            if (platform.syncTimeOnConnect()) sendTime(s)
            for (request in listOf(XiaomiCommands.getDeviceInfo(), XiaomiCommands.getDeviceState(), XiaomiCommands.getBattery())) {
                try {
                    handleCommand(s, s.request(request))
                } catch (e: BandException) {
                    if (e.failure != BandFailure.REQUEST_TIMEOUT) throw e
                    log.add("Sin respuesta a ${request.type}/${request.subtype}")
                }
            }
            loadAlarms(s)
        } catch (e: BandException) {
            // A closed session is reported by watch().
            if (e.failure == BandFailure.REQUEST_TIMEOUT) log.add("La pulsera no respondió a la lista de alarmas")
        }
    }

    // ---- actions ----

    fun findDevice(start: Boolean) = command(if (start) "Buscar pulsera" else "Parar búsqueda") { s ->
        // Updated first, so the band's own 2/18 answer to "stop" is not taken for a "found it" press.
        _state.update { it.copy(finding = start) }
        try {
            s.send(XiaomiCommands.findWatch(start))
        } catch (e: BandException) {
            if (start) _state.update { it.copy(finding = false) }
            throw e
        }
    }

    fun syncTime() = command("Sincronizar hora") { s ->
        sendTime(s)
        _messages.tryEmit("Hora de la pulsera sincronizada")
    }

    fun refreshStatus() = command("Actualizar estado") { s ->
        handleCommand(s, s.request(XiaomiCommands.getDeviceState()))
        handleCommand(s, s.request(XiaomiCommands.getBattery()))
    }

    fun setLiveHeartRate(enabled: Boolean) = command(if (enabled) "Pulso en vivo" else "Parar pulso en vivo") { s ->
        if (enabled) {
            heartRateRecorder.reset()
            _heartRate.value = emptyList()
        }
        // Set before sending: the first 8/47 can arrive before send() returns and must not trigger the
        // "nobody is listening" stop.
        _state.update { it.copy(liveActive = enabled, live = if (enabled) null else it.live) }
        try {
            s.send(XiaomiCommands.realtimeStats(enabled))
        } catch (e: BandException) {
            if (enabled) _state.update { it.copy(liveActive = false) }
            throw e
        }
    }

    fun refreshAlarms() = command("Alarmas") { s -> loadAlarms(s) }

    /** Creates ([id] null) or edits an alarm, then reloads the list from the band. */
    fun saveAlarm(id: Int?, spec: AlarmSpec) = command("Guardar alarma") { s ->
        if (id == null) {
            try {
                s.request(XiaomiCommands.createAlarm(spec))
            } catch (e: BandException) {
                if (e.failure != BandFailure.REQUEST_TIMEOUT) throw e
                log.add("La pulsera no confirmó la alarma nueva")
            }
        } else {
            s.send(XiaomiCommands.editAlarm(id, spec))
        }
        loadAlarms(s)
    }

    fun deleteAlarm(id: Int) = command("Borrar alarma") { s ->
        s.send(XiaomiCommands.deleteAlarms(listOf(id)))
        loadAlarms(s)
    }

    fun sendTestNotification() = command("Notificación de prueba") { s ->
        s.send(
            XiaomiCommands.notification(
                BandNotification(
                    id = TEST_NOTIFICATION_ID,
                    packageName = platform.appPackageName,
                    appName = "GlobalFitness",
                    title = "GlobalFitness",
                    body = "Notificación de prueba del modo directo",
                    whenMillis = clock(),
                ),
            ),
        )
        _messages.tryEmit("Notificación de prueba enviada")
    }

    // ---- notification forwarding ----

    fun onPhoneNotification(n: PhoneNotification, forwardingEnabled: Boolean, allowedPackages: Set<String>) {
        scope.launch {
            val s = readySession()
            val bandNotification = forwarder.onPosted(n, forwardingEnabled, allowedPackages, connected = s != null)
                ?: return@launch
            try {
                s?.send(XiaomiCommands.notification(bandNotification))
                log.add("Notificación reenviada (${n.appName})")
            } catch (e: BandException) {
                log.add("No se pudo reenviar la notificación: ${e.message}")
            }
        }
    }

    fun onPhoneNotificationRemoved(key: String) {
        scope.launch {
            val s = readySession()
            val (id, packageName) = forwarder.onRemoved(key, connected = s != null) ?: return@launch
            try {
                s?.send(XiaomiCommands.dismissNotification(id, packageName))
            } catch (e: BandException) {
                log.add("No se pudo retirar la notificación: ${e.message}")
            }
        }
    }

    fun clearForwarded() {
        scope.launch { forwarder.clear() }
    }

    // ---- incoming commands ----

    private fun handleCommand(s: XiaomiSession, cmd: XiaomiProto.Command) {
        when (cmd.type) {
            XiaomiCommands.TYPE_SYSTEM -> handleSystem(s, cmd)
            XiaomiCommands.TYPE_HEALTH ->
                if (cmd.subtype == XiaomiCommands.HEALTH_REALTIME_STATS_EVENT && cmd.health.hasRealTimeStats()) {
                    onRealtimeStats(s, cmd.health.realTimeStats)
                }
            XiaomiCommands.TYPE_SCHEDULE -> when (cmd.subtype) {
                XiaomiCommands.SCHEDULE_ALARMS_GET -> if (cmd.schedule.hasAlarms()) {
                    val alarms = cmd.schedule.alarms
                    _state.update {
                        it.copy(
                            alarms = XiaomiCommands.parseAlarms(alarms),
                            maxAlarms = if (alarms.hasMaxAlarms()) alarms.maxAlarms else it.maxAlarms,
                        )
                    }
                }
                XiaomiCommands.SCHEDULE_ALARMS_CREATE -> launchCommand(s) { loadAlarms(it) }
            }
            else -> Unit
        }
    }

    private fun handleSystem(s: XiaomiSession, cmd: XiaomiProto.Command) {
        val system = cmd.system
        when (cmd.subtype) {
            XiaomiCommands.SYSTEM_DEVICE_INFO -> if (system.hasDeviceInfo()) {
                val info = system.deviceInfo
                _state.update { it.copy(info = BandDeviceInfo(info.model, info.firmware, info.serialNumber)) }
                log.add("Modelo ${info.model}, firmware ${info.firmware}")
            }
            XiaomiCommands.SYSTEM_BATTERY -> if (system.power.hasBattery()) {
                val battery = system.power.battery
                _state.update {
                    it.copy(
                        batteryLevel = if (battery.hasLevel()) battery.level else it.batteryLevel,
                        // XiaomiSystemService: 1 charging, 2 and 3 not charging.
                        charging = if (battery.hasState()) chargingFromRaw(battery.state) ?: it.charging else it.charging,
                    )
                }
            }
            XiaomiCommands.SYSTEM_DEVICE_STATE_GET -> {
                if (!system.hasBasicDeviceState()) {
                    launchCommand(s) { handleCommand(it, it.request(XiaomiCommands.getBattery())) }
                    return
                }
                val state = system.basicDeviceState
                _state.update {
                    it.copy(
                        charging = state.isCharging,
                        worn = state.isWorn,
                        batteryLevel = if (state.hasBatteryLevel()) state.batteryLevel else it.batteryLevel,
                    )
                }
            }
            XiaomiCommands.SYSTEM_DEVICE_STATE -> {
                if (system.hasDeviceState()) {
                    val state = system.deviceState
                    _state.update {
                        it.copy(
                            charging = if (state.hasChargingState()) chargingFromRaw(state.chargingState) ?: it.charging else it.charging,
                            worn = when (state.wearingState) {
                                1 -> true
                                2 -> false
                                else -> it.worn
                            },
                        )
                    }
                }
                launchCommand(s) { handleCommand(it, it.request(XiaomiCommands.getBattery())) }
            }
            XiaomiCommands.SYSTEM_FIND_WATCH -> if (system.hasFindDevice() && system.findDevice == XiaomiCommands.FIND_STOP) {
                val wasFinding = _state.value.finding
                _state.update { it.copy(finding = false) }
                if (wasFinding) _messages.tryEmit(FOUND_MESSAGE)
            }
            XiaomiCommands.SYSTEM_FIND_PHONE -> log.add("La pulsera pide buscar el teléfono (no disponible en modo directo)")
        }
    }

    private fun onRealtimeStats(s: XiaomiSession, stats: XiaomiProto.RealTimeStats) {
        if (!_state.value.liveActive) {
            // Gadgetbridge failsafe: nobody is listening, stop the stream.
            launchCommand(s) { it.send(XiaomiCommands.realtimeStats(false)) }
            return
        }
        val now = clock()
        val bpm = stats.heartRate.takeIf { HeartRateRecorder.isValid(it) }
        _state.update { it.copy(live = LiveStats(bpm, stats.steps, stats.calories, stats.standingHours, now)) }
        touch()
        val sample = heartRateRecorder.accept(stats.heartRate, now) ?: return
        _heartRate.update { points -> (points + sample).filter { it.timestampMs >= now - LIVE_WINDOW_MS } }
        platform.saveHeartRate(sample, platform.selectedBand()?.address)
    }

    private suspend fun loadAlarms(s: XiaomiSession) {
        _state.update { it.copy(alarmsBusy = true) }
        try {
            handleCommand(s, s.request(XiaomiCommands.getAlarms()))
        } finally {
            _state.update { it.copy(alarmsBusy = false) }
        }
    }

    private suspend fun sendTime(s: XiaomiSession) {
        val now = clock()
        s.send(XiaomiCommands.setTime(now, TimeZone.getDefault(), platform.is24HourFormat()))
        _state.update { it.copy(lastTimeSyncMs = now) }
        log.add("Hora enviada a la pulsera")
    }

    // ---- helpers ----

    private fun readySession(): XiaomiSession? =
        session?.takeIf { it.isOpen && _state.value.connection == BandConnectionState.READY }

    /** Runs a user action on the open session; any action keeps the connection alive. */
    private fun command(label: String, block: suspend (XiaomiSession) -> Unit) {
        scope.launch {
            val s = readySession()
            if (s == null) {
                _messages.tryEmit("Conecta primero la pulsera")
                return@launch
            }
            touch()
            try {
                block(s)
            } catch (e: BandException) {
                log.add("$label: ${e.message}")
                _messages.tryEmit("$label: ${e.message}")
            }
        }
    }

    private fun launchCommand(s: XiaomiSession, block: suspend (XiaomiSession) -> Unit) {
        scope.launch {
            try {
                block(s)
            } catch (e: BandException) {
                log.add(e.message ?: e.failure.name)
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
            disconnectNow("Desconectada por inactividad")
        }
    }

    private fun chargingFromRaw(raw: Int): Boolean? = when (raw) {
        1 -> true
        2, 3 -> false
        else -> null
    }

    companion object {
        const val FOUND_MESSAGE = "La pulsera indica que ya la encontraste"
        private const val LIVE_WINDOW_MS = 5 * 60_000L
        private const val TEST_NOTIFICATION_ID = 0x6766 // "gf"
        private val BUSY_SYMPTOMS = setOf(BandFailure.CONNECT_FAILED, BandFailure.NO_RESPONSE)
        const val BUSY_MESSAGE =
            "La pulsera está conectada a otra app (casi seguro Mi Fitness) y solo admite una conexión. " +
                "Detén temporalmente Mi Fitness (Ajustes → Aplicaciones → Mi Fitness → Forzar detención) y vuelve a intentarlo. " +
                "Al terminar, abre Mi Fitness para que siga sincronizando."
    }
}
