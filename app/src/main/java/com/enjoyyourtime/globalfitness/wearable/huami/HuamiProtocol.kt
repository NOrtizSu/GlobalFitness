// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.wearable.huami

import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmRepeat
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.wearable.LocalAlarms
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** UUIDs of the classic Huami protocol (Gadgetbridge HuamiService / MiBandService). */
object HuamiUuids {
    private fun huami(short: Int): UUID = UUID.fromString(String.format(Locale.ROOT, "%08x-0000-3512-2118-0009af100700", short))

    val SERVICE_MIBAND: UUID = Bytes.uuid16(0xFEE0)
    val SERVICE_MIBAND2: UUID = Bytes.uuid16(0xFEE1)

    val CONFIGURATION: UUID = huami(0x0003)
    val BATTERY_INFO: UUID = huami(0x0006)
    val REALTIME_STEPS: UUID = huami(0x0007)
    val AUTH: UUID = huami(0x0009)
    val DEVICE_EVENT: UUID = huami(0x0010)
    val CHUNKED_TRANSFER: UUID = huami(0x0020)

    /** Zepp OS chunked protocol (Huami 2021). */
    val CHUNKED_2021_WRITE: UUID = huami(0x0016)
    val CHUNKED_2021_READ: UUID = huami(0x0017)

    /** Mi Band 1 era notification characteristic Gadgetbridge also subscribes to during auth. */
    val NOTIFICATION_FF03: UUID = Bytes.uuid16(0xFF03)
}

/**
 * Byte formats of the classic Huami protocol, as Gadgetbridge's HuamiSupport and InitOperation
 * write them. Everything here is pure and covered by unit tests.
 */
object HuamiProtocol {
    const val AUTH_SEND_KEY = 0x01
    const val AUTH_REQUEST_RANDOM = 0x02
    const val AUTH_SEND_ENCRYPTED = 0x03
    const val AUTH_RESPONSE = 0x10
    const val AUTH_SUCCESS = 0x01
    const val AUTH_FAIL = 0x04

    /** Default auth flags byte (HuamiService.AUTH_BYTE). */
    const val AUTH_BYTE = 0x08

    // ---- authentication (InitOperation) ----

    /** "Request random number": `02 flags`, or `82 flags 02 01 00` on devices with crypt flags 0x80. */
    fun requestRandom(authFlags: Int, cryptFlags: Int): ByteArray =
        if (cryptFlags == 0) {
            byteArrayOf(AUTH_REQUEST_RANDOM.toByte(), authFlags.toByte())
        } else {
            byteArrayOf((cryptFlags or AUTH_REQUEST_RANDOM).toByte(), authFlags.toByte(), 0x02, 0x01, 0x00)
        }

    /** Answer to the random number: `03|crypt flags authFlags AES-ECB(key, random)`. */
    fun encryptedRandom(random: ByteArray, key: ByteArray, authFlags: Int, cryptFlags: Int): ByteArray =
        byteArrayOf((AUTH_SEND_ENCRYPTED or cryptFlags).toByte(), authFlags.toByte()) + aesEcb(key, random)

    sealed interface AuthReply {
        /** `10 02|82 01 <16 random>`. */
        class Random(val random: ByteArray) : AuthReply

        /** `10 03|83 01`. */
        data object Success : AuthReply

        /** `10 03|83 04`: the key is wrong. */
        data object WrongKey : AuthReply

        /** Any other answer to the auth characteristic (with its raw step/status). */
        data class Failed(val step: Int, val status: Int) : AuthReply
    }

    /** Parses a notification of the auth characteristic; null when it is not an auth response. */
    fun parseAuth(value: ByteArray): AuthReply? {
        if (value.size < 3 || (value[0].toInt() and 0xff) != AUTH_RESPONSE) return null
        val step = value[1].toInt() and 0xff
        val status = value[2].toInt() and 0xff
        return when {
            step and 0x0f == AUTH_REQUEST_RANDOM && status == AUTH_SUCCESS && value.size >= 19 ->
                AuthReply.Random(value.copyOfRange(3, 19))
            step and 0x0f == AUTH_SEND_ENCRYPTED && status == AUTH_SUCCESS -> AuthReply.Success
            step and 0x0f == AUTH_SEND_ENCRYPTED && status == AUTH_FAIL -> AuthReply.WrongKey
            else -> AuthReply.Failed(step, status)
        }
    }

    /** Single-block AES/ECB without padding, as InitOperation.handleAESAuth (one 16-byte block only). */
    @Suppress("GetInstance")
    fun aesEcb(key: ByteArray, block: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }

    // ---- battery (0x0006) ----

    data class Battery(val level: Int, val charging: Boolean?)

    /** HuamiBatteryInfo: level at byte 1, state at byte 2 (0 normal, 1 charging). */
    fun parseBattery(value: ByteArray): Battery? {
        if (value.size < 2) return null
        val level = (value[1].toInt() and 0xff).coerceIn(0, 100)
        val charging = if (value.size >= 3) {
            when (value[2].toInt()) {
                0 -> false
                1 -> true
                else -> null
            }
        } else {
            null
        }
        return Battery(level, charging)
    }

    // ---- time (0x2A2B) ----

    /**
     * HuamiSupport.getTimeBytes(SECONDS): year LE, month, day, hour, minute, second, day of week
     * (Monday = 1 ... Sunday = 7), fractions (0), then `0` and the offset in quarter hours with DST.
     */
    fun currentTime(nowMillis: Long, timeZone: TimeZone): ByteArray {
        val c = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
        val year = c.get(Calendar.YEAR)
        val dow = c.get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }
        return byteArrayOf(
            (year and 0xff).toByte(),
            ((year shr 8) and 0xff).toByte(),
            (c.get(Calendar.MONTH) + 1).toByte(),
            c.get(Calendar.DATE).toByte(),
            c.get(Calendar.HOUR_OF_DAY).toByte(),
            c.get(Calendar.MINUTE).toByte(),
            c.get(Calendar.SECOND).toByte(),
            dow.toByte(),
            0,
            0,
            (timeZone.getOffset(nowMillis) / (1000 * 60 * 15)).toByte(),
        )
    }

    // ---- heart rate (0x2A39 control point) ----

    val HR_STOP_MANUAL = byteArrayOf(0x15, 0x02, 0x00)
    val HR_START_CONTINUOUS = byteArrayOf(0x15, 0x01, 0x01)
    val HR_STOP_CONTINUOUS = byteArrayOf(0x15, 0x01, 0x00)

    /** Amazfit Neo keep-alive, every 10 s (AmazfitNeoSupport). */
    val HR_PING = byteArrayOf(0x16)

    // ---- realtime steps (0x0007) ----

    /** 13-byte frame, steps = uint16 LE at offset 1 (the other fields are not decoded by Gadgetbridge). */
    fun parseRealtimeSteps(value: ByteArray): Int? = if (value.size == 13) Bytes.fromUnsignedInt16Le(value, 1) else null

    // ---- find device ----

    /** Alert Level (0x2A06): 3 rings until stopped with 0. */
    val FIND_START = byteArrayOf(0x03)
    val FIND_STOP = byteArrayOf(0x00)

    /** Gadgetbridge's default find-band repeat (ring profile 300+200+600+2000 ms × 3). */
    const val FIND_REPEAT_MS = 9_300L

    /** Incoming "call" alert used by Mi Band 6 to ring (category 3, one alert, caller name). */
    fun callAlert(name: String): ByteArray = byteArrayOf(0x03, 0x01) + name.toByteArray(Charsets.UTF_8)

    val CALL_ALERT_STOP = byteArrayOf(0x03, 0x00)

    // ---- device events (0x0010) ----

    const val EVENT_FIND_PHONE_START = 0x08
    const val EVENT_FIND_PHONE_STOP = 0x0f

    /** Ack written to the configuration characteristic when the band asks to find the phone. */
    val FIND_PHONE_ACK = byteArrayOf(0x06, 0x14, 0x00, 0x00)

    // ---- alarms (0x0003) ----

    /**
     * HuamiSupport.queueAlarm: `02 [0x80 enabled | 0x40 no snooze | slot] HH MM days`, one-shot days
     * = 0x80; an unused slot is `02 slot HH MM 00`.
     */
    fun alarm(slot: Int, spec: AlarmSpec?, snooze: Boolean = false): ByteArray {
        if (spec == null) return byteArrayOf(0x02, (slot and 0x0f).toByte(), 0, 0, 0)
        var action = 0
        if (spec.enabled) {
            action = 0x80
            if (!snooze) action = action or 0x40
        }
        val mask = LocalAlarms.dayMask(spec.repeat)
        val days = if (mask == 0 || spec.repeat == AlarmRepeat.Once) 0x80 else mask
        return byteArrayOf(0x02, (action or (slot and 0x0f)).toByte(), spec.hour.toByte(), spec.minute.toByte(), days.toByte())
    }

    // ---- notifications ----

    /** Alert categories (Gadgetbridge AlertCategory) and Huami icons (HuamiIcon). */
    const val CATEGORY_EMAIL = 0x01
    const val CATEGORY_INCOMING_CALL = 0x03
    const val CATEGORY_SMS = 0x05
    const val CATEGORY_CUSTOM_HUAMI = 0xFA

    object Icons {
        const val WECHAT = 0
        const val PENGUIN = 1
        const val FACEBOOK = 3
        const val TWITTER = 4
        const val SNAPCHAT = 6
        const val WHATSAPP = 7
        const val ALARM_CLOCK = 10
        const val APP = 11
        const val INSTAGRAM = 12
        const val CHAT_BLUE = 13
        const val CALENDAR = 21
        const val FACEBOOK_MESSENGER = 22
        const val VIBER = 23
        const val LINE = 24
        const val TELEGRAM = 25
        const val KAKAOTALK = 26
        const val SKYPE = 27
        const val VKONTAKTE = 28
        const val HANGOUTS = 30
        const val EMAIL = 34
        const val WEATHER = 35
    }

    /** SMS apps get the SMS category (Gadgetbridge NotificationType.GENERIC_SMS). */
    private val SMS_PACKAGES = setOf(
        "com.android.mms", "com.android.messaging", "com.moez.QKSMS", "com.sonyericsson.conversations",
        "org.smssecure.smssecure", "com.google.android.apps.googlevoice", "com.samsung.android.messaging",
    )

    /** Package → icon, following Gadgetbridge's AppNotificationType + HuamiIcon.mapToIconId. */
    private val ICONS: Map<String, Int> = mapOf(
        "com.whatsapp" to Icons.WHATSAPP, "com.whatsapp.w4b" to Icons.WHATSAPP,
        "org.telegram.messenger" to Icons.TELEGRAM, "org.telegram.messenger.web" to Icons.TELEGRAM, "org.thunderdog.challegram" to Icons.TELEGRAM,
        "org.thoughtcrime.securesms" to Icons.FACEBOOK_MESSENGER, "com.facebook.orca" to Icons.FACEBOOK_MESSENGER,
        "com.facebook.katana" to Icons.FACEBOOK, "com.facebook.lite" to Icons.FACEBOOK,
        "com.instagram.android" to Icons.INSTAGRAM, "com.google.android.apps.photos" to Icons.INSTAGRAM,
        "com.twitter.android" to Icons.TWITTER,
        "jp.naver.line.android" to Icons.LINE, "com.viber.voip" to Icons.VIBER,
        "com.skype.raider" to Icons.SKYPE, "com.discord" to Icons.SKYPE,
        "com.kakao.talk" to Icons.KAKAOTALK, "com.vkontakte.android" to Icons.VKONTAKTE,
        "com.snapchat.android" to Icons.SNAPCHAT, "com.tencent.mm" to Icons.WECHAT, "com.tencent.mobileqq" to Icons.PENGUIN,
        "com.wire" to Icons.CHAT_BLUE, "ch.threema.app" to Icons.CHAT_BLUE, "im.vector.app" to Icons.CHAT_BLUE,
        "com.google.android.apps.messaging" to Icons.HANGOUTS, "com.google.android.talk" to Icons.HANGOUTS,
        "com.google.android.gm" to Icons.EMAIL, "com.microsoft.office.outlook" to Icons.EMAIL, "com.android.email" to Icons.EMAIL,
        "com.yahoo.mobile.client.android.mail" to Icons.EMAIL, "ch.protonmail.android" to Icons.EMAIL, "eu.faircode.email" to Icons.EMAIL,
        "com.fsck.k9" to Icons.EMAIL, "net.thunderbird.android" to Icons.EMAIL,
        "com.android.calendar" to Icons.CALENDAR, "com.google.android.calendar" to Icons.CALENDAR,
        "com.google.android.deskclock" to Icons.ALARM_CLOCK, "com.android.deskclock" to Icons.ALARM_CLOCK,
    )

    /** SMS apps map to WECHAT in Gadgetbridge's icon table (the SMS category carries the icon). */
    fun iconFor(packageName: String): Int = if (packageName in SMS_PACKAGES) Icons.WECHAT else ICONS[packageName] ?: Icons.APP

    fun categoryFor(packageName: String): Int = when {
        packageName in SMS_PACKAGES -> CATEGORY_SMS
        iconFor(packageName) == Icons.EMAIL -> CATEGORY_EMAIL
        else -> CATEGORY_CUSTOM_HUAMI
    }

    /** HuamiSupport.getNotificationBody: sender/title (≤ 32, " " when empty) NUL, body (≤ 512) + blank line. */
    fun notificationBody(title: String?, body: String?): String {
        val sb = StringBuilder()
        val t = title.orEmpty()
        sb.append(if (t.isNotEmpty()) truncate(t, 32) else " ")
        sb.append('\u0000')
        if (!body.isNullOrEmpty()) sb.append(truncate(body, 512)).append("\n\n") else sb.append(" ")
        return sb.toString()
    }

    /**
     * The chunked-transfer payload of a text notification (HuamiSupport.onNotification with the
     * 0x0020 characteristic): `category [00 00 00 00] 01 [icon] <message> 00 <app> 00`, the message
     * cut to [maxLength] - prefix bytes without splitting a UTF-8 sequence.
     */
    fun notificationPayload(
        packageName: String,
        appName: String?,
        title: String?,
        body: String?,
        extraHeader: Boolean,
        maxLength: Int,
    ): ByteArray {
        val category = categoryFor(packageName)
        val icon = iconFor(packageName)
        val message = notificationBody(title, body)
        var prefixLength = 2
        var appSuffix = "\u0000 \u0000".toByteArray(Charsets.UTF_8)
        if (category == CATEGORY_CUSTOM_HUAMI) {
            prefixLength = 3
            appSuffix = ("\u0000" + (appName?.takeIf { it.isNotEmpty() } ?: "UNKNOWN") + "\u0000").toByteArray(Charsets.UTF_8)
        }
        if (extraHeader) prefixLength += 4
        val raw = message.toByteArray(Charsets.UTF_8)
        var length = minOf(raw.size, maxLength - prefixLength).coerceAtLeast(0)
        if (length < raw.size) length = utf8ByteLength(message, length)
        val command = ByteArray(length + prefixLength + appSuffix.size)
        var pos = 0
        command[pos++] = category.toByte()
        if (extraHeader) pos += 4
        command[pos++] = 1
        if (category == CATEGORY_CUSTOM_HUAMI) command[pos] = icon.toByte()
        System.arraycopy(raw, 0, command, prefixLength, length)
        System.arraycopy(appSuffix, 0, command, prefixLength + length, appSuffix.size)
        return command
    }

    /**
     * New Alert (0x2A46) used when the device has no chunked transfer characteristic:
     * `category 01 [icon] <utf8 message>`, the message truncated to [maxLength] characters.
     */
    fun newAlert(category: Int, icon: Int?, message: String, maxLength: Int): ByteArray {
        val header = if (category == CATEGORY_CUSTOM_HUAMI) byteArrayOf(category.toByte(), 1, (icon ?: Icons.APP).toByte()) else byteArrayOf(category.toByte(), 1)
        return header + truncate(message, maxLength).toByteArray(Charsets.UTF_8)
    }

    /** Mi Band 2 text notifications: the message as SMS alerts of at most 18 characters each. */
    fun mi2TextAlerts(message: String, maxLength: Int = 18): List<ByteArray> =
        message.chunked(maxLength).map { byteArrayOf(CATEGORY_SMS.toByte(), 1) + it.toByteArray(Charsets.UTF_8) }

    /** Mi Band 2 vibration pattern on the alert level characteristic (default "short" profile 200/200 × 3). */
    fun mi2Vibration(onMs: Int = 200, offMs: Int = 200, repeat: Int = 3): ByteArray = byteArrayOf(
        0xff.toByte(),
        (onMs and 0xff).toByte(),
        ((onMs shr 8) and 0xff).toByte(),
        (offMs and 0xff).toByte(),
        ((offMs shr 8) and 0xff).toByte(),
        repeat.toByte(),
    )

    /**
     * HuamiSupport.writeToChunkedOld: `00 flags|type count <data>` frames of at most (MTU - 3) - 3
     * data bytes; flags 0x80 = last, 0x40 = continuation (a single frame has both).
     */
    fun chunkedFrames(type: Int, data: ByteArray, mtu: Int): List<ByteArray> {
        val maxChunk = (minOf(512, maxOf(23, mtu) - 3) - 3).coerceAtLeast(1)
        val frames = ArrayList<ByteArray>()
        var remaining = data.size
        var count = 0
        while (remaining > 0) {
            val copy = minOf(remaining, maxChunk)
            var flags = 0
            if (remaining <= maxChunk) {
                flags = flags or 0x80
                if (count == 0) flags = flags or 0x40
            } else if (count > 0) {
                flags = flags or 0x40
            }
            val chunk = ByteArray(copy + 3)
            chunk[0] = 0
            chunk[1] = (flags or type).toByte()
            chunk[2] = (count and 0xff).toByte()
            System.arraycopy(data, count * maxChunk, chunk, 3, copy)
            frames += chunk
            count++
            remaining -= copy
        }
        return frames
    }

    /** Gadgetbridge StringUtils.truncate (UTF-16 units). */
    fun truncate(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max)

    /** Gadgetbridge StringUtils.utf8ByteLength: bytes of the longest prefix of [s] that fits [length]. */
    fun utf8ByteLength(s: String, length: Int): Int {
        val out = ByteBuffer.allocate(length.coerceAtLeast(0))
        StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(s.toCharArray()), out, true)
        return out.position()
    }
}
