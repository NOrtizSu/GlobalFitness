package com.enjoyyourtime.globalfitness.scale.libs

import android.annotation.SuppressLint
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-CCM (RFC 3610 / NIST SP 800-38C) built on the platform AES block cipher, so it works on
 * every Android version and on the JVM without extra libraries. Nonce length 7..13 bytes, tag
 * length 4..16 (even).
 */
object AesCcm {
    private const val BLOCK = 16

    /** Returns ciphertext || tag. */
    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray, tagLength: Int): ByteArray {
        val aes = aes(key)
        val tag = cbcMac(aes, nonce, plaintext, aad, tagLength)
        val out = ctr(aes, nonce, plaintext)
        val s0 = aes.doFinal(counterBlock(nonce, 0))
        val encTag = ByteArray(tagLength) { i -> (tag[i].toInt() xor s0[i].toInt()).toByte() }
        return out + encTag
    }

    /** Returns the plaintext, or null when the tag does not verify. */
    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray, tagLength: Int): ByteArray? {
        if (ciphertextAndTag.size < tagLength) return null
        val aes = aes(key)
        val ct = ciphertextAndTag.copyOfRange(0, ciphertextAndTag.size - tagLength)
        val receivedTag = ciphertextAndTag.copyOfRange(ciphertextAndTag.size - tagLength, ciphertextAndTag.size)
        val plain = ctr(aes, nonce, ct)
        val s0 = aes.doFinal(counterBlock(nonce, 0))
        val expected = cbcMac(aes, nonce, plain, aad, tagLength)
        var diff = 0
        for (i in 0 until tagLength) diff = diff or ((expected[i].toInt() xor s0[i].toInt()) xor receivedTag[i].toInt())
        return if (diff and 0xFF == 0) plain else null
    }

    // Raw AES block cipher: CCM is built on top of it (ECB on single blocks is the primitive here).
    @SuppressLint("GetInstance")
    private fun aes(key: ByteArray): Cipher {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "Bad AES key length" }
        return Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
    }

    private fun lengthFieldSize(nonce: ByteArray): Int {
        require(nonce.size in 7..13) { "Bad nonce length" }
        return 15 - nonce.size
    }

    private fun counterBlock(nonce: ByteArray, counter: Int): ByteArray {
        val l = lengthFieldSize(nonce)
        val block = ByteArray(BLOCK)
        block[0] = (l - 1).toByte()
        nonce.copyInto(block, 1)
        for (i in 0 until l) block[BLOCK - 1 - i] = ((counter shr (8 * i)) and 0xFF).toByte()
        return block
    }

    private fun ctr(aes: Cipher, nonce: ByteArray, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var counter = 1
        var offset = 0
        while (offset < input.size) {
            val s = aes.doFinal(counterBlock(nonce, counter++))
            val n = minOf(BLOCK, input.size - offset)
            for (i in 0 until n) out[offset + i] = (input[offset + i].toInt() xor s[i].toInt()).toByte()
            offset += n
        }
        return out
    }

    private fun cbcMac(aes: Cipher, nonce: ByteArray, message: ByteArray, aad: ByteArray, tagLength: Int): ByteArray {
        require(tagLength in 4..16 && tagLength % 2 == 0) { "Bad tag length" }
        val l = lengthFieldSize(nonce)
        val b0 = ByteArray(BLOCK)
        val flags = (if (aad.isNotEmpty()) 0x40 else 0) or (((tagLength - 2) / 2) shl 3) or (l - 1)
        b0[0] = flags.toByte()
        nonce.copyInto(b0, 1)
        for (i in 0 until l) b0[BLOCK - 1 - i] = ((message.size.toLong() shr (8 * i)) and 0xFF).toByte()

        val blocks = ArrayList<ByteArray>()
        blocks += b0
        if (aad.isNotEmpty()) {
            require(aad.size < 0xFF00) { "AAD too long" }
            val encoded = byteArrayOf(((aad.size shr 8) and 0xFF).toByte(), (aad.size and 0xFF).toByte()) + aad
            blocks += pad(encoded)
        }
        if (message.isNotEmpty()) blocks += pad(message)

        var x = ByteArray(BLOCK)
        for (chunk in blocks) {
            var offset = 0
            while (offset < chunk.size) {
                val y = ByteArray(BLOCK) { i -> (x[i].toInt() xor chunk[offset + i].toInt()).toByte() }
                x = aes.doFinal(y)
                offset += BLOCK
            }
        }
        return x.copyOf(tagLength)
    }

    private fun pad(data: ByteArray): ByteArray {
        val rem = data.size % BLOCK
        return if (rem == 0) data else data + ByteArray(BLOCK - rem)
    }
}
