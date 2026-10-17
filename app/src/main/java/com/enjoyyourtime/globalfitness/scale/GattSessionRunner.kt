package com.enjoyyourtime.globalfitness.scale

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** One notification / indication (or read result) received from the peripheral. */
class GattNotification(val characteristic: UUID, val value: ByteArray)

/** A connection failure; [notice] is what the user sees. */
class GattException(val notice: ScaleNotice) : Exception(notice.kind.name)

/**
 * Minimal coroutine view of a GATT connection. Implemented on Android by [GattSession]; a fake
 * implementation drives the runner in unit tests. Every suspending call is one GATT operation
 * that completes when its callback arrives (implementations serialize them).
 */
interface GattLink {
    val address: String
    val isConnected: StateFlow<Boolean>

    /** Hot stream of notifications/indications. */
    val notifications: Flow<GattNotification>

    suspend fun connect()
    suspend fun discoverServices()

    /** Returns the negotiated MTU, or null when the request failed. */
    suspend fun requestMtu(mtu: Int): Int?
    fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean

    /** The discovered GATT tree (empty before service discovery). */
    fun characteristics(): List<GattCharInfo> = emptyList()

    /** Why the link failed on its own (e.g. a wedged operation), null when it did not. */
    val failure: ScaleNotice? get() = null
    suspend fun enableNotifications(service: UUID, characteristic: UUID): Boolean
    suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean): Boolean
    suspend fun read(service: UUID, characteristic: UUID): ByteArray?

    /** Disconnects and releases the connection. Idempotent. */
    fun close()
}

/**
 * Runs one GATT session: connect, discover, hand the link to the driver's [GattProtocol] through a
 * serialized operation queue, forward notifications, and end when the protocol disconnects, the
 * link drops, nothing happens for [idleTimeoutMs] or [maxSessionMs] elapses.
 *
 * All protocol callbacks run sequentially on the flow's coroutine.
 */
class GattSessionRunner(
    private val driver: GattScaleDriver,
    private val link: GattLink,
    private val env: DriverEnv,
    private val adv: AdvertisementInfo?,
    private val connectTimeoutMs: Long = 20_000L,
    private val idleTimeoutMs: Long = 45_000L,
    private val maxSessionMs: Long = 240_000L,
    private val requestedMtu: Int = 185,
) {
    private sealed interface Op {
        class Notify(val service: UUID, val characteristic: UUID) : Op
        class Write(val service: UUID, val characteristic: UUID, val payload: ByteArray, val withResponse: Boolean) : Op
        class Read(val service: UUID, val characteristic: UUID) : Op
        data object Disconnect : Op
    }

    private sealed interface Event {
        class Notify(val n: GattNotification) : Event
        class Timer(val action: () -> Unit) : Event
        class End(val reason: ScaleNotice?) : Event
    }

    fun run(): Flow<ScaleEvent> = channelFlow {
        send(ScaleEvent.Status(SessionStatus.CONNECTING))
        try {
            withTimeout(connectTimeoutMs) {
                link.connect()
                link.discoverServices()
            }
        } catch (e: CancellationException) {
            link.close()
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                send(ScaleEvent.Status(SessionStatus.FAILED, ScaleNotice(ScaleNotice.Kind.CONNECT_TIMEOUT)))
                return@channelFlow
            }
            throw e
        } catch (e: Exception) {
            link.close()
            val notice = when {
                e is GattException -> e.notice
                e.message != null -> ScaleNotice(ScaleNotice.Kind.CONNECT_FAILED_DETAIL, listOf(e.message!!))
                else -> ScaleNotice(ScaleNotice.Kind.CONNECT_FAILED)
            }
            send(ScaleEvent.Status(SessionStatus.FAILED, notice))
            return@channelFlow
        }
        val ops = Channel<Op>(Channel.UNLIMITED)
        val events = Channel<Event>(Channel.UNLIMITED)
        var measuring = false
        var published = 0
        val timers = ArrayList<Job>()

        val io = object : GattIo {
            override fun setNotifyOn(service: UUID, characteristic: UUID) {
                ops.trySend(Op.Notify(service, characteristic))
            }

            override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
                ops.trySend(Op.Write(service, characteristic, payload.copyOf(), withResponse))
            }

            override fun read(service: UUID, characteristic: UUID) {
                ops.trySend(Op.Read(service, characteristic))
            }

            override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
                link.hasCharacteristic(service, characteristic)

            override fun characteristics(): List<GattCharInfo> = link.characteristics()

            override fun disconnect() {
                ops.trySend(Op.Disconnect)
            }

            override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
                val job = launch {
                    delay(delayMs)
                    events.send(Event.Timer(action))
                }
                timers += job
                return Cancellable { job.cancel() }
            }

            override fun publish(reading: ScaleReading) {
                published++
                trySend(ScaleEvent.Reading(reading))
            }

            override fun live(weightKg: Double) {
                if (!measuring) {
                    measuring = true
                    trySend(ScaleEvent.Status(SessionStatus.MEASURING))
                }
                trySend(ScaleEvent.Live(weightKg))
            }

            override fun message(notice: ScaleNotice) {
                trySend(ScaleEvent.Message(notice))
            }
        }

        var notificationsJob: Job? = null
        var linkWatchJob: Job? = null
        var opsJob: Job? = null
        var protocol: GattProtocol? = null
        val pacing = driver.pacing
        fun startJobs() {
            notificationsJob = launch {
                link.notifications.collect { events.send(Event.Notify(it)) }
            }
            linkWatchJob = launch {
                link.isConnected.first { !it }
                events.send(Event.End(ScaleNotice(ScaleNotice.Kind.SCALE_DISCONNECTED)))
            }
            opsJob = launch {
            for (op in ops) {
                try {
                    when (op) {
                        is Op.Notify -> link.enableNotifications(op.service, op.characteristic)
                        is Op.Write -> link.write(op.service, op.characteristic, op.payload, op.withResponse)
                        is Op.Read -> link.read(op.service, op.characteristic)?.let {
                            events.send(Event.Notify(GattNotification(op.characteristic, it)))
                        }
                        Op.Disconnect -> {
                            events.send(Event.End(null))
                            break
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failed operation is not fatal: the protocol decides (like openScale).
                }
                delay(
                    when (op) {
                        is Op.Notify -> pacing.afterNotifyMs
                        is Op.Write -> if (op.withResponse) pacing.afterWriteMs else pacing.afterWriteNoResponseMs
                        is Op.Read -> pacing.afterReadMs
                        Op.Disconnect -> 0L
                    },
                )
            }
            }
        }

        val startedAt = env.elapsedClock()
        var lastActivity = startedAt
        var endReason: ScaleNotice? = null

        fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trySend(ScaleEvent.Message(ScaleNotice(ScaleNotice.Kind.PROTOCOL_ERROR, listOf(e.message ?: e.javaClass.simpleName))))
            }
        }

        try {
            runCatching { if (requestedMtu > 23) link.requestMtu(requestedMtu) }
                .onFailure { if (it is CancellationException) throw it }
            send(ScaleEvent.Status(SessionStatus.CONNECTED))
            val p = driver.createProtocol(adv)
            p.attach(io, env)
            protocol = p
            startJobs()
            guarded { p.onConnected() }
            while (true) {
                val now = env.elapsedClock()
                val idleLeft = idleTimeoutMs - (now - lastActivity)
                val sessionLeft = maxSessionMs - (now - startedAt)
                val wait = minOf(idleLeft, sessionLeft)
                if (wait <= 0) {
                    endReason = if (published == 0) NO_DATA else null
                    break
                }
                val event = withTimeoutOrNull(wait) { events.receive() } ?: continue
                when (event) {
                    is Event.Notify -> {
                        lastActivity = env.elapsedClock()
                        guarded { p.onNotification(event.n.characteristic, event.n.value) }
                    }
                    is Event.Timer -> guarded { event.action() }
                    is Event.End -> {
                        endReason = event.reason
                        break
                    }
                }
            }
        } finally {
            // Drivers may still publish here (flush on disconnect): counted below.
            protocol?.let { guarded { it.onDisconnected() } }
            timers.forEach { it.cancel() }
            notificationsJob?.cancel()
            linkWatchJob?.cancel()
            opsJob?.cancel()
            ops.close()
            link.close()
        }
        // Connected but nothing received is not an error (the user stepped off, or the scale had
        // nothing new); only a link that failed on its own is.
        val linkFailure = link.failure
        val status = if (linkFailure != null && published == 0) SessionStatus.FAILED else SessionStatus.DONE
        val detail = when {
            linkFailure != null && published == 0 -> linkFailure
            published > 0 -> null
            endReason != null -> NO_DATA
            else -> null
        }
        send(ScaleEvent.Status(status, detail))
    }.buffer(Channel.UNLIMITED)

    companion object {
        val NO_DATA = ScaleNotice(ScaleNotice.Kind.NO_NEW_DATA)
    }
}

/** Entry point matching the driver API: runs a full session over [link]. */
fun GattScaleDriver.runSession(link: GattLink, env: DriverEnv, adv: AdvertisementInfo?): Flow<ScaleEvent> =
    GattSessionRunner(this, link, env, adv).run()
