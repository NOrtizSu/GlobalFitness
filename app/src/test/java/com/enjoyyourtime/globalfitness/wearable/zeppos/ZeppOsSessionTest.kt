package com.enjoyyourtime.globalfitness.wearable.zeppos

import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import com.enjoyyourtime.globalfitness.wearable.FakeGattDevice
import com.enjoyyourtime.globalfitness.wearable.FakeLinks
import com.enjoyyourtime.globalfitness.wearable.WearableEvent
import com.enjoyyourtime.globalfitness.wearable.WearableException
import com.enjoyyourtime.globalfitness.wearable.WearableFailure
import com.enjoyyourtime.globalfitness.wearable.hex
import com.enjoyyourtime.globalfitness.wearable.huami.HuamiUuids
import com.enjoyyourtime.globalfitness.wearable.standard.StandardUuids
import com.enjoyyourtime.globalfitness.wearable.testEnv
import java.util.Collections
import java.util.Random
import java.util.TimeZone
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

/** Zepp OS end to end: ECDH auth, encrypted endpoints, services list and the features, against a simulated watch. */
class ZeppOsSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keyHex = "00112233445566778899aabbccddeeff"

    @After
    fun tearDown() = scope.cancel()

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    /** The watch side, built from the same format description (its own encoder / decoder instances). */
    private class Watch(watchKey: ByteArray) {
        private val key = watchKey
        val device = FakeGattDevice(
            chars = listOf(
                HuamiUuids.SERVICE_MIBAND to HuamiUuids.CHUNKED_2021_WRITE,
                HuamiUuids.SERVICE_MIBAND to HuamiUuids.CHUNKED_2021_READ,
                StandardUuids.HEART_RATE_SERVICE to StandardUuids.HEART_RATE_MEASUREMENT,
            ),
        )
        private val decoder = ZeppOsChunkDecoder()
        private val encoder = ZeppOsChunkEncoder(247)
        private val privateKey = ByteArray(24).also { val r = Random(5); do r.nextBytes(it) while (EcdhB163.generatePublic(it) == null) }
        private val random = ByteArray(16) { (0x30 + it).toByte() }
        private var sessionKey = ByteArray(16)
        val encryptedEndpoints = mapOf(
            0x0029 to true, 0x0047 to false, 0x001a to true, 0x001d to false, 0x0016 to false,
            0x001e to true, 0x000f to false, 0x0015 to true, 0x0043 to false,
        )

        /** (endpoint, payload) the phone sent after authentication. */
        val received: MutableList<Pair<Int, String>> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var authenticated = false

        init {
            device.onWrite = { w -> synchronized(this) { if (w.characteristic == HuamiUuids.CHUNKED_2021_WRITE) onChunk(w.value) } }
        }

        private fun onChunk(value: ByteArray) {
            val message = decoder.decode(value)?.message ?: return
            onMessage(message.endpoint, message.payload)
        }

        @Synchronized
        fun send(endpoint: Int, payload: ByteArray) {
            val encrypt = authenticated && encryptedEndpoints[endpoint] == true
            encoder.encode(endpoint, payload, encrypt).forEach { device.notify(HuamiUuids.CHUNKED_2021_READ, it) }
        }

        @Suppress("GetInstance")
        private fun aes(k: ByteArray, b: ByteArray) = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES")) }.doFinal(b)

        private fun onMessage(endpoint: Int, payload: ByteArray) {
            if (endpoint == ZeppOsProtocol.AUTH) {
                when (payload[0].toInt()) {
                    0x04 -> {
                        val phonePublic = payload.copyOfRange(4, 52)
                        val shared = EcdhB163.generateShared(privateKey, phonePublic)!!
                        val session = ZeppOsProtocol.deriveSession(shared, key)
                        sessionKey = session.key
                        encoder.setEncryption(session.sequence, session.key)
                        decoder.setEncryption(session.key)
                        send(endpoint, hex("100401") + random + EcdhB163.generatePublic(privateKey)!!)
                    }
                    0x05 -> {
                        val ok = payload.copyOfRange(1, 17).contentEquals(aes(key, random)) &&
                            payload.copyOfRange(17, 33).contentEquals(aes(sessionKey, random))
                        send(endpoint, hex(if (ok) "100501" else "100525"))
                        authenticated = ok
                    }
                }
                return
            }
            received += endpoint to payload.hex()
            when (endpoint) {
                ZeppOsProtocol.SERVICES -> {
                    val body = encryptedEndpoints.entries.flatMap { (e, enc) -> listOf(e and 0xff, e shr 8, if (enc) 1 else 0) }
                    send(endpoint, byteArrayOf(0x04, encryptedEndpoints.size.toByte(), 0) + body.map { it.toByte() }.toByteArray())
                }
                ZeppOsProtocol.NOTIFICATIONS -> if (payload[0].toInt() == 0x01) send(endpoint, hex("0205640001000101 01"))
                ZeppOsProtocol.BATTERY -> send(endpoint, hex("040f4c00") + ByteArray(17))
                ZeppOsProtocol.ALARMS -> if (payload[0].toInt() == 0x09) send(endpoint, hex("0a01" + "0400071e1f0000000000"))
                ZeppOsProtocol.DEVICE_INFO -> send(endpoint, hex("02010800000000000000") + "3.18.2\u0000".toByteArray())
            }
        }
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    private fun model(id: String) = ZeppOsModels.ALL.first { it.id == id }

    @Test
    fun authenticatesAndTalksToEncryptedEndpoints() = runTest {
        val watch = Watch(hex(keyHex))
        val session = ZeppOsSession(testEnv(scope, FakeLinks(watch.device), keyHex), model("amazfit_band_7"))
        val events = Collections.synchronizedList(mutableListOf<WearableEvent>())
        scope.launch { session.events.collect { events += it } }
        session.open()
        assertTrue(watch.authenticated)
        assertEquals("Zepp OS", session.label)
        // Services list then notification capabilities and the find capabilities.
        assertEquals(listOf(0x0000 to "03", 0x001e to "01"), watch.received.take(2))

        session.refreshStatus(includeInfo = true)
        waitFor { events.any { it is WearableEvent.Battery } }
        assertEquals(WearableEvent.Battery(76, false), events.first { it is WearableEvent.Battery })
        assertEquals("3.18.2", (events.first { it is WearableEvent.Info } as WearableEvent.Info).info.firmware)

        session.setTime(0, TimeZone.getTimeZone("UTC"), true)
        session.findDevice(true)
        session.setLiveHeartRate(true)
        session.sendNotification(BandNotification(9, "com.whatsapp", "WhatsApp", "Ana", "Hola", 0))
        val alarms = session.loadAlarms()
        assertEquals(listOf(1), alarms.alarms.map { it.id })
        session.saveAlarm(null, AlarmSpec(6, 0))
        waitFor { watch.received.any { it.first == ZeppOsProtocol.ALARMS && it.second.startsWith("0301") } }

        val got = watch.received.toList()
        assertTrue(got.contains(ZeppOsProtocol.TIME to ("05" + ZeppOsProtocol.currentTimeBytes(0, TimeZone.getTimeZone("UTC")).hex())))
        assertTrue(got.contains(ZeppOsProtocol.FIND_DEVICE to "03"))
        assertTrue(got.contains(ZeppOsProtocol.HEART_RATE to "0401"))
        assertTrue(got.contains(ZeppOsProtocol.STEPS to "0501"))
        val caps = ZeppOsProtocol.NotificationCapabilities(5, true, true)
        assertTrue(got.contains(ZeppOsProtocol.NOTIFICATIONS to ZeppOsProtocol.notification(9, "com.whatsapp", "WhatsApp", "Ana", "Hola", false, caps).hex()))
        // Slot 1 is used: the new alarm goes to position 1 (id 2).
        assertTrue(got.contains(ZeppOsProtocol.ALARMS to ZeppOsProtocol.saveAlarm(1, AlarmSpec(6, 0)).hex()))

        // Live data, keep-alive and the watch's ping.
        watch.device.notify(StandardUuids.HEART_RATE_MEASUREMENT, hex("0050"))
        watch.send(ZeppOsProtocol.STEPS, hex("07 00 1027") + ByteArray(11))
        watch.send(ZeppOsProtocol.CONNECTION, hex("03"))
        waitFor { events.any { it is WearableEvent.HeartRate } && events.any { it is WearableEvent.Activity } }
        assertEquals(WearableEvent.HeartRate(80), events.first { it is WearableEvent.HeartRate })
        assertEquals(10000, (events.first { it is WearableEvent.Activity } as WearableEvent.Activity).steps)
        waitFor { watch.received.contains(ZeppOsProtocol.CONNECTION to "04") && watch.received.contains(ZeppOsProtocol.HEART_RATE to "0402") }

        // The watch asks to find the phone.
        watch.send(ZeppOsProtocol.FIND_DEVICE, hex("11"))
        waitFor { events.contains(WearableEvent.FindPhone) && watch.received.contains(ZeppOsProtocol.FIND_DEVICE to "1201") }
        session.close()
    }

    @Test
    fun wrongKey() = runTest {
        val watch = Watch(hex("ff".repeat(16)))
        val session = ZeppOsSession(testEnv(scope, FakeLinks(watch.device), keyHex), model("amazfit_gtr_4"))
        try {
            session.open()
            fail("authenticated with a wrong key")
        } catch (e: WearableException) {
            assertEquals(WearableFailure.WRONG_KEY, e.failure)
        }
    }

    @Test
    fun strapWithoutDisplayHasNoNotifications() {
        val strap = ZeppOsDriver(model("amazfit_helio_strap"))
        assertTrue(com.enjoyyourtime.globalfitness.wearable.WearableFeature.NOTIFICATIONS !in strap.features)
    }
}
