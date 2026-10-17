// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.band.xiaomi

import com.enjoyyourtime.globalfitness.band.proto.XiaomiProto
import com.google.protobuf.ByteString
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Command type/subtype numbers and protobuf builders, taken from Gadgetbridge's Xiaomi services
 * (XiaomiAuthService, XiaomiSystemService, XiaomiHealthService, XiaomiScheduleService,
 * XiaomiNotificationService). Every band request and response is a [XiaomiProto.Command]; replies
 * reuse the type/subtype of the request.
 */
object XiaomiCommands {
    // XiaomiAuthService
    const val TYPE_AUTH = 1
    const val AUTH_SEND_USERID = 5
    const val AUTH_NONCE = 26
    const val AUTH_CONFIRM = 27

    // XiaomiSystemService
    const val TYPE_SYSTEM = 2
    const val SYSTEM_BATTERY = 1
    const val SYSTEM_DEVICE_INFO = 2
    const val SYSTEM_CLOCK = 3
    const val SYSTEM_FIND_PHONE = 17
    const val SYSTEM_FIND_WATCH = 18
    const val SYSTEM_DEVICE_STATE_GET = 78
    const val SYSTEM_DEVICE_STATE = 79

    // XiaomiNotificationService
    const val TYPE_NOTIFICATION = 7
    const val NOTIFICATION_SEND = 0
    const val NOTIFICATION_DISMISS = 1

    // XiaomiHealthService
    const val TYPE_HEALTH = 8
    const val HEALTH_REALTIME_STATS_START = 45
    const val HEALTH_REALTIME_STATS_STOP = 46
    const val HEALTH_REALTIME_STATS_EVENT = 47

    // XiaomiScheduleService
    const val TYPE_SCHEDULE = 17
    const val SCHEDULE_ALARMS_GET = 0
    const val SCHEDULE_ALARMS_CREATE = 1
    const val SCHEDULE_ALARMS_EDIT = 2
    const val SCHEDULE_ALARMS_DELETE = 4

    // Values used inside the messages.
    const val FIND_START = 0
    const val FIND_STOP = 1
    const val ALARM_SMART = 1
    const val ALARM_NORMAL = 2
    const val REPEAT_ONCE = 0
    const val REPEAT_DAILY = 1
    const val REPEAT_WEEKLY = 5

    fun simple(type: Int, subtype: Int): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder().setType(type).setSubtype(subtype).build()

    // ---- Auth ----

    /** Auth step 1 (1/26): the phone nonce. [deviceId] is only needed by Wear OS watches. */
    fun nonce(nonce: ByteArray, deviceId: String? = null): XiaomiProto.Command {
        val verify = XiaomiProto.Auth.AppVerify.newBuilder().setNonce(ByteString.copyFrom(nonce))
        if (!deviceId.isNullOrEmpty()) verify.setDeviceId(deviceId)
        return XiaomiProto.Command.newBuilder()
            .setType(TYPE_AUTH)
            .setSubtype(AUTH_NONCE)
            .setAuth(XiaomiProto.Account.newBuilder().setAppVerify(verify))
            .build()
    }

    /** Auth step 2 (1/27): HMAC over both nonces plus the encrypted phone description. */
    fun authConfirm(encryptedNonces: ByteArray, encryptedDeviceInfo: ByteArray): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder()
            .setType(TYPE_AUTH)
            .setSubtype(AUTH_CONFIRM)
            .setAuth(
                XiaomiProto.Account.newBuilder().setAppConfirm(
                    XiaomiProto.Auth.AppConfirm.newBuilder()
                        .setEncryptedNonces(ByteString.copyFrom(encryptedNonces))
                        .setEncryptedDeviceInfo(ByteString.copyFrom(encryptedDeviceInfo)),
                ),
            )
            .build()

    fun companionDevice(info: CompanionInfo): XiaomiProto.CompanionDevice =
        XiaomiProto.CompanionDevice.newBuilder()
            .setUnknown1(0)
            .setPhoneApiLevel(info.apiLevel.toFloat())
            .setPhoneName(info.phoneModel)
            .setUnknown3(224)
            .setRegion(info.region)
            .build()

    // ---- System ----

    fun getDeviceInfo() = simple(TYPE_SYSTEM, SYSTEM_DEVICE_INFO)

    fun getBattery() = simple(TYPE_SYSTEM, SYSTEM_BATTERY)

    fun getDeviceState() = simple(TYPE_SYSTEM, SYSTEM_DEVICE_STATE_GET)

    /** 2/18: makes the band vibrate until stopped (or "found it" is pressed on the band). */
    fun findWatch(start: Boolean): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder()
            .setType(TYPE_SYSTEM)
            .setSubtype(SYSTEM_FIND_WATCH)
            .setSystem(XiaomiProto.System.newBuilder().setFindDevice(if (start) FIND_START else FIND_STOP))
            .build()

    /**
     * 2/3 set clock, as XiaomiSystemService.setCurrentTime: local date/time, zone and DST offsets in
     * 15-minute blocks, zone id and the 12/24 h flag.
     */
    fun setTime(nowMillis: Long, timeZone: TimeZone, is24Hour: Boolean): XiaomiProto.Command {
        val now = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
        val clock = XiaomiProto.Clock.newBuilder()
            .setTime(
                XiaomiProto.Time.newBuilder()
                    .setHour(now.get(Calendar.HOUR_OF_DAY))
                    .setMinute(now.get(Calendar.MINUTE))
                    .setSecond(now.get(Calendar.SECOND))
                    .setMillisecond(now.get(Calendar.MILLISECOND)),
            )
            .setDate(
                XiaomiProto.Date.newBuilder()
                    .setYear(now.get(Calendar.YEAR))
                    .setMonth(now.get(Calendar.MONTH) + 1)
                    .setDay(now.get(Calendar.DATE)),
            )
            .setTimezone(
                XiaomiProto.TimeZone.newBuilder()
                    .setZoneOffset(now.get(Calendar.ZONE_OFFSET) / 1000 / 60 / 15)
                    .setDstOffset(now.get(Calendar.DST_OFFSET) / 1000 / 60 / 15)
                    .setName(timeZone.id),
            )
            .setIsNot24Hour(!is24Hour)
        return XiaomiProto.Command.newBuilder()
            .setType(TYPE_SYSTEM)
            .setSubtype(SYSTEM_CLOCK)
            .setSystem(XiaomiProto.System.newBuilder().setClock(clock))
            .build()
    }

    // ---- Health ----

    fun realtimeStats(start: Boolean) =
        simple(TYPE_HEALTH, if (start) HEALTH_REALTIME_STATS_START else HEALTH_REALTIME_STATS_STOP)

    // ---- Schedule (alarms) ----

    fun getAlarms() = simple(TYPE_SCHEDULE, SCHEDULE_ALARMS_GET)

    fun createAlarm(alarm: AlarmSpec): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder()
            .setType(TYPE_SCHEDULE)
            .setSubtype(SCHEDULE_ALARMS_CREATE)
            .setSchedule(XiaomiProto.Schedule.newBuilder().setCreateAlarm(alarmDetails(alarm)))
            .build()

    fun editAlarm(id: Int, alarm: AlarmSpec): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder()
            .setType(TYPE_SCHEDULE)
            .setSubtype(SCHEDULE_ALARMS_EDIT)
            .setSchedule(
                XiaomiProto.Schedule.newBuilder().setEditAlarm(
                    XiaomiProto.AlarmInfo.newBuilder().setId(id).setAlarmDetails(alarmDetails(alarm)),
                ),
            )
            .build()

    fun deleteAlarms(ids: List<Int>): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder()
            .setType(TYPE_SCHEDULE)
            .setSubtype(SCHEDULE_ALARMS_DELETE)
            .setSchedule(XiaomiProto.Schedule.newBuilder().setDeleteAlarm(XiaomiProto.AlarmDelete.newBuilder().addAllId(ids)))
            .build()

    /** XiaomiScheduleService.onSetAlarms: once = mode 0, daily = mode 1, otherwise weekly mode 5 + day flags. */
    fun alarmDetails(alarm: AlarmSpec): XiaomiProto.AlarmDetails.Builder {
        val details = XiaomiProto.AlarmDetails.newBuilder()
            .setTime(XiaomiProto.HourMinute.newBuilder().setHour(alarm.hour).setMinute(alarm.minute))
            .setEnabled(alarm.enabled)
            .setSmart(if (alarm.smart) ALARM_SMART else ALARM_NORMAL)
        when (val repeat = alarm.repeat) {
            AlarmRepeat.Once -> details.setRepeatMode(REPEAT_ONCE)
            AlarmRepeat.Daily -> details.setRepeatMode(REPEAT_DAILY)
            is AlarmRepeat.Weekly -> details.setRepeatMode(REPEAT_WEEKLY).setRepeatFlags(repeat.days)
            is AlarmRepeat.Other -> {
                details.setRepeatMode(repeat.mode)
                if (repeat.flags != null) details.setRepeatFlags(repeat.flags)
            }
        }
        return details
    }

    /**
     * XiaomiScheduleService.handleAlarms: band ids start at 1. Hour and minute are uint32 on the wire,
     * so an alarm with an impossible time is dropped instead of reaching the UI.
     */
    fun parseAlarms(alarms: XiaomiProto.Alarms): List<BandAlarm> = alarms.alarmList.mapNotNull { info ->
        val d = info.alarmDetails
        if (d.time.hour !in 0..23 || d.time.minute !in 0..59) return@mapNotNull null
        val repeat = when (d.repeatMode) {
            REPEAT_ONCE -> AlarmRepeat.Once
            REPEAT_DAILY -> AlarmRepeat.Daily
            REPEAT_WEEKLY -> AlarmRepeat.fromDays(d.repeatFlags)
            else -> AlarmRepeat.Other(d.repeatMode, if (d.hasRepeatFlags()) d.repeatFlags else null)
        }
        BandAlarm(
            id = info.id,
            spec = AlarmSpec(
                hour = d.time.hour,
                minute = d.time.minute,
                enabled = d.enabled,
                smart = d.smart == ALARM_SMART,
                repeat = repeat,
            ),
        )
    }.sortedWith(compareBy({ it.spec.hour }, { it.spec.minute }, { it.id }))

    // ---- Notifications ----

    /** Longest title or body sent (characters), as the Zepp OS driver: long texts would only slow the link. */
    const val MAX_NOTIFICATION_TEXT = 512

    /** [s] cut to [max] chars without splitting a surrogate pair. */
    internal fun truncate(s: String, max: Int): String {
        if (s.length <= max) return s
        val end = if (Character.isHighSurrogate(s[max - 1])) max - 1 else max
        return s.substring(0, end)
    }

    /** XiaomiNotificationService.onNotification (7/0). */
    fun notification(n: BandNotification, timeZone: TimeZone = TimeZone.getDefault()): XiaomiProto.Command {
        val format = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT).apply { this.timeZone = timeZone }
        val n3 = XiaomiProto.Notification3.newBuilder()
            .setId(n.id)
            .setUnknown4("")
            .setTimestamp(format.format(Date(n.whenMillis)))
            .setPackage(n.packageName)
            // The band does not show a notification without an app name.
            .setAppName(n.appName.ifEmpty { "UNKNOWN" })
        if (n.title.isNotEmpty()) n3.setTitle(truncate(n.title, MAX_NOTIFICATION_TEXT))
        n3.setBody(truncate(n.body, MAX_NOTIFICATION_TEXT))
        return XiaomiProto.Command.newBuilder()
            .setType(TYPE_NOTIFICATION)
            .setSubtype(NOTIFICATION_SEND)
            .setNotification(
                XiaomiProto.Notification.newBuilder()
                    .setNotification2(XiaomiProto.Notification2.newBuilder().setNotification3(n3)),
            )
            .build()
    }

    /** XiaomiNotificationService.onDeleteNotification (7/1). */
    fun dismissNotification(id: Int, packageName: String): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder()
            .setType(TYPE_NOTIFICATION)
            .setSubtype(NOTIFICATION_DISMISS)
            .setNotification(
                XiaomiProto.Notification.newBuilder().setNotificationDismiss(
                    XiaomiProto.NotificationDismiss.newBuilder()
                        .addNotificationId(XiaomiProto.NotificationId.newBuilder().setId(id).setPackage(packageName)),
                ),
            )
            .build()
}

/** What the band is told about the phone during authentication (CompanionDevice). */
data class CompanionInfo(val apiLevel: Int, val phoneModel: String, val region: String)

/** Alarm repetition as the band stores it. Day flags: Monday = 1 ... Sunday = 64 (Gadgetbridge Alarm). */
sealed interface AlarmRepeat {
    data object Once : AlarmRepeat
    data object Daily : AlarmRepeat
    data class Weekly(val days: Int) : AlarmRepeat

    /** A repeat mode this app does not edit (e.g. set on the band); sent back unchanged. */
    data class Other(val mode: Int, val flags: Int?) : AlarmRepeat

    companion object {
        const val MONDAY = 1
        const val SUNDAY = 64
        const val ALL_DAYS = 127

        /** Same mapping as Gadgetbridge: no day = once, every day = daily. */
        fun fromDays(days: Int): AlarmRepeat = when (days and ALL_DAYS) {
            0 -> Once
            ALL_DAYS -> Daily
            else -> Weekly(days and ALL_DAYS)
        }
    }
}

data class AlarmSpec(
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    val smart: Boolean = false,
    val repeat: AlarmRepeat = AlarmRepeat.Once,
)

data class BandAlarm(val id: Int, val spec: AlarmSpec)

/** A notification to show on the band. */
data class BandNotification(
    val id: Int,
    val packageName: String,
    val appName: String,
    val title: String,
    val body: String,
    val whenMillis: Long,
)
