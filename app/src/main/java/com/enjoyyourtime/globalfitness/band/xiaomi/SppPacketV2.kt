// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.band.xiaomi

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SPP protocol V2 frames (Gadgetbridge XiaomiSppPacketV2):
 *
 * `a5 a5 | type (low nibble) | sequence | payload length (LE) | CRC-16/ARC of payload (LE) | payload`
 */
sealed class SppPacketV2(val sequenceNumber: Int) {
    /** Cumulative ack: everything up to and including [sequenceNumber] arrived. */
    class Ack(sequenceNumber: Int) : SppPacketV2(sequenceNumber)

    /** Out-of-order data was dropped; [sequenceNumber] is the packet the receiver expects next. */
    class Nack(sequenceNumber: Int) : SppPacketV2(sequenceNumber)

    class SessionConfig(sequenceNumber: Int, val opCode: Int) : SppPacketV2(sequenceNumber)

    /** [payload] is still encrypted when [opCode] is [OPCODE_SEND_ENCRYPTED]. */
    class Data(sequenceNumber: Int, val channel: SppChannel, val opCode: Int, val payload: ByteArray) :
        SppPacketV2(sequenceNumber) {
        fun decryptedPayload(auth: XiaomiAuth): ByteArray =
            if (opCode == OPCODE_SEND_ENCRYPTED) auth.decryptV2(payload) else payload
    }

    companion object {
        val PREAMBLE = byteArrayOf(0xa5.toByte(), 0xa5.toByte())
        const val HEADER_SIZE = 8

        const val TYPE_NACK = 0
        const val TYPE_ACK = 1
        const val TYPE_SESSION_CONFIG = 2
        const val TYPE_DATA = 3

        const val SESSION_START_REQUEST = 1
        const val SESSION_START_RESPONSE = 2

        private const val CHANNEL_PROTOBUF = 1 // encrypted after authentication
        private const val CHANNEL_DATA = 2 // not encrypted
        private const val CHANNEL_ACTIVITY = 5 // encrypted

        const val OPCODE_SEND_PLAINTEXT = 1
        const val OPCODE_SEND_ENCRYPTED = 2

        fun opCodeForChannel(channel: SppChannel): Int = when (channel) {
            SppChannel.Authentication, SppChannel.Data -> OPCODE_SEND_PLAINTEXT
            SppChannel.ProtobufCommand, SppChannel.Activity -> OPCODE_SEND_ENCRYPTED
            else -> -1
        }

        private fun rawChannel(channel: SppChannel): Int = when (channel) {
            SppChannel.Authentication, SppChannel.ProtobufCommand -> CHANNEL_PROTOBUF
            SppChannel.Data -> CHANNEL_DATA
            SppChannel.Activity -> CHANNEL_ACTIVITY
            else -> -1
        }

        private fun channelFromRaw(raw: Int): SppChannel = when (raw) {
            CHANNEL_PROTOBUF -> SppChannel.ProtobufCommand
            CHANNEL_ACTIVITY -> SppChannel.Activity
            CHANNEL_DATA -> SppChannel.Data
            else -> SppChannel.Unknown
        }

        /**
         * Session config payload copied by Gadgetbridge from the official app: version 1.0.0, max frame
         * size 0xfc00, TX window 32 frames, send timeout 10000 ms.
         */
        fun sessionConfigPayload(opCode: Int): ByteArray = byteArrayOf(
            opCode.toByte(),
            1, 0x03, 0x00, 0x01, 0x00, 0x00,
            2, 0x02, 0x00, 0x00, 0xfc.toByte(),
            3, 0x02, 0x00, 0x20, 0x00,
            4, 0x02, 0x00, 0x10, 0x27,
        )

        /** Data packet payload: channel, opcode, then the payload, encrypted for encrypted opcodes. */
        fun dataPayload(channel: SppChannel, payload: ByteArray, auth: XiaomiAuth): ByteArray {
            val opCode = opCodeForChannel(channel)
            val body = if (opCode == OPCODE_SEND_ENCRYPTED) auth.encryptV2(payload) else payload
            return byteArrayOf((rawChannel(channel) and 0xf).toByte(), (opCode and 0xff).toByte()) + body
        }

        /** The checksum covers the payload only, so a payload can be encrypted before its sequence number is known. */
        fun encodeFrame(type: Int, sequenceNumber: Int, payload: ByteArray): ByteArray =
            ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
                .put(PREAMBLE)
                .put((type and 0xf).toByte())
                .put((sequenceNumber and 0xff).toByte())
                .putShort(payload.size.toShort())
                .putShort(crc16Arc(payload).toShort())
                .put(payload)
                .array()

        fun ackFrame(sequenceNumber: Int): ByteArray = encodeFrame(TYPE_ACK, sequenceNumber, ByteArray(0))

        /** CRC-16/ARC (poly 0x8005 reflected, init 0), written exactly as Gadgetbridge computes it. */
        fun crc16Arc(payload: ByteArray): Int {
            var crc = 0
            for (b in payload) {
                for (j in 0 until 8) {
                    crc = crc shl 1
                    if (((crc shr 16) and 1) xor ((b.toInt() shr j) and 1) == 1) crc = crc xor 0x8005
                }
            }
            return Integer.reverse(crc) ushr 16
        }

        /** Decodes one complete frame starting at offset 0; null if malformed or the checksum fails. */
        fun decode(frame: ByteArray): SppPacketV2? {
            if (frame.size < HEADER_SIZE) return null
            val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            val preamble = ByteArray(PREAMBLE.size)
            buffer.get(preamble)
            if (!preamble.contentEquals(PREAMBLE)) return null

            val type = buffer.get().toInt() and 0xf
            val sequenceNumber = buffer.get().toInt() and 0xff
            val length = buffer.getShort().toInt() and 0xffff
            val checksum = buffer.getShort().toInt() and 0xffff
            if (buffer.remaining() < length) return null
            val payload = ByteArray(length)
            buffer.get(payload)
            if (crc16Arc(payload) != checksum) return null

            return when (type) {
                TYPE_SESSION_CONFIG ->
                    if (payload.isEmpty()) null else SessionConfig(sequenceNumber, payload[0].toInt() and 0xff)
                TYPE_DATA ->
                    if (payload.size < 2) {
                        null
                    } else {
                        Data(
                            sequenceNumber,
                            channelFromRaw(payload[0].toInt() and 0xf),
                            payload[1].toInt() and 0xff,
                            payload.copyOfRange(2, payload.size),
                        )
                    }
                TYPE_ACK -> Ack(sequenceNumber)
                TYPE_NACK -> Nack(sequenceNumber)
                else -> null
            }
        }
    }
}

/**
 * Sequence numbers and unacked frames of the data packets sent over SPP V2 (Gadgetbridge
 * XiaomiSppV2SendWindow). Sequence numbers are 8 bit and wrap; acks are cumulative.
 */
class SppV2SendWindow {
    private class Frame(val sequenceNumber: Int, val bytes: ByteArray)

    private val unacked = ArrayDeque<Frame>()
    private var next = 0

    @Synchronized
    fun nextSequenceNumber(): Int {
        val sequenceNumber = next
        next = (next + 1) and 0xff
        return sequenceNumber
    }

    /** Tracks a written frame until acked. Past [MAX_TRACKED_FRAMES] the oldest one is forgotten. */
    @Synchronized
    fun onSent(sequenceNumber: Int, frame: ByteArray) {
        unacked.addLast(Frame(sequenceNumber, frame))
        while (unacked.size > MAX_TRACKED_FRAMES) unacked.removeFirst()
    }

    /** @return number of frames released, 0 if [sequenceNumber] matches no unacked frame. */
    @Synchronized
    fun onAck(sequenceNumber: Int): Int {
        if (unacked.none { it.sequenceNumber == sequenceNumber }) return 0
        var released = 0
        do {
            val frame = unacked.removeFirst()
            released++
        } while (frame.sequenceNumber != sequenceNumber)
        return released
    }

    /** Sequence number of the oldest unacked frame, or -1 when everything was acked. */
    @Synchronized
    fun oldestUnacked(): Int = unacked.firstOrNull()?.sequenceNumber ?: -1

    @Synchronized
    fun isEmpty(): Boolean = unacked.isEmpty()

    @Synchronized
    fun unackedFrames(): List<ByteArray> = unacked.map { it.bytes }

    @Synchronized
    fun clear() {
        unacked.clear()
        next = 0
    }

    companion object {
        /** Half the sequence space, so a sequence number identifies at most one tracked frame. */
        const val MAX_TRACKED_FRAMES = 128
    }
}
