// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.libs

import android.annotation.SuppressLint
import com.enjoyyourtime.globalfitness.scale.Bytes
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EufyReading(val weightKg: Float, val impedanceOhm: Int)

/**
 * Eufy Smart Scale P2 / P2 Pro: AES-CBC (key = MD5 of the MAC, IV = sixteen '0') handshake split
 * in XOR-checksummed segments, then 16-byte weight notifications.
 */
object EufyP2Lib {
    private val AES_IV = "0000000000000000".toByteArray(Charsets.US_ASCII)

    fun keyFromMac(mac: String): ByteArray {
        val clean = mac.replace(":", "").replace("-", "").uppercase()
        require(Regex("^[0-9A-F]{12}$").matches(clean)) { "Eufy: dirección MAC no válida" }
        return MessageDigest.getInstance("MD5").digest(clean.toByteArray(Charsets.UTF_8))
    }

    // CBC with the vendor's fixed IV is what the scale requires.
    @SuppressLint("GetInstance")
    private fun cbc(mode: Int, key: ByteArray): Cipher =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(AES_IV)) }

    fun encryptToHex(plaintext: String, key: ByteArray): String {
        val enc = cbc(Cipher.ENCRYPT_MODE, key).doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Bytes.toHex(Base64.getEncoder().encodeToString(enc).toByteArray(Charsets.US_ASCII))
    }

    fun decrypt(encrypted: ByteArray, key: ByteArray): String = String(cbc(Cipher.DECRYPT_MODE, key).doFinal(encrypted), Charsets.UTF_8)

    fun buildSubContract(dataHex: String, prefix: Int): List<ByteArray> {
        val origLen = dataHex.length
        val segments = maxOf(1, (origLen + 29) / 30)
        return (0 until segments).map { i ->
            val slice = dataHex.substring(i * 30, minOf((i + 1) * 30, origLen))
            val body = byteArrayOf(prefix.toByte(), segments.toByte(), i.toByte(), (origLen / 2).toByte()) + Bytes.fromHex(slice)
            body + byteArrayOf(Bytes.xor(body).toByte())
        }
    }

    class SegmentReassembler(private val prefix: Int) {
        private var buffer = ByteArray(0)
        private var expectedTotal = 0
        private var nextSegment = 0

        private fun reset() {
            buffer = ByteArray(0)
            expectedTotal = 0
            nextSegment = 0
        }

        fun feed(frame: ByteArray): ByteArray? {
            if (frame.size < 5 || Bytes.u8(frame, 0) != prefix) return null
            if (Bytes.xor(frame, 0, frame.size - 1) != Bytes.u8(frame, frame.size - 1)) return null
            val segments = Bytes.u8(frame, 1)
            val index = Bytes.u8(frame, 2)
            if (index == 0) {
                reset()
                expectedTotal = Bytes.u8(frame, 3)
            }
            if (index != nextSegment) return null
            buffer += frame.copyOfRange(4, frame.size - 1)
            nextSegment++
            if (index == segments - 1) {
                val out = buffer
                val expected = expectedTotal
                reset()
                return if (out.size == expected) out else null
            }
            return null
        }
    }

    /** Final weight notification: `CF ? 00 …`, weight LE /100 at 6, impedance 24-bit LE at 8, final flag at 12. */
    fun parseWeightNotification(d: ByteArray): EufyReading? {
        if (d.size != 16 || Bytes.u8(d, 0) != 0xCF || Bytes.u8(d, 2) != 0x00) return null
        val weight = Bytes.fromUnsignedInt16Le(d, 6) / 100f
        if (weight < 2f || weight > 200f) return null
        if (Bytes.u8(d, 12) != 0x00) return null
        return EufyReading(weight, Bytes.fromUnsignedInt24Le(d, 8))
    }
}

/** Client side of the Eufy C0/C1/C2/C3 authentication exchange. */
class EufyAuthHandler(mac: String, clientUuid: String? = null) {
    val key: ByteArray = EufyP2Lib.keyFromMac(mac)
    val clientUuid: String = clientUuid ?: UUID.randomUUID().toString().replace("-", "").take(15)
    private val c1 = EufyP2Lib.SegmentReassembler(0xC1)
    var deviceUuid: String? = null
        private set
    var isAuthenticated = false
        private set

    fun buildC0(): List<ByteArray> = EufyP2Lib.buildSubContract(EufyP2Lib.encryptToHex(clientUuid, key), 0xC0)

    fun handleC1(frame: ByteArray): Boolean {
        val payload = c1.feed(frame) ?: return false
        deviceUuid = EufyP2Lib.decrypt(Base64.getDecoder().decode(String(payload, Charsets.US_ASCII)), key)
        return true
    }

    fun buildC2(): List<ByteArray> {
        val dev = checkNotNull(deviceUuid) { "C1 not received" }
        return EufyP2Lib.buildSubContract(EufyP2Lib.encryptToHex("${clientUuid}_$dev", key), 0xC2)
    }

    fun handleC3(frame: ByteArray): Boolean {
        if (frame.size < 5 || Bytes.u8(frame, 0) != 0xC3) return false
        isAuthenticated = Bytes.u8(frame, 4) == 0
        return true
    }
}
