// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.wearable.pinetime

import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.wearable.AuthKeyKind
import com.enjoyyourtime.globalfitness.wearable.GattWearableSession
import com.enjoyyourtime.globalfitness.wearable.NamePatternDriver
import com.enjoyyourtime.globalfitness.wearable.WearableEvent
import com.enjoyyourtime.globalfitness.wearable.WearableException
import com.enjoyyourtime.globalfitness.wearable.WearableFailure
import com.enjoyyourtime.globalfitness.wearable.WearableFamily
import com.enjoyyourtime.globalfitness.wearable.WearableFeature
import com.enjoyyourtime.globalfitness.wearable.WearableSession
import com.enjoyyourtime.globalfitness.wearable.WearableSessionEnv
import com.enjoyyourtime.globalfitness.wearable.WearableTransport
import com.enjoyyourtime.globalfitness.wearable.standard.StandardGatt
import com.enjoyyourtime.globalfitness.wearable.standard.StandardUuids
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.withContext

/** InfiniTime message formats (Gadgetbridge PineTimeJFSupport / PineTimeJFConstants). */
object InfiniTime {
    private fun uuid(prefix: String): UUID = UUID.fromString("$prefix-78fc-48fe-8e23-433b3a1942d0")

    val MOTION_SERVICE: UUID = uuid("00030000")
    val STEP_COUNT: UUID = uuid("00030001")

    /** Call control from the watch (0 reject, 1 accept, 2 ignore). */
    val ALERT_EVENT: UUID = uuid("00020001")

    const val MAX_NOTIFICATION_LENGTH = 100
    private const val CUT_TITLE_MIN_AT = 25

    /** Alert category CustomHuami (0xFA), one alert, icon 0xFF. */
    private val NOTIFICATION_HEADER = byteArrayOf(0xFA.toByte(), 0x01, 0xFF.toByte())

    /** Alert category IncomingCall (3), one alert, then the text "1" (Java `(byte) 0x01 + String`). */
    private val CALL_HEADER = byteArrayOf(0x03, 0x01, 0x31)

    /** Firmware "major.minor.patch"; text notifications with title need 0.15 or later. Unknown = modern. */
    fun isAtLeast015(firmware: String?): Boolean {
        val tokens = firmware?.trim()?.removePrefix("v")?.split('.') ?: return true
        if (tokens.size != 3) return true
        val major = tokens[0].toIntOrNull() ?: return true
        val minor = tokens[1].toIntOrNull() ?: return true
        return major > 0 || minor >= 15
    }

    /** The New Alert (0x2A46) bytes for a notification (Gadgetbridge onNotification, TRUNCATE at 100 chars). */
    fun notification(source: String?, title: String?, body: String?, firmware015: Boolean = true): ByteArray {
        val src = source?.takeIf { it.isNotEmpty() }
        val bodyText = body.orEmpty()
        var senderOrTitle = title.orEmpty()
        val message = if (bodyText.isNotEmpty()) {
            if (firmware015) {
                var cutLength = maxOf(CUT_TITLE_MIN_AT, MAX_NOTIFICATION_LENGTH - 3 - bodyText.length - (if (src != null) src.length + 2 else 0))
                if (cutLength < senderOrTitle.length - 1) {
                    while (cutLength > 0 && senderOrTitle[cutLength - 1] == ' ') cutLength--
                    senderOrTitle = senderOrTitle.substring(0, cutLength) + ">"
                }
                listOfNotNull(src, senderOrTitle.takeIf { it.isNotEmpty() }).joinToString(": ") + "\u0000" + bodyText
            } else {
                bodyText
            }
        } else {
            if (firmware015) (src ?: "") + "\u0000" + senderOrTitle else senderOrTitle
        }
        return NOTIFICATION_HEADER + truncate(message, MAX_NOTIFICATION_LENGTH).toByteArray(Charsets.UTF_8)
    }

    /** Incoming call alert (also how Gadgetbridge rings the watch for "find device"). */
    fun call(name: String, source: String? = null, firmware015: Boolean = true): ByteArray {
        val text = if (firmware015 && source != null) source + "\u0000" + name else name
        return CALL_HEADER + text.toByteArray(Charsets.UTF_8)
    }

    /** InfiniTime motion service step count: uint32 little-endian (the watch's daily total). */
    fun parseSteps(value: ByteArray): Int? =
        if (value.size >= 4) Bytes.fromUnsignedInt32Le(value, 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null

    /** Gadgetbridge StringUtils.truncate: by UTF-16 units. */
    private fun truncate(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max)
}

/** PineTime with InfiniTime: no key, standard services plus InfiniTime's motion service. */
class PineTimeDriver : NamePatternDriver() {
    override val id = "pinetime_infinitime"
    override val displayName = "PineTime (InfiniTime)"
    override val brand = "Pine64"
    override val family = WearableFamily.PINETIME
    override val transport = WearableTransport.BLE_GATT
    override val authKey = AuthKeyKind.NONE
    override val namePatterns = listOf(Regex("Pinetime-JF.*|InfiniTime.*"))
    override val keywords = listOf("pine64", "infinitime", "open source")
    override val docUrl = "https://gadgetbridge.org/gadgets/wearables/pine64/#device__pine64_pinetime"
    override val features = setOf(
        WearableFeature.BATTERY,
        WearableFeature.DEVICE_INFO,
        WearableFeature.TIME_SYNC,
        WearableFeature.FIND_DEVICE,
        WearableFeature.LIVE_HEART_RATE,
        WearableFeature.LIVE_STEPS,
        WearableFeature.NOTIFICATIONS,
    )

    override fun createSession(env: WearableSessionEnv): WearableSession = PineTimeSession(env)
}

class PineTimeSession(env: WearableSessionEnv) : GattWearableSession(env, requestedMtu = 23) {
    override val label = "InfiniTime"
    private var firmware015 = true
    private var liveActive = false

    override suspend fun onLinkReady() {
        if (serviceOf(StandardUuids.NEW_ALERT) == null && serviceOf(InfiniTime.STEP_COUNT) == null) {
            throw WearableException(WearableFailure.UNSUPPORTED_DEVICE, "no InfiniTime services")
        }
        val info = readDeviceInformation()
        firmware015 = InfiniTime.isAtLeast015(info.firmware)
        emit(WearableEvent.Info(info))
        serviceOf(InfiniTime.STEP_COUNT)?.let { enableNotifications(it, InfiniTime.STEP_COUNT) }
        serviceOf(StandardUuids.HEART_RATE_MEASUREMENT)?.let { enableNotifications(it, StandardUuids.HEART_RATE_MEASUREMENT) }
        if (hasCharacteristic(StandardUuids.BATTERY_SERVICE, StandardUuids.BATTERY_LEVEL)) {
            enableNotifications(StandardUuids.BATTERY_SERVICE, StandardUuids.BATTERY_LEVEL)
        }
        negotiateMtu(256)
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        when (characteristic) {
            StandardUuids.HEART_RATE_MEASUREMENT -> if (liveActive) {
                StandardGatt.parseHeartRate(value)?.let { emit(WearableEvent.HeartRate(it.bpm, it.rrIntervalsMs)) }
            }
            InfiniTime.STEP_COUNT -> InfiniTime.parseSteps(value)?.let { emit(WearableEvent.Activity(steps = it)) }
            StandardUuids.BATTERY_LEVEL -> StandardGatt.parseBatteryLevel(value)?.let { emit(WearableEvent.Battery(it)) }
        }
    }

    override suspend fun refreshStatus(includeInfo: Boolean) = withContext(serial) {
        if (includeInfo) {
            val info = readDeviceInformation()
            firmware015 = InfiniTime.isAtLeast015(info.firmware)
            emit(WearableEvent.Info(info))
        }
        if (hasCharacteristic(StandardUuids.BATTERY_SERVICE, StandardUuids.BATTERY_LEVEL)) {
            read(StandardUuids.BATTERY_SERVICE, StandardUuids.BATTERY_LEVEL)?.let { StandardGatt.parseBatteryLevel(it) }
                ?.let { emit(WearableEvent.Battery(it)) }
        }
        serviceOf(InfiniTime.STEP_COUNT)?.let { s -> read(s, InfiniTime.STEP_COUNT)?.let { onNotification(InfiniTime.STEP_COUNT, it) } }
        Unit
    }

    override suspend fun setTime(nowMillis: Long, timeZone: TimeZone, is24Hour: Boolean) = withContext(serial) {
        val cts = serviceOf(StandardUuids.CURRENT_TIME) ?: throw WearableException(WearableFailure.NOT_SUPPORTED)
        write(cts, StandardUuids.CURRENT_TIME, StandardGatt.currentTime(nowMillis, timeZone))
        serviceOf(StandardUuids.LOCAL_TIME_INFORMATION)?.let {
            write(it, StandardUuids.LOCAL_TIME_INFORMATION, StandardGatt.localTimeInformation(nowMillis, timeZone))
        }
        Unit
    }

    override suspend fun sendNotification(notification: BandNotification) = withContext(serial) {
        writeAlert(InfiniTime.notification(notification.appName, notification.title, notification.body, firmware015))
    }

    /** InfiniTime has no "remove notification" message (Gadgetbridge sends nothing either). */
    override suspend fun dismissNotification(id: Int, packageName: String) = Unit

    /** As Gadgetbridge: an incoming "call" rings the watch; there is nothing to send to stop it. */
    override suspend fun findDevice(start: Boolean) = withContext(serial) {
        if (start) writeAlert(InfiniTime.call(FIND_CALLER_NAME, firmware015 = firmware015))
    }

    override suspend fun setLiveHeartRate(enabled: Boolean) = withContext(serial) {
        liveActive = enabled
    }

    private suspend fun writeAlert(bytes: ByteArray) {
        val service = serviceOf(StandardUuids.NEW_ALERT) ?: throw WearableException(WearableFailure.NOT_SUPPORTED)
        if (!write(service, StandardUuids.NEW_ALERT, bytes)) throw WearableException(WearableFailure.REQUEST_TIMEOUT, "alert refused")
    }

    companion object {
        /** Shown by the watch as the "caller" while finding it. */
        const val FIND_CALLER_NAME = "Fortia"
    }
}
