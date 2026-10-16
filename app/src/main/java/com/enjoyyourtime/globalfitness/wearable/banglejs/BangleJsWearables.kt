// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.wearable.banglejs

import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import com.enjoyyourtime.globalfitness.wearable.AuthKeyKind
import com.enjoyyourtime.globalfitness.wearable.GattWearableSession
import com.enjoyyourtime.globalfitness.wearable.LocalAlarms
import com.enjoyyourtime.globalfitness.wearable.NamePatternDriver
import com.enjoyyourtime.globalfitness.wearable.WearableAlarms
import com.enjoyyourtime.globalfitness.wearable.WearableDeviceInfo
import com.enjoyyourtime.globalfitness.wearable.WearableEvent
import com.enjoyyourtime.globalfitness.wearable.WearableException
import com.enjoyyourtime.globalfitness.wearable.WearableFailure
import com.enjoyyourtime.globalfitness.wearable.WearableFamily
import com.enjoyyourtime.globalfitness.wearable.WearableFeature
import com.enjoyyourtime.globalfitness.wearable.WearableSession
import com.enjoyyourtime.globalfitness.wearable.WearableSessionEnv
import com.enjoyyourtime.globalfitness.wearable.WearableTransport
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.withContext

/** Bangle.js (and other Espruino devices running the Android Integration app); no key. */
class BangleJsDriver : NamePatternDriver() {
    override val id = "banglejs"
    override val displayName = "Bangle.js"
    override val brand = "Espruino"
    override val family = WearableFamily.BANGLEJS
    override val transport = WearableTransport.BLE_UART
    override val authKey = AuthKeyKind.NONE
    override val namePatterns = listOf(Regex("Bangle\\.js.*|Pixl\\.js.*|Puck\\.js.*|MDBT42Q.*|Espruino.*"))
    override val keywords = listOf("espruino", "bangle", "open source", "nordic uart")
    override val docUrl = "https://gadgetbridge.org/gadgets/wearables/banglejs/"
    override val features = setOf(
        WearableFeature.BATTERY,
        WearableFeature.DEVICE_INFO,
        WearableFeature.TIME_SYNC,
        WearableFeature.FIND_DEVICE,
        WearableFeature.LIVE_HEART_RATE,
        WearableFeature.LIVE_STEPS,
        WearableFeature.NOTIFICATIONS,
        WearableFeature.ALARMS,
    )

    override fun createSession(env: WearableSessionEnv): WearableSession = BangleJsSession(env)
}

/**
 * Nordic UART line protocol. The watch pushes its status ("status", "ver") on its own; live heart
 * rate and steps come as "act" messages once requested. Alarms cannot be read back, so the app's
 * copy is kept in the device store and the whole list is sent on every change.
 */
class BangleJsSession(env: WearableSessionEnv) : GattWearableSession(env, requestedMtu = BangleJs.REQUESTED_MTU) {
    override val label = "Nordic UART"
    private val lines = BangleJs.LineBuffer()
    private val alarms = LocalAlarms(env.store, BangleJs.MAX_ALARMS)
    private var chunk = BangleJs.INITIAL_CHUNK
    private var liveActive = false
    private var uartService: UUID = BangleJs.UART_SERVICE

    override suspend fun onLinkReady() {
        uartService = serviceOf(BangleJs.UART_TX)
            ?: throw WearableException(WearableFailure.UNSUPPORTED_DEVICE, "no Nordic UART service")
        val rxService = serviceOf(BangleJs.UART_RX)
            ?: throw WearableException(WearableFailure.UNSUPPORTED_DEVICE, "no Nordic UART RX")
        enableNotifications(rxService, BangleJs.UART_RX)
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        if (characteristic != BangleJs.UART_RX) return
        // Gadgetbridge grows its write size to the largest packet the watch sends.
        if (value.size > chunk) chunk = minOf(value.size, mtu - 3)
        for (line in lines.append(value)) {
            val message = BangleJs.parseLine(line) ?: continue
            onMessage(message)
        }
    }

    private fun onMessage(m: Map<String, Any?>) {
        when (m["t"]) {
            "status" -> {
                val level = (m["bat"] as? Double)?.toInt()?.coerceIn(0, 100)
                val charging = (m["chg"] as? Double)?.let { it.toInt() == 1 }
                if (level != null || charging != null) emit(WearableEvent.Battery(level, charging))
            }
            "ver" -> emit(WearableEvent.Info(WearableDeviceInfo(model = "Bangle.js", firmware = m["fw"] as? String, hardware = m["hw"] as? String)))
            "findPhone" -> if (m["n"] == true) emit(WearableEvent.FindPhone)
            "act" -> {
                if (!liveActive) return
                (m["hrm"] as? Double)?.toInt()?.let { emit(WearableEvent.HeartRate(it)) }
                (m["stp"] as? Double)?.toInt()?.takeIf { it >= 0 }?.let { emit(WearableEvent.Activity(stepsIncrement = it)) }
            }
        }
    }

    private suspend fun send(line: String) {
        ensureOpen()
        for (part in BangleJs.chunks(BangleJs.bytes(line), chunk)) {
            if (!write(uartService, BangleJs.UART_TX, part)) throw WearableException(WearableFailure.REQUEST_TIMEOUT, "UART write refused")
        }
    }

    /** The watch reports battery and version by itself; nothing to request. */
    override suspend fun refreshStatus(includeInfo: Boolean) = Unit

    override suspend fun setTime(nowMillis: Long, timeZone: TimeZone, is24Hour: Boolean) =
        withContext(serial) { send(BangleJs.setTime(nowMillis, timeZone)) }

    override suspend fun findDevice(start: Boolean) = withContext(serial) { send(BangleJs.find(start)) }

    override suspend fun setLiveHeartRate(enabled: Boolean) = withContext(serial) {
        liveActive = enabled
        send(BangleJs.realtime(heartRate = enabled, steps = enabled))
    }

    override suspend fun sendNotification(notification: BandNotification) = withContext(serial) { send(BangleJs.notification(notification)) }

    override suspend fun dismissNotification(id: Int, packageName: String) = withContext(serial) { send(BangleJs.dismissNotification(id)) }

    override suspend fun loadAlarms(): WearableAlarms = WearableAlarms(alarms.list(), BangleJs.MAX_ALARMS)

    override suspend fun saveAlarm(id: Int?, spec: AlarmSpec) = withContext(serial) {
        alarms.put(id, spec) ?: throw WearableException(WearableFailure.NOT_SUPPORTED, "all alarm slots used")
        send(BangleJs.alarms(alarms.list()))
    }

    override suspend fun deleteAlarm(id: Int) = withContext(serial) {
        alarms.remove(id)
        send(BangleJs.alarms(alarms.list()))
    }
}
