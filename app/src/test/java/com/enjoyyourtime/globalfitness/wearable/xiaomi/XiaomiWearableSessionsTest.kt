package com.enjoyyourtime.globalfitness.wearable.xiaomi

import com.enjoyyourtime.globalfitness.band.proto.XiaomiProto
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import com.enjoyyourtime.globalfitness.band.xiaomi.FakeBand
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiCommands
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiSession
import com.enjoyyourtime.globalfitness.wearable.FakeLinks
import com.enjoyyourtime.globalfitness.wearable.WearableEvent
import com.enjoyyourtime.globalfitness.wearable.WearableException
import com.enjoyyourtime.globalfitness.wearable.WearableFailure
import com.enjoyyourtime.globalfitness.wearable.WearableSession
import com.enjoyyourtime.globalfitness.wearable.testEnv
import java.util.Collections
import java.util.TimeZone
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

/** Xiaomi sessions end to end: SPP (the Band 9 path) and BLE V1 / V1 plaintext / V2, against simulated devices. */
class XiaomiWearableSessionsTest {
    private val keyBytes = ByteArray(16) { it.toByte() }
    private val keyHex = keyBytes.joinToString("") { "%02x".format(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(15_000) { block() } }

    private fun model(id: String) = XiaomiModels.ALL.first { it.id == id }

    private fun batteryReply(level: Int) = XiaomiProto.Command.newBuilder().setType(2).setSubtype(1)
        .setSystem(XiaomiProto.System.newBuilder().setPower(XiaomiProto.Power.newBuilder().setBattery(XiaomiProto.Battery.newBuilder().setLevel(level).setState(1))))
        .build()

    private fun realtime(hr: Int, steps: Int) = XiaomiProto.Command.newBuilder().setType(8).setSubtype(47)
        .setHealth(XiaomiProto.Health.newBuilder().setRealTimeStats(XiaomiProto.RealTimeStats.newBuilder().setHeartRate(hr).setSteps(steps).setCalories(12)))
        .build()

    private fun answers(cmd: XiaomiProto.Command): List<XiaomiProto.Command> = when (cmd.type to cmd.subtype) {
        2 to 1 -> listOf(batteryReply(64))
        2 to 78 -> listOf(
            XiaomiProto.Command.newBuilder().setType(2).setSubtype(78).setSystem(
                XiaomiProto.System.newBuilder().setBasicDeviceState(
                    XiaomiProto.BasicDeviceState.newBuilder().setIsCharging(false).setIsWorn(true).setIsUserAsleep(false),
                ),
            ).build(),
        )
        2 to 2 -> listOf(
            XiaomiProto.Command.newBuilder().setType(2).setSubtype(2).setSystem(
                XiaomiProto.System.newBuilder().setDeviceInfo(XiaomiProto.DeviceInfo.newBuilder().setSerialNumber("S").setFirmware("2.1.0").setModel("M2")),
            ).build(),
        )
        17 to 0 -> listOf(
            XiaomiProto.Command.newBuilder().setType(17).setSubtype(0).setSchedule(
                XiaomiProto.Schedule.newBuilder().setAlarms(XiaomiProto.Alarms.newBuilder().setMaxAlarms(8)),
            ).build(),
        )
        else -> emptyList()
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    private suspend fun exercise(session: WearableSession, received: () -> List<XiaomiProto.Command>, push: (XiaomiProto.Command) -> Unit) = coroutineScopeEvents(session) { events ->
        session.refreshStatus(includeInfo = true)
        waitFor { events.any { it is WearableEvent.Battery } && events.any { it is WearableEvent.Info } }
        assertEquals(WearableEvent.Battery(64, true), events.first { it is WearableEvent.Battery })
        assertEquals("2.1.0", (events.first { it is WearableEvent.Info } as WearableEvent.Info).info.firmware)
        assertEquals(true, (events.first { it is WearableEvent.DeviceState } as WearableEvent.DeviceState).worn)

        session.setTime(1_800_000_000_000L, TimeZone.getTimeZone("UTC"), true)
        session.findDevice(true)
        session.sendNotification(BandNotification(5, "com.whatsapp", "WhatsApp", "Ana", "Hola", 1_800_000_000_000L))
        assertEquals(8, session.loadAlarms().maxAlarms)
        session.saveAlarm(null, AlarmSpec(7, 30))
        waitFor { received().count { it.type == 17 && it.subtype == 1 } == 1 }

        // Live heart rate: the stream is reported while wanted, then stopped.
        session.setLiveHeartRate(true)
        push(realtime(71, 1000))
        waitFor { events.any { it is WearableEvent.HeartRate } }
        assertEquals(WearableEvent.HeartRate(71), events.first { it is WearableEvent.HeartRate })
        assertEquals(1000, (events.first { it is WearableEvent.Activity } as WearableEvent.Activity).steps)
        session.setLiveHeartRate(false)
        push(realtime(72, 1001))
        // Failsafe: an unwanted stream is stopped again (8/46).
        waitFor { received().count { it.type == 8 && it.subtype == 46 } >= 2 }

        val sent = received().map { it.type to it.subtype }
        assertTrue(sent.toString(), sent.containsAll(listOf(2 to 3, 2 to 18, 7 to 0, 17 to 0, 8 to 45)))
    }

    private suspend fun coroutineScopeEvents(session: WearableSession, block: suspend (MutableList<WearableEvent>) -> Unit) {
        val list = Collections.synchronizedList(mutableListOf<WearableEvent>())
        val job = scope.launch { session.events.collect { list += it } }
        try {
            block(list)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun sppBand9PathUsesTheExistingSession() = runTest {
        val band = FakeBand(keyBytes, sppVersion = 2).apply { onCommand = ::answers }
        val links = FakeLinks(sppLink = band.link)
        val fast = XiaomiSession.Timeouts(version = 300, sessionConfig = 1_000, auth = 1_000, request = 1_500, resend = 2_000)
        val session = XiaomiSppWearableSession(testEnv(scope, links, keyHex), model("xiaomi_smart_band_9"), fast)
        session.open()
        assertEquals("SPP v2", session.label)
        exercise(session, { band.received.toList() }, { band.push(it) })
        session.close()
        assertEquals(WearableFailure.CLOSED, session.awaitClosed().failure)
    }

    @Test
    fun sppWrongKey() = runTest {
        val band = FakeBand(ByteArray(16) { 9 }, sppVersion = 1)
        val fast = XiaomiSession.Timeouts(version = 300, sessionConfig = 1_000, auth = 1_000, request = 1_500, resend = 2_000)
        val session = XiaomiSppWearableSession(testEnv(scope, FakeLinks(sppLink = band.link), keyHex), model("redmi_watch_4"), fast)
        expectFailure(WearableFailure.WRONG_KEY) { session.open() }
    }

    @Test
    fun bleV1Encrypted() = runTest {
        val band = FakeXiaomiBleBand(keyBytes, FakeXiaomiBleBand.Mode.V1_ENCRYPTED).apply { onCommand = ::answers }
        val session = XiaomiBleWearableSession(testEnv(scope, FakeLinks(band.device), keyHex), model("xiaomi_smart_band_8"))
        session.open()
        assertEquals("BLE v1", session.label)
        assertTrue(band.authenticated)
        exercise(session, { band.received.toList() }, { band.push(it) })
        // After authentication every message carries a fresh counter starting at 1.
        assertEquals((1..band.phoneCounters.size).toList(), band.phoneCounters.toList())
        // A long notification needs a chunked transfer (the body is capped at 512 characters, still several chunks).
        session.sendNotification(BandNotification(6, "com.whatsapp", "WhatsApp", "Ana", "x".repeat(600), 0))
        waitFor { band.received.count { it.type == 7 && it.subtype == 0 } == 2 }
        assertEquals("x".repeat(XiaomiCommands.MAX_NOTIFICATION_TEXT), band.received.last().notification.notification2.notification3.body)
        session.close()
    }

    @Test
    fun bleV1PlaintextWithUserId() = runTest {
        val band = FakeXiaomiBleBand(keyBytes, FakeXiaomiBleBand.Mode.V1_PLAINTEXT, userId = "1234567890").apply { onCommand = ::answers }
        val session = XiaomiBleWearableSession(testEnv(scope, FakeLinks(band.device), "1234567890"), model("xiaomi_mi_watch_lite"))
        session.open()
        assertEquals("BLE v1 (plaintext)", session.label)
        exercise(session, { band.received.toList() }, { band.push(it) })
        session.close()
    }

    @Test
    fun bleV1PlaintextWrongUserId() = runTest {
        val band = FakeXiaomiBleBand(keyBytes, FakeXiaomiBleBand.Mode.V1_PLAINTEXT, userId = "1")
        val session = XiaomiBleWearableSession(testEnv(scope, FakeLinks(band.device), "2"), model("xiaomi_mi_watch_lite"))
        expectFailure(WearableFailure.WRONG_KEY) { session.open() }
    }

    @Test
    fun bleV1WrongKey() = runTest {
        val band = FakeXiaomiBleBand(ByteArray(16) { 7 }, FakeXiaomiBleBand.Mode.V1_ENCRYPTED)
        val session = XiaomiBleWearableSession(testEnv(scope, FakeLinks(band.device), keyHex), model("redmi_watch_3"))
        expectFailure(WearableFailure.WRONG_KEY) { session.open() }
        assertTrue(band.device.closed)
    }

    @Test
    fun bleV2() = runTest {
        val band = FakeXiaomiBleBand(keyBytes, FakeXiaomiBleBand.Mode.V2).apply { onCommand = ::answers }
        val session = XiaomiBleWearableSession(testEnv(scope, FakeLinks(band.device), keyHex), model("xiaomi_smart_band_9_active"))
        session.open()
        assertEquals("BLE v2", session.label)
        exercise(session, { band.received.toList() }, { band.push(it) })
        session.close()
    }

    @Test
    fun bleWithoutXiaomiCharacteristics() = runTest {
        val device = com.enjoyyourtime.globalfitness.wearable.FakeGattDevice(chars = emptyList())
        val session = XiaomiBleWearableSession(testEnv(scope, FakeLinks(device), keyHex), model("xiaomi_smart_band_8"))
        expectFailure(WearableFailure.UNSUPPORTED_DEVICE) { session.open() }
    }

    private suspend fun expectFailure(failure: WearableFailure, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $failure")
        } catch (e: WearableException) {
            assertEquals(failure, e.failure)
        }
    }
}
