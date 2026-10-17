// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.wearable.zeppos

import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandAlarm
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import com.enjoyyourtime.globalfitness.wearable.AuthKeyKind
import com.enjoyyourtime.globalfitness.wearable.GattWearableSession
import com.enjoyyourtime.globalfitness.wearable.KeyCheck
import com.enjoyyourtime.globalfitness.wearable.KeyHelp
import com.enjoyyourtime.globalfitness.wearable.NamePatternDriver
import com.enjoyyourtime.globalfitness.wearable.WearableAlarms
import com.enjoyyourtime.globalfitness.wearable.WearableEvent
import com.enjoyyourtime.globalfitness.wearable.WearableException
import com.enjoyyourtime.globalfitness.wearable.WearableFailure
import com.enjoyyourtime.globalfitness.wearable.WearableFamily
import com.enjoyyourtime.globalfitness.wearable.WearableFeature
import com.enjoyyourtime.globalfitness.wearable.WearableKeys
import com.enjoyyourtime.globalfitness.wearable.WearableSession
import com.enjoyyourtime.globalfitness.wearable.WearableSessionEnv
import com.enjoyyourtime.globalfitness.wearable.WearableTransport
import com.enjoyyourtime.globalfitness.wearable.huami.HuamiModels
import com.enjoyyourtime.globalfitness.wearable.huami.HuamiProtocol
import com.enjoyyourtime.globalfitness.wearable.huami.HuamiUuids
import com.enjoyyourtime.globalfitness.wearable.standard.StandardGatt
import com.enjoyyourtime.globalfitness.wearable.standard.StandardUuids
import java.security.SecureRandom
import java.util.TimeZone
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** One Zepp OS device (Gadgetbridge ZeppOsCoordinator subclasses): its Bluetooth names. */
data class ZeppOsModel(
    val id: String,
    val displayName: String,
    val brand: String,
    val names: List<String>,
    val docUrl: String,
    /** Devices without a display (Helio Strap) get no text notifications. */
    val hasDisplay: Boolean = true,
) {
    /** ZeppOsCoordinator.getSupportedDeviceName: the quoted names, optionally followed by a 4-char suffix. */
    val pattern: Regex = Regex("^(" + names.joinToString("|") { Pattern.quote(it) } + ")([- ]+[A-Z0-9]{4})?$")
}

object ZeppOsModels {
    private const val AMAZFIT = "https://gadgetbridge.org/gadgets/wearables/amazfit/#device__"

    private fun m(id: String, name: String, vararg names: String, doc: String = id, hasDisplay: Boolean = true, brand: String = "Amazfit") =
        ZeppOsModel(id, name, brand, names.toList(), AMAZFIT + doc, hasDisplay)

    val ALL: List<ZeppOsModel> = listOf(
        ZeppOsModel("xiaomi_smart_band_7", "Xiaomi Smart Band 7", "Xiaomi", listOf("Xiaomi Smart Band 7"), "https://gadgetbridge.org/gadgets/wearables/xiaomi/#device__xiaomi_mi_band_7"),
        m("amazfit_band_7", "Amazfit Band 7", "Amazfit Band 7"),
        m("amazfit_gtr_3", "Amazfit GTR 3", "Amazfit GTR 3"),
        m("amazfit_gtr_3_pro", "Amazfit GTR 3 Pro", "Amazfit GTR 3 Pro"),
        m("amazfit_gtr_4", "Amazfit GTR 4", "Amazfit GTR 4", "Amazfit GTR 4 LE"),
        m("amazfit_gtr_mini", "Amazfit GTR Mini", "Amazfit GTR Mini"),
        m("amazfit_gts_3", "Amazfit GTS 3", "Amazfit GTS 3"),
        m("amazfit_gts_4", "Amazfit GTS 4", "Amazfit GTS 4"),
        m("amazfit_gts_4_mini", "Amazfit GTS 4 Mini", "Amazfit GTS 4 Mini"),
        m("amazfit_gts_4_mini_new", "Amazfit GTS 4 mini New", "Amazfit GTS 4 mini New"),
        m("amazfit_bip_5", "Amazfit Bip 5", "Amazfit Bip 5"),
        m("amazfit_bip_5_unity", "Amazfit Bip 5 Unity", "Amazfit Bip 5 Unity"),
        m("amazfit_bip_6", "Amazfit Bip 6", "Amazfit Bip 6"),
        m("amazfit_bip_max", "Amazfit Bip Max", "Amazfit Bip Max", "Amazfit BIP Max"),
        m("amazfit_balance", "Amazfit Balance", "Amazfit Balance"),
        m("amazfit_balance_2", "Amazfit Balance 2", "Amazfit Balance 2"),
        m("amazfit_balance_2_xt", "Amazfit Balance 2 XT", "Amazfit Balance 2 XT"),
        m("amazfit_balance_3", "Amazfit Balance 3", "Amazfit Balance 3", "Balance 3"),
        m("amazfit_balance_ultra", "Amazfit Balance Ultra", "Amazfit Balance Ultra"),
        m("amazfit_active", "Amazfit Active", "Amazfit Active"),
        m("amazfit_active_edge", "Amazfit Active Edge", "Amazfit Active Edge"),
        m("amazfit_active_max", "Amazfit Active Max", "Amazfit Active Max", "Active Max"),
        m("amazfit_active_2_round", "Amazfit Active 2 (Round)", "Active 2 (Round)", doc = "amazfit_active_2"),
        m("amazfit_active_2_nfc", "Amazfit Active 2 NFC (Round)", "Active 2 NFC (Round)", doc = "amazfit_active_2"),
        m("amazfit_active_2_square", "Amazfit Active 2 (Square)", "Active 2 (Square)", "Active 2 NFC (Square)", "Active 2 Square", "Active 2 NFC Square", doc = "amazfit_active_2"),
        m("amazfit_active_3_premium", "Amazfit Active 3 Premium", "Amazfit Active 3 Premium", "Active 3 Premium"),
        m("amazfit_cheetah_pro", "Amazfit Cheetah Pro", "Amazfit Cheetah Pro"),
        m("amazfit_cheetah_round", "Amazfit Cheetah (Round)", "Amazfit Cheetah R", doc = "amazfit_cheetah"),
        m("amazfit_cheetah_square", "Amazfit Cheetah (Square)", "Amazfit Cheetah S", doc = "amazfit_cheetah"),
        m("amazfit_cheetah_2_pro", "Amazfit Cheetah 2 Pro", "Amazfit Cheetah 2 Pro", "Cheetah 2 Pro"),
        m("amazfit_cheetah_2_ultra", "Amazfit Cheetah 2 Ultra", "Amazfit Cheetah 2 Ultra", "Cheetah 2 Ultra"),
        m("amazfit_falcon", "Amazfit Falcon", "Amazfit Falcon"),
        m("amazfit_trex_2", "Amazfit T-Rex 2", "Amazfit T-Rex 2"),
        m("amazfit_trex_ultra", "Amazfit T-Rex Ultra", "Amazfit T-Rex Ultra"),
        m("amazfit_trex_ultra_2", "Amazfit T-Rex Ultra 2", "Amazfit T-Rex Ultra 2", "T-Rex Ultra 2"),
        m("amazfit_trex_3", "Amazfit T-Rex 3", "Amazfit T-Rex 3"),
        m("amazfit_trex_3_pro_44", "Amazfit T-Rex 3 Pro (44 mm)", "T-Rex 3 Pro (44mm)", doc = "amazfit_trex_3_pro"),
        m("amazfit_trex_3_pro_48", "Amazfit T-Rex 3 Pro (48 mm)", "T-Rex 3 Pro (48mm)", doc = "amazfit_trex_3_pro"),
        ZeppOsModel("amazfit_helio_ring", "Amazfit Helio Ring", "Amazfit", listOf("Amazfit Helio Ring"), "https://gadgetbridge.org/gadgets/rings/amazfit/#device__amazfit_helio_ring"),
        m("amazfit_helio_strap", "Amazfit Helio Strap", "Amazfit Helio Strap", hasDisplay = false),
    )

    fun drivers(): List<ZeppOsDriver> = ALL.map { ZeppOsDriver(it) }
}

class ZeppOsDriver(val model: ZeppOsModel) : NamePatternDriver() {
    override val id = model.id
    override val displayName = model.displayName
    override val brand = model.brand
    override val family = WearableFamily.ZEPP_OS
    override val transport = WearableTransport.BLE_GATT
    override val authKey = AuthKeyKind.HEX_32
    override val namePatterns = listOf(model.pattern)
    override val keyHelp = KeyHelp(AuthKeyKind.HEX_32, HuamiModels.SERVER_DOC_URL, model.docUrl, "Zepp")
    override val keywords = listOf("zepp", "zepp os", "huami", "amazfit")
    override val features: Set<WearableFeature> = buildSet {
        add(WearableFeature.BATTERY)
        add(WearableFeature.DEVICE_INFO)
        add(WearableFeature.TIME_SYNC)
        add(WearableFeature.FIND_DEVICE)
        add(WearableFeature.LIVE_HEART_RATE)
        add(WearableFeature.LIVE_STEPS)
        if (model.hasDisplay) add(WearableFeature.NOTIFICATIONS)
        add(WearableFeature.ALARMS)
    }

    override fun createSession(env: WearableSessionEnv): WearableSession = ZeppOsSession(env, model)
}

/**
 * Zepp OS session over BLE (Gadgetbridge ZeppOsBtleSupport + ZeppOsSupport): chunked messages on
 * 0x0016 / 0x0017, ECDH (B-163) authentication with the user's key, then the services list that
 * says which endpoints exist and which are encrypted.
 */
class ZeppOsSession(
    env: WearableSessionEnv,
    private val model: ZeppOsModel,
    private val random: SecureRandom = SecureRandom(),
) : GattWearableSession(env, requestedMtu = 23) {
    override val label = "Zepp OS"

    private class PendingWrite(val service: UUID, val characteristic: UUID, val bytes: ByteArray)

    private class MessageWaiter(val endpoint: Int, val predicate: (ByteArray) -> Boolean, val deferred: CompletableDeferred<ByteArray>)

    private val encoder = ZeppOsChunkEncoder(23)
    private val decoder = ZeppOsChunkDecoder()
    private val writes = Channel<PendingWrite>(Channel.UNLIMITED)
    private val waiters = ArrayList<MessageWaiter>()
    private var writeService: UUID = HuamiUuids.SERVICE_MIBAND
    private var readService: UUID = HuamiUuids.SERVICE_MIBAND

    /** Endpoint → encrypted, from the services list; null until it arrives. */
    private var services: Map<Int, Boolean>? = null
    private var notificationCaps = ZeppOsProtocol.NotificationCapabilities(4, false, false)
    private var findVersion = 2
    private var finding = false
    private var liveActive = false
    private var hrNotifying = false
    private var hrJob: Job? = null
    private var findJob: Job? = null
    private var lastAlarms: List<BandAlarm> = emptyList()

    override fun onClosed(reason: WearableException) {
        hrJob?.cancel()
        findJob?.cancel()
        writes.close()
        synchronized(waiters) {
            waiters.forEach { it.deferred.completeExceptionally(reason) }
            waiters.clear()
        }
        super.onClosed(reason)
    }

    override fun onMtuChanged(mtu: Int) {
        encoder.mtu = mtu
    }

    override suspend fun onLinkReady() {
        val key = (WearableKeys.validate(AuthKeyKind.HEX_32, env.authKey.orEmpty()) as? KeyCheck.Valid)
            ?.let { WearableKeys.hexToBytes(it.normalized) }
            ?: throw WearableException(WearableFailure.NO_KEY)
        writeService = serviceOf(HuamiUuids.CHUNKED_2021_WRITE)
            ?: throw WearableException(WearableFailure.UNSUPPORTED_DEVICE, "no Zepp OS chunked characteristics")
        readService = serviceOf(HuamiUuids.CHUNKED_2021_READ)
            ?: throw WearableException(WearableFailure.UNSUPPORTED_DEVICE, "no Zepp OS chunked read characteristic")
        scope.launch { for (w in writes) if (!write(w.service, w.characteristic, w.bytes)) log("Write to ${w.characteristic} refused") }
        enableNotifications(readService, HuamiUuids.CHUNKED_2021_READ)
        encoder.reset()
        decoder.reset()

        authenticate(key)
        log("Authenticated (Zepp OS)")
        negotiateMtu(247)

        val list = expectMessage(ZeppOsProtocol.SERVICES) { it.firstOrNull()?.toInt() == 0x04 }
        send(ZeppOsProtocol.SERVICES, ZeppOsProtocol.SERVICES_REQUEST)
        services = awaitMessageOrNull(list, env.timeouts.auth)?.let { ZeppOsProtocol.parseServices(it) }
        if (services == null) log("No services list: using default endpoints")

        if (supports(ZeppOsProtocol.NOTIFICATIONS) && model.hasDisplay) {
            val caps = expectMessage(ZeppOsProtocol.NOTIFICATIONS) { it.firstOrNull()?.toInt() == 0x02 }
            send(ZeppOsProtocol.NOTIFICATIONS, ZeppOsProtocol.NOTIFICATION_CAPABILITIES)
            awaitMessageOrNull(caps, env.timeouts.optionalStep)?.let { ZeppOsProtocol.parseNotificationCapabilities(it) }
                ?.let { notificationCaps = it }
        }
        if (supports(ZeppOsProtocol.FIND_DEVICE)) send(ZeppOsProtocol.FIND_DEVICE, ZeppOsProtocol.FIND_CAPABILITIES)
    }

    private suspend fun authenticate(key: ByteArray) {
        val privateKey = ByteArray(EcdhB163.PRIVATE_KEY_SIZE)
        var publicKey: ByteArray?
        do {
            random.nextBytes(privateKey)
            publicKey = EcdhB163.generatePublic(privateKey)
        } while (publicKey == null)

        val challengeReply = expectMessage(ZeppOsProtocol.AUTH) { it.size >= 2 && it[0].toInt() == 0x10 && it[1].toInt() == 0x04 }
        send(ZeppOsProtocol.AUTH, ZeppOsProtocol.authPublicKey(publicKey))
        val challengePayload = awaitMessageOrNull(challengeReply, env.timeouts.auth)
            ?: throw WearableException(WearableFailure.NO_RESPONSE, "no auth answer")
        val challenge = ZeppOsProtocol.parseAuthChallenge(challengePayload)
            ?: throw WearableException(WearableFailure.AUTH_REJECTED, "public key refused")
        val shared = EcdhB163.generateShared(privateKey, challenge.publicKey)
            ?: throw WearableException(WearableFailure.AUTH_REJECTED, "invalid device public key")
        val session = ZeppOsProtocol.deriveSession(shared, key)
        encoder.setEncryption(session.sequence, session.key)
        decoder.setEncryption(session.key)

        val result = expectMessage(ZeppOsProtocol.AUTH) { it.size >= 2 && it[0].toInt() == 0x10 && it[1].toInt() == 0x05 }
        send(ZeppOsProtocol.AUTH, ZeppOsProtocol.authAnswer(challenge.random, key, session.key))
        val answer = awaitMessageOrNull(result, env.timeouts.auth) ?: throw WearableException(WearableFailure.NO_RESPONSE, "no auth result")
        when (ZeppOsProtocol.parseAuthResult(answer)) {
            ZeppOsProtocol.AuthResult.SUCCESS -> Unit
            ZeppOsProtocol.AuthResult.WRONG_KEY -> throw WearableException(WearableFailure.WRONG_KEY)
            else -> throw WearableException(WearableFailure.AUTH_REJECTED, "authentication refused")
        }
    }

    // ---- messages ----

    private fun supports(endpoint: Int): Boolean = services?.containsKey(endpoint) ?: true

    private fun encrypted(endpoint: Int): Boolean =
        services?.get(endpoint) ?: ZeppOsProtocol.DEFAULT_ENCRYPTED[endpoint] ?: false

    private fun send(endpoint: Int, payload: ByteArray) {
        ensureOpen()
        val encrypt = encrypted(endpoint) && encoder.hasEncryption
        for (chunk in encoder.encode(endpoint, payload, encrypt)) {
            writes.trySend(PendingWrite(writeService, HuamiUuids.CHUNKED_2021_WRITE, chunk))
        }
    }

    private fun expectMessage(endpoint: Int, predicate: (ByteArray) -> Boolean = { true }): CompletableDeferred<ByteArray> {
        ensureOpen()
        val d = CompletableDeferred<ByteArray>()
        synchronized(waiters) { waiters += MessageWaiter(endpoint, predicate, d) }
        return d
    }

    private suspend fun awaitMessageOrNull(d: CompletableDeferred<ByteArray>, timeoutMs: Long): ByteArray? {
        val value = withTimeoutOrNull(timeoutMs) { d.await() }
        if (value == null) synchronized(waiters) { waiters.removeAll { it.deferred === d } }
        return value
    }

    private suspend fun awaitMessage(d: CompletableDeferred<ByteArray>): ByteArray = try {
        withTimeout(env.timeouts.request) { d.await() }
    } catch (e: TimeoutCancellationException) {
        synchronized(waiters) { waiters.removeAll { it.deferred === d } }
        throw WearableException(WearableFailure.REQUEST_TIMEOUT, "no answer", e)
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        when (characteristic) {
            HuamiUuids.CHUNKED_2021_READ -> {
                if (value.isEmpty() || value[0].toInt() != 0x03) return
                val result = decoder.decode(value) ?: return
                result.ack?.let { writes.trySend(PendingWrite(readService, HuamiUuids.CHUNKED_2021_READ, it)) }
                result.message?.let { onMessage(it.endpoint, it.payload) }
            }
            StandardUuids.HEART_RATE_MEASUREMENT -> if (liveActive) {
                StandardGatt.parseHeartRate(value)?.let { emit(WearableEvent.HeartRate(it.bpm, it.rrIntervalsMs)) }
            }
            // 0x0016 carries the watch's acks of our chunks (Gadgetbridge only logs them).
        }
    }

    private fun onMessage(endpoint: Int, payload: ByteArray) {
        val waiter = synchronized(waiters) {
            waiters.firstOrNull { it.endpoint == endpoint && runCatching { it.predicate(payload) }.getOrDefault(false) }
                ?.also { waiters.remove(it) }
        }
        if (waiter != null) {
            waiter.deferred.complete(payload)
            return
        }
        if (payload.isEmpty()) return
        val cmd = payload[0].toInt() and 0xff
        when (endpoint) {
            ZeppOsProtocol.CONNECTION -> if (cmd == ZeppOsProtocol.PING) send(ZeppOsProtocol.CONNECTION, ZeppOsProtocol.PONG)
            ZeppOsProtocol.BATTERY -> ZeppOsProtocol.parseBattery(payload)?.let { (level, charging) -> emit(WearableEvent.Battery(level, charging)) }
            ZeppOsProtocol.STEPS -> ZeppOsProtocol.parseRealtimeSteps(payload)?.let { emit(WearableEvent.Activity(steps = it)) }
            ZeppOsProtocol.DEVICE_INFO -> ZeppOsProtocol.parseDeviceInfo(payload)?.let { emit(WearableEvent.Info(it)) }
            ZeppOsProtocol.FIND_DEVICE -> onFindMessage(cmd, payload)
            ZeppOsProtocol.NOTIFICATIONS ->
                ZeppOsProtocol.parseNotificationCapabilities(payload)?.let { notificationCaps = it }
            ZeppOsProtocol.ALARMS -> when (cmd) {
                ZeppOsProtocol.ALARMS_RESPONSE -> ZeppOsProtocol.parseAlarms(payload)?.let {
                    lastAlarms = it
                    emit(WearableEvent.Alarms(it, ZeppOsProtocol.MAX_ALARMS))
                }
                ZeppOsProtocol.ALARMS_CHANGED -> send(ZeppOsProtocol.ALARMS, ZeppOsProtocol.ALARMS_REQUEST)
            }
        }
    }

    private fun onFindMessage(cmd: Int, payload: ByteArray) {
        when (cmd) {
            ZeppOsProtocol.FIND_CAPABILITIES_RESPONSE -> if (payload.size >= 3) findVersion = payload[2].toInt() and 0xff
            ZeppOsProtocol.FIND_ACK -> if (finding && findVersion < 2) {
                // No continuous mode: ring again after a while.
                findJob?.cancel()
                findJob = scope.launch {
                    delay(ZeppOsProtocol.FIND_REPEAT_MS)
                    if (finding) send(ZeppOsProtocol.FIND_DEVICE, ZeppOsProtocol.FIND_START)
                }
            }
            ZeppOsProtocol.FIND_PHONE_START -> {
                emit(WearableEvent.FindPhone)
                send(ZeppOsProtocol.FIND_DEVICE, ZeppOsProtocol.FIND_PHONE_ACK)
            }
        }
    }

    // ---- WearableSession ----

    override suspend fun refreshStatus(includeInfo: Boolean) = withContext(serial) {
        if (includeInfo) {
            if (supports(ZeppOsProtocol.DEVICE_INFO) && services != null) {
                val reply = expectMessage(ZeppOsProtocol.DEVICE_INFO) { it.size >= 2 && it[0].toInt() == 0x02 }
                send(ZeppOsProtocol.DEVICE_INFO, ZeppOsProtocol.DEVICE_INFO_REQUEST)
                awaitMessageOrNull(reply, env.timeouts.optionalStep)?.let { ZeppOsProtocol.parseDeviceInfo(it) }
                    ?.let { emit(WearableEvent.Info(it)) }
            } else {
                emit(WearableEvent.Info(readDeviceInformation()))
            }
        }
        if (supports(ZeppOsProtocol.BATTERY)) {
            val reply = expectMessage(ZeppOsProtocol.BATTERY) { it.firstOrNull()?.toInt() == 0x04 }
            send(ZeppOsProtocol.BATTERY, ZeppOsProtocol.BATTERY_REQUEST)
            ZeppOsProtocol.parseBattery(awaitMessage(reply))?.let { (level, charging) -> emit(WearableEvent.Battery(level, charging)) }
        }
        Unit
    }

    override suspend fun setTime(nowMillis: Long, timeZone: TimeZone, is24Hour: Boolean) = withContext(serial) {
        if (supports(ZeppOsProtocol.TIME) && services != null) {
            send(ZeppOsProtocol.TIME, ZeppOsProtocol.setTime(nowMillis, timeZone))
        } else {
            val cts = serviceOf(StandardUuids.CURRENT_TIME) ?: throw WearableException(WearableFailure.NOT_SUPPORTED)
            write(cts, StandardUuids.CURRENT_TIME, ZeppOsProtocol.currentTimeBytes(nowMillis, timeZone))
        }
        Unit
    }

    override suspend fun findDevice(start: Boolean) = withContext(serial) {
        finding = start
        findJob?.cancel()
        findJob = null
        send(ZeppOsProtocol.FIND_DEVICE, if (start) ZeppOsProtocol.FIND_START else ZeppOsProtocol.FIND_STOP)
    }

    override suspend fun setLiveHeartRate(enabled: Boolean) = withContext(serial) {
        liveActive = enabled
        hrJob?.cancel()
        hrJob = null
        if (enabled && !hrNotifying) {
            serviceOf(StandardUuids.HEART_RATE_MEASUREMENT)?.let { enableNotifications(it, StandardUuids.HEART_RATE_MEASUREMENT) }
            hrNotifying = true
        }
        send(ZeppOsProtocol.HEART_RATE, if (enabled) ZeppOsProtocol.HR_START else ZeppOsProtocol.HR_STOP)
        if (supports(ZeppOsProtocol.STEPS)) {
            send(ZeppOsProtocol.STEPS, if (enabled) ZeppOsProtocol.STEPS_ENABLE else ZeppOsProtocol.STEPS_DISABLE)
        }
        if (enabled) {
            hrJob = scope.launch {
                while (isActive) {
                    delay(ZeppOsProtocol.HR_KEEPALIVE_MS)
                    send(ZeppOsProtocol.HEART_RATE, ZeppOsProtocol.HR_CONTINUE)
                }
            }
        }
    }

    override suspend fun sendNotification(notification: BandNotification) = withContext(serial) {
        if (!model.hasDisplay) unsupported()
        send(
            ZeppOsProtocol.NOTIFICATIONS,
            ZeppOsProtocol.notification(
                id = notification.id,
                packageName = notification.packageName,
                appName = notification.appName,
                title = notification.title,
                body = notification.body,
                sms = HuamiProtocol.categoryFor(notification.packageName) == HuamiProtocol.CATEGORY_SMS,
                caps = notificationCaps,
            ),
        )
    }

    override suspend fun dismissNotification(id: Int, packageName: String) = withContext(serial) {
        if (!model.hasDisplay) unsupported()
        send(ZeppOsProtocol.NOTIFICATIONS, ZeppOsProtocol.deleteNotification(id))
    }

    override suspend fun loadAlarms(): WearableAlarms = withContext(serial) {
        val reply = expectMessage(ZeppOsProtocol.ALARMS) { it.firstOrNull()?.toInt() == ZeppOsProtocol.ALARMS_RESPONSE }
        send(ZeppOsProtocol.ALARMS, ZeppOsProtocol.ALARMS_REQUEST)
        val alarms = ZeppOsProtocol.parseAlarms(awaitMessage(reply)) ?: emptyList()
        lastAlarms = alarms
        emit(WearableEvent.Alarms(alarms, ZeppOsProtocol.MAX_ALARMS))
        WearableAlarms(alarms, ZeppOsProtocol.MAX_ALARMS)
    }

    override suspend fun saveAlarm(id: Int?, spec: AlarmSpec) = withContext(serial) {
        val target = id ?: (1..ZeppOsProtocol.MAX_ALARMS).firstOrNull { c -> lastAlarms.none { it.id == c } }
            ?: throw WearableException(WearableFailure.ALARM_SLOTS_FULL, "all alarm slots used")
        send(ZeppOsProtocol.ALARMS, ZeppOsProtocol.saveAlarm(target - 1, spec))
    }

    override suspend fun deleteAlarm(id: Int) = withContext(serial) {
        send(ZeppOsProtocol.ALARMS, ZeppOsProtocol.deleteAlarm(id - 1))
    }
}
