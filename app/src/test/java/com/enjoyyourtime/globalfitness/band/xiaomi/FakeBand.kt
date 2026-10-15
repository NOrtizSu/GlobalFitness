package com.enjoyyourtime.globalfitness.band.xiaomi

import com.enjoyyourtime.globalfitness.band.proto.XiaomiProto
import com.google.protobuf.ByteString
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlinx.coroutines.channels.Channel

/**
 * The band's side of the SPP + auth protocol, for tests. Built from the protocol description in
 * Gadgetbridge (band â†’ phone traffic uses the phone's *decryption* key, phone â†’ band its encryption key),
 * so a session that talks to it exercises both directions of every layer.
 */
class FakeBand(
    private val key: ByteArray,
    private val sppVersion: Int,
    private val answerVersion: Boolean = true,
    private val answerAuth: Boolean = true,
    private val watchNonce: ByteArray = range(0x20, 0x30),
) {
    private val toPhone = Channel<ByteArray>(Channel.UNLIMITED)
    private var v2Active = false
    private var bandSequence = 0
    private var expectedPhoneSequence = 0
    private var bandFrameSerial = 0
    private var phoneNonce = ByteArray(0)
    private var material = ByteArray(64)

    /** Decrypted commands from the phone after authentication. */
    val received: MutableList<XiaomiProto.Command> = Collections.synchronizedList(mutableListOf())

    /** Sequence numbers the phone acked (V2). */
    val acksFromPhone: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    /** Raw V2 data frames from the phone, in arrival order. */
    val dataFramesFromPhone: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var companionDevice: XiaomiProto.CompanionDevice? = null

    @Volatile
    var authenticated = false

    /** Whether to ack the phone's V2 data packets. */
    @Volatile
    var ackData = true

    /** Replies to post-auth commands (sent encrypted). */
    @Volatile
    var onCommand: (XiaomiProto.Command) -> List<XiaomiProto.Command> = { emptyList() }

    val link: BandLink = object : BandLink {
        override suspend fun read(): ByteArray = try {
            toPhone.receive()
        } catch (e: Exception) {
            throw IOException("closed", e)
        }

        override suspend fun write(data: ByteArray) = onBytes(data)

        override fun close() {
            toPhone.close()
        }
    }

    /** Pushes an unsolicited, encrypted command to the phone. */
    @Synchronized
    fun push(command: XiaomiProto.Command) = sendCommand(command, encrypted = true)

    /** Sends raw bytes to the phone (e.g. split or garbage-prefixed frames). */
    fun pushRaw(bytes: ByteArray) {
        toPhone.trySend(bytes)
    }

    @Synchronized
    private fun onBytes(data: ByteArray) {
        if (!v2Active) {
            val packet = requireNotNull(SppPacketV1.decode(data)) { "bad V1 frame ${data.toHexString()}" }
            when (packet.channel) {
                SppChannel.Version -> if (answerVersion) {
                    toPhone.trySend(v1Frame(SppPacketV1.CHANNEL_VERSION, SppPacketV1.DATA_TYPE_PLAIN, byteArrayOf(sppVersion.toByte(), 0, 0)))
                    if (sppVersion >= 2) v2Active = true
                }
                SppChannel.ProtobufCommand -> {
                    val payload = if (packet.dataType == SppPacketV1.DATA_TYPE_ENCRYPTED) {
                        val counter = ByteBuffer.wrap(packet.payload, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
                        XiaomiCrypto.aesCcmDecrypt(phoneEncKey(), v1Nonce(phoneEncNonce(), counter), packet.payload.copyOfRange(2, packet.payload.size))
                    } else {
                        packet.payload
                    }
                    handle(XiaomiProto.Command.parseFrom(payload))
                }
                else -> error("unexpected channel ${packet.channel}")
            }
            return
        }
        when (val packet = requireNotNull(SppPacketV2.decode(data)) { "bad V2 frame ${data.toHexString()}" }) {
            is SppPacketV2.SessionConfig -> toPhone.trySend(
                SppPacketV2.encodeFrame(SppPacketV2.TYPE_SESSION_CONFIG, 0, SppPacketV2.sessionConfigPayload(SppPacketV2.SESSION_START_RESPONSE)),
            )
            is SppPacketV2.Data -> {
                dataFramesFromPhone += data
                if (!ackData) return
                // Go-back-N: only the expected packet is accepted, anything else is a duplicate here.
                if (packet.sequenceNumber != expectedPhoneSequence) return
                expectedPhoneSequence = (expectedPhoneSequence + 1) and 0xff
                toPhone.trySend(SppPacketV2.ackFrame(packet.sequenceNumber))
                val payload = if (packet.opCode == SppPacketV2.OPCODE_SEND_ENCRYPTED) {
                    XiaomiCrypto.aesCtr(phoneEncKey(), phoneEncKey(), packet.payload)
                } else {
                    packet.payload
                }
                handle(XiaomiProto.Command.parseFrom(payload))
            }
            is SppPacketV2.Ack -> acksFromPhone += packet.sequenceNumber
            is SppPacketV2.Nack -> error("phone never nacks")
        }
    }

    private fun handle(command: XiaomiProto.Command) {
        if (command.type == XiaomiCommands.TYPE_AUTH && command.subtype == XiaomiCommands.AUTH_NONCE) {
            if (!answerAuth) return
            phoneNonce = command.auth.appVerify.nonce.toByteArray()
            material = XiaomiCrypto.deriveKeyMaterial(key, phoneNonce, watchNonce)
            val hmac = XiaomiCrypto.hmacSha256(phoneDecKey(), watchNonce + phoneNonce)
            sendCommand(
                XiaomiProto.Command.newBuilder()
                    .setType(XiaomiCommands.TYPE_AUTH)
                    .setSubtype(XiaomiCommands.AUTH_NONCE)
                    .setAuth(
                        XiaomiProto.Account.newBuilder().setDeviceVerify(
                            XiaomiProto.Auth.DeviceVerify.newBuilder()
                                .setNonce(ByteString.copyFrom(watchNonce))
                                .setHmac(ByteString.copyFrom(hmac)),
                        ),
                    )
                    .build(),
                encrypted = false,
            )
            return
        }
        if (command.type == XiaomiCommands.TYPE_AUTH && command.subtype == XiaomiCommands.AUTH_CONFIRM) {
            val confirm = command.auth.appConfirm
            val expected = XiaomiCrypto.hmacSha256(phoneEncKey(), phoneNonce + watchNonce)
            check(expected.contentEquals(confirm.encryptedNonces.toByteArray())) { "bad confirm hmac" }
            companionDevice = XiaomiProto.CompanionDevice.parseFrom(
                XiaomiCrypto.aesCcmDecrypt(phoneEncKey(), v1Nonce(phoneEncNonce(), 0), confirm.encryptedDeviceInfo.toByteArray()),
            )
            authenticated = true
            sendCommand(XiaomiCommands.simple(XiaomiCommands.TYPE_AUTH, XiaomiCommands.AUTH_CONFIRM), encrypted = false)
            return
        }
        check(authenticated) { "command before auth" }
        received += command
        onCommand(command).forEach { sendCommand(it, encrypted = true) }
    }

    private fun sendCommand(command: XiaomiProto.Command, encrypted: Boolean) {
        val bytes = command.toByteArray()
        if (!v2Active) {
            val frame = if (encrypted) {
                v1Frame(SppPacketV1.CHANNEL_PROTO_RX, SppPacketV1.DATA_TYPE_ENCRYPTED, XiaomiCrypto.aesCcmEncrypt(phoneDecKey(), v1Nonce(phoneDecNonce(), 0), bytes))
            } else {
                v1Frame(SppPacketV1.CHANNEL_PROTO_RX, SppPacketV1.DATA_TYPE_AUTH, bytes)
            }
            toPhone.trySend(frame)
        } else {
            val opCode = if (encrypted) SppPacketV2.OPCODE_SEND_ENCRYPTED else SppPacketV2.OPCODE_SEND_PLAINTEXT
            val body = if (encrypted) XiaomiCrypto.aesCtr(phoneDecKey(), phoneDecKey(), bytes) else bytes
            val payload = byteArrayOf(1, opCode.toByte()) + body
            toPhone.trySend(SppPacketV2.encodeFrame(SppPacketV2.TYPE_DATA, bandSequence, payload))
            bandSequence = (bandSequence + 1) and 0xff
        }
    }

    private fun v1Frame(rawChannel: Int, dataType: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(11 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(SppPacketV1.PREAMBLE)
            .put(rawChannel.toByte())
            .put(0x80.toByte())
            .putShort((payload.size + 3).toShort())
            .put(SppPacketV1.OPCODE_SEND.toByte())
            .put((bandFrameSerial++ and 0xff).toByte())
            .put(dataType.toByte())
            .put(payload)
            .put(SppPacketV1.EPILOGUE)
            .array()

    private fun v1Nonce(base: ByteArray, counter: Int): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(base).putInt(0).putInt(counter).array()

    private fun phoneDecKey() = material.copyOfRange(0, 16)
    private fun phoneEncKey() = material.copyOfRange(16, 32)
    private fun phoneDecNonce() = material.copyOfRange(32, 36)
    private fun phoneEncNonce() = material.copyOfRange(36, 40)
}
