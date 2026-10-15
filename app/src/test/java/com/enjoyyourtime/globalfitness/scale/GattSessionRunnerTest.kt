package com.enjoyyourtime.globalfitness.scale

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class GattSessionRunnerTest {
    private val service = Bytes.uuid16(0xFFF0)
    private val notifyChar = Bytes.uuid16(0xFFF1)
    private val writeChar = Bytes.uuid16(0xFFF2)

    /** Toy protocol: subscribe, send "start", publish the weight it gets back, then disconnect. */
    private inner class ToyDriver(private val disconnectAfterReading: Boolean = true) : GattScaleDriver() {
        override val id = "toy"
        override val displayName = "Toy"
        override val brand = "Test"
        override val capabilities = ScaleCapabilities()
        override fun match(adv: AdvertisementInfo): MatchConfidence? = null
        override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
            override fun onConnected() {
                setNotifyOn(service, notifyChar)
                writeTo(service, writeChar, byteArrayOf(0x01))
            }

            override fun onNotification(characteristic: UUID, data: ByteArray) {
                val kg = Bytes.fromUnsignedInt16Le(data, 0) / 100f
                live(kg)
                publish(ScaleSample().apply { weightKg = kg })
                if (disconnectAfterReading) requestDisconnect()
            }
        }
    }

    private class FakeLink(private val failConnect: Boolean = false) : GattLink {
        override val address = ScaleTestSupport.ADDRESS
        private val connected = MutableStateFlow(false)
        override val isConnected: StateFlow<Boolean> = connected
        private val flow = MutableSharedFlow<GattNotification>(replay = 16)
        override val notifications: Flow<GattNotification> = flow
        val log = mutableListOf<String>()
        var closed = false

        override suspend fun connect() {
            if (failConnect) throw GattException("boom")
            connected.value = true
            log += "connect"
        }

        override suspend fun discoverServices() {
            log += "discover"
        }

        override suspend fun requestMtu(mtu: Int): Int = mtu
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = true
        override suspend fun enableNotifications(service: UUID, characteristic: UUID): Boolean {
            log += "notify"
            return true
        }

        override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean): Boolean {
            log += "write " + Bytes.toHex(value)
            // The scale answers with 72.30 kg.
            flow.emit(GattNotification(characteristic, Bytes.toInt16Le(7230)))
            return true
        }

        override suspend fun read(service: UUID, characteristic: UUID): ByteArray? = null

        override fun close() {
            closed = true
            connected.value = false
        }
    }

    @Test
    fun runsQueuedOperationsInOrderAndPublishes() = runBlocking {
        val link = FakeLink()
        val env = ScaleTestSupport.env("toy")
        val events = ToyDriver().runSession(link, env, null).toList()

        assertEquals(listOf("connect", "discover", "notify", "write 01"), link.log)
        assertTrue(link.closed)
        val statuses = events.filterIsInstance<ScaleEvent.Status>().map { it.status }
        assertEquals(listOf(SessionStatus.CONNECTING, SessionStatus.CONNECTED, SessionStatus.MEASURING, SessionStatus.DONE), statuses)
        val reading = events.filterIsInstance<ScaleEvent.Reading>().single().reading
        assertEquals(72.3, reading.weightKg, 1e-4)
        assertEquals("toy", reading.driverId)
        assertEquals(ScaleTestSupport.ADDRESS, reading.deviceAddress)
    }

    @Test
    fun connectionFailureIsReportedAndLinkClosed() = runBlocking {
        val link = FakeLink(failConnect = true)
        val events = ToyDriver().runSession(link, ScaleTestSupport.env("toy"), null).toList()
        val last = events.last() as ScaleEvent.Status
        assertEquals(SessionStatus.FAILED, last.status)
        assertEquals("boom", last.detail)
        assertTrue(link.closed)
    }

    @Test
    fun idleSessionTimesOut() = runBlocking {
        val link = FakeLink()
        val runner = GattSessionRunner(
            driver = ToyDriver(disconnectAfterReading = false),
            link = link,
            env = ScaleTestSupport.env("toy").let { DriverEnv(it.ctx, it.store, it.driverId, it.address) },
            adv = null,
            idleTimeoutMs = 300,
        )
        val events = runner.run().toList()
        // It got its reading, then nothing more happened: the session ends normally.
        assertEquals(1, events.filterIsInstance<ScaleEvent.Reading>().size)
        assertEquals(SessionStatus.DONE, (events.last() as ScaleEvent.Status).status)
        assertTrue(link.closed)
    }
}
