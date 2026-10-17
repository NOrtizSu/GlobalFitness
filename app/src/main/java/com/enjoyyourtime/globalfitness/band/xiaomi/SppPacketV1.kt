// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.band.xiaomi

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Logical channels multiplexed over the SPP link (Gadgetbridge XiaomiChannelHandler.Channel). */
enum class SppChannel { Unknown, Version, ProtobufCommand, Activity, Data, Authentication }

/**
 * SPP protocol V1 frame (Gadgetbridge XiaomiSppPacketV1):
 *
 * `ba dc fe | channel | flags | length (LE, payload + 3) | opcode | frame serial | data type | payload | ef`
 *
 * flags: 0x80 = flag (always set when sending), 0x40 = needs response.
 */
class SppPacketV1(
    val channel: SppChannel,
    val flag: Boolean = true,
    val needsResponse: Boolean = false,
    val opCode: Int,
    val frameSerial: Int,
    val dataType: Int,
    val payload: ByteArray,
) {
    /** True when encoding consumes a fresh encryption counter (encrypted protobuf commands). */
    val usesCounter: Boolean get() = dataType == DATA_TYPE_ENCRYPTED && channel == SppChannel.ProtobufCommand

    /**
     * Encrypts as Gadgetbridge does: protobuf commands carry their [counter] (LE short prefix), which is
     * also part of the CCM nonce; other encrypted channels use counter 0.
     */
    fun encode(auth: XiaomiAuth?, counter: Int = 0): ByteArray {
        val body = when {
            usesCounter -> {
                val encrypted = requireNotNull(auth).encryptV1(payload, counter)
                ByteBuffer.allocate(encrypted.size + 2).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(counter.toShort()).put(encrypted).array()
            }
            dataType == DATA_TYPE_ENCRYPTED -> requireNotNull(auth).encryptV1(payload, 0)
            else -> payload
        }
        val buffer = ByteBuffer.allocate(11 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(PREAMBLE)
        buffer.put((rawChannel(channel, tx = true) and 0xf).toByte())
        buffer.put(((if (flag) 0x80 else 0) or (if (needsResponse) 0x40 else 0)).toByte())
        buffer.putShort((body.size + 3).toShort())
        buffer.put((opCode and 0xff).toByte())
        buffer.put((frameSerial and 0xff).toByte())
        buffer.put((dataType and 0xff).toByte())
        buffer.put(body)
        buffer.put(EPILOGUE)
        return buffer.array()
    }

    /** Encrypted payloads are only decrypted once the session keys exist (as in Gadgetbridge). */
    fun decryptedPayload(auth: XiaomiAuth): ByteArray =
        if (dataType == DATA_TYPE_ENCRYPTED && auth.isEncryptionInitialized) auth.decryptV1(payload) else payload

    companion object {
        val PREAMBLE = byteArrayOf(0xba.toByte(), 0xdc.toByte(), 0xfe.toByte())
        val EPILOGUE = byteArrayOf(0xef.toByte())
        const val HEADER_AND_EPILOGUE = 11

        const val CHANNEL_VERSION = 0
        const val CHANNEL_PROTO_RX = 1
        const val CHANNEL_PROTO_TX = 2
        const val CHANNEL_FITNESS = 3
        const val CHANNEL_MASS = 5

        const val DATA_TYPE_PLAIN = 0
        const val DATA_TYPE_ENCRYPTED = 1
        const val DATA_TYPE_AUTH = 2

        const val OPCODE_READ = 0
        const val OPCODE_SEND = 2

        fun dataTypeForChannel(channel: SppChannel): Int = when (channel) {
            SppChannel.Authentication -> DATA_TYPE_AUTH
            SppChannel.ProtobufCommand, SppChannel.Version, SppChannel.Data -> DATA_TYPE_ENCRYPTED
            else -> DATA_TYPE_PLAIN
        }

        fun rawChannel(channel: SppChannel, tx: Boolean): Int = when (channel) {
            SppChannel.Version -> CHANNEL_VERSION
            SppChannel.Authentication, SppChannel.ProtobufCommand -> if (tx) CHANNEL_PROTO_TX else CHANNEL_PROTO_RX
            SppChannel.Activity -> CHANNEL_FITNESS
            SppChannel.Data -> CHANNEL_MASS
            SppChannel.Unknown -> -1
        }

        fun channelOf(raw: Int): SppChannel = when (raw and 0xff) {
            CHANNEL_PROTO_RX, CHANNEL_PROTO_TX -> SppChannel.ProtobufCommand
            CHANNEL_FITNESS -> SppChannel.Activity
            CHANNEL_MASS -> SppChannel.Data
            CHANNEL_VERSION -> SppChannel.Version
            else -> SppChannel.Unknown
        }

        /** Request sent right after connecting: the band answers with its SPP protocol version. */
        fun versionRequest(): ByteArray = SppPacketV1(
            channel = SppChannel.Version,
            needsResponse = true,
            opCode = OPCODE_READ,
            frameSerial = 0,
            dataType = DATA_TYPE_PLAIN,
            payload = ByteArray(0),
        ).encode(null)

        /** Decodes one complete frame starting at offset 0, or null if it is malformed. */
        fun decode(packet: ByteArray): SppPacketV1? {
            if (packet.size < HEADER_AND_EPILOGUE) return null
            val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            val preamble = ByteArray(PREAMBLE.size)
            buffer.get(preamble)
            if (!preamble.contentEquals(PREAMBLE)) return null

            var channel = buffer.get().toInt()
            if (channel and 0xf0 != 0) channel = 0x0f // reserved bits set: unknown channel
            val flags = buffer.get().toInt()
            val payloadLength = (buffer.getShort().toInt() and 0xffff) - 3
            if (payloadLength < 0 || payloadLength + HEADER_AND_EPILOGUE > packet.size) return null
            val opCode = buffer.get().toInt() and 0xff
            val frameSerial = buffer.get().toInt() and 0xff
            val dataType = buffer.get().toInt() and 0xff
            val payload = ByteArray(payloadLength)
            buffer.get(payload)
            val epilogue = ByteArray(EPILOGUE.size)
            buffer.get(epilogue)
            if (!epilogue.contentEquals(EPILOGUE)) return null

            return SppPacketV1(
                channel = channelOf(channel),
                flag = flags and 0x80 != 0,
                needsResponse = flags and 0x40 != 0,
                opCode = opCode,
                frameSerial = frameSerial,
                dataType = dataType,
                payload = payload,
            )
        }
    }
}
