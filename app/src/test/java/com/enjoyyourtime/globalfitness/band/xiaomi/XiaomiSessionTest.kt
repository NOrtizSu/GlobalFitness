package com.enjoyyourtime.globalfitness.band.xiaomi

import com.enjoyyourtime.globalfitness.band.proto.XiaomiProto
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** End-to-end: session ↔ [FakeBand] over SPP V1 and V2, including auth, correlation, acks and failures. */
class XiaomiSessionTest {
    private val keyBytes = range(0, 16)
    private val key = (AuthKey.parse(keyBytes.toHexString()) as AuthKeyResult.Valid).key
    private val companion = CompanionInfo(34, "Pixel 8", "ES")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logLines: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val fast = XiaomiSession.Timeouts(version = 300, sessionConfig = 1_000, auth = 1_000, request = 1_000, resend = 150)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun session(band: FakeBand, timeouts: XiaomiSession.Timeouts = fast) =
        XiaomiSession(band.link, XiaomiAuth(key), companion, scope, { logLines += it }, timeouts)

    private fun batteryReply(level: Int) = XiaomiProto.Command.newBuilder()
        .setType(XiaomiCommands.TYPE_SYSTEM)
        .setSubtype(XiaomiCommands.SYSTEM_BATTERY)
        .setSystem(XiaomiProto.System.newBuilder().setPower(XiaomiProto.Power.newBuilder().setBattery(XiaomiProto.Battery.newBuilder().setLevel(level))))
        .build()

    private fun answeringBattery(band: FakeBand) {
        band.onCommand = { cmd ->
            if (cmd.type == XiaomiCommands.TYPE_SYSTEM && cmd.subtype == XiaomiCommands.SYSTEM_BATTERY) listOf(batteryReply(77)) else emptyList()
        }
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    private suspend fun expectFailure(failure: BandFailure, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $failure")
        } catch (e: BandException) {
            assertEquals(failure, e.failure)
        }
    }

    @Test
    fun v1HandshakeAndRequest() = runTest {
        val band = FakeBand(keyBytes, sppVersion = 1).also(::answeringBattery)
        val session = session(band)
        session.handshake()

        assertEquals("SPP v1", session.protocolLabel)
        assertTrue(band.authenticated)
        assertEquals("Pixel 8", band.companionDevice!!.phoneName)
        val reply = session.request(XiaomiCommands.getBattery())
        assertEquals(77, reply.system.power.battery.level)
        assertEquals(listOf(2 to 1), band.received.map { it.type to it.subtype })

        session.close()
        assertEquals(BandFailure.CLOSED, session.awaitClosed().failure)
        assertFalse(session.isOpen)
    }

    @Test
    fun v2HandshakeRequestAndAcks() = runTest {
        val band = FakeBand(keyBytes, sppVersion = 2).also(::answeringBattery)
        val session = session(band)
        session.handshake()

        assertEquals("SPP v2", session.protocolLabel)
        assertEquals(77, session.request(XiaomiCommands.getBattery()).system.power.battery.level)
        // The band sent 3 data packets (nonce reply, confirm, battery): the phone acks each.
        repeat(50) { if (band.acksFromPhone.size < 3) delay(20) }
        assertEquals(listOf(0, 1, 2), band.acksFromPhone.toList())
        session.close()
    }

    @Test
    fun unsolicitedCommandsArePublished() = runTest {
        val band = FakeBand(keyBytes, sppVersion = 2)
        val session = session(band)
        session.handshake()

        val event = async { session.commands.first() }
        delay(50)
        band.push(
            XiaomiProto.Command.newBuilder()
                .setType(XiaomiCommands.TYPE_HEALTH)
                .setSubtype(XiaomiCommands.HEALTH_REALTIME_STATS_EVENT)
                .setHealth(XiaomiProto.Health.newBuilder().setRealTimeStats(XiaomiProto.RealTimeStats.newBuilder().setHeartRate(64).setSteps(1200)))
                .build(),
        )
        val stats = event.await().health.realTimeStats
        assertEquals(64, stats.heartRate)
        assertEquals(1200, stats.steps)
        session.close()
    }

    /** A command in the same read as the 1/27 confirmation, collected only later. */
    private fun commandRightAfterAuthIsKept(sppVersion: Int) = runTest {
        val band = FakeBand(keyBytes, sppVersion).apply { pushAfterAuth = batteryReply(55) }
        val session = session(band)
        session.handshake()
        delay(100) // nobody collects yet
        val pushed = withTimeout(2_000) { session.commands.first() }
        assertEquals(55, pushed.system.power.battery.level)
        assertTrue(logLines.none { it.contains("ilegible") || it.contains("descifrar") })
        session.close()
    }

    @Test
    fun v1CommandRightAfterAuthIsDecryptedAndBuffered() = commandRightAfterAuthIsKept(1)

    @Test
    fun v2CommandRightAfterAuthIsBuffered() = commandRightAfterAuthIsKept(2)

    @Test
    fun wrongKeyFailsTheHandshake() = runTest {
        val band = FakeBand(range(1, 17), sppVersion = 2)
        val session = session(band)
        expectFailure(BandFailure.WRONG_KEY) { session.handshake() }
        assertFalse(band.authenticated)
        assertEquals(BandFailure.WRONG_KEY, session.awaitClosed().failure)
    }

    @Test
    fun missingVersionAnswerFallsBackToV1() = runTest {
        val band = FakeBand(keyBytes, sppVersion = 1, answerVersion = false)
        val session = session(band)
        session.handshake()
        assertEquals("SPP v1", session.protocolLabel)
        assertTrue(band.authenticated)
        session.close()
    }

    @Test
    fun silentBandIsReportedAsNoResponse() = runTest {
        val band = FakeBand(keyBytes, sppVersion = 1, answerAuth = false)
        val session = session(band)
        expectFailure(BandFailure.NO_RESPONSE) { session.handshake() }
    }

    @Test
    fun requestTimesOutWhenTheBandDoesNotAnswer() = runTest {
        val band = FakeBand(keyBytes, sppVersion = 1)
        val session = session(band)
        session.handshake()
        expectFailure(BandFailure.REQUEST_TIMEOUT) { session.request(XiaomiCommands.getDeviceInfo(), timeoutMs = 200) }
        assertTrue(session.isOpen) // a missing answer does not end the session
        session.close()
    }

    @Test
    fun unackedV2PacketsAreResentThenTheLinkIsDropped() = runTest {
        val band = FakeBand(keyBytes, sppVersion = 2)
        val session = session(band)
        session.handshake()
        band.ackData = false
        val before = band.dataFramesFromPhone.size

        session.send(XiaomiCommands.findWatch(true))
        val reason = session.awaitClosed()

        assertEquals(BandFailure.LINK_LOST, reason.failure)
        // Sent once, resent after each of the first two timeouts, dropped at the third.
        val frames = band.dataFramesFromPhone.drop(before)
        assertEquals(3, frames.size)
        assertTrue(frames.all { it.contentEquals(frames[0]) })
        expectFailure(BandFailure.LINK_LOST) { session.send(XiaomiCommands.getBattery()) }
    }

    @Test
    fun keyNeverAppearsInTheLog() = runTest {
        val band = FakeBand(keyBytes, sppVersion = 2).also(::answeringBattery)
        val session = session(band)
        session.handshake()
        session.request(XiaomiCommands.getBattery())
        session.close()
        assertTrue(logLines.isNotEmpty())
        assertTrue(logLines.none { it.contains(key.toHex(), ignoreCase = true) })
    }
}
