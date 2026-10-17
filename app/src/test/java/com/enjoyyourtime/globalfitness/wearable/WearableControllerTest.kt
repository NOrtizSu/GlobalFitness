package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.band.HeartRateSample
import com.enjoyyourtime.globalfitness.band.PhoneNotification
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandAlarm
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import java.util.Collections
import java.util.TimeZone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generic on-demand connection lifecycle and actions, with a scripted session. */
@OptIn(ExperimentalCoroutinesApi::class)
class WearableControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val timing = WearableTiming(idleTimeoutMs = 60_000, leaveGraceMs = 200, stopTimeoutMs = 300)
    private val device = ConfiguredWearable("dev-1", "test_driver", "AA:BB:CC:DD:EE:FF", "Test watch")

    @After
    fun tearDown() = scope.cancel()

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(15_000) { block() } }

    private class FakeDriver(
        override val needsAuthKey: Boolean = true,
        override val features: Set<WearableFeature> = WearableFeature.entries.toSet(),
    ) : WearableDriver {
        override val id = "test_driver"
        override val displayName = "Test"
        override val brand = "Test"
        override val family = WearableFamily.STANDARD_BLE
        override val transport = WearableTransport.BLE_GATT
        override val authKey = if (needsAuthKey) AuthKeyKind.HEX_32 else AuthKeyKind.NONE
        override fun match(device: WearableSighting): MatchConfidence? = null
        override fun createSession(env: WearableSessionEnv): WearableSession = error("not used")
    }

    /** Records every call; the test plays the device through [push] and [closeWith]. */
    private class FakeSession(private val openFailure: WearableFailure? = null) : WearableSession {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val channel = Channel<WearableEvent>(Channel.UNLIMITED)
        private val closed = CompletableDeferred<WearableException>()
        override val label = "fake"
        override val events: Flow<WearableEvent> = channel.receiveAsFlow()
        override val isOpen: Boolean get() = !closed.isCompleted

        @Volatile
        var openGate: CompletableDeferred<Unit>? = null

        /** Thrown from open() as is, e.g. a driver bug. */
        @Volatile
        var openThrows: Exception? = null

        fun push(e: WearableEvent) {
            channel.trySend(e)
        }

        fun closeWith(failure: WearableFailure) {
            closed.complete(WearableException(failure))
        }

        override suspend fun open() {
            calls += "open"
            openGate?.await()
            openThrows?.let { throw it }
            openFailure?.let { throw WearableException(it) }
        }

        override suspend fun awaitClosed(): WearableException = closed.await()
        override fun close() {
            calls += "close"
            closed.complete(WearableException(WearableFailure.CLOSED))
        }

        override suspend fun refreshStatus(includeInfo: Boolean) {
            calls += "refresh($includeInfo)"
            push(WearableEvent.Battery(55, false))
            push(WearableEvent.Info(WearableDeviceInfo(model = "M", firmware = "1.0")))
        }

        override suspend fun setTime(nowMillis: Long, timeZone: TimeZone, is24Hour: Boolean) {
            calls += "time"
        }

        override suspend fun findDevice(start: Boolean) {
            calls += "find($start)"
        }

        override suspend fun setLiveHeartRate(enabled: Boolean) {
            calls += "live($enabled)"
        }

        override suspend fun sendNotification(notification: BandNotification) {
            calls += "notify(${notification.title})"
        }

        override suspend fun dismissNotification(id: Int, packageName: String) {
            calls += "dismiss($id)"
        }

        override suspend fun loadAlarms(): WearableAlarms {
            calls += "alarms"
            return WearableAlarms(listOf(BandAlarm(1, AlarmSpec(7, 0))), 5)
        }

        @Volatile
        var saveAlarmFailure: WearableFailure? = null

        override suspend fun saveAlarm(id: Int?, spec: AlarmSpec) {
            calls += "save($id)"
            saveAlarmFailure?.let { throw WearableException(it) }
        }

        override suspend fun deleteAlarm(id: Int) {
            calls += "delete($id)"
        }
    }

    private class FakePlatform(var session: FakeSession, var key: String? = "k") : WearablePlatform {
        var precondition: WearableFailure? = null
        var busy = false
        var syncTime = true
        val saved: MutableList<Pair<HeartRateSample, String>> = Collections.synchronizedList(mutableListOf())
        override fun preconditionFailure(transport: WearableTransport) = precondition
        override fun authKey(address: String) = key
        override fun createSession(driver: WearableDriver, device: ConfiguredWearable, authKey: String?): WearableSession = session
        override fun isBusyElsewhere(address: String) = busy
        override fun syncTimeOnConnect(device: ConfiguredWearable) = syncTime
        override fun is24HourFormat() = true
        override fun saveHeartRate(sample: HeartRateSample, address: String, driverId: String) {
            saved += sample to driverId
        }
        override fun beforeConnect() = Unit
        override fun testNotification(nowMillis: Long) = BandNotification(1, "app", "App", "Test", "Body", nowMillis)
    }

    private fun controller(platform: FakePlatform, driver: WearableDriver = FakeDriver(), clock: () -> Long = { System.currentTimeMillis() }) =
        WearableController(scope, {}, platform, device, driver, timing, clock)

    private suspend fun WearableController.awaitState(predicate: (WearableState) -> Boolean): WearableState =
        withTimeout(5_000) { state.first(predicate) }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun connectsAndLoadsStatus() = runTest {
        val session = FakeSession()
        val c = controller(FakePlatform(session))
        c.connect()
        val ready = c.awaitState { it.connection == WearableConnection.READY && it.alarms != null && it.batteryLevel != null && it.info != null }
        assertEquals("fake", ready.protocol)
        assertEquals(55, ready.batteryLevel)
        assertEquals("1.0", ready.info?.firmware)
        assertEquals(5, ready.maxAlarms)
        assertEquals(listOf("open", "time", "refresh(true)", "alarms"), session.calls.take(4))
    }

    @Test
    fun unexpectedErrorWhileOpeningClosesTheSession() = runTest {
        val session = FakeSession().apply { openThrows = IllegalStateException("driver bug") }
        val platform = FakePlatform(session)
        val c = controller(platform)
        c.connect()
        val failed = c.awaitState { it.failure != null }
        assertEquals(WearableFailure.CONNECT_FAILED, failed.failure)
        assertEquals(WearableConnection.DISCONNECTED, failed.connection)
        assertTrue(session.calls.contains("close"))
        assertFalse(session.isOpen)
        // A later attempt starts a fresh connection.
        platform.session = FakeSession()
        c.connect()
        c.awaitState { it.connection == WearableConnection.READY }
    }

    @Test
    fun noKeyPreconditionAndBusy() = runTest {
        val noKey = controller(FakePlatform(FakeSession(), key = null))
        noKey.connect()
        assertEquals(WearableFailure.NO_KEY, noKey.awaitState { it.failure != null }.failure)

        // A key-less driver connects without one.
        val keyless = controller(FakePlatform(FakeSession(), key = null), FakeDriver(needsAuthKey = false))
        keyless.connect()
        keyless.awaitState { it.connection == WearableConnection.READY }

        val off = controller(FakePlatform(FakeSession()).apply { precondition = WearableFailure.BLUETOOTH_OFF })
        off.connect()
        assertEquals(WearableFailure.BLUETOOTH_OFF, off.awaitState { it.failure != null }.failure)

        val busy = controller(FakePlatform(FakeSession(WearableFailure.NO_RESPONSE)).apply { busy = true })
        busy.connect()
        val failed = busy.awaitState { it.failure != null }
        assertEquals(WearableFailure.NO_RESPONSE, failed.failure)
        assertTrue(failed.busyElsewhere)
        assertTrue(failed.releasedForVendorApp)
    }

    @Test
    fun liveHeartRateIsRecordedWithTheDriverId() = runTest {
        val session = FakeSession()
        var now = 1_000_000L
        val platform = FakePlatform(session)
        val c = controller(platform, clock = { now })
        c.connect()
        c.awaitState { it.connection == WearableConnection.READY }
        c.setLiveHeartRate(true)
        c.awaitState { it.liveActive }
        session.push(WearableEvent.HeartRate(0)) // still measuring
        now += 2_000
        session.push(WearableEvent.HeartRate(72, listOf(830)))
        session.push(WearableEvent.Activity(stepsIncrement = 10))
        session.push(WearableEvent.Activity(stepsIncrement = 5))
        val s = c.awaitState { it.live?.heartRate == 72 && it.live?.steps == 15 }
        assertEquals(listOf(830), s.live?.rrIntervalsMs)
        waitUntil { platform.saved.size == 1 }
        assertEquals(72, platform.saved.single().first.bpm)
        assertEquals("test_driver", platform.saved.single().second)
        assertEquals(1, c.heartRate.value.size)
        c.setLiveHeartRate(false)
        c.awaitState { !it.liveActive }
        assertTrue(session.calls.containsAll(listOf("live(true)", "live(false)")))
    }

    @Test
    fun findDeviceFoundItAndFindPhoneMessages() = runTest {
        val session = FakeSession()
        val c = controller(FakePlatform(session))
        val messages = Collections.synchronizedList(mutableListOf<WearableMessage>())
        scope.launch { c.messages.collect { messages += it } }
        c.connect()
        c.awaitState { it.connection == WearableConnection.READY }
        c.findDevice(true)
        c.awaitState { it.finding }
        session.push(WearableEvent.FindStopped)
        c.awaitState { !it.finding }
        session.push(WearableEvent.FindPhone)
        waitUntil { messages.containsAll(listOf(WearableMessage.FoundIt, WearableMessage.FindPhoneRequested)) }
        c.syncTime()
        c.sendTestNotification()
        waitUntil { messages.containsAll(listOf(WearableMessage.TimeSynced, WearableMessage.TestNotificationSent)) }
        assertTrue(session.calls.contains("notify(Test)"))
    }

    @Test
    fun alarmsAndUnsupportedActions() = runTest {
        val session = FakeSession()
        val driver = FakeDriver(features = setOf(WearableFeature.BATTERY, WearableFeature.ALARMS))
        val c = controller(FakePlatform(session).apply { syncTime = false }, driver)
        val messages = Collections.synchronizedList(mutableListOf<WearableMessage>())
        scope.launch { c.messages.collect { messages += it } }
        c.connect()
        c.awaitState { it.alarms != null }
        assertFalse(session.calls.contains("time")) // TIME_SYNC not supported by this driver
        c.saveAlarm(null, AlarmSpec(6, 0))
        c.deleteAlarm(1)
        waitUntil { session.calls.containsAll(listOf("save(null)", "delete(1)")) && session.calls.count { it == "alarms" } >= 3 }
        // Notifications are not forwarded to a driver without NOTIFICATIONS.
        c.onPhoneNotification(PhoneNotification("k", "com.x", "X", "T", "B", 0), forwardingEnabled = true, allowedPackages = setOf("com.x"))
        delay(100)
        assertFalse(session.calls.any { it.startsWith("notify") })
    }

    @Test
    fun fullAlarmSlotsAreReportedNotSwallowed() = runTest {
        val session = FakeSession()
        val c = controller(FakePlatform(session))
        val messages = Collections.synchronizedList(mutableListOf<WearableMessage>())
        scope.launch { c.messages.collect { messages += it } }
        c.connect()
        c.awaitState { it.alarms != null }
        session.saveAlarmFailure = WearableFailure.ALARM_SLOTS_FULL
        c.saveAlarm(null, AlarmSpec(6, 0))
        waitUntil { messages.contains(WearableMessage.ActionFailed(WearableAction.SAVE_ALARM, WearableFailure.ALARM_SLOTS_FULL)) }
        // A device that does not answer is still tolerated: the list is read back.
        session.saveAlarmFailure = WearableFailure.REQUEST_TIMEOUT
        val loads = session.calls.count { it == "alarms" }
        c.saveAlarm(null, AlarmSpec(7, 0))
        waitUntil { session.calls.count { it == "alarms" } > loads }
    }

    @Test
    fun forwardsNotificationsWhileConnected() = runTest {
        val session = FakeSession()
        val c = controller(FakePlatform(session))
        c.connect()
        c.awaitState { it.connection == WearableConnection.READY }
        val n = PhoneNotification("key-1", "com.x", "X", "Hello", "World", 0)
        c.onPhoneNotification(n, forwardingEnabled = true, allowedPackages = setOf("com.x"))
        c.onPhoneNotification(n.copy(key = "key-2", packageName = "com.other"), forwardingEnabled = true, allowedPackages = setOf("com.x"))
        c.onPhoneNotificationRemoved("key-1")
        waitUntil { session.calls.any { it.startsWith("dismiss") } }
        assertEquals(1, session.calls.count { it == "notify(Hello)" })
    }

    @Test
    fun disconnectStopsLiveStreamAndReleases() = runTest {
        val session = FakeSession()
        val c = controller(FakePlatform(session))
        c.connect()
        c.awaitState { it.connection == WearableConnection.READY }
        c.setLiveHeartRate(true)
        c.awaitState { it.liveActive }
        c.disconnect()
        val released = c.awaitState { it.connection == WearableConnection.DISCONNECTED && it.releasedForVendorApp }
        assertEquals(null, released.failure)
        assertTrue(session.calls.indexOf("live(false)") in 0 until session.calls.indexOf("close"))
    }

    @Test
    fun leavingTheScreenDisconnectsAfterGrace() = runTest {
        val session = FakeSession()
        val c = controller(FakePlatform(session))
        c.onUiVisible()
        c.connect()
        c.awaitState { it.connection == WearableConnection.READY }
        c.onUiHidden()
        c.awaitState { it.connection == WearableConnection.DISCONNECTED }
        assertTrue(session.calls.contains("close"))
    }

    @Test
    fun linkLossIsReported() = runTest {
        val session = FakeSession()
        val messages = Collections.synchronizedList(mutableListOf<WearableMessage>())
        val c = controller(FakePlatform(session))
        scope.launch { c.messages.collect { messages += it } }
        c.connect()
        c.awaitState { it.connection == WearableConnection.READY && it.batteryLevel != null }
        session.closeWith(WearableFailure.LINK_LOST)
        val lost = c.awaitState { it.connection == WearableConnection.DISCONNECTED }
        assertEquals(WearableFailure.LINK_LOST, lost.failure)
        assertEquals(55, lost.batteryLevel) // last known values are kept
        c.findDevice(true)
        waitUntil { messages.contains(WearableMessage.ConnectFirst) }
    }

    @Test
    fun idleTimeoutDisconnects() = runTest {
        val session = FakeSession()
        val c = WearableController(scope, {}, FakePlatform(session), device, FakeDriver(), WearableTiming(idleTimeoutMs = 300, leaveGraceMs = 100, stopTimeoutMs = 100))
        c.connect()
        c.awaitState { it.connection == WearableConnection.READY }
        c.awaitState { it.connection == WearableConnection.DISCONNECTED }
        assertTrue(session.calls.contains("close"))
    }
}
