package com.enjoyyourtime.globalfitness.ble

import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.data.MeasurementStore
import com.enjoyyourtime.globalfitness.domain.MeasurementRecorder
import com.enjoyyourtime.globalfitness.domain.RecordOutcome
import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.scale.ConfiguredScale
import com.enjoyyourtime.globalfitness.scale.GattException
import com.enjoyyourtime.globalfitness.scale.GattLink
import com.enjoyyourtime.globalfitness.scale.GattNotification
import com.enjoyyourtime.globalfitness.scale.GattProtocol
import com.enjoyyourtime.globalfitness.scale.GattScaleDriver
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.ScaleCapabilities
import com.enjoyyourtime.globalfitness.scale.ScaleConfigStore
import com.enjoyyourtime.globalfitness.scale.ScaleNotice
import com.enjoyyourtime.globalfitness.scale.ScaleRegistry
import com.enjoyyourtime.globalfitness.scale.ScaleSample
import com.enjoyyourtime.globalfitness.scale.ScaleTestSupport
import com.enjoyyourtime.globalfitness.scale.SessionStatus
import com.enjoyyourtime.globalfitness.scale.drivers.AllDrivers
import com.enjoyyourtime.globalfitness.scale.drivers.MiScale2Driver
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * End to end over fakes: advertisement stream -> detector -> auto-configuration -> driver session
 * -> recorder -> store, plus the GATT reconnection policy (backoff after failures, cooldown and
 * "scale went quiet" after a session).
 */
class ScaleMonitorIntegrationTest {
    private val wall = AtomicLong(ScaleTestSupport.epochMillis(2026, 10, 15, 8, 30, 40))
    private val elapsed = AtomicLong(5_000_000L)
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val running = ArrayList<Running>()

    @After
    fun tearDown() {
        running.forEach { it.stop() }
        executor.shutdownNow()
    }

    private class FakeConfig(initial: List<ConfiguredScale> = emptyList(), legacyDone: Boolean = true) : ScaleConfigStore {
        override val configuredScales = MutableStateFlow(initial)
        override val dismissedAutoConfig = MutableStateFlow<Set<String>>(emptySet())

        @Volatile
        override var legacyScaleCheckDone: Boolean = legacyDone

        @Synchronized
        override fun updateConfiguredScales(transform: (List<ConfiguredScale>) -> List<ConfiguredScale>) {
            configuredScales.value = transform(configuredScales.value)
        }

        override fun dismissAutoConfig(address: String) {
            dismissedAutoConfig.value = dismissedAutoConfig.value + address.uppercase()
        }

        override fun markLegacyScaleCheckDone() {
            legacyScaleCheckDone = true
        }
    }

    private class FakeStore(initial: List<Measurement> = emptyList()) : MeasurementStore {
        override val measurements = MutableStateFlow(initial)
        private var nextId = initial.size + 1L

        override suspend fun awaitLoaded() = Unit

        override suspend fun insertMeasurement(m: Measurement): Measurement? {
            if (m.scaleKey != null && measurements.value.any { it.scaleKey == m.scaleKey }) return null
            val stored = m.copy(id = nextId++)
            measurements.value = measurements.value + stored
            return stored
        }

        override suspend fun updateScaleData(updated: Measurement): Measurement? {
            if (measurements.value.none { it.id == updated.id }) return null
            measurements.value = measurements.value.map { if (it.id == updated.id) updated else it }
            return updated
        }

        override fun isDeletedScaleKey(key: String): Boolean = false
    }

    /** One monitor instance (an app process); [stop] simulates the app being killed. */
    private inner class Running(
        registry: ScaleRegistry,
        config: ScaleConfigStore,
        store: MeasurementStore,
        linkFactory: (String) -> GattLink = { throw AssertionError("unexpected GATT connection") },
    ) {
        private val job = SupervisorJob()
        private val adverts = Channel<AdvertisementInfo>(Channel.UNLIMITED)
        val outcomes = CopyOnWriteArrayList<RecordOutcome>()
        val monitor = ScaleMonitor(
            advertisements = adverts.receiveAsFlow(),
            registry = registry,
            settings = config,
            recorder = MeasurementRecorder(store),
            contextProvider = { ScaleTestSupport.ctx() },
            linkFactory = linkFactory,
            scope = CoroutineScope(job + dispatcher),
            wallClock = { wall.get() },
            elapsedClock = { elapsed.get() },
            dispatcher = dispatcher,
        )

        init {
            CoroutineScope(job + dispatcher).launch { monitor.outcomes.collect { outcomes += it } }
            running += this
            settle()
        }

        /** Advances both clocks by [afterMs], then delivers the advertisement and lets it be processed. */
        fun send(afterMs: Long, build: () -> AdvertisementInfo) {
            elapsed.addAndGet(afterMs)
            wall.addAndGet(afterMs)
            adverts.trySend(build())
            settle()
        }

        fun stop() = job.cancel()
    }

    private fun settle() = runBlocking {
        repeat(6) {
            delay(15)
            withContext(dispatcher) { yield() }
        }
    }

    private fun await(what: String, timeoutMs: Long = 6_000, condition: () -> Boolean) = runBlocking {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("Timed out waiting for: $what")
            delay(10)
        }
    }

    private fun mi(ctrl1: Int, raw: Int, impedance: Int = 0) = ScaleTestSupport.adv(
        name = "MIBFS",
        serviceData = mapOf(Bytes.uuid16(0x181B) to TestFrames.bytes(ctrl1, raw, impedance)),
        address = MI_ADDRESS,
        receivedAt = wall.get(),
    )

    private fun weighing(r: Running, impedance: Int = 500) {
        r.send(400) { mi(TestFrames.LIVE, 10_000) }
        r.send(400) { mi(TestFrames.STABLE, 14_400) }
        r.send(400) { mi(TestFrames.STABLE_IMPEDANCE, 14_400, impedance) }
        r.send(400) { mi(TestFrames.STABLE_IMPEDANCE_LOAD_REMOVED, 14_400, impedance) }
    }

    @Test
    fun miScale2IsAutoConfiguredStoredOnceAndNotDuplicatedAfterARestart() {
        val config = FakeConfig()
        val store = FakeStore()
        val first = Running(AllDrivers.registry(), config, store)

        // Two adverts 3.5 s apart: an unambiguous, settled Mi Scale 2 is set up automatically.
        first.send(0) { mi(TestFrames.LIVE, 9_000) }
        assertTrue(config.configuredScales.value.isEmpty())
        first.send(3_500) { mi(TestFrames.LIVE, 9_500) }
        await("auto-configuration") { config.configuredScales.value.size == 1 }
        val cs = config.configuredScales.value.single()
        assertEquals(MiScale2Driver.ID, cs.driverId)
        assertEquals(MI_ADDRESS, cs.address)
        assertTrue(cs.autoConfigured)

        weighing(first)
        await("stored weighing") { store.measurements.value.size == 1 }
        val m = store.measurements.value.single()
        assertEquals(72.0, m.weightKg, 1e-9)
        assertEquals(500, m.impedance)
        assertEquals(MiScale2Driver.ID, m.deviceDriver)
        assertEquals(MI_ADDRESS, m.deviceAddress)
        assertEquals("2026-10-15 08:30:45|14400", m.scaleKey)
        assertEquals(MeasurementSource.SCALE, m.source)
        await("saved card") { first.monitor.live.value is LiveScale.Saved }
        assertTrue(first.outcomes.single() is RecordOutcome.Saved)

        // App killed and restarted while the scale still broadcasts the finished weighing.
        first.stop()
        val second = Running(AllDrivers.registry(), config, store)
        repeat(3) { second.send(1_000) { mi(TestFrames.STABLE_IMPEDANCE_LOAD_REMOVED, 14_400, 500) } }
        await("adverts routed to the configured scale") { second.monitor.lastSeen.value.containsKey(cs.id) }
        settle()
        assertEquals(1, store.measurements.value.size)
        assertTrue(second.outcomes.none { it is RecordOutcome.Saved || it is RecordOutcome.Upgraded })
        assertEquals(1, config.configuredScales.value.size)
    }

    @Test
    fun weightOnlyCommitIsUpgradedWithTheLateImpedance() {
        val cs = ConfiguredScale("mi", MiScale2Driver.ID, MI_ADDRESS, "Mi")
        val store = FakeStore()
        val r = Running(AllDrivers.registry(), FakeConfig(listOf(cs)), store)

        r.send(0) { mi(TestFrames.STABLE, 14_400) }
        assertTrue(store.measurements.value.isEmpty())
        // No impedance arrives in time: the 1 s tick commits the weight alone.
        elapsed.addAndGet(MeasurementAssembler.WEIGHT_ONLY_TIMEOUT_MS + 500)
        wall.addAndGet(MeasurementAssembler.WEIGHT_ONLY_TIMEOUT_MS + 500)
        await("weight-only commit") { store.measurements.value.size == 1 }
        assertNull(store.measurements.value.single().impedance)

        r.send(2_000) { mi(TestFrames.STABLE_IMPEDANCE, 14_400, 480) }
        await("impedance upgrade") { store.measurements.value.single().impedance == 480 }
        assertEquals(1, store.measurements.value.size)
        assertTrue(r.outcomes.any { it is RecordOutcome.Upgraded })
    }

    @Test
    fun legacyMiScaleRowsCreateAnUnboundConfigThatBindsOnTheFirstWeighing() {
        val legacy = Measurement(1, null, wall.get() - 86_400_000L, 71.5, 480, "2026-10-14 08:00:00|14300", MeasurementSource.SCALE)
        val config = FakeConfig(legacyDone = false)
        val store = FakeStore(listOf(legacy))
        val r = Running(AllDrivers.registry(), config, store)
        await("legacy config") { config.configuredScales.value.size == 1 }
        assertNull(config.configuredScales.value.single().address)
        assertTrue(config.legacyScaleCheckDone)

        weighing(r)
        await("stored weighing") { store.measurements.value.size == 2 }
        await("address bound") { config.configuredScales.value.single().address == MI_ADDRESS }
    }

    // ---- GATT reconnection policy ---------------------------------------------------------------

    private class ToyGattDriver : GattScaleDriver() {
        override val id = "toy_gatt"
        override val displayName = "Toy GATT"
        override val brand = "Test"
        override val capabilities = ScaleCapabilities()

        override fun match(adv: AdvertisementInfo): MatchConfidence? = if (adv.name == TOY_NAME) MatchConfidence.HIGH else null

        override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
            override fun onConnected() {
                publish(ScaleSample().apply { weightKg = 72.3f })
                requestDisconnect()
            }
        }
    }

    private class FakeLink(override val address: String, private val failConnect: Boolean) : GattLink {
        private val connected = MutableStateFlow(false)
        override val isConnected: StateFlow<Boolean> = connected
        override val notifications: Flow<GattNotification> = emptyFlow()

        override suspend fun connect() {
            if (failConnect) throw GattException(ScaleNotice(ScaleNotice.Kind.CONNECT_FAILED))
            connected.value = true
        }

        override suspend fun discoverServices() = Unit
        override suspend fun requestMtu(mtu: Int): Int = mtu
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = true
        override suspend fun enableNotifications(service: UUID, characteristic: UUID) = true
        override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean) = true
        override suspend fun read(service: UUID, characteristic: UUID): ByteArray? = null

        override fun close() {
            connected.value = false
        }
    }

    private fun toyAdv() = ScaleTestSupport.adv(name = TOY_NAME, address = TOY_ADDRESS, receivedAt = wall.get())

    private fun toyMonitor(failConnect: Boolean, attempts: AtomicInteger, store: FakeStore = FakeStore()): Running {
        val cs = ConfiguredScale(TOY_ID, ToyGattDriver().id, TOY_ADDRESS, "Toy")
        return Running(ScaleRegistry(listOf(ToyGattDriver())), FakeConfig(listOf(cs)), store) { address ->
            attempts.incrementAndGet()
            FakeLink(address, failConnect)
        }
    }

    @Test
    fun failedConnectionsBackOffExponentially() {
        val attempts = AtomicInteger()
        val r = toyMonitor(failConnect = true, attempts = attempts)

        r.send(0) { toyAdv() }
        await("first attempt failed") { attempts.get() == 1 && r.monitor.sessions.value[TOY_ID]?.status == SessionStatus.FAILED }
        settle()
        r.send(5_000) { toyAdv() } // within the 10 s backoff
        assertEquals(1, attempts.get())
        r.send(6_000) { toyAdv() } // 11 s after the failure
        await("second attempt") { attempts.get() == 2 }
        settle()
        r.send(15_000) { toyAdv() } // backoff doubled to 20 s
        assertEquals(2, attempts.get())
        r.send(6_000) { toyAdv() } // 21 s after the second failure
        await("third attempt") { attempts.get() == 3 }
        assertEquals(SessionStatus.FAILED, r.monitor.sessions.value[TOY_ID]?.status)
    }

    @Test
    fun finishedSessionWaitsForCooldownAndForTheScaleToGoQuiet() {
        val attempts = AtomicInteger()
        val store = FakeStore()
        val r = toyMonitor(failConnect = false, attempts = attempts, store = store)

        r.send(0) { toyAdv() }
        await("reading stored") { store.measurements.value.size == 1 }
        await("session saved") { r.monitor.sessions.value[TOY_ID]?.status == SessionStatus.SAVED }
        settle()
        assertEquals(72.3, store.measurements.value.single().weightKg, 1e-4)
        assertEquals(TOY_ADDRESS, store.measurements.value.single().deviceAddress)

        r.send(10_000) { toyAdv() } // cooldown (45 s)
        r.send(20_000) { toyAdv() } // still cooldown
        r.send(10_000) { toyAdv() } // still cooldown
        r.send(10_000) { toyAdv() } // cooldown over, but the scale kept advertising: still awake from this weighing
        assertEquals(1, attempts.get())
        r.send(25_000) { toyAdv() } // advertising stopped for 25 s: a new weighing woke it up
        await("reconnection") { attempts.get() == 2 }
        // Same weight within minutes: the recorder deduplicates it (the session ends DONE, not SAVED).
        await("second session finished") { r.monitor.sessions.value[TOY_ID]?.status == SessionStatus.DONE }
        settle()
        assertEquals(1, store.measurements.value.size)
        assertTrue(r.outcomes.last() is RecordOutcome.Duplicate)
    }

    private companion object {
        const val MI_ADDRESS = "C8:47:8C:11:22:33"
        const val TOY_ADDRESS = "AA:BB:CC:DD:EE:01"
        const val TOY_NAME = "TOYSCALE"
        const val TOY_ID = "toy"
    }
}
