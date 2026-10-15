// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.band.xiaomi

/** A complete frame taken off the receive buffer. */
sealed interface SppFrame {
    class V1(val packet: SppPacketV1) : SppFrame
    class V2(val packet: SppPacketV2) : SppFrame

    /** A complete V2 frame that failed to decode (bad checksum...): dropped, the band resends it. */
    data object Corrupt : SppFrame
}

class SppParseResult(val status: Status, val size: Int = 0, val frame: SppFrame? = null) {
    enum class Status { Invalid, Incomplete, Complete }
}

/** Framing of one SPP protocol version (Gadgetbridge AbstractXiaomiSppProtocol). */
sealed interface SppProtocol {
    val label: String

    fun parse(buffer: ByteArray): SppParseResult

    /** Offset of the next possible frame start after a garbage byte, -1 if none. */
    fun findNextPacketOffset(buffer: ByteArray): Int
}

/** SPP V1 (Gadgetbridge XiaomiSppProtocolV1): no acks, CCM-encrypted protobuf payloads. */
class SppProtocolV1(private val auth: XiaomiAuth) : SppProtocol {
    override val label = "SPP v1"
    private var frameCounter = 0
    private var encryptionCounter = 0

    override fun findNextPacketOffset(buffer: ByteArray): Int {
        for (i in 1 until buffer.size) if (buffer[i] == SppPacketV1.PREAMBLE[0]) return i
        return -1
    }

    override fun parse(buffer: ByteArray): SppParseResult {
        if (buffer.size < SppPacketV1.HEADER_AND_EPILOGUE) return SppParseResult(SppParseResult.Status.Incomplete)
        for (i in SppPacketV1.PREAMBLE.indices) {
            if (buffer[i] != SppPacketV1.PREAMBLE[i]) return SppParseResult(SppParseResult.Status.Invalid)
        }
        val payloadSize = (buffer[5].toInt() and 0xff) or ((buffer[6].toInt() and 0xff) shl 8)
        val packetSize = payloadSize + 8 // the size field includes the 3-byte payload header
        if (buffer.size < packetSize) return SppParseResult(SppParseResult.Status.Incomplete)
        val packet = SppPacketV1.decode(buffer) ?: return SppParseResult(SppParseResult.Status.Invalid)
        return SppParseResult(SppParseResult.Status.Complete, packetSize, SppFrame.V1(packet))
    }

    fun encode(channel: SppChannel, payload: ByteArray): ByteArray {
        val packet = SppPacketV1(
            channel = channel,
            opCode = SppPacketV1.OPCODE_SEND,
            frameSerial = frameCounter++,
            dataType = SppPacketV1.dataTypeForChannel(channel),
            payload = payload,
        )
        val counter = if (packet.usesCounter) ++encryptionCounter else 0
        return packet.encode(auth, counter)
    }
}

/**
 * SPP V2 (Gadgetbridge XiaomiSppProtocolV2): data packets are delivered go-back-N. The band accepts them
 * strictly in sequence and acks cumulatively; a NACK names the packet it expects next. Every received
 * data packet is acked. Timers (resend after [RESEND_TIMEOUT_MS], give up after
 * [MAX_TIMEOUTS_WITHOUT_ACK]) are driven by the session.
 */
class SppProtocolV2(private val auth: XiaomiAuth) : SppProtocol {
    override val label = "SPP v2"
    val window = SppV2SendWindow()

    override fun findNextPacketOffset(buffer: ByteArray): Int {
        for (i in 1 until buffer.size) if (buffer[i] == SppPacketV2.PREAMBLE[0]) return i
        return -1
    }

    override fun parse(buffer: ByteArray): SppParseResult {
        if (buffer.size < SppPacketV2.HEADER_SIZE) return SppParseResult(SppParseResult.Status.Incomplete)
        for (i in SppPacketV2.PREAMBLE.indices) {
            if (buffer[i] != SppPacketV2.PREAMBLE[i]) return SppParseResult(SppParseResult.Status.Invalid)
        }
        val packetSize = SppPacketV2.HEADER_SIZE + ((buffer[4].toInt() and 0xff) or ((buffer[5].toInt() and 0xff) shl 8))
        if (buffer.size < packetSize) return SppParseResult(SppParseResult.Status.Incomplete)
        val packet = SppPacketV2.decode(buffer.copyOf(packetSize))
        return SppParseResult(SppParseResult.Status.Complete, packetSize, packet?.let { SppFrame.V2(it) } ?: SppFrame.Corrupt)
    }

    fun sessionConfigFrame(): ByteArray = SppPacketV2.encodeFrame(
        SppPacketV2.TYPE_SESSION_CONFIG,
        0,
        SppPacketV2.sessionConfigPayload(SppPacketV2.SESSION_START_REQUEST),
    )

    /** Encrypts now; the sequence number is only taken in [nextDataFrame], right before writing. */
    fun dataPayload(channel: SppChannel, payload: ByteArray): ByteArray = SppPacketV2.dataPayload(channel, payload, auth)

    fun nextDataFrame(dataPayload: ByteArray): ByteArray {
        val sequenceNumber = window.nextSequenceNumber()
        val frame = SppPacketV2.encodeFrame(SppPacketV2.TYPE_DATA, sequenceNumber, dataPayload)
        window.onSent(sequenceNumber, frame)
        return frame
    }

    /** @return true when the ack released at least one pending frame. */
    fun onAck(sequenceNumber: Int): Boolean = window.onAck(sequenceNumber) > 0

    class NackResult(val progress: Boolean, val resend: List<ByteArray>)

    /** Everything before the expected packet counts as acked; resend from it if it is still pending. */
    fun onNack(expected: Int): NackResult {
        val progress = onAck((expected - 1) and 0xff)
        val resend = if (window.oldestUnacked() == expected) window.unackedFrames() else emptyList()
        return NackResult(progress, resend)
    }

    companion object {
        /** Same value as the send timeout announced in the session config. */
        const val RESEND_TIMEOUT_MS = 10_000L

        /** Resend timeouts in a row without any ack before the link is considered dead. */
        const val MAX_TIMEOUTS_WITHOUT_ACK = 3
    }
}

/** Reassembles frames from the byte stream (Gadgetbridge XiaomiSppSupport.processBuffer). */
class SppReceiveBuffer {
    private var buffer = ByteArray(0)

    val size: Int get() = buffer.size

    fun append(bytes: ByteArray) {
        buffer += bytes
    }

    fun clear() {
        buffer = ByteArray(0)
    }

    /**
     * Takes the next complete frame off the front, parsed with [protocol] (which may change between
     * calls, e.g. after the version answer). Garbage is skipped up to the next possible preamble.
     * Returns null when more bytes are needed.
     */
    fun poll(protocol: SppProtocol): SppFrame? {
        while (buffer.isNotEmpty()) {
            val result = protocol.parse(buffer)
            when (result.status) {
                SppParseResult.Status.Incomplete -> return null
                SppParseResult.Status.Invalid -> {
                    val next = protocol.findNextPacketOffset(buffer)
                    skip(if (next < 0) buffer.size else next)
                }
                SppParseResult.Status.Complete -> {
                    skip(result.size)
                    return result.frame
                }
            }
        }
        return null
    }

    private fun skip(count: Int) {
        buffer = if (count >= buffer.size) ByteArray(0) else buffer.copyOfRange(count, buffer.size)
    }
}
