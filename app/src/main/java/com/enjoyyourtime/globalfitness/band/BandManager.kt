package com.enjoyyourtime.globalfitness.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import android.util.Log
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
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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

/**
 * Experimental direct mode for the Xiaomi Smart Band 9: owns the on-demand connection and exposes the
 * band's functions to the UI and to the notification listener.
 *
 * The band accepts a single connection, normally held by Mi Fitness (which keeps syncing to Health
 * Connect), so the link is only open while the band screen is in use: it closes after [IDLE_TIMEOUT_MS]
 * without user actions, [LEAVE_GRACE_MS] after leaving the screen, or on request.
 */
class BandManager(private val context: Context, private val scope: CoroutineScope) {
    val store = BandStore(context)
    val log = BandLog()
    val scanner = BandScanner(context, scope)
    private val database = BandDatabase(context)
    private val bluetooth: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
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
    private var idleJob: Job? = null
    private var leaveJob: Job? = null
    private var uiVisible = false

    init {
        store.loadAuthKey()?.let { log.registerSecret(it.toHex()) }
    }

    // ---- setup ----

    fun saveAuthKey(key: AuthKey) {
        log.registerSecret(key.toHex())
        store.saveAuthKey(key)
        log.add("Clave de autenticación guardada")
    }

    fun clearAuthKey() {
        disconnect()
        store.clearAuthKey()
        log.add("Clave de autenticación borrada")
    }

    fun selectBand(candidate: BandCandidate) {
        if (store.selected.value?.address != candidate.address) disconnect()
        store.selectBand(candidate.address, candidate.name)
        log.add("Pulsera elegida: ${candidate.name} (${candidate.address})")
    }

    // ---- screen lifecycle ----

    fun onUiVisible() {
        uiVisible = true
        leaveJob?.cancel()
        leaveJob = null
    }

    fun onUiHidden() {
        uiVisible = false
        scanner.stopScan()
        if (_state.value.connection == BandConnectionState.DISCONNECTED) return
        leaveJob?.cancel()
        leaveJob = scope.launch {
            delay(LEAVE_GRACE_MS)
            disconnectNow("Desconectada al salir de la pantalla de la pulsera")
        }
    }

    // ---- connection ----

    fun connect() {
        if (connectJob?.isActive == true || session?.isOpen == true) return
        leaveJob?.cancel()
        connectJob = scope.launch { runConnect() }
    }

    fun disconnect() {
        scope.launch { disconnectNow("Desconectada") }
    }

    private suspend fun runConnect() {
        val precondition = preconditionFailure()
        if (precondition != null) {
            onFailure(precondition, null)
            return
        }
        val key = requireNotNull(store.loadAuthKey())
        val band = requireNotNull(store.selected.value)
        val adapter = requireNotNull(bluetooth?.adapter)
        scanner.stopScan()
        _state.value = BandState(connection = BandConnectionState.CONNECTING)
        _heartRate.value = emptyList()
        log.add("Conectando con ${band.name} (${band.address}) por Bluetooth clásico (SPP)")
        if (isBusyElsewhere(band.address)) log.add("Aviso: la pulsera ya tiene una conexión BLE abierta por otra app")

        var link: BandLink? = null
        var newSession: XiaomiSession? = null
        try {
            link = RfcommLink.connect(context, adapter, band.address, CONNECT_TIMEOUT_MS)
            _state.update { it.copy(connection = BandConnectionState.AUTHENTICATING) }
            log.add("Socket RFCOMM abierto")
            newSession = XiaomiSession(link, XiaomiAuth(key), companionInfo(), scope, log::add)
            session = newSession
            newSession.handshake()
        } catch (e: CancellationException) {
            newSession?.close() ?: link?.close()
            session = null
            _state.value = BandState(releasedForMiFitness = true)
            throw e
        } catch (e: BandException) {
            session = null
            onFailure(e.failure, band.address)
            return
        }

        val ready = requireNotNull(newSession)
        _state.update { it.copy(connection = BandConnectionState.READY, protocol = ready.protocolLabel) }
        log.add("Conectada (${ready.protocolLabel})")
        watch(ready)
        touch()
        initialRequests(ready)
    }

    private fun watch(s: XiaomiSession) {
        val collector = scope.launch { s.commands.collect { handleCommand(s, it) } }
        scope.launch {
            val reason = s.awaitClosed()
            collector.cancel()
            if (session !== s) return@launch
            session = null
            idleJob?.cancel()
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
        val pending = connectJob
        if (pending?.isActive == true) {
            pending.cancel()
            log.add("Conexión cancelada")
            return
        }
        val s = session ?: return
        withContext(NonCancellable) {
            // Leave the band as Mi Fitness expects it: no realtime stream, no vibration.
            if (_state.value.liveActive) withTimeoutOrNull(STOP_TIMEOUT_MS) { runCatching { s.send(XiaomiCommands.realtimeStats(false)) } }
            if (_state.value.finding) withTimeoutOrNull(STOP_TIMEOUT_MS) { runCatching { s.send(XiaomiCommands.findWatch(false)) } }
            log.add(reason)
            s.close()
        }
    }

    private fun onFailure(failure: BandFailure, address: String?) {
        // A refused or silent connection while another app holds a BLE link is the "busy" case.
        val busy = address != null && failure in BUSY_SYMPTOMS && isBusyElsewhere(address)
        val message = if (busy) BUSY_MESSAGE else failure.message
        log.add("Error: $message")
        Log.w(TAG, "Band connection failed: $failure")
        _state.value = BandState(failure = failure, failureMessage = message, releasedForMiFitness = address != null)
    }

    @SuppressLint("MissingPermission") // only called after BandPermissions.hasConnect
    private fun isBusyElsewhere(address: String): Boolean {
        if (!BandPermissions.hasConnect(context)) return false
        return try {
            bluetooth?.getConnectedDevices(BluetoothProfile.GATT).orEmpty().any { it.address.equals(address, ignoreCase = true) }
        } catch (_: SecurityException) {
            false
        }
    }

    private fun preconditionFailure(): BandFailure? {
        val adapter = bluetooth?.adapter ?: return BandFailure.BLUETOOTH_UNAVAILABLE
        if (!BandPermissions.hasConnect(context)) return BandFailure.MISSING_PERMISSION
        if (!adapter.isEnabled) return BandFailure.BLUETOOTH_OFF
        if (store.loadAuthKey() == null) return BandFailure.NO_KEY
        if (store.selected.value == null) return BandFailure.NO_DEVICE
        return null
    }

    private fun companionInfo(): CompanionInfo {
        val language = Locale.getDefault().language.take(2).uppercase(Locale.ROOT)
        return CompanionInfo(Build.VERSION.SDK_INT, Build.MODEL ?: "Android", language.ifEmpty { "ES" })
    }

    /** As Gadgetbridge on auth success: time (optional), device info, device state, battery; then alarms. */
    private suspend fun initialRequests(s: XiaomiSession) {
        try {
            if (store.syncTimeOnConnect.value) sendTime(s)
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
        s.send(XiaomiCommands.findWatch(start))
        _state.update { it.copy(finding = start) }
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
        s.send(XiaomiCommands.realtimeStats(enabled))
        _state.update { it.copy(liveActive = enabled, live = if (enabled) null else it.live) }
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
                    packageName = context.packageName,
                    appName = "GlobalFitness",
                    title = "GlobalFitness",
                    body = "Notificación de prueba del modo directo",
                    whenMillis = System.currentTimeMillis(),
                ),
            ),
        )
        _messages.tryEmit("Notificación de prueba enviada")
    }

    fun setSyncTimeOnConnect(enabled: Boolean) = store.setSyncTimeOnConnect(enabled)

    fun setForwardingEnabled(enabled: Boolean) {
        store.setForwardingEnabled(enabled)
        if (!enabled) forwarder.clear()
    }

    fun setPackageAllowed(packageName: String, allowed: Boolean) = store.setPackageAllowed(packageName, allowed)

    // ---- notification forwarding (called by BandNotificationListener) ----

    fun onPhoneNotification(n: PhoneNotification) {
        scope.launch {
            store.recordSeenApp(n.packageName, n.appName)
            val s = readySession()
            val bandNotification = forwarder.onPosted(
                n,
                enabled = store.forwardingEnabled.value,
                allowed = store.allowedPackages.value,
                connected = s != null,
            ) ?: return@launch
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
                _state.update { it.copy(finding = false) }
                _messages.tryEmit("La pulsera indica que ya la encontraste")
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
        val now = System.currentTimeMillis()
        val bpm = stats.heartRate.takeIf { HeartRateRecorder.isValid(it) }
        _state.update { it.copy(live = LiveStats(bpm, stats.steps, stats.calories, stats.standingHours, now)) }
        touch()
        val sample = heartRateRecorder.accept(stats.heartRate, now) ?: return
        _heartRate.update { points -> (points + sample).filter { it.timestampMs >= now - LIVE_WINDOW_MS } }
        val address = store.selected.value?.address
        scope.launch(Dispatchers.IO) {
            try {
                database.insertHeartRate(listOf(sample), address)
            } catch (e: android.database.SQLException) {
                Log.w(TAG, "Could not store heart rate sample", e)
            }
        }
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
        val now = System.currentTimeMillis()
        s.send(XiaomiCommands.setTime(now, TimeZone.getDefault(), DateFormat.is24HourFormat(context)))
        _state.update { it.copy(lastTimeSyncMs = now) }
        log.add("Hora enviada a la pulsera")
    }

    // ---- helpers ----

    private fun readySession(): XiaomiSession? =
        session?.takeIf { it.isOpen && _state.value.connection == BandConnectionState.READY }

    /** Runs a user action on the open session; any action keeps the connection alive. */
    private fun command(label: String, block: suspend (XiaomiSession) -> Unit) {
        val s = readySession()
        if (s == null) {
            _messages.tryEmit("Conecta primero la pulsera")
            return
        }
        touch()
        scope.launch {
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
                delay(IDLE_TIMEOUT_MS)
                if (!(_state.value.liveActive && uiVisible)) break
            }
            disconnectNow("Desconectada tras 2 minutos sin uso")
        }
    }

    private fun chargingFromRaw(raw: Int): Boolean? = when (raw) {
        1 -> true
        2, 3 -> false
        else -> null
    }

    companion object {
        private const val TAG = "BandDirect"
        const val IDLE_TIMEOUT_MS = 2 * 60_000L
        const val LEAVE_GRACE_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val STOP_TIMEOUT_MS = 1_500L
        private const val LIVE_WINDOW_MS = 5 * 60_000L
        private const val TEST_NOTIFICATION_ID = 0x6766 // "gf"
        private val BUSY_SYMPTOMS = setOf(BandFailure.CONNECT_FAILED, BandFailure.NO_RESPONSE)
        private const val BUSY_MESSAGE =
            "La pulsera está conectada a otra app (casi seguro Mi Fitness) y solo admite una conexión. " +
                "Detén temporalmente Mi Fitness (Ajustes → Aplicaciones → Mi Fitness → Forzar detención) y vuelve a intentarlo. " +
                "Al terminar, abre Mi Fitness para que siga sincronizando."
    }
}
