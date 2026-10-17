// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.wearable.huami

import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import com.enjoyyourtime.globalfitness.wearable.AuthKeyKind
import com.enjoyyourtime.globalfitness.wearable.GattWearableSession
import com.enjoyyourtime.globalfitness.wearable.KeyCheck
import com.enjoyyourtime.globalfitness.wearable.KeyHelp
import com.enjoyyourtime.globalfitness.wearable.LocalAlarms
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
import com.enjoyyourtime.globalfitness.wearable.standard.StandardGatt
import com.enjoyyourtime.globalfitness.wearable.standard.StandardUuids
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One classic Huami device (Gadgetbridge devices/huami coordinators and their support classes):
 * name pattern, auth / crypt flags of InitOperation, and the notification variant.
 */
data class HuamiModel(
    val id: String,
    val displayName: String,
    val brand: String,
    val pattern: Regex,
    val authFlags: Int,
    val cryptFlags: Int,
    /** Four zero bytes after the category (notificationHasExtraHeader). */
    val extraHeader: Boolean,
    val maxNotificationLength: Int = 230,
    val heartRate: Boolean = true,
    /** Mi Band 2 / HRX: header + vibration + 18-character SMS alerts (Mi2TextNotificationStrategy). */
    val mi2Notifications: Boolean = false,
    /** Mi Band 6 rings by a fake incoming call instead of the alert level. */
    val findViaCall: Boolean = false,
    /** Amazfit Neo: continuous HR kept alive with 0x16 every 10 s; find repeats every 10 s. */
    val neo: Boolean = false,
    val docUrl: String,
)

object HuamiModels {
    const val SERVER_DOC_URL = "https://gadgetbridge.org/basics/pairing/huami-xiaomi-server/"
    private const val XIAOMI = "https://gadgetbridge.org/gadgets/wearables/xiaomi/#device__"
    private const val AMAZFIT = "https://gadgetbridge.org/gadgets/wearables/amazfit/#device__"
    const val VENDOR_APP = "Zepp Life / Zepp"

    private const val A8 = HuamiProtocol.AUTH_BYTE
    private const val C80 = 0x80

    private fun ci(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    val ALL: List<HuamiModel> = listOf(
        HuamiModel("xiaomi_mi_band_2", "Mi Band 2", "Xiaomi", ci("MI Band 2"), A8, 0, false, mi2Notifications = true, docUrl = XIAOMI + "xiaomi_mi_band_2"),
        HuamiModel("xiaomi_mi_band_2_hrx", "Mi Band 2 HRX", "Xiaomi", ci("Mi Band HRX|Mi Band 2i"), A8, 0, false, heartRate = false, mi2Notifications = true, docUrl = XIAOMI + "xiaomi_mi_band_2"),
        HuamiModel("xiaomi_mi_band_3", "Mi Band 3", "Xiaomi", ci("Mi Band 3|Xiaomi Band 3"), 0, 0, false, docUrl = XIAOMI + "xiaomi_mi_band_3"),
        HuamiModel("xiaomi_mi_band_4", "Mi Smart Band 4", "Xiaomi", ci("Mi Smart Band 4"), 0, C80, true, docUrl = XIAOMI + "xiaomi_mi_band_4"),
        HuamiModel("xiaomi_mi_band_5", "Mi Smart Band 5", "Xiaomi", ci("Mi Smart Band 5"), 0, C80, true, docUrl = XIAOMI + "xiaomi_mi_band_5"),
        HuamiModel("xiaomi_mi_band_6", "Mi Smart Band 6", "Xiaomi", ci("Mi Smart Band 6"), 0, C80, true, findViaCall = true, docUrl = XIAOMI + "xiaomi_mi_band_6"),
        HuamiModel("amazfit_band_5", "Amazfit Band 5", "Amazfit", ci("Amazfit Band 5"), 0, C80, true, docUrl = AMAZFIT + "amazfit_band_5"),
        HuamiModel("amazfit_x", "Amazfit X", "Amazfit", ci("Amazfit X"), 0, C80, true, docUrl = AMAZFIT + "amazfit_x"),
        HuamiModel("amazfit_neo", "Amazfit Neo", "Amazfit", ci("Amazfit Neo"), 0, C80, false, neo = true, docUrl = AMAZFIT + "amazfit_neo"),
        HuamiModel("amazfit_bip", "Amazfit Bip", "Amazfit", ci("Amazfit Bip Watch"), A8, 0, false, docUrl = AMAZFIT + "amazfit_bip"),
        HuamiModel("amazfit_bip_lite", "Amazfit Bip Lite", "Amazfit", ci("Amazfit Bip Lite"), 0, C80, false, docUrl = AMAZFIT + "amazfit_bip_lite"),
        HuamiModel("amazfit_bip_s", "Amazfit Bip S", "Amazfit", ci("Amazfit Bip S"), 0, C80, true, 512, docUrl = AMAZFIT + "amazfit_bip_s"),
        HuamiModel("amazfit_bip_s_lite", "Amazfit Bip S Lite", "Amazfit", ci("Bip S Lite"), 0, C80, true, 512, docUrl = AMAZFIT + "amazfit_bip_s_lite"),
        HuamiModel("amazfit_bip_u", "Amazfit Bip U", "Amazfit", ci("Amazfit Bip U"), 0, C80, true, 512, docUrl = AMAZFIT + "amazfit_bip_u"),
        HuamiModel("amazfit_bip_u_pro", "Amazfit Bip U Pro", "Amazfit", ci("Amazfit Bip U Pro"), 0, C80, true, 512, docUrl = AMAZFIT + "amazfit_bip_u_pro"),
        HuamiModel("amazfit_pop", "Amazfit Pop", "Amazfit", ci("Amazfit Pop"), 0, C80, true, 512, docUrl = AMAZFIT + "amazfit_pop"),
        HuamiModel("amazfit_pop_pro", "Amazfit Pop Pro", "Amazfit", ci("Amazfit Pop Pro"), 0, C80, true, 512, docUrl = AMAZFIT + "amazfit_pop_pro"),
        HuamiModel("amazfit_bip_3", "Amazfit Bip 3", "Amazfit", ci("^Amazfit Bip 3$"), 0, C80, true, docUrl = AMAZFIT + "amazfit_bip_3"),
        HuamiModel("amazfit_bip_3_pro", "Amazfit Bip 3 Pro", "Amazfit", ci("Amazfit Bip 3 Pro"), 0, C80, true, docUrl = AMAZFIT + "amazfit_bip_3_pro"),
        HuamiModel("amazfit_cor", "Amazfit Cor", "Amazfit", ci("Amazfit Band|Amazfit Cor"), A8, 0, false, docUrl = AMAZFIT + "amazfit_cor"),
        HuamiModel("amazfit_cor_2", "Amazfit Cor 2", "Amazfit", ci("Amazfit Band 2|Amazfit Cor 2"), A8, 0, false, docUrl = AMAZFIT + "amazfit_cor_2"),
        // Case-sensitive in Gadgetbridge.
        HuamiModel("amazfit_gts", "Amazfit GTS", "Amazfit", Regex("Amazfit GTS"), 0, C80, true, docUrl = AMAZFIT + "amazfit_gts"),
        HuamiModel("amazfit_gtr", "Amazfit GTR", "Amazfit", ci("Amazfit GTR"), 0, C80, true, docUrl = AMAZFIT + "amazfit_gtr"),
        HuamiModel("amazfit_gtr_lite", "Amazfit GTR Lite", "Amazfit", ci("Amazfit GTR L"), 0, C80, true, docUrl = AMAZFIT + "amazfit_gtr_lite"),
        HuamiModel("amazfit_gts_2", "Amazfit GTS 2", "Amazfit", ci("Amazfit GTS 2"), 0, C80, true, docUrl = AMAZFIT + "amazfit_gts_2"),
        HuamiModel("amazfit_gts_2e", "Amazfit GTS 2e", "Amazfit", ci("Amazfit GTS 2e"), 0, C80, true, docUrl = AMAZFIT + "amazfit_gts_2"),
        HuamiModel("amazfit_gts_2_mini", "Amazfit GTS 2 mini", "Amazfit", ci("Amazfit GTS2 mini"), 0, C80, true, docUrl = AMAZFIT + "amazfit_gts_2_mini"),
        HuamiModel("amazfit_gtr_2", "Amazfit GTR 2", "Amazfit", ci("Amazfit GTR 2"), 0, C80, true, docUrl = AMAZFIT + "amazfit_gtr_2"),
        HuamiModel("amazfit_gtr_2e", "Amazfit GTR 2e", "Amazfit", ci("Amazfit GTR 2e"), 0, C80, true, docUrl = AMAZFIT + "amazfit_gtr_2"),
        HuamiModel("amazfit_trex", "Amazfit T-Rex", "Amazfit", ci("Amazfit T-Rex"), 0, C80, true, docUrl = AMAZFIT + "amazfit_trex"),
        HuamiModel("amazfit_trex_pro", "Amazfit T-Rex Pro", "Amazfit", ci("Amazfit T-Rex Pro"), 0, C80, true, docUrl = AMAZFIT + "amazfit_trex_pro"),
        HuamiModel("amazfit_verge_lite", "Amazfit Verge Lite", "Amazfit", ci("Amazfit Verge L"), 0, C80, true, docUrl = AMAZFIT + "amazfit_verge_lite"),
        HuamiModel("zepp_e", "Zepp E", "Zepp", ci("Zepp E"), 0, C80, true, docUrl = "https://gadgetbridge.org/gadgets/wearables/amazfit/"),
    )

    fun drivers(): List<HuamiDriver> = ALL.map { HuamiDriver(it) }
}

class HuamiDriver(val model: HuamiModel) : NamePatternDriver() {
    override val id = model.id
    override val displayName = model.displayName
    override val brand = model.brand
    override val family = WearableFamily.HUAMI
    override val transport = WearableTransport.BLE_GATT
    override val authKey = AuthKeyKind.HEX_32
    override val namePatterns = listOf(model.pattern)
    override val keyHelp = KeyHelp(AuthKeyKind.HEX_32, HuamiModels.SERVER_DOC_URL, model.docUrl, HuamiModels.VENDOR_APP)
    override val keywords = listOf("huami", "zepp", "mi fit", "amazfit")
    override val features: Set<WearableFeature> = buildSet {
        add(WearableFeature.BATTERY)
        add(WearableFeature.DEVICE_INFO)
        add(WearableFeature.TIME_SYNC)
        add(WearableFeature.FIND_DEVICE)
        if (model.heartRate) add(WearableFeature.LIVE_HEART_RATE)
        add(WearableFeature.LIVE_STEPS)
        add(WearableFeature.NOTIFICATIONS)
        add(WearableFeature.ALARMS)
    }

    override fun createSession(env: WearableSessionEnv): WearableSession = HuamiSession(env, model)
}

/**
 * Classic Huami session (Gadgetbridge HuamiSupport + InitOperation). Only the re-authentication path
 * with the user's key is used: the "send key" first-pairing step would replace the key the vendor
 * app relies on, so it is never sent.
 */
class HuamiSession(env: WearableSessionEnv, private val model: HuamiModel) : GattWearableSession(env, requestedMtu = 23) {
    override val label = "Huami"
    private val alarms = LocalAlarms(env.store, MAX_ALARMS)
    private var liveActive = false
    private var hrJob: Job? = null
    private var findJob: Job? = null

    override fun onClosed(reason: WearableException) {
        hrJob?.cancel()
        findJob?.cancel()
        super.onClosed(reason)
    }

    override suspend fun onLinkReady() {
        val key = (WearableKeys.validate(AuthKeyKind.HEX_32, env.authKey.orEmpty()) as? KeyCheck.Valid)
            ?.let { WearableKeys.hexToBytes(it.normalized) }
            ?: throw WearableException(WearableFailure.NO_KEY)
        val authService = serviceOf(HuamiUuids.AUTH)
            ?: throw WearableException(WearableFailure.UNSUPPORTED_DEVICE, "no Huami auth characteristic")
        serviceOf(HuamiUuids.NOTIFICATION_FF03)?.let { enableNotifications(it, HuamiUuids.NOTIFICATION_FF03) }
        enableNotifications(authService, HuamiUuids.AUTH)

        var reply = expect(HuamiUuids.AUTH) { HuamiProtocol.parseAuth(it) != null }
        write(authService, HuamiUuids.AUTH, HuamiProtocol.requestRandom(model.authFlags, model.cryptFlags))
        val random = when (val r = HuamiProtocol.parseAuth(await(reply, env.timeouts.auth))) {
            is HuamiProtocol.AuthReply.Random -> r.random
            HuamiProtocol.AuthReply.WrongKey -> throw WearableException(WearableFailure.WRONG_KEY)
            else -> throw WearableException(WearableFailure.AUTH_REJECTED, "random number refused")
        }
        reply = expect(HuamiUuids.AUTH) { HuamiProtocol.parseAuth(it) != null }
        write(authService, HuamiUuids.AUTH, HuamiProtocol.encryptedRandom(random, key, model.authFlags, model.cryptFlags))
        // Gadgetbridge writes the time right after the encrypted number.
        writeTime(env.clock(), TimeZone.getDefault())
        when (HuamiProtocol.parseAuth(await(reply, env.timeouts.auth))) {
            HuamiProtocol.AuthReply.Success -> Unit
            HuamiProtocol.AuthReply.WrongKey -> throw WearableException(WearableFailure.WRONG_KEY)
            else -> throw WearableException(WearableFailure.AUTH_REJECTED, "authentication refused")
        }
        log("Authenticated (Huami)")
        for (c in listOf(HuamiUuids.CONFIGURATION, HuamiUuids.BATTERY_INFO, HuamiUuids.DEVICE_EVENT)) {
            serviceOf(c)?.let { enableNotifications(it, c) }
        }
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        when (characteristic) {
            HuamiUuids.BATTERY_INFO -> HuamiProtocol.parseBattery(value)?.let { emit(WearableEvent.Battery(it.level, it.charging)) }
            HuamiUuids.REALTIME_STEPS -> HuamiProtocol.parseRealtimeSteps(value)?.let { emit(WearableEvent.Activity(steps = it)) }
            StandardUuids.HEART_RATE_MEASUREMENT -> if (liveActive) {
                StandardGatt.parseHeartRate(value)?.let { emit(WearableEvent.HeartRate(it.bpm, it.rrIntervalsMs)) }
            }
            HuamiUuids.DEVICE_EVENT -> when (value.firstOrNull()?.toInt()?.and(0xff)) {
                HuamiProtocol.EVENT_FIND_PHONE_START -> {
                    emit(WearableEvent.FindPhone)
                    scope.launch { serviceOf(HuamiUuids.CONFIGURATION)?.let { write(it, HuamiUuids.CONFIGURATION, HuamiProtocol.FIND_PHONE_ACK) } }
                }
                else -> Unit
            }
        }
    }

    override suspend fun refreshStatus(includeInfo: Boolean) = withContext(serial) {
        if (includeInfo) {
            val info = readDeviceInformation()
            emit(WearableEvent.Info(info.copy(firmware = info.firmware?.removePrefix("V"))))
        }
        serviceOf(HuamiUuids.BATTERY_INFO)?.let { s ->
            read(s, HuamiUuids.BATTERY_INFO)?.let { onNotification(HuamiUuids.BATTERY_INFO, it) }
        }
        Unit
    }

    private suspend fun writeTime(nowMillis: Long, timeZone: TimeZone) {
        val service = serviceOf(StandardUuids.CURRENT_TIME) ?: return
        write(service, StandardUuids.CURRENT_TIME, HuamiProtocol.currentTime(nowMillis, timeZone))
    }

    override suspend fun setTime(nowMillis: Long, timeZone: TimeZone, is24Hour: Boolean) = withContext(serial) {
        writeTime(nowMillis, timeZone)
    }

    override suspend fun findDevice(start: Boolean) = withContext(serial) {
        findJob?.cancel()
        findJob = null
        if (model.findViaCall) {
            val service = serviceOf(StandardUuids.NEW_ALERT) ?: throw WearableException(WearableFailure.NOT_SUPPORTED)
            write(service, StandardUuids.NEW_ALERT, if (start) HuamiProtocol.callAlert(FIND_CALLER_NAME) else HuamiProtocol.CALL_ALERT_STOP)
            return@withContext
        }
        val service = serviceOf(StandardUuids.ALERT_LEVEL) ?: throw WearableException(WearableFailure.NOT_SUPPORTED)
        write(service, StandardUuids.ALERT_LEVEL, if (start) HuamiProtocol.FIND_START else HuamiProtocol.FIND_STOP)
        if (start) {
            val interval = if (model.neo) 10_000L else HuamiProtocol.FIND_REPEAT_MS
            findJob = scope.launch {
                while (isActive) {
                    delay(interval)
                    write(service, StandardUuids.ALERT_LEVEL, HuamiProtocol.FIND_START)
                }
            }
        }
    }

    override suspend fun setLiveHeartRate(enabled: Boolean) = withContext(serial) {
        if (!model.heartRate) unsupported()
        liveActive = enabled
        hrJob?.cancel()
        hrJob = null
        val hrService = serviceOf(StandardUuids.HEART_RATE_CONTROL_POINT) ?: throw WearableException(WearableFailure.NOT_SUPPORTED)
        serviceOf(HuamiUuids.REALTIME_STEPS)?.let { s ->
            if (enabled) {
                enableNotifications(s, HuamiUuids.REALTIME_STEPS)
                read(s, HuamiUuids.REALTIME_STEPS)?.let { onNotification(HuamiUuids.REALTIME_STEPS, it) }
            }
        }
        if (!enabled) {
            write(hrService, StandardUuids.HEART_RATE_CONTROL_POINT, HuamiProtocol.HR_STOP_CONTINUOUS)
            return@withContext
        }
        enableNotifications(hrService, StandardUuids.HEART_RATE_MEASUREMENT)
        if (model.neo) {
            write(hrService, StandardUuids.HEART_RATE_CONTROL_POINT, HuamiProtocol.HR_START_CONTINUOUS)
            hrJob = scope.launch {
                while (isActive) {
                    write(hrService, StandardUuids.HEART_RATE_CONTROL_POINT, HuamiProtocol.HR_PING)
                    delay(NEO_PING_MS)
                }
            }
        } else {
            // Gadgetbridge's live view re-enables continuous measurement every second to keep it going.
            hrJob = scope.launch {
                while (isActive) {
                    write(hrService, StandardUuids.HEART_RATE_CONTROL_POINT, HuamiProtocol.HR_STOP_MANUAL)
                    write(hrService, StandardUuids.HEART_RATE_CONTROL_POINT, HuamiProtocol.HR_START_CONTINUOUS)
                    delay(KEEPALIVE_MS)
                }
            }
        }
    }

    override suspend fun sendNotification(notification: BandNotification) = withContext(serial) {
        val chunkedService = serviceOf(HuamiUuids.CHUNKED_TRANSFER)
        when {
            chunkedService != null -> {
                val payload = HuamiProtocol.notificationPayload(
                    notification.packageName,
                    notification.appName,
                    notification.title,
                    notification.body,
                    model.extraHeader,
                    model.maxNotificationLength,
                )
                for (frame in HuamiProtocol.chunkedFrames(0, payload, mtu)) write(chunkedService, HuamiUuids.CHUNKED_TRANSFER, frame)
            }
            model.mi2Notifications -> {
                val alert = serviceOf(StandardUuids.NEW_ALERT) ?: throw WearableException(WearableFailure.NOT_SUPPORTED)
                val category = HuamiProtocol.categoryFor(notification.packageName)
                val icon = HuamiProtocol.iconFor(notification.packageName)
                write(alert, StandardUuids.NEW_ALERT, HuamiProtocol.newAlert(category, icon, "", 18))
                serviceOf(StandardUuids.ALERT_LEVEL)?.let { write(it, StandardUuids.ALERT_LEVEL, HuamiProtocol.mi2Vibration()) }
                val text = notification.body.ifEmpty { notification.title }.take(40)
                for (frame in HuamiProtocol.mi2TextAlerts(text)) write(alert, StandardUuids.NEW_ALERT, frame)
            }
            else -> {
                val alert = serviceOf(StandardUuids.NEW_ALERT) ?: throw WearableException(WearableFailure.NOT_SUPPORTED)
                val message = HuamiProtocol.notificationBody(notification.title, notification.body)
                write(
                    alert,
                    StandardUuids.NEW_ALERT,
                    HuamiProtocol.newAlert(
                        HuamiProtocol.categoryFor(notification.packageName),
                        HuamiProtocol.iconFor(notification.packageName),
                        message,
                        model.maxNotificationLength,
                    ),
                )
            }
        }
        Unit
    }

    /** Classic Huami devices have no "remove notification" message. */
    override suspend fun dismissNotification(id: Int, packageName: String) = Unit

    override suspend fun loadAlarms(): WearableAlarms = WearableAlarms(alarms.list(), MAX_ALARMS)

    override suspend fun saveAlarm(id: Int?, spec: AlarmSpec) = withContext(serial) {
        val config = serviceOf(HuamiUuids.CONFIGURATION) ?: throw WearableException(WearableFailure.NOT_SUPPORTED)
        val alarm = alarms.put(id, spec) ?: throw WearableException(WearableFailure.NOT_SUPPORTED, "all alarm slots used")
        write(config, HuamiUuids.CONFIGURATION, HuamiProtocol.alarm(alarm.id - 1, alarm.spec))
        Unit
    }

    override suspend fun deleteAlarm(id: Int) = withContext(serial) {
        val config = serviceOf(HuamiUuids.CONFIGURATION) ?: throw WearableException(WearableFailure.NOT_SUPPORTED)
        alarms.remove(id)
        write(config, HuamiUuids.CONFIGURATION, HuamiProtocol.alarm(id - 1, null))
        Unit
    }

    companion object {
        const val MAX_ALARMS = 10
        const val KEEPALIVE_MS = 1_000L
        const val NEO_PING_MS = 10_000L
        const val FIND_CALLER_NAME = "GlobalFitness"
    }
}
