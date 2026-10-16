// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.wearable.zeppos

import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** AES-128/ECB without padding (Gadgetbridge CryptoUtils.encryptAES / decryptAES). */
internal object ZeppOsCrypto {
    @Suppress("GetInstance")
    private fun cipher(mode: Int, key: ByteArray): Cipher =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(mode, SecretKeySpec(key, "AES")) }

    fun encrypt(data: ByteArray, key: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE, key).doFinal(data)

    fun decrypt(data: ByteArray, key: ByteArray): ByteArray = cipher(Cipher.DECRYPT_MODE, key).doFinal(data)

    fun crc32(data: ByteArray, offset: Int, length: Int): Int = CRC32().apply { update(data, offset, length) }.value.toInt()

    /** Per-message key: the session key with every byte XORed with the message handle. */
    fun messageKey(sessionKey: ByteArray, handle: Int): ByteArray = ByteArray(16) { (sessionKey[it].toInt() xor handle).toByte() }

    /** Ciphertext length for a plaintext of [length]: + 8 (sequence, CRC) rounded up to 16. */
    fun encryptedLength(length: Int): Int {
        var n = length + 8
        val overflow = n % 16
        if (overflow > 0) n += 16 - overflow
        return n
    }
}

/**
 * Zepp OS chunked transfer encoder (Gadgetbridge Huami2021ChunkedEncoder, extended flags as Zepp OS
 * always uses them). Every message gets a new handle; the first chunk carries the plaintext length
 * and the endpoint:
 *
 * - first chunk: `03 flags 00 handle 00 | length u32 LE | endpoint u16 LE | data`;
 * - later chunks: `03 flags 00 handle count | data`;
 * - flags: 0x01 first, 0x02 last, 0x04 needs ack (last chunk), 0x08 encrypted.
 *
 * Encrypted messages are `data | sequence u32 LE | CRC32(data | sequence) LE | zero padding`
 * AES-ECB'd with the session key XOR handle.
 */
class ZeppOsChunkEncoder(var mtu: Int = 23) {
    private var writeHandle = 0
    private var sessionKey: ByteArray? = null
    var sequence: Int = 0
        private set

    val hasEncryption: Boolean get() = sessionKey != null

    fun setEncryption(sequence: Int, sessionKey: ByteArray) {
        this.sequence = sequence
        this.sessionKey = sessionKey.copyOf()
    }

    fun reset() {
        writeHandle = 0
    }

    /** The chunks of one message to write to 0x0016. Throws when [encrypt] is asked without a key. */
    fun encode(endpoint: Int, data: ByteArray, encrypt: Boolean): List<ByteArray> {
        val key = sessionKey
        check(!encrypt || key != null) { "no session key" }
        writeHandle = (writeHandle + 1) and 0xff
        val length = data.size
        val toSend = if (encrypt) {
            val payload = ByteArray(ZeppOsCrypto.encryptedLength(length))
            System.arraycopy(data, 0, payload, 0, length)
            putIntLe(payload, length, sequence)
            sequence++
            putIntLe(payload, length + 4, ZeppOsCrypto.crc32(payload, 0, length + 4))
            ZeppOsCrypto.encrypt(payload, ZeppOsCrypto.messageKey(key!!, writeHandle))
        } else {
            data
        }
        val chunks = ArrayList<ByteArray>()
        var remaining = toSend.size
        var count = 0
        var headerSize = 11
        while (remaining > 0) {
            val maxChunk = maxWrite(mtu) - headerSize
            val copy = minOf(remaining, maxChunk)
            val chunk = ByteArray(copy + headerSize)
            var flags = if (encrypt) 0x08 else 0
            if (count == 0) {
                flags = flags or 0x01
                putIntLe(chunk, 5, length)
                chunk[9] = (endpoint and 0xff).toByte()
                chunk[10] = ((endpoint shr 8) and 0xff).toByte()
            }
            if (remaining <= maxChunk) flags = flags or 0x02 or 0x04
            chunk[0] = 0x03
            chunk[1] = flags.toByte()
            chunk[2] = 0
            chunk[3] = writeHandle.toByte()
            chunk[4] = count.toByte()
            System.arraycopy(toSend, toSend.size - remaining, chunk, headerSize, copy)
            chunks += chunk
            remaining -= copy
            headerSize = 5
            count++
        }
        return chunks
    }

    companion object {
        /** Gadgetbridge calcMaxWriteChunk: min(512, max(23, mtu) - 3). */
        fun maxWrite(mtu: Int): Int = minOf(512, maxOf(23, mtu) - 3)

        internal fun putIntLe(b: ByteArray, offset: Int, value: Int) {
            b[offset] = value.toByte()
            b[offset + 1] = (value shr 8).toByte()
            b[offset + 2] = (value shr 16).toByte()
            b[offset + 3] = (value shr 24).toByte()
        }
    }
}

/**
 * Reassembles Zepp OS chunked messages from 0x0017 notifications (Gadgetbridge
 * Huami2021ChunkedDecoder with the 2021 header). Never throws on malformed input: bad frames
 * reset the reassembly.
 */
class ZeppOsChunkDecoder(private val maxMessage: Int = 1 shl 20) {
    class Message(val endpoint: Int, val payload: ByteArray)

    /** What one frame produced: a finished [message] and whether the watch wants an ack. */
    class Result(val message: Message?, val needsAck: Boolean, val ack: ByteArray?)

    private var sessionKey: ByteArray? = null
    private var currentHandle: Int? = null
    private var currentType = 0
    private var currentLength = 0
    private var buffer: ByteArray? = null
    private var filled = 0
    private var currentEncrypted = false
    var lastHandle = 0
        private set
    var lastCount = 0
        private set

    fun setEncryption(sessionKey: ByteArray) {
        this.sessionKey = sessionKey.copyOf()
    }

    fun reset() {
        currentHandle = null
        buffer = null
        filled = 0
        currentLength = 0
        currentType = 0
        currentEncrypted = false
    }

    fun decode(data: ByteArray): Result? = try {
        decodeUnsafe(data)
    } catch (e: IndexOutOfBoundsException) {
        reset()
        null
    } catch (e: IllegalArgumentException) {
        reset()
        null
    } catch (e: java.security.GeneralSecurityException) {
        reset()
        null
    }

    private fun decodeUnsafe(data: ByteArray): Result? {
        if (data.size < 5 || data[0].toInt() != 0x03) return null
        var i = 1
        val flags = data[i++].toInt() and 0xff
        val encrypted = flags and 0x08 != 0
        val firstChunk = flags and 0x01 != 0
        val lastChunk = flags and 0x02 != 0
        val needsAck = flags and 0x04 != 0
        i++ // extended header byte
        val handle = data[i++].toInt() and 0xff
        val handleNow = currentHandle
        if (handleNow != null && handleNow != handle && !firstChunk) {
            // A chunk of another message: ignored, as Gadgetbridge does. (A new first chunk restarts
            // the reassembly, so one lost last chunk cannot wedge the decoder.)
            return null
        }
        lastHandle = handle
        lastCount = data[i++].toInt() and 0xff
        if (firstChunk) {
            require(data.size >= i + 6) { "short first chunk" }
            val fullLength = (data[i].toInt() and 0xff) or ((data[i + 1].toInt() and 0xff) shl 8) or
                ((data[i + 2].toInt() and 0xff) shl 16) or ((data[i + 3].toInt() and 0xff) shl 24)
            i += 4
            require(fullLength in 0..maxMessage) { "bad length" }
            currentLength = fullLength
            currentEncrypted = encrypted
            val allocate = if (encrypted) ZeppOsCrypto.encryptedLength(fullLength) else fullLength
            buffer = ByteArray(allocate)
            filled = 0
            currentType = (data[i].toInt() and 0xff) or ((data[i + 1].toInt() and 0xff) shl 8)
            i += 2
            currentHandle = handle
        }
        val buf = buffer ?: return null // continuation without a start
        val n = data.size - i
        require(filled + n <= buf.size) { "overflow" }
        System.arraycopy(data, i, buf, filled, n)
        filled += n
        var message: Message? = null
        if (lastChunk) {
            var payload = buf.copyOf(filled)
            if (currentEncrypted) {
                val key = sessionKey ?: throw IllegalArgumentException("no session key")
                require(payload.size % 16 == 0) { "bad ciphertext size" }
                payload = ZeppOsCrypto.decrypt(payload, ZeppOsCrypto.messageKey(key, handle)).copyOf(currentLength)
            }
            message = Message(currentType, payload)
            reset()
        }
        return Result(message, needsAck, if (needsAck) ack(lastHandle, lastCount) else null)
    }

    companion object {
        /** Phone → watch ack, written to 0x0017: `04 00 handle 01 count`. */
        fun ack(handle: Int, count: Int): ByteArray = byteArrayOf(0x04, 0x00, handle.toByte(), 0x01, count.toByte())
    }
}
