// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.wearable.zeppos

import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmRepeat
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandAlarm
import com.enjoyyourtime.globalfitness.wearable.LocalAlarms
import com.enjoyyourtime.globalfitness.wearable.WearableDeviceInfo
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * Zepp OS endpoints and payloads (Gadgetbridge service/devices/huami/zeppos/services). The first
 * payload byte is always the per-service command.
 */
object ZeppOsProtocol {
    // Endpoints.
    const val SERVICES = 0x0000
    const val ALARMS = 0x000f
    const val CONNECTION = 0x0015
    const val STEPS = 0x0016
    const val FIND_DEVICE = 0x001a
    const val HEART_RATE = 0x001d
    const val NOTIFICATIONS = 0x001e
    const val BATTERY = 0x0029
    const val DEVICE_INFO = 0x0043
    const val TIME = 0x0047
    const val AUTH = 0x0082

    /** Encryption Gadgetbridge assumes until the services list says otherwise. */
    val DEFAULT_ENCRYPTED: Map<Int, Boolean> = mapOf(
        SERVICES to false, AUTH to false, ALARMS to false, STEPS to false, HEART_RATE to false,
        DEVICE_INFO to false, TIME to false, BATTERY to true, FIND_DEVICE to true, NOTIFICATIONS to true,
        CONNECTION to true,
    )

    // ---- authentication (ZeppOsAuthenticationService / InitOperation2021) ----

    /** `04 02 00 02 <public key 48>`. */
    fun authPublicKey(publicKey: ByteArray): ByteArray = byteArrayOf(0x04, 0x02, 0x00, 0x02) + publicKey

    /** The watch's answer `10 04 01 <random 16> <public key 48>`. */
    class AuthChallenge(val random: ByteArray, val publicKey: ByteArray)

    fun parseAuthChallenge(payload: ByteArray): AuthChallenge? {
        if (payload.size < 3 + 16 + 48) return null
        if (payload[0].toInt() != 0x10 || payload[1].toInt() != 0x04 || payload[2].toInt() != 0x01) return null
        return AuthChallenge(payload.copyOfRange(3, 19), payload.copyOfRange(19, 67))
    }

    /** Session parameters from the 48-byte shared point: sequence = u32 LE of X[0..3], key = X[8..23] XOR auth key. */
    class Session(val sequence: Int, val key: ByteArray)

    fun deriveSession(shared: ByteArray, authKey: ByteArray): Session {
        val sequence = (shared[0].toInt() and 0xff) or ((shared[1].toInt() and 0xff) shl 8) or
            ((shared[2].toInt() and 0xff) shl 16) or ((shared[3].toInt() and 0xff) shl 24)
        return Session(sequence, ByteArray(16) { (shared[it + 8].toInt() xor authKey[it].toInt()).toByte() })
    }

    /** `05 | AES-ECB(authKey, random) | AES-ECB(sessionKey, random)`. */
    fun authAnswer(random: ByteArray, authKey: ByteArray, sessionKey: ByteArray): ByteArray =
        byteArrayOf(0x05) + ZeppOsCrypto.encrypt(random, authKey) + ZeppOsCrypto.encrypt(random, sessionKey)

    enum class AuthResult { SUCCESS, WRONG_KEY, FAILED }

    /** `10 05 01` success, `10 05 25` wrong key; null when not an answer to step 2. */
    fun parseAuthResult(payload: ByteArray): AuthResult? {
        if (payload.size < 3 || payload[0].toInt() != 0x10 || payload[1].toInt() != 0x05) return null
        return when (payload[2].toInt() and 0xff) {
            0x01 -> AuthResult.SUCCESS
            0x25 -> AuthResult.WRONG_KEY
            else -> AuthResult.FAILED
        }
    }

    // ---- services list (ZeppOsServicesService) ----

    val SERVICES_REQUEST = byteArrayOf(0x03)

    /** `04 count(u16) [endpoint(u16) encrypted(u8)]*` → endpoint → encrypted. */
    fun parseServices(payload: ByteArray): Map<Int, Boolean>? {
        if (payload.size < 3 || payload[0].toInt() != 0x04) return null
        val count = u16(payload, 1)
        if (payload.size < 3 + count * 3) return null
        return (0 until count).associate { i ->
            val o = 3 + i * 3
            u16(payload, o) to (payload[o + 2].toInt() != 0)
        }
    }

    // ---- battery ----

    val BATTERY_REQUEST = byteArrayOf(0x03)

    /** `04` + HuamiBatteryInfo bytes: level at [1], state at [2] (1 = charging). */
    fun parseBattery(payload: ByteArray): Pair<Int, Boolean?>? {
        if (payload.size < 3 || payload[0].toInt() != 0x04) return null
        val level = (payload[2].toInt() and 0xff).coerceIn(0, 100)
        val charging = when (payload.getOrNull(3)?.toInt()) {
            0 -> false
            1 -> true
            else -> null
        }
        return level to charging
    }

    // ---- device info ----

    val DEVICE_INFO_REQUEST = byteArrayOf(0x01)

    /** `02 01 flags(u64)` then the fields flagged 1 (skipped), 2 serial, 4 hardware, 8 firmware, NUL-terminated. */
    fun parseDeviceInfo(payload: ByteArray): WearableDeviceInfo? {
        if (payload.size < 10 || payload[0].toInt() != 0x02 || payload[1].toInt() != 0x01) return null
        var flags = 0L
        for (k in 0 until 8) flags = flags or ((payload[2 + k].toLong() and 0xff) shl (8 * k))
        var i = 10
        if (flags and 1L != 0L) {
            if (i >= payload.size) return null
            i += 1 + (payload[i].toInt() and 0xff)
        }
        fun cstring(): String? {
            if (i > payload.size) return null
            var end = i
            while (end < payload.size && payload[end].toInt() != 0) end++
            val s = String(payload, i, end - i, Charsets.UTF_8)
            i = end + 1
            return s.ifEmpty { null }
        }
        val serial = if (flags and 2L != 0L) cstring() else null
        val hardware = if (flags and 4L != 0L) cstring() else null
        val firmware = if (flags and 8L != 0L) cstring() else null
        return WearableDeviceInfo(serial = serial, hardware = hardware, firmware = firmware)
    }

    // ---- time ----

    /**
     * HuamiUtils.getCurrentTimeBytes: year LE, month, day, hour, minute, second, day of week with
     * Sunday = 0, fractions/256, 0x08 when on DST, total offset in quarter hours. Endpoint 0x0047
     * prefixes it with 0x05; without that endpoint it goes to the Current Time characteristic.
     */
    fun currentTimeBytes(nowMillis: Long, timeZone: TimeZone): ByteArray {
        val c = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
        val year = c.get(Calendar.YEAR)
        return byteArrayOf(
            (year and 0xff).toByte(),
            ((year shr 8) and 0xff).toByte(),
            (c.get(Calendar.MONTH) + 1).toByte(),
            c.get(Calendar.DATE).toByte(),
            c.get(Calendar.HOUR_OF_DAY).toByte(),
            c.get(Calendar.MINUTE).toByte(),
            c.get(Calendar.SECOND).toByte(),
            (c.get(Calendar.DAY_OF_WEEK) - 1).toByte(),
            (c.get(Calendar.MILLISECOND) / 1000.0 * 256).toInt().toByte(),
            (if (timeZone.inDaylightTime(Date(nowMillis))) 0x08 else 0x00).toByte(),
            (timeZone.getOffset(nowMillis) / 1000 / (60 * 15)).toByte(),
        )
    }

    fun setTime(nowMillis: Long, timeZone: TimeZone): ByteArray = byteArrayOf(0x05) + currentTimeBytes(nowMillis, timeZone)

    // ---- find device ----

    val FIND_CAPABILITIES = byteArrayOf(0x01)
    val FIND_START = byteArrayOf(0x03)
    val FIND_STOP = byteArrayOf(0x06)
    const val FIND_ACK = 0x04
    const val FIND_CAPABILITIES_RESPONSE = 0x02

    /** Without continuous mode (version < 2) the start command is repeated (default every 10 s). */
    const val FIND_REPEAT_MS = 10_000L
    const val FIND_PHONE_START = 0x11
    val FIND_PHONE_ACK = byteArrayOf(0x12, 0x01)

    // ---- heart rate ----

    val HR_START = byteArrayOf(0x04, 0x01)
    val HR_CONTINUE = byteArrayOf(0x04, 0x02)
    val HR_STOP = byteArrayOf(0x04, 0x00)
    const val HR_KEEPALIVE_MS = 1_000L

    // ---- steps ----

    val STEPS_ENABLE = byteArrayOf(0x05, 0x01)
    val STEPS_DISABLE = byteArrayOf(0x05, 0x00)

    /** `07` + 13 bytes; steps = u16 LE at offset 1 of those bytes. */
    fun parseRealtimeSteps(payload: ByteArray): Int? =
        if (payload.size >= 4 && payload[0].toInt() == 0x07) u16(payload, 2) else null

    // ---- connection keep-alive ----

    const val PING = 0x03
    val PONG = byteArrayOf(0x04)

    // ---- notifications ----

    val NOTIFICATION_CAPABILITIES = byteArrayOf(0x01)

    class NotificationCapabilities(val version: Int, val supportsPictures: Boolean, val supportsKey: Boolean)

    /** `02 version [unk u16, unk u8, hasList u8 [n u16 + n bytes]] [pictures u8, key u8, [unk]]`. */
    fun parseNotificationCapabilities(payload: ByteArray): NotificationCapabilities? {
        if (payload.size < 2 || payload[0].toInt() != 0x02) return null
        val version = payload[1].toInt() and 0xff
        var i = 2
        if (version >= 4) {
            if (payload.size < i + 4) return NotificationCapabilities(version, false, false)
            i += 3
            val hasList = payload[i++].toInt() != 0
            if (hasList) {
                if (payload.size < i + 2) return NotificationCapabilities(version, false, false)
                i += 2 + u16(payload, i)
            }
        }
        var pictures = false
        var key = false
        if (version >= 5 && payload.size >= i + 2) {
            pictures = payload[i].toInt() != 0
            key = payload[i + 1].toInt() != 0
        }
        return NotificationCapabilities(version, pictures, key)
    }

    const val NOTIFICATION_TYPE_SMS = 0x05
    const val NOTIFICATION_TYPE_NORMAL = 0xfa
    const val MAX_NOTIFICATION_BODY = 512

    /**
     * ZeppOsNotificationService.sendNotification: `03 id(u32) type 00 package\0 title\0 body\0
     * appName\0 reply [silent] [picture] [key\0]`.
     */
    fun notification(
        id: Int,
        packageName: String,
        appName: String?,
        title: String?,
        body: String?,
        sms: Boolean,
        caps: NotificationCapabilities,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x03)
        out.write(le32(id))
        out.write(if (sms) NOTIFICATION_TYPE_SMS else NOTIFICATION_TYPE_NORMAL)
        out.write(0x00)
        out.write(packageName.toByteArray(Charsets.UTF_8))
        out.write(0)
        if (!title.isNullOrEmpty()) out.write(title.toByteArray(Charsets.UTF_8))
        out.write(0)
        if (body != null) out.write(truncate(body, MAX_NOTIFICATION_BODY).toByteArray(Charsets.UTF_8))
        out.write(0)
        if (appName != null) out.write(appName.toByteArray(Charsets.UTF_8))
        out.write(0)
        out.write(0) // no reply action
        if (caps.version >= 5) out.write(0) // not silent
        if (caps.supportsPictures) out.write(0) // no picture
        if (caps.supportsKey) out.write(0) // no notification key
        return out.toByteArray()
    }

    /** `03 id(u32) fa 02 00 00 00 00 00`. */
    fun deleteNotification(id: Int): ByteArray =
        byteArrayOf(0x03) + le32(id) + byteArrayOf(NOTIFICATION_TYPE_NORMAL.toByte(), 0x02, 0, 0, 0, 0, 0)

    // ---- alarms ----

    val ALARMS_REQUEST = byteArrayOf(0x09)
    const val ALARMS_RESPONSE = 0x0a
    const val ALARMS_CHANGED = 0x0f
    const val ALARM_FLAG_SMART = 0x01
    const val ALARM_FLAG_ENABLED = 0x04
    const val MAX_ALARMS = 10

    /** Create or edit: `03 01 flags position hour minute repetition 00 00 00 00 00`. */
    fun saveAlarm(position: Int, spec: AlarmSpec): ByteArray {
        var flags = 0
        if (spec.enabled) flags = ALARM_FLAG_ENABLED
        if (spec.smart) flags = flags or ALARM_FLAG_SMART
        return byteArrayOf(
            0x03, 0x01, flags.toByte(), position.toByte(), spec.hour.toByte(), spec.minute.toByte(),
            LocalAlarms.dayMask(spec.repeat).toByte(), 0, 0, 0, 0, 0,
        )
    }

    fun deleteAlarm(position: Int): ByteArray = byteArrayOf(0x05, 0x01, position.toByte())

    /** `0a n` then n × `[flags, position, hour, minute, repetition, 5 unknown]`; ids are position + 1. */
    fun parseAlarms(payload: ByteArray): List<BandAlarm>? {
        if (payload.size < 2 || payload[0].toInt() != ALARMS_RESPONSE) return null
        val n = payload[1].toInt() and 0xff
        if (payload.size != 2 + n * 10) return null
        return (0 until n).mapNotNull { k ->
            val o = 2 + k * 10
            val flags = payload[o].toInt() and 0xff
            val hour = payload[o + 2].toInt() and 0xff
            val minute = payload[o + 3].toInt() and 0xff
            if (hour > 23 || minute > 59) return@mapNotNull null
            BandAlarm(
                id = (payload[o + 1].toInt() and 0xff) + 1,
                spec = AlarmSpec(
                    hour = hour,
                    minute = minute,
                    enabled = flags and ALARM_FLAG_ENABLED != 0,
                    smart = flags and ALARM_FLAG_SMART != 0,
                    repeat = AlarmRepeat.fromDays(payload[o + 4].toInt() and 0xff),
                ),
            )
        }.sortedWith(compareBy({ it.spec.hour }, { it.spec.minute }, { it.id }))
    }

    // ---- helpers ----

    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun le32(v: Int): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun truncate(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max)
}
