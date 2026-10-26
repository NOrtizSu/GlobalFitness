package com.enjoyyourtime.globalfitness.wearable.huami

import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import com.enjoyyourtime.globalfitness.scale.MapDriverStore
import com.enjoyyourtime.globalfitness.wearable.ConfiguredWearable
import com.enjoyyourtime.globalfitness.wearable.FakeGattDevice
import com.enjoyyourtime.globalfitness.wearable.FakeLinks
import com.enjoyyourtime.globalfitness.wearable.WearableEvent
import com.enjoyyourtime.globalfitness.wearable.WearableException
import com.enjoyyourtime.globalfitness.wearable.WearableFailure
import com.enjoyyourtime.globalfitness.wearable.hex
import com.enjoyyourtime.globalfitness.wearable.standard.StandardUuids
import com.enjoyyourtime.globalfitness.wearable.testEnv
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
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

/** A classic Huami session against a simulated band (auth, status, live data, find, notifications, alarms). */
class HuamiSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keyHex = "0123456789abcdef0123456789abcdef"
    private val random = hex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf")

    @After
    fun tearDown() = scope.cancel()

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(15_000) { block() } }

    private fun model(id: String) = HuamiModels.ALL.first { it.id == id }

    /** A Huami band that knows [bandKey] (hex). */
    private fun band(bandKey: String = keyHex, chunked: Boolean = true): FakeGattDevice {
        val fee0 = HuamiUuids.SERVICE_MIBAND
        val chars = buildList {
            add(HuamiUuids.SERVICE_MIBAND2 to HuamiUuids.AUTH)
            add(fee0 to HuamiUuids.CONFIGURATION)
            add(fee0 to HuamiUuids.BATTERY_INFO)
            add(fee0 to HuamiUuids.REALTIME_STEPS)
            add(fee0 to HuamiUuids.DEVICE_EVENT)
            add(fee0 to StandardUuids.CURRENT_TIME)
            if (chunked) add(fee0 to HuamiUuids.CHUNKED_TRANSFER)
            add(StandardUuids.HEART_RATE_SERVICE to StandardUuids.HEART_RATE_MEASUREMENT)
            add(StandardUuids.HEART_RATE_SERVICE to StandardUuids.HEART_RATE_CONTROL_POINT)
            add(StandardUuids.IMMEDIATE_ALERT_SERVICE to StandardUuids.ALERT_LEVEL)
            add(StandardUuids.ALERT_NOTIFICATION_SERVICE to StandardUuids.NEW_ALERT)
            add(StandardUuids.DEVICE_INFORMATION to StandardUuids.FIRMWARE_REVISION)
            add(StandardUuids.DEVICE_INFORMATION to StandardUuids.HARDWARE_REVISION)
        }
        val device = FakeGattDevice(chars = chars, mtu = 23)
        device.readValues[HuamiUuids.BATTERY_INFO] = hex("0f5501")
        device.readValues[StandardUuids.FIRMWARE_REVISION] = "V1.0.9.66".toByteArray()
        device.readValues[StandardUuids.HARDWARE_REVISION] = "V0.18.3.1".toByteArray()
        device.readValues[HuamiUuids.REALTIME_STEPS] = hex("0c" + "e803" + "00".repeat(10))
        device.onWrite = { w ->
            if (w.characteristic == HuamiUuids.AUTH) {
                val v = w.value
                when (v[0].toInt() and 0x0f) {
                    2 -> device.notify(HuamiUuids.AUTH, byteArrayOf(0x10, v[0], 0x01) + random)
                    3 -> {
                        @Suppress("GetInstance")
                        val expected = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(hex(bandKey), "AES")) }.doFinal(random)
                        val ok = v.copyOfRange(2, v.size).contentEquals(expected)
                        device.notify(HuamiUuids.AUTH, byteArrayOf(0x10, v[0], if (ok) 0x01 else 0x04))
                    }
                }
            }
        }
        return device
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun syncTimeOffSkipsTheTimeWrittenDuringAuthentication() = runTest {
        val device = band()
        val env = testEnv(scope, FakeLinks(device), keyHex, config = mapOf(ConfiguredWearable.CONFIG_SYNC_TIME to "0"))
        val session = HuamiSession(env, model("xiaomi_mi_band_5"))
        session.open()
        assertEquals(2, device.writesTo(HuamiUuids.AUTH).size)
        assertTrue(device.writesTo(StandardUuids.CURRENT_TIME).isEmpty())
        session.close()
    }

    @Test
    fun authenticatesAndRunsTheFeatures() = runTest {
        val device = band()
        val store = MapDriverStore()
        val session = HuamiSession(testEnv(scope, FakeLinks(device), keyHex, store), model("xiaomi_mi_band_5"))
        val events = CopyOnWriteArrayList<WearableEvent>()
        scope.launch { session.events.collect { events += it } }
        session.open()

        // Crypt flags 0x80 path, then the time right after the encrypted number.
        assertEquals(listOf("8200020100", "8300" + device.writesTo(HuamiUuids.AUTH)[1].substring(4)), device.writesTo(HuamiUuids.AUTH))
        assertEquals(1, device.writesTo(StandardUuids.CURRENT_TIME).size)
        assertTrue(device.subscribed.containsAll(listOf(HuamiUuids.AUTH, HuamiUuids.CONFIGURATION, HuamiUuids.BATTERY_INFO, HuamiUuids.DEVICE_EVENT)))

        session.refreshStatus(includeInfo = true)
        waitFor { events.any { it is WearableEvent.Battery } }
        assertEquals(WearableEvent.Battery(85, true), events.first { it is WearableEvent.Battery })
        assertEquals("1.0.9.66", (events.first { it is WearableEvent.Info } as WearableEvent.Info).info.firmware)

        session.setTime(0, TimeZone.getTimeZone("UTC"), true)
        assertEquals("b2070101000000040000" + "00", device.writesTo(StandardUuids.CURRENT_TIME).last())

        session.setLiveHeartRate(true)
        assertTrue(device.subscribed.contains(StandardUuids.HEART_RATE_MEASUREMENT))
        waitFor { device.writesTo(StandardUuids.HEART_RATE_CONTROL_POINT).size >= 4 } // keep-alive repeats
        assertEquals(listOf("150200", "150101", "150200", "150101"), device.writesTo(StandardUuids.HEART_RATE_CONTROL_POINT).take(4))
        device.notify(StandardUuids.HEART_RATE_MEASUREMENT, hex("0048"))
        device.notify(HuamiUuids.REALTIME_STEPS, hex("0c" + "d007" + "00".repeat(10)))
        waitFor { events.any { it is WearableEvent.HeartRate } && events.count { it is WearableEvent.Activity } >= 2 }
        assertEquals(WearableEvent.HeartRate(72), events.first { it is WearableEvent.HeartRate })
        assertEquals(2000, (events.last { it is WearableEvent.Activity } as WearableEvent.Activity).steps)
        session.setLiveHeartRate(false)
        assertEquals("150100", device.writesTo(StandardUuids.HEART_RATE_CONTROL_POINT).last())

        session.findDevice(true)
        session.findDevice(false)
        assertEquals(listOf("03", "00"), device.writesTo(StandardUuids.ALERT_LEVEL))

        session.sendNotification(BandNotification(1, "com.whatsapp", "WhatsApp", "Ana", "Hola", 0))
        val payload = HuamiProtocol.notificationPayload("com.whatsapp", "WhatsApp", "Ana", "Hola", true, 230)
        assertEquals(HuamiProtocol.chunkedFrames(0, payload, 23).map { it.hex() }, device.writesTo(HuamiUuids.CHUNKED_TRANSFER))

        session.saveAlarm(null, AlarmSpec(7, 30))
        session.saveAlarm(null, AlarmSpec(8, 0, enabled = false))
        session.deleteAlarm(1)
        assertEquals(listOf("02c0071e80", "0201080080", "0200000000"), device.writesTo(HuamiUuids.CONFIGURATION))
        assertEquals(listOf(2), session.loadAlarms().alarms.map { it.id })

        // The band asks to find the phone.
        device.notify(HuamiUuids.DEVICE_EVENT, hex("08"))
        waitFor { events.contains(WearableEvent.FindPhone) && device.writesTo(HuamiUuids.CONFIGURATION).last() == "06140000" }
        session.close()
        assertEquals(WearableFailure.CLOSED, session.awaitClosed().failure)
    }

    @Test
    fun wrongKey() = runTest {
        val session = HuamiSession(testEnv(scope, FakeLinks(band(bandKey = "ff".repeat(16))), keyHex), model("amazfit_gts_2"))
        try {
            session.open()
            fail("authenticated with a wrong key")
        } catch (e: WearableException) {
            assertEquals(WearableFailure.WRONG_KEY, e.failure)
        }
    }

    @Test
    fun miBand2NotificationsWithoutChunkedTransfer() = runTest {
        val device = band(chunked = false)
        val session = HuamiSession(testEnv(scope, FakeLinks(device), keyHex), model("xiaomi_mi_band_2"))
        session.open()
        assertEquals("0208", device.writesTo(HuamiUuids.AUTH).first())
        session.sendNotification(BandNotification(1, "com.whatsapp", "WhatsApp", "Ana", "x".repeat(20), 0))
        assertEquals(listOf("fa0107", "0501" + "x".repeat(18).toByteArray().hex(), "0501" + "xx".toByteArray().hex()), device.writesTo(StandardUuids.NEW_ALERT))
        assertEquals(listOf("ffc800c80003"), device.writesTo(StandardUuids.ALERT_LEVEL))
        session.close()
    }

    @Test
    fun missingKeyAndUnsupportedDevice() = runTest {
        try {
            HuamiSession(testEnv(scope, FakeLinks(band()), null), model("amazfit_bip")).open()
            fail()
        } catch (e: WearableException) {
            assertEquals(WearableFailure.NO_KEY, e.failure)
        }
        try {
            HuamiSession(testEnv(scope, FakeLinks(FakeGattDevice(chars = emptyList())), keyHex), model("amazfit_bip")).open()
            fail()
        } catch (e: WearableException) {
            assertEquals(WearableFailure.UNSUPPORTED_DEVICE, e.failure)
        }
    }
}
