package com.enjoyyourtime.globalfitness.wearable.zeppos

import com.enjoyyourtime.globalfitness.wearable.hex
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gadgetbridge Huami2021ChunkedEncoder / Decoder framing and encryption. */
class ZeppOsChunkedTest {
    private val sessionKey = hex("00112233445566778899aabbccddeeff")

    @Test
    fun servicesRequestAtMtu23() {
        val e = ZeppOsChunkEncoder(23)
        e.encode(0x0082, hex("01"), false)
        e.encode(0x0082, hex("02"), false)
        // Third message: handle 3, first|last|ack, length 1, endpoint 0x0000, payload 03.
        assertEquals(listOf("030700030001000000000003"), e.encode(0x0000, hex("03"), false).map { it.hex() })
    }

    @Test
    fun multiChunkFlagsAndCounts() {
        val e = ZeppOsChunkEncoder(23)
        val data = ByteArray(30) { it.toByte() }
        val chunks = e.encode(0x001e, data, false)
        assertEquals(3, chunks.size)
        assertEquals("0301000100" + "1e000000" + "1e00" + data.copyOfRange(0, 9).hex(), chunks[0].hex())
        assertEquals("0300000101" + data.copyOfRange(9, 24).hex(), chunks[1].hex())
        assertEquals("0306000102" + data.copyOfRange(24, 30).hex(), chunks[2].hex())
        assertTrue(chunks.all { it.size <= 20 })
    }

    @Test
    fun encryptedMessageLayout() {
        val e = ZeppOsChunkEncoder(247).apply { setEncryption(0x01020304, sessionKey) }
        val chunks = e.encode(0x0029, hex("03"), true)
        assertEquals(1, chunks.size)
        val c = chunks[0]
        // Flags: encrypted | first | last | ack; length is the plaintext length.
        assertEquals("030f000100" + "01000000" + "2900", c.copyOfRange(0, 11).hex())
        val ciphertext = c.copyOfRange(11, c.size)
        assertEquals(16, ciphertext.size)
        @Suppress("GetInstance")
        val aes = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(ByteArray(16) { (sessionKey[it].toInt() xor 1).toByte() }, "AES"))
        }
        val plain = aes.doFinal(ciphertext)
        // data | sequence LE | CRC32(data | sequence) LE | zero padding
        val crc = CRC32().apply { update(plain, 0, 5) }.value
        assertEquals("03" + "04030201", plain.copyOfRange(0, 5).hex())
        assertEquals(crc.toInt(), (plain[5].toInt() and 0xff) or ((plain[6].toInt() and 0xff) shl 8) or ((plain[7].toInt() and 0xff) shl 16) or ((plain[8].toInt() and 0xff) shl 24))
        assertEquals("00".repeat(7), plain.copyOfRange(9, 16).hex())
        assertEquals(0x01020305, e.sequence)
    }

    @Test
    fun encoderDecoderRoundTrip() {
        for (mtu in listOf(23, 100, 247)) {
            for (encrypt in listOf(false, true)) {
                val e = ZeppOsChunkEncoder(mtu).apply { setEncryption(7, sessionKey) }
                val d = ZeppOsChunkDecoder().apply { setEncryption(sessionKey) }
                for (size in listOf(0, 1, 15, 16, 17, 100, 600)) {
                    if (size == 0 && !encrypt) continue // nothing to send (Gadgetbridge writes no chunk either)
                    val data = ByteArray(size) { (it * 7).toByte() }
                    val chunks = e.encode(0x001e, data, encrypt)
                    val results = chunks.mapNotNull { d.decode(it) }
                    val message = results.last().message!!
                    assertEquals(0x001e, message.endpoint)
                    assertArrayEquals("mtu $mtu enc $encrypt size $size", data, message.payload)
                    assertTrue(results.last().needsAck)
                    assertEquals(ZeppOsChunkDecoder.ack(d.lastHandle, d.lastCount).hex(), results.last().ack!!.hex())
                }
            }
        }
    }

    @Test
    fun decoderIgnoresOtherHandlesAndGarbage() {
        val e = ZeppOsChunkEncoder(23)
        val d = ZeppOsChunkDecoder()
        val first = e.encode(0x0016, ByteArray(30), false)
        val other = e.encode(0x0016, ByteArray(30), false)
        assertNull(d.decode(first[0])!!.message)
        // A middle chunk of another message is ignored.
        assertNull(d.decode(other[1]))
        assertNull(d.decode(first[1])!!.message)
        assertEquals(30, d.decode(first[2])!!.message!!.payload.size)
        assertNull(d.decode(hex("0401")))
        assertNull(d.decode(hex("03")))
        assertNull(d.decode(hex("0301000100ffffffff0000"))) // absurd length
        assertNull(d.decode(hex("0300000900aa"))) // continuation without a start
        assertEquals("0400050103", ZeppOsChunkDecoder.ack(5, 3).hex())
    }
}
