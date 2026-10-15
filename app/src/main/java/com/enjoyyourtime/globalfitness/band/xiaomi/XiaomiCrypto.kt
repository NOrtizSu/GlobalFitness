// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.band.xiaomi

import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto primitives of the Xiaomi protobuf protocol (Gadgetbridge XiaomiAuthService), on plain
 * `javax.crypto` so they run on Android and in JVM unit tests:
 *
 * - session key derivation: HMAC-SHA256 "miwear-auth" expansion ([deriveKeyMaterial]);
 * - AES-CCM with a 4-byte tag and 12-byte nonce (SPP V1 payloads, auth device info). Android has no
 *   CCM provider (Gadgetbridge bundles BouncyCastle), so it is built here from AES-ECB per RFC 3610;
 * - AES-CTR with the key reused as IV (SPP V2 payloads).
 */
object XiaomiCrypto {
    const val CCM_TAG_LENGTH = 4
    private const val BLOCK = 16
    private val MIWEAR_AUTH = "miwear-auth".toByteArray(Charsets.US_ASCII)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * Gadgetbridge `computeAuthStep3Hmac`: PRK = HMAC(phoneNonce || watchNonce, secretKey), then 64
     * bytes of T(i) = HMAC(PRK, T(i-1) || "miwear-auth" || i). Layout of the result: decryption key
     * [0,16), encryption key [16,32), decryption nonce [32,36), encryption nonce [36,40).
     */
    fun deriveKeyMaterial(secretKey: ByteArray, phoneNonce: ByteArray, watchNonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(phoneNonce + watchNonce, "HmacSHA256"))
        val prk = mac.doFinal(secretKey)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val output = ByteArray(64)
        var previous = ByteArray(0)
        var counter = 1
        var filled = 0
        while (filled < output.size) {
            mac.update(previous)
            mac.update(MIWEAR_AUTH)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, output.size - filled)
            System.arraycopy(previous, 0, output, filled, n)
            filled += n
            counter++
        }
        return output
    }

    /** AES-CCM (RFC 3610) without associated data. Returns ciphertext || tag. */
    fun aesCcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, tagLength: Int = CCM_TAG_LENGTH): ByteArray {
        val aes = ecb(key)
        val lengthBytes = 15 - nonce.size
        checkCcmParameters(nonce, tagLength, plaintext.size, lengthBytes)
        val tag = cbcMac(aes, nonce, plaintext, tagLength, lengthBytes)
        val out = ByteArray(plaintext.size + tagLength)
        ctrXor(aes, nonce, lengthBytes, plaintext, out)
        val s0 = aes.doFinal(counterBlock(nonce, lengthBytes, 0))
        for (i in 0 until tagLength) out[plaintext.size + i] = (tag[i].toInt() xor s0[i].toInt()).toByte()
        return out
    }

    /** Inverse of [aesCcmEncrypt]; throws [GeneralSecurityException] when the tag does not match. */
    fun aesCcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, tagLength: Int = CCM_TAG_LENGTH): ByteArray {
        if (ciphertext.size < tagLength) throw GeneralSecurityException("CCM: datos más cortos que la etiqueta")
        val aes = ecb(key)
        val lengthBytes = 15 - nonce.size
        val dataLength = ciphertext.size - tagLength
        checkCcmParameters(nonce, tagLength, dataLength, lengthBytes)
        val plaintext = ByteArray(dataLength)
        ctrXor(aes, nonce, lengthBytes, ciphertext.copyOf(dataLength), plaintext)
        val s0 = aes.doFinal(counterBlock(nonce, lengthBytes, 0))
        val received = ByteArray(tagLength) { (ciphertext[dataLength + it].toInt() xor s0[it].toInt()).toByte() }
        val expected = cbcMac(aes, nonce, plaintext, tagLength, lengthBytes)
        if (!MessageDigest.isEqual(received, expected)) throw GeneralSecurityException("CCM: etiqueta de autenticación incorrecta")
        return plaintext
    }

    /** AES/CTR/NoPadding; encryption and decryption are the same operation. */
    fun aesCtr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    fun toHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(digits[(b.toInt() shr 4) and 0xf])
            sb.append(digits[b.toInt() and 0xf])
        }
        return sb.toString()
    }

    /** Raw single-block AES, the building block of CCM (CBC-MAC + CTR); never used to encrypt data directly. */
    @Suppress("GetInstance")
    private fun ecb(key: ByteArray): Cipher =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }

    private fun checkCcmParameters(nonce: ByteArray, tagLength: Int, dataLength: Int, lengthBytes: Int) {
        require(nonce.size in 7..13) { "CCM nonce must be 7..13 bytes" }
        require(tagLength in 4..16 && tagLength % 2 == 0) { "CCM tag length must be 4..16 and even" }
        require(lengthBytes >= 4 || dataLength < (1 shl (8 * lengthBytes))) { "CCM payload too long" }
    }

    private fun cbcMac(aes: Cipher, nonce: ByteArray, data: ByteArray, tagLength: Int, lengthBytes: Int): ByteArray {
        val b0 = ByteArray(BLOCK)
        b0[0] = ((((tagLength - 2) / 2) shl 3) or (lengthBytes - 1)).toByte() // no associated data
        System.arraycopy(nonce, 0, b0, 1, nonce.size)
        var length = data.size
        for (i in 0 until lengthBytes) {
            b0[BLOCK - 1 - i] = (length and 0xff).toByte()
            length = length ushr 8
        }
        var x = aes.doFinal(b0)
        var offset = 0
        while (offset < data.size) {
            val block = ByteArray(BLOCK)
            System.arraycopy(data, offset, block, 0, minOf(BLOCK, data.size - offset))
            for (i in 0 until BLOCK) block[i] = (block[i].toInt() xor x[i].toInt()).toByte()
            x = aes.doFinal(block)
            offset += BLOCK
        }
        return x.copyOf(tagLength)
    }

    private fun counterBlock(nonce: ByteArray, lengthBytes: Int, counter: Int): ByteArray {
        val block = ByteArray(BLOCK)
        block[0] = (lengthBytes - 1).toByte()
        System.arraycopy(nonce, 0, block, 1, nonce.size)
        var c = counter
        for (i in 0 until lengthBytes) {
            block[BLOCK - 1 - i] = (c and 0xff).toByte()
            c = c ushr 8
        }
        return block
    }

    /** CTR keystream starting at counter 1 (counter 0 encrypts the tag). */
    private fun ctrXor(aes: Cipher, nonce: ByteArray, lengthBytes: Int, input: ByteArray, output: ByteArray) {
        var offset = 0
        var counter = 1
        while (offset < input.size) {
            val stream = aes.doFinal(counterBlock(nonce, lengthBytes, counter))
            val n = minOf(BLOCK, input.size - offset)
            for (i in 0 until n) output[offset + i] = (input[offset + i].toInt() xor stream[i].toInt()).toByte()
            offset += BLOCK
            counter++
        }
    }
}
