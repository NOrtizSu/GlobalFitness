package com.enjoyyourtime.globalfitness.wearable.xiaomi

import com.enjoyyourtime.globalfitness.band.proto.XiaomiProto
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKey
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKeyResult
import com.enjoyyourtime.globalfitness.band.xiaomi.SppFrame
import com.enjoyyourtime.globalfitness.band.xiaomi.SppPacketV2
import com.enjoyyourtime.globalfitness.band.xiaomi.SppProtocolV2
import com.enjoyyourtime.globalfitness.band.xiaomi.SppReceiveBuffer
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiAuth
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiCommands
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiCrypto
import com.enjoyyourtime.globalfitness.wearable.FakeGattDevice
import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.UUID

/**
 * The device side of Xiaomi's BLE protocols, written from Gadgetbridge's description (independently
 * of the phone-side classes under test): V1 chunked characteristics (encrypted or with the plaintext
 * user id) and V2 (SPP V2 frames over 005e / 005f).
 */
class FakeXiaomiBleBand(
    private val key: ByteArray,
    private val mode: Mode,
    private val userId: String = "1234567890",
    private val watchNonce: ByteArray = ByteArray(16) { (0x20 + it).toByte() },
) {
    enum class Mode { V1_ENCRYPTED, V1_PLAINTEXT, V2 }

    private val set = if (mode == Mode.V1_PLAINTEXT) XiaomiBleUuids.V1_SETS[1] else XiaomiBleUuids.V1_SETS[0]
    private val readChar: UUID = set.commandRead
    private val writeChar: UUID = set.commandWrite

    val device = FakeGattDevice(
        chars = when (mode) {
            Mode.V2 -> listOf(XiaomiBleUuids.V2_SERVICE to XiaomiBleUuids.V2_RX, XiaomiBleUuids.V2_SERVICE to XiaomiBleUuids.V2_TX)
            else -> listOfNotNull(set.commandRead, set.commandWrite, set.activityData, set.dataUpload).map { set.service to it }
        },
    )

    val received: MutableList<XiaomiProto.Command> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var authenticated = false

    @Volatile
    var onCommand: (XiaomiProto.Command) -> List<XiaomiProto.Command> = { emptyList() }

    /** Counters the phone used for its encrypted V1 messages. */
    val phoneCounters: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    private var phoneNonce = ByteArray(16)
    private var material = ByteArray(64)

    // V1: inbound chunked transfer on the write characteristic, outbound chunks waiting for the start ack.
    private var inboundCount = 0
    private var inboundEncrypted = false
    private val inbound = HashMap<Int, ByteArray>()
    private var outboundChunks: List<ByteArray> = emptyList()

    // V2
    private val rx = SppReceiveBuffer()
    private val v2 = SppProtocolV2(XiaomiAuth((AuthKey.parse("00".repeat(16)) as AuthKeyResult.Valid).key))
    private var bandSequence = 0

    init {
        device.onWrite = { w -> synchronized(this) { onWrite(w.characteristic, w.value) } }
    }

    /** Pushes a command to the phone (encrypted once authenticated on encrypted transports). */
    @Synchronized
    fun push(command: XiaomiProto.Command) = send(command, encrypted = authenticated && mode != Mode.V1_PLAINTEXT)

    private fun onWrite(characteristic: UUID, value: ByteArray) {
        when {
            mode == Mode.V2 && characteristic == XiaomiBleUuids.V2_TX -> onV2Bytes(value)
            characteristic == writeChar -> onPhoneFrame(value)
            characteristic == readChar -> onPhoneAck(value)
        }
    }

    // ---- V1 ----

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun onPhoneFrame(b: ByteArray) {
        val chunk = le16(b, 0)
        if (chunk != 0) {
            inbound[chunk] = b.copyOfRange(2, b.size)
            if (inbound.size == inboundCount) {
                device.notify(writeChar, byteArrayOf(0, 0, 1, 0))
                val all = (1..inboundCount).fold(ByteArray(0)) { acc, i -> acc + inbound.getValue(i) }
                inboundCount = 0
                val plain = if (inboundEncrypted) decryptFromPhone(all.copyOfRange(2, all.size), le16(all, 0)) else all
                handle(XiaomiProto.Command.parseFrom(plain))
            }
            return
        }
        when (b[2].toInt()) {
            0 -> {
                inboundEncrypted = b[3].toInt() == 1
                inboundCount = le16(b, 4)
                inbound.clear()
                device.notify(writeChar, byteArrayOf(0, 0, 1, 1))
            }
            2 -> {
                device.notify(writeChar, byteArrayOf(0, 0, 3, 0))
                val plain = if (b[3].toInt() == 1) decryptFromPhone(b.copyOfRange(6, b.size), le16(b, 4)) else b.copyOfRange(4, b.size)
                handle(XiaomiProto.Command.parseFrom(plain))
            }
        }
    }

    private fun onPhoneAck(b: ByteArray) {
        if (b.size >= 4 && b[2].toInt() == 1 && b[3].toInt() == 1) {
            outboundChunks.forEach { device.notify(readChar, it) }
            outboundChunks = emptyList()
        }
    }

    private fun decryptFromPhone(cipher: ByteArray, counter: Int): ByteArray {
        phoneCounters += counter
        return XiaomiCrypto.aesCcmDecrypt(phoneEncKey(), nonce(phoneEncNonce(), counter), cipher)
    }

    private fun send(command: XiaomiProto.Command, encrypted: Boolean) {
        val bytes = command.toByteArray()
        if (mode == Mode.V2) {
            val opCode = if (encrypted) SppPacketV2.OPCODE_SEND_ENCRYPTED else SppPacketV2.OPCODE_SEND_PLAINTEXT
            val body = if (encrypted) XiaomiCrypto.aesCtr(phoneDecKey(), phoneDecKey(), bytes) else bytes
            device.notify(XiaomiBleUuids.V2_RX, SppPacketV2.encodeFrame(SppPacketV2.TYPE_DATA, bandSequence++ and 0xff, byteArrayOf(1, opCode.toByte()) + body))
            return
        }
        val payload = if (encrypted) XiaomiCrypto.aesCcmEncrypt(phoneDecKey(), nonce(phoneDecNonce(), 0), bytes) else bytes
        if (payload.size + 6 <= MAX_WRITE && mode == Mode.V1_ENCRYPTED) {
            device.notify(readChar, byteArrayOf(0, 0, 2, if (encrypted) 1 else 2) + payload)
            return
        }
        // Chunked: announce, then send the chunks once the phone accepts.
        val chunkSize = MAX_WRITE - 2
        outboundChunks = payload.toList().chunked(chunkSize).mapIndexed { i, part ->
            byteArrayOf(((i + 1) and 0xff).toByte(), ((i + 1) shr 8).toByte()) + part.toByteArray()
        }
        val n = outboundChunks.size
        device.notify(readChar, byteArrayOf(0, 0, 0, if (encrypted) 1 else 0, (n and 0xff).toByte(), (n shr 8).toByte()))
    }

    // ---- V2 ----

    private fun onV2Bytes(value: ByteArray) {
        rx.append(value)
        while (true) {
            val frame = rx.poll(v2) as? SppFrame.V2 ?: break
            when (val p = frame.packet) {
                is SppPacketV2.SessionConfig -> device.notify(
                    XiaomiBleUuids.V2_RX,
                    SppPacketV2.encodeFrame(SppPacketV2.TYPE_SESSION_CONFIG, 0, SppPacketV2.sessionConfigPayload(SppPacketV2.SESSION_START_RESPONSE)),
                )
                is SppPacketV2.Data -> {
                    device.notify(XiaomiBleUuids.V2_RX, SppPacketV2.ackFrame(p.sequenceNumber))
                    val plain = if (p.opCode == SppPacketV2.OPCODE_SEND_ENCRYPTED) XiaomiCrypto.aesCtr(phoneEncKey(), phoneEncKey(), p.payload) else p.payload
                    handle(XiaomiProto.Command.parseFrom(plain))
                }
                else -> Unit
            }
        }
    }

    // ---- commands ----

    private fun handle(command: XiaomiProto.Command) {
        if (command.type == XiaomiCommands.TYPE_AUTH) {
            onAuth(command)
            return
        }
        check(authenticated) { "command before auth" }
        received += command
        onCommand(command).forEach { send(it, encrypted = mode != Mode.V1_PLAINTEXT) }
    }

    private fun onAuth(command: XiaomiProto.Command) {
        when (command.subtype) {
            XiaomiCommands.AUTH_NONCE -> {
                phoneNonce = command.auth.appVerify.nonce.toByteArray()
                material = XiaomiCrypto.deriveKeyMaterial(key, phoneNonce, watchNonce)
                val hmac = XiaomiCrypto.hmacSha256(phoneDecKey(), watchNonce + phoneNonce)
                send(
                    XiaomiProto.Command.newBuilder().setType(1).setSubtype(XiaomiCommands.AUTH_NONCE).setAuth(
                        XiaomiProto.Account.newBuilder().setDeviceVerify(
                            XiaomiProto.Auth.DeviceVerify.newBuilder().setNonce(ByteString.copyFrom(watchNonce)).setHmac(ByteString.copyFrom(hmac)),
                        ),
                    ).build(),
                    encrypted = false,
                )
            }
            XiaomiCommands.AUTH_CONFIRM -> {
                val expected = XiaomiCrypto.hmacSha256(phoneEncKey(), phoneNonce + watchNonce)
                check(expected.contentEquals(command.auth.appConfirm.encryptedNonces.toByteArray())) { "bad confirm" }
                authenticated = true
                send(XiaomiCommands.simple(1, XiaomiCommands.AUTH_CONFIRM), encrypted = false)
            }
            XiaomiCommands.AUTH_SEND_USERID -> {
                val ok = command.auth.userId == userId
                authenticated = ok
                send(
                    XiaomiProto.Command.newBuilder().setType(1).setSubtype(XiaomiCommands.AUTH_SEND_USERID)
                        .setAuth(XiaomiProto.Account.newBuilder().setStatus(if (ok) 1 else 0)).build(),
                    encrypted = false,
                )
            }
        }
    }

    private fun nonce(base: ByteArray, counter: Int): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(base).putInt(0).putInt(counter).array()

    private fun phoneDecKey() = material.copyOfRange(0, 16)
    private fun phoneEncKey() = material.copyOfRange(16, 32)
    private fun phoneDecNonce() = material.copyOfRange(32, 36)
    private fun phoneEncNonce() = material.copyOfRange(36, 40)

    companion object {
        /** MTU 247 - 3. */
        const val MAX_WRITE = 244
    }
}
