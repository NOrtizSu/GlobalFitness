package com.enjoyyourtime.globalfitness.band.xiaomi

import com.enjoyyourtime.globalfitness.band.proto.XiaomiProto
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Type/subtype numbers and payloads as Gadgetbridge's Xiaomi services send them. */
class XiaomiCommandsTest {

    private fun assertTypes(command: XiaomiProto.Command, type: Int, subtype: Int) {
        assertEquals(type, command.type)
        assertEquals(subtype, command.subtype)
    }

    @Test
    fun simpleGettersUseTheGadgetbridgeNumbers() {
        assertTypes(XiaomiCommands.getBattery(), 2, 1)
        assertTypes(XiaomiCommands.getDeviceInfo(), 2, 2)
        assertTypes(XiaomiCommands.getDeviceState(), 2, 78)
        assertTypes(XiaomiCommands.realtimeStats(true), 8, 45)
        assertTypes(XiaomiCommands.realtimeStats(false), 8, 46)
        assertTypes(XiaomiCommands.getAlarms(), 17, 0)
        assertEquals("08021001", XiaomiCommands.getBattery().toByteArray().toHexString())
    }

    @Test
    fun findWatchSerializesTheExplicitZero() {
        val start = XiaomiCommands.findWatch(true)
        assertTypes(start, 2, 18)
        assertEquals(0, start.system.findDevice)
        // proto2 keeps the explicitly set 0: system { findDevice: 0 }
        assertEquals("08021012" + "22022800", start.toByteArray().toHexString())
        assertEquals(1, XiaomiCommands.findWatch(false).system.findDevice)
    }

    @Test
    fun setTimeSendsLocalTimeZoneAndDstInQuarterHours() {
        val madrid = ZoneId.of("Europe/Madrid")
        val summer = ZonedDateTime.of(2026, 7, 15, 10, 20, 30, 400_000_000, madrid).toInstant().toEpochMilli()
        val command = XiaomiCommands.setTime(summer, TimeZone.getTimeZone(madrid), is24Hour = true)
        assertTypes(command, 2, 3)
        val clock = command.system.clock
        assertEquals(2026, clock.date.year)
        assertEquals(7, clock.date.month)
        assertEquals(15, clock.date.day)
        assertEquals(10, clock.time.hour)
        assertEquals(20, clock.time.minute)
        assertEquals(30, clock.time.second)
        assertEquals(400, clock.time.millisecond)
        assertEquals(4, clock.timezone.zoneOffset) // +1 h standard
        assertEquals(4, clock.timezone.dstOffset) // +1 h summer time
        assertEquals("Europe/Madrid", clock.timezone.name)
        assertFalse(clock.isNot24Hour)

        val winter = ZonedDateTime.of(2026, 1, 10, 8, 0, 0, 0, madrid).toInstant().toEpochMilli()
        val winterClock = XiaomiCommands.setTime(winter, TimeZone.getTimeZone(madrid), is24Hour = false).system.clock
        assertEquals(4, winterClock.timezone.zoneOffset)
        assertEquals(0, winterClock.timezone.dstOffset)
        assertTrue(winterClock.isNot24Hour)
    }

    @Test
    fun alarmRepeatModesAndFlags() {
        val once = XiaomiCommands.alarmDetails(AlarmSpec(7, 30, repeat = AlarmRepeat.Once))
        assertEquals(0, once.repeatMode)
        assertFalse(once.hasRepeatFlags())
        assertEquals(XiaomiCommands.ALARM_NORMAL, once.smart)

        val daily = XiaomiCommands.alarmDetails(AlarmSpec(7, 30, smart = true, repeat = AlarmRepeat.Daily))
        assertEquals(1, daily.repeatMode)
        assertEquals(XiaomiCommands.ALARM_SMART, daily.smart)

        val weekdays = XiaomiCommands.alarmDetails(AlarmSpec(6, 45, enabled = false, repeat = AlarmRepeat.Weekly(31)))
        assertEquals(5, weekdays.repeatMode)
        assertEquals(31, weekdays.repeatFlags)
        assertFalse(weekdays.enabled)
        assertEquals(6, weekdays.time.hour)
        assertEquals(45, weekdays.time.minute)
    }

    @Test
    fun alarmDaysMapToOnceDailyOrWeekly() {
        assertEquals(AlarmRepeat.Once, AlarmRepeat.fromDays(0))
        assertEquals(AlarmRepeat.Daily, AlarmRepeat.fromDays(AlarmRepeat.ALL_DAYS))
        assertEquals(AlarmRepeat.Weekly(AlarmRepeat.MONDAY or AlarmRepeat.SUNDAY), AlarmRepeat.fromDays(65))
    }

    @Test
    fun alarmCommandsCarryIdsAndDetails() {
        val spec = AlarmSpec(8, 0, repeat = AlarmRepeat.Weekly(96))
        val create = XiaomiCommands.createAlarm(spec)
        assertTypes(create, 17, 1)
        assertEquals(96, create.schedule.createAlarm.repeatFlags)

        val edit = XiaomiCommands.editAlarm(3, spec.copy(enabled = false))
        assertTypes(edit, 17, 2)
        assertEquals(3, edit.schedule.editAlarm.id)
        assertFalse(edit.schedule.editAlarm.alarmDetails.enabled)

        val delete = XiaomiCommands.deleteAlarms(listOf(2, 5))
        assertTypes(delete, 17, 4)
        assertEquals(listOf(2, 5), delete.schedule.deleteAlarm.idList)
    }

    @Test
    fun parsesAlarmsFromTheBand() {
        fun info(id: Int, hour: Int, mode: Int, flags: Int?, smart: Int) = XiaomiProto.AlarmInfo.newBuilder()
            .setId(id)
            .setAlarmDetails(
                XiaomiProto.AlarmDetails.newBuilder()
                    .setTime(XiaomiProto.HourMinute.newBuilder().setHour(hour).setMinute(15))
                    .setRepeatMode(mode)
                    .setEnabled(true)
                    .setSmart(smart)
                    .also { if (flags != null) it.setRepeatFlags(flags) },
            )
        val alarms = XiaomiProto.Alarms.newBuilder()
            .setMaxAlarms(10)
            .addAlarm(info(2, 9, 5, 31, 2))
            .addAlarm(info(1, 6, 1, null, 1))
            .addAlarm(info(4, 22, 7, 64, 2))
            .build()

        val parsed = XiaomiCommands.parseAlarms(alarms)
        assertEquals(listOf(1, 2, 4), parsed.map { it.id }) // sorted by time
        assertEquals(AlarmSpec(6, 15, enabled = true, smart = true, repeat = AlarmRepeat.Daily), parsed[0].spec)
        assertEquals(AlarmRepeat.Weekly(31), parsed[1].spec.repeat)
        assertEquals(AlarmRepeat.Other(7, 64), parsed[2].spec.repeat)
        // An unknown repeat mode is written back unchanged.
        val roundTrip = XiaomiCommands.alarmDetails(parsed[2].spec)
        assertEquals(7, roundTrip.repeatMode)
        assertEquals(64, roundTrip.repeatFlags)
    }

    @Test
    fun dropsAlarmsWithImpossibleTimes() {
        fun info(id: Int, hour: Int, minute: Int) = XiaomiProto.AlarmInfo.newBuilder()
            .setId(id)
            .setAlarmDetails(
                XiaomiProto.AlarmDetails.newBuilder()
                    .setTime(XiaomiProto.HourMinute.newBuilder().setHour(hour).setMinute(minute))
                    .setRepeatMode(0)
                    .setEnabled(true)
                    .setSmart(2),
            )
        val alarms = XiaomiProto.Alarms.newBuilder()
            .addAlarm(info(1, 24, 0))
            .addAlarm(info(2, 7, 60))
            .addAlarm(info(3, -1, 0)) // uint32 0xFFFFFFFF
            .addAlarm(info(4, 23, 59))
            .addAlarm(info(5, 0, 0))
            .build()
        assertEquals(listOf(5, 4), XiaomiCommands.parseAlarms(alarms).map { it.id })
    }

    @Test
    fun notificationFields() {
        val whenMillis = ZonedDateTime.of(2026, 3, 4, 5, 6, 7, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val command = XiaomiCommands.notification(
            BandNotification(id = 42, packageName = "com.example.chat", appName = "Chat", title = "Ana", body = "Hola", whenMillis = whenMillis),
            TimeZone.getTimeZone("UTC"),
        )
        assertTypes(command, 7, 0)
        val n3 = command.notification.notification2.notification3
        assertEquals(42, n3.id)
        assertEquals("com.example.chat", n3.`package`)
        assertEquals("Chat", n3.appName)
        assertEquals("Ana", n3.title)
        assertEquals("Hola", n3.body)
        assertEquals("20260304T050607", n3.timestamp)
        assertTrue(n3.hasUnknown4())
        assertFalse(n3.hasKey())

        val noName = XiaomiCommands.notification(BandNotification(1, "p", "", "", "x", 0L))
            .notification.notification2.notification3
        assertEquals("UNKNOWN", noName.appName)
        assertFalse(noName.hasTitle())

        val dismiss = XiaomiCommands.dismissNotification(42, "com.example.chat")
        assertTypes(dismiss, 7, 1)
        assertEquals(42, dismiss.notification.notificationDismiss.getNotificationId(0).id)
        assertEquals("com.example.chat", dismiss.notification.notificationDismiss.getNotificationId(0).`package`)
    }
}
