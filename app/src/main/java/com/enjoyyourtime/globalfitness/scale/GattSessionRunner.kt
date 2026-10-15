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

class GattException(message: String) : Exception(message)

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
        class End(val reason: String?) : Event
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
                send(ScaleEvent.Status(SessionStatus.FAILED, "No se pudo conectar (tiempo agotado)"))
                return@channelFlow
            }
            throw e
        } catch (e: Exception) {
            link.close()
            send(ScaleEvent.Status(SessionStatus.FAILED, e.message ?: "No se pudo conectar"))
            return@channelFlow
        }
        runCatching { if (requestedMtu > 23) link.requestMtu(requestedMtu) }
        send(ScaleEvent.Status(SessionStatus.CONNECTED))

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

            override fun message(text: String) {
                trySend(ScaleEvent.Message(text))
            }
        }

        val protocol = driver.createProtocol(adv)
        protocol.attach(io, env)

        val notificationsJob = launch {
            link.notifications.collect { events.send(Event.Notify(it)) }
        }
        val linkWatchJob = launch {
            link.isConnected.first { !it }
            events.send(Event.End("La báscula se desconectó"))
        }
        val pacing = driver.pacing
        val opsJob = launch {
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

        val startedAt = env.elapsedClock()
        var lastActivity = startedAt
        var endReason: String? = null
        var failed = false

        fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trySend(ScaleEvent.Message("Error del protocolo: ${e.message ?: e.javaClass.simpleName}"))
            }
        }

        try {
            guarded { protocol.onConnected() }
            while (true) {
                val now = env.elapsedClock()
                val idleLeft = idleTimeoutMs - (now - lastActivity)
                val sessionLeft = maxSessionMs - (now - startedAt)
                val wait = minOf(idleLeft, sessionLeft)
                if (wait <= 0) {
                    endReason = if (published == 0) "Sin datos de la báscula (tiempo agotado)" else null
                    failed = published == 0
                    break
                }
                val event = withTimeoutOrNull(wait) { events.receive() } ?: continue
                when (event) {
                    is Event.Notify -> {
                        lastActivity = env.elapsedClock()
                        guarded { protocol.onNotification(event.n.characteristic, event.n.value) }
                    }
                    is Event.Timer -> guarded { event.action() }
                    is Event.End -> {
                        endReason = event.reason
                        failed = event.reason != null && published == 0
                        break
                    }
                }
            }
        } finally {
            guarded { protocol.onDisconnected() }
            timers.forEach { it.cancel() }
            notificationsJob.cancel()
            linkWatchJob.cancel()
            opsJob.cancel()
            ops.close()
            link.close()
        }
        send(
            ScaleEvent.Status(
                if (failed) SessionStatus.FAILED else SessionStatus.DONE,
                endReason,
            ),
        )
    }.buffer(Channel.UNLIMITED)
}

/** Entry point matching the driver API: runs a full session over [link]. */
fun GattScaleDriver.runSession(link: GattLink, env: DriverEnv, adv: AdvertisementInfo?): Flow<ScaleEvent> =
    GattSessionRunner(this, link, env, adv).run()
