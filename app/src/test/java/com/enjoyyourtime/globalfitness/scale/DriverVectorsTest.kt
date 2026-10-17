package com.enjoyyourtime.globalfitness.scale

import com.enjoyyourtime.globalfitness.scale.ScaleTestSupport.adv
import com.enjoyyourtime.globalfitness.scale.ScaleTestSupport.hex
import com.enjoyyourtime.globalfitness.scale.drivers.HealthKeep280Driver
import com.enjoyyourtime.globalfitness.scale.drivers.HumeDara2Driver
import com.enjoyyourtime.globalfitness.scale.drivers.MgbDriver
import com.enjoyyourtime.globalfitness.scale.drivers.OneByoneDriver
import com.enjoyyourtime.globalfitness.scale.drivers.RenphoES26BBDriver
import com.enjoyyourtime.globalfitness.scale.drivers.RobiS9Driver
import com.enjoyyourtime.globalfitness.scale.drivers.RunstarR6Driver
import com.enjoyyourtime.globalfitness.scale.drivers.SinocareDriver
import com.enjoyyourtime.globalfitness.scale.drivers.VitafitVT701Driver
import com.enjoyyourtime.globalfitness.scale.drivers.YunmaiXDriver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsers and protocol flows checked against openScale's captured frames. */
class DriverVectorsTest {
    @Test
    fun vitafitFrames() {
        assertEquals(8500, VitafitVT701Driver.parseStableWeightRaw(hex("5a 0a 26 10 02 00 00 21 21 34 0a aa")))
        assertNull(VitafitVT701Driver.parseStableWeightRaw(hex("5a 0a 26 10 01 00 00 21 21 43 7e aa")))
        assertEquals(393, VitafitVT701Driver.parseImpedanceOhm(hex("5a 0b 26 11 00 00 00 00 00 01 89 b4 aa")))
        assertEquals(389, VitafitVT701Driver.parseImpedanceOhm(hex("5a 0b 26 11 00 00 00 00 00 01 85 b8 aa")))
        assertNull(VitafitVT701Driver.parseStableWeightRaw(hex("5a 0a 26 10 02 00 00 21 21 34 ff aa")))
        assertNull(VitafitVT701Driver.parseImpedanceOhm(hex("5a 0a 26 10 02 00 00 21 21 34 0a aa")))
        assertArrayEquals(hex("a5 05 26 33 00 10 aa"), VitafitVT701Driver.buildCommand(0x33, byteArrayOf(0x00)))
        assertArrayEquals(hex("a5 04 26 44 66 aa"), VitafitVT701Driver.buildCommand(0x44, byteArrayOf()))
    }

    @Test
    fun mgbStreamingFrames() {
        assertEquals(9910, MgbDriver.parseFinalWeightRaw(hex("AC 02 26 B6 00 00 CA A6")))
        assertNull(MgbDriver.parseFinalWeightRaw(hex("AC 02 26 B6 00 00 CE AA")))
        assertEquals(9910, MgbDriver.parseLiveWeightRaw(hex("AC 02 26 B6 00 00 CE AA")))
        assertEquals(600, MgbDriver.parseImpedanceOhm(hex("AC 02 FD 01 02 58 CB 23")))
        assertNull(MgbDriver.parseImpedanceOhm(hex("AC 02 FD 00 00 00 CB C8")))
        assertNull(MgbDriver.parseImpedanceOhm(hex("AC 02 FD FF 00 00 CB C7")))
        assertNull(MgbDriver.parseImpedanceOhm(hex("AC 02 FD 1A 07 18 CC 02")))
        assertFalse(MgbDriver.isValidStreamingFrame(hex("AC 02 26 B6 00 00 CA FF")))
        assertFalse(MgbDriver.isValidStreamingFrame(ByteArray(20)))
    }

    @Test
    fun robiFinalFrameAndHandshake() {
        assertEquals(77.25f, RobiS9Driver.parseFinalWeightKg(hex("030800a300012dc2000000000000000000000013"))!!, 1e-2f)
        assertNull(RobiS9Driver.parseFinalWeightKg(hex("1d0700a20400012c000000000000000000000013")))
        assertNull(RobiS9Driver.parseFinalWeightKg(hex("0308ffa300012dc2000000000000000000000013")))
        assertEquals(11, RobiS9Driver.HANDSHAKE.size)
        RobiS9Driver.HANDSHAKE.forEachIndexed { i, h -> assertEquals(i, Bytes.fromHex(h)[0].toInt() and 0xFF) }

        val h = ProtocolHarness(RobiS9Driver()).connect()
        assertEquals(11, h.io.writes.size)
        h.notify(uuid16(0xFFB3), "030800a300012dc2000000000000000000000013")
        assertEquals(77.25, h.readings.single().weightKg, 1e-3)
    }

    @Test
    fun runstarR6FinalResult() {
        val r = RunstarR6Driver.decodeFinalResult(hex("f3 08 00 a3 00 01 03 2e 4b 02 45 00 00 00 00 00 00 00 00 07"))!!
        assertEquals(66350, r.grams)
        assertEquals(75, r.heartRate)
        assertEquals(581, r.impedanceOhm)
        val corrupted = hex("f3 08 00 a3 00 01 03 2e 4b 02 45 00 00 00 00 00 00 00 00 1e")
        assertNull(RunstarR6Driver.decodeFinalResult(corrupted))
        assertNull(RunstarR6Driver.decodeFinalResult(ByteArray(19)))
    }

    @Test
    fun renphoEs26bbFrames() {
        val (w, r) = RenphoES26BBDriver.parseLiveFrame(hex("55 AA 14 00 07 01 00 00 1D 83 01 F4 B0"))!!
        assertEquals(7555L, w)
        assertEquals(500, r)
        assertEquals(0, RenphoES26BBDriver.parseLiveFrame(hex("55 AA 14 00 07 11 00 00 1D 83 00 00 CB"))!!.second)
        assertNull(RenphoES26BBDriver.parseLiveFrame(hex("55 AA 14 00 07 00 00 00 1D 83 01 F4 AF")))
        val (ow, or, ago) = RenphoES26BBDriver.parseOfflineRecord(hex("55 AA 15 00 0C 00 00 1D 83 01 F4 00 00 00 1E 00 00 D3"))!!
        assertEquals(7555L, ow)
        assertEquals(500, or)
        assertEquals(30L, ago)
    }

    @Test
    fun humeDara2PublishesTheLockedFrame() {
        val h = ProtocolHarness(HumeDara2Driver()).connect()
        val notify = uuid16(0xFFF4)
        h.notify(notify, "CF 00 00 DE 21 00 00 00 00 01 31") // live stream
        assertTrue(h.readings.isEmpty())
        assertEquals(86.70, h.io.lives.single(), 1e-3)
        h.notify(notify, "CF C4 13 E8 21 E5 E7 96 00 00 45")
        val r = h.readings.single()
        assertEquals(86.80, r.weightKg, 1e-3)
        assertEquals(502, r.impedance) // 501.95 Ω
        assertEquals(FormulaLib.STANDARD_IMPEDANCE, r.formulaLib)
        assertTrue(h.io.disconnected)
    }

    @Test
    fun healthKeep280FinalIndication() {
        val h = ProtocolHarness(HealthKeep280Driver()).connect()
        h.notify(uuid16(0xFFB3), "CF 08 00 A3 00 01 C6 B0 44 01 65 00 00 00 00 00 00 00 00 04")
        val r = h.readings.single()
        assertEquals(116.40, r.weightKg, 1e-2)
        assertEquals(357, r.impedance)
    }

    @Test
    fun oneByoneCoalescedFramesAreLiveNotHistory() {
        val coalesced = hex("CF 76 0C 1E 28 DF D6 07 01 00 8C CF 76 0C 1E 28 DF D6 07 01")
        assertTrue(OneByoneDriver.isLiveFrame(coalesced))
        assertFalse(OneByoneDriver.hasHistoryTimestamp(coalesced))
        assertTrue(OneByoneDriver.hasHistoryTimestamp(hex("CF 60 0E 9C 27 04 02 F0 00 00 11 07 EA 08 0C 09 0B 2B")))
        assertTrue(OneByoneDriver.isFinalReading(hex("CF 50 0F B0 27 10 13 51 00 36 63")))
        assertFalse(OneByoneDriver.isFinalReading(hex("CF 50 0F B0 27 10 13 51 00 01 54")))
        assertTrue(OneByoneDriver.isClassicName("1byone Health Scale"))
        assertFalse(OneByoneDriver.isClassicName("eufy T9146"))

        // Eufy C1: live session, the coalesced final frame is published once with its impedance.
        val h = ProtocolHarness(OneByoneDriver(), adv("eufy T9146")).connect()
        assertArrayEquals(hex("FD 37 00 01 00 00 00 00 00 00 CB"), h.io.writes.first().payload)
        h.notify(uuid16(0xFFF4), coalesced)
        h.notify(uuid16(0xFFF4), coalesced)
        val r = h.readings.single()
        assertEquals(102.70, r.weightKg, 1e-3)
        assertEquals(319, r.impedance)
        assertEquals(FormulaLib.ONE_BYONE, r.formulaLib)
    }

    @Test
    fun oneByoneClassicKeepsWeightOnlyReadings() {
        val h = ProtocolHarness(OneByoneDriver(), adv("Health Scale")).connect()
        h.notify(uuid16(0xFFF4), "CF 00 00 28 28 00 00 00 01 00 CE")
        val r = h.readings.single()
        assertEquals(102.80, r.weightKg, 1e-3)
        assertNull(r.impedance)
    }

    @Test
    fun yunmaiXBroadcastFrames() {
        val mfr = 0x3456
        fun frame(state: Int, raw: Int, impedance: Int = 0): ByteArray {
            val body = byteArrayOf(0x12, 0xEE.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0x0B, 0x74, 0x17, 0x04, state.toByte(), (raw shr 8).toByte(), raw.toByte(), (impedance shr 8).toByte(), impedance.toByte())
            return body + Bytes.xor(body, 7, 13).toByte()
        }
        val final = YunmaiXDriver.parseFrame(mfr, frame(3, 8000, 500), ScaleTestSupport.ADDRESS)!!
        assertEquals(YunmaiXDriver.STATE_FINAL, final.state)
        assertEquals(80.0f, final.weightKg, 1e-3f)
        assertEquals(500, final.impedanceOhm)
        assertNull(YunmaiXDriver.parseFrame(mfr, frame(3, 8000, 500), "AA:BB:CC:DD:EE:FF"))
        assertNull(YunmaiXDriver.parseFrame(mfr, frame(3, 8000, 500).also { it[9] = 0x1C }, ScaleTestSupport.ADDRESS))

        val driver = YunmaiXDriver()
        val env = ScaleTestSupport.env(driver.id)
        val session = driver.newSession(env)
        val out = ArrayList<ScaleReading>()
        for (f in listOf(frame(1, 7955), frame(2, 8000), frame(3, 8000, 500), frame(3, 8000, 500))) {
            out += session.parseAdvertisement(adv(manufacturer = mapOf(mfr to f)), env)
        }
        assertEquals(1, out.size)
        assertEquals(500, out.single().impedance)
        assertEquals(FormulaLib.YUNMAI, out.single().formulaLib)
    }

    @Test
    fun sinocareNeedsNineIdenticalFrames() {
        val data = hex("000000000000CE0000061D0000000001D4")
        assertEquals(7430, SinocareDriver.weightRaw(data))
        val driver = SinocareDriver()
        val env = ScaleTestSupport.env(driver.id)
        val session = driver.newSession(env)
        val out = ArrayList<ScaleReading>()
        repeat(12) { out += session.parseAdvertisement(adv("Weight Scale", manufacturer = mapOf(0x0000 to data)), env) }
        assertEquals(74.30, out.single().weightKg, 1e-3)
    }
}
