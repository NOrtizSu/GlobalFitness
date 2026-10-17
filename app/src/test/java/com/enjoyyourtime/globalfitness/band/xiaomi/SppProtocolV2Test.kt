package com.enjoyyourtime.globalfitness.band.xiaomi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Go-back-N ack/nack handling, scenarios ported from Gadgetbridge's XiaomiSppProtocolV2Test. */
class SppProtocolV2Test {
    private val auth = XiaomiAuth((AuthKey.parse("00112233445566778899aabbccddeeff") as AuthKeyResult.Valid).key)
    private val protocol = SppProtocolV2(auth)

    private fun sendPackets(count: Int): List<ByteArray> =
        (0 until count).map { protocol.nextDataFrame(protocol.dataPayload(SppChannel.Data, byteArrayOf(it.toByte()))) }

    /** Feeds frames from the band and returns the frames the phone has to resend. */
    private fun receive(hexFrames: String): List<ByteArray> {
        val buffer = SppReceiveBuffer().apply { append(hex(hexFrames)) }
        val resent = mutableListOf<ByteArray>()
        while (true) {
            val frame = buffer.poll(protocol) as? SppFrame.V2 ?: break
            when (val p = frame.packet) {
                is SppPacketV2.Ack -> protocol.onAck(p.sequenceNumber)
                is SppPacketV2.Nack -> resent += protocol.onNack(p.sequenceNumber).resend
                else -> error("unexpected $p")
            }
        }
        return resent
    }

    private fun assertFrames(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertArrayEquals("frame $it", expected[it], actual[it]) }
    }

    @Test
    fun resendsFromThePacketTheBandExpectsAfterANack() {
        val sent = sendPackets(21)

        // Redmi Watch 6 log (Gadgetbridge #6888): acks 8 and 9, then a NACK expecting 17.
        assertFrames(sent.subList(17, 21), receive("A5A5010800000000A5A5010900000000A5A5001100000000"))

        // Then ack 17 and NACK for 18: everything from 18 on is resent, 17 is not.
        assertFrames(sent.subList(18, 21), receive("A5A5011100000000A5A5001200000000"))
    }

    @Test
    fun ignoresANackForAPacketNotPending() {
        sendPackets(3)
        assertTrue(receive("A5A5000300000000").isEmpty())
        assertTrue(protocol.window.isEmpty())
    }

    @Test
    fun resendsEverythingWhenTheFirstPacketWasLost() {
        val sent = sendPackets(3)
        assertFrames(sent, receive("A5A5000000000000"))
    }

    @Test
    fun cumulativeAckReleasesEarlierFrames() {
        sendPackets(5)
        assertTrue(protocol.onAck(2))
        assertEquals(3, protocol.window.oldestUnacked())
        assertFalse(protocol.onAck(2)) // already released
        assertTrue(protocol.onAck(4))
        assertTrue(protocol.window.isEmpty())
    }

    @Test
    fun sequenceNumbersWrapAt256() {
        val window = SppV2SendWindow()
        repeat(255) { window.nextSequenceNumber() }
        assertEquals(255, window.nextSequenceNumber())
        assertEquals(0, window.nextSequenceNumber())
    }

    @Test
    fun windowForgetsFramesBeyondHalfTheSequenceSpace() {
        val window = SppV2SendWindow()
        repeat(SppV2SendWindow.MAX_TRACKED_FRAMES + 2) { window.onSent(window.nextSequenceNumber(), byteArrayOf(it.toByte())) }
        assertEquals(SppV2SendWindow.MAX_TRACKED_FRAMES, window.unackedFrames().size)
        assertEquals(2, window.oldestUnacked())
    }
}
