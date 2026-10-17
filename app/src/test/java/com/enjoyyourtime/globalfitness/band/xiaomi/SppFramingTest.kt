package com.enjoyyourtime.globalfitness.band.xiaomi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SppFramingTest {
    private val auth = XiaomiAuth((AuthKey.parse("00112233445566778899aabbccddeeff") as AuthKeyResult.Valid).key)

    // ---- V1 ----

    @Test
    fun v1VersionRequestBytes() {
        // preamble, channel 0, flags 0x80|0x40 (needs response), size 3, opcode READ, serial 0, plain, epilogue
        assertEquals("badcfe00c00300000000ef", SppPacketV1.versionRequest().toHexString())
    }

    @Test
    fun v1AuthPacketIsPlainOnTheProtoTxChannel() {
        val frame = SppProtocolV1(auth).encode(SppChannel.Authentication, hex("0801101a"))
        assertEquals("badcfe0280070002000208" + "01101a" + "ef", frame.toHexString())
        val decoded = assertNotNullV1(SppPacketV1.decode(frame))
        assertEquals(SppChannel.ProtobufCommand, decoded.channel)
        assertEquals(SppPacketV1.DATA_TYPE_AUTH, decoded.dataType)
        assertArrayEquals(hex("0801101a"), decoded.payload)
    }

    @Test
    fun v1EncryptedCommandsCarryAnIncrementingCounterAndSerial() {
        val protocol = SppProtocolV1(auth)
        protocol.encode(SppChannel.Authentication, hex("01")) // consumes serial 0, no counter
        val first = assertNotNullV1(SppPacketV1.decode(protocol.encode(SppChannel.ProtobufCommand, hex("0802"))))
        val second = assertNotNullV1(SppPacketV1.decode(protocol.encode(SppChannel.ProtobufCommand, hex("0802"))))
        assertEquals(1, first.frameSerial)
        assertEquals(2, second.frameSerial)
        assertEquals(SppPacketV1.DATA_TYPE_ENCRYPTED, first.dataType)
        // LE counter prefix: 1 then 2, followed by CCM(ciphertext || 4-byte tag)
        assertEquals("0100", first.payload.copyOf(2).toHexString())
        assertEquals("0200", second.payload.copyOf(2).toHexString())
        assertEquals(2 + 2 + XiaomiCrypto.CCM_TAG_LENGTH, first.payload.size)
        assertArrayEquals(auth.encryptV1(hex("0802"), 1), first.payload.copyOfRange(2, first.payload.size))
    }

    @Test
    fun v1DecodeRejectsBadEpilogueAndTruncation() {
        val frame = SppProtocolV1(auth).encode(SppChannel.Authentication, hex("0801"))
        assertNull(SppPacketV1.decode(frame.copyOf(frame.size - 1)))
        val bad = frame.copyOf().also { it[it.size - 1] = 0 }
        assertNull(SppPacketV1.decode(bad))
    }

    @Test
    fun receiveBufferReassemblesSplitFramesAndSkipsGarbage() {
        val protocol = SppProtocolV1(auth)
        val a = protocol.encode(SppChannel.Authentication, hex("0801101a"))
        val b = protocol.encode(SppChannel.Authentication, hex("08011005"))
        val stream = hex("00ff") + a + b
        val buffer = SppReceiveBuffer()

        buffer.append(stream.copyOfRange(0, 7))
        assertNull(buffer.poll(protocol)) // garbage skipped, first frame incomplete
        buffer.append(stream.copyOfRange(7, stream.size - 3))
        val first = buffer.poll(protocol) as SppFrame.V1
        assertArrayEquals(hex("0801101a"), first.packet.payload)
        assertNull(buffer.poll(protocol)) // second frame still incomplete
        buffer.append(stream.copyOfRange(stream.size - 3, stream.size))
        val second = buffer.poll(protocol) as SppFrame.V1
        assertArrayEquals(hex("08011005"), second.packet.payload)
        assertEquals(0, buffer.size)
    }

    // ---- V2 ----

    @Test
    fun crc16ArcCheckValue() {
        assertEquals(0xbb3d, SppPacketV2.crc16Arc("123456789".toByteArray()))
        assertEquals(0, SppPacketV2.crc16Arc(ByteArray(0)))
    }

    @Test
    fun v2DecodesCapturedAckAndNack() {
        // Captured frames quoted in Gadgetbridge's XiaomiSppPacketV2Test / XiaomiSppProtocolV2Test.
        val ack = SppPacketV2.decode(hex("a5a501f200000000"))
        assertTrue(ack is SppPacketV2.Ack)
        assertEquals(0xf2, ack!!.sequenceNumber)
        val nack = SppPacketV2.decode(hex("a5a500f600000000"))
        assertTrue(nack is SppPacketV2.Nack)
        assertEquals(0xf6, nack!!.sequenceNumber)
    }

    @Test
    fun v2RejectsShortTruncatedAndBadChecksum() {
        assertNull(SppPacketV2.decode(hex("a5a501")))
        assertNull(SppPacketV2.decode(hex("a5a503010600 0000")))
        assertNull(SppPacketV2.decode(hex("a5a503010200ffff0102")))
    }

    @Test
    fun v2SessionConfigFrame() {
        val frame = SppProtocolV2(auth).sessionConfigFrame()
        assertEquals("a5a50200" + "1600", frame.copyOf(6).toHexString())
        val decoded = SppPacketV2.decode(frame)
        assertTrue(decoded is SppPacketV2.SessionConfig)
        assertEquals(SppPacketV2.SESSION_START_REQUEST, (decoded as SppPacketV2.SessionConfig).opCode)
        assertEquals(
            "01" + "0103000100 00" .replace(" ", "") + "020200 00fc".replace(" ", "") + "0302002000" + "0402001027",
            frame.copyOfRange(8, frame.size).toHexString(),
        )
    }

    @Test
    fun v2DataFramesUseSequenceNumbersAndChannelOpcodes() {
        val protocol = SppProtocolV2(auth)
        val plain = protocol.nextDataFrame(protocol.dataPayload(SppChannel.Authentication, hex("0801")))
        val encrypted = protocol.nextDataFrame(protocol.dataPayload(SppChannel.ProtobufCommand, hex("0802")))

        val p0 = SppPacketV2.decode(plain) as SppPacketV2.Data
        assertEquals(0, p0.sequenceNumber)
        assertEquals(SppChannel.ProtobufCommand, p0.channel) // raw channel 1 carries auth and commands
        assertEquals(SppPacketV2.OPCODE_SEND_PLAINTEXT, p0.opCode)
        assertArrayEquals(hex("0801"), p0.payload)

        val p1 = SppPacketV2.decode(encrypted) as SppPacketV2.Data
        assertEquals(1, p1.sequenceNumber)
        assertEquals(SppPacketV2.OPCODE_SEND_ENCRYPTED, p1.opCode)
        assertArrayEquals(auth.encryptV2(hex("0802")), p1.payload)
        assertEquals(2, protocol.window.unackedFrames().size)
    }

    @Test
    fun v2ReceiveBufferHandlesBackToBackFramesAndCorruption() {
        val protocol = SppProtocolV2(auth)
        val buffer = SppReceiveBuffer()
        buffer.append(hex("a5a5010800000000" + "a5a503010200ffff0102" + "a5a5001100000000"))
        assertEquals(8, ((buffer.poll(protocol) as SppFrame.V2).packet as SppPacketV2.Ack).sequenceNumber)
        assertSame(SppFrame.Corrupt, buffer.poll(protocol))
        assertEquals(0x11, ((buffer.poll(protocol) as SppFrame.V2).packet as SppPacketV2.Nack).sequenceNumber)
        assertNull(buffer.poll(protocol))
    }

    private fun assertNotNullV1(packet: SppPacketV1?): SppPacketV1 {
        assertNotNull(packet)
        return packet!!
    }
}
