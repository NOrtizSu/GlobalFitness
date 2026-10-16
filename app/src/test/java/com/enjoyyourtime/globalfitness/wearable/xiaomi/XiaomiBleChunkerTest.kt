package com.enjoyyourtime.globalfitness.wearable.xiaomi

import com.enjoyyourtime.globalfitness.wearable.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frames of Gadgetbridge's XiaomiCharacteristicV1 chunk protocol, byte for byte. */
class XiaomiBleChunkerTest {
    /** Toy cipher: XOR with the counter (encrypt) / 0x55 (decrypt), so frames are predictable. */
    private class ToyCipher(override var ready: Boolean = true) : XiaomiBleCipher {
        val counters = mutableListOf<Int>()
        override fun encrypt(plain: ByteArray, counter: Int): ByteArray {
            counters += counter
            return ByteArray(plain.size) { (plain[it].toInt() xor counter).toByte() }
        }

        override fun decrypt(cipher: ByteArray): ByteArray = ByteArray(cipher.size) { (cipher[it].toInt() xor 0x55).toByte() }
    }

    private fun writes(out: List<XiaomiBleChunker.Output>) = out.filterIsInstance<XiaomiBleChunker.Output.Write>().map { it.bytes.hex() }

    private fun received(out: List<XiaomiBleChunker.Output>) = out.filterIsInstance<XiaomiBleChunker.Output.Received>().map { it.payload.hex() }

    @Test
    fun plaintextSendIsAlwaysChunked() {
        val c = XiaomiBleChunker(encrypted = false, cipher = null)
        // Start: chunk 0, type 0, not encrypted, 1 chunk.
        assertEquals(listOf("000000000100"), writes(c.send(hex("0805"))))
        assertTrue(c.busy)
        // Start accepted: the chunk, numbered from 1.
        assertEquals(listOf("01000805"), writes(c.onNotification(hex("00000101"))))
        // All received.
        val done = c.onNotification(hex("00000100"))
        assertEquals(listOf(XiaomiBleChunker.Output.Sent(true)), done)
        assertFalse(c.busy)
    }

    @Test
    fun encryptedSingleCommandsBeforeAndAfterAuthentication() {
        val cipher = ToyCipher(ready = false)
        val c = XiaomiBleChunker(encrypted = true, cipher = cipher)
        // Before the keys exist: plain single command, flag 2, no counter.
        assertEquals(listOf("00000202aabb"), writes(c.send(hex("aabb"))))
        // Queued until the ack.
        assertTrue(writes(c.send(hex("cc"))).isEmpty())
        assertEquals(listOf("00000202cc"), writes(c.onNotification(hex("00000300"))))
        c.onNotification(hex("00000300"))

        cipher.ready = true
        c.reset() // as after authentication: counter 1
        assertEquals(listOf("000002010100" + "abba"), writes(c.send(hex("aabb"))))
        c.onNotification(hex("00000300"))
        assertEquals(listOf("000002010200" + "cecc"), writes(c.send(hex("ccce"))))
        assertEquals(listOf(1, 2), cipher.counters)
    }

    @Test
    fun largeEncryptedMessageIsChunkedWithCounterPrefix() {
        val cipher = ToyCipher()
        val c = XiaomiBleChunker(encrypted = true, cipher = cipher)
        c.setMtu(23) // 20-byte frames
        c.reset()
        val payload = ByteArray(30) { it.toByte() }
        // 30 + 6 > 20: chunked; counter (2 bytes) + 30 bytes = 32 bytes in 18-byte chunks = 2 chunks.
        assertEquals(listOf("000000010200"), writes(c.send(payload)))
        val chunks = writes(c.onNotification(hex("00000101")))
        val encrypted = ByteArray(30) { (it xor 1).toByte() }
        val body = hex("0100") + encrypted
        assertEquals(listOf("0100" + body.copyOfRange(0, 18).hex(), "0200" + body.copyOfRange(18, 32).hex()), chunks)
        // The device asks for chunk 2 again.
        assertEquals(listOf("0200" + body.copyOfRange(18, 32).hex()), writes(c.onNotification(hex("000001050200"))))
        assertEquals(listOf(XiaomiBleChunker.Output.Sent(true)), c.onNotification(hex("00000100")))
    }

    @Test
    fun receivesSingleAndChunkedMessages() {
        val c = XiaomiBleChunker(encrypted = true, cipher = ToyCipher())
        val single = c.onNotification(hex("00000201") + hex("1d1e"))
        assertEquals(listOf("00000300"), writes(single))
        assertEquals(listOf("484b"), received(single))
        // Unencrypted single (flag != 1).
        assertEquals(listOf("0102"), received(c.onNotification(hex("00000202") + hex("0102"))))

        assertEquals(listOf("00000101"), writes(c.onNotification(hex("000000010200"))))
        assertTrue(c.receiving)
        val first = c.onNotification(hex("0100") + hex("5555"))
        assertEquals(listOf(XiaomiBleChunker.Output.StartTimer), first)
        val last = c.onNotification(hex("0200") + hex("54"))
        assertEquals(listOf("00000100"), writes(last))
        assertEquals(listOf("000001"), received(last))
        assertTrue(last.contains(XiaomiBleChunker.Output.StopTimer))
        assertFalse(c.receiving)
    }

    @Test
    fun missingChunksAreRequestedAndStartsWithTheWrongFlagIgnored() {
        val c = XiaomiBleChunker(encrypted = true, cipher = ToyCipher())
        assertTrue(c.onNotification(hex("000000000300")).isEmpty()) // plaintext start on an encrypted characteristic
        c.onNotification(hex("000000010300"))
        c.onNotification(hex("0200") + hex("aa"))
        assertTrue(c.onNotification(hex("0500") + hex("aa")).isEmpty()) // beyond the announced count
        assertEquals(listOf("00000105" + "0100" + "0300"), writes(c.onReceiveTimeout()))
    }

    @Test
    fun nackAndAckTimeoutMoveTheQueueOn() {
        val c = XiaomiBleChunker(encrypted = false, cipher = null)
        c.send(hex("01"))
        assertTrue(writes(c.send(hex("02"))).isEmpty())
        val nack = c.onNotification(hex("00000102"))
        assertEquals(XiaomiBleChunker.Output.Sent(false), nack.first())
        assertEquals(listOf("000000000100"), writes(nack))
        val timeout = c.onAckTimeout()
        assertEquals(XiaomiBleChunker.Output.Sent(false), timeout.first())
        assertFalse(c.busy)
        assertTrue(c.onAckTimeout().isEmpty())
    }

    @Test
    fun unsolicitedAcksAndGarbageAreHarmless() {
        val c = XiaomiBleChunker(encrypted = true, cipher = ToyCipher())
        assertTrue(c.onNotification(hex("00000101")).isEmpty())
        assertTrue(c.onNotification(hex("0000010500ff")).isEmpty())
        assertTrue(c.onNotification(ByteArray(0)).isEmpty())
        assertTrue(c.onNotification(hex("00")).isEmpty())
        assertTrue(c.onNotification(hex("0000ff")).isEmpty())
        assertArrayEquals(hex("00000300"), XiaomiBleChunker.PAYLOAD_ACK)
    }
}
