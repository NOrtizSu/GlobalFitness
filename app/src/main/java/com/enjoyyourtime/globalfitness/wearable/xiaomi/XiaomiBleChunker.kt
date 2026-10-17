// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.wearable.xiaomi

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Encryption of the BLE V1 characteristics once authenticated (backed by XiaomiAuth's CCM). */
interface XiaomiBleCipher {
    /** True once the session keys exist and messages must be encrypted. */
    val ready: Boolean

    fun encrypt(plain: ByteArray, counter: Int): ByteArray

    fun decrypt(cipher: ByteArray): ByteArray
}

/**
 * The chunked message protocol of one Xiaomi BLE V1 characteristic (Gadgetbridge
 * XiaomiCharacteristicV1), as a pure state machine: the session feeds it notifications and timer
 * expiries and performs the [Output]s it returns (GATT writes to this characteristic, decoded
 * payloads, send results). Every frame starts with a little-endian 16-bit chunk number; 0 means a
 * control frame:
 *
 * - `00 00 00 enc n(2)`: chunked transfer start (answered with `00 00 01 01`);
 * - `00 00 01 sub [...]`: chunked ack: 0 end, 1 start accepted, 2 nack, 5 resend the listed chunks;
 * - `00 00 02 enc [payload]`: single command (acked with `00 00 03 00`), enc 1 = encrypted;
 * - `00 00 03 result`: ack of a single command (0 = ok).
 *
 * Encrypted characteristics send short commands as single frames with a 16-bit counter that is part
 * of the CCM nonce; plaintext characteristics always use chunked transfers.
 */
class XiaomiBleChunker(
    private val encrypted: Boolean,
    private val cipher: XiaomiBleCipher?,
    /** False for the data upload characteristic (counter 0 every time). */
    private val incrementNonce: Boolean = true,
) {
    sealed interface Output {
        /** Write [bytes] to this characteristic. */
        class Write(val bytes: ByteArray) : Output {
            override fun toString(): String = "Write(${bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }})"
        }

        /** A complete, decrypted message from the device. */
        class Received(val payload: ByteArray) : Output

        /** The device acknowledged ([ok]) or rejected the message being sent. */
        data class Sent(val ok: Boolean) : Output

        /** (Re)arm the missing-chunks timer ([TIMEOUT_MS]); [StopTimer] disarms it. */
        data object StartTimer : Output
        data object StopTimer : Output
    }

    /** Max frame size including the chunk header (MTU - 3; Gadgetbridge assumes 247 until told). */
    var maxWriteSize: Int = DEFAULT_MAX_WRITE
        private set

    private var maxWriteSizeForCurrent = maxWriteSize
    private var encryptedIndex = 0
    private var numChunks = 0
    private val receivedChunks = HashMap<Int, ByteArray>()

    private class Payload(val plain: ByteArray) {
        var toSend: ByteArray = plain
    }

    private val queue = ArrayDeque<Payload>()
    private var current: Payload? = null
    private var waitingAck = false
    private var sendingChunked = false

    /** True while something was sent and its (n)ack is pending. */
    val busy: Boolean get() = waitingAck || sendingChunked

    /** True while an incoming chunked transfer is incomplete. */
    val receiving: Boolean get() = numChunks > 0

    fun setMtu(mtu: Int) {
        maxWriteSize = (mtu - 3).coerceIn(20, 512)
    }

    /** As Gadgetbridge's reset(): after authentication the counter restarts at 1 (0 is used by auth). */
    fun reset() {
        numChunks = 0
        encryptedIndex = 1
        receivedChunks.clear()
        queue.clear()
        waitingAck = false
        sendingChunked = false
        current = null
    }

    /** Queues [payload] (a serialized command); returns what to write now. */
    fun send(payload: ByteArray): List<Output> {
        queue.addLast(Payload(payload))
        return sendNext()
    }

    /** Drops the message waiting for an ack that never came, so the queue keeps moving. */
    fun onAckTimeout(): List<Output> {
        if (!busy) return emptyList()
        current = null
        waitingAck = false
        sendingChunked = false
        return listOf<Output>(Output.Sent(false)) + sendNext()
    }

    /** Missing-chunks timer expired: ask the device to resend what is missing (Gadgetbridge requestMissingChunks). */
    fun onReceiveTimeout(): List<Output> {
        if (numChunks <= 0) return emptyList()
        val missing = (1..numChunks).filter { it !in receivedChunks }
        val count = minOf(missing.size, (maxWriteSize - 4) / 2)
        val buf = ByteBuffer.allocate(4 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0).put(1).put(5)
        for (i in 0 until count) buf.putShort(missing[i].toShort())
        return listOf(Output.Write(buf.array()), Output.StartTimer)
    }

    /** One notification of this characteristic. Never throws on malformed input. */
    fun onNotification(value: ByteArray): List<Output> = try {
        handle(ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN))
    } catch (e: BufferUnderflowException) {
        emptyList()
    } catch (e: IndexOutOfBoundsException) {
        emptyList()
    }

    private fun handle(buf: ByteBuffer): List<Output> {
        val chunk = buf.getShort().toInt()
        if (chunk != 0) return onChunk(chunk, buf)
        return when (buf.get().toInt()) {
            0 -> onChunkedStart(buf)
            1 -> onChunkedAck(buf)
            2 -> onSingleCommand(buf)
            3 -> onAck(buf)
            else -> emptyList()
        }
    }

    private fun onChunk(chunk: Int, buf: ByteBuffer): List<Output> {
        if (chunk < 0 || chunk > numChunks) return emptyList() // exceeds the announced count
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        receivedChunks[chunk] = bytes
        if (receivedChunks.size < numChunks) return listOf(Output.StartTimer)
        val out = mutableListOf<Output>(Output.StopTimer, Output.Write(CHUNK_END_ACK))
        val payload = reassemble()
        numChunks = 0
        receivedChunks.clear()
        if (payload.isNotEmpty()) {
            // Chunks are always encrypted on encrypted characteristics.
            val plain = if (encrypted) decryptOrNull(payload) else payload
            if (plain != null) out += Output.Received(plain)
        }
        return out
    }

    private fun reassemble(): ByteArray {
        var total = 0
        for (i in 1..numChunks) total += receivedChunks[i]?.size ?: return ByteArray(0)
        val out = ByteArray(total)
        var offset = 0
        for (i in 1..numChunks) {
            val part = receivedChunks.getValue(i)
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }

    private fun onChunkedStart(buf: ByteBuffer): List<Output> {
        val messageEncrypted = buf.get().toInt()
        if (messageEncrypted != (if (encrypted) 1 else 0)) return emptyList()
        numChunks = buf.getShort().toInt() and 0xffff
        receivedChunks.clear()
        return listOf(Output.Write(CHUNK_START_ACK))
    }

    private fun onChunkedAck(buf: ByteBuffer): List<Output> {
        val subtype = buf.get().toInt()
        val remaining = ByteArray(buf.remaining())
        buf.get(remaining)
        return when (subtype) {
            0 -> { // all chunks received
                val out = mutableListOf<Output>()
                if (current != null) out += Output.Sent(true)
                current = null
                sendingChunked = false
                out + sendNext()
            }
            1 -> { // start accepted: send every chunk
                val payload = current?.toSend ?: return emptyList() // unsolicited (Mi Watch Color Sport)
                val chunkPayloadSize = maxWriteSizeForCurrent - 2
                val out = mutableListOf<Output>()
                var i = 0
                while (i * chunkPayloadSize < payload.size) {
                    out += Output.Write(chunkFrame(payload, i, chunkPayloadSize))
                    i++
                }
                out
            }
            2 -> { // nack
                val out = mutableListOf<Output>()
                if (current != null) out += Output.Sent(false)
                current = null
                sendingChunked = false
                out + sendNext()
            }
            5 -> { // resend the listed chunks (1-based)
                val payload = current?.toSend ?: return emptyList()
                if (remaining.size >= 2) {
                    val rb = ByteBuffer.wrap(remaining).order(ByteOrder.LITTLE_ENDIAN)
                    val out = mutableListOf<Output>()
                    repeat(remaining.size / 2) {
                        val index = (rb.getShort().toInt() and 0xffff) - 1
                        if (index >= 0 && index * (maxWriteSizeForCurrent - 2) < payload.size) {
                            out += Output.Write(chunkFrame(payload, index, maxWriteSizeForCurrent - 2))
                        }
                    }
                    out
                } else if (maxWriteSize != maxWriteSizeForCurrent) {
                    // MTU changed mid-transfer: start this message again with the new size.
                    val again = current
                    current = null
                    sendingChunked = false
                    if (again != null) queue.addFirst(Payload(again.plain))
                    sendNext()
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun onSingleCommand(buf: ByteBuffer): List<Output> {
        val out = mutableListOf<Output>(Output.Write(PAYLOAD_ACK))
        val encryption = buf.get().toInt()
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        val plain = if (encryption == 1) decryptOrNull(bytes) else bytes
        if (plain != null) out += Output.Received(plain)
        return out
    }

    private fun onAck(buf: ByteBuffer): List<Output> {
        val result = buf.get().toInt()
        val out = mutableListOf<Output>()
        if (current != null) out += Output.Sent(result == 0)
        current = null
        waitingAck = false
        return out + sendNext()
    }

    private fun decryptOrNull(bytes: ByteArray): ByteArray? = try {
        cipher?.decrypt(bytes)
    } catch (e: Exception) {
        null
    }

    private fun sendNext(): List<Output> {
        if (busy) return emptyList()
        val payload = queue.removeFirstOrNull() ?: return emptyList()
        current = payload
        val encrypt = encrypted && cipher != null && cipher.ready
        if (encrypt) payload.toSend = cipher!!.encrypt(payload.plain, if (incrementNonce) encryptedIndex else 0)
        maxWriteSizeForCurrent = maxWriteSize
        val chunked = !encrypted || payload.toSend.size + 6 > maxWriteSizeForCurrent
        if (chunked) {
            if (encrypt && incrementNonce) {
                payload.toSend = ByteBuffer.allocate(2 + payload.toSend.size).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(encryptedIndex++.toShort()).put(payload.toSend).array()
            }
            sendingChunked = true
            val count = (payload.toSend.size + (maxWriteSizeForCurrent - 2) - 1) / (maxWriteSizeForCurrent - 2)
            val start = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0).put(0).put((if (encrypt) 1 else 0).toByte()).putShort(count.toShort()).array()
            return listOf(Output.Write(start))
        }
        val buf = ByteBuffer.allocate((if (encrypt) 6 else 4) + payload.toSend.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0).put(2).put((if (encrypt) 1 else 2).toByte())
        if (encrypt) buf.putShort((if (incrementNonce) encryptedIndex++ else 0).toShort())
        buf.put(payload.toSend)
        waitingAck = true
        return listOf(Output.Write(buf.array()))
    }

    private fun chunkFrame(payload: ByteArray, index: Int, chunkPayloadSize: Int): ByteArray {
        val start = index * chunkPayloadSize
        val end = minOf((index + 1) * chunkPayloadSize, payload.size)
        val frame = ByteArray(2 + end - start)
        frame[0] = ((index + 1) and 0xff).toByte()
        frame[1] = (((index + 1) shr 8) and 0xff).toByte()
        System.arraycopy(payload, start, frame, 2, end - start)
        return frame
    }

    companion object {
        val PAYLOAD_ACK = byteArrayOf(0, 0, 3, 0)
        val CHUNK_START_ACK = byteArrayOf(0, 0, 1, 1)
        val CHUNK_END_ACK = byteArrayOf(0, 0, 1, 0)
        const val TIMEOUT_MS = 5_000L

        /** MTU 247 - 3, as Gadgetbridge assumes before the MTU exchange completes. */
        const val DEFAULT_MAX_WRITE = 244
    }
}
