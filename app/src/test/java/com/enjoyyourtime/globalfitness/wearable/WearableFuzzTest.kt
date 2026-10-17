package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandLink
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiSession
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.wearable.banglejs.BangleJs
import com.enjoyyourtime.globalfitness.wearable.banglejs.MiniJson
import com.enjoyyourtime.globalfitness.wearable.huami.HuamiProtocol
import com.enjoyyourtime.globalfitness.wearable.huami.HuamiUuids
import com.enjoyyourtime.globalfitness.wearable.pinetime.InfiniTime
import com.enjoyyourtime.globalfitness.wearable.standard.StandardGatt
import com.enjoyyourtime.globalfitness.wearable.standard.StandardUuids
import com.enjoyyourtime.globalfitness.wearable.xiaomi.XiaomiBleChunker
import com.enjoyyourtime.globalfitness.wearable.xiaomi.XiaomiBleCipher
import com.enjoyyourtime.globalfitness.wearable.xiaomi.XiaomiBleUuids
import com.enjoyyourtime.globalfitness.wearable.xiaomi.XiaomiModels
import com.enjoyyourtime.globalfitness.wearable.xiaomi.XiaomiSppWearableSession
import com.enjoyyourtime.globalfitness.wearable.zeppos.ZeppOsChunkDecoder
import com.enjoyyourtime.globalfitness.wearable.zeppos.ZeppOsProtocol
import java.io.IOException
import java.util.Collections
import java.util.TimeZone
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Robustness: random, truncated and oversized input into every parser and every session type
 * (deterministic seeds). Nothing may throw out of a parser, and a session may only fail with a
 * [WearableException]; no coroutine may crash.
 */
class WearableFuzzTest {
    private val crashes: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> crashes += e })

    @After
    fun tearDown() = scope.cancel()

    private fun Random.bytes(max: Int): ByteArray = ByteArray(nextInt(0, max + 1)) { nextInt(256).toByte() }

    /** Random bytes, biased towards the prefixes the parsers look for. */
    private fun Random.frame(max: Int): ByteArray {
        val b = bytes(max)
        if (b.isNotEmpty() && nextInt(3) == 0) b[0] = listOf(0x00, 0x02, 0x03, 0x04, 0x05, 0x07, 0x0a, 0x10, 0xa5, 0xba).random(this).toByte()
        if (b.size > 2 && nextInt(3) == 0) b[1] = nextInt(0, 8).toByte()
        return b
    }

    private fun Random.text(max: Int): String = String(CharArray(nextInt(0, max + 1)) { "{}[]\":,tfn0123456789.-+eE\\u\u0010\n\r ab".random(this) })

    @Test
    fun parsersNeverThrow() {
        val rnd = Random(20261016)
        val failures = ArrayList<String>()
        fun check(name: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                failures += "$name: $e"
            }
        }
        val cipher = object : XiaomiBleCipher {
            override val ready = true
            override fun encrypt(plain: ByteArray, counter: Int) = plain
            override fun decrypt(cipher: ByteArray): ByteArray = if (cipher.size % 3 == 0) throw IllegalStateException("bad") else cipher
        }
        val chunkers = listOf(XiaomiBleChunker(true, cipher), XiaomiBleChunker(false, null))
        val decoders = listOf(ZeppOsChunkDecoder(), ZeppOsChunkDecoder().apply { setEncryption(ByteArray(16) { 1 }) })
        val lines = BangleJs.LineBuffer()
        repeat(20_000) {
            val b = rnd.frame(80)
            check("hrs") { StandardGatt.parseHeartRate(b) }
            check("battery") { StandardGatt.parseBatteryLevel(b) }
            check("dis") { StandardGatt.parseString(b) }
            check("huami auth") { HuamiProtocol.parseAuth(b) }
            check("huami battery") { HuamiProtocol.parseBattery(b) }
            check("huami steps") { HuamiProtocol.parseRealtimeSteps(b) }
            check("zepp challenge") { ZeppOsProtocol.parseAuthChallenge(b) }
            check("zepp result") { ZeppOsProtocol.parseAuthResult(b) }
            check("zepp services") { ZeppOsProtocol.parseServices(b) }
            check("zepp battery") { ZeppOsProtocol.parseBattery(b) }
            check("zepp info") { ZeppOsProtocol.parseDeviceInfo(b) }
            check("zepp caps") { ZeppOsProtocol.parseNotificationCapabilities(b) }
            check("zepp alarms") { ZeppOsProtocol.parseAlarms(b) }
            check("zepp steps") { ZeppOsProtocol.parseRealtimeSteps(b) }
            check("infinitime steps") { InfiniTime.parseSteps(b) }
            chunkers.forEach { c -> check("xiaomi chunker") { c.onNotification(b) } }
            if (it % 50 == 0) chunkers.forEach { c -> check("xiaomi chunker timeout") { c.onReceiveTimeout(); c.onAckTimeout() } }
            decoders.forEach { d -> check("zepp decoder") { d.decode(b) } }
            check("bangle lines") { lines.append(b).forEach { l -> BangleJs.parseLine(l) } }
            val t = rnd.text(60)
            check("bangle line") { BangleJs.parseLine("{\"t\"" + t) }
            check("bangle encode") { BangleJs.encode(t) }
            check("mini json") {
                try {
                    MiniJson.parse(t)
                } catch (e: IllegalArgumentException) {
                    // rejected input is fine
                }
            }
            check("local alarms") { LocalAlarms.decode(t) }
            check("codec") { ConfiguredWearableCodec.decode("w1\n" + t.replace('u', '\t')) }
            check("keys") {
                AuthKeyKind.entries.forEach { k ->
                    WearableKeys.validate(k, t)
                    WearableKeys.extractFromClipboard(k, t)
                }
            }
        }
        assertTrue(failures.take(10).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun matchersNeverThrow() {
        val rnd = Random(7)
        val registry = AllWearables.registry()
        val services = listOf(0x180D, 0x180F, 0xFE95, 0xFEE0, 0xFEE1, 0x1811).map { Bytes.uuid16(it) } + XiaomiBleUuids.ADVERTISED_SERVICES + BangleJs.UART_SERVICE
        val failures = ArrayList<String>()
        repeat(3_000) {
            val name = when (rnd.nextInt(4)) {
                0 -> null
                1 -> rnd.text(40)
                2 -> String(CharArray(rnd.nextInt(0, 30)) { rnd.nextInt(32, 0x2FF).toChar() })
                else -> listOf("Xiaomi Smart Band 9 ", "Amazfit ", "Mi Band ", "Redmi Watch ", "InfiniTime", "Bangle.js").random(rnd) + rnd.text(6)
            }
            val s = WearableSighting(
                address = "AA:BB:CC:DD:EE:FF",
                name = name,
                rssi = if (rnd.nextBoolean()) -rnd.nextInt(30, 100) else null,
                bonded = rnd.nextBoolean(),
                serviceUuids = services.shuffled(rnd).take(rnd.nextInt(0, 3)),
                manufacturerData = mapOf(rnd.nextInt(0x10000) to rnd.bytes(30)),
            )
            for (d in registry.drivers) {
                try {
                    d.match(s)
                } catch (e: Exception) {
                    failures += "${d.id}: $e on $s"
                }
            }
        }
        assertTrue(failures.take(10).joinToString("\n"), failures.isEmpty())
    }

    // ---- sessions ----

    private val allChars: List<Pair<UUID, UUID>> = buildList {
        XiaomiBleUuids.V1_SETS.take(2).forEach { s -> listOfNotNull(s.commandRead, s.commandWrite, s.activityData, s.dataUpload).forEach { add(s.service to it) } }
        add(XiaomiBleUuids.V2_SERVICE to XiaomiBleUuids.V2_RX)
        add(XiaomiBleUuids.V2_SERVICE to XiaomiBleUuids.V2_TX)
        add(HuamiUuids.SERVICE_MIBAND2 to HuamiUuids.AUTH)
        listOf(HuamiUuids.CONFIGURATION, HuamiUuids.BATTERY_INFO, HuamiUuids.REALTIME_STEPS, HuamiUuids.DEVICE_EVENT, HuamiUuids.CHUNKED_TRANSFER, HuamiUuids.CHUNKED_2021_WRITE, HuamiUuids.CHUNKED_2021_READ, StandardUuids.CURRENT_TIME)
            .forEach { add(HuamiUuids.SERVICE_MIBAND to it) }
        add(StandardUuids.HEART_RATE_SERVICE to StandardUuids.HEART_RATE_MEASUREMENT)
        add(StandardUuids.HEART_RATE_SERVICE to StandardUuids.HEART_RATE_CONTROL_POINT)
        add(StandardUuids.BATTERY_SERVICE to StandardUuids.BATTERY_LEVEL)
        add(StandardUuids.DEVICE_INFORMATION to StandardUuids.FIRMWARE_REVISION)
        add(StandardUuids.IMMEDIATE_ALERT_SERVICE to StandardUuids.ALERT_LEVEL)
        add(StandardUuids.ALERT_NOTIFICATION_SERVICE to StandardUuids.NEW_ALERT)
        add(StandardUuids.CURRENT_TIME_SERVICE to StandardUuids.LOCAL_TIME_INFORMATION)
        add(InfiniTime.MOTION_SERVICE to InfiniTime.STEP_COUNT)
        add(BangleJs.UART_SERVICE to BangleJs.UART_TX)
        add(BangleJs.UART_SERVICE to BangleJs.UART_RX)
    }

    private val fuzzTimeouts = WearableTimeouts(connect = 500, auth = 150, request = 150, optionalStep = 80)

    private fun randomDevice(rnd: Random): FakeGattDevice {
        val chars = allChars.filter { rnd.nextInt(4) != 0 }
        val device = FakeGattDevice(chars = chars, mtu = listOf(23, 185, 247).random(rnd))
        chars.forEach { (_, c) -> if (rnd.nextBoolean()) device.readValues[c] = rnd.frame(30) }
        device.onWrite = {
            synchronized(rnd) {
                repeat(rnd.nextInt(0, 3)) {
                    val (_, c) = chars.randomOrNull(rnd) ?: return@repeat
                    device.notify(c, rnd.frame(60))
                }
            }
        }
        return device
    }

    /** Returns true when the session opened (and was then exercised). */
    private suspend fun exercise(session: WearableSession, rnd: Random, failures: MutableList<String>, push: () -> Unit): Boolean {
        try {
            withTimeout(5_000) { session.open() }
        } catch (e: WearableException) {
            return false
        } catch (e: CancellationException) {
            failures += "${session.javaClass.simpleName}: open hung"
            session.close()
            return false
        } catch (e: Exception) {
            failures += "${session.javaClass.simpleName}.open: $e"
            return false
        }
        val actions: List<suspend () -> Unit> = listOf(
            { session.refreshStatus(rnd.nextBoolean()) },
            { session.setTime(0, TimeZone.getTimeZone("UTC"), true) },
            { session.findDevice(rnd.nextBoolean()) },
            { session.setLiveHeartRate(rnd.nextBoolean()) },
            { session.sendNotification(BandNotification(1, "com.x", "X", "t", "b".repeat(rnd.nextInt(600)), 0)) },
            { session.dismissNotification(1, "com.x") },
            { session.loadAlarms() },
            { session.saveAlarm(null, AlarmSpec(1, 2)) },
            { session.deleteAlarm(1) },
        )
        repeat(12) {
            push()
            try {
                withTimeout(3_000) { actions.random(rnd)() }
            } catch (e: WearableException) {
                // fine
            } catch (e: CancellationException) {
                failures += "${session.javaClass.simpleName}: action hung"
            } catch (e: Exception) {
                failures += "${session.javaClass.simpleName}: $e"
            }
        }
        session.close()
        withTimeout(3_000) { session.awaitClosed() }
        return true
    }

    @Test
    fun gattSessionsSurviveRandomDevices() = runBlocking {
        val registry = AllWearables.registry()
        val drivers = listOf("xiaomi_smart_band_8", "xiaomi_mi_watch_lite", "xiaomi_mi_band_5", "xiaomi_mi_band_2", "amazfit_band_7", "pinetime_infinitime", "banglejs", "standard_heart_rate")
            .map { requireNotNull(registry.find(it)) }
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val opened = HashSet<String>()
        for ((i, driver) in drivers.withIndex()) {
            repeat(6) { round ->
                val rnd = Random(1000 * i + round)
                val device = randomDevice(rnd)
                val key = if (driver.authKey == AuthKeyKind.NUMERIC_USER_ID) "1234567890" else "00112233445566778899aabbccddeeff"
                val session = driver.createSession(testEnv(scope, FakeLinks(device), key, timeouts = fuzzTimeouts))
                val ok = exercise(session, rnd, failures) {
                    synchronized(rnd) { repeat(rnd.nextInt(0, 6)) { device.notify(allChars.random(rnd).second, rnd.frame(80)) } }
                }
                if (ok) opened += driver.id
            }
        }
        delay(100)
        assertTrue(failures.take(10).joinToString("\n"), failures.isEmpty())
        assertTrue(crashes.take(5).joinToString("\n"), crashes.isEmpty())
        // The key-less sessions open on random devices, so their feature paths were exercised too.
        assertTrue(opened.toString(), opened.containsAll(listOf("pinetime_infinitime", "banglejs", "standard_heart_rate")))
    }

    @Test
    fun sppSessionSurvivesRandomStreams() = runBlocking {
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val model = XiaomiModels.ALL.first { it.id == XiaomiModels.BAND_9_ID }
        repeat(8) { round ->
            val rnd = Random(round)
            val incoming = Channel<ByteArray>(Channel.UNLIMITED)
            val link = object : BandLink {
                override suspend fun read(): ByteArray = try {
                    incoming.receive()
                } catch (e: Exception) {
                    throw IOException("closed")
                }

                override suspend fun write(data: ByteArray) {
                    synchronized(rnd) { repeat(rnd.nextInt(0, 3)) { incoming.trySend(rnd.frame(120)) } }
                }

                override fun close() {
                    incoming.close()
                }
            }
            val timeouts = XiaomiSession.Timeouts(version = 100, sessionConfig = 150, auth = 150, request = 150, resend = 100)
            val session = XiaomiSppWearableSession(testEnv(scope, FakeLinks(sppLink = link), "00112233445566778899aabbccddeeff", timeouts = fuzzTimeouts), model, timeouts)
            exercise(session, rnd, failures) { synchronized(rnd) { repeat(rnd.nextInt(0, 4)) { incoming.trySend(rnd.frame(120)) } } }
        }
        delay(100)
        assertTrue(failures.take(10).joinToString("\n"), failures.isEmpty())
        assertTrue(crashes.take(5).joinToString("\n"), crashes.isEmpty())
    }
}
