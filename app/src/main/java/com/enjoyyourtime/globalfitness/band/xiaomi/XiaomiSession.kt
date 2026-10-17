// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.band.xiaomi

import com.enjoyyourtime.globalfitness.band.proto.XiaomiProto
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Byte stream to the band (an RFCOMM socket on a phone, a fake in tests). */
interface BandLink {
    /** Next chunk of bytes; throws an IOException when the link is gone. */
    suspend fun read(): ByteArray

    suspend fun write(data: ByteArray)

    /** Idempotent; unblocks a pending [read]. */
    fun close()
}

/**
 * Why a connection attempt or session ended. [logText] is the English diagnostics text; the band
 * screen shows a translated explanation for each value.
 */
enum class BandFailure(val logText: String) {
    BLUETOOTH_UNAVAILABLE("This phone has no Bluetooth"),
    BLUETOOTH_OFF("Bluetooth is off"),
    MISSING_PERMISSION("Nearby devices permission missing"),
    NO_KEY("No authentication key saved"),
    NO_DEVICE("No band selected"),
    NOT_FOUND("Band not found"),
    CONNECT_FAILED("Could not open the connection"),
    NO_RESPONSE("The band does not respond"),
    WRONG_KEY("Wrong authentication key"),
    AUTH_REJECTED("The band rejected the authentication"),
    LINK_LOST("Connection lost"),
    REQUEST_TIMEOUT("The band did not answer in time"),
    CLOSED("Disconnected"),
}

class BandException(val failure: BandFailure, message: String = failure.logText, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * One authenticated connection to a Xiaomi band over SPP (port of Gadgetbridge XiaomiSppSupport,
 * XiaomiSppProtocolV1/V2 and XiaomiAuthService).
 *
 * All state lives on a single-threaded dispatcher, so incoming frames, writes and timers form one
 * serialized queue; writes additionally hold [writeMutex] so frames reach the socket in the order their
 * counters / sequence numbers were assigned.
 *
 * Handshake: V1 version request → (V2 only) session config → encrypted auth. Replies reuse the request's
 * type/subtype; [request] correlates them, everything else is published on [commands].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XiaomiSession(
    private val link: BandLink,
    private val auth: XiaomiAuth,
    private val companion: CompanionInfo,
    parentScope: CoroutineScope,
    private val log: (String) -> Unit,
    private val timeouts: Timeouts = Timeouts(),
) {
    data class Timeouts(
        /** Gadgetbridge VersionTimeoutRunnable: fall back to V1 after 5 s. */
        val version: Long = 5_000,
        val sessionConfig: Long = 8_000,
        val auth: Long = 10_000,
        val request: Long = 6_000,
        val resend: Long = SppProtocolV2.RESEND_TIMEOUT_MS,
    )

    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + serial)
    private val writeMutex = Mutex()

    private val rx = SppReceiveBuffer()
    private var protocol: SppProtocol = SppProtocolV1(auth)
    private var versionHandled = false
    private val versionReply = CompletableDeferred<ByteArray>()
    private val sessionStarted = CompletableDeferred<Unit>()
    private val authReplies = Channel<XiaomiProto.Command>(Channel.UNLIMITED)
    private val pending = HashMap<Long, ArrayDeque<CompletableDeferred<XiaomiProto.Command>>>()

    private var resendJob: Job? = null
    private var timeoutsWithoutAck = 0

    @Volatile
    private var closeReason: BandException? = null

    @Volatile
    private var closing = false

    private val closedSignal = CompletableDeferred<BandException>()

    private val unsolicited = Channel<XiaomiProto.Command>(Channel.UNLIMITED)

    /** Set right before auth step 2 is written: only then does a 1/27 from the band mean "authenticated". */
    private var confirmSent = false

    /**
     * Commands from the band that did not answer a pending [request] (pushes, events...). Buffered from
     * the moment the session starts, so nothing sent during or right after the handshake is lost; meant
     * for a single collector.
     */
    val commands: Flow<XiaomiProto.Command> = unsolicited.receiveAsFlow()

    /** "SPP v1" or "SPP v2" once the version is known. */
    val protocolLabel: String get() = protocol.label

    val isOpen: Boolean get() = closeReason == null

    /** Suspends until the session ends and returns why. */
    suspend fun awaitClosed(): BandException = closedSignal.await()

    /** Connects the protocol layers and authenticates. Throws [BandException]; the session is then closed. */
    suspend fun handshake() = withContext(serial) {
        startReader()

        log("Requesting the SPP protocol version")
        writeRaw(SppPacketV1.versionRequest())
        val version = withTimeoutOrNull(timeouts.version) { versionReply.await() }
        if (version == null) {
            versionHandled = true
            log("No version reply: using SPP v1")
        }

        val current = protocol
        if (current is SppProtocolV2) {
            log("Starting SPP v2 session")
            writeRaw(current.sessionConfigFrame())
            withTimeoutOrNull(timeouts.sessionConfig) { sessionStarted.await() }
                ?: throw fail(BandException(BandFailure.NO_RESPONSE))
        }

        log("Authenticating (step 1/2)")
        writeCommand(auth.startHandshake())
        val nonceReply = awaitAuthReply()
        if (nonceReply.subtype == XiaomiCommands.AUTH_NONCE && nonceReply.auth.hasDeviceVerify()) {
            when (val result = auth.handleWatchNonce(nonceReply.auth.deviceVerify, companion)) {
                XiaomiAuth.WatchNonceResult.WrongKey -> throw fail(BandException(BandFailure.WRONG_KEY))
                is XiaomiAuth.WatchNonceResult.Confirm -> {
                    log("Authenticating (step 2/2)")
                    confirmSent = true
                    writeCommand(result.command)
                }
            }
            // The reader already switched to encrypted mode when the 1/27 arrived (see onCommandBytes).
            val confirm = awaitAuthReply()
            if (confirm.subtype != XiaomiCommands.AUTH_CONFIRM) throw fail(BandException(BandFailure.AUTH_REJECTED))
            log("Authenticated: encrypted communication (${protocol.label})")
        } else if (nonceReply.subtype == XiaomiCommands.AUTH_SEND_USERID && nonceReply.auth.status == 1) {
            auth.onAuthenticated(encrypted = false)
            log("Authenticated: unencrypted communication")
        } else {
            log("Unexpected authentication reply ${nonceReply.type}/${nonceReply.subtype}")
            throw fail(BandException(BandFailure.AUTH_REJECTED))
        }
    }

    /** Fire-and-forget command. */
    suspend fun send(command: XiaomiProto.Command) = withContext(serial) {
        ensureOpen()
        writeCommand(command)
    }

    /** Sends [command] and waits for the band's command with the same type and [responseSubtype]. */
    suspend fun request(
        command: XiaomiProto.Command,
        responseSubtype: Int = command.subtype,
        timeoutMs: Long = timeouts.request,
    ): XiaomiProto.Command = withContext(serial) {
        ensureOpen()
        val key = key(command.type, responseSubtype)
        val reply = CompletableDeferred<XiaomiProto.Command>()
        pending.getOrPut(key) { ArrayDeque() }.addLast(reply)
        try {
            writeCommand(command)
            withTimeout(timeoutMs) { reply.await() }
        } catch (e: TimeoutCancellationException) {
            throw BandException(BandFailure.REQUEST_TIMEOUT, cause = e)
        } finally {
            pending[key]?.let { queue ->
                queue.remove(reply)
                if (queue.isEmpty()) pending.remove(key)
            }
        }
    }

    /** Ends the session (user request, idle timeout...). Safe to call from any thread, repeatedly. */
    fun close() {
        closing = true
        link.close()
        if (job.isActive) scope.launch { fail(BandException(BandFailure.CLOSED)) }
    }

    // ---- receive path ----

    private fun startReader() {
        scope.launch {
            try {
                while (isActive) {
                    val bytes = link.read()
                    rx.append(bytes)
                    while (true) {
                        val frame = rx.poll(protocol) ?: break
                        onFrame(frame)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(
                    if (closing) BandException(BandFailure.CLOSED) else BandException(BandFailure.LINK_LOST, cause = e),
                )
            }
        }
    }

    private fun onFrame(frame: SppFrame) {
        when (frame) {
            is SppFrame.V1 -> {
                val payload = decryptOrNull { frame.packet.decryptedPayload(auth) } ?: return
                onChannelPayload(frame.packet.channel, payload)
            }
            is SppFrame.V2 -> onV2Packet(frame.packet)
            SppFrame.Corrupt -> log("SPP v2 frame dropped (bad checksum)")
        }
    }

    private fun onV2Packet(packet: SppPacketV2) {
        val v2 = protocol as? SppProtocolV2 ?: return
        when (packet) {
            is SppPacketV2.SessionConfig -> {
                log("SPP v2 session accepted by the band")
                sessionStarted.complete(Unit)
            }
            is SppPacketV2.Data -> {
                val payload = decryptOrNull { packet.decryptedPayload(auth) }
                if (payload != null) onChannelPayload(packet.channel, payload)
                val ack = SppPacketV2.ackFrame(packet.sequenceNumber)
                scope.launch { writeRawOrFail(ack) }
            }
            is SppPacketV2.Ack -> if (v2.onAck(packet.sequenceNumber)) onAckProgress()
            is SppPacketV2.Nack -> {
                val result = v2.onNack(packet.sequenceNumber)
                if (result.progress) onAckProgress()
                if (result.resend.isNotEmpty()) {
                    log("The band asks to resend from packet ${packet.sequenceNumber}")
                    resend(result.resend)
                }
            }
        }
    }

    private inline fun decryptOrNull(block: () -> ByteArray): ByteArray? = try {
        block()
    } catch (e: GeneralSecurityException) {
        log("Packet dropped: could not decrypt")
        null
    } catch (e: IllegalArgumentException) {
        log("Packet dropped: invalid encrypted data")
        null
    }

    private fun onChannelPayload(channel: SppChannel, payload: ByteArray) {
        when (channel) {
            SppChannel.Version -> onVersion(payload)
            SppChannel.ProtobufCommand, SppChannel.Authentication -> onCommandBytes(payload)
            else -> log("Packet on channel $channel ignored (${payload.size} bytes)")
        }
    }

    private fun onVersion(payload: ByteArray) {
        if (versionHandled) return
        versionHandled = true
        log("Band SPP version: ${XiaomiCrypto.toHex(payload).ifEmpty { "(empty)" }}")
        // Gadgetbridge XiaomiSppSupport.handleVersionPacket: version 2 or higher switches to SPP V2.
        if (payload.isNotEmpty() && payload[0] >= 2) protocol = SppProtocolV2(auth)
        versionReply.complete(payload)
    }

    private fun onCommandBytes(bytes: ByteArray) {
        val command = try {
            XiaomiProto.Command.parseFrom(bytes)
        } catch (e: Exception) {
            log("Unreadable band command (${bytes.size} bytes)")
            return
        }
        if (command.type != XiaomiCommands.TYPE_HEALTH || command.subtype != XiaomiCommands.HEALTH_REALTIME_STATS_EVENT) {
            log("← ${command.type}/${command.subtype}")
        }
        if (command.type == XiaomiCommands.TYPE_AUTH) {
            // As Gadgetbridge's XiaomiAuthService.handleCommand: encryption is on as soon as 1/27 is parsed,
            // before the next frame of the same read is decrypted (on V1 it would otherwise be dropped).
            if (command.subtype == XiaomiCommands.AUTH_CONFIRM && confirmSent) auth.onAuthenticated(encrypted = true)
            authReplies.trySend(command)
            return
        }
        val key = key(command.type, command.subtype)
        val waiter = pending[key]?.removeFirstOrNull()
        if (waiter != null) {
            if (pending[key].isNullOrEmpty()) pending.remove(key)
            waiter.complete(command)
        } else {
            unsolicited.trySend(command)
        }
    }

    private suspend fun awaitAuthReply(): XiaomiProto.Command =
        withTimeoutOrNull(timeouts.auth) { authReplies.receive() } ?: throw fail(BandException(BandFailure.NO_RESPONSE))

    // ---- send path ----

    private suspend fun writeCommand(command: XiaomiProto.Command) {
        if (command.type != XiaomiCommands.TYPE_AUTH) log("→ ${command.type}/${command.subtype}")
        val channel = if (command.type == XiaomiCommands.TYPE_AUTH) SppChannel.Authentication else SppChannel.ProtobufCommand
        val bytes = command.toByteArray()
        writeMutex.withLock {
            when (val p = protocol) {
                is SppProtocolV1 -> linkWrite(p.encode(channel, bytes))
                is SppProtocolV2 -> {
                    val payload = p.dataPayload(channel, bytes)
                    linkWrite(p.nextDataFrame(payload))
                    armResendTimeout()
                }
            }
        }
    }

    private suspend fun writeRaw(frame: ByteArray) {
        writeMutex.withLock { linkWrite(frame) }
    }

    private suspend fun writeRawOrFail(frame: ByteArray) {
        try {
            writeRaw(frame)
        } catch (e: BandException) {
            // Already closed by linkWrite.
        }
    }

    private suspend fun linkWrite(frame: ByteArray) {
        ensureOpen()
        try {
            link.write(frame)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw fail(if (closing) BandException(BandFailure.CLOSED) else BandException(BandFailure.LINK_LOST, cause = e))
        }
    }

    private fun resend(frames: List<ByteArray>) {
        scope.launch {
            try {
                writeMutex.withLock { for (frame in frames) linkWrite(frame) }
                armResendTimeout()
            } catch (e: BandException) {
                // Session already closed.
            }
        }
    }

    private fun armResendTimeout() {
        if (resendJob?.isActive == true) return
        resendJob = scope.launch {
            delay(timeouts.resend)
            resendJob = null
            onResendTimeout()
        }
    }

    private fun onAckProgress() {
        timeoutsWithoutAck = 0
        resendJob?.cancel()
        resendJob = null
        val v2 = protocol as? SppProtocolV2 ?: return
        if (!v2.window.isEmpty()) armResendTimeout()
    }

    private fun onResendTimeout() {
        val v2 = protocol as? SppProtocolV2 ?: return
        if (v2.window.isEmpty()) {
            timeoutsWithoutAck = 0
            return
        }
        timeoutsWithoutAck++
        if (timeoutsWithoutAck >= SppProtocolV2.MAX_TIMEOUTS_WITHOUT_ACK) {
            log("No acknowledgement after $timeoutsWithoutAck retries: closing the connection")
            fail(BandException(BandFailure.LINK_LOST, "The band stopped acknowledging the data sent"))
            return
        }
        log("No acknowledgement of packet ${v2.window.oldestUnacked()}: resending")
        resend(v2.window.unackedFrames())
    }

    // ---- lifecycle ----

    private fun ensureOpen() {
        closeReason?.let { throw it }
    }

    /** Closes everything once; returns the (first) reason so callers can `throw fail(...)`. */
    private fun fail(reason: BandException): BandException {
        closeReason?.let { return it }
        closeReason = reason
        if (reason.failure != BandFailure.CLOSED) log("Session ended: ${reason.message}")
        link.close()
        versionReply.completeExceptionally(reason)
        sessionStarted.completeExceptionally(reason)
        authReplies.close(reason)
        unsolicited.close()
        pending.values.flatten().forEach { it.completeExceptionally(reason) }
        pending.clear()
        (protocol as? SppProtocolV2)?.window?.clear()
        closedSignal.complete(reason)
        scope.cancel()
        return reason
    }

    private fun key(type: Int, subtype: Int): Long = (type.toLong() shl 32) or (subtype.toLong() and 0xffffffffL)
}
