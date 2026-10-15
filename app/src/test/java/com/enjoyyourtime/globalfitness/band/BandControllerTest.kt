package com.enjoyyourtime.globalfitness.band

import com.enjoyyourtime.globalfitness.band.proto.XiaomiProto
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKey
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKeyResult
import com.enjoyyourtime.globalfitness.band.xiaomi.BandFailure
import com.enjoyyourtime.globalfitness.band.xiaomi.BandLink
import com.enjoyyourtime.globalfitness.band.xiaomi.CompanionInfo
import com.enjoyyourtime.globalfitness.band.xiaomi.FakeBand
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiCommands
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiSession
import com.enjoyyourtime.globalfitness.band.xiaomi.range
import com.enjoyyourtime.globalfitness.band.xiaomi.toHexString
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-demand connection lifecycle and actions, against a simulated band (SPP V2). */
@OptIn(ExperimentalCoroutinesApi::class)
class BandControllerTest {
    private val keyBytes = range(0, 16)
    private val key = (AuthKey.parse(keyBytes.toHexString()) as AuthKeyResult.Valid).key

    // Single-threaded, like the main thread the app runs the controller on.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val log = BandLog()
    private val timing = BandTiming(
        idleTimeoutMs = 60_000,
        leaveGraceMs = 200,
        stopTimeoutMs = 300,
        session = XiaomiSession.Timeouts(version = 300, sessionConfig = 1_000, auth = 1_000, request = 3_000, resend = 2_000),
    )

    private class FakePlatform(@Volatile var band: FakeBand, private val key: AuthKey) : BandPlatform {
        @Volatile
        var gate: CompletableDeferred<Unit>? = null
        val saved: MutableList<HeartRateSample> = Collections.synchronizedList(mutableListOf())
        override val appPackageName = "com.enjoyyourtime.globalfitness"

        override fun preconditionFailure(): BandFailure? = null
        override fun authKey(): AuthKey = key
        override fun selectedBand() = SelectedBand("AA:BB:CC:DD:EE:FF", "Xiaomi Smart Band 9 1234")
        override suspend fun openLink(address: String): BandLink {
            gate?.await()
            return band.link
        }
        override fun isBusyElsewhere(address: String) = false
        override fun companionInfo() = CompanionInfo(34, "Pixel 8", "ES")
        override fun syncTimeOnConnect() = false
        override fun is24HourFormat() = true
        override fun saveHeartRate(sample: HeartRateSample, address: String?) {
            saved += sample
        }
        override fun beforeConnect() = Unit
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private suspend fun BandController.awaitState(predicate: (BandState) -> Boolean): BandState =
        withTimeout(5_000) { state.first(predicate) }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(20)
    }

    private fun command(type: Int, subtype: Int, build: XiaomiProto.Command.Builder.() -> Unit = {}): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder().setType(type).setSubtype(subtype).apply(build).build()

    /** Answers the initial requests, plus [extra] for anything else. */
    private fun responsiveBand(extra: (XiaomiProto.Command) -> List<XiaomiProto.Command> = { emptyList() }) =
        FakeBand(keyBytes, sppVersion = 2).apply {
            onCommand = { cmd ->
                when (cmd.type to cmd.subtype) {
                    2 to 2 -> listOf(
                        command(2, 2) {
                            setSystem(
                                XiaomiProto.System.newBuilder().setDeviceInfo(
                                    XiaomiProto.DeviceInfo.newBuilder().setSerialNumber("S1").setFirmware("3.1.0").setModel("M2345B1"),
                                ),
                            )
                        },
                    )
                    2 to 78 -> listOf(
                        command(2, 78) {
                            setSystem(
                                XiaomiProto.System.newBuilder().setBasicDeviceState(
                                    XiaomiProto.BasicDeviceState.newBuilder().setIsCharging(false).setIsWorn(true).setIsUserAsleep(false).setBatteryLevel(80),
                                ),
                            )
                        },
                    )
                    2 to 1 -> listOf(command(2, 1) { setSystem(XiaomiProto.System.newBuilder().setPower(XiaomiProto.Power.newBuilder().setBattery(XiaomiProto.Battery.newBuilder().setLevel(80)))) })
                    17 to 0 -> listOf(command(17, 0) { setSchedule(XiaomiProto.Schedule.newBuilder().setAlarms(XiaomiProto.Alarms.newBuilder().setMaxAlarms(10))) })
                    else -> extra(cmd)
                }
            }
        }

    private fun findWatchReply(value: Int) = command(XiaomiCommands.TYPE_SYSTEM, XiaomiCommands.SYSTEM_FIND_WATCH) {
        setSystem(XiaomiProto.System.newBuilder().setFindDevice(value))
    }

    private fun sent(band: FakeBand, type: Int, subtype: Int) = band.received.toList().count { it.type == type && it.subtype == subtype }

    @Test
    fun connectsAndLoadsTheBandStatus() = runTest {
        val band = responsiveBand()
        val controller = BandController(scope, log, FakePlatform(band, key), timing)
        controller.connect()
        val ready = controller.awaitState { it.connection == BandConnectionState.READY && it.alarms != null }
        assertEquals("SPP v2", ready.protocol)
        assertEquals("3.1.0", ready.info?.firmware)
        assertEquals(80, ready.batteryLevel)
        assertEquals(true, ready.worn)
        assertEquals(10, ready.maxAlarms)
    }

    @Test
    fun disconnectWhileInitialRequestsArePendingReleasesTheBand() = runTest {
        // The band never answers the device info request, so the initial requests are still running.
        val band = FakeBand(keyBytes, sppVersion = 2)
        val controller = BandController(scope, log, FakePlatform(band, key), timing)
        controller.connect()
        controller.awaitState { it.connection == BandConnectionState.READY }
        waitUntil { sent(band, 2, 2) == 1 }

        controller.disconnect()

        val state = controller.awaitState { it.connection == BandConnectionState.DISCONNECTED }
        assertTrue(band.linkClosed)
        assertTrue(state.releasedForMiFitness)
    }

    @Test
    fun reconnectRightAfterCancellingAPendingConnectKeepsTheNewSession() = runTest {
        val first = FakeBand(keyBytes, sppVersion = 2)
        val second = responsiveBand()
        val platform = FakePlatform(first, key).apply { gate = CompletableDeferred() }
        val controller = BandController(scope, log, platform, timing)

        controller.connect()
        controller.awaitState { it.connection == BandConnectionState.CONNECTING }
        controller.disconnect()
        platform.gate = null
        platform.band = second
        controller.connect()

        controller.awaitState { it.connection == BandConnectionState.READY }
        controller.findDevice(true)
        waitUntil { sent(second, 2, 18) == 1 }
        delay(200)
        assertEquals(BandConnectionState.READY, controller.state.value.connection)
        assertFalse(second.linkClosed)
        assertFalse(first.authenticated)
    }

    @Test
    fun liveHeartRateEventRightAfterStartIsKept() = runTest {
        val event = command(XiaomiCommands.TYPE_HEALTH, XiaomiCommands.HEALTH_REALTIME_STATS_EVENT) {
            setHealth(XiaomiProto.Health.newBuilder().setRealTimeStats(XiaomiProto.RealTimeStats.newBuilder().setHeartRate(70).setSteps(1234)))
        }
        // The band answers the start with an event immediately, before send() returns on the phone.
        val band = responsiveBand { cmd -> if (cmd.type == 8 && cmd.subtype == 45) listOf(event) else emptyList() }
        val platform = FakePlatform(band, key)
        val controller = BandController(scope, log, platform, timing)
        controller.connect()
        controller.awaitState { it.connection == BandConnectionState.READY }

        controller.setLiveHeartRate(true)

        val state = controller.awaitState { it.live?.heartRate == 70 }
        assertEquals(1234, state.live?.steps)
        delay(300)
        assertEquals(0, sent(band, 8, 46)) // no "nobody is listening" stop
        assertEquals(listOf(70), platform.saved.map { it.bpm })
        assertEquals(listOf(70), controller.heartRate.value.map { it.bpm })
    }

    @Test
    fun foundItIsOnlyReportedWhileSearching() = runTest {
        // The band answers "stop" with 2/18 value 1, the same value as a "found it" press.
        val band = responsiveBand { cmd -> if (cmd.type == 2 && cmd.subtype == 18 && cmd.system.findDevice == 1) listOf(findWatchReply(1)) else emptyList() }
        val controller = BandController(scope, log, FakePlatform(band, key), timing)
        val messages = Collections.synchronizedList(mutableListOf<String>())
        val collector = launch(Dispatchers.Default) { controller.messages.collect { messages += it } }
        delay(50)
        controller.connect()
        controller.awaitState { it.connection == BandConnectionState.READY }

        controller.findDevice(true)
        controller.awaitState { it.finding }
        band.push(findWatchReply(1)) // "found it" pressed on the band
        controller.awaitState { !it.finding }
        waitUntil { messages.contains(BandController.FOUND_MESSAGE) }

        controller.findDevice(true)
        controller.awaitState { it.finding }
        controller.findDevice(false)
        controller.awaitState { !it.finding }
        waitUntil { sent(band, 2, 18) == 3 }
        delay(300)
        assertEquals(1, messages.count { it == BandController.FOUND_MESSAGE })
        collector.cancel()
    }

    @Test
    fun idleTimeoutReleasesTheBand() = runTest {
        val band = responsiveBand()
        val controller = BandController(scope, log, FakePlatform(band, key), timing.copy(idleTimeoutMs = 300))
        controller.connect()
        controller.awaitState { it.connection == BandConnectionState.READY }
        val state = controller.awaitState { it.connection == BandConnectionState.DISCONNECTED }
        assertTrue(state.releasedForMiFitness)
        assertTrue(band.linkClosed)
    }

    @Test
    fun leavingTheScreenDisconnectsUnlessComingBackWithinTheGrace() = runTest {
        val band = responsiveBand()
        val controller = BandController(scope, log, FakePlatform(band, key), timing)
        controller.onUiVisible()
        controller.connect()
        controller.awaitState { it.connection == BandConnectionState.READY }

        controller.onUiHidden()
        controller.onUiVisible() // e.g. a rotation
        delay(timing.leaveGraceMs + 200)
        assertEquals(BandConnectionState.READY, controller.state.value.connection)

        controller.onUiHidden()
        controller.awaitState { it.connection == BandConnectionState.DISCONNECTED }
        assertTrue(band.linkClosed)
    }

    @Test
    fun wrongKeyIsReportedAndReleasesTheBand() = runTest {
        val band = FakeBand(range(1, 17), sppVersion = 2)
        val controller = BandController(scope, log, FakePlatform(band, key), timing)
        controller.connect()
        val state = controller.awaitState { it.failure != null }
        assertEquals(BandFailure.WRONG_KEY, state.failure)
        assertEquals(BandConnectionState.DISCONNECTED, state.connection)
        assertTrue(band.linkClosed)
        assertTrue(log.lines.value.none { it.text.contains(key.toHex(), ignoreCase = true) })
    }
}
