package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import com.enjoyyourtime.globalfitness.scale.MapDriverStore
import com.enjoyyourtime.globalfitness.wearable.banglejs.BangleJs
import com.enjoyyourtime.globalfitness.wearable.banglejs.BangleJsSession
import com.enjoyyourtime.globalfitness.wearable.pinetime.InfiniTime
import com.enjoyyourtime.globalfitness.wearable.pinetime.PineTimeSession
import com.enjoyyourtime.globalfitness.wearable.standard.StandardGatt
import com.enjoyyourtime.globalfitness.wearable.standard.StandardHeartRateSession
import com.enjoyyourtime.globalfitness.wearable.standard.StandardUuids
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Standard BLE heart rate, PineTime / InfiniTime and Bangle.js sessions against simulated devices. */
class KeylessSessionsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(15_000) { block() } }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    private fun events(session: WearableSession): MutableList<WearableEvent> {
        val list = CopyOnWriteArrayList<WearableEvent>()
        scope.launch { session.events.collect { list += it } }
        return list
    }

    @Test
    fun standardHeartRateStrap() = runTest {
        val device = FakeGattDevice(
            chars = listOf(
                StandardUuids.HEART_RATE_SERVICE to StandardUuids.HEART_RATE_MEASUREMENT,
                StandardUuids.BATTERY_SERVICE to StandardUuids.BATTERY_LEVEL,
                StandardUuids.DEVICE_INFORMATION to StandardUuids.MANUFACTURER_NAME,
                StandardUuids.DEVICE_INFORMATION to StandardUuids.MODEL_NUMBER,
            ),
        )
        device.readValues[StandardUuids.BATTERY_LEVEL] = hex("5a")
        device.readValues[StandardUuids.MANUFACTURER_NAME] = "Polar".toByteArray()
        device.readValues[StandardUuids.MODEL_NUMBER] = "H10".toByteArray()
        val session = StandardHeartRateSession(testEnv(scope, FakeLinks(device)))
        val events = events(session)
        session.open()
        assertTrue(device.subscribed.containsAll(listOf(StandardUuids.HEART_RATE_MEASUREMENT, StandardUuids.BATTERY_LEVEL)))
        session.refreshStatus(includeInfo = true)
        waitFor { events.any { it is WearableEvent.Battery } }
        assertEquals(WearableDeviceInfo(manufacturer = "Polar", model = "H10"), (events.first { it is WearableEvent.Info } as WearableEvent.Info).info)
        assertEquals(WearableEvent.Battery(90), events.first { it is WearableEvent.Battery })

        session.setLiveHeartRate(true)
        device.notify(StandardUuids.HEART_RATE_MEASUREMENT, hex("1640 0004"))
        device.notify(StandardUuids.HEART_RATE_MEASUREMENT, hex("0430"))
        waitFor { events.count { it is WearableEvent.HeartRate } == 2 }
        val hr = events.filterIsInstance<WearableEvent.HeartRate>()
        assertEquals(WearableEvent.HeartRate(64, listOf(1000), sensorContact = true), hr[0])
        // No contact: reported as "measuring" (0).
        assertEquals(0, hr[1].bpm)
        // Readings are only reported while live heart rate is on.
        session.setLiveHeartRate(false)
        device.notify(StandardUuids.HEART_RATE_MEASUREMENT, hex("0050"))
        delay(200)
        assertEquals(2, events.count { it is WearableEvent.HeartRate })
        try {
            session.findDevice(true)
            fail()
        } catch (e: WearableException) {
            assertEquals(WearableFailure.NOT_SUPPORTED, e.failure)
        }
        device.drop()
        assertEquals(WearableFailure.LINK_LOST, session.awaitClosed().failure)
    }

    @Test
    fun strapWithoutHeartRateIsRejected() = runTest {
        val session = StandardHeartRateSession(testEnv(scope, FakeLinks(FakeGattDevice(chars = listOf(StandardUuids.BATTERY_SERVICE to StandardUuids.BATTERY_LEVEL)))))
        try {
            session.open()
            fail()
        } catch (e: WearableException) {
            assertEquals(WearableFailure.UNSUPPORTED_DEVICE, e.failure)
        }
    }

    @Test
    fun pineTime() = runTest {
        val device = FakeGattDevice(
            chars = listOf(
                StandardUuids.ALERT_NOTIFICATION_SERVICE to StandardUuids.NEW_ALERT,
                StandardUuids.CURRENT_TIME_SERVICE to StandardUuids.CURRENT_TIME,
                StandardUuids.CURRENT_TIME_SERVICE to StandardUuids.LOCAL_TIME_INFORMATION,
                StandardUuids.HEART_RATE_SERVICE to StandardUuids.HEART_RATE_MEASUREMENT,
                StandardUuids.BATTERY_SERVICE to StandardUuids.BATTERY_LEVEL,
                StandardUuids.DEVICE_INFORMATION to StandardUuids.FIRMWARE_REVISION,
                InfiniTime.MOTION_SERVICE to InfiniTime.STEP_COUNT,
            ),
        )
        device.readValues[StandardUuids.FIRMWARE_REVISION] = "0.14.0".toByteArray()
        val session = PineTimeSession(testEnv(scope, FakeLinks(device)))
        val events = events(session)
        session.open()
        assertTrue(device.subscribed.containsAll(listOf(InfiniTime.STEP_COUNT, StandardUuids.HEART_RATE_MEASUREMENT, StandardUuids.BATTERY_LEVEL)))
        val utc = TimeZone.getTimeZone("UTC")
        session.setTime(0, utc, true)
        assertEquals(listOf(StandardGatt.currentTime(0, utc).hex()), device.writesTo(StandardUuids.CURRENT_TIME))
        assertEquals(listOf("0000"), device.writesTo(StandardUuids.LOCAL_TIME_INFORMATION))
        // Firmware 0.14: body only.
        session.sendNotification(BandNotification(1, "com.whatsapp", "WhatsApp", "Ana", "Hola", 0))
        session.findDevice(true)
        session.findDevice(false)
        session.dismissNotification(1, "com.whatsapp")
        assertEquals(
            listOf(InfiniTime.notification("WhatsApp", "Ana", "Hola", firmware015 = false).hex(), InfiniTime.call(PineTimeSession.FIND_CALLER_NAME, firmware015 = false).hex()),
            device.writesTo(StandardUuids.NEW_ALERT),
        )
        session.setLiveHeartRate(true)
        device.notify(StandardUuids.HEART_RATE_MEASUREMENT, hex("0048"))
        device.notify(InfiniTime.STEP_COUNT, hex("10270000"))
        waitFor { events.any { it is WearableEvent.HeartRate } && events.any { it is WearableEvent.Activity } }
        assertEquals(10000, (events.first { it is WearableEvent.Activity } as WearableEvent.Activity).steps)
        session.close()
    }

    @Test
    fun bangleJs() = runTest {
        val device = FakeGattDevice(chars = listOf(BangleJs.UART_SERVICE to BangleJs.UART_TX, BangleJs.UART_SERVICE to BangleJs.UART_RX))
        val store = MapDriverStore()
        val session = BangleJsSession(testEnv(scope, FakeLinks(device), store = store))
        val events = events(session)
        session.open()
        assertTrue(device.subscribed.contains(BangleJs.UART_RX))

        fun sent(): String = device.writes.toList().filter { it.characteristic == BangleJs.UART_TX }.joinToString("") { String(it.value, Charsets.ISO_8859_1) }

        session.findDevice(true)
        assertEquals(BangleJs.find(true), sent())
        // Written in 20-byte packets until the watch sends a longer one.
        assertTrue(device.writes.all { it.value.size <= 20 })

        device.notify(BangleJs.UART_RX, "{\"t\":\"status\",\"bat\":77,\"chg\":0}\r\n{\"t\":\"ver\",\"fw\":\"2v21\",\"hw\":\"BANGLEJS2\"}\n".toByteArray())
        device.notify(BangleJs.UART_RX, "{\"t\":\"findPhone\",\"n\":true}\n".toByteArray())
        waitFor { events.any { it is WearableEvent.Battery } && events.any { it is WearableEvent.Info } && events.contains(WearableEvent.FindPhone) }
        assertEquals(WearableEvent.Battery(77, false), events.first { it is WearableEvent.Battery })
        assertEquals("2v21", (events.first { it is WearableEvent.Info } as WearableEvent.Info).info.firmware)

        session.setLiveHeartRate(true)
        device.notify(BangleJs.UART_RX, "{\"t\":\"act\",\"hrm\":68,\"stp\":12,\"rt\":1}\n".toByteArray())
        waitFor { events.any { it is WearableEvent.HeartRate } }
        assertEquals(WearableEvent.HeartRate(68), events.first { it is WearableEvent.HeartRate })
        assertEquals(12, (events.first { it is WearableEvent.Activity } as WearableEvent.Activity).stepsIncrement)

        device.writes.clear()
        session.saveAlarm(null, AlarmSpec(7, 0))
        assertEquals(BangleJs.alarms(session.loadAlarms().alarms), sent())
        assertEquals(1, session.loadAlarms().alarms.size)
        session.setTime(1_800_000_000_000L, TimeZone.getTimeZone("UTC"), true)
        session.sendNotification(BandNotification(3, "x", "App", "T", "B", 0))
        session.close()
    }
}
